/*
 * This is the source code of LuminaGram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.messenger;

/**
 * LuminaGram: the single reserved id range for every menu / message-option / list-item id that
 * LuminaGram adds to an UPSTREAM screen.
 *
 * Upstream numbers these ids per screen from 0/1 upward and keeps appending (ChatActivity's header
 * menu reached 75 and its message options ~120 in 13.2). The 13.2 sync hit a silent collision:
 * our go_to_first_message = 75 vs upstream's new chat_menu_topics_panel = 75, so the new topics
 * side-panel button would have jumped to the first message instead. Every Lumina id now lives in
 * [BASE, BASE + 0xFFFF]: far above anything upstream allocates, positive, and outside the Android
 * resource-id space (0x01xxxxxx framework / 0x7fxxxxxx app), so it can never shadow an R.id either.
 *
 * Rules: never reuse a number, never put a Lumina id outside this class, and when an upstream
 * screen gains a Lumina item add a new constant here (grouped per screen, 100 per screen).
 */
public final class LuminaMenuIds {

    private LuminaMenuIds() {
    }

    /** 0x4C47 = "LG". */
    public static final int BASE = 0x4C470000;

    /**
     * Whether {@code id} is in the Lumina reserved range. ActionBarMenuItem only lets the long-press / drag-down
     * gesture pick sub-items whose id is below 100 (upstream convention); it also accepts this range so that our
     * sub-items (go to first message, clear all cache, save frame, ...) stay gesture-selectable like before.
     */
    public static boolean isLumina(int id) {
        return (id & 0xFFFF0000) == BASE;
    }

    // ChatActivity header (actionBar) menu
    public static final int CHAT_GO_TO_FIRST_MESSAGE = BASE + 1;
    public static final int CHAT_TRANSLATE_TOGGLE = BASE + 2;
    public static final int CHAT_AD_SHIELD = BASE + 3;          // 隱藏重複廣告: per-group mode

    // ChatActivity message context-menu options (processSelectedOption)
    public static final int CHAT_OPTION_FORWARD_NO_AUTHOR = BASE + 100;
    public static final int CHAT_OPTION_FORWARD_NO_CAPTION = BASE + 101;
    public static final int CHAT_OPTION_SAVE_TO_CLOUD = BASE + 102;
    public static final int CHAT_OPTION_SELECT_AUTHOR = BASE + 103;
    public static final int CHAT_OPTION_DETAILS = BASE + 104;
    public static final int CHAT_OPTION_BOOKMARK = BASE + 105;
    public static final int CHAT_OPTION_VOICE_TO_TEXT = BASE + 106;
    public static final int CHAT_OPTION_EXPLAIN = BASE + 107;

    // DialogsActivity header menu
    public static final int DIALOGS_CHAT_LOCK = BASE + 200;

    // PhotoViewer menu
    public static final int GALLERY_SAVE_FRAME = BASE + 300;
    public static final int GALLERY_OCR_TRANSLATE = BASE + 301;

    // CacheControlActivity menu
    public static final int CACHE_CLEAR_ALL = BASE + 400;

    // StickersActivity menu
    public static final int STICKERS_OVERFLOW = BASE + 500;
    public static final int STICKERS_EXPORT = BASE + 501;
    public static final int STICKERS_IMPORT = BASE + 502;

    // PrivacyUsersActivity (blocked users) menu
    public static final int PRIVACY_USERS_OTHER = BASE + 600;
    public static final int PRIVACY_USERS_UNBLOCK_ALL = BASE + 601;

    // TextSelectionHelper floating text-selection (ActionMode) menu
    public static final int TEXT_SELECTION_TRANSLATE = BASE + 700;

    // SettingsActivity list item (UItem id of the pinned LuminaGram hub card)
    public static final int SETTINGS_HUB = BASE + 800;
}
