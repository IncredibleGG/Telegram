package org.telegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LuminaDuress — the duress code (脅迫密碼).
 *
 * A separate, purely local code (never the Telegram passcode, never the vault's secret code)
 * that, typed EXACTLY on one of LuminaGram's password screens, runs the action the user picked
 * in {@link org.telegram.ui.LuminaDisguiseActivity} instead of unlocking:
 * <ul>
 *   <li>{@link #MODE_LOGOUT_CURRENT}: log out the account in use;</li>
 *   <li>{@link #MODE_LOGOUT_ALL}: log out every account logged in on this device;</li>
 *   <li>{@link #MODE_EXIT}: close the app without logging out (the old "exit immediately"
 *       mode; passcode lock screen only, exactly as before).</li>
 * </ul>
 * A logout is the app's own one: {@link MessagesController#performLogout(int)} with type 1, the
 * same call as Settings &gt; Log out (unregister push, server {@code auth.logOut}, local config +
 * database wipe). The local logout never needs the network. The server part does, so it is
 * followed up until the server has answered:
 * <ul>
 *   <li>the passcode screen only ends the process after every account's {@code auth.logOut}
 *       was answered; when that does not happen in time (offline, slow proxy, still
 *       reconnecting) the process is left running with the window closed, so the request,
 *       still queued in this process, goes out as soon as the network is back;</li>
 *   <li>each account waiting for that answer is also flagged on disk, and if the process ends
 *       before the answer came, the next start sends {@code auth.logOut} for it again
 *       ({@link #resumePendingServerLogouts()}), unless that slot was logged in again since.</li>
 * </ul>
 * A duress logout does not keep the {@code future_auth_token} the server may return: with it,
 * this device could log the account back in without a login code, which is exactly what a
 * duress logout must not leave behind (the settings warn that a login code is needed).
 *
 * Only an exact match of the non-empty configured code fires. Typos and every other wrong code
 * go through the normal wrong-code handling, and the real passcode / vault code is always
 * checked first, so neither can ever be swallowed by the duress code. That holds even when the
 * passcode was later changed to the duress code: then the duress code simply does nothing.
 *
 * Where it is checked:
 * <ul>
 *   <li>{@code PasscodeView} (Telegram's passcode lock): runs the action, then closes the app.</li>
 *   <li>{@code LuminaVaultDoorActivity}, {@code LuminaCalculatorActivity} ("=") and
 *       {@code LuminaNotepadActivity} (title long-press): runs a logout mode, then the caller
 *       carries on exactly as for any other wrong code, so the decoy stays on screen.</li>
 * </ul>
 *
 * Migration: the fake-crash era stored {@code fakeCrashEnabled} + {@code fakeCrashMode}
 * ("dialog" / "exit"). The mode now lives under its own key, so anyone who had the old feature
 * on starts OFF and has to pick a mode again (a logout mode only through the warning dialog);
 * the old keys are dropped. The code itself keeps its stored key, so it carries over.
 *
 * The code is never logged.
 */
public final class LuminaDuress {

    private LuminaDuress() {}

    /** Prefs key: the selected mode; absent means {@link #MODE_OFF}. */
    public static final String KEY_MODE = "duressMode";
    /** Prefs key: the duress code. Same stored key as the fake-crash era, so the code is kept. */
    public static final String KEY_CODE = "fakeCrashCode";
    // Fake-crash era keys, removed on first read (see the class comment).
    private static final String LEGACY_KEY_ENABLED = "fakeCrashEnabled";
    private static final String LEGACY_KEY_MODE = "fakeCrashMode";

    public static final String MODE_OFF = "off";
    public static final String MODE_LOGOUT_CURRENT = "logoutCurrent";
    public static final String MODE_LOGOUT_ALL = "logoutAll";
    public static final String MODE_EXIT = "exit";

    /** {@link #clashOf(String)}: the code clashes with nothing. */
    public static final int CLASH_NONE = 0;
    /** {@link #clashOf(String)}: the code is the Telegram passcode. */
    public static final int CLASH_PASSCODE = 1;
    /** {@link #clashOf(String)}: the code is the disguise vault's secret code. */
    public static final int CLASH_VAULT = 2;

    // After a logout on the passcode screen the window closes at once. The process is ended
    // once every account's auth.logOut was answered and the local wipe has run, plus a short
    // grace for the logout callback's own follow-up (tgnet cleanup). Without an answer within
    // CLOSE_MAX_WAIT_MS the process is left running: the request is still queued in it.
    private static final long CLOSE_MAX_WAIT_MS = 15000;
    private static final long CLOSE_GRACE_MS = 500;
    private static final long CLOSE_POLL_MS = 250;

    // Accounts whose server logout is not confirmed yet survive a process end in this prefs
    // file (one flag per account slot). Its own file, not LuminaConfig, so a settings backup
    // never carries it to another install.
    private static final String PENDING_PREFS = "lumina_duress_pending";
    private static final String PENDING_KEY_PREFIX = "serverLogout_";

    private static final Object pendingLock = new Object();
    // Accounts whose duress auth.logOut this process sent and has not seen answered yet.
    private static final HashSet<Integer> awaitingServer = new HashSet<>();

    /** The configured mode, one of the MODE_* values. Never throws; errors read as OFF. */
    public static String getMode() {
        try {
            dropLegacyKeys();
            String mode = LuminaConfig.getString(KEY_MODE, MODE_OFF);
            if (MODE_LOGOUT_CURRENT.equals(mode) || MODE_LOGOUT_ALL.equals(mode) || MODE_EXIT.equals(mode)) {
                return mode;
            }
        } catch (Throwable ignore) {
        }
        return MODE_OFF;
    }

    /** Persist the mode. Callers must have shown the warning before passing a logout mode. */
    public static void setMode(String mode) {
        if (!MODE_LOGOUT_CURRENT.equals(mode) && !MODE_LOGOUT_ALL.equals(mode) && !MODE_EXIT.equals(mode)) {
            mode = MODE_OFF;
        }
        LuminaConfig.putString(KEY_MODE, mode);
    }

    public static boolean isEnabled() {
        return !MODE_OFF.equals(getMode());
    }

    public static boolean isLogoutMode(String mode) {
        return MODE_LOGOUT_CURRENT.equals(mode) || MODE_LOGOUT_ALL.equals(mode);
    }

    /** The stored code, trimmed on save; empty when not set. */
    public static String getCode() {
        try {
            String code = LuminaConfig.getString(KEY_CODE, "");
            return code == null ? "" : code;
        } catch (Throwable ignore) {
            return "";
        }
    }

    public static void setCode(String code) {
        LuminaConfig.putString(KEY_CODE, code == null ? "" : code.trim());
    }

    /**
     * True only when the feature is on and {@code entered} EXACTLY equals the non-empty
     * configured code. Never throws.
     */
    public static boolean matches(String entered) {
        try {
            if (entered == null || entered.length() == 0 || !isEnabled()) {
                return false;
            }
            String code = getCode();
            return code.length() > 0 && code.equals(entered);
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Whether {@code code} (a candidate or the stored duress code) is the Telegram passcode
     * ({@link #CLASH_PASSCODE}) or the vault's secret code ({@link #CLASH_VAULT}); the duress
     * code must be neither. Either can be set to the duress code after it was saved, and a code
     * kept from the fake-crash era was never checked, so settings re-check before arming.
     */
    public static int clashOf(String code) {
        if (code == null || code.length() == 0) {
            return CLASH_NONE;
        }
        if (isTelegramPasscode(code)) {
            return CLASH_PASSCODE;
        }
        try {
            String vaultCode = LuminaConfig.getString(LuminaDecoy.KEY_CODE, "");
            if (vaultCode != null && vaultCode.length() > 0 && vaultCode.equals(code)) {
                return CLASH_VAULT;
            }
        } catch (Throwable ignore) {
        }
        return CLASH_NONE;
    }

    /**
     * Whether {@code code} unlocks Telegram's passcode lock. Any error answers true: a caller
     * that would log out must then not do it.
     */
    private static boolean isTelegramPasscode(String code) {
        try {
            return code != null && code.length() > 0
                    && SharedConfig.passcodeHash != null && SharedConfig.passcodeHash.length() > 0
                    && SharedConfig.checkPasscode(code);
        } catch (Throwable ignore) {
            return true;
        }
    }

    /**
     * Restoring an encrypted backup must never arm a logout mode behind the user's back: that
     * only happens through the warning dialog in settings. Used by {@link LuminaConfig#importAll}.
     */
    public static boolean blocksImport(String key, String value) {
        return KEY_MODE.equals(key) && isLogoutMode(value);
    }

    /**
     * The duress code was typed on Telegram's passcode lock (the caller already made sure it
     * is not the real passcode). Logout modes log out, then close the app; {@link #MODE_EXIT}
     * closes the app exactly as the old exit mode did.
     */
    public static void onPasscodeScreenMatch(Activity activity) {
        String mode = getMode();
        if (isLogoutMode(mode)) {
            ArrayList<Integer> accounts = logout(mode);
            closeAppAfterLogout(activity, accounts);
        } else {
            exitApp(activity);
        }
    }

    /**
     * Vault password door / decoy calculator / decoy notepad: call with every entry that did
     * NOT unlock the vault. When it is the duress code and a logout mode is on, the logout runs
     * here; either way the caller then carries on exactly as for any other wrong entry, so the
     * decoy stays on screen. {@link #MODE_EXIT} does nothing here, as before. The real Telegram
     * passcode never logs out here, even if the duress code was later made equal to it.
     * Never throws.
     */
    public static void onVaultEntry(String entered) {
        try {
            if (!matches(entered)) {
                return;
            }
            String mode = getMode();
            if (!isLogoutMode(mode)) {
                return;
            }
            // The vault runs before LaunchActivity initialises the app; the passcode settings
            // are only loaded once it is.
            try {
                ApplicationLoader.postInitApplication();
            } catch (Throwable ignore) {
            }
            if (isTelegramPasscode(entered)) {
                return;
            }
            logout(mode);
        } catch (Throwable ignore) {
            // The decoy must keep behaving like an ordinary app whatever happens here.
        }
    }

    /**
     * Log out the accounts the mode covers through the app's own logout path. The vault runs
     * before LaunchActivity initialises the app, so make sure it is initialised first (no-op
     * when it already is). Each account is flagged as waiting for the server first (in memory
     * and on disk), see {@link #onServerLogoutDone}. Returns the accounts that were logged out.
     */
    private static ArrayList<Integer> logout(String mode) {
        ArrayList<Integer> accounts = new ArrayList<>();
        try {
            ApplicationLoader.postInitApplication();
        } catch (Throwable ignore) {
        }
        try {
            if (MODE_LOGOUT_ALL.equals(mode)) {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        accounts.add(a);
                    }
                }
            } else if (MODE_LOGOUT_CURRENT.equals(mode)) {
                int a = UserConfig.selectedAccount;
                if (a >= 0 && a < UserConfig.MAX_ACCOUNT_COUNT && UserConfig.getInstance(a).isClientActivated()) {
                    accounts.add(a);
                }
            }
        } catch (Throwable ignore) {
        }
        for (int i = 0; i < accounts.size(); i++) {
            final int account = accounts.get(i);
            markPendingOnDisk(account);
            synchronized (pendingLock) {
                awaitingServer.add(account);
            }
            try {
                // type 1 = full logout, the same call as Settings > Log out.
                MessagesController.getInstance(account).performLogout(1);
            } catch (Throwable ignore) {
                // One failing account must not stop the others. Its flag on disk stays: the
                // next start finishes the server part if the local logout went through.
                synchronized (pendingLock) {
                    awaitingServer.remove(account);
                }
            }
        }
        return accounts;
    }

    /**
     * Called from the auth.logOut callback of {@link MessagesController#performLogout(int)}
     * (type 1, any caller) once the request is done for {@code account}. Returns true when
     * it was a duress logout, so the caller does not keep the future-auth token. A normal
     * logout only costs a read of an empty prefs file. Never throws.
     */
    public static boolean onServerLogoutDone(int account, TLObject response, TLRPC.TL_error error) {
        boolean duress = false;
        try {
            synchronized (pendingLock) {
                duress = awaitingServer.remove(account);
            }
            SharedPreferences prefs = pendingPrefs();
            if (prefs != null && prefs.getBoolean(pendingKey(account), false)) {
                duress = true;
                if (serverAnswered(response, error)) {
                    prefs.edit().remove(pendingKey(account)).apply();
                }
            }
        } catch (Throwable ignore) {
        }
        return duress;
    }

    /**
     * App start ({@link ApplicationLoader#postInitApplication()}, after every account's config
     * and connection are set up): send auth.logOut again for each duress logout whose answer
     * never came because the process ended first, e.g. while offline. tgnet.dat still holds
     * that slot's authorisation, but the account itself is logged out locally, so it goes
     * without login, like the login requests. A slot that was logged in again since is a new
     * session and is never touched: its flag is just dropped. Returns at once when there is
     * nothing pending. Never throws.
     */
    public static void resumePendingServerLogouts() {
        try {
            SharedPreferences prefs = pendingPrefs();
            if (prefs == null || prefs.getAll().isEmpty()) {
                return;
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                final String key = pendingKey(a);
                if (!prefs.getBoolean(key, false)) {
                    continue;
                }
                if (UserConfig.getInstance(a).isClientActivated()) {
                    prefs.edit().remove(key).apply();
                    continue;
                }
                final int account = a;
                // Server errors fail instead of being retried later in this process: a late
                // retry must never land after a new login on this slot. The flag then stays, and
                // the next start checks the slot again before retrying.
                ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_auth_logOut(), (response, error) -> {
                    try {
                        if (!serverAnswered(response, error)) {
                            return; // Keep the flag: the next start tries again.
                        }
                        if (!UserConfig.getInstance(account).isClientActivated()) {
                            // The same local follow-up as the app's own logout callback.
                            ConnectionsManager.getInstance(account).cleanup(false);
                        }
                        SharedPreferences p = pendingPrefs();
                        if (p != null) {
                            p.edit().remove(pendingKey(account)).apply();
                        }
                    } catch (Throwable ignore) {
                    }
                }, ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors);
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * Whether the server itself answered auth.logOut for good: a result, or a 4xx error such as
     * 401 (the authorisation is already gone). Flood waits, server errors and errors made up
     * locally (negative codes, e.g. a request dropped by a connection cleanup) are not.
     */
    private static boolean serverAnswered(TLObject response, TLRPC.TL_error error) {
        if (response != null) {
            return true;
        }
        return error != null && error.code >= 400 && error.code < 500 && error.code != 420;
    }

    private static SharedPreferences pendingPrefs() {
        Context context = ApplicationLoader.applicationContext;
        return context == null ? null : context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE);
    }

    private static String pendingKey(int account) {
        return PENDING_KEY_PREFIX + account;
    }

    private static void markPendingOnDisk(int account) {
        try {
            SharedPreferences prefs = pendingPrefs();
            if (prefs != null) {
                // commit, not apply: the flag must be on disk before the logout starts.
                prefs.edit().putBoolean(pendingKey(account), true).commit();
            }
        } catch (Throwable ignore) {
        }
    }

    private static boolean anyAwaitingServer(ArrayList<Integer> accounts) {
        synchronized (pendingLock) {
            for (int i = 0; i < accounts.size(); i++) {
                if (awaitingServer.contains(accounts.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Close the app at once, exactly as the old exit mode did. */
    private static void exitApp(Activity activity) {
        try {
            if (activity != null) {
                activity.finishAffinity();
            }
        } catch (Throwable ignore) {
        }
        System.exit(0);
    }

    /**
     * Close the window at once (what an onlooker sees is the same as the old exit). The process
     * is ended only once the server answered every logged-out account's auth.logOut and its
     * local wipe has run, plus {@link #CLOSE_GRACE_MS}. If that has not happened within
     * {@link #CLOSE_MAX_WAIT_MS} (offline, slow or proxied connection, still reconnecting) the
     * process is left running with no window, so the queued request goes out when the network
     * is back; the flag on disk covers the process ending before that. If any screen of the app
     * is opened again in the meantime the process is left alone.
     */
    private static void closeAppAfterLogout(Activity activity, ArrayList<Integer> accounts) {
        final boolean[] reopened = {false};
        Application.ActivityLifecycleCallbacks watcher = null;
        Application app = null;
        try {
            app = (Application) ApplicationLoader.applicationContext;
            watcher = new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityStarted(Activity a) {
                    reopened[0] = true;
                }

                @Override
                public void onActivityCreated(Activity a, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityResumed(Activity a) {
                }

                @Override
                public void onActivityPaused(Activity a) {
                }

                @Override
                public void onActivityStopped(Activity a) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity a, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity a) {
                }
            };
            app.registerActivityLifecycleCallbacks(watcher);
        } catch (Throwable ignore) {
            watcher = null;
        }
        try {
            if (activity != null) {
                activity.finishAffinity();
            }
        } catch (Throwable ignore) {
        }

        final AtomicInteger pendingWipes = new AtomicInteger(accounts.size());
        for (int i = 0; i < accounts.size(); i++) {
            try {
                // performLogout queued the database wipe on this queue; this runs after it.
                MessagesStorage.getInstance(accounts.get(i)).getStorageQueue().postRunnable(pendingWipes::decrementAndGet);
            } catch (Throwable ignore) {
                pendingWipes.decrementAndGet();
            }
        }

        final long start = SystemClock.elapsedRealtime();
        final long[] doneAt = {0};
        final Application finalApp = app;
        final Application.ActivityLifecycleCallbacks finalWatcher = watcher;
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                boolean done = pendingWipes.get() <= 0 && !anyAwaitingServer(accounts);
                if (done && doneAt[0] == 0) {
                    doneAt[0] = now;
                }
                boolean exit = !reopened[0] && done && now - doneAt[0] >= CLOSE_GRACE_MS;
                boolean stop = exit || reopened[0] || now - start >= CLOSE_MAX_WAIT_MS;
                if (!stop) {
                    AndroidUtilities.runOnUIThread(this, CLOSE_POLL_MS);
                    return;
                }
                try {
                    if (finalApp != null && finalWatcher != null) {
                        finalApp.unregisterActivityLifecycleCallbacks(finalWatcher);
                    }
                } catch (Throwable ignore) {
                }
                if (exit) {
                    System.exit(0);
                }
            }
        }, CLOSE_POLL_MS);
    }

    private static void dropLegacyKeys() {
        if (LuminaConfig.contains(LEGACY_KEY_ENABLED) || LuminaConfig.contains(LEGACY_KEY_MODE)) {
            LuminaConfig.remove(LEGACY_KEY_ENABLED);
            LuminaConfig.remove(LEGACY_KEY_MODE);
        }
    }
}
