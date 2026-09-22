package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * LuminaGram global feature-flag store.
 * Style mirrors SharedConfig / exteraGram's ExteraConfig: a private prefs file
 * ("luminagram") cached in public static fields, reachable from any render hook
 * without a currentAccount in scope.
 */
public class LuminaConfig {

    private static final Object sync = new Object();
    private static boolean configLoaded;

    public static SharedPreferences preferences;
    public static SharedPreferences.Editor editor;

    // ---- Chat-list / tabs / stories customization ----
    public static boolean hideTabs;
    public static boolean hideStories;
    // "Stories, fully off" master switch (Wave 32). Independent of hideStories so that
    // existing users keep exactly their current behavior; when on it implies hideStories.
    public static boolean storiesFullyOff;
    // Sub-option of storiesFullyOff: also hide the entries that post YOUR OWN story.
    public static boolean storiesHidePostEntry;
    public static boolean compactChatList;
    public static int chatListPreviewLines;      // 0 = follow Telegram list layout (default); 1/2/3 = forced preview lines

    // ---- Translation ----
    public static boolean translateBeforeSend;
    public static boolean translateBeforeSendConfirm;

    // ---- Appearance (Wave 2) ----
    public static boolean materialYouEnabled;   // Android 12+ dynamic colors (Monet)
    public static boolean customAccentEnabled;   // manual accent override (when Material You off)
    public static int customAccentColor;         // 0 = none picked yet
    public static int appFont;                   // 0 = Telegram default, 1 = System, 2 = Serif, 3 = Monospace

    // ---- Anti-scam / security ----
    // Warn when a stranger's display name / username uses visual look-alike
    // (homoglyph) characters commonly used to impersonate a trusted account.
    public static boolean homoglyphWarn = true;
    // ---- Anti-scam: file masquerade guard (Wave) ----
    // Warn before opening a document whose displayed name / type hides what it really is
    // (RTL-override filenames, or executables disguised as media/pdf, e.g. "EvilVideo").
    // Purely local check; default ON so the protection is opt-out, not opt-in. Initialized to
    // true at declaration so that even the degraded prefs-load path (preferences == null) leaves
    // the guard armed rather than silently off.
    public static boolean fileMasqueradeGuard = true;

    // ---- Input field (Wave) ----
    // Hide the AI Editor icon Telegram shows inside the message input field
    // (the "Rewrite / translate / correct with AI" button). Default OFF = keep stock
    // behavior (button shown); when ON that single icon is never displayed. Fail-safe:
    // this only gates that one icon's visibility, so a wrong value at worst shows the
    // stock button rather than breaking the input row.
    public static boolean hideInputAiButton;

    // ---- Input row / composer (Batch 3) ----
    // Free-form boolean prefs read directly via getBoolean(...) at their call sites (same
    // literal-key pattern as disableLinkPreview / confirmSendVoiceVideo), so no cached field is
    // required. All default false = current stock behavior; each is opt-in from LuminaInterfaceActivity:
    //   "hideVoiceButton"      #10 hide the voice/video record button (ChatActivityEnterView)
    //   "hideSendAsButton"     #10 hide the send-as / post-as-channel button (ChatActivityEnterView)
    //   "disableThanosDelete"  #10 disable the "dust" delete animation (gated in ChatActivity)
    //   "sendByReturnKey"      #15 soft-keyboard Return sends instead of newline (ChatActivityEnterView)
    //   "formattingBar"        #23 quick Bold/Italic/Link bar over the input (ChatActivityEnterView)

    // ---- Cultural annotation "Explain this message" (Wave) ----
    // Adds an "Explain" action to the message long-press menu that sends the message text to
    // the user's OWN translation LLM (same key/base/model as the LLM translate provider) and
    // shows a card with its literal meaning, real tone, cultural/slang notes and a suggested
    // reply. Default ON; initialized true at declaration so the degraded prefs-load path
    // (preferences == null) still offers the action rather than silently hiding it.
    public static boolean explainMessage = true;
    // ---- Image OCR translation (Wave) ----
    // When viewing a full-screen image, offer to recognize the text inside it (on-device
    // ML Kit OCR) and translate it with the user's chosen engine, shown in an overlay panel.
    // Default ON so the action is opt-out; initialized true so even the degraded prefs-load
    // path (preferences == null) leaves the feature available. Only gates the menu action's
    // visibility, so a wrong value at worst hides one optional menu entry.
    public static boolean ocrTranslate = true;
    // ---- Reverse voice (advanced, Wave) ----
    // Type text, translate it to the recipient's language, synthesize it with the
    // on-device system TTS engine and send the result as a real voice message.
    // Advanced / opt-in: default OFF. Local-only (system TTS + the fork's own translate
    // engine); never does voice cloning. Trigger: long-press Send in a chat.
    public static boolean reverseVoice;
    // ---- Stranger request inbox (Wave) ----
    // Divert unsolicited 1:1 messages from non-contacts (no known common group) out of the
    // main chat list into a dedicated request inbox. Purely a local DISPLAY diversion: no
    // server call, and receive / unread / read / typing / online state are never touched.
    // Default OFF so enabling the fork never silently changes an existing user's chat list.
    public static boolean strangerInbox;

    // ---- Update channel (Wave) ----
    // When ON the in-app self-updater polls the BETA manifest (version-beta.json) and
    // auto-updates to test builds; when OFF it polls the STABLE manifest (version.json)
    // and only sees promoted builds. Default OFF so existing users stay on stable.
    public static boolean betaChannel;

    // ---- Message-bubble display gates (Wave) ----
    // All three default OFF so enabling the fork changes nothing until the user opts in.
    // Read from ChatMessageCell / ReactionsLayoutInBubble draw+measure hot paths, so they are
    // plain static-field reads (no prefs I/O per frame), refreshed by loadConfig().
    // Hide the reaction chips under messages (skips draw AND measure -> no empty gap).
    public static boolean hideReactions;
    // Render incoming broadcast-channel text posts at (near) full available width (reading layout).
    public static boolean wideChannelPosts;

    // ---- Outgoing photo quality / size (Batch 8 #20) ----
    // JPEG quality used when compressing a photo you SEND, plus a "send larger" toggle that
    // routes the standard send through Telegram's own larger decode/scale bounds (2560 vs 1280).
    // Both go through the NORMAL upload pipeline; only the compression/resize params change.
    // Defaults are exactly the stock values: quality 80, sendLargePhotos OFF -> byte-identical
    // to upstream. outgoingPhotoQuality is initialised to 80 at declaration so even the degraded
    // prefs-load path (preferences == null) still sends at the stock quality.
    public static int outgoingPhotoQuality = 80;
    public static boolean sendLargePhotos;
    // ---- Swipe-down video -> picture-in-picture (Batch 8 #19) ----
    // When ON, swiping a full-screen playing video downward drops it into PiP instead of closing
    // the viewer. Default OFF = stock behavior (swipe closes). Read once at gesture-release time.
    public static boolean swipeVideoToPip;

    // ---- Large-file transfer boost (feature #21) ----
    // Raise the MTProto upload/download part size (and download concurrency) so large
    // files move with fewer round trips. Default OFF: when off, FileLoadOperation and
    // FileUploadOperation take their existing stock code paths untouched and every part
    // size / part-count value is byte-identical to upstream. When on, downloads use 1 MB
    // parts and uploads use 512 KB parts -- both server-valid protocol maxima (see the
    // hooks). Plain static-field read from the transfer hot paths, refreshed by loadConfig().
    public static boolean transferBoost;

    // ---- Message filter (Batch 4 #22) ----
    // A user keyword list; incoming messages whose visible text matches any keyword are dropped
    // from the to-be-displayed list before it is built (pure client-side display filter -- the
    // user's own choice, never content-locking; nothing is deleted server-side). Persisted as a
    // newline-joined string under KEY_MESSAGE_FILTER and cached as a lowercased List for the
    // per-message check. Default = empty list = no filtering (stock behavior).
    public static final String KEY_MESSAGE_FILTER = "messageFilterKeywords";
    public static java.util.List<String> messageFilterKeywords = new java.util.ArrayList<>();

    static {
        loadConfig();
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded) {
                return;
            }
            try {
                preferences = ApplicationLoader.applicationContext
                        .getSharedPreferences("luminagram", Activity.MODE_PRIVATE);
                editor = preferences.edit();

                hideTabs = preferences.getBoolean("hideTabs", false);
                hideStories = preferences.getBoolean("hideStories", false);
                storiesFullyOff = preferences.getBoolean("storiesFullyOff", false);
                storiesHidePostEntry = preferences.getBoolean("storiesHidePostEntry", false);
                compactChatList = preferences.getBoolean("compactChatList", false);
                chatListPreviewLines = preferences.getInt("chatListPreviewLines", 0);
                translateBeforeSend = preferences.getBoolean("translateBeforeSend", true);
                translateBeforeSendConfirm = preferences.getBoolean("translateBeforeSendConfirm", false);
                materialYouEnabled = preferences.getBoolean("materialYouEnabled", false);
                customAccentEnabled = preferences.getBoolean("customAccentEnabled", false);
                customAccentColor = preferences.getInt("customAccentColor", 0);
                appFont = preferences.getInt("appFont", 0);
                homoglyphWarn = preferences.getBoolean("homoglyphWarn", true);
                fileMasqueradeGuard = preferences.getBoolean("fileMasqueradeGuard", true);
                ocrTranslate = preferences.getBoolean("ocrTranslate", true);
                hideInputAiButton = preferences.getBoolean("hideInputAiButton", false);
                explainMessage = preferences.getBoolean("explainMessage", true);
                reverseVoice = preferences.getBoolean("reverseVoice", false);
                strangerInbox = preferences.getBoolean("strangerInbox", false);
                betaChannel = preferences.getBoolean("betaChannel", false);
                hideReactions = preferences.getBoolean("hideReactions", false);
                wideChannelPosts = preferences.getBoolean("wideChannelPosts", false);
                outgoingPhotoQuality = preferences.getInt("outgoingPhotoQuality", 80);
                sendLargePhotos = preferences.getBoolean("sendLargePhotos", false);
                swipeVideoToPip = preferences.getBoolean("swipeVideoToPip", false);
                transferBoost = preferences.getBoolean("transferBoost", false);
                reloadMessageFilter();

                configLoaded = true;
            } catch (Throwable e) {
                // A prefs failure here must never poison this class: an uncaught throw in the
                // static initializer becomes ExceptionInInitializerError and bricks EVERY
                // LuminaConfig access (incl. the passcode screen). Leave fields at their safe
                // defaults and null out the store; the accessors below null-guard it.
                preferences = null;
                editor = null;
            }
            // Push the persisted appearance into the render hooks (Theme accent + font override).
            applyAppearance();
            // Re-arm the receive-side voice-to-text auto pipeline across app restarts. Strictly
            // opt-in (sttAutoPipeline defaults to false) and self-deferring onto the main thread,
            // so the default build registers nothing at all. Never allowed to throw: this runs
            // inside the static initializer.
            try {
                if (preferences != null && preferences.getBoolean("sttAutoPipeline", false)) {
                    LuminaVoiceToText.ensureAutoPipelineInstalled();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    // Generic accessors (used by later feature batches)
    public static boolean getBoolean(String key, boolean def) {
        if (preferences == null) {
            return def;
        }
        return preferences.getBoolean(key, def);
    }

    public static void putBoolean(String key, boolean value) {
        if (editor == null) {
            return;
        }
        editor.putBoolean(key, value).apply();
    }

    // Generic string accessors — used by the multi-provider translation settings
    // (provider id, per-provider API keys, base URL, model, prompt). Values live only
    // in the app-private "luminagram" prefs and are never logged.
    public static String getString(String key, String def) {
        if (preferences == null) {
            return def;
        }
        return preferences.getString(key, def);
    }

    public static void putString(String key, String value) {
        if (editor == null) {
            return;
        }
        editor.putString(key, value).apply();
    }

    // Generic int accessors (percent-style feature values, e.g. sticker render scale).
    public static int getInt(String key, int def) {
        if (preferences == null) {
            return def;
        }
        return preferences.getInt(key, def);
    }

    public static void putInt(String key, int value) {
        if (editor == null) {
            return;
        }
        editor.putInt(key, value).apply();
    }

    // LuminaGram #6: keep the cached static field in sync with the persisted value so the
    // per-frame chat-list render path (DialogCell) reads a plain field, never SharedPreferences.
    public static void setChatListPreviewLines(int lines) {
        if (lines < 0 || lines > 3) {
            lines = 0;
        }
        chatListPreviewLines = lines;
        putInt("chatListPreviewLines", lines);
    }

    // ---- Message filter (Batch 4 #22) helpers ----

    /** Rebuild the cached, lowercased keyword list from the persisted newline-joined string. */
    public static void reloadMessageFilter() {
        final java.util.ArrayList<String> out = new java.util.ArrayList<>();
        final String raw = getString(KEY_MESSAGE_FILTER, "");
        if (raw != null && raw.length() > 0) {
            final String[] lines = raw.split("\n");
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                final String t = line.trim();
                if (t.length() > 0) {
                    out.add(t.toLowerCase());
                }
            }
        }
        messageFilterKeywords = out;
    }

    /** True when the user has at least one filter keyword configured. */
    public static boolean hasMessageFilter() {
        final java.util.List<String> kws = messageFilterKeywords;
        return kws != null && !kws.isEmpty();
    }

    /**
     * True when this message's visible text contains any filter keyword (case-insensitive
     * substring). Purely a display decision -- nothing is deleted server-side.
     */
    public static boolean isMessageFiltered(MessageObject mo) {
        final java.util.List<String> kws = messageFilterKeywords;
        if (mo == null || kws == null || kws.isEmpty()) {
            return false;
        }
        try {
            final StringBuilder sb = new StringBuilder();
            if (mo.messageOwner != null && mo.messageOwner.message != null) {
                sb.append(mo.messageOwner.message);
            }
            if (mo.caption != null) {
                sb.append(' ').append(mo.caption);
            }
            if (sb.length() == 0 && mo.messageText != null) {
                sb.append(mo.messageText);
            }
            if (sb.length() == 0) {
                return false;
            }
            final String hay = sb.toString().toLowerCase();
            for (int i = 0; i < kws.size(); i++) {
                final String k = kws.get(i);
                if (k.length() > 0 && hay.contains(k)) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** Drop every keyword-matched message from a to-be-displayed list, in place. */
    public static void filterMessages(java.util.List<MessageObject> list) {
        final java.util.List<String> kws = messageFilterKeywords;
        if (list == null || list.isEmpty() || kws == null || kws.isEmpty()) {
            return;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (isMessageFiltered(list.get(i))) {
                list.remove(i);
            }
        }
    }

    // ---- Notification fine control (#18) ----
    // Two independent, opt-in gates over the OS-notification pipeline, read by
    // NotificationsController when it decides whether a freshly-arrived (or reloaded)
    // message should raise a notification. Both keys default to false, so a user who
    // never touches them -- and every stock build -- behaves byte-for-byte like upstream:
    // shouldSuppressNotification() returns false and the caller takes its normal path.
    // These gates ONLY suppress the notification; the caller never marks anything read
    // and never changes an unread count as a result of this return value.
    public static final String KEY_MUTE_NOTIFY_PINNED = "muteNotifyPinned";
    public static final String KEY_MUTE_NOTIFY_MENTIONS = "muteNotifyMentions";

    /**
     * True when this message must NOT raise an OS notification under the user's #18
     * settings: a pinned-message service event when "mute pinned" is on, or a message
     * that @mentions the user / replies to their message when "mute mentions & replies"
     * is on. Never throws; on any doubt it returns false (= show the notification).
     */
    public static boolean shouldSuppressNotification(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return false;
        }
        try {
            if (getBoolean(KEY_MUTE_NOTIFY_PINNED, false)
                    && mo.messageOwner.action instanceof org.telegram.tgnet.TLRPC.TL_messageActionPinMessage) {
                return true;
            }
            if (getBoolean(KEY_MUTE_NOTIFY_MENTIONS, false) && mo.messageOwner.mentioned) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    // True only when {@code key} has actually been written to the prefs (as opposed to
    // returning a caller-supplied default). Used by the disguise vault to detect legacy
    // decoy-lock users whose new {@code vaultEnabled} flag has never been persisted.
    public static boolean contains(String key) {
        if (preferences == null) {
            return false;
        }
        return preferences.contains(key);
    }

    // ---- Public gallery album ----
    // "Save to gallery" exports into a named album under the shared media roots:
    // Pictures/<album>, Movies/<album>, Download/<album>, Music/<album>.
    //
    // Upstream hardcodes "Telegram" at every one of those call sites. LuminaGram is a
    // separate application and must not create or write into another app's album, so the
    // default is DEFAULT_GALLERY_ALBUM. Anything exported before this change keeps living
    // in LEGACY_GALLERY_ALBUM: that directory is SHARED public storage which the genuine
    // Telegram app (or another fork) may own on the same device, so we never rename, move
    // or delete it. Nothing in the app ever reads the album back — it is a one-way export
    // target — so the old files stay exactly where they were and stay indexed by
    // MediaStore. A user who wants the old layout can type LEGACY_GALLERY_ALBUM into the
    // "Save media to folder" setting.
    public static final String DEFAULT_GALLERY_ALBUM = "LuminaGram";
    public static final String LEGACY_GALLERY_ALBUM = "Telegram";
    public static final String KEY_SAVE_MEDIA_FOLDER = "saveMediaFolder";

    /** Effective album name for every gallery export. Never null, never empty. */
    public static String galleryAlbumName() {
        return sanitizeAlbumName(getString(KEY_SAVE_MEDIA_FOLDER, ""));
    }

    /**
     * Reduce a user-supplied folder name to a single safe path segment.
     * The value flows straight into {@code new File(...)} and into
     * {@code MediaStore.MediaColumns.RELATIVE_PATH}, so separators, drive/reserved
     * characters and control characters are stripped rather than trusted, and a leading
     * dot (".", "..", hidden dirs) is removed. An empty result falls back to the default.
     */
    public static String sanitizeAlbumName(String raw) {
        if (raw == null) {
            return DEFAULT_GALLERY_ALBUM;
        }
        String name = raw.trim();
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < ' ') {
                continue;
            }
            sb.append(c);
        }
        name = sb.toString().trim();
        while (name.startsWith(".")) {
            name = name.substring(1).trim();
        }
        if (name.length() > 48) {
            name = name.substring(0, 48).trim();
        }
        return name.isEmpty() ? DEFAULT_GALLERY_ALBUM : name;
    }

    // ---- Message bookmarks / collections (Wave 3) ----
    // A local, client-side alternative to Saved Messages. Bookmarks live only in the
    // app-private "luminagram" prefs as a JSON array string under the "bookmarks" key
    // (via getString/putString) — nothing is ever sent to Telegram. Each entry:
    //   { "dialogId": <long>, "messageId": <int>, "snippet": <String>, "date": <long ms> }
    public static final String KEY_BOOKMARKS = "bookmarks";

    /** All saved bookmarks (oldest first, matching insertion order). Never null. */
    public static org.json.JSONArray getBookmarks() {
        String raw = getString(KEY_BOOKMARKS, "");
        if (raw != null && raw.length() > 0) {
            try {
                return new org.json.JSONArray(raw);
            } catch (org.json.JSONException ignore) {
            }
        }
        return new org.json.JSONArray();
    }

    public static boolean isBookmarked(long dialogId, int messageId) {
        org.json.JSONArray arr = getBookmarks();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("dialogId") == dialogId && o.optInt("messageId") == messageId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Toggle a bookmark: add it when absent, remove it when already present.
     * @return true if the message is bookmarked after this call, false if it was removed.
     */
    public static boolean toggleBookmark(long dialogId, int messageId, String snippet) {
        org.json.JSONArray arr = getBookmarks();
        org.json.JSONArray out = new org.json.JSONArray();
        boolean removed = false;
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (o.optLong("dialogId") == dialogId && o.optInt("messageId") == messageId) {
                removed = true; // drop the existing entry
                continue;
            }
            out.put(o);
        }
        if (!removed) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("dialogId", dialogId);
                o.put("messageId", messageId);
                o.put("snippet", snippet == null ? "" : snippet);
                o.put("date", System.currentTimeMillis());
                out.put(o);
            } catch (org.json.JSONException ignore) {
            }
        }
        putString(KEY_BOOKMARKS, out.toString());
        return !removed;
    }

    /** Remove a single bookmark if present (used by the Bookmarks list's delete action). */
    public static void removeBookmark(long dialogId, int messageId) {
        org.json.JSONArray arr = getBookmarks();
        org.json.JSONArray out = new org.json.JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (o.optLong("dialogId") == dialogId && o.optInt("messageId") == messageId) {
                continue;
            }
            out.put(o);
        }
        putString(KEY_BOOKMARKS, out.toString());
    }

    // ---- Text replacer / auto-substitution (Wave 8) ----
    // User-defined substitution rules ("brb" -> "be right back") applied to OUTGOING
    // plain-text messages just before send (see SendMessagesHelper.sendMessage). Rules
    // live only in the app-private "luminagram" prefs as a JSON array string under the
    // "textReplacements" key (via getString/putString) -- nothing is sent to Telegram.
    // Each entry: { "from": <String>, "to": <String> }.
    public static final String KEY_TEXT_REPLACEMENTS = "textReplacements";

    /** All substitution rules (insertion order). Never null; empty when none / parse error. */
    public static org.json.JSONArray getTextReplacements() {
        String raw = getString(KEY_TEXT_REPLACEMENTS, "");
        if (raw != null && raw.length() > 0) {
            try {
                return new org.json.JSONArray(raw);
            } catch (org.json.JSONException ignore) {
            }
        }
        return new org.json.JSONArray();
    }

    /**
     * Apply the user's substitution rules to an outgoing plain-text message.
     * Each rule replaces whole-word, case-sensitive occurrences of "from" with "to"
     * (regex word boundaries, so "brb" does not fire inside "abrbcd"). Returns the
     * input unchanged when there are no rules -- the default, zero-change behavior.
     */
    public static String applyTextReplacements(String message) {
        if (message == null || message.length() == 0) {
            return message;
        }
        org.json.JSONArray arr = getTextReplacements();
        if (arr.length() == 0) {
            return message;
        }
        String result = message;
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String from = o.optString("from", "");
            String to = o.optString("to", "");
            if (from.length() == 0) {
                continue;
            }
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "\\b" + java.util.regex.Pattern.quote(from) + "\\b");
                result = p.matcher(result).replaceAll(java.util.regex.Matcher.quoteReplacement(to));
            } catch (Exception ignore) {
                // Malformed rule: skip it, never block the send.
            }
        }
        return result;
    }

    // ---- Translation glossary / do-not-translate list (global) ----
    // A flat JSON array of user-defined terms that must survive translation verbatim
    // (brand names, product models, people, handles). Stored in the app-private
    // "luminagram" prefs under this key -- nothing is ever sent to Telegram. @usernames
    // and http(s) links are always protected by LuminaGlossary regardless of this list.
    public static final String KEY_GLOSSARY_TERMS = "glossaryTerms";

    /** All do-not-translate terms (insertion order). Never null; empty on parse error. */
    public static org.json.JSONArray getGlossaryTerms() {
        String raw = getString(KEY_GLOSSARY_TERMS, "");
        if (raw != null && raw.length() > 0) {
            try {
                return new org.json.JSONArray(raw);
            } catch (org.json.JSONException ignore) {
            }
        }
        return new org.json.JSONArray();
    }

    // ---- Languages the user already reads ("only translate what I can't read") ----
    // A LOCAL, display-only list of language codes the user understands. Used by
    // TranslateController to SKIP translating group/channel messages whose detected
    // source language is already one of these (saves translation quota + hides
    // redundant sub-lines). Stored in the app-private "luminagram" prefs as a plain
    // comma-separated string of normalized language codes, e.g. "zh,en" (a JSON array
    // string is also accepted on read). Nothing is ever sent to Telegram.
    //
    // Default (when the user has never customised the list): the interface language
    // PLUS the configured dual-language READ language (trReadLang), if any.
    public static final String KEY_MY_LANGUAGES = "myLanguages";
    // On/off switch for the group-skip optimisation (default ON). When off, group
    // translation behaves exactly like upstream (translate every foreign message).
    public static final String KEY_GROUP_SKIP_MY_LANGUAGES = "groupSkipMyLanguages";

    /** True when the "skip languages I already read" optimisation is enabled (default ON). */
    public static boolean isGroupSkipMyLanguagesEnabled() {
        return getBoolean(KEY_GROUP_SKIP_MY_LANGUAGES, true);
    }

    public static void toggleGroupSkipMyLanguages() {
        putBoolean(KEY_GROUP_SKIP_MY_LANGUAGES, !isGroupSkipMyLanguagesEnabled());
    }

    /**
     * Canonicalise a raw language code for membership tests: lower-cased, trimmed, with
     * any region/script subtag stripped ("zh-Hans" / "zh_CN" / "en-US" -> "zh" / "en"),
     * and the app-wide Bokmal alias applied ("nb" -> "no"). Returns "" for blank input.
     */
    public static String normalizeLangCode(String lang) {
        if (lang == null) {
            return null;
        }
        String s = lang.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '-' || ch == '_') {
                cut = i;
                break;
            }
        }
        s = s.substring(0, cut);
        if ("nb".equals(s)) {
            s = "no";
        }
        return s;
    }

    /** Parse a stored value (comma/space/semicolon separated, or a JSON array) into a normalized set. */
    private static java.util.Set<String> parseLangSet(String raw) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        raw = raw.trim();
        if (raw.isEmpty()) {
            return out;
        }
        if (raw.charAt(0) == '[') {
            try {
                org.json.JSONArray a = new org.json.JSONArray(raw);
                for (int i = 0; i < a.length(); i++) {
                    String c = normalizeLangCode(a.optString(i));
                    if (c != null && !c.isEmpty()) {
                        out.add(c);
                    }
                }
                return out;
            } catch (org.json.JSONException ignore) {
                // fall through to delimiter parsing
            }
        }
        for (String part : raw.split("[,;\\s]+")) {
            String c = normalizeLangCode(part);
            if (c != null && !c.isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    private static String joinLangSet(java.util.Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** Interface language + configured READ language (trReadLang) -- the fallback set. */
    public static java.util.Set<String> getDefaultMyLanguages() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            String iface = LocaleController.getInstance().getCurrentLocaleInfo().pluralLangCode;
            String c = normalizeLangCode(iface);
            if (c != null && !c.isEmpty()) {
                out.add(c);
            }
        } catch (Throwable ignore) {
        }
        try {
            String read = getString("trReadLang", "");
            String c = normalizeLangCode(read);
            if (c != null && !c.isEmpty()) {
                out.add(c);
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    /**
     * Languages the user reads. When the key has never been written, this is the live
     * default (interface language + trReadLang). Once the user customises the list, the
     * stored value wins verbatim -- an explicitly emptied list means "translate everything".
     */
    public static java.util.Set<String> getMyLanguages() {
        if (!contains(KEY_MY_LANGUAGES)) {
            return getDefaultMyLanguages();
        }
        return parseLangSet(getString(KEY_MY_LANGUAGES, ""));
    }

    /** Membership test used by the translate pipeline; normalizes {@code lang} first. */
    public static boolean isMyLanguage(String lang) {
        String n = normalizeLangCode(lang);
        if (n == null || n.isEmpty()) {
            return false;
        }
        return getMyLanguages().contains(n);
    }

    /** User-editable value for the settings UI; shows the computed default until customised. */
    public static String getMyLanguagesRaw() {
        if (!contains(KEY_MY_LANGUAGES)) {
            return joinLangSet(getDefaultMyLanguages());
        }
        return getString(KEY_MY_LANGUAGES, "");
    }

    /** Persist a user-entered list; normalized + de-duped so the stored value is canonical. */
    public static void setMyLanguages(String raw) {
        putString(KEY_MY_LANGUAGES, joinLangSet(parseLangSet(raw)));
    }

    // ---- Single-chat lock / private folder (LuminaGram) ----
    // A purely LOCAL, display-only "private folder": dialogIds the user chose to hide from the
    // chat list and search until they type a secret reveal code. Stored only in the app-private
    // "luminagram" prefs as a JSON array string of dialogIds under the "lockedChats" key (via
    // getString/putString) -- nothing is ever sent to Telegram, and NO receive/read/typing/
    // online state is touched. See LuminaChatLock for the session reveal + display predicate.
    public static final String KEY_LOCKED_CHATS = "lockedChats";
    // Optional dedicated reveal code. When empty, LuminaChatLock falls back to the vault's
    // decoyUnlockCode so existing vault users get chat-lock reveal for free.
    public static final String KEY_CHAT_LOCK_CODE = "chatLockCode";

    /** All locked dialogIds (insertion order). Never null; empty on none / parse error. */
    public static org.json.JSONArray getLockedChatIds() {
        String raw = getString(KEY_LOCKED_CHATS, "");
        if (raw != null && raw.length() > 0) {
            try {
                return new org.json.JSONArray(raw);
            } catch (org.json.JSONException ignore) {
            }
        }
        return new org.json.JSONArray();
    }

    /** True when at least one chat is in the locked set. */
    public static boolean hasAnyLockedChat() {
        try {
            return getLockedChatIds().length() > 0;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** Membership test for the locked set (does NOT consider the session reveal state). */
    public static boolean isChatInLockedSet(long dialogId) {
        try {
            org.json.JSONArray arr = getLockedChatIds();
            for (int i = 0; i < arr.length(); i++) {
                if (arr.optLong(i) == dialogId) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** Add ({@code locked=true}) or remove ({@code locked=false}) a dialog from the locked set. */
    public static void setChatLocked(long dialogId, boolean locked) {
        try {
            if (dialogId == 0) {
                return;
            }
            org.json.JSONArray arr = getLockedChatIds();
            org.json.JSONArray out = new org.json.JSONArray();
            boolean present = false;
            for (int i = 0; i < arr.length(); i++) {
                long v = arr.optLong(i);
                if (v == dialogId) {
                    present = true;
                    if (!locked) {
                        continue; // drop it
                    }
                }
                out.put(v);
            }
            if (locked && !present) {
                out.put(dialogId);
            }
            putString(KEY_LOCKED_CHATS, out.toString());
        } catch (Throwable ignore) {
        }
    }

    // Typed toggles keep the static field and the persisted value in sync (XOR idiom)
    public static void toggleHideTabs() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("hideTabs", hideTabs ^= true).apply();
    }

    public static void toggleHideStories() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("hideStories", hideStories ^= true).apply();
    }

    public static void toggleStoriesFullyOff() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("storiesFullyOff", storiesFullyOff ^= true).apply();
    }

    public static void toggleStoriesHidePostEntry() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("storiesHidePostEntry", storiesHidePostEntry ^= true).apply();
    }

    /**
     * Master kill switch for stories. Purely a local display gate: no server state is
     * touched and no API is called, we simply never render or notify about stories.
     * Read from draw paths, so it must stay a plain static field read (no prefs I/O).
     */
    public static boolean isStoriesFullyOff() {
        return storiesFullyOff;
    }

    /** The stories row above the chat list (and in Archive) is hidden. */
    public static boolean isStoriesRowHidden() {
        return hideStories || storiesFullyOff;
    }

    /** The "post my own story" camera entries are hidden (sub-option of the master switch). */
    public static boolean isStoriesPostEntryHidden() {
        return storiesFullyOff && storiesHidePostEntry;
    }

    public static void toggleCompactChatList() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("compactChatList", compactChatList ^= true).apply();
    }

    public static void toggleFileMasqueradeGuard() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("fileMasqueradeGuard", fileMasqueradeGuard ^= true).apply();
    }

    /** Whether the message long-press "Explain" cultural-note action is offered (default on). */
    public static void toggleExplainMessage() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("explainMessage", explainMessage ^= true).apply();
    }

    public static void toggleOcrTranslate() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("ocrTranslate", ocrTranslate ^= true).apply();
    }

    /** Persist the stranger-request-inbox master switch (default OFF). */
    public static void toggleStrangerInbox() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("strangerInbox", strangerInbox ^= true).apply();
    }

    /** Persist the beta update-channel switch (default OFF = stable channel). */
    public static void toggleBetaChannel() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("betaChannel", betaChannel ^= true).apply();
    }

    /** Hide the reaction chips under messages (default OFF = stock behavior). */
    public static void toggleHideReactions() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("hideReactions", hideReactions ^= true).apply();
    }

    /** Render broadcast-channel text posts at (near) full width (default OFF = stock behavior). */
    public static void toggleWideChannelPosts() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("wideChannelPosts", wideChannelPosts ^= true).apply();
    }

    /**
     * Outgoing-photo JPEG quality (Batch 8 #20). Clamped to a sane 1..100 range; anything out of
     * range falls back to the stock 80 so a bad value can never produce a broken/empty JPEG.
     */
    public static void setOutgoingPhotoQuality(int quality) {
        if (quality < 1 || quality > 100) {
            quality = 80;
        }
        outgoingPhotoQuality = quality;
        putInt("outgoingPhotoQuality", quality);
    }

    /** Send photos with less downscaling (default OFF = stock behavior). */
    public static void toggleSendLargePhotos() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("sendLargePhotos", sendLargePhotos ^= true).apply();
    }

    /** Swipe a full-screen playing video down into PiP (default OFF = stock behavior). */
    public static void toggleSwipeVideoToPip() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("swipeVideoToPip", swipeVideoToPip ^= true).apply();
    }

    /** Speed up large uploads/downloads with bigger MTProto parts (default OFF = stock). */
    public static void toggleTransferBoost() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("transferBoost", transferBoost ^= true).apply();
    }

    public static void toggleTranslateBeforeSend() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("translateBeforeSend", translateBeforeSend ^= true).apply();
    }

    public static void toggleTranslateBeforeSendConfirm() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("translateBeforeSendConfirm", translateBeforeSendConfirm ^= true).apply();
    }

    // ---- Anti-scam / security ----

    /** Whether to warn about homoglyph / look-alike impersonation names (default on). */
    public static boolean isHomoglyphWarn() {
        return homoglyphWarn;
    }

    /** Persist the homoglyph-warning switch. */
    public static void setHomoglyphWarn(boolean value) {
        homoglyphWarn = value;
        if (editor != null) {
            editor.putBoolean("homoglyphWarn", value).apply();
        }
    }

    public static void toggleHomoglyphWarn() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("homoglyphWarn", homoglyphWarn ^= true).apply();
    }

    // ---- Appearance (Wave 2) ----

    /** The Android 12+ Material You primary accent, or 0 when unavailable. */
    public static int getMaterialYouAccent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ApplicationLoader.applicationContext.getColor(android.R.color.system_accent1_500) | 0xff000000;
            } catch (Exception ignore) {
            }
        }
        return 0;
    }

    /** Effective app accent: Material You wins, then a manual override, else 0 (no override). */
    public static int getEffectiveAccent() {
        if (materialYouEnabled) {
            int c = getMaterialYouAccent();
            if (c != 0) {
                return c;
            }
        }
        if (customAccentEnabled && customAccentColor != 0) {
            return customAccentColor | 0xff000000;
        }
        return 0;
    }

    /** Publish the current appearance selection into the render hooks. */
    public static void applyAppearance() {
        try {
            org.telegram.ui.ActionBar.Theme.luminaAccentColor = getEffectiveAccent();
        } catch (Throwable ignore) {
        }
        try {
            AndroidUtilities.luminaFont = appFont;
            AndroidUtilities.mediumTypeface = null; // recompute bold() with the new family
        } catch (Throwable ignore) {
        }
    }

    public static void toggleMaterialYou() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("materialYouEnabled", materialYouEnabled ^= true).apply();
        applyAppearance();
    }

    public static void toggleCustomAccent() {
        if (editor == null) {
            return;
        }
        editor.putBoolean("customAccentEnabled", customAccentEnabled ^= true).apply();
        applyAppearance();
    }

    public static void setCustomAccentColor(int color) {
        if (editor == null) {
            return;
        }
        customAccentColor = color;
        editor.putInt("customAccentColor", color).apply();
        applyAppearance();
    }

    public static void setAppFont(int font) {
        if (editor == null) {
            return;
        }
        appFont = font;
        editor.putInt("appFont", font).apply();
        applyAppearance();
    }

    // ---- Encrypted local backup (Wave 11) ----
    // Helpers for LuminaBackupActivity's export/import. Everything LuminaGram stores
    // lives in the single app-private "luminagram" prefs file, so a backup is simply a
    // type-tagged snapshot of preferences.getAll() — bookmarks, contactNotes, quickReplies,
    // textReplacements and every toggle/int/string key, including future ones. Nothing is
    // ever sent to Telegram; the snapshot is encrypted with a passphrase before it leaves
    // the app (see LuminaBackupActivity).

    /**
     * Snapshot every key in the "luminagram" prefs as a type-tagged JSON object. Each
     * entry maps a key to {@code {"t":<type>,"v":<value>}} where type is one of
     * b(oolean)/i(nt)/l(ong)/f(loat)/s(tring)/ss(string-set). Never null.
     */
    // ---- Per-dialog translate-before-send language lock ("auto" mode) ----
    // In "auto" send-language mode, translate-before-send asks ONCE per chat which language to
    // translate outgoing messages into, then remembers the choice here so it never re-prompts.
    // Stored app-privately as a JSON object string { "<dialogId>": "<langCode>" } under the
    // "trSendLangDialog" key (via getString/putString) -- never sent to Telegram.
    public static final String KEY_TR_SEND_LANG_DIALOG = "trSendLangDialog";

    /** Locked outgoing-translation language for a dialog, or null when none is chosen yet. */
    public static String getDialogSendLang(long dialogId) {
        String raw = getString(KEY_TR_SEND_LANG_DIALOG, "");
        if (raw != null && raw.length() > 0) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(raw);
                String v = o.optString(String.valueOf(dialogId), null);
                if (v != null && v.length() > 0) {
                    return v;
                }
            } catch (org.json.JSONException ignore) {
            }
        }
        return null;
    }

    /** Lock (or overwrite) the outgoing-translation language for a dialog; empty/null clears it. */
    public static void setDialogSendLang(long dialogId, String lang) {
        org.json.JSONObject o;
        String raw = getString(KEY_TR_SEND_LANG_DIALOG, "");
        try {
            o = (raw != null && raw.length() > 0) ? new org.json.JSONObject(raw) : new org.json.JSONObject();
        } catch (org.json.JSONException e) {
            o = new org.json.JSONObject();
        }
        try {
            if (lang == null || lang.length() == 0) {
                o.remove(String.valueOf(dialogId));
            } else {
                o.put(String.valueOf(dialogId), lang);
            }
        } catch (org.json.JSONException ignore) {
        }
        putString(KEY_TR_SEND_LANG_DIALOG, o.toString());
    }

    // ---- Per-dialog translate-before-send ON/OFF switch ----
    // translateBeforeSend (the global boolean) is only the capability gate: it makes the feature
    // available but translates nothing on its own. Whether outgoing messages in a GIVEN chat are
    // translated is decided here, per dialog, and defaults to OFF. Send-side translation applies
    // only when translateBeforeSend (global) && getDialogSendEnabled(dialogId).
    // Kept as its own boolean map rather than reusing the presence of a per-dialog send language
    // (KEY_TR_SEND_LANG_DIALOG): that language map is only written in "auto" send-language mode, so
    // "has a language" cannot mark a chat enabled when a FIXED global send language is in use (no
    // per-dialog language is ever stored then). A dedicated switch also keeps "which language" and
    // "is this chat on" independent -- re-picking or clearing the language never flips the switch,
    // and turning a chat off never destroys its remembered language. Stored app-privately as a JSON
    // object string { "<dialogId>": true } under the "trSendEnabledDialog" key -- never sent to Telegram.
    public static final String KEY_TR_SEND_ENABLED_DIALOG = "trSendEnabledDialog";

    /** True when translate-before-send is turned on for this dialog (default false). */
    public static boolean getDialogSendEnabled(long dialogId) {
        String raw = getString(KEY_TR_SEND_ENABLED_DIALOG, "");
        if (raw != null && raw.length() > 0) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(raw);
                return o.optBoolean(String.valueOf(dialogId), false);
            } catch (org.json.JSONException ignore) {
            }
        }
        return false;
    }

    /** Turn translate-before-send on/off for this dialog; off removes the entry (keeps the map small). */
    public static void setDialogSendEnabled(long dialogId, boolean enabled) {
        org.json.JSONObject o;
        String raw = getString(KEY_TR_SEND_ENABLED_DIALOG, "");
        try {
            o = (raw != null && raw.length() > 0) ? new org.json.JSONObject(raw) : new org.json.JSONObject();
        } catch (org.json.JSONException e) {
            o = new org.json.JSONObject();
        }
        try {
            if (!enabled) {
                o.remove(String.valueOf(dialogId));
            } else {
                o.put(String.valueOf(dialogId), true);
            }
        } catch (org.json.JSONException ignore) {
        }
        putString(KEY_TR_SEND_ENABLED_DIALOG, o.toString());
    }

    // ---- Per-dialog translation register (tone / formality / relationship) ----
    // Which relationship a chat stands in -- client, colleague, friend, family, elder, someone you
    // are flirting with, or a sentence the user writes themselves -- so translations of that chat
    // can be asked for the tone that relationship calls for. See LuminaRegister for the meaning of
    // the stored codes and how each engine honours them. Stored app-privately as a JSON object
    // string { "<dialogId>": "<code>" } under the "trRegisterDialog" key, exactly like the
    // per-dialog send language above -- never sent to Telegram, and nothing in it leaves the device
    // except as part of the translation request the user's own API key pays for.
    public static final String KEY_TR_REGISTER_DIALOG = "trRegisterDialog";

    /** Register chosen for a dialog ("client", "custom:<text>", …), or null when unset. */
    public static String getDialogRegister(long dialogId) {
        String raw = getString(KEY_TR_REGISTER_DIALOG, "");
        if (raw != null && raw.length() > 0) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(raw);
                String v = o.optString(String.valueOf(dialogId), null);
                if (v != null && v.length() > 0) {
                    return v;
                }
            } catch (org.json.JSONException ignore) {
            }
        }
        return null;
    }

    /** Set (or overwrite) the register for a dialog; empty/null clears it back to unset. */
    public static void setDialogRegister(long dialogId, String register) {
        org.json.JSONObject o;
        String raw = getString(KEY_TR_REGISTER_DIALOG, "");
        try {
            o = (raw != null && raw.length() > 0) ? new org.json.JSONObject(raw) : new org.json.JSONObject();
        } catch (org.json.JSONException e) {
            o = new org.json.JSONObject();
        }
        try {
            if (register == null || register.length() == 0) {
                o.remove(String.valueOf(dialogId));
            } else {
                o.put(String.valueOf(dialogId), register);
            }
        } catch (org.json.JSONException ignore) {
        }
        putString(KEY_TR_REGISTER_DIALOG, o.toString());
    }

    // ---- Undo-send durability (b25) ----
    // The undo-send window holds a just-"sent" plain message in memory for a few seconds. To
    // survive a hard process kill inside that window, the held text is also persisted here as a
    // JSON object { "dialogId": <long>, "text": <String> } under "undoSendPending" (via
    // getString/putString) -- never sent to Telegram. Cleared the moment the message is actually
    // dispatched or the user undoes; restored into the composer on next open of the same chat.
    // Only one pending entry at a time (the window allows a single hold).
    public static final String KEY_UNDO_SEND_PENDING = "undoSendPending";

    /** Persist the held undo-send text for a dialog. Empty/null text clears the entry. */
    public static void setUndoSendPending(long dialogId, String text) {
        if (text == null || text.length() == 0) {
            clearUndoSendPending();
            return;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("dialogId", dialogId);
            o.put("text", text);
            putString(KEY_UNDO_SEND_PENDING, o.toString());
        } catch (org.json.JSONException ignore) {
        }
    }

    /** Held undo-send text for this dialog, or null when none pending / a different dialog. */
    public static String getUndoSendPending(long dialogId) {
        String raw = getString(KEY_UNDO_SEND_PENDING, "");
        if (raw != null && raw.length() > 0) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(raw);
                if (o.optLong("dialogId") == dialogId) {
                    String t = o.optString("text", "");
                    if (t != null && t.length() > 0) {
                        return t;
                    }
                }
            } catch (org.json.JSONException ignore) {
            }
        }
        return null;
    }

    /** Drop any persisted undo-send entry (dispatched, undone, or restored). */
    public static void clearUndoSendPending() {
        putString(KEY_UNDO_SEND_PENDING, "");
    }

    // ---- Login guard / session guard (Wave: session guard) ----
    // Local-only bookkeeping for {@link LuminaSessionGuard}: the set of authorization hashes the
    // user has already seen or approved, plus the timestamp of the last automatic check. Both are
    // per account (key + account index) and live only in the app-private "luminagram" prefs --
    // nothing is ever sent to Telegram. The hash set is a JSON array of decimal strings.
    public static final String KEY_SESSION_GUARD_ENABLED = "sessionGuardEnabled";
    private static final String KEY_SESSION_GUARD_KNOWN = "sessionGuardKnown";
    private static final String KEY_SESSION_GUARD_LAST_CHECK = "sessionGuardLastCheck";

    private static String sessionGuardKnownKey(int account) {
        return KEY_SESSION_GUARD_KNOWN + account;
    }

    /** True once a baseline has been stored for this account, i.e. this is not the first run. */
    public static boolean hasKnownSessions(int account) {
        return contains(sessionGuardKnownKey(account));
    }

    /** Mutable copy of the approved/seen authorization hashes for an account. Never null. */
    public static java.util.HashSet<String> getKnownSessions(int account) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        String raw = getString(sessionGuardKnownKey(account), "");
        if (raw != null && raw.length() > 0) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    String v = arr.optString(i, "");
                    if (v != null && v.length() > 0) {
                        out.add(v);
                    }
                }
            } catch (org.json.JSONException ignore) {
            }
        }
        return out;
    }

    public static void setKnownSessions(int account, java.util.Set<String> hashes) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (hashes != null) {
            for (String h : hashes) {
                if (h != null && h.length() > 0) {
                    arr.put(h);
                }
            }
        }
        putString(sessionGuardKnownKey(account), arr.toString());
    }

    /** Wall-clock ms of the last automatic authorization check, or 0 when never run. */
    public static long getSessionGuardLastCheck(int account) {
        if (preferences == null) {
            return 0L;
        }
        try {
            return preferences.getLong(KEY_SESSION_GUARD_LAST_CHECK + account, 0L);
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    public static void setSessionGuardLastCheck(int account, long time) {
        if (editor == null) {
            return;
        }
        try {
            editor.putLong(KEY_SESSION_GUARD_LAST_CHECK + account, time).apply();
        } catch (Throwable ignore) {
        }
    }

    public static org.json.JSONObject exportAll() {
        org.json.JSONObject out = new org.json.JSONObject();
        try {
            if (preferences == null) {
                return out;
            }
            java.util.Map<String, ?> all = preferences.getAll();
            for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                Object val = e.getValue();
                if (val == null) {
                    continue;
                }
                org.json.JSONObject entry = new org.json.JSONObject();
                if (val instanceof Boolean) {
                    entry.put("t", "b").put("v", ((Boolean) val).booleanValue());
                } else if (val instanceof Integer) {
                    entry.put("t", "i").put("v", ((Integer) val).intValue());
                } else if (val instanceof Long) {
                    entry.put("t", "l").put("v", ((Long) val).longValue());
                } else if (val instanceof Float) {
                    entry.put("t", "f").put("v", (double) ((Float) val).floatValue());
                } else if (val instanceof String) {
                    entry.put("t", "s").put("v", (String) val);
                } else if (val instanceof java.util.Set) {
                    org.json.JSONArray a = new org.json.JSONArray();
                    for (Object s : (java.util.Set<?>) val) {
                        a.put(String.valueOf(s));
                    }
                    entry.put("t", "ss").put("v", a);
                } else {
                    continue; // unknown type: skip it rather than guess
                }
                out.put(e.getKey(), entry);
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    /**
     * Restore keys produced by {@link #exportAll()} back into the "luminagram" prefs,
     * writing each value with its original type. Returns the number of keys applied.
     * Cached static fields are refreshed via {@link #reloadFromPreferences()} afterward
     * so a restored appearance/toggle takes effect without a restart.
     */
    public static int importAll(org.json.JSONObject data) {
        if (data == null) {
            return 0;
        }
        int applied = 0;
        java.util.Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            org.json.JSONObject entry = data.optJSONObject(key);
            if (entry == null) {
                continue;
            }
            String t = entry.optString("t", "s");
            try {
                switch (t) {
                    case "b":
                        editor.putBoolean(key, entry.optBoolean("v", false));
                        break;
                    case "i":
                        editor.putInt(key, entry.optInt("v", 0));
                        break;
                    case "l":
                        editor.putLong(key, entry.optLong("v", 0L));
                        break;
                    case "f":
                        editor.putFloat(key, (float) entry.optDouble("v", 0));
                        break;
                    case "ss": {
                        org.json.JSONArray a = entry.optJSONArray("v");
                        java.util.HashSet<String> set = new java.util.HashSet<>();
                        if (a != null) {
                            for (int i = 0; i < a.length(); i++) {
                                set.add(a.optString(i));
                            }
                        }
                        editor.putStringSet(key, set);
                        break;
                    }
                    default:
                        editor.putString(key, entry.optString("v", ""));
                        break;
                }
                applied++;
            } catch (Exception ignore) {
                // Malformed entry: skip it, never abort the whole restore.
            }
        }
        editor.apply();
        reloadFromPreferences();
        return applied;
    }

    /** Re-read every cached field from the "luminagram" prefs (used after an import). */
    public static void reloadFromPreferences() {
        synchronized (sync) {
            configLoaded = false;
            loadConfig();
        }
    }
}
