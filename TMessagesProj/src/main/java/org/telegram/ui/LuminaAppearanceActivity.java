package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * LuminaGram — Appearance customization (Wave 2).
 *
 * Mirrors {@link LuminaGramSettingsActivity}'s UItem / UniversalRecyclerView pattern.
 * Exposes three "Safe" appearance controls, all persisted through {@link LuminaConfig}:
 *   1. Material You / dynamic colors (Android 12+): derives the app accent from the
 *      system wallpaper palette ({@code android.R.color.system_accent1_500}).
 *   2. Custom accent color: a swatch picker that overrides the app accent when
 *      Material You is off.
 *   3. Custom app font: swaps the default Roboto typeface app-wide (System / Serif /
 *      Monospace) via {@link AndroidUtilities#getTypeface(String)}.
 *
 * The actual recolor happens inside {@link Theme#getColor(int)} (accent) and the font
 * swap inside {@link AndroidUtilities#getTypeface(String)} — both keyed off static
 * fields refreshed by {@link LuminaConfig#applyAppearance()}.
 */
public class LuminaAppearanceActivity extends BaseFragment {

    private static final int ITEM_MATERIAL_YOU = 1;
    private static final int ITEM_CUSTOM_ACCENT = 2;
    private static final int ITEM_ACCENT_COLOR = 3;
    private static final int ITEM_FONT = 4;
    private static final int ITEM_STICKER_SIZE_75 = 5;
    private static final int ITEM_STICKER_SIZE_100 = 6;
    private static final int ITEM_STICKER_SIZE_125 = 7;
    private static final int ITEM_STICKER_SIZE_150 = 8;
    private static final int ITEM_HIDE_REACTIONS = 9;
    private static final int ITEM_WIDE_CHANNEL_POSTS = 10;

    // Sticker render scale (percent); read in ChatMessageCell's sticker layout branch. 100 = unchanged.
    private static final String KEY_STICKER_SCALE = "stickerScale";

    // A compact accent palette (opaque). Mirrors Telegram's default accent circles.
    private static final int[] ACCENT_PALETTE = new int[]{
            0xFF3390EC, 0xFF00A2ED, 0xFF16A085, 0xFF2CC36B, 0xFF8E7CFF,
            0xFFE05FA6, 0xFFEB5545, 0xFFF08631, 0xFFE8AE1C, 0xFF7F8C99
    };

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaAppearanceTitle));
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

    private boolean materialYouSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    private CharSequence accentValueText() {
        int c = LuminaConfig.customAccentColor;
        if (c == 0) {
            return LuminaLocale.getString(R.string.LuminaAppearanceAccentDefault);
        }
        return String.format(Locale.US, "#%06X", 0xFFFFFF & c);
    }

    private CharSequence fontValueText() {
        switch (LuminaConfig.appFont) {
            case 1:
                return LuminaLocale.getString(R.string.LuminaAppearanceFontSystem);
            case 2:
                return LuminaLocale.getString(R.string.LuminaAppearanceFontSerif);
            case 3:
                return LuminaLocale.getString(R.string.LuminaAppearanceFontMono);
            default:
                return LuminaLocale.getString(R.string.LuminaAppearanceFontDefault);
        }
    }

    private CharSequence stickerScaleLabel(int pct) {
        if (pct == 100) {
            return pct + "% (" + LuminaLocale.getString(R.string.LuminaAppearanceAccentDefault) + ")";
        }
        return pct + "%";
    }

    private void setStickerScale(int pct) {
        LuminaConfig.putInt(KEY_STICKER_SCALE, pct);
        update();
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaAppearanceColorsHeader)));
        if (materialYouSupported()) {
            items.add(UItem.asSwitch(ITEM_MATERIAL_YOU, LuminaLocale.getString(R.string.LuminaAppearanceMaterialYou))
                    .setChecked(LuminaConfig.materialYouEnabled));
        }
        // Material You wins over the custom accent; only expose the manual controls when it is off.
        boolean materialYouActive = materialYouSupported() && LuminaConfig.materialYouEnabled;
        items.add(UItem.asSwitch(ITEM_CUSTOM_ACCENT, LuminaLocale.getString(R.string.LuminaAppearanceCustomAccent))
                .setChecked(LuminaConfig.customAccentEnabled && !materialYouActive));
        if (LuminaConfig.customAccentEnabled && !materialYouActive) {
            items.add(UItem.asButton(ITEM_ACCENT_COLOR, LuminaLocale.getString(R.string.LuminaAppearanceAccentColor), accentValueText()));
        }
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaAppearanceColorsInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaAppearanceFontHeader)));
        items.add(UItem.asButton(ITEM_FONT, LuminaLocale.getString(R.string.LuminaAppearanceFont), fontValueText()));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaAppearanceFontInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaAppearanceStickerSizeHeader)));
        int stickerScale = LuminaConfig.getInt(KEY_STICKER_SCALE, 100);
        items.add(UItem.asRadio(ITEM_STICKER_SIZE_75, stickerScaleLabel(75)).setChecked(stickerScale == 75));
        items.add(UItem.asRadio(ITEM_STICKER_SIZE_100, stickerScaleLabel(100)).setChecked(stickerScale == 100));
        items.add(UItem.asRadio(ITEM_STICKER_SIZE_125, stickerScaleLabel(125)).setChecked(stickerScale == 125));
        items.add(UItem.asRadio(ITEM_STICKER_SIZE_150, stickerScaleLabel(150)).setChecked(stickerScale == 150));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaAppearanceStickerSizeInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaBubbleHeader)));
        items.add(UItem.asSwitch(ITEM_HIDE_REACTIONS, LuminaLocale.getString(R.string.LuminaHideReactions)).setChecked(LuminaConfig.hideReactions));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaHideReactionsInfo)));
        items.add(UItem.asSwitch(ITEM_WIDE_CHANNEL_POSTS, LuminaLocale.getString(R.string.LuminaWideChannelPosts)).setChecked(LuminaConfig.wideChannelPosts));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaWideChannelPostsInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ITEM_MATERIAL_YOU:
                LuminaConfig.toggleMaterialYou();
                refreshTheme();
                update();
                break;
            case ITEM_CUSTOM_ACCENT:
                LuminaConfig.toggleCustomAccent();
                refreshTheme();
                update();
                break;
            case ITEM_ACCENT_COLOR:
                showColorPicker();
                break;
            case ITEM_FONT:
                showFontPicker();
                break;
            case ITEM_STICKER_SIZE_75:
                setStickerScale(75);
                break;
            case ITEM_STICKER_SIZE_100:
                setStickerScale(100);
                break;
            case ITEM_STICKER_SIZE_125:
                setStickerScale(125);
                break;
            case ITEM_STICKER_SIZE_150:
                setStickerScale(150);
                break;
            case ITEM_HIDE_REACTIONS:
                LuminaConfig.toggleHideReactions();
                update();
                break;
            case ITEM_WIDE_CHANNEL_POSTS:
                LuminaConfig.toggleWideChannelPosts();
                update();
                break;
        }
    }

    /** Force every open fragment to re-read colors from {@link Theme#getColor(int)}. */
    private void refreshTheme() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetNewTheme, false, true);
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void showColorPicker() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(12);
        container.setPadding(pad, pad, pad, pad);

        LinearLayout row = null;
        for (int i = 0; i < ACCENT_PALETTE.length; i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            final int color = ACCENT_PALETTE[i] | 0xFF000000;
            FrameLayout cell = new FrameLayout(context);
            ImageView circle = new ImageView(context);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color);
            if (color == (LuminaConfig.customAccentColor | 0xFF000000)) {
                bg.setStroke(AndroidUtilities.dp(3), Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }
            circle.setBackground(bg);
            cell.addView(circle, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
            row.addView(cell, LayoutHelper.createLinear(0, 56, 1f));
            cell.setOnClickListener(v -> {
                LuminaConfig.setCustomAccentColor(color);
                refreshTheme();
                update();
                dismissCurrentDialog();
            });
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LuminaLocale.getString(R.string.LuminaAppearanceAccentColor));
        builder.setView(container);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showFontPicker() {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] names = new CharSequence[]{
                LuminaLocale.getString(R.string.LuminaAppearanceFontDefault),
                LuminaLocale.getString(R.string.LuminaAppearanceFontSystem),
                LuminaLocale.getString(R.string.LuminaAppearanceFontSerif),
                LuminaLocale.getString(R.string.LuminaAppearanceFontMono)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LuminaLocale.getString(R.string.LuminaAppearanceFont));
        builder.setItems(names, (dialog, which) -> {
            LuminaConfig.setAppFont(which);
            refreshTheme();
            update();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }
}
