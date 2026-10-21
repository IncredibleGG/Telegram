package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.LuminaSessionGuard;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.File;
import java.util.ArrayList;

/**
 * LuminaSecurityActivity — anti-censorship security tools.
 *
 * Panic wipe (Kaboom): one tap logs out every account (server + local) and erases
 * local chats and cached media, then returns to the login screen.
 *
 * NOTE: the old "Disguise" launcher toggle that used to live here has been retired. App disguise
 * (Calculator / Notes / Clock launcher presets) is now owned solely by LuminaDisguiseActivity /
 * {@link org.telegram.messenger.LuminaDisguiseController}, so a single controller drives the
 * launcher aliases. Any tester who had the old toggle on is migrated automatically by
 * {@link org.telegram.messenger.LuminaDisguiseController#migrateStaleDisguiseToggle}.
 *
 * The fake-crash duress code that used to live here moved to the top of LuminaDisguiseActivity
 * (same LuminaConfig keys, so existing settings carry over unchanged).
 *
 * Toggles are persisted through {@link LuminaConfig#getBoolean}/{@link LuminaConfig#putBoolean}.
 */
public class LuminaSecurityActivity extends BaseFragment {

    private static final int ID_PANIC_WIPE = 1;
    private static final int ID_SCREENSHOT_DETECTION = 5;
    private static final int ID_SESSION_GUARD = 6;
    private static final int ID_SESSION_GUARD_CHECK = 7;
    private static final int ID_HOMOGLYPH_WARN = 8;
    private static final int ID_FILE_GUARD = 9;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaSecurityTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaSecurityPanicHeader)));
        items.add(UItem.asButton(ID_PANIC_WIPE, LuminaLocale.getString(R.string.LuminaSecurityPanicWipe)).red());
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaSecurityPanicWipeInfo)));

        // App disguise and the fake-crash duress code live in LuminaDisguiseActivity.

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaSecurityDetectionHeader)));
        items.add(UItem.asSwitch(ID_SCREENSHOT_DETECTION, LuminaLocale.getString(R.string.LuminaScreenshotDetection))
                .setChecked(LuminaConfig.getBoolean("screenshotDetection", false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaScreenshotDetectionInfo)));

        // Login guard: diff the account's authorization list against the locally stored one
        // and alert on anything new. Terminating is always the user's own tap.
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaSessionGuardHeader)));
        items.add(UItem.asSwitch(ID_SESSION_GUARD, LuminaLocale.getString(R.string.LuminaSessionGuard))
                .setChecked(LuminaSessionGuard.isEnabled()));
        items.add(UItem.asButton(ID_SESSION_GUARD_CHECK, LuminaLocale.getString(R.string.LuminaSessionGuardCheckNow)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaSessionGuardInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaAntiScamHeader)));
        items.add(UItem.asSwitch(ID_HOMOGLYPH_WARN, LuminaLocale.getString(R.string.LuminaHomoglyphWarn))
                .setChecked(LuminaConfig.homoglyphWarn));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaHomoglyphWarnInfo)));
        items.add(UItem.asSwitch(ID_FILE_GUARD, LuminaLocale.getString(R.string.LuminaFileGuard))
                .setChecked(LuminaConfig.fileMasqueradeGuard));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaFileGuardInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_PANIC_WIPE:
                showPanicConfirm();
                break;
            case ID_SCREENSHOT_DETECTION:
                LuminaConfig.putBoolean("screenshotDetection", !LuminaConfig.getBoolean("screenshotDetection", false));
                break;
            case ID_SESSION_GUARD:
                LuminaSessionGuard.setEnabled(!LuminaSessionGuard.isEnabled());
                break;
            case ID_SESSION_GUARD_CHECK:
                runSessionGuardCheck();
                break;
            case ID_HOMOGLYPH_WARN:
                LuminaConfig.toggleHomoglyphWarn();
                break;
            case ID_FILE_GUARD:
                LuminaConfig.toggleFileMasqueradeGuard();
                break;
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    /**
     * Manual "check linked devices now". Runs the same official {@code account.getAuthorizations}
     * diff as the automatic foreground check; any previously unknown login opens its own alert, so
     * only the quiet outcomes are reported here. Nothing is ever terminated automatically.
     */
    private void runSessionGuardCheck() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        try {
            progress.show();
        } catch (Throwable ignore) {
        }
        LuminaSessionGuard.checkNow(currentAccount, (newLogins, failed) -> {
            try {
                progress.dismiss();
            } catch (Throwable ignore) {
            }
            if (getParentActivity() == null) {
                return;
            }
            if (failed) {
                AlertsCreator.showSimpleAlert(LuminaSecurityActivity.this,
                        LuminaLocale.getString(R.string.LuminaSessionGuardHeader),
                        LuminaLocale.getString(R.string.LuminaSessionGuardCheckFailed));
            } else if (newLogins <= 0) {
                AlertsCreator.showSimpleAlert(LuminaSecurityActivity.this,
                        LuminaLocale.getString(R.string.LuminaSessionGuardHeader),
                        LuminaLocale.getString(R.string.LuminaSessionGuardNoNew));
            }
        });
    }

    private void showPanicConfirm() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LuminaLocale.getString(R.string.LuminaSecurityPanicConfirmTitle));
        builder.setMessage(LuminaLocale.getString(R.string.LuminaSecurityPanicConfirmMessage));
        builder.setPositiveButton(LuminaLocale.getString(R.string.LuminaSecurityPanicConfirmButton),
                (d, w) -> performPanicWipe());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
        showDialog(dialog);
    }

    /**
     * Panic wipe: log out every ACTIVATED account FIRST (server session + local DB), THEN
     * erase cached media off the main thread. Order matters under duress: logging out before
     * the (potentially slow) cache delete means that if we are killed mid-wipe the accounts
     * are already gone server-side. Moving the recursive delete off the UI thread avoids an
     * ANR on a large cache. Each {@link MessagesController#performLogout(int)} drops that
     * account's local message database and config; the final logout posts {@code appDidLogout},
     * which LaunchActivity turns into a switch back to the intro/login screen.
     */
    private void performPanicWipe() {
        // (a) Kill each activated account first, on the main thread. Wrap each logout so one
        // failing account cannot skip the rest.
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    // type 1 = full logout: unregister push + server-side auth.logOut + local wipe.
                    MessagesController.getInstance(a).performLogout(1);
                }
            } catch (Exception ignored) {
            }
        }
        // (b) THEN erase cached media OFF the main thread to avoid an ANR on a large cache.
        Utilities.globalQueue.postRunnable(() -> clearLocalMediaCache());
    }

    /** Best-effort recursive wipe of every local media/cache directory. */
    private void clearLocalMediaCache() {
        int[] mediaDirs = {
                FileLoader.MEDIA_DIR_IMAGE,
                FileLoader.MEDIA_DIR_AUDIO,
                FileLoader.MEDIA_DIR_VIDEO,
                FileLoader.MEDIA_DIR_DOCUMENT,
                FileLoader.MEDIA_DIR_CACHE,
                FileLoader.MEDIA_DIR_FILES,
                FileLoader.MEDIA_DIR_STORIES,
        };
        for (int type : mediaDirs) {
            try {
                wipeContents(FileLoader.getDirectory(type));
            } catch (Exception ignored) {
            }
        }
        try {
            wipeContents(ApplicationLoader.applicationContext.getCacheDir());
        } catch (Exception ignored) {
        }
    }

    /** Delete everything inside dir, keeping the top-level directory itself. */
    private static void wipeContents(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            deleteRecursive(child);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
