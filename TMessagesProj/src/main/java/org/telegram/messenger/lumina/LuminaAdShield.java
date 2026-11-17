/*
 * LuminaGram - AdShield ("隱藏重複廣告" / hide repeated ads) core engine, Android port.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 *
 * Normative source: adshield/SPEC.md (algoVersion 2 / formatVersion 2), the reference engine
 * adshield/ref/adshield.py, adshield/vectors/*.json and adshield/corpus/scenarios.json.
 * This file is a function-by-function port of the reference (SPEC §9.2); it must reproduce the
 * vectors byte for byte. Checker: lumina-dev/adshield-check (not compiled by the app build).
 */
package org.telegram.messenger.lumina;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-logic part of AdShield: normalisation helpers on top of the generated {@link AdShieldTables},
 * link extraction / canonicalisation (SPEC §4), the per-message fingerprint (SPEC §3, §5) and
 * {@link #resolve} (SPEC §5.5), plus the platform-neutral data model of SPEC §9.1.
 *
 * <p>No Android, no UI, no I/O. Everything works on Unicode scalars (code points): Java strings are
 * UTF-16, so every count / window / slice that the SPEC defines in scalars is done with
 * {@code codePointAt}/{@code codePointBefore}; regex match offsets (UTF-16) are only ever used to
 * slice, never to count. Usernames are lower-cased only through {@link AdShieldTables#fold}.
 * String ordering uses {@link #compareCodePoints} (code point order), never {@link String#compareTo}.
 * "All digits" means ASCII 0-9 only. Unsigned 64-bit comparisons go through
 * {@link AdShieldTables#compareUnsigned}.
 *
 * <p>Threading: stateless except the lazily computed caches inside {@link ChatContext}; like the
 * store, it is meant to be used from the single AdShield engine thread ({@link LuminaAdShieldThread}).
 * {@code java.util.regex} recurses once per repetition of {@code (?:...)+}; inputs such as
 * {@code a.a.a....com} need a big stack, which is why the engine thread has 16 MB and why the store
 * entry points catch {@link Throwable}.
 */
public final class LuminaAdShield {

    private LuminaAdShield() {
    }

    public static final int ALGO_VERSION = 2;
    public static final int FORMAT_VERSION = 2;

    public static final long MINUTE = 60;
    public static final long HOUR = 3600;
    public static final long DAY = 86400;

    // ---- length thresholds (SPEC §3) ----
    static final int MIN_TEXT_WLEN = 12;
    static final int MIN_TMPL_WLEN = 4;
    static final int MIN_CJK_COUNT = 8;
    static final int PARA_MIN_WLEN = 32;
    static final int MAX_PARA = 16;
    static final int PURE_TEXT_MIN_CJK = 12;
    static final int PURE_TEXT_MIN_LETTERS = 24;
    static final int ACK_REMAINDER_MAX = 2;
    static final int MASS_MENTION = 3;
    static final int GAP_MAX = 4;
    static final int NUM_CTX_WINDOW = 12;
    static final int POINTER_NEG_WINDOW = 4;
    static final long KNOWN_MEMBER_MIN_AGE = DAY;

    // ---- storage limits (SPEC §8.2) ----
    public static final long EVICT_AFTER = 8 * DAY;
    public static final long RECORD_TTL = 90 * DAY;
    public static final long HANDLE_TTL = 30 * DAY;
    public static final int MAX_ROWS = 3000;
    public static final int MAX_ENDORSE_RECORDS = 200;
    public static final int MAX_NOTAD_RECORDS = 200;
    public static final int MAX_HANDLES = 2000;
    public static final int MAX_LINKS_PER_ROW = 16;
    public static final int MAX_PENDING_PER_ROW = 16;
    public static final int MAX_ROW_KEYS = 1 + 1 + 1 + 1 + 1 + 1 + 16;
    public static final int MAX_RECORD_KEYS = 64;
    public static final int MAX_RECORD_LINKS = 64;
    public static final long TOTAL_CAP_BYTES = 10L * 1024 * 1024;
    public static final int MAX_LOADED_CHATS = 8;
    public static final long MAX_DATE = 1L << 40;
    public static final int MAX_MSG_ID = 0x7FFFFFFF;

    // ---- sensitivities (SPEC §6.6) ----
    public static final String SENS_LOOSE = "loose";
    public static final String SENS_STANDARD = "standard";
    public static final String SENS_AGGRESSIVE = "aggressive";
    public static final String[] SENSITIVITIES = {SENS_LOOSE, SENS_STANDARD, SENS_AGGRESSIVE};

    // ---- roles (SPEC §9.1) ----
    public static final String ROLE_MEMBER = "member";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_CREATOR = "creator";
    public static final String ROLE_ANON_ADMIN = "anon_admin";
    public static final String ROLE_BOT = "bot";
    public static final String ROLE_CONTACT = "contact";
    public static final String ROLE_SELF = "self";
    public static final String ROLE_CHANNEL = "channel";

    // ---- chat types (SPEC §6.1) ----
    public static final String CHAT_GROUP = "group";
    public static final String CHAT_SUPERGROUP = "supergroup";
    public static final String CHAT_FORUM = "forum";
    public static final String CHAT_GIGAGROUP = "gigagroup";

    // ---- forward kinds ----
    public static final String FWD_CHANNEL = "channel";
    public static final String FWD_USER = "user";
    public static final String FWD_HIDDEN = "hidden";

    // ---- fingerprint key kinds (stored 1-byte codes) and hash tags ----
    public static final int KIND_FWD = 1;
    public static final int KIND_MEDIA = 2;
    public static final int KIND_TEXT = 3;
    public static final int KIND_CJK = 4;
    public static final int KIND_TMPL = 5;
    public static final int KIND_PARA = 6;
    public static final int KIND_CONTACT = 7;
    static final String[] KIND_TAG = {null, "fwd", "media", "text", "cjk", "tmpl", "para", "contact"};
    public static final String[] KIND_NAME = {null, "FWD", "MEDIA", "TEXT", "CJK", "TMPL", "PARA", "CONTACT"};

    static boolean isExactKind(int kind) {
        return kind >= 1 && kind <= 7 && kind != KIND_PARA;
    }

    // ---- feature / flag bits (SPEC §5.2) ----
    public static final String[] STRONG_NAMES = {"invite", "handle", "shortener", "button", "via_bot", "contact_card",
            "phone", "account", "pointer"};               // bits 0..8
    public static final String[] WEAK_NAMES = {"url", "member_mention", "long_number", "mass_mention", "email"}; // bits 10..14
    static final int WEAK_BASE = 10;
    static final int S_INVITE = 1;
    static final int S_HANDLE = 1 << 1;
    static final int S_SHORTENER = 1 << 2;
    static final int S_BUTTON = 1 << 3;
    static final int S_VIA_BOT = 1 << 4;
    static final int S_CONTACT_CARD = 1 << 5;
    static final int S_PHONE = 1 << 6;
    static final int S_ACCOUNT = 1 << 7;
    static final int S_POINTER = 1 << 8;
    static final int W_URL = 1 << 10;
    static final int W_MEMBER_MENTION = 1 << 11;
    static final int W_LONG_NUMBER = 1 << 12;
    static final int W_MASS_MENTION = 1 << 13;
    static final int W_EMAIL = 1 << 14;
    public static final int F_HAS_TEXT = 1 << 16;
    public static final int F_MEDIA_ONLY = 1 << 17;      // computed at decide time only, never stored
    public static final int F_PURE_TEXT = 1 << 18;
    public static final int F_ACK = 1 << 19;
    public static final int F_REPLY_ME = 1 << 20;
    public static final int F_MENTION_ME = 1 << 21;
    public static final int F_IS_REPLY = 1 << 22;
    public static final int F_ENDORSING = 1 << 23;
    public static final int F_BOT = 1 << 24;
    public static final int F_CONTACT = 1 << 25;
    public static final int F_SELF = 1 << 26;
    public static final int F_HAS_MEDIA_KEY = 1 << 27;
    public static final int F_HAS_FWD_KEY = 1 << 28;
    public static final int F_ANON = 1 << 29;
    public static final int F_TMPL_COND = 1 << 30;
    public static final int STRONG_MASK = 0x1FF;
    public static final int WEAK_MASK = 0x1F << 10;
    public static final int ANON_KEEP = F_ACK | F_HAS_MEDIA_KEY | F_HAS_FWD_KEY | F_HAS_TEXT | F_TMPL_COND;
    public static final int STORED_FLAGS_MASK = STRONG_MASK | WEAK_MASK | F_HAS_TEXT | F_PURE_TEXT | F_ACK | F_REPLY_ME
            | F_MENTION_ME | F_IS_REPLY | F_ENDORSING | F_BOT | F_CONTACT | F_SELF | F_HAS_MEDIA_KEY | F_HAS_FWD_KEY
            | F_ANON | F_TMPL_COND;

    // ---- link kinds / feature names ----
    public static final String LK_INVITE = "invite";
    public static final String LK_HANDLE = "handle";
    public static final String LK_URL = "url";
    public static final String LK_PHONE = "phone";
    public static final String LK_ACCT = "acct";
    public static final String LK_MAIL = "mail";
    public static final String SRC_TEXT = "text";
    public static final String SRC_HIDDEN = "hidden";
    public static final String SRC_BUTTON = "button";
    public static final String SRC_MEDIA = "media";
    public static final String FEAT_NONE = "none";
    public static final String FEAT_STRONG = "strong";
    public static final String FEAT_WEAK = "weak";
    public static final String FEAT_MEMBER = "member";
    public static final String FEAT_PENDING = "pending";

    private static final Set<String> ELIGIBLE_CHAT_TYPES = new HashSet<String>(Arrays.asList(
            CHAT_GROUP, CHAT_SUPERGROUP, CHAT_FORUM, CHAT_GIGAGROUP));
    private static final Set<String> ENDORSING_ROLES = new HashSet<String>(Arrays.asList(
            ROLE_ADMIN, ROLE_CREATOR, ROLE_ANON_ADMIN, ROLE_SELF));

    // =====================================================================================
    // Logging hook: the engine never logs message text, links, usernames or sender ids; only fixed
    // event names, counts, durations and exception class names. Default: no-op.
    // =====================================================================================

    public interface Logger {
        void log(String event);
    }

    private static volatile Logger logger;

    public static void setLogger(Logger l) {
        logger = l;
    }

    static void log(String event) {
        Logger l = logger;
        if (l != null) {
            try {
                l.log(event);
            } catch (Throwable ignore) {
            }
        }
    }

    // =====================================================================================
    // Data model (SPEC §9.1). Missing ids are 0 / null; the integration step fills these from
    // TLRPC objects (media whitelist SPEC §3: never MessageObject.getPhoto()/getDocument()).
    // =====================================================================================

    public static final class Sender {
        public long id;                         // Bot-API style id; anonymous admin = the group id
        public String role = ROLE_MEMBER;       // member|admin|creator|anon_admin|bot|contact|self|channel
        public String username;                 // raw; folded by the engine (may be null)

        public Sender() {
        }

        public Sender(long id, String role, String username) {
            this.id = id;
            this.role = role;
            this.username = username;
        }
    }

    public static final class Entity {
        public static final String TEXT_URL = "text_url";
        public static final String MENTION_NAME = "mention_name";
        public String type;
        public String url;                      // text_url
        public Long userId;                     // mention_name (null = absent)

        public static Entity textUrl(String url) {
            Entity e = new Entity();
            e.type = TEXT_URL;
            e.url = url;
            return e;
        }

        public static Entity mentionName(long userId) {
            Entity e = new Entity();
            e.type = MENTION_NAME;
            e.userId = userId;
            return e;
        }
    }

    public static final class Media {
        /** photo|video|document|audio|contact|sticker|gif|voice|round|dice|location|venue|poll|webpage|game|story|invoice|giveaway|paid */
        public String kind;
        public String id;                       // signed int64 in decimal (photo / document id); null = none
        public long userId;                     // contact user id, 0 = none
        public String phone;                    // contact phone, null = none
    }

    public static final class Forward {
        public String fromKind;                 // channel|user|hidden
        public long fromId;                     // 0 = unknown
        public long postId;                     // 0 = unknown
        public String fromName;
        public String username;
        public long date;
    }

    public static final class MessageInput {
        public int id;                          // msgId (> 0)
        public long date;                       // server date, Unix seconds
        public Sender sender;
        public String text = "";                // text or caption
        public List<Entity> entities;           // in original entity order
        public Media media;
        public long album;                      // grouped_id, 0 = none
        public Forward forward;
        public List<String> buttons;            // URL buttons only
        public boolean viaBot;
        public boolean replyToMe;
        public boolean mentionsMe;
        public boolean pinned;
        public boolean autoForward;
        public boolean service;
        public boolean sponsored;
        public boolean scheduled;
        public int replyTo;                     // 0 = not a reply
    }

    /**
     * Per-chat context (SPEC §9.1). Immutable; usernames are folded on construction (SPEC §1 rule 6).
     * Build with {@link Builder}. Caches (trusted tokens, member-handle hashes) are computed lazily and
     * must only be touched from the engine thread.
     */
    public static final class ChatContext {
        public final long chatId;
        public final String type;
        public final String username;               // folded, without '@'
        public final long linkedChannelId;
        public final String linkedChannelUsername;  // folded
        public final String description;
        public final List<String> pinnedTexts;
        public final boolean adminsKnown;
        public final List<String> memberHandles;    // folded; admins + bots at least (SPEC §5.1)
        public final String selfUsername;           // folded

        private Set<String> trustedTokens;
        private Set<String> trustedDomains;
        private byte[] memberHashKey;
        private long[] memberHashesSorted;
        private HashSet<Long> memberHashSet;

        private ChatContext(Builder b) {
            chatId = b.chatId;
            type = b.type != null ? b.type : CHAT_SUPERGROUP;
            username = uname(b.username);
            linkedChannelId = b.linkedChannelId;
            linkedChannelUsername = uname(b.linkedChannelUsername);
            description = b.description != null ? b.description : "";
            ArrayList<String> pins = new ArrayList<String>();
            if (b.pinnedTexts != null) {
                for (String p : b.pinnedTexts) {
                    if (p != null) {
                        pins.add(p);
                    }
                }
            }
            pinnedTexts = Collections.unmodifiableList(pins);
            adminsKnown = b.adminsKnown;
            ArrayList<String> mh = new ArrayList<String>();
            if (b.memberHandles != null) {
                for (String h : b.memberHandles) {
                    mh.add(uname(h));
                }
            }
            memberHandles = Collections.unmodifiableList(mh);
            selfUsername = uname(b.selfUsername);
        }

        public static Builder builder(long chatId) {
            return new Builder(chatId);
        }

        public static final class Builder {
            long chatId;
            String type = CHAT_SUPERGROUP;
            String username;
            long linkedChannelId;
            String linkedChannelUsername;
            String description = "";
            List<String> pinnedTexts;
            boolean adminsKnown = true;
            List<String> memberHandles;
            String selfUsername;

            Builder(long chatId) {
                this.chatId = chatId;
            }

            public Builder type(String v) {
                type = v;
                return this;
            }

            public Builder username(String v) {
                username = v;
                return this;
            }

            public Builder linkedChannel(long id, String username) {
                linkedChannelId = id;
                linkedChannelUsername = username;
                return this;
            }

            public Builder description(String v) {
                description = v;
                return this;
            }

            public Builder pinnedTexts(List<String> v) {
                pinnedTexts = v;
                return this;
            }

            public Builder adminsKnown(boolean v) {
                adminsKnown = v;
                return this;
            }

            public Builder memberHandles(List<String> v) {
                memberHandles = v;
                return this;
            }

            public Builder selfUsername(String v) {
                selfUsername = v;
                return this;
            }

            public ChatContext build() {
                return new ChatContext(this);
            }
        }

        /** "Not a feature" tokens and domains (SPEC §5.1): own/linked usernames, description and pinned links. */
        void ensureTrusted() {
            if (trustedTokens != null) {
                return;
            }
            HashSet<String> tokens = new HashSet<String>();
            HashSet<String> domains = new HashSet<String>();
            if (!username.isEmpty()) {
                tokens.add("@" + username);
            }
            if (!linkedChannelUsername.isEmpty()) {
                tokens.add("@" + linkedChannelUsername);
            }
            ArrayList<String> texts = new ArrayList<String>();
            texts.add(description);
            texts.addAll(pinnedTexts);
            for (String text : texts) {
                Extraction ex = extractLinks(AdShieldTables.fold(text));
                for (Link l : ex.links) {
                    normalizePhoneToken(l);
                    tokens.add(l.token);
                    if ((LK_URL.equals(l.kind) || LK_MAIL.equals(l.kind)) && !l.shortener && !l.domain.isEmpty()) {
                        domains.add(l.domain);
                    }
                }
            }
            trustedDomains = domains;
            trustedTokens = tokens;
        }

        Set<String> trustedTokens() {
            ensureTrusted();
            return trustedTokens;
        }

        Set<String> trustedDomains() {
            ensureTrusted();
            return trustedDomains;
        }

        /** H("link", "@" + name) of every non-empty member handle, sorted unsigned, distinct. */
        long[] memberHashes(byte[] key) {
            if (memberHashesSorted == null || memberHashKey == null || !Arrays.equals(memberHashKey, key)) {
                HashSet<Long> set = new HashSet<Long>();
                for (String n : memberHandles) {
                    if (!n.isEmpty()) {
                        set.add(handleHash(key, n));
                    }
                }
                long[] arr = new long[set.size()];
                int i = 0;
                for (Long v : set) {
                    arr[i++] = v;
                }
                sortUnsigned(arr);
                memberHashKey = key.clone();
                memberHashesSorted = arr;
                memberHashSet = set;
            }
            return memberHashesSorted;
        }

        HashSet<Long> memberHashSet(byte[] key) {
            memberHashes(key);
            return memberHashSet;
        }
    }

    public static final class Settings {
        public String sensitivity = SENS_STANDARD;
        public boolean exemptContacts = true;
        public boolean exemptBots = true;
        public boolean exemptReplies = true;
        public boolean dryRun = false;          // UI-only: never changes the Decision

        public Settings() {
        }

        public Settings(String sensitivity) {
            this.sensitivity = sensitivity;
        }
    }

    /** SPEC §6.10. {@code cls} is the SPEC's {@code class} field (a Java keyword). */
    public static final class Decision {
        public static final String INELIGIBLE = "ineligible";
        public static final String UNTRACKED = "untracked";
        public static final String PENDING = "pending";
        public static final String ENDORSED = "endorsed";
        public static final String NOT_AD = "not_ad";
        public static final String NONE = "none";
        public static final String ORIGINAL = "original";
        public static final String EXEMPT = "exempt";
        public static final String FOLD = "fold";

        public static final String CLASS_STRONG = "strong";
        public static final String CLASS_WEAK = "weak";
        public static final String CLASS_PLAIN = "plain";
        public static final String CLASS_IMPERSONATION = "impersonation";

        public static final String RULE_STRONG = "strong";
        public static final String RULE_WEAK = "weak";
        public static final String RULE_MEDIA_SAME_SENDER = "media_same_sender";
        public static final String RULE_FWD_SAME_SENDER = "fwd_same_sender";
        public static final String RULE_PLAIN = "plain";

        public String state = NONE;
        public boolean fold;
        public String cls;
        public String rule;
        public int occurrence;
        public int groupSize;
        public boolean replyToMe;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Decision)) {
                return false;
            }
            Decision d = (Decision) o;
            return eq(state, d.state) && fold == d.fold && eq(cls, d.cls) && eq(rule, d.rule)
                    && occurrence == d.occurrence && groupSize == d.groupSize && replyToMe == d.replyToMe;
        }

        @Override
        public int hashCode() {
            return (state != null ? state.hashCode() : 0) * 31 + occurrence * 7 + groupSize;
        }

        @Override
        public String toString() {
            return "Decision{" + state + ",fold=" + fold + ",class=" + cls + ",rule=" + rule + ",occ=" + occurrence
                    + ",group=" + groupSize + ",replyToMe=" + replyToMe + "}";
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /** A fingerprint key: (kind, H(tag, payload)). */
    public static final class Key {
        public final int kind;
        public final long hash;

        public Key(int kind, long hash) {
            this.kind = kind;
            this.hash = hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return kind == k.kind && hash == k.hash;
        }

        @Override
        public int hashCode() {
            return (int) (hash ^ (hash >>> 32)) * 31 + kind;
        }

        /** (kind, hash unsigned) ascending. */
        static final Comparator<Key> ORDER = new Comparator<Key>() {
            @Override
            public int compare(Key a, Key b) {
                if (a.kind != b.kind) {
                    return a.kind < b.kind ? -1 : 1;
                }
                return AdShieldTables.compareUnsigned(a.hash, b.hash);
            }
        };
    }

    public static final class Link {
        public String token;          // e.g. "@abc", "invite:xyz", "url:example.com/a", "uid:123"
        public final String kind;     // invite / handle / url / phone / acct / mail
        public final String type;     // INVITE / HANDLE / SHORT / URL:<regdomain> / PHONE / ACCT:<class> / MAIL
        public final String domain;   // url/mail registrable domain ("" otherwise)
        public final boolean shortener;
        public final String source;   // text / hidden / button / media

        Link(String token, String kind, String type, String domain, boolean shortener, String source) {
            this.token = token;
            this.kind = kind;
            this.type = type;
            this.domain = domain != null ? domain : "";
            this.shortener = shortener;
            this.source = source;
        }

        Link(String token, String kind, String type, String source) {
            this(token, kind, type, "", false, source);
        }
    }

    public static final class Extraction {
        public final String foldNoLinks;
        public final List<Link> links;
        public final List<String> mentions;     // @handle names in text (incl. "@ name"), not t.me links
        public final boolean pointer;
        public final List<String> longNumbers;

        Extraction(String foldNoLinks, List<Link> links, List<String> mentions, boolean pointer, List<String> longNumbers) {
            this.foldNoLinks = foldNoLinks;
            this.links = links;
            this.mentions = mentions;
            this.pointer = pointer;
            this.longNumbers = longNumbers;
        }
    }

    public static final class Fingerprint {
        public String fold;
        public String foldNoLinks;
        public String text;
        public String cjk;
        public int wlen;
        public int cjkCount;
        public int letterCount;
        public List<String> paragraphs;
        public List<Link> links;
        public List<String> linkFeatures;     // parallel to links: strong / weak / member / pending / none
        public List<String> strong;           // fixed strong features, sorted
        public List<String> weak;             // fixed weak features, sorted
        public int[] keyKinds;
        public String[] keyPayloads;          // plaintext payloads (never stored, never logged)
        public long[] keyHashes;
        public long[] linkHashes;             // subset links
        public long[] pending;                // pending handle hashes
        public int flags;
        public long senderKey;
        public boolean store;
        public boolean endorsing;
        public int mentionCount;
        public List<String> resolvedStrong;   // only filled by fingerprint() (vectors), not by the store
        public List<String> resolvedWeak;
        public int resolvedFlags;

        public Key[] keys() {
            Key[] out = new Key[keyKinds.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = new Key(keyKinds[i], keyHashes[i]);
            }
            return out;
        }
    }

    /** Is-known callback for {@link #resolve}: h = H("link", "@" + folded username). */
    public interface KnownFn {
        boolean isKnown(long handleHash);
    }

    public static final class Resolved {
        public final int flags;
        public final Key[] keys;
        public final long[] links;

        Resolved(int flags, Key[] keys, long[] links) {
            this.flags = flags;
            this.keys = keys;
            this.links = links;
        }
    }

    // =====================================================================================
    // Small helpers
    // =====================================================================================

    /** Code point (= UTF-8 byte) order; String.compareTo is UTF-16 order and must not be used. */
    public static int compareCodePoints(String a, String b) {
        int i = 0, j = 0, n = a.length(), m = b.length();
        while (i < n && j < m) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return ca < cb ? -1 : 1;
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        if (i < n) {
            return 1;
        }
        if (j < m) {
            return -1;
        }
        return 0;
    }

    static final Comparator<String> CODE_POINT_ORDER = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            return compareCodePoints(a, b);
        }
    };

    static void sortUnsigned(long[] a) {
        for (int i = 0; i < a.length; i++) {
            a[i] ^= Long.MIN_VALUE;
        }
        Arrays.sort(a);
        for (int i = 0; i < a.length; i++) {
            a[i] ^= Long.MIN_VALUE;
        }
    }

    static boolean isAz(int c) {
        return c >= 'a' && c <= 'z';
    }

    static boolean isAsciiDigit(int c) {
        return c >= '0' && c <= '9';
    }

    static boolean isAsciiAlnum(int c) {
        return isAz(c) || isAsciiDigit(c);
    }

    /** SPEC §1 rule 4: non-empty and every char is ASCII 0-9. */
    static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isAsciiDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static String digits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isAsciiDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** [a-z][a-z0-9_]{3,31} (full match). */
    static boolean validUsername(String s) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        if (n < 4 || n > 32 || !isAz(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < n; i++) {
            char c = s.charAt(i);
            if (!(isAsciiAlnum(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** Usernames always go through fold() (SPEC §1 rule 6); leading '@' removed. */
    static String uname(String s) {
        String f = AdShieldTables.fold(s != null ? s : "");
        int i = 0;
        while (i < f.length() && f.charAt(i) == '@') {
            i++;
        }
        return f.substring(i);
    }

    static long handleHash(byte[] key, String name) {
        return AdShieldTables.keyedHash(key, "link", "@" + name);
    }

    /**
     * H("sender", senderId): the keyed sender id stored in rows. Exposed for the platform's similar-image
     * (dHash) table of SPEC §5.4, which must key its entries the same way instead of storing plain ids.
     */
    public static long senderKey(byte[] key, long senderId) {
        return AdShieldTables.keyedHash(key, "sender", Long.toString(senderId));
    }

    private static String join(String sep, Collection<String> parts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String p : parts) {
            if (!first) {
                sb.append(sep);
            }
            first = false;
            sb.append(p);
        }
        return sb.toString();
    }

    private static String stripChars(String s, String chars, boolean left, boolean right) {
        int a = 0, b = s.length();
        if (left) {
            while (a < b && chars.indexOf(s.charAt(a)) >= 0) {
                a++;
            }
        }
        if (right) {
            while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) {
                b--;
            }
        }
        return s.substring(a, b);
    }

    /** Python str.isspace() for Unicode 13 (used by str.strip()). */
    private static boolean isPySpace(int c) {
        return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0x85 || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000;
    }

    private static String pyStrip(String s) {
        int a = 0, b = s.length();
        while (a < b && isPySpace(s.charAt(a))) {
            a++;
        }
        while (b > a && isPySpace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    /** UTF-16 index that is {@code count} scalars before {@code index} (clamped to 0). */
    static int backByScalars(String s, int index, int count) {
        int j = index;
        for (int c = 0; c < count && j > 0; c++) {
            j -= Character.charCount(s.codePointBefore(j));
        }
        return j;
    }

    // =====================================================================================
    // Normalisation helpers on top of the generated tables (SPEC §2)
    // =====================================================================================

    /** (cjkCount, otherLetterCount) of a text form; '#' counts as neither. */
    static int[] letterCounts(String t) {
        int cjk = 0, other = 0, i = 0, n = t.length();
        while (i < n) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '#') {
                continue;
            }
            if (AdShieldTables.isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
        }
        return new int[]{cjk, other};
    }

    /** SPEC §2.6: split F' on \n . ! ? ;, text form of each piece, wlen >= 32, dedupe, max 16. */
    public static List<String> paragraphs(String fNoLinks) {
        ArrayList<String> out = new ArrayList<String>();
        int start = 0, n = fNoLinks.length();
        for (int i = 0; i <= n; i++) {
            if (i < n) {
                char c = fNoLinks.charAt(i);
                if (c != '\n' && c != '.' && c != '!' && c != '?' && c != ';') {
                    continue;
                }
            }
            String tp = AdShieldTables.textForm(fNoLinks.substring(start, i));
            start = i + 1;
            if (AdShieldTables.wlen(tp) >= PARA_MIN_WLEN && !out.contains(tp)) {
                out.add(tp);
                if (out.size() == MAX_PARA) {
                    break;
                }
            }
        }
        return out;
    }

    // =====================================================================================
    // Link extraction (SPEC §4)
    // =====================================================================================

    static final String TRIM = ".,;:!?*";
    private static final String URLC = "[a-z0-9\\-._~:/?#@!$&*+,;=%]";
    private static final String NOT_BEFORE = "(?<![a-z0-9_\\-.@/])";

    private static String tldAlternation() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AdShieldTables.TLDS.length; i++) {      // already longest first
            if (i > 0) {
                sb.append('|');
            }
            String t = AdShieldTables.TLDS[i];
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                if (!isAsciiAlnum(c)) {
                    sb.append('\\');
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static final Pattern RX_SCHEME = Pattern.compile("(?:https?|ftp|tg)://" + URLC + "+");
    static final Pattern RX_TME = Pattern.compile(NOT_BEFORE + "(?:www\\.)?(?:t|telegram) ?\\. ?(?:me|dog) ?/" + URLC + "*");
    static final Pattern RX_TME_SUB = Pattern.compile(NOT_BEFORE + "[a-z][a-z0-9_]{3,31}\\.t\\.me(?![a-z0-9\\-])(?:/" + URLC + "*)?");
    static final Pattern RX_EMAIL = Pattern.compile("(?<![a-z0-9._%+\\-])[a-z0-9._%+\\-]+@[a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)*\\.[a-z]{2,24}"
            + "(?![a-z0-9\\-])");
    static final Pattern RX_DOMAIN = Pattern.compile(NOT_BEFORE + "(?:[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?\\.)+(?:"
            + tldAlternation() + ")(?![a-z0-9\\-])(?::[0-9]{1,5})?(?:/" + URLC + "*)?");
    static final Pattern RX_MENTION = Pattern.compile("(?<![a-z0-9_])@([a-z][a-z0-9_]{3,31})(?![a-z0-9_])");
    static final Pattern RX_MENTION_SP = Pattern.compile("(?<![a-z0-9_])@ ([a-z][a-z0-9_]{3,31})(?![a-z0-9_])");
    static final Pattern RX_PHONE_INTL = Pattern.compile("(?<![0-9a-z+])\\+[0-9](?:[ \\-.]?[0-9]){6,14}(?![0-9])");
    static final Pattern RX_PHONE_CN = Pattern.compile("(?<![0-9])1[ \\-.]?[3-9](?:[ \\-.]?[0-9]){9}(?![0-9])");
    static final Pattern RX_PHONE_TW = Pattern.compile("(?<![0-9])09[0-9](?:[ \\-.]?[0-9]){7}(?![0-9])");
    static final Pattern RX_ACCOUNT = Pattern.compile("[a-z0-9][a-z0-9_\\-.]{3,29}");
    static final Pattern RX_DIGITS_SEP = Pattern.compile("[0-9](?:[ \\-.]?[0-9]){6,14}(?![0-9])");
    static final Pattern RX_LONG_NUMBER = Pattern.compile("[0-9]{6,}");
    static final Pattern RX_DATE8 = Pattern.compile("(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])");
    static final Pattern RX_LEADING = Pattern.compile("[ \\n]*(?:@[a-z][a-z0-9_]{3,31}(?![a-z0-9_])[ \\n,:]*)+");
    static final Pattern RX_LEADING_NAME = Pattern.compile("@([a-z][a-z0-9_]{3,31})(?![a-z0-9_])");

    private static final HashSet<String> SHORTENERS = new HashSet<String>(Arrays.asList(AdShieldTables.SHORTENERS));
    private static final HashSet<String> TRACKING = new HashSet<String>(Arrays.asList(AdShieldTables.TRACKING_PARAMS));
    private static final HashSet<String> MULTI_SUFFIX = new HashSet<String>(Arrays.asList(AdShieldTables.MULTI_SUFFIX));
    private static final HashSet<String> TME_RESERVED = new HashSet<String>(Arrays.asList(AdShieldTables.TME_RESERVED));
    private static final HashMap<String, String> DOMAIN_ALIAS = new HashMap<String, String>();
    private static final HashMap<String, String> POINTER_NOT_AFTER = new HashMap<String, String>();
    private static final HashMap<String, String> POINTER_NOT_BEFORE = new HashMap<String, String>();
    private static final HashMap<String, String> MEDIA_KEYED = new HashMap<String, String>();

    static {
        for (int i = 0; i < AdShieldTables.DOMAIN_ALIAS_FROM.length; i++) {
            DOMAIN_ALIAS.put(AdShieldTables.DOMAIN_ALIAS_FROM[i], AdShieldTables.DOMAIN_ALIAS_TO[i]);
        }
        for (int i = 0; i < AdShieldTables.POINTER_NOT_AFTER_PHRASE.length; i++) {
            POINTER_NOT_AFTER.put(AdShieldTables.POINTER_NOT_AFTER_PHRASE[i], AdShieldTables.POINTER_NOT_AFTER_CHARS[i]);
        }
        for (int i = 0; i < AdShieldTables.POINTER_NOT_BEFORE_PHRASE.length; i++) {
            POINTER_NOT_BEFORE.put(AdShieldTables.POINTER_NOT_BEFORE_PHRASE[i], AdShieldTables.POINTER_NOT_BEFORE_CHARS[i]);
        }
        MEDIA_KEYED.put("photo", "p");
        MEDIA_KEYED.put("video", "d");
        MEDIA_KEYED.put("document", "d");
        MEDIA_KEYED.put("audio", "d");
    }

    /** Registrable domain (SPEC §4.3 step 11); not a full Public Suffix List. */
    static String regdomain(String host) {
        boolean ipish = !host.isEmpty();
        for (int i = 0; i < host.length() && ipish; i++) {
            char c = host.charAt(i);
            ipish = isAsciiDigit(c) || c == '.';
        }
        if (ipish) {
            return host;
        }
        ArrayList<String> labels = new ArrayList<String>();
        for (String x : splitChar(host, '.')) {
            if (!x.isEmpty()) {
                labels.add(x);
            }
        }
        int n = labels.size();
        if (n <= 2) {
            return join(".", labels);
        }
        String two = labels.get(n - 2) + "." + labels.get(n - 1);
        if (MULTI_SUFFIX.contains(two)) {
            return labels.get(n - 3) + "." + two;
        }
        return two;
    }

    /** Python str.split(sep) for a single char: keeps empty pieces. */
    static List<String> splitChar(String s, char sep) {
        ArrayList<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Query pairs; empty parts skipped; k before the first '=', v after. */
    private static List<String[]> parseQuery(String q) {
        ArrayList<String[]> out = new ArrayList<String[]>();
        for (String part : splitChar(q, '&')) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq < 0) {
                out.add(new String[]{part, ""});
            } else {
                out.add(new String[]{part.substring(0, eq), part.substring(eq + 1)});
            }
        }
        return out;
    }

    /** Duplicate keys: the last one wins (SPEC §4.3 step 2). */
    private static Map<String, String> queryDict(String q) {
        HashMap<String, String> d = new HashMap<String, String>();
        for (String[] kv : parseQuery(q)) {
            d.put(kv[0], kv[1]);
        }
        return d;
    }

    private static String cleanQuery(String q) {
        ArrayList<String[]> keep = new ArrayList<String[]>();
        for (String[] kv : parseQuery(q)) {
            if (TRACKING.contains(kv[0])) {
                continue;
            }
            boolean tracked = false;
            for (String p : AdShieldTables.TRACKING_PREFIXES) {
                if (kv[0].startsWith(p)) {
                    tracked = true;
                    break;
                }
            }
            if (!tracked) {
                keep.add(kv);
            }
        }
        Collections.sort(keep, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                int c = compareCodePoints(a[0], b[0]);
                return c != 0 ? c : compareCodePoints(a[1], b[1]);
            }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            String[] kv = keep.get(i);
            sb.append(kv[0]);
            if (!kv[1].isEmpty()) {
                sb.append('=').append(kv[1]);
            }
        }
        return sb.toString();
    }

    private static Link phoneLink(String digits, String source) {
        if (digits.length() >= 7 && digits.length() <= 15) {
            return new Link("tel:" + digits, LK_PHONE, "PHONE", source);
        }
        return null;
    }

    private static Link tmeLink(String path, String source) {
        ArrayList<String> segs = new ArrayList<String>();
        for (String s : splitChar(path, '/')) {
            if (!s.isEmpty()) {
                segs.add(s);
            }
        }
        if (segs.isEmpty()) {
            return new Link("url:t.me", LK_URL, "URL:t.me", "t.me", false, source);
        }
        String s0 = segs.get(0);
        if (s0.startsWith("+")) {
            String h = s0.substring(1);
            if (isDigits(h)) {
                return phoneLink(h, source);
            }
            if (!h.isEmpty()) {
                return new Link("invite:" + h, LK_INVITE, "INVITE", source);
            }
        }
        if (s0.equals("joinchat") && segs.size() > 1) {
            return new Link("invite:" + segs.get(1), LK_INVITE, "INVITE", source);
        }
        if (s0.equals("addlist") && segs.size() > 1) {
            return new Link("invite:addlist/" + segs.get(1), LK_INVITE, "INVITE", source);
        }
        if (s0.equals("c") && segs.size() > 1 && isDigits(segs.get(1))) {
            return new Link("tgc:" + segs.get(1), LK_HANDLE, "HANDLE", source);
        }
        if (s0.equals("s") && segs.size() > 1 && validUsername(segs.get(1))) {
            return new Link("@" + segs.get(1), LK_HANDLE, "HANDLE", source);
        }
        if (TME_RESERVED.contains(s0)) {
            return new Link("url:t.me/" + s0, LK_URL, "URL:t.me", "t.me", false, source);
        }
        if (validUsername(s0)) {
            return new Link("@" + s0, LK_HANDLE, "HANDLE", source);
        }
        return new Link("url:t.me/" + join("/", segs), LK_URL, "URL:t.me", "t.me", false, source);
    }

    /** Scheme per {@code ([a-z][a-z0-9+.\-]*)://} at the start, or -1. Returns the index after "://". */
    private static int schemeEnd(String u) {
        int n = u.length();
        if (n == 0 || !isAz(u.charAt(0))) {
            return -1;
        }
        int i = 1;
        while (i < n) {
            char c = u.charAt(i);
            if (isAsciiAlnum(c) || c == '+' || c == '.' || c == '-') {
                i++;
            } else {
                break;
            }
        }
        if (u.startsWith("://", i)) {
            return i + 3;
        }
        return -1;
    }

    /** SPEC §4.3. The input must already be folded (lower case, half width). May return null. */
    public static Link canonicalizeUrl(String raw, String source) {
        String u = pyStrip(raw).replace(" ", "");
        u = stripChars(u, TRIM, false, true);
        String scheme = "";
        int se = schemeEnd(u);
        if (se >= 0) {
            scheme = u.substring(0, se - 3);
            u = u.substring(se);
        }
        if (scheme.equals("tg")) {
            int qi = u.indexOf('?');
            String host = qi < 0 ? u : u.substring(0, qi);
            String rest = qi < 0 ? "" : u.substring(qi + 1);
            host = stripChars(host, "/", true, true);
            Map<String, String> q = queryDict(rest);
            if (host.equals("user") || host.equals("openmessage")) {
                String uid = host.equals("user") ? q.get("id") : q.get("user_id");
                if (isDigits(uid)) {
                    return new Link("uid:" + uid, LK_HANDLE, "HANDLE", source);
                }
            }
            if (host.equals("resolve") && validUsername(q.get("domain"))) {
                return new Link("@" + q.get("domain"), LK_HANDLE, "HANDLE", source);
            }
            if (host.equals("join")) {
                String inv = q.get("invite");
                if (inv != null && !inv.isEmpty()) {
                    return new Link("invite:" + inv, LK_INVITE, "INVITE", source);
                }
            }
            return new Link("url:tg/" + host, LK_URL, "URL:tg", "tg", false, source);
        }
        int cut = u.length();
        for (int k = 0; k < 3; k++) {
            int i = u.indexOf("/?#".charAt(k));
            if (i != -1 && i < cut) {
                cut = i;
            }
        }
        String hostport = u.substring(0, cut);
        String rest = u.substring(cut);
        int at = hostport.lastIndexOf('@');
        if (at >= 0) {
            hostport = hostport.substring(at + 1);
        }
        int colon = hostport.indexOf(':');
        String host = stripChars(colon >= 0 ? hostport.substring(0, colon) : hostport, ".", true, true);
        if (host.isEmpty()) {
            return null;
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        String alias = DOMAIN_ALIAS.get(host);
        if (alias != null) {
            host = alias;
        }
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        int qi = rest.indexOf('?');
        String path = qi < 0 ? rest : rest.substring(0, qi);
        String query = qi < 0 ? "" : rest.substring(qi + 1);
        path = stripChars(path, "/", false, true);
        if (host.equals("t.me")) {
            return tmeLink(path, source);
        }
        if (host.endsWith(".t.me")) {
            String name = host.substring(0, host.length() - 5);
            if (validUsername(name)) {
                return new Link("@" + name, LK_HANDLE, "HANDLE", source);
            }
        }
        if (host.equals("youtu.be")) {
            String vid = splitChar(stripChars(path, "/", true, true), '/').get(0);
            host = "youtube.com";
            path = "/watch";
            query = "v=" + vid + (query.isEmpty() ? "" : "&" + query);
        }
        if (host.equals("wa.me")) {
            String d = stripChars(path, "/", true, true);
            if (isDigits(d)) {
                return phoneLink(d, source);
            }
        }
        if ((host.equals("api.whatsapp.com") || host.equals("whatsapp.com")) && path.equals("/send")) {
            String ph = queryDict(query).get("phone");
            String d = digits(ph != null ? ph : "");
            if (!d.isEmpty()) {
                return phoneLink(d, source);
            }
        }
        String q = cleanQuery(query);
        String token = "url:" + host + path + (q.isEmpty() ? "" : "?" + q);
        if (SHORTENERS.contains(host)) {
            return new Link(token, LK_URL, "SHORT", host, true, source);
        }
        String rd = regdomain(host);
        return new Link(token, LK_URL, "URL:" + rd, rd, false, source);
    }

    /** Replace each span [a, b) (UTF-16 offsets into s) by one U+0020; overlapping later spans are skipped. */
    private static String removeSpans(String s, List<int[]> spans) {
        if (spans.isEmpty()) {
            return s;
        }
        ArrayList<int[]> sorted = new ArrayList<int[]>(spans);
        Collections.sort(sorted, new Comparator<int[]>() {
            @Override
            public int compare(int[] x, int[] y) {
                if (x[0] != y[0]) {
                    return x[0] < y[0] ? -1 : 1;
                }
                return x[1] < y[1] ? -1 : (x[1] == y[1] ? 0 : 1);
            }
        });
        StringBuilder sb = new StringBuilder(s.length());
        int pos = 0;
        for (int[] sp : sorted) {
            if (sp[0] < pos) {
                continue;
            }
            sb.append(s, pos, sp[0]).append(' ');
            pos = sp[1];
        }
        sb.append(s, pos, s.length());
        return sb.toString();
    }

    /** SPEC §4.6: contact keyword + account / phone keyword + number. Offsets are UTF-16; gap counted in scalars. */
    private static void contactAccounts(String s, List<Link> outLinks, List<int[]> outSpans) {
        LinkedHashMap<Long, Link> found = new LinkedHashMap<Long, Link>();
        int n = s.length();
        String[] words = AdShieldTables.KEYWORD_WORDS;
        String[] classes = AdShieldTables.KEYWORD_CLASSES;
        String[] kinds = AdShieldTables.KEYWORD_KINDS;
        Matcher digM = RX_DIGITS_SEP.matcher(s);
        digM.useTransparentBounds(true);
        digM.useAnchoringBounds(false);
        Matcher accM = RX_ACCOUNT.matcher(s);
        accM.useTransparentBounds(true);
        accM.useAnchoringBounds(false);
        for (int w = 0; w < words.length; w++) {
            String kw = words[w];
            String cls = classes[w];
            String kind = kinds[w];
            boolean asciiKw = true;
            for (int k = 0; k < kw.length(); k++) {
                if (kw.charAt(k) >= 0x80) {
                    asciiKw = false;
                    break;
                }
            }
            boolean phoneKind = kind.equals("phone_cjk") || kind.equals("phone_ascii");
            int start = 0;
            while (true) {
                int i = s.indexOf(kw, start);
                if (i < 0) {
                    break;
                }
                start = i + 1;
                int j = i + kw.length();
                if (asciiKw) {
                    if (i > 0 && isAsciiAlnum(s.charAt(i - 1))) {
                        continue;
                    }
                    if (j < n && isAz(s.charAt(j))) {
                        continue;
                    }
                }
                if (kind.equals("prefixed")) {
                    int k = i - 1;
                    while (k >= 0 && s.charAt(k) == ' ') {
                        k--;
                    }
                    if (k >= 0 && (s.charAt(k) == '我' || s.charAt(k) == '个')) {      // 我 / 个
                        k--;
                        while (k >= 0 && s.charAt(k) == ' ') {
                            k--;
                        }
                    }
                    if (k < 0 || (s.charAt(k) != '加' && s.charAt(k) != '+')) {           // 加 / +
                        continue;
                    }
                }
                int g = j;
                int gapCount = 0;
                boolean gapSep = false;
                while (g < n && gapCount < GAP_MAX) {
                    int cp = s.codePointAt(g);
                    if (cp == '\n' || isAsciiAlnum(cp)) {
                        break;
                    }
                    if (cp == ':' || cp == '=' || cp == '@') {
                        gapSep = true;
                    }
                    gapCount++;
                    g += Character.charCount(cp);
                }
                if (phoneKind) {
                    digM.region(g, n);
                    if (!digM.lookingAt()) {
                        continue;
                    }
                    long span = ((long) digM.start() << 32) | digM.end();
                    if (!found.containsKey(span)) {
                        found.put(span, new Link("tel:" + digits(digM.group()), LK_PHONE, "PHONE", SRC_TEXT));
                    }
                    continue;
                }
                accM.region(g, n);
                if (!accM.lookingAt()) {
                    continue;
                }
                int mStart = accM.start();
                int mEnd = accM.end();
                if (mEnd < n && isAsciiAlnum(s.charAt(mEnd))) {
                    continue;                               // longer than 30: not an account
                }
                String acct = stripChars(accM.group(), ".-", false, true);
                boolean allDigits = isDigits(acct);
                if (acct.length() < 4 || (allDigits && acct.length() < 5)) {
                    continue;
                }
                boolean hasAlpha = false, hasDigit = false;
                for (int k = 0; k < acct.length(); k++) {
                    char c = acct.charAt(k);
                    if (isAz(c)) {
                        hasAlpha = true;
                    } else if (isAsciiDigit(c)) {
                        hasDigit = true;
                    }
                }
                boolean hasUnderscore = acct.indexOf('_') >= 0;
                if (!allDigits && !hasAlpha && !hasUnderscore) {
                    continue;                               // only digits and . - (versions, dates)
                }
                if (kind.equals("standalone_ascii")) {
                    boolean ok = gapSep || hasUnderscore || (hasAlpha && hasDigit) || (allDigits && acct.length() >= 7);
                    if (!ok) {
                        continue;
                    }
                }
                if (kind.equals("standalone_strict")) {
                    if (!(gapSep || hasUnderscore) || !hasAlpha) {
                        continue;
                    }
                }
                long span = ((long) mStart << 32) | (mStart + acct.length());
                if (!found.containsKey(span)) {
                    found.put(span, new Link("acct:" + cls + ":" + acct, LK_ACCT, "ACCT:" + cls, SRC_TEXT));
                }
            }
        }
        ArrayList<Long> spans = new ArrayList<Long>(found.keySet());
        Collections.sort(spans, new Comparator<Long>() {       // (start asc, end desc), stable
            @Override
            public int compare(Long x, Long y) {
                int ax = (int) (x >>> 32), ay = (int) (y >>> 32);
                if (ax != ay) {
                    return ax < ay ? -1 : 1;
                }
                int bx = (int) (long) x, by = (int) (long) y;
                return bx > by ? -1 : (bx == by ? 0 : 1);
            }
        });
        int lastEnd = -1;
        for (Long sp : spans) {
            int a = (int) (sp >>> 32), b = (int) (long) sp;
            if (a < lastEnd) {
                continue;
            }
            outSpans.add(new int[]{a, b});
            outLinks.add(found.get(sp));
            lastEnd = b;
        }
    }

    private static String wordForm(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append(' ');
        boolean pendingSpace = false;
        boolean any = false;
        int i = 0, n = s.length();
        while (i < n) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isAsciiAlnum(cp)) {
                if (pendingSpace && any) {
                    sb.append(' ');
                }
                sb.append((char) cp);
                any = true;
                pendingSpace = false;
            } else {
                pendingSpace = true;
            }
        }
        sb.append(' ');
        return sb.toString();
    }

    /** SPEC §4.7: is this occurrence of pointer word p at tc[i] (UTF-16 offset) a real pointer? */
    private static boolean pointerHitOk(String tc, String p, int i) {
        String na = POINTER_NOT_AFTER.get(p);
        if (na != null && !na.isEmpty() && i > 0 && na.indexOf(tc.codePointBefore(i)) >= 0) {
            return false;
        }
        String nb = POINTER_NOT_BEFORE.get(p);
        int after = i + p.length();
        if (nb != null && !nb.isEmpty() && after < tc.length() && nb.indexOf(tc.codePointAt(after)) >= 0) {
            boolean ok = false;
            for (String w : AdShieldTables.POINTER_BEFORE_OK) {
                if (tc.startsWith(w, after)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        String pre = tc.substring(backByScalars(tc, i, POINTER_NEG_WINDOW), i);
        for (String neg : AdShieldTables.POINTER_NEGATION) {
            int k = pre.indexOf(neg);
            while (k >= 0) {
                String between = pre.substring(k + neg.length());
                boolean broken = false;
                for (String b : AdShieldTables.POINTER_NEGATION_BREAK) {
                    if (between.contains(b)) {
                        broken = true;
                        break;
                    }
                }
                if (!broken) {
                    return false;
                }
                k = pre.indexOf(neg, k + 1);
            }
        }
        return true;
    }

    private static boolean hasPointer(String fNoLinks) {
        String tc = AdShieldTables.cjkForm(AdShieldTables.textForm(fNoLinks));
        for (String p : AdShieldTables.POINTER_CJK) {
            int start = 0;
            while (true) {
                int i = tc.indexOf(p, start);
                if (i < 0) {
                    break;
                }
                start = i + 1;
                if (pointerHitOk(tc, p, i)) {
                    return true;
                }
            }
        }
        String w = wordForm(fNoLinks);
        for (String p : AdShieldTables.POINTER_ASCII) {
            if (w.contains(" " + p + " ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean asciiWord(String c) {
        for (int i = 0; i < c.length(); i++) {
            if (!isAz(c.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** SPEC §4.8 rule 2: number span [a, b) followed / preceded (1 optional space) by a currency marker. */
    private static boolean priceContext(String s, int a, int b) {
        String after = s.substring(b);
        if (after.startsWith(" ")) {
            after = after.substring(1);
        }
        for (String c : AdShieldTables.CURRENCY_AFTER) {
            if (after.startsWith(c)) {
                if (asciiWord(c) && after.length() > c.length() && isAz(after.charAt(c.length()))) {
                    continue;
                }
                return true;
            }
        }
        String before = s.substring(0, a);
        if (before.endsWith(" ")) {
            before = before.substring(0, before.length() - 1);
        }
        for (String c : AdShieldTables.CURRENCY_BEFORE) {
            if (before.endsWith(c)) {
                if (asciiWord(c) && before.length() > c.length() && isAz(before.charAt(before.length() - c.length() - 1))) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** The 12-scalar window before offset a, as an independent string (boundary checks never look outside it). */
    private static String windowBefore(String s, int a) {
        return s.substring(backByScalars(s, a, NUM_CTX_WINDOW), a);
    }

    /** (?<![a-z])word(?![a-z]) anywhere inside window. */
    private static boolean containsAsciiWord(String window, String word) {
        int idx = window.indexOf(word);
        while (idx >= 0) {
            int end = idx + word.length();
            boolean leftOk = idx == 0 || !isAz(window.charAt(idx - 1));
            boolean rightOk = end >= window.length() || !isAz(window.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            idx = window.indexOf(word, idx + 1);
        }
        return false;
    }

    private static List<String> longNumbers(String fNoLinks) {
        ArrayList<String> out = new ArrayList<String>();
        Matcher m = RX_LONG_NUMBER.matcher(fNoLinks);
        while (m.find()) {
            String run = m.group();
            if (run.length() == 8 && RX_DATE8.matcher(run).matches()) {
                continue;
            }
            if (priceContext(fNoLinks, m.start(), m.end())) {
                continue;
            }
            String window = windowBefore(fNoLinks, m.start());
            boolean ctx = false;
            for (String w : AdShieldTables.ORDER_CONTEXT_CJK) {
                if (window.contains(w)) {
                    ctx = true;
                    break;
                }
            }
            if (!ctx) {
                for (String w : AdShieldTables.ORDER_CONTEXT_ASCII) {
                    if (containsAsciiWord(window, w)) {
                        ctx = true;
                        break;
                    }
                }
            }
            if (!ctx) {
                out.add(run);
            }
        }
        return out;
    }

    private static boolean phoneExempt(String s, int a, int b) {
        if (priceContext(s, a, b)) {
            return true;
        }
        String window = windowBefore(s, a);
        for (String w : AdShieldTables.PHONE_ORDER_CONTEXT_CJK) {
            if (window.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * SPEC §4.2: scheme URLs -> t.me -> *.t.me -> e-mail -> bare domains -> @mention -> "@ name" -> phones
     * -> contact accounts; every round replaces its matches by one space before the next round.
     * The input must be folded. May throw StackOverflowError on pathological input on a small stack.
     */
    public static Extraction extractLinks(String f) {
        String s = f;
        ArrayList<Link> links = new ArrayList<Link>();
        ArrayList<String> mentions = new ArrayList<String>();
        Pattern[] urlRounds = {RX_SCHEME, RX_TME, RX_TME_SUB, RX_EMAIL, RX_DOMAIN};
        for (Pattern p : urlRounds) {
            ArrayList<int[]> spans = new ArrayList<int[]>();
            Matcher m = p.matcher(s);
            while (m.find()) {
                int a = m.start(), b = m.end();
                while (b > a && TRIM.indexOf(s.charAt(b - 1)) >= 0) {
                    b--;
                }
                if (b <= a) {
                    continue;
                }
                String raw = s.substring(a, b);
                if (p == RX_EMAIL) {
                    links.add(new Link("mail:" + raw, LK_MAIL, "MAIL", regdomain(raw.substring(raw.indexOf('@') + 1)),
                            false, SRC_TEXT));
                } else {
                    Link l = canonicalizeUrl(raw, SRC_TEXT);
                    if (l == null) {
                        continue;
                    }
                    links.add(l);
                }
                spans.add(new int[]{a, b});
            }
            s = removeSpans(s, spans);
        }
        for (int round = 0; round < 2; round++) {
            ArrayList<int[]> spans = new ArrayList<int[]>();
            Matcher m = (round == 0 ? RX_MENTION : RX_MENTION_SP).matcher(s);
            while (m.find()) {
                String h = m.group(1);
                if (round == 1) {
                    boolean ok = false;
                    for (int k = 0; k < h.length(); k++) {
                        char c = h.charAt(k);
                        if (c == '_' || isAsciiDigit(c)) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) {
                        continue;
                    }
                }
                mentions.add(h);
                links.add(new Link("@" + h, LK_HANDLE, "HANDLE", SRC_TEXT));
                spans.add(new int[]{m.start(), m.end()});
            }
            s = removeSpans(s, spans);
        }
        Pattern[] phones = {RX_PHONE_INTL, RX_PHONE_CN, RX_PHONE_TW};
        for (int r = 0; r < phones.length; r++) {
            ArrayList<int[]> spans = new ArrayList<int[]>();
            Matcher m = phones[r].matcher(s);
            while (m.find()) {
                if (phoneExempt(s, m.start(), m.end())) {
                    continue;
                }
                String d = digits(m.group());
                if (r == 1) {
                    d = "86" + d;
                } else if (r == 2) {
                    d = "886" + d.substring(1);
                }
                links.add(new Link("tel:" + d, LK_PHONE, "PHONE", SRC_TEXT));
                spans.add(new int[]{m.start(), m.end()});
            }
            s = removeSpans(s, spans);
        }
        ArrayList<int[]> spans = new ArrayList<int[]>();
        contactAccounts(s, links, spans);
        s = removeSpans(s, spans);
        return new Extraction(s, links, mentions, hasPointer(s), longNumbers(s));
    }

    /** CN mobiles get 86, TW 09... becomes 8869... (SPEC §4.2). Mutates and returns the link. */
    static Link normalizePhoneToken(Link link) {
        if (LK_PHONE.equals(link.kind)) {
            String d = link.token.substring(4);
            if (d.length() == 11 && d.charAt(0) == '1' && "3456789".indexOf(d.charAt(1)) >= 0) {
                link.token = "tel:86" + d;
            } else if (d.length() == 10 && d.startsWith("09")) {
                link.token = "tel:886" + d.substring(1);
            }
        }
        return link;
    }

    /** Leading salutation handles: "@alice 謝謝", "@a @b 歡迎" (SPEC §5.1). */
    static Set<String> leadingHandles(String f) {
        HashSet<String> out = new HashSet<String>();
        Matcher m = RX_LEADING.matcher(f);
        if (!m.lookingAt()) {
            return out;
        }
        Matcher n = RX_LEADING_NAME.matcher(m.group());
        while (n.find()) {
            out.add(n.group(1));
        }
        return out;
    }

    static boolean isAck(String t) {
        if (t.isEmpty() || t.indexOf('#') >= 0) {
            return false;
        }
        String r = t;
        boolean hit = false;
        for (String p : AdShieldTables.ACK_PHRASES) {
            if (r.contains(p)) {
                hit = true;
                r = r.replace(p, "");
            }
        }
        if (!hit) {
            return false;
        }
        for (String p : AdShieldTables.ACK_FILLERS) {
            r = r.replace(p, "");
        }
        return AdShieldTables.wlen(r) <= ACK_REMAINDER_MAX;
    }

    static boolean eligibleChat(ChatContext ctx) {
        return ctx != null && ELIGIBLE_CHAT_TYPES.contains(ctx.type);
    }

    static boolean eligibleMessage(MessageInput msg) {
        return !(msg.service || msg.sponsored || msg.scheduled);
    }

    /** observe only accepts 0 < msgId <= 2^31-1 and 0 <= date <= 2^40 (SPEC §6.1). */
    static boolean validMessage(MessageInput msg) {
        return msg.id > 0 && msg.date >= 0 && msg.date <= MAX_DATE;
    }

    private static long[] dedupeHashes(List<Long> xs, int cap) {
        ArrayList<Long> out = new ArrayList<Long>();
        for (Long x : xs) {
            if (!out.contains(x)) {
                out.add(x);
                if (out.size() == cap) {
                    break;
                }
            }
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static final Sender EMPTY_SENDER = new Sender();

    /**
     * SPEC §3-§5: one message -> normalised text, links, features, fingerprint keys.
     * The stored result (keys/flags/linkHashes/pending) depends only on (msg, ctx, key); knownHandles
     * (folded usernames, no '@') together with ctx.memberHandles only affect the resolved* fields.
     */
    public static Fingerprint fingerprint(MessageInput msg, ChatContext ctx, byte[] key, Collection<String> knownHandles) {
        return fingerprint(msg, ctx, key, knownHandles, true);
    }

    public static Fingerprint fingerprint(MessageInput msg, ChatContext ctx, byte[] key) {
        return fingerprint(msg, ctx, key, null, true);
    }

    static Fingerprint fingerprint(MessageInput msg, ChatContext ctx, byte[] key, Collection<String> knownHandles,
                                   boolean withResolved) {
        Sender sender = msg.sender != null ? msg.sender : EMPTY_SENDER;
        String role = sender.role != null ? sender.role : ROLE_MEMBER;
        long senderId = sender.id;
        String senderUsername = uname(sender.username);
        Forward fwd = msg.forward;
        boolean chanFwd = fwd != null && FWD_CHANNEL.equals(fwd.fromKind);
        Media media = msg.media;
        String mkind = media != null && media.kind != null ? media.kind : "";
        boolean isContact = mkind.equals("contact");
        List<Entity> entities = msg.entities != null ? msg.entities : Collections.<Entity>emptyList();

        String F = AdShieldTables.fold(msg.text != null ? msg.text : "");
        Extraction ex = extractLinks(F);
        ArrayList<Link> links = new ArrayList<Link>(ex.links);
        for (Entity ent : entities) {
            if (ent == null) {
                continue;
            }
            if (Entity.MENTION_NAME.equals(ent.type) && ent.userId != null && ent.userId.longValue() == senderId) {
                links.add(new Link("uid:" + ent.userId.longValue(), LK_HANDLE, "HANDLE", SRC_HIDDEN));   // points to self
            }
            if (Entity.TEXT_URL.equals(ent.type) && ent.url != null && !ent.url.isEmpty()) {
                Link l = canonicalizeUrl(AdShieldTables.fold(ent.url), SRC_HIDDEN);
                if (l != null) {
                    links.add(l);
                }
            }
        }
        ArrayList<String> buttons = new ArrayList<String>();
        if (msg.buttons != null) {
            for (String b : msg.buttons) {
                if (b != null && !b.isEmpty()) {
                    buttons.add(b);
                }
            }
        }
        for (String b : buttons) {
            Link l = canonicalizeUrl(AdShieldTables.fold(b), SRC_BUTTON);
            if (l != null) {
                links.add(l);
            }
        }
        String contactPhone = isContact && media.phone != null && !media.phone.isEmpty() ? media.phone : null;
        if (contactPhone != null) {
            Link l = phoneLink(digits(AdShieldTables.fold(contactPhone)), SRC_MEDIA);
            if (l != null) {
                links.add(l);
            }
        }
        for (Link l : links) {
            normalizePhoneToken(l);
        }

        String T = AdShieldTables.textForm(ex.foldNoLinks);
        String Tc = AdShieldTables.cjkForm(T);
        int wl = AdShieldTables.wlen(T);
        int[] lc = letterCounts(T);
        List<String> paras = paragraphs(ex.foldNoLinks);

        // ---- features ----
        Set<String> trustedTokens = ctx.trustedTokens();
        Set<String> trustedDomains = ctx.trustedDomains();
        String fwdUsername = uname(fwd != null ? fwd.username : null);
        Set<String> addressed = leadingHandles(F);
        TreeSet<String> strong = new TreeSet<String>(CODE_POINT_ORDER);
        TreeSet<String> weak = new TreeSet<String>(CODE_POINT_ORDER);
        ArrayList<String> features = new ArrayList<String>(links.size());
        for (Link l : links) {
            String feat = FEAT_NONE;
            if (trustedTokens.contains(l.token)) {
                feat = FEAT_NONE;
            } else if (LK_INVITE.equals(l.kind)) {
                feat = chanFwd ? FEAT_WEAK : FEAT_STRONG;
            } else if (LK_HANDLE.equals(l.kind)) {
                String h = l.token.startsWith("@") ? l.token.substring(1) : "";
                if (!h.isEmpty() && h.equals(fwdUsername)) {
                    feat = FEAT_NONE;                        // the forward source's own handle
                } else if (!h.isEmpty() && h.equals(ctx.selfUsername)) {
                    feat = FEAT_NONE;                        // @me
                } else if (chanFwd) {
                    feat = FEAT_WEAK;                        // part of the channel post
                } else if (!h.isEmpty() && h.equals(senderUsername)) {
                    feat = FEAT_STRONG;                      // self promotion
                } else if (h.isEmpty()) {
                    feat = FEAT_STRONG;                      // uid: / tgc:
                } else if (addressed.contains(h)) {
                    feat = FEAT_MEMBER;                      // leading salutation
                } else {
                    feat = FEAT_PENDING;                     // known member? decided at decide time
                }
            } else if (LK_URL.equals(l.kind)) {
                if (l.shortener) {
                    feat = chanFwd ? FEAT_WEAK : FEAT_STRONG;
                } else if (trustedDomains.contains(l.domain)) {
                    feat = FEAT_NONE;
                } else {
                    feat = FEAT_WEAK;
                }
            } else if (LK_PHONE.equals(l.kind)) {
                feat = FEAT_STRONG;
            } else if (LK_ACCT.equals(l.kind)) {
                feat = FEAT_STRONG;
            } else if (LK_MAIL.equals(l.kind)) {
                feat = trustedDomains.contains(l.domain) ? FEAT_NONE : FEAT_WEAK;
            }
            features.add(feat);
            if (feat == FEAT_STRONG) {
                if (LK_INVITE.equals(l.kind)) {
                    strong.add("invite");
                } else if (LK_HANDLE.equals(l.kind)) {
                    strong.add("handle");
                } else if (LK_URL.equals(l.kind)) {
                    strong.add("shortener");
                } else if (LK_PHONE.equals(l.kind)) {
                    strong.add("phone");
                } else if (LK_ACCT.equals(l.kind)) {
                    strong.add("account");
                }
            } else if (feat == FEAT_WEAK) {
                weak.add(LK_MAIL.equals(l.kind) ? "email" : "url");
            } else if (feat == FEAT_MEMBER) {
                weak.add("member_mention");
            }
        }
        if (ex.pointer) {
            strong.add("pointer");
        }
        if (!buttons.isEmpty()) {
            if (chanFwd) {
                weak.add("url");
            } else {
                strong.add("button");
            }
        }
        if (msg.viaBot) {
            if (chanFwd) {
                weak.add("url");
            } else {
                strong.add("via_bot");
            }
        }
        if (isContact) {
            strong.add("contact_card");
        }
        if (!ex.longNumbers.isEmpty()) {
            weak.add("long_number");
        }
        HashSet<String> mentionIds = new HashSet<String>(ex.mentions);
        for (Entity ent : entities) {
            if (ent != null && Entity.MENTION_NAME.equals(ent.type) && ent.userId != null) {
                mentionIds.add("#" + ent.userId.longValue());
            }
        }
        if (mentionIds.size() >= MASS_MENTION) {
            weak.add("mass_mention");
        }
        ArrayList<String> pendingTokens = new ArrayList<String>();
        for (int i = 0; i < links.size(); i++) {
            if (features.get(i) == FEAT_PENDING) {
                pendingTokens.add(links.get(i).token);
            }
        }

        // ---- fingerprint keys: FWD MEDIA TEXT CJK TMPL CONTACT PARA... ----
        ArrayList<Integer> kinds = new ArrayList<Integer>();
        ArrayList<String> payloads = new ArrayList<String>();
        String mediaPayload = "";
        String mk = MEDIA_KEYED.get(mkind);
        if (mk != null && media.id != null && !media.id.isEmpty()) {
            mediaPayload = mk + ":" + media.id;
        } else if (isContact) {
            if (media.userId != 0) {
                mediaPayload = "u:" + media.userId;
            } else if (contactPhone != null) {
                mediaPayload = "tel:" + digits(AdShieldTables.fold(contactPhone));
            }
        }
        if (fwd != null) {
            long fromId = fwd.fromId;
            long postId = fwd.postId;
            kinds.add(KIND_FWD);
            if (chanFwd && fromId != 0 && postId != 0) {
                payloads.add("c|" + fromId + "|" + postId);
            } else {
                String who = fromId != 0 ? Long.toString(fromId)
                        : "n:" + AdShieldTables.textForm(AdShieldTables.fold(fwd.fromName != null ? fwd.fromName : ""));
                String content = !T.isEmpty() ? "t:" + T : (!mediaPayload.isEmpty() ? "m:" + mediaPayload : "e");
                payloads.add("u|" + who + "|" + fwd.date + "|" + content);
            }
        }
        if (!mediaPayload.isEmpty()) {
            kinds.add(KIND_MEDIA);
            payloads.add(mediaPayload);
        }
        if (wl >= MIN_TEXT_WLEN) {
            kinds.add(KIND_TEXT);
            payloads.add(T);
        }
        if (lc[0] >= MIN_CJK_COUNT) {
            kinds.add(KIND_CJK);
            payloads.add(Tc);
        }
        boolean tmplCond = false;
        if ((!strong.isEmpty() || !pendingTokens.isEmpty()) && wl >= MIN_TMPL_WLEN) {
            TreeSet<String> types = new TreeSet<String>(CODE_POINT_ORDER);
            for (Link l : links) {
                types.add(l.type.startsWith("URL:") ? "URL" : l.type);
            }
            if (!buttons.isEmpty()) {
                types.add("BUTTON");
            }
            if (msg.viaBot) {
                types.add("VIABOT");
            }
            if (isContact) {
                types.add("CONTACT");
            }
            kinds.add(KIND_TMPL);
            payloads.add(T + "\u001e" + join(",", types));
            tmplCond = strong.isEmpty();
        }
        TreeSet<String> contactTokens = new TreeSet<String>(CODE_POINT_ORDER);
        for (int i = 0; i < links.size(); i++) {
            Link l = links.get(i);
            if (features.get(i) == FEAT_STRONG
                    && (LK_INVITE.equals(l.kind) || LK_PHONE.equals(l.kind) || LK_ACCT.equals(l.kind))) {
                contactTokens.add(l.token);
            }
        }
        if (!contactTokens.isEmpty()) {
            kinds.add(KIND_CONTACT);
            payloads.add(join(",", contactTokens));
        }
        for (String p : paras) {
            kinds.add(KIND_PARA);
            payloads.add(p);
        }
        int nk = kinds.size();
        int[] keyKinds = new int[nk];
        String[] keyPayloads = new String[nk];
        long[] keyHashes = new long[nk];
        for (int i = 0; i < nk; i++) {
            keyKinds[i] = kinds.get(i);
            keyPayloads[i] = payloads.get(i);
            keyHashes[i] = AdShieldTables.keyedHash(key, KIND_TAG[keyKinds[i]], keyPayloads[i]);
        }

        ArrayList<Long> subset = new ArrayList<Long>();
        for (int i = 0; i < links.size(); i++) {
            String f = features.get(i);
            if (f == FEAT_STRONG || f == FEAT_WEAK) {
                subset.add(AdShieldTables.keyedHash(key, "link", links.get(i).token));
            }
        }
        if (ex.pointer) {
            subset.add(AdShieldTables.keyedHash(key, "link", "ptr:"));
        }
        if (msg.viaBot) {
            subset.add(AdShieldTables.keyedHash(key, "link", "via:"));
        }
        long[] linkHashes = dedupeHashes(subset, MAX_LINKS_PER_ROW);
        ArrayList<Long> pend = new ArrayList<Long>();
        for (String tok : pendingTokens) {
            pend.add(AdShieldTables.keyedHash(key, "link", tok));
        }
        long[] pending = dedupeHashes(pend, MAX_PENDING_PER_ROW);

        // ---- flags ----
        boolean endorsing = (ENDORSING_ROLES.contains(role) && !(fwd != null && !chanFwd))   // forwarding others: no
                || msg.pinned || msg.autoForward
                || (fwd != null && ctx.linkedChannelId != 0 && fwd.fromId == ctx.linkedChannelId)
                || (ROLE_CHANNEL.equals(role) && ctx.linkedChannelId != 0 && senderId == ctx.linkedChannelId);
        int flags = 0;
        for (int b = 0; b < STRONG_NAMES.length; b++) {
            if (strong.contains(STRONG_NAMES[b])) {
                flags |= 1 << b;
            }
        }
        for (int b = 0; b < WEAK_NAMES.length; b++) {
            if (weak.contains(WEAK_NAMES[b])) {
                flags |= 1 << (WEAK_BASE + b);
            }
        }
        if (wl >= MIN_TEXT_WLEN) {
            flags |= F_HAS_TEXT;
        }
        for (int k : keyKinds) {
            if (k == KIND_MEDIA) {
                flags |= F_HAS_MEDIA_KEY;
            } else if (k == KIND_FWD) {
                flags |= F_HAS_FWD_KEY;
            }
        }
        if (tmplCond) {
            flags |= F_TMPL_COND;
        }
        if ((lc[0] >= PURE_TEXT_MIN_CJK || lc[1] >= PURE_TEXT_MIN_LETTERS) && T.indexOf('#') < 0) {
            flags |= F_PURE_TEXT;
        }
        if (msg.replyTo != 0) {
            flags |= F_IS_REPLY;
            if (isAck(T)) {
                flags |= F_ACK;
            }
        }
        if (msg.replyToMe) {
            flags |= F_REPLY_ME;
        }
        if (msg.mentionsMe) {
            flags |= F_MENTION_ME;
        }
        if (endorsing) {
            flags |= F_ENDORSING;
        }
        if (ROLE_BOT.equals(role)) {
            flags |= F_BOT;
        }
        if (ROLE_CONTACT.equals(role)) {
            flags |= F_CONTACT;
        }
        if (ROLE_SELF.equals(role)) {
            flags |= F_SELF;
        }

        Fingerprint fp = new Fingerprint();
        fp.store = nk > 0 && (!strong.isEmpty() || !weak.isEmpty() || pending.length > 0
                || (flags & (F_HAS_MEDIA_KEY | F_HAS_FWD_KEY | F_PURE_TEXT)) != 0 || endorsing);
        fp.senderKey = senderKey(key, senderId);
        fp.fold = F;
        fp.foldNoLinks = ex.foldNoLinks;
        fp.text = T;
        fp.cjk = Tc;
        fp.wlen = wl;
        fp.cjkCount = lc[0];
        fp.letterCount = lc[1];
        fp.paragraphs = paras;
        fp.links = links;
        fp.linkFeatures = features;
        fp.strong = new ArrayList<String>(strong);
        fp.weak = new ArrayList<String>(weak);
        fp.keyKinds = keyKinds;
        fp.keyPayloads = keyPayloads;
        fp.keyHashes = keyHashes;
        fp.linkHashes = linkHashes;
        fp.pending = pending;
        fp.flags = flags;
        fp.endorsing = endorsing;
        fp.mentionCount = mentionIds.size();

        if (withResolved) {
            final HashSet<Long> known = new HashSet<Long>();
            if (knownHandles != null) {
                for (String n : knownHandles) {
                    known.add(handleHash(key, n));
                }
            }
            for (String n : ctx.memberHandles) {
                known.add(handleHash(key, n));
            }
            Resolved r = resolve(flags, fp.keys(), linkHashes, pending, new KnownFn() {
                @Override
                public boolean isKnown(long h) {
                    return known.contains(h);
                }
            });
            fp.resolvedFlags = r.flags;
            fp.resolvedStrong = featureNames(r.flags, true);
            fp.resolvedWeak = featureNames(r.flags, false);
        }
        return fp;
    }

    static List<String> featureNames(int flags, boolean strong) {
        TreeSet<String> out = new TreeSet<String>(CODE_POINT_ORDER);
        String[] names = strong ? STRONG_NAMES : WEAK_NAMES;
        int base = strong ? 0 : WEAK_BASE;
        for (int b = 0; b < names.length; b++) {
            if ((flags & (1 << (base + b))) != 0) {
                out.add(names[b]);
            }
        }
        return new ArrayList<String>(out);
    }

    /**
     * SPEC §5.5: resolve pending handles against "known member". Unknown -> strong handle (and the
     * handle counts as a new link for the endorsement subset check); known -> weak member_mention.
     * Drops the conditional TMPL key when no pending handle is unknown, and computes MEDIA_ONLY.
     */
    public static Resolved resolve(int flags, Key[] keys, long[] links, long[] pending, KnownFn isKnown) {
        int nUnknown = 0;
        long[] unknown = new long[pending.length];
        for (long h : pending) {
            if (!isKnown.isKnown(h)) {
                unknown[nUnknown++] = h;
            }
        }
        int f = flags & ~F_MEDIA_ONLY;
        if (nUnknown > 0) {
            f |= S_HANDLE;
        }
        if (nUnknown < pending.length) {
            f |= W_MEMBER_MENTION;
        }
        Key[] outKeys = keys;
        if ((flags & F_TMPL_COND) != 0 && nUnknown == 0) {
            int cnt = 0;
            for (Key k : keys) {
                if (k.kind != KIND_TMPL) {
                    cnt++;
                }
            }
            if (cnt != keys.length) {
                outKeys = new Key[cnt];
                int i = 0;
                for (Key k : keys) {
                    if (k.kind != KIND_TMPL) {
                        outKeys[i++] = k;
                    }
                }
            }
        }
        long[] outLinks = links;
        if (nUnknown > 0) {
            int extra = 0;
            long[] add = new long[nUnknown];
            for (int i = 0; i < nUnknown; i++) {
                boolean present = false;
                for (long l : links) {
                    if (l == unknown[i]) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    add[extra++] = unknown[i];
                }
            }
            if (extra > 0) {
                outLinks = Arrays.copyOf(links, links.length + extra);
                System.arraycopy(add, 0, outLinks, links.length, extra);
            }
        }
        if ((f & F_HAS_MEDIA_KEY) != 0 && (f & STRONG_MASK) == 0) {
            f |= F_MEDIA_ONLY;
        }
        return new Resolved(f, outKeys, outLinks);
    }
}
