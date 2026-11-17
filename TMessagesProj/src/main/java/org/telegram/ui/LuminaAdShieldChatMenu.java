/*
 * LuminaGram - 「隱藏重複廣告」 per-group mode chooser (chat action-bar ⋮ menu).
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.R;
import org.telegram.messenger.lumina.LuminaAdShieldChatSession;
import org.telegram.messenger.lumina.LuminaAdShieldManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

/**
 * B: 跟隨預設（目前：開/關） / 在此群開啟 / 在此群關閉, plus 「最近 7 天已收合 N 則」 when N &gt; 0, and the
 * per-group switch 「連管理員和頻道的也收」 (SPEC §6.12, default off; it takes effect while the feature is on
 * for the group). Only offered for eligible groups (the chat has an AdShield session). Choosing
 * 「在此群開啟」 before the feature was ever turned on shows the first-time explainer first.
 */
public final class LuminaAdShieldChatMenu {

    private LuminaAdShieldChatMenu() {
    }

    public static void show(BaseFragment fragment, LuminaAdShieldChatSession session) {
        if (fragment == null || session == null || fragment.getParentActivity() == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final int mode = session.getChatMode();
        final boolean globalOn = LuminaAdShieldManager.isGloballyEnabled();

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView countView = new TextView(context);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        countView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        countView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        countView.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        countView.setVisibility(View.GONE);
        layout.addView(countView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        String follow = String.format(LuminaLocale.getString(R.string.LuminaAdShieldChatFollow),
                LuminaLocale.getString(globalOn ? R.string.LuminaAdShieldValueOn : R.string.LuminaAdShieldValueOff));
        CharSequence[] options = {
                follow,
                LuminaLocale.getString(R.string.LuminaAdShieldChatOn),
                LuminaLocale.getString(R.string.LuminaAdShieldChatOff),
        };
        final int[] modes = {LuminaAdShieldManager.MODE_DEFAULT, LuminaAdShieldManager.MODE_ON, LuminaAdShieldManager.MODE_OFF};

        AlertDialog.Builder builder = new AlertDialog.Builder(context, rp);
        builder.setTitle(LuminaLocale.getString(R.string.LuminaAdShieldTitle));
        for (int i = 0; i < options.length; i++) {
            RadioColorCell cell = new RadioColorCell(context, rp);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setTextAndValue(options[i], modes[i] == mode);
            final int chosen = modes[i];
            cell.setOnClickListener(v -> {
                builder.getDismissRunnable().run();
                choose(fragment, session, chosen);
            });
            layout.addView(cell);
        }
        layout.addView(privilegedRow(context, rp, session),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());

        session.requestFoldedCount(n -> {
            if (n != null && n > 0) {
                countView.setText(String.format(LuminaLocale.getString(R.string.LuminaAdShieldChatFolded7d), n));
                countView.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 「連管理員和頻道的也收」 with its help line; toggling stores it for this group and re-decides the chat. */
    private static View privilegedRow(Context context, Theme.ResourcesProvider rp, LuminaAdShieldChatSession session) {
        final String title = LuminaLocale.getString(R.string.LuminaAdShieldChatPrivFold);
        final String info = LuminaLocale.getString(R.string.LuminaAdShieldChatPrivFoldInfo);
        final Switch sw = new Switch(context, rp);
        FrameLayout row = new FrameLayout(context) {
            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo node) {
                super.onInitializeAccessibilityNodeInfo(node);
                node.setClassName("android.widget.Switch");
                node.setCheckable(true);
                node.setChecked(sw.isChecked());
                node.setContentDescription(title + "\n" + info);
            }
        };
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, rp), Theme.RIPPLE_MASK_ALL));
        boolean rtl = LocaleController.isRTL;
        int gravity = rtl ? Gravity.RIGHT : Gravity.LEFT;

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        titleView.setGravity(gravity);
        titleView.setText(title);
        texts.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView infoView = new TextView(context);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        infoView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        infoView.setGravity(gravity);
        infoView.setText(info);
        texts.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                gravity | Gravity.CENTER_VERTICAL, rtl ? 70 : 24, 10, rtl ? 24 : 70, 10));

        sw.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhite);
        sw.setChecked(session.getFoldPrivileged(), false);
        sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(sw, LayoutHelper.createFrame(37, 20, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL,
                22, 0, 22, 0));
        row.setOnClickListener(v -> {
            boolean on = !sw.isChecked();
            sw.setChecked(on, true);
            session.setFoldPrivileged(on);
        });
        return row;
    }

    private static void choose(BaseFragment fragment, LuminaAdShieldChatSession session, int mode) {
        if (mode == session.getChatMode()) {
            return;
        }
        if (mode == LuminaAdShieldManager.MODE_ON && !LuminaAdShieldManager.isExplained()) {
            LuminaAdShieldActivity.showExplainer(fragment, () -> session.setChatMode(LuminaAdShieldManager.MODE_ON));
            return;
        }
        session.setChatMode(mode);
    }
}
