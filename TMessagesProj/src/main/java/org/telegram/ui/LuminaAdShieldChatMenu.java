/*
 * LuminaGram - 「隱藏重複廣告」 per-group mode chooser (chat action-bar ⋮ menu).
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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

/**
 * B: 跟隨預設（目前：開/關） / 在此群開啟 / 在此群關閉, plus 「最近 7 天已收合 N 則」 when N &gt; 0.
 * Only offered for eligible groups (the chat has an AdShield session). Choosing 「在此群開啟」 before
 * the feature was ever turned on shows the first-time explainer first.
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
