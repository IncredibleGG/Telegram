/*
 * LuminaGram - 「隱藏重複廣告」 (Hide repeated ads) settings page.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.R;
import org.telegram.messenger.lumina.LuminaAdShield;
import org.telegram.messenger.lumina.LuminaAdShieldManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * Global settings of 「隱藏重複廣告」 (A): the switch (default off, explainer dialog the first time it is
 * turned on), sensitivity, exemptions, trial run and "clear local data". Everything is local to this
 * device; nothing is deleted, reported or uploaded. Opened from {@link LuminaChatActivity}.
 */
public class LuminaAdShieldActivity extends BaseFragment {

    private static final int ID_ENABLED = 1;
    private static final int ID_SENS_LOOSE = 10;
    private static final int ID_SENS_STANDARD = 11;
    private static final int ID_SENS_AGGRESSIVE = 12;
    private static final int ID_EXEMPT_CONTACTS = 20;
    private static final int ID_EXEMPT_BOTS = 21;
    private static final int ID_EXEMPT_REPLIES = 22;
    private static final int ID_TRIAL = 30;
    private static final int ID_CLEAR = 40;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaAdShieldTitle));
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
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaAdShieldTitle)));
        items.add(UItem.asSwitch(ID_ENABLED, LuminaLocale.getString(R.string.LuminaAdShieldTitle))
                .setChecked(LuminaAdShieldManager.isGloballyEnabled()));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaAdShieldEntryInfo)));

        String sens = LuminaAdShieldManager.getSensitivity();
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaAdShieldSensitivity)));
        items.add(UItem.asRadio2(ID_SENS_LOOSE,
                LuminaLocale.getString(R.string.LuminaAdShieldSensLoose),
                LuminaLocale.getString(R.string.LuminaAdShieldSensLooseInfo))
                .setChecked(LuminaAdShield.SENS_LOOSE.equals(sens)));
        items.add(UItem.asRadio2(ID_SENS_STANDARD,
                LuminaLocale.getString(R.string.LuminaAdShieldSensStandard),
                LuminaLocale.getString(R.string.LuminaAdShieldSensStandardInfo))
                .setChecked(LuminaAdShield.SENS_STANDARD.equals(sens)));
        items.add(UItem.asRadio2(ID_SENS_AGGRESSIVE,
                LuminaLocale.getString(R.string.LuminaAdShieldSensAggressive),
                LuminaLocale.getString(R.string.LuminaAdShieldSensAggressiveInfo))
                .setChecked(LuminaAdShield.SENS_AGGRESSIVE.equals(sens)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaAdShieldExemptHeader)));
        items.add(UItem.asSwitch(ID_EXEMPT_CONTACTS, LuminaLocale.getString(R.string.LuminaAdShieldExemptContacts))
                .setChecked(LuminaAdShieldManager.isExemptContacts()));
        items.add(UItem.asSwitch(ID_EXEMPT_BOTS, LuminaLocale.getString(R.string.LuminaAdShieldExemptBots))
                .setChecked(LuminaAdShieldManager.isExemptBots()));
        items.add(UItem.asSwitch(ID_EXEMPT_REPLIES, LuminaLocale.getString(R.string.LuminaAdShieldExemptReplies))
                .setChecked(LuminaAdShieldManager.isExemptReplies()));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaAdShieldExemptInfo)));

        items.add(UItem.asSwitch(ID_TRIAL, LuminaLocale.getString(R.string.LuminaAdShieldTrial))
                .setChecked(LuminaAdShieldManager.isTrial()));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaAdShieldTrialInfo)));

        items.add(UItem.asButton(ID_CLEAR, LuminaLocale.getString(R.string.LuminaAdShieldClearData)).red());
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaAdShieldFooter)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ENABLED:
                if (LuminaAdShieldManager.isGloballyEnabled()) {
                    LuminaAdShieldManager.setGloballyEnabled(false);
                } else if (!LuminaAdShieldManager.isExplained()) {
                    showExplainer(this, () -> {
                        LuminaAdShieldManager.setGloballyEnabled(true);
                        update();
                    });
                    return;
                } else {
                    LuminaAdShieldManager.setGloballyEnabled(true);
                }
                break;
            case ID_SENS_LOOSE:
                LuminaAdShieldManager.setSensitivity(LuminaAdShield.SENS_LOOSE);
                break;
            case ID_SENS_STANDARD:
                LuminaAdShieldManager.setSensitivity(LuminaAdShield.SENS_STANDARD);
                break;
            case ID_SENS_AGGRESSIVE:
                LuminaAdShieldManager.setSensitivity(LuminaAdShield.SENS_AGGRESSIVE);
                break;
            case ID_EXEMPT_CONTACTS:
                LuminaAdShieldManager.setExemptContacts(!LuminaAdShieldManager.isExemptContacts());
                break;
            case ID_EXEMPT_BOTS:
                LuminaAdShieldManager.setExemptBots(!LuminaAdShieldManager.isExemptBots());
                break;
            case ID_EXEMPT_REPLIES:
                LuminaAdShieldManager.setExemptReplies(!LuminaAdShieldManager.isExemptReplies());
                break;
            case ID_TRIAL:
                LuminaAdShieldManager.setTrial(!LuminaAdShieldManager.isTrial());
                break;
            case ID_CLEAR:
                showClearConfirm();
                return;
            default:
                return;
        }
        update();
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void showClearConfirm() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LuminaLocale.getString(R.string.LuminaAdShieldClearData));
        builder.setMessage(LuminaLocale.getString(R.string.LuminaAdShieldClearDataConfirm));
        builder.setPositiveButton(LuminaLocale.getString(R.string.LuminaAdShieldClearDataButton), (d, w) -> {
            LuminaAdShieldManager.clearLocalData();
            if (BulletinFactory.canShowBulletin(LuminaAdShieldActivity.this)) {
                BulletinFactory.of(LuminaAdShieldActivity.this)
                        .createSimpleBulletin(R.raw.done, LuminaLocale.getString(R.string.LuminaAdShieldClearDataDone))
                        .show();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    /**
     * First-time explainer (A.1): shown before the feature is turned on for the first time, either globally
     * or in a single group. {@code onEnable} runs only after 「開啟」; 「取消」 changes nothing.
     */
    public static void showExplainer(BaseFragment fragment, Runnable onEnable) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int[] lines = {R.string.LuminaAdShieldExplainer1, R.string.LuminaAdShieldExplainer2,
                R.string.LuminaAdShieldExplainer3, R.string.LuminaAdShieldExplainer4,
                R.string.LuminaAdShieldExplainer5};
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("• ").append(LuminaLocale.getString(lines[i]));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(LuminaLocale.getString(R.string.LuminaAdShieldTitle));
        builder.setMessage(sb.toString());
        builder.setPositiveButton(LuminaLocale.getString(R.string.LuminaAdShieldExplainerEnable), (d, w) -> {
            LuminaAdShieldManager.setExplained();
            if (onEnable != null) {
                onEnable.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }
}
