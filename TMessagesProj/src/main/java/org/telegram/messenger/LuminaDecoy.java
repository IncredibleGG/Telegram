package org.telegram.messenger;

import android.content.Context;

/**
 * LuminaDecoy — process-lifetime state for the "calculator vault" decoy lock.
 *
 * This is a fully local gate that is SEPARATE from Telegram's own passcode. When
 * {@code decoyLockEnabled} is on and a non-empty {@code decoyUnlockCode} is set, a
 * cold launch first shows {@link org.telegram.ui.LuminaCalculatorActivity} (a working
 * calculator). Typing the secret code and pressing "=" flips {@link #unlocked} true
 * for the rest of the process and lets {@link org.telegram.ui.LaunchActivity} proceed
 * into the real app; any other input just computes normally.
 *
 * Everything here is intentionally tiny and defensive: {@link #shouldGate(Context)}
 * can NEVER throw (it swallows any error and returns false) so a misconfiguration can
 * never make the app unopenable — the gate fails OPEN.
 */
public final class LuminaDecoy {

    private LuminaDecoy() {}

    /**
     * True once the correct code has been entered in this process. Volatile because it
     * is written from the calculator Activity and read from LaunchActivity.onCreate.
     * Resets to false on every fresh process (cold launch), which re-arms the lock.
     */
    public static volatile boolean unlocked = false;

    /**
     * Re-arm the gate so the next foreground entry shows the decoy again. Called when the
     * app is backgrounded (LaunchActivity onStop / onUserLeaveHint). Cheap and never throws.
     */
    public static void relock() {
        unlocked = false;
    }

    /** Prefs key: legacy master toggle for the calculator decoy lock (pre-vault users). */
    public static final String KEY_ENABLED = "decoyLockEnabled";
    /** Prefs key: the secret code that unlocks the real app. */
    public static final String KEY_CODE = "decoyUnlockCode";

    // ---- Unified "disguise vault" (vgate) --------------------------------------------
    /** Prefs key: master toggle for the unified disguise vault. */
    public static final String KEY_VAULT_ENABLED = "vaultEnabled";
    /** Prefs key: how the vault presents — {@link #MODE_PASSWORD_DOOR} or {@link #MODE_DECOY_APP}. */
    public static final String KEY_VAULT_MODE = "vaultMode";
    /** Prefs key: which decoy skin to show — {@link #SKIN_NOTEPAD} or {@link #SKIN_CALCULATOR}. */
    public static final String KEY_DECOY_SKIN = "decoySkin";

    /** Vault mode: show a discreet password door first, then route to the decoy on failure. */
    public static final String MODE_PASSWORD_DOOR = "passwordDoor";
    /** Vault mode: jump straight into the decoy skin (the code is entered inside the decoy). */
    public static final String MODE_DECOY_APP = "decoyApp";
    /** Decoy skin: a notepad. */
    public static final String SKIN_NOTEPAD = "notepad";
    /** Decoy skin: a working calculator (the original decoy). */
    public static final String SKIN_CALCULATOR = "calculator";

    /**
     * Whether LaunchActivity should divert to the vault/decoy instead of showing the real
     * UI. True ONLY when the vault (or the legacy calculator lock) is enabled, a non-empty
     * code is configured and this process has not been unlocked yet. Fail-open: any
     * exception (e.g. prefs not ready) is swallowed and returns false so the real app
     * always launches.
     *
     * @param context launch context (unused today; kept so the gate can read prefs
     *                directly should LuminaConfig ever be unavailable).
     */
    public static boolean shouldGate(Context context) {
        if (unlocked) {
            return false;
        }
        return isConfigured();
    }

    /**
     * Whether the vault (or the legacy calculator lock) is armed at all — enabled with a
     * non-empty code — regardless of whether this process has been unlocked yet. This is
     * {@link #shouldGate(Context)} without the per-process {@link #unlocked} check. Fail-open:
     * any error returns false.
     */
    public static boolean isConfigured() {
        try {
            boolean vaultEnabled = LuminaConfig.getBoolean(KEY_VAULT_ENABLED, false);
            boolean legacyEnabled = LuminaConfig.getBoolean(KEY_ENABLED, false);
            if (!vaultEnabled && !legacyEnabled) {
                return false;
            }
            String code = LuminaConfig.getString(KEY_CODE, "");
            return code != null && code.length() > 0;
        } catch (Throwable ignore) {
            // Never block launch because of a config/prefs error.
            return false;
        }
    }

    /**
     * Keep the real app out of the Recents screen while the vault is armed (Android 13+).
     *
     * The vault promises that the real app is only shown after the code. But the system keeps
     * a screenshot of the last thing on screen for the Recents card and, when the task is
     * brought back from Recents, as the window shown while the app comes back; after an
     * unlocked session that screenshot is the real chat list. With recents screenshots
     * disabled the system uses a plain placeholder built from the window background instead.
     * Screenshots taken by the user inside the app are not affected. Called from
     * LaunchActivity.onResume and whenever a vault setting changes; re-enables screenshots as
     * soon as the vault is off. Users who never arm the vault never get any call at all.
     * Android 12L and older have no public equivalent; there the Recents card keeps the last
     * screenshot as before. Never throws.
     */
    public static void applyRecentsPrivacy(android.app.Activity activity) {
        if (activity == null || android.os.Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            if (isConfigured()) {
                activity.setRecentsScreenshotEnabled(false);
                recentsPrivacyUsed = true;
            } else if (recentsPrivacyUsed) {
                activity.setRecentsScreenshotEnabled(true);
            }
        } catch (Throwable ignore) {
        }
    }

    /** True once this process has disabled recents screenshots, so turning the vault off restores them. */
    private static volatile boolean recentsPrivacyUsed = false;

    /**
     * How the vault should present. When the unified vault is enabled, this returns the
     * configured {@link #KEY_VAULT_MODE} (defaulting to {@link #MODE_PASSWORD_DOOR}).
     * Otherwise (legacy {@code decoyLockEnabled}-only users, no {@code vaultEnabled}) it
     * returns {@link #MODE_DECOY_APP} to preserve the original calculator-vault behavior
     * exactly. Fail-open with safe defaults: any error yields {@link #MODE_DECOY_APP}.
     */
    public static String resolveVaultMode(Context context) {
        try {
            if (LuminaConfig.getBoolean(KEY_VAULT_ENABLED, false)) {
                String m = LuminaConfig.getString(KEY_VAULT_MODE, MODE_PASSWORD_DOOR);
                return (m == null || m.length() == 0) ? MODE_PASSWORD_DOOR : m;
            }
        } catch (Throwable ignore) {
        }
        return MODE_DECOY_APP;
    }

    /**
     * Which decoy skin to display. When the unified vault is enabled, this returns the
     * configured {@link #KEY_DECOY_SKIN} (defaulting to {@link #SKIN_NOTEPAD}). Otherwise
     * (legacy users) it returns {@link #SKIN_CALCULATOR}, preserving the original decoy.
     * Fail-open with safe defaults: any error yields {@link #SKIN_CALCULATOR}.
     */
    public static String resolveDecoySkin(Context context) {
        try {
            if (LuminaConfig.getBoolean(KEY_VAULT_ENABLED, false)) {
                String s = LuminaConfig.getString(KEY_DECOY_SKIN, SKIN_NOTEPAD);
                return (s == null || s.length() == 0) ? SKIN_NOTEPAD : s;
            }
        } catch (Throwable ignore) {
        }
        return SKIN_CALCULATOR;
    }

    /**
     * Flip the process to unlocked and hand control to {@link org.telegram.ui.LaunchActivity},
     * which then proceeds into the real app. Used by the password door (correct code) and as
     * the fail-open escape hatch when a decoy/door UI cannot be built. Fully guarded: it sets
     * {@link #unlocked} first (so a failed relaunch still leaves the process unlocked) and
     * never throws.
     *
     * @param a the current decoy/door Activity to finish after relaunching.
     */
    public static void unlockAndProceed(android.app.Activity a) {
        unlocked = true;
        try {
            android.content.Intent intent =
                    new android.content.Intent(a, org.telegram.ui.LaunchActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            a.startActivity(intent);
        } catch (Throwable ignore) {
        }
        try {
            a.finish();
        } catch (Throwable ignore) {
        }
    }
}
