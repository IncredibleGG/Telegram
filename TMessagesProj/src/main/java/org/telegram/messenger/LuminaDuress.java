package org.telegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.ArrayList;
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
 * same call as Settings &gt; Log out (unregister push, server {@code auth.logOut} when online,
 * local config + database wipe). Offline the request cannot reach the server, but the local
 * logout still completes.
 *
 * Only an exact match of the non-empty configured code fires. Typos and every other wrong code
 * go through the normal wrong-code handling, and the real passcode / vault code is always
 * checked first, so neither can ever be swallowed by the duress code.
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

    // After a logout on the passcode screen the window closes at once, but the process is only
    // ended once the local wipe has run and auth.logOut had a moment to leave the device.
    private static final long CLOSE_MIN_WAIT_MS = 3000;
    private static final long CLOSE_MAX_WAIT_MS = 8000;
    private static final long CLOSE_POLL_MS = 250;

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
     * decoy stays on screen. {@link #MODE_EXIT} does nothing here, as before. Never throws.
     */
    public static void onVaultEntry(String entered) {
        try {
            if (!matches(entered)) {
                return;
            }
            String mode = getMode();
            if (isLogoutMode(mode)) {
                logout(mode);
            }
        } catch (Throwable ignore) {
            // The decoy must keep behaving like an ordinary app whatever happens here.
        }
    }

    /**
     * Log out the accounts the mode covers through the app's own logout path. The vault runs
     * before LaunchActivity initialises the app, so make sure it is initialised first (no-op
     * when it already is). Returns the accounts that were logged out.
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
            try {
                // type 1 = full logout, the same call as Settings > Log out.
                MessagesController.getInstance(accounts.get(i)).performLogout(1);
            } catch (Throwable ignore) {
                // One failing account must not stop the others.
            }
        }
        return accounts;
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
     * Close the window at once (what an onlooker sees is the same as the old exit), then end the
     * process once every logged-out account's database wipe has run and at least
     * {@link #CLOSE_MIN_WAIT_MS} have passed, so the queued auth.logOut can reach the server when
     * online, but never later than {@link #CLOSE_MAX_WAIT_MS}. If any screen of the app is opened
     * again in the meantime the process is left alone.
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
        final Application finalApp = app;
        final Application.ActivityLifecycleCallbacks finalWatcher = watcher;
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                long waited = SystemClock.elapsedRealtime() - start;
                if (waited < CLOSE_MIN_WAIT_MS || (pendingWipes.get() > 0 && waited < CLOSE_MAX_WAIT_MS)) {
                    AndroidUtilities.runOnUIThread(this, CLOSE_POLL_MS);
                    return;
                }
                try {
                    if (finalApp != null && finalWatcher != null) {
                        finalApp.unregisterActivityLifecycleCallbacks(finalWatcher);
                    }
                } catch (Throwable ignore) {
                }
                if (!reopened[0]) {
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
