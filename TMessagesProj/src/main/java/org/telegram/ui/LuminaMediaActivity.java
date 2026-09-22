package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * LuminaMediaActivity — media &amp; download preferences.
 *
 * Three independent toggles, each persisted through
 * {@link LuminaConfig#getBoolean}/{@link LuminaConfig#putBoolean} (all default false):
 *   keepOriginalFilename — save downloads under the sender's original filename.
 *   autoPauseBgVideo     — pause the playing video when the app is backgrounded.
 *   unlockAudioSpeed     — show the speed control for music / long audio.
 *
 * This page only renders the switches and persists their keys. The
 * keepOriginalFilename behaviour lives in FileLoader; the other two are
 * wired up by their own hooks that read the same keys.
 */
public class LuminaMediaActivity extends BaseFragment {

    private static final int ID_KEEP_ORIGINAL_FILENAME = 1;
    private static final int ID_AUTO_PAUSE_BG_VIDEO = 2;
    private static final int ID_UNLOCK_AUDIO_SPEED = 3;
    private static final int ID_SAVE_MEDIA_FOLDER = 4;
    private static final int ID_ATTACH_DRAG_REORDER = 5;
    private static final int ID_SEND_LARGE_PHOTOS = 6;
    private static final int ID_SWIPE_VIDEO_PIP = 7;
    private static final int ID_TRANSFER_BOOST = 8;

    private static final String KEY_KEEP_ORIGINAL_FILENAME = "keepOriginalFilename";
    private static final String KEY_AUTO_PAUSE_BG_VIDEO = "autoPauseBgVideo";
    private static final String KEY_UNLOCK_AUDIO_SPEED = "unlockAudioSpeed";
    private static final String KEY_SAVE_MEDIA_FOLDER = "saveMediaFolder";
    private static final String KEY_ATTACH_DRAG_REORDER = "attachDragReorder";

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaMediaTitle));
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
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaMediaHeader)));

        items.add(UItem.asSwitch(ID_KEEP_ORIGINAL_FILENAME, LuminaLocale.getString(R.string.LuminaMediaKeepOriginalFilename))
                .setChecked(LuminaConfig.getBoolean(KEY_KEEP_ORIGINAL_FILENAME, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMediaKeepOriginalFilenameInfo)));

        items.add(UItem.asSwitch(ID_AUTO_PAUSE_BG_VIDEO, LuminaLocale.getString(R.string.LuminaMediaAutoPauseBgVideo))
                .setChecked(LuminaConfig.getBoolean(KEY_AUTO_PAUSE_BG_VIDEO, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMediaAutoPauseBgVideoInfo)));

        items.add(UItem.asSwitch(ID_UNLOCK_AUDIO_SPEED, LuminaLocale.getString(R.string.LuminaMediaUnlockAudioSpeed))
                .setChecked(LuminaConfig.getBoolean(KEY_UNLOCK_AUDIO_SPEED, false)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMediaUnlockAudioSpeedInfo)));

        items.add(UItem.asSwitch(ID_ATTACH_DRAG_REORDER, LuminaLocale.getString(R.string.LuminaMediaDragReorder))
                .setChecked(LuminaConfig.getBoolean(KEY_ATTACH_DRAG_REORDER, true)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMediaDragReorderInfo)));

        // Show the album the saver will actually use (default included), not the raw pref.
        items.add(UItem.asButton(ID_SAVE_MEDIA_FOLDER, LuminaLocale.getString(R.string.LuminaMediaSaveFolder),
                LuminaConfig.galleryAlbumName()));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMediaSaveFolderInfo)));

        // --- Outgoing photos (Batch 8 #20) ---
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaMediaPhotoHeader)));
        final int[] qualityValues = {60, 70, 80, 90, 100};
        final String[] qualityChoices = {"60%", "70%", "80%", "90%", "100%"};
        int qIndex = 2; // 80 = stock default
        final int currentQuality = LuminaConfig.outgoingPhotoQuality;
        for (int i = 0; i < qualityValues.length; i++) {
            if (qualityValues[i] == currentQuality) {
                qIndex = i;
                break;
            }
        }
        items.add(UItem.asSlideView(qualityChoices, qIndex, index -> {
            if (index >= 0 && index < qualityValues.length) {
                LuminaConfig.setOutgoingPhotoQuality(qualityValues[index]);
            }
        }));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMediaPhotoQualityInfo)));

        items.add(UItem.asSwitch(ID_SEND_LARGE_PHOTOS, LuminaLocale.getString(R.string.LuminaMediaSendLargePhotos))
                .setChecked(LuminaConfig.sendLargePhotos));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMediaSendLargePhotosInfo)));

        // --- Video (Batch 8 #19) ---
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaMediaVideoHeader)));
        items.add(UItem.asSwitch(ID_SWIPE_VIDEO_PIP, LuminaLocale.getString(R.string.LuminaMediaSwipeVideoPip))
                .setChecked(LuminaConfig.swipeVideoToPip));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMediaSwipeVideoPipInfo)));

        // --- Large file transfers (feature #21) ---
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaMediaTransferHeader)));
        items.add(UItem.asSwitch(ID_TRANSFER_BOOST, LuminaLocale.getString(R.string.LuminaMediaTransferBoost))
                .setChecked(LuminaConfig.transferBoost));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaMediaTransferBoostInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_SAVE_MEDIA_FOLDER) {
            Context context = getParentActivity();
            if (context == null) {
                return;
            }
            String current = LuminaConfig.getString(KEY_SAVE_MEDIA_FOLDER, "");
            AlertsCreator.createSimpleTextInputAlert(context, this,
                    LuminaLocale.getString(R.string.LuminaMediaSaveFolder),
                    LuminaLocale.getString(R.string.LuminaMediaSaveFolderInfo),
                    LuminaLocale.getString(R.string.LuminaMediaSaveFolderHint),
                    current, 48, LocaleController.getString(R.string.Save), getResourceProvider(),
                    text -> {
                        // Empty keeps "use the default album"; anything else is stored already
                        // sanitized so the row never advertises a name the saver would rewrite.
                        String cleaned = text == null ? "" : text.trim();
                        if (!cleaned.isEmpty()) {
                            cleaned = LuminaConfig.sanitizeAlbumName(cleaned);
                        }
                        LuminaConfig.putString(KEY_SAVE_MEDIA_FOLDER, cleaned);
                        if (listView != null && listView.adapter != null) {
                            listView.adapter.update(true);
                        }
                    });
            return;
        }
        if (item.id == ID_ATTACH_DRAG_REORDER) {
            LuminaConfig.putBoolean(KEY_ATTACH_DRAG_REORDER, !LuminaConfig.getBoolean(KEY_ATTACH_DRAG_REORDER, true));
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            return;
        }
        if (item.id == ID_SEND_LARGE_PHOTOS) {
            LuminaConfig.toggleSendLargePhotos();
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            return;
        }
        if (item.id == ID_SWIPE_VIDEO_PIP) {
            LuminaConfig.toggleSwipeVideoToPip();
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            return;
        }
        if (item.id == ID_TRANSFER_BOOST) {
            LuminaConfig.toggleTransferBoost();
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            return;
        }
        final String key;
        switch (item.id) {
            case ID_KEEP_ORIGINAL_FILENAME:
                key = KEY_KEEP_ORIGINAL_FILENAME;
                break;
            case ID_AUTO_PAUSE_BG_VIDEO:
                key = KEY_AUTO_PAUSE_BG_VIDEO;
                break;
            case ID_UNLOCK_AUDIO_SPEED:
                key = KEY_UNLOCK_AUDIO_SPEED;
                break;
            default:
                return;
        }
        LuminaConfig.putBoolean(key, !LuminaConfig.getBoolean(key, false));
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
