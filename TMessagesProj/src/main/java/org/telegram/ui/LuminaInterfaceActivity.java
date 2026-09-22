package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * LuminaInterfaceActivity - interface & display preferences.
 *
 * Three independent toggles, each persisted through
 * {@link LuminaConfig#getBoolean}/{@link LuminaConfig#putBoolean} (all default false):
 *   - systemEmoji            use the device emoji set instead of Telegram's (impl elsewhere)
 *   - confirmSendVoiceVideo  ask before sending a voice / video message (impl elsewhere)
 *   - disableNumberRounding  show exact counts instead of 1.2K/3.4M (impl in AndroidUtilities)
 *
 * Structure mirrors {@link LuminaSecurityActivity}: a UItem list rendered by a
 * {@link UniversalRecyclerView}. This page only renders the switches and persists
 * the keys; the emoji and voice/video behaviours are wired by other components.
 */
public class LuminaInterfaceActivity extends BaseFragment {

    private static final int ID_SYSTEM_EMOJI = 1;
    private static final int ID_CONFIRM_VOICE_VIDEO = 2;
    private static final int ID_DISABLE_NUMBER_ROUNDING = 3;
    private static final int ID_UNREAD_DIGEST = 4;
    private static final int ID_PHOTO_UPLOAD_DATE = 5;
    private static final int ID_HIDE_AI_BUTTON = 6;
    // Batch 3 — input row
    private static final int ID_HIDE_VOICE_BUTTON = 7;
    private static final int ID_HIDE_SEND_AS = 8;
    private static final int ID_DISABLE_THANOS = 9;
    private static final int ID_SEND_BY_RETURN = 10;
    private static final int ID_FORMATTING_BAR = 11;

    private static final String KEY_SYSTEM_EMOJI = "systemEmoji";
    private static final String KEY_CONFIRM_VOICE_VIDEO = "confirmSendVoiceVideo";
    private static final String KEY_DISABLE_NUMBER_ROUNDING = "disableNumberRounding";
    private static final String KEY_PHOTO_UPLOAD_DATE = "showPhotoUploadDate";
    private static final String KEY_HIDE_VOICE_BUTTON = "hideVoiceButton";
    private static final String KEY_HIDE_SEND_AS = "hideSendAsButton";
    private static final String KEY_DISABLE_THANOS = "disableThanosDelete";
    private static final String KEY_SEND_BY_RETURN = "sendByReturnKey";
    private static final String KEY_FORMATTING_BAR = "formattingBar";

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaInterfaceTitle));
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
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaInterfaceEmojiHeader)));
        items.add(UItem.asSwitch(ID_SYSTEM_EMOJI, LuminaLocale.getString(R.string.LuminaInterfaceSystemEmoji))
                .setChecked(LuminaConfig.getBoolean(KEY_SYSTEM_EMOJI, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaInterfaceSystemEmojiInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaInterfaceMessagingHeader)));
        items.add(UItem.asSwitch(ID_CONFIRM_VOICE_VIDEO, LuminaLocale.getString(R.string.LuminaInterfaceConfirmVoiceVideo))
                .setChecked(LuminaConfig.getBoolean(KEY_CONFIRM_VOICE_VIDEO, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaInterfaceConfirmVoiceVideoInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaInterfaceNumbersHeader)));
        items.add(UItem.asSwitch(ID_DISABLE_NUMBER_ROUNDING, LuminaLocale.getString(R.string.LuminaInterfaceExactNumbers))
                .setChecked(LuminaConfig.getBoolean(KEY_DISABLE_NUMBER_ROUNDING, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaInterfaceExactNumbersInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaDigestTitle)));
        items.add(UItem.asSwitch(ID_UNREAD_DIGEST, LuminaLocale.getString(R.string.LuminaDigestTitle))
                .setChecked(LuminaConfig.getBoolean("unreadDigest", true)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaDigestInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaShowPhotoUploadDate)));
        items.add(UItem.asSwitch(ID_PHOTO_UPLOAD_DATE, LuminaLocale.getString(R.string.LuminaShowPhotoUploadDate))
                .setChecked(LuminaConfig.getBoolean(KEY_PHOTO_UPLOAD_DATE, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaShowPhotoUploadDateInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaInterfaceComposerHeader)));
        items.add(UItem.asSwitch(ID_HIDE_AI_BUTTON, LuminaLocale.getString(R.string.LuminaHideAiButton))
                .setChecked(LuminaConfig.hideInputAiButton));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaHideAiButtonInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaInputRowHeader)));
        items.add(UItem.asSwitch(ID_HIDE_VOICE_BUTTON, LuminaLocale.getString(R.string.LuminaHideVoiceButton))
                .setChecked(LuminaConfig.getBoolean(KEY_HIDE_VOICE_BUTTON, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaHideVoiceButtonInfo)));
        items.add(UItem.asSwitch(ID_HIDE_SEND_AS, LuminaLocale.getString(R.string.LuminaHideSendAsButton))
                .setChecked(LuminaConfig.getBoolean(KEY_HIDE_SEND_AS, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaHideSendAsButtonInfo)));
        items.add(UItem.asSwitch(ID_SEND_BY_RETURN, LuminaLocale.getString(R.string.LuminaSendByReturnKey))
                .setChecked(LuminaConfig.getBoolean(KEY_SEND_BY_RETURN, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaSendByReturnKeyInfo)));
        items.add(UItem.asSwitch(ID_FORMATTING_BAR, LuminaLocale.getString(R.string.LuminaFormattingBar))
                .setChecked(LuminaConfig.getBoolean(KEY_FORMATTING_BAR, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaFormattingBarInfo)));
        items.add(UItem.asSwitch(ID_DISABLE_THANOS, LuminaLocale.getString(R.string.LuminaDisableThanosDelete))
                .setChecked(LuminaConfig.getBoolean(KEY_DISABLE_THANOS, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaDisableThanosDeleteInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        String key;
        switch (item.id) {
            case ID_SYSTEM_EMOJI:
                key = KEY_SYSTEM_EMOJI;
                break;
            case ID_CONFIRM_VOICE_VIDEO:
                key = KEY_CONFIRM_VOICE_VIDEO;
                break;
            case ID_DISABLE_NUMBER_ROUNDING:
                key = KEY_DISABLE_NUMBER_ROUNDING;
                break;
            case ID_PHOTO_UPLOAD_DATE:
                key = KEY_PHOTO_UPLOAD_DATE;
                break;
            case ID_HIDE_VOICE_BUTTON:
                key = KEY_HIDE_VOICE_BUTTON;
                break;
            case ID_HIDE_SEND_AS:
                key = KEY_HIDE_SEND_AS;
                break;
            case ID_DISABLE_THANOS:
                key = KEY_DISABLE_THANOS;
                break;
            case ID_SEND_BY_RETURN:
                key = KEY_SEND_BY_RETURN;
                break;
            case ID_FORMATTING_BAR:
                key = KEY_FORMATTING_BAR;
                break;
            case ID_UNREAD_DIGEST:
                LuminaConfig.putBoolean("unreadDigest", !LuminaConfig.getBoolean("unreadDigest", true));
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                return;
            case ID_HIDE_AI_BUTTON:
                LuminaConfig.hideInputAiButton = !LuminaConfig.hideInputAiButton;
                LuminaConfig.putBoolean("hideInputAiButton", LuminaConfig.hideInputAiButton);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                return;
            default:
                return;
        }
        LuminaConfig.putBoolean(key, !LuminaConfig.getBoolean(key, false));
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
