package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
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
 * LuminaGram chat &amp; media enhancements settings.
 * Mirrors {@link LuminaGramSettingsActivity} (UItem / UniversalRecyclerView).
 * SAFE toggles are persisted via {@link LuminaConfig} generic accessors.
 */
public class LuminaChatActivity extends BaseFragment {

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaChatSettings));
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
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaMessageActions)));
        items.add(UItem.asSwitch(1, LuminaLocale.getString(R.string.LuminaForwardNoAuthorTitle)).setChecked(LuminaConfig.getBoolean("forwardNoAuthor", false)));
        items.add(UItem.asSwitch(2, LuminaLocale.getString(R.string.LuminaForwardNoCaptionTitle)).setChecked(LuminaConfig.getBoolean("forwardNoCaption", false)));
        items.add(UItem.asSwitch(3, LuminaLocale.getString(R.string.LuminaSaveToCloudTitle)).setChecked(LuminaConfig.getBoolean("saveToCloud", true)));
        items.add(UItem.asSwitch(4, LuminaLocale.getString(R.string.LuminaSelectFromAuthorTitle)).setChecked(LuminaConfig.getBoolean("selectFromAuthor", true)));
        items.add(UItem.asSwitch(12, LuminaLocale.getString(R.string.LuminaShowBookmarks)).setChecked(LuminaConfig.getBoolean("showBookmarks", true)));
        items.add(UItem.asSwitch(11, LuminaLocale.getString(R.string.LuminaHideKeyboardOnScroll)).setChecked(LuminaConfig.getBoolean("hideKeyboardOnScroll", false)));
        items.add(UItem.asShadow(null));

        // Each explanatory shadow must directly follow the row it describes, so the
        // toggles that carry their own description live in their own block.
        items.add(UItem.asSwitch(13, LuminaLocale.getString(R.string.LuminaUndoSendWindow)).setChecked(LuminaConfig.getBoolean("undoSendWindow", false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaUndoSendWindowInfo)));

        items.add(UItem.asSwitch(14, LuminaLocale.getString(R.string.LuminaForwardWarningTitle)).setChecked(LuminaConfig.getBoolean("forwardOriginWarning", true)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaForwardWarningInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaMediaSaving)));
        items.add(UItem.asSwitch(5, LuminaLocale.getString(R.string.LuminaSaveStickers)).setChecked(LuminaConfig.getBoolean("saveStickers", true)));
        items.add(UItem.asSwitch(10, LuminaLocale.getString(R.string.LuminaSendAsFileDefault)).setChecked(LuminaConfig.getBoolean("sendAsFileDefault", false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaSendAsFileDefaultInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaInfoDensity)));
        items.add(UItem.asSwitch(6, LuminaLocale.getString(R.string.LuminaShowDcId)).setChecked(LuminaConfig.getBoolean("showDcId", false)));
        items.add(UItem.asSwitch(8, LuminaLocale.getString(R.string.LuminaShowMessageDetails)).setChecked(LuminaConfig.getBoolean("showMessageDetails", true)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaShowDcIdInfo)));

        items.add(UItem.asSwitch(7, LuminaLocale.getString(R.string.LuminaShowChatDate)).setChecked(LuminaConfig.getBoolean("showChatDate", true)));
        items.add(UItem.asSwitch(9, LuminaLocale.getString(R.string.LuminaTimeWithSeconds)).setChecked(LuminaConfig.getBoolean("timeWithSeconds", false)));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case 1:
                LuminaConfig.putBoolean("forwardNoAuthor", !LuminaConfig.getBoolean("forwardNoAuthor", false));
                break;
            case 2:
                LuminaConfig.putBoolean("forwardNoCaption", !LuminaConfig.getBoolean("forwardNoCaption", false));
                break;
            case 3:
                LuminaConfig.putBoolean("saveToCloud", !LuminaConfig.getBoolean("saveToCloud", true));
                break;
            case 4:
                LuminaConfig.putBoolean("selectFromAuthor", !LuminaConfig.getBoolean("selectFromAuthor", true));
                break;
            case 12:
                LuminaConfig.putBoolean("showBookmarks", !LuminaConfig.getBoolean("showBookmarks", true));
                break;
            case 5:
                LuminaConfig.putBoolean("saveStickers", !LuminaConfig.getBoolean("saveStickers", true));
                break;
            case 10:
                LuminaConfig.putBoolean("sendAsFileDefault", !LuminaConfig.getBoolean("sendAsFileDefault", false));
                break;
            case 11:
                LuminaConfig.putBoolean("hideKeyboardOnScroll", !LuminaConfig.getBoolean("hideKeyboardOnScroll", false));
                break;
            case 13:
                LuminaConfig.putBoolean("undoSendWindow", !LuminaConfig.getBoolean("undoSendWindow", false));
                break;
            case 14:
                LuminaConfig.putBoolean("forwardOriginWarning", !LuminaConfig.getBoolean("forwardOriginWarning", true));
                break;
            case 6:
                LuminaConfig.putBoolean("showDcId", !LuminaConfig.getBoolean("showDcId", false));
                break;
            case 7:
                LuminaConfig.putBoolean("showChatDate", !LuminaConfig.getBoolean("showChatDate", true));
                break;
            case 8:
                LuminaConfig.putBoolean("showMessageDetails", !LuminaConfig.getBoolean("showMessageDetails", true));
                break;
            case 9:
                LuminaConfig.putBoolean("timeWithSeconds", !LuminaConfig.getBoolean("timeWithSeconds", false));
                LocaleController.getInstance().recreateFormatters();
                break;
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }
}
