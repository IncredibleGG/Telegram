package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LuminaBackgroundGuard;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class LuminaGramSettingsActivity extends BaseFragment {

    private UniversalRecyclerView listView;
    private boolean onboardingHandled;
    private boolean changelogHandled;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaGramSettings));
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
        // Sits above everything else and only when there is something wrong: a
        // messenger the system has silenced is not a settings detail, and the user
        // has no way to discover it on their own - the symptom is silence.
        if (luminaBackgroundAtRisk()) {
            items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaBackgroundGuardTitle)));
            items.add(UItem.asButton(ID_BACKGROUND_GUARD, LuminaLocale.getString(R.string.LuminaBackgroundGuardAction)));
            items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaBackgroundGuardInfo)));
        }
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaGramChatList)));
        items.add(UItem.asSwitch(1, LuminaLocale.getString(R.string.LuminaHideTabs)).setChecked(LuminaConfig.hideTabs));
        items.add(UItem.asSwitch(2, LuminaLocale.getString(R.string.LuminaHideStories)).setChecked(LuminaConfig.hideStories));
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(10, LuminaLocale.getString(R.string.LuminaPrivacyTitle)));
        items.add(UItem.asButton(11, LuminaLocale.getString(R.string.LuminaChatSettings)));
        items.add(UItem.asButton(12, LuminaLocale.getString(R.string.LuminaTranslateTitle)));
        items.add(UItem.asButton(28, LuminaLocale.getString(R.string.LuminaGlossaryManage)));
        items.add(UItem.asButton(13, LuminaLocale.getString(R.string.LuminaSecurityTitle)));
        items.add(UItem.asButton(27, LuminaLocale.getString(R.string.LuminaSecCheckupTitle)));
        items.add(UItem.asButton(14, LuminaLocale.getString(R.string.LuminaAppearanceTitle)));
        items.add(UItem.asButton(15, LuminaLocale.getString(R.string.LuminaQuickRepliesTitle)));
        items.add(UItem.asButton(16, LuminaLocale.getString(R.string.LuminaBookmarksTitle)));
        items.add(UItem.asButton(17, LuminaLocale.getString(R.string.LuminaGramChatList)));
        items.add(UItem.asButton(18, LuminaLocale.getString(R.string.LuminaMediaTitle)));
        items.add(UItem.asButton(19, LuminaLocale.getString(R.string.LuminaDisguiseTitle)));
        items.add(UItem.asButton(21, LuminaLocale.getString(R.string.LuminaInterfaceTitle)));
        items.add(UItem.asButton(22, LuminaLocale.getString(R.string.LuminaReplacerTitle)));
        items.add(UItem.asButton(23, LuminaLocale.getString(R.string.LuminaBackupTitle)));
        items.add(UItem.asButton(24, LuminaLocale.getString(R.string.LuminaProfileCardTitle)));
        items.add(UItem.asButton(25, LuminaLocale.getString(R.string.LuminaVoiceToTextTitle)));
        items.add(UItem.asButton(26, LuminaLocale.getString(R.string.LuminaChangelogTitle)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asSwitch(30, LuminaLocale.getString(R.string.LuminaBetaChannel)).setChecked(LuminaConfig.betaChannel));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaBetaChannelInfo)));
        items.add(UItem.asButton(20, LuminaLocale.getString(R.string.LuminaCheckUpdate)));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_BACKGROUND_GUARD:
                showBackgroundGuardDialog();
                break;
            case 1:
                LuminaConfig.toggleHideTabs();
                // Live-refresh: DialogsActivity re-runs updateFilterTabs() on dialogFiltersUpdated.
                if (getNotificationCenter() != null) {
                    getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
                }
                break;
            case 2:
                LuminaConfig.toggleHideStories();
                // Live-refresh: DialogsActivity re-runs updateStoriesVisibility() on storiesUpdated.
                if (getNotificationCenter() != null) {
                    getNotificationCenter().postNotificationName(NotificationCenter.storiesUpdated);
                }
                break;
            case 10:
                presentFragment(new LuminaPrivacyActivity());
                break;
            case 11:
                presentFragment(new LuminaChatActivity());
                break;
            case 12:
                presentFragment(new LuminaTranslateActivity());
                break;
            case 28:
                presentFragment(new LuminaGlossaryActivity());
                break;
            case 13:
                presentFragment(new LuminaSecurityActivity());
                break;
            case 27:
                presentFragment(new LuminaSecurityCheckupActivity());
                break;
            case 14:
                presentFragment(new LuminaAppearanceActivity());
                break;
            case 15:
                presentFragment(new LuminaQuickRepliesActivity());
                break;
            case 16:
                presentFragment(new LuminaBookmarksActivity());
                break;
            case 17:
                presentFragment(new LuminaChatListActivity());
                break;
            case 18:
                presentFragment(new LuminaMediaActivity());
                break;
            case 19:
                presentFragment(new LuminaDisguiseActivity());
                break;
            case 21:
                presentFragment(new LuminaInterfaceActivity());
                break;
            case 22:
                presentFragment(new LuminaReplacerActivity());
                break;
            case 23:
                presentFragment(new LuminaBackupActivity());
                break;
            case 24:
                presentFragment(new LuminaProfileCardActivity());
                break;
            case 25:
                presentFragment(new LuminaVoiceToTextActivity());
                break;
            case 26:
                presentFragment(new LuminaChangelogActivity());
                LuminaChangelogActivity.markCurrentVersionSeen();
                break;
            case 30:
                LuminaConfig.toggleBetaChannel();
                break;
            case 20:
                LaunchActivity launchActivity = LaunchActivity.instance;
                if (launchActivity != null) {
                    // force=true bypasses the CHECK_UPDATES gate / rate-limit; a non-null
                    // progress makes LaunchActivity show the "already latest" bulletin when
                    // no newer build is found. The custom updater path handles the popup.
                    launchActivity.checkAppUpdate(true, new Browser.Progress());
                }
                break;
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // First-run setup card: the first time this settings hub is opened after
        // install, show a short welcome dialog highlighting LuminaGram's features.
        // Gated once-per-install on the LuminaConfig "onboardingShown" flag. Posted
        // with a small delay so the open transition has finished before showDialog()
        // runs (showDialog() is rejected while a transition animation is in progress).
        if (!onboardingHandled && !LuminaConfig.getBoolean("onboardingShown", false)) {
            onboardingHandled = true;
            AndroidUtilities.runOnUIThread(this::showOnboardingCard, 400);
        }
        // LuminaGram: auto-show changelog after update (only if onboarding already done)
        if (!changelogHandled && LuminaConfig.getBoolean("onboardingShown", false)
                && LuminaChangelogActivity.hasUnseenChangelog()) {
            changelogHandled = true;
            AndroidUtilities.runOnUIThread(() -> {
                presentFragment(new LuminaChangelogActivity());
                LuminaChangelogActivity.markCurrentVersionSeen();
            }, 500);
        }
    }

    private static final int ID_BACKGROUND_GUARD = 900;

    // "At risk" is deliberately narrow: only the battery exemption can actually be
    // read back. Vendor autostart cannot, so nagging about it forever would train
    // people to ignore this row. A user who turned delivery off is left alone.
    private boolean luminaBackgroundAtRisk() {
        if (getParentActivity() == null || LuminaBackgroundGuard.deliveryDisabledByUser()) {
            return false;
        }
        return !LuminaBackgroundGuard.batteryUnrestricted(getParentActivity());
    }

    private void showBackgroundGuardDialog() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LuminaLocale.getString(R.string.LuminaBackgroundGuardTitle));
        builder.setMessage(LuminaBackgroundGuard.vendorRestrictsBackground()
                ? LuminaLocale.getString(R.string.LuminaBackgroundGuardMessageVendor)
                : LuminaLocale.getString(R.string.LuminaBackgroundGuardMessage));
        builder.setPositiveButton(LuminaLocale.getString(R.string.LuminaBackgroundGuardBattery),
                (dialog, which) -> LuminaBackgroundGuard.requestBatteryExemption(activity));
        if (LuminaBackgroundGuard.vendorRestrictsBackground()) {
            builder.setNeutralButton(LuminaLocale.getString(R.string.LuminaBackgroundGuardAutostart),
                    (dialog, which) -> LuminaBackgroundGuard.openAutostartSettings(activity));
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showOnboardingCard() {
        if (getParentActivity() == null || LuminaConfig.getBoolean("onboardingShown", false)) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LuminaLocale.getString(R.string.LuminaOnboardingTitle));
        builder.setMessage(LuminaLocale.getString(R.string.LuminaOnboardingMessage));
        builder.setPositiveButton(LuminaLocale.getString(R.string.LuminaOnboardingGotIt), null);
        // Deep-link into the Privacy & Stealth page (reuses its existing localized title).
        builder.setNeutralButton(LuminaLocale.getString(R.string.LuminaPrivacyTitle),
                (dialog, which) -> presentFragment(new LuminaPrivacyActivity()));
        // Persist the once-per-install gate only when the card actually appeared.
        if (showDialog(builder.create()) != null) {
            LuminaConfig.putBoolean("onboardingShown", true);
        }
    }
}
