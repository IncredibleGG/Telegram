package org.telegram.messenger;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;

import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LauncherIconController;

/**
 * LuminaDisguiseController — switches the launcher icon + app name between the real
 * LuminaGram identity and a camouflage "app" (Calculator / Notes / Clock).
 *
 * Each preset maps to one {@code <activity-alias>} in AndroidManifest.xml and to one
 * {@link LauncherIconController.LauncherIcon} enum entry. Reusing that enum matters:
 * {@link LauncherIconController#tryFixLauncherIconIfNeeded()} runs on every cold start
 * and re-enables the real DEFAULT icon whenever it finds no enum alias enabled, so a
 * disguise alias that is NOT an enum member would be silently un-hidden on restart.
 *
 * {@link #applyDisguise(Context, String)} enables exactly the chosen alias and disables
 * every sibling, guaranteeing that precisely one launcher component stays enabled and
 * that the real icon is never revealed behind an active disguise.
 *
 * The camouflage aliases target {@link org.telegram.ui.LuminaDisguiseLaunchActivity} (a neutral
 * trampoline in front of LaunchActivity) so a disguised launch never shows the LuminaGram splash
 * logo, and {@link #taskDescription} / {@link #applyTaskIdentity} keep the Recents label + icon
 * on the camouflage as well.
 */
public final class LuminaDisguiseController {

    public static final String PRESET_DEFAULT = "default";
    public static final String PRESET_CALCULATOR = "calculator";
    public static final String PRESET_NOTES = "notes";
    public static final String PRESET_CLOCK = "clock";

    /** LuminaConfig string key holding the active preset id. */
    public static final String KEY_PRESET = "disguisePreset";
    /** LuminaConfig boolean key for the master disguise switch. */
    public static final String KEY_ENABLED = "disguiseEnabled";

    /**
     * Legacy LuminaConfig boolean key from the old LuminaSecurityActivity "Disguise" switch, which
     * drove the CalculatorIcon alias directly. Kept only so {@link #migrateStaleDisguiseToggle} can
     * fold it into this preset system; no code should read it as a live toggle any more.
     */
    public static final String KEY_LEGACY_DISGUISE_ICON = "disguiseIcon";

    private LuminaDisguiseController() {}

    /** Map a preset id to its launcher alias; null / unknown / "default" -> the real DEFAULT icon. */
    public static LauncherIconController.LauncherIcon iconForPreset(String presetId) {
        if (PRESET_CALCULATOR.equals(presetId)) {
            return LauncherIconController.LauncherIcon.CALCULATOR;
        }
        if (PRESET_NOTES.equals(presetId)) {
            return LauncherIconController.LauncherIcon.NOTES;
        }
        if (PRESET_CLOCK.equals(presetId)) {
            return LauncherIconController.LauncherIcon.CLOCK;
        }
        return LauncherIconController.LauncherIcon.DEFAULT;
    }

    /**
     * The disguise preset whose launcher icon + name match a {@link LuminaDecoy} decoy skin,
     * so that turning the vault on can make the home-screen entry look like the decoy app the
     * user will actually see ({@code notepad} -> Notes, {@code calculator} -> Calculator).
     *
     * Notepad is the fallback because it is also {@code decoySkin}'s default; this never
     * returns {@link #PRESET_DEFAULT}, since a decoy skin always has a camouflage icon.
     */
    public static String presetForDecoySkin(String skin) {
        return LuminaDecoy.SKIN_CALCULATOR.equals(skin) ? PRESET_CALCULATOR : PRESET_NOTES;
    }

    /**
     * Apply the disguise for {@code presetId}: enable exactly its launcher alias and disable
     * every sibling (the other disguise aliases AND the icon-pack aliases), leaving precisely
     * one launcher component enabled. A null / unknown preset and the "default" preset restore
     * the real LuminaGram icon and name.
     *
     * The target alias is enabled first, so there is never an instant with zero launcher
     * entries; siblings are disabled only after the target is confirmed enabled.
     */
    public static void applyDisguise(Context context, String presetId) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context == null) {
            return;
        }
        final PackageManager pm = context.getPackageManager();
        final LauncherIconController.LauncherIcon target = iconForPreset(presetId);

        try {
            pm.setComponentEnabledSetting(target.getComponentName(context),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            // Could not enable the target: leave the current launcher state untouched rather
            // than risk disabling every alias and ending up with zero launcher entries.
            return;
        }
        for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
            if (icon == target) {
                continue;
            }
            try {
                pm.setComponentEnabledSetting(icon.getComponentName(context),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            } catch (Exception ignored) {
            }
        }
        // Called from the settings page (context = LaunchActivity): make the running task's
        // Recents label + icon follow the new launcher identity right away. The callers store
        // the new preset before applying it, so activeDisguise() already sees it.
        // (The Activity check comes first so the cold-start call with the application context
        // never even touches the LaunchActivity class.)
        if (context instanceof Activity && context instanceof LaunchActivity) {
            try {
                ((LaunchActivity) context).refreshLuminaTaskIdentity();
            } catch (Throwable ignore) {
            }
        }
    }

    // ---- Recents identity ------------------------------------------------------------

    /**
     * The camouflage alias (Calculator / Notes / Clock) currently presented on the launcher, or
     * null when the launcher shows the real LuminaGram identity (disguise off, or the Default
     * preset). Read from the persisted disguise state, which
     * {@link LauncherIconController#tryFixLauncherIconIfNeeded()} enforces on the aliases at
     * every cold start. Never throws.
     */
    public static LauncherIconController.LauncherIcon activeDisguise() {
        try {
            if (!LuminaConfig.getBoolean(KEY_ENABLED, false)) {
                return null;
            }
            LauncherIconController.LauncherIcon icon =
                    iconForPreset(LuminaConfig.getString(KEY_PRESET, PRESET_DEFAULT));
            return icon != null && icon.isDisguise() ? icon : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static LauncherIconController.LauncherIcon cachedIdentityIcon;
    private static String cachedIdentityLabel;
    private static Bitmap cachedIdentityBitmap;

    /**
     * The TaskDescription an activity of our task should publish, so the Recents screen shows
     * the camouflage instead of LuminaGram.
     *
     * Recents takes a task's label and icon from its TaskDescription and only falls back to the
     * task's launch component when those are null. That fallback is NOT reliably the camouflage
     * alias: once the activity that started the task finishes (the vault diverting to its door /
     * decoy, or the disguise trampoline handing over), older Android versions re-base the task
     * on the next activity, and a launch from a notification never had the alias at all. All of
     * those resolve to the application's own label and icon, i.e. "LuminaGram". So while a
     * camouflage alias is active the label and icon are set explicitly from that alias (read
     * from the PackageManager, so they always match what the launcher shows). The icon is a
     * bitmap because the Recents screen ignores resource icons in a TaskDescription.
     *
     * Without an active camouflage the label and icon are null, which is exactly the
     * TaskDescription LaunchActivity always published, so non-disguised behaviour is unchanged.
     *
     * @param primaryColor the task colour the caller already uses (0 = none).
     */
    @SuppressWarnings("deprecation")
    public static ActivityManager.TaskDescription taskDescription(Context context, int primaryColor) {
        String label = null;
        Bitmap icon = null;
        LauncherIconController.LauncherIcon disguise = activeDisguise();
        if (disguise != null && context != null) {
            loadIdentity(context, disguise);
            if (cachedIdentityIcon == disguise) {
                label = cachedIdentityLabel;
                icon = cachedIdentityBitmap;
            }
        }
        if (primaryColor != 0 && Color.alpha(primaryColor) != 255) {
            primaryColor = 0; // TaskDescription rejects translucent primary colours
        }
        return new ActivityManager.TaskDescription(label, icon, primaryColor);
    }

    /**
     * For the vault door and the decoys: publish the camouflage label + icon (see
     * {@link #taskDescription}) and use the camouflage name as the window title (what
     * accessibility services announce). Does nothing at all while no camouflage alias is
     * active, so those screens keep their previous behaviour then. Never throws.
     */
    public static void applyTaskIdentity(Activity activity) {
        if (activity == null || activeDisguise() == null) {
            return;
        }
        try {
            int color = 0;
            TypedArray a = activity.getTheme().obtainStyledAttributes(new int[]{android.R.attr.colorPrimary});
            try {
                color = a.getColor(0, 0);
            } finally {
                a.recycle();
            }
            ActivityManager.TaskDescription description = taskDescription(activity, color);
            activity.setTaskDescription(description);
            if (description.getLabel() != null) {
                activity.setTitle(description.getLabel());
            }
        } catch (Throwable ignore) {
        }
    }

    /** Load (once per alias) the alias's launcher label and icon. Leaves the cache untouched on failure. */
    private static void loadIdentity(Context context, LauncherIconController.LauncherIcon disguise) {
        if (cachedIdentityIcon == disguise) {
            return;
        }
        try {
            PackageManager pm = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 24
                    ? PackageManager.MATCH_DISABLED_COMPONENTS
                    : PackageManager.GET_DISABLED_COMPONENTS;
            ActivityInfo info = pm.getActivityInfo(disguise.getComponentName(context), flags);
            CharSequence label = info.loadLabel(pm);
            Bitmap bitmap = null;
            Drawable drawable = info.loadIcon(pm);
            if (drawable != null) {
                int size = 0;
                try {
                    ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                    size = am != null ? am.getLauncherLargeIconSize() : 0;
                } catch (Throwable ignore) {
                }
                if (size <= 0) {
                    size = Math.max(1, (int) (48 * context.getResources().getDisplayMetrics().density));
                }
                bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(new Canvas(bitmap));
            }
            cachedIdentityLabel = label != null ? label.toString() : null;
            cachedIdentityBitmap = bitmap;
            cachedIdentityIcon = disguise;
        } catch (Throwable ignore) {
        }
    }

    /**
     * One-time consolidation of the retired LuminaSecurityActivity "Disguise" switch.
     *
     * That old toggle wrote {@link #KEY_LEGACY_DISGUISE_ICON} and flipped the CalculatorIcon alias
     * through {@link LauncherIconController#setIcon}, i.e. a SECOND controller fighting this preset
     * system over the same alias. This migrates any tester who had it on into the preset system —
     * {@code disguiseEnabled = true}, {@code disguisePreset = calculator} — then clears the legacy
     * flag so the two systems can never contend again. It never re-enables anything on its own; the
     * caller ({@link LauncherIconController#tryFixLauncherIconIfNeeded}) applies the resulting state.
     *
     * FAIL-SAFE and idempotent: guarded end-to-end, and a no-op once the legacy flag is cleared (the
     * common case), so it is cheap to call on every cold start.
     */
    public static void migrateStaleDisguiseToggle(Context context) {
        try {
            if (!LuminaConfig.getBoolean(KEY_LEGACY_DISGUISE_ICON, false)) {
                return;
            }
            // Only adopt the legacy disguise if the user has not already configured the new system,
            // so we never clobber an explicit new-system choice.
            if (!LuminaConfig.getBoolean(KEY_ENABLED, false)) {
                LuminaConfig.putBoolean(KEY_ENABLED, true);
                LuminaConfig.putString(KEY_PRESET, PRESET_CALCULATOR);
            }
            // Retire the legacy flag so only the preset system drives the launcher aliases now.
            LuminaConfig.putBoolean(KEY_LEGACY_DISGUISE_ICON, false);
        } catch (Throwable ignore) {
        }
    }
}
