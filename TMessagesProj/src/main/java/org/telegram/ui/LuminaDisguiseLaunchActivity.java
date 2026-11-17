package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.Set;

/**
 * LuminaDisguiseLaunchActivity — the neutral launcher entry behind the camouflage icons
 * (the Calculator / Notes / Clock {@code <activity-alias>}es).
 *
 * WHY IT EXISTS: an {@code <activity-alias>} cannot carry its own theme; the system always
 * builds the launch window from the TARGET activity's theme. The disguise aliases used to
 * target {@link LaunchActivity} directly, whose {@code Theme.TMessages.Start} sets the animated
 * LuminaGram/Telegram logo as the Android 12+ system splash
 * ({@code windowSplashScreenAnimatedIcon = tg_splash_320}). So every launch from a camouflage
 * icon flashed the real logo before the vault door, the decoy or the app itself appeared.
 *
 * The disguise aliases now target this activity. Its theme ({@code Theme.Lumina.DisguiseLaunch})
 * sets no splash icon, so on Android 12+ the system splash shows the alias's own camouflage icon
 * on a plain background (exactly what any real calculator / notes / clock app shows), and on
 * Android 6-11 the launch window is just a plain colour. It never draws anything itself: it hands
 * a clean launcher intent to {@link LaunchActivity}, which keeps owning the vault gate exactly as
 * before, and finishes inside onCreate. The system passes this activity's launch window on to
 * LaunchActivity and from there to the vault door / decoy, so nothing branded is drawn between.
 *
 * {@code singleTask} (same task affinity as LaunchActivity) is deliberate:
 * <ul>
 *   <li>A tap on the camouflage icon while the app's task already exists creates a fresh
 *       instance of this activity on top of that task, so the launch window again comes from
 *       this neutral theme instead of a snapshot of whatever was last on screen (for the vault:
 *       the real chat list from before the app was left).</li>
 *   <li>LaunchActivity (singleTask) then still receives the very same clear-top +
 *       onNewIntent(MAIN) it got when the alias pointed at it directly, so re-opening the app
 *       from its icon behaves as before.</li>
 * </ul>
 * Normal (non-disguised) launches never come through here: the real icon and the icon-pack
 * aliases still target LaunchActivity unchanged.
 *
 * FAIL-OPEN: forwarding is wrapped; if the first attempt throws, a minimal explicit intent is
 * tried, so this activity can never be the reason the app does not open.
 */
public class LuminaDisguiseLaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean forwarded = false;
        try {
            startActivity(buildForwardIntent(getIntent()));
            forwarded = true;
        } catch (Throwable ignore) {
        }
        if (!forwarded) {
            try {
                startActivity(new Intent(this, LaunchActivity.class)
                        .setAction(Intent.ACTION_MAIN)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignore) {
            }
        }
        finish();
        try {
            overridePendingTransition(0, 0);
        } catch (Throwable ignore) {
        }
    }

    /**
     * The launcher intent, re-addressed to LaunchActivity. Only the action, the categories and
     * the "launched from history" bit are carried over (that is all a launcher tap holds);
     * no data, extras or URI grants are forwarded.
     */
    private Intent buildForwardIntent(Intent source) {
        Intent forward = new Intent(this, LaunchActivity.class);
        String action = source != null ? source.getAction() : null;
        forward.setAction(action != null ? action : Intent.ACTION_MAIN);
        Set<String> categories = source != null ? source.getCategories() : null;
        if (categories != null && !categories.isEmpty()) {
            for (String category : categories) {
                forward.addCategory(category);
            }
        } else {
            forward.addCategory(Intent.CATEGORY_LAUNCHER);
        }
        if (source != null) {
            forward.addFlags(source.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        }
        forward.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return forward;
    }
}
