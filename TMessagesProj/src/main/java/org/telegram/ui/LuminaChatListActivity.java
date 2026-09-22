package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.NotificationCenter;
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
 * LuminaGram chat-list customization.
 *
 * All toggles are client-side and default OFF (standard Telegram behavior):
 *   compactChatList (def false)  true = denser dialog rows (reduced row height in
 *                                       {@link org.telegram.ui.Cells.DialogCell})
 *   hideMuteIcon    (def false)  true = hide the muted-chat bell icon in the list
 *   showMutedCount  (def false)  true = draw the unread badge of muted chats in the
 *                                       normal accent color instead of the muted gray
 *
 * Persisted via LuminaConfig.getBoolean/putBoolean (no static fields, to avoid merge
 * conflicts). Toggling posts {@link NotificationCenter#reloadInterface} so the open
 * dialog list is rebuilt and re-measured live (needed because compact changes row height).
 *
 * Self-contained: not wired into any hub here (LuminaGramSettingsActivity links it later).
 */
public class LuminaChatListActivity extends BaseFragment {

    private static final int ID_COMPACT = 1;
    private static final int ID_HIDE_MUTE = 2;
    private static final int ID_SHOW_COUNT = 3;
    private static final int ID_ONLINE_DOT = 4;
    private static final int ID_RECENCY_DOT = 5;
    private static final int ID_STORIES_OFF = 6;
    private static final int ID_STORIES_POST = 7;
    private static final int ID_PREVIEW_AUTO = 8;
    private static final int ID_PREVIEW_1 = 9;
    private static final int ID_PREVIEW_2 = 10;
    private static final int ID_PREVIEW_3 = 11;
    private static final int ID_DISABLE_SWIPE = 12;
    private static final int ID_HIDE_DELETE_SWIPE = 13;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaGramChatList));
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
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaChatListRowHeader)));
        items.add(UItem.asSwitch(ID_COMPACT, LuminaLocale.getString(R.string.LuminaCompactChatList))
                .setChecked(LuminaConfig.getBoolean("compactChatList", false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaCompactChatListInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaChatListPreviewLinesHeader)));
        int previewLines = LuminaConfig.chatListPreviewLines;
        items.add(UItem.asRadio(ID_PREVIEW_AUTO, LuminaLocale.getString(R.string.LuminaChatListPreviewLinesAuto)).setChecked(previewLines == 0));
        items.add(UItem.asRadio(ID_PREVIEW_1, LuminaLocale.getString(R.string.LuminaChatListPreviewLines1)).setChecked(previewLines == 1));
        items.add(UItem.asRadio(ID_PREVIEW_2, LuminaLocale.getString(R.string.LuminaChatListPreviewLines2)).setChecked(previewLines == 2));
        items.add(UItem.asRadio(ID_PREVIEW_3, LuminaLocale.getString(R.string.LuminaChatListPreviewLines3)).setChecked(previewLines == 3));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaChatListPreviewLinesInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaChatListBadgesHeader)));
        items.add(UItem.asSwitch(ID_HIDE_MUTE, LuminaLocale.getString(R.string.LuminaHideMuteIcon))
                .setChecked(LuminaConfig.getBoolean("hideMuteIcon", false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaHideMuteIconInfo)));
        items.add(UItem.asSwitch(ID_SHOW_COUNT, LuminaLocale.getString(R.string.LuminaShowMutedCount))
                .setChecked(LuminaConfig.getBoolean("showMutedCount", false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaShowMutedCountInfo)));
        items.add(UItem.asSwitch(ID_ONLINE_DOT, LuminaLocale.getString(R.string.LuminaChatListOnlineDot))
                .setChecked(LuminaConfig.getBoolean("chatListOnlineDot", true)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaChatListOnlineDotInfo)));
        items.add(UItem.asSwitch(ID_RECENCY_DOT, LuminaLocale.getString(R.string.LuminaChatListRecencyDot))
                .setChecked(LuminaConfig.getBoolean("chatListRecencyDot", false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaChatListRecencyDotInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaStoriesHeader)));
        items.add(UItem.asSwitch(ID_STORIES_OFF, LuminaLocale.getString(R.string.LuminaStoriesFullyOff))
                .setChecked(LuminaConfig.isStoriesFullyOff()));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaStoriesFullyOffInfo)));
        if (LuminaConfig.isStoriesFullyOff()) {
            items.add(UItem.asSwitch(ID_STORIES_POST, LuminaLocale.getString(R.string.LuminaStoriesHidePostEntry))
                    .setChecked(LuminaConfig.storiesHidePostEntry));
            items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaStoriesHidePostEntryInfo)));
        }

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaChatListSwipeHeader)));
        items.add(UItem.asSwitch(ID_DISABLE_SWIPE, LuminaLocale.getString(R.string.LuminaChatListDisableSwipe))
                .setChecked(LuminaConfig.getBoolean("disableChatListSwipe", false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaChatListDisableSwipeInfo)));
        items.add(UItem.asSwitch(ID_HIDE_DELETE_SWIPE, LuminaLocale.getString(R.string.LuminaChatListHideDeleteSwipe))
                .setChecked(LuminaConfig.getBoolean("hideDeleteSwipe", false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaChatListHideDeleteSwipeInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        boolean storiesChanged = false;
        switch (item.id) {
            case ID_COMPACT:
                LuminaConfig.putBoolean("compactChatList", !LuminaConfig.getBoolean("compactChatList", false));
                break;
            case ID_HIDE_MUTE:
                LuminaConfig.putBoolean("hideMuteIcon", !LuminaConfig.getBoolean("hideMuteIcon", false));
                break;
            case ID_SHOW_COUNT:
                LuminaConfig.putBoolean("showMutedCount", !LuminaConfig.getBoolean("showMutedCount", false));
                break;
            case ID_ONLINE_DOT:
                LuminaConfig.putBoolean("chatListOnlineDot", !LuminaConfig.getBoolean("chatListOnlineDot", true));
                break;
            case ID_RECENCY_DOT:
                LuminaConfig.putBoolean("chatListRecencyDot", !LuminaConfig.getBoolean("chatListRecencyDot", false));
                break;
            case ID_PREVIEW_AUTO:
                LuminaConfig.setChatListPreviewLines(0);
                break;
            case ID_PREVIEW_1:
                LuminaConfig.setChatListPreviewLines(1);
                break;
            case ID_PREVIEW_2:
                LuminaConfig.setChatListPreviewLines(2);
                break;
            case ID_PREVIEW_3:
                LuminaConfig.setChatListPreviewLines(3);
                break;
            case ID_DISABLE_SWIPE:
                LuminaConfig.putBoolean("disableChatListSwipe", !LuminaConfig.getBoolean("disableChatListSwipe", false));
                break;
            case ID_HIDE_DELETE_SWIPE:
                LuminaConfig.putBoolean("hideDeleteSwipe", !LuminaConfig.getBoolean("hideDeleteSwipe", false));
                break;
            case ID_STORIES_OFF:
                LuminaConfig.toggleStoriesFullyOff();
                storiesChanged = true;
                break;
            case ID_STORIES_POST:
                LuminaConfig.toggleStoriesHidePostEntry();
                storiesChanged = true;
                break;
        }
        if (storiesChanged && getNotificationCenter() != null) {
            // DialogsActivity re-runs updateStoriesVisibility() on storiesUpdated.
            getNotificationCenter().postNotificationName(NotificationCenter.storiesUpdated);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        // Rebuild the open dialog list so row height / badges / mute icons refresh live.
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
    }
}
