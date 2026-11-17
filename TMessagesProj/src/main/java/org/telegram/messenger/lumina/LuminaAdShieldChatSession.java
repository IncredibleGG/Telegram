/*
 * LuminaGram - AdShield ("隱藏重複廣告"): one open group chat.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.messenger.lumina;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The AdShield side of one open group chat (ChatActivity in MODE_DEFAULT, including forum topics and
 * comment threads; never private chats, secret chats, Saved Messages or broadcast channels).
 *
 * <p>Observation pipeline (only while the feature is effectively on for this chat): the session registers
 * its own NotificationCenter observers for history loads, new messages, edits, deletions, chat info,
 * pinned messages and admin lists. The UI thread copies plain fields into engine inputs (the keyword
 * filter runs first: what it hides is never observed) and posts them to the engine thread, which observes,
 * applies the similar-image hook, debounces the flush and decides the loaded messages with one {@code now}
 * per batch. Live events (new messages, edits) are coalesced on the UI thread, and back-to-back engine
 * batches publish once. The engine thread only computes decisions; they are applied to the
 * {@link ConcurrentHashMap} that the chat UI reads in O(1) on the UI thread, together with the display
 * rules below, and the changed ids are reported to the {@link Listener}.
 *
 * <p>Display rules kept here so the chat UI only asks {@link #getDisplayState}: trial mode never folds;
 * "not an ad" unfolds at once; expanded segments are runtime-only; a message that was shown unfolded in
 * this session never folds afterwards (it folds the next time the chat is opened). Messages that arrive
 * with a history load may still fold when their decision comes in, but only while they have been on
 * screen for less than {@link #SHOWN_LOCK_MS} (the engine normally answers within a few milliseconds of
 * the load). Rows the store already holds are decided as soon as the chat opens, so known ads bind as
 * fold rows right away.
 *
 * <p>Created by {@link #open}; {@link #close} when the chat goes away. UI-thread object except where noted.
 */
public final class LuminaAdShieldChatSession implements NotificationCenter.NotificationCenterDelegate {

    /** What the chat UI needs from ChatActivity (UI thread). */
    public interface Host {
        int getClassGuid();

        /** ChatActivity's message list (newest first, may contain date / unread separators). */
        List<MessageObject> getLoadedMessages();

        /** Loaded pinned messages (may be null). */
        Collection<MessageObject> getPinnedMessages();

        TLRPC.ChatFull getChatFull();

        /** The album {@code groupId} as the chat lays it out (null when unknown). */
        default MessageObject.GroupedMessages getGroupedMessages(long groupId) {
            return null;
        }
    }

    /** UI-thread callbacks for the chat UI stage. */
    public interface Listener {
        /** These message ids may have changed display state; null = re-check every message. */
        void onAdShieldChanged(int[] msgIds);

        /** Once per chat per app session: every new message of this opening folded (show the toast). */
        void onAdShieldAllNewFolded();
    }

    public static final int DISPLAY_NORMAL = 0;
    /** Part of a fold segment: the segment head becomes the fold row, the other members are hidden. */
    public static final int DISPLAY_FOLDED = 1;
    /** Would fold, but its segment was expanded by a tap (show the slim header above the segment). */
    public static final int DISPLAY_EXPANDED = 2;
    /** Trial mode: show normally with a small 「疑似重複廣告」 label. */
    public static final int DISPLAY_TRIAL_LABEL = 3;

    /** Display information of one segment (see {@link #segmentInfo}). */
    public static final class SegmentInfo {
        /** Message ids of the segment, list order. */
        public int[] msgIds;
        /** Units in the segment: an album counts once (the n of 「已收合 n 則」). */
        public int count;
        /** Some member replies to / mentions me (use the 「回覆你的」 text). */
        public boolean replyToMe;
        /** For a single-unit segment: Decision.occurrence (「7 天內第 k 次出現」), else 0. */
        public int occurrence;
    }

    private static final int MAX_DECIDE = 800;
    /** Stored rows (newest message ids first) decided when the chat opens, before any history arrives. */
    private static final int PRE_DECIDE = 400;
    private static final long OPENING_GRACE_MS = 8000;
    /** A history message bound on screen for this long never folds in this session any more. */
    private static final long SHOWN_LOCK_MS = 500;
    /** New messages and edits are collected for this long and observed as one batch. */
    private static final long LIVE_COALESCE_MS = 300;
    /** Back-to-back engine batches publish once, but never later than this after the first one waited. */
    private static final long MAX_DEFER_MS = 1000;
    private static final long[] ADMIN_RECHECK_MS = {2000, 5000, 12000, 30000};
    /** After the quick rechecks: keep looking for a silently loaded admin list at this pace. */
    private static final long ADMIN_SLOW_RECHECK_MS = 60000;
    /** A jump target (reply quote, search, link) still unfolds its segment if its decision folds it this late. */
    private static final long JUMP_MS = 15000;
    private static final int[] EMPTY = new int[0];

    /** Bookkeeping that travels with one engine batch and comes back to the UI thread. */
    private static final class Batch {
        int[] grace = EMPTY;        // ids allowed to fold although they may already be bound
        int[] newIds;               // unread incoming ids of a history batch (all-new-folded check)

        /** Two batches published together (never modifies either one). */
        static Batch merge(Batch a, Batch b) {
            if (a == null || a == NO_BATCH) {
                return b;
            }
            if (b == null || b == NO_BATCH) {
                return a;
            }
            Batch m = new Batch();
            m.grace = concat(a.grace, b.grace);
            m.newIds = a.newIds == null ? b.newIds : b.newIds == null ? a.newIds : concat(a.newIds, b.newIds);
            return m;
        }
    }

    private static final Batch NO_BATCH = new Batch();

    /** A grace message the chat UI bound as a normal row. */
    private static final class Shown {
        long at;                    // SystemClock.elapsedRealtime() of the first bind
        long album;                 // grouped_id, 0 = none
        MessageObject mo;           // the bound object (album-aware display state)
    }
    private static final HashSet<String> allNewNotified = new HashSet<String>();    // UI thread

    public final int account;
    public final long dialogId;
    private final long userId;
    private final long chatId;          // TL id (positive)
    private final long botChatId;       // engine chat id (Bot-API form)
    private final String chatType;
    private final Host host;
    private Listener listener;

    private boolean closed;
    private volatile boolean closedFlag;
    private boolean active;
    private boolean trial;
    private boolean observing;
    private long activatedAt;

    /** Read by the chat UI on every bind; replaced (never cleared in place) when the session resets. */
    private volatile ConcurrentHashMap<Integer, LuminaAdShield.Decision> decisions =
            new ConcurrentHashMap<Integer, LuminaAdShield.Decision>();
    private volatile int version;

    // ---- UI thread ----
    private final HashSet<Integer> expanded = new HashSet<Integer>();
    private final HashSet<Long> expandedAlbums = new HashSet<Long>();
    private final HashSet<Integer> locked = new HashSet<Integer>();
    private final HashSet<Long> lockedAlbums = new HashSet<Long>();
    private final HashSet<Integer> grace = new HashSet<Integer>();
    /** Grace ids bound as a normal row (first bind time, album, the bound object). */
    private final HashMap<Integer, Shown> shownInGrace = new HashMap<Integer, Shown>();
    private final HashSet<Integer> notAdLocal = new HashSet<Integer>();
    private LuminaAdShield.ChatContext ctx;
    private LuminaAdShieldMapper.Roles roles;
    private TLRPC.ChatFull full;
    private Collection<MessageObject> pinned;
    /** Pin / unpin updates of this session (the loaded MessageObjects keep their old {@code pinned} flag). */
    private final HashMap<Integer, Boolean> pinOverride = new HashMap<Integer, Boolean>();
    private boolean ownerPinnedStale;
    private long ctxSignature = Long.MIN_VALUE;
    private int adminRecheck;
    private Runnable adminRecheckRunnable;
    private boolean preDecided;
    private final LinkedHashMap<Integer, MessageObject> pendingLive = new LinkedHashMap<Integer, MessageObject>();
    private Runnable liveFlush;
    private final int openReadMax;
    private boolean allNewChecked;
    private int[] openingNewIds;
    private int jumpTarget;
    private long jumpTargetAt;

    // ---- engine thread ----
    /** The most recently observed ids (at most MAX_DECIDE, oldest first): re-decided on every publish. */
    private final LinkedHashSet<Integer> tracked = new LinkedHashSet<Integer>();
    private Object engineTarget;
    private int engineEpoch = -1;
    /** Observed batches whose publish waits for the next engine batch already in the queue. */
    private LinkedHashSet<Integer> deferredIds;
    private Batch deferredBatch;
    private Object deferredTarget;
    private long deferredSince;
    /** Observe tasks posted but not started yet (any thread). */
    private final AtomicInteger queuedObserves = new AtomicInteger();

    private LuminaAdShieldChatSession(int account, TLRPC.Chat chat, String chatType, Host host) {
        this.account = account;
        this.dialogId = -chat.id;
        this.chatId = chat.id;
        this.botChatId = LuminaAdShieldMapper.botApiChatId(chat);
        this.chatType = chatType;
        this.host = host;
        this.userId = UserConfig.getInstance(account).getClientUserId();
        Integer readMax = MessagesController.getInstance(account).dialogs_read_inbox_max.get(dialogId);
        this.openReadMax = readMax != null ? readMax : -1;
    }

    /**
     * A session for an eligible group chat opened in MODE_DEFAULT, else null. The session stays inert (no
     * observers, no engine work) until the feature is effectively on for this chat.
     */
    public static LuminaAdShieldChatSession open(int account, TLRPC.Chat chat, int chatMode, boolean insideContainer, Host host) {
        if (chat == null || chatMode != 0 || insideContainer || host == null) {
            return null;
        }
        String type = LuminaAdShieldMapper.chatType(chat);
        if (type == null || UserConfig.getInstance(account).getClientUserId() == 0) {
            return null;
        }
        LuminaAdShieldChatSession s = new LuminaAdShieldChatSession(account, chat, type, host);
        LuminaAdShieldManager.addSession(s);
        s.refresh(LuminaAdShieldManager.CHANGE_SETTINGS);
        return s;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closedFlag = true;
        setObserving(false);
        cancelAdminRecheck();
        cancelLive();
        LuminaAdShieldManager.removeSession(this);
        listener = null;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // =====================================================================================
    // Public state for the chat UI and the group menu (UI thread unless noted)
    // =====================================================================================

    /** The feature is effectively on for this chat (global on and not set off here, or set on here). */
    public boolean isActive() {
        return active;
    }

    /** Trial mode: label only, never fold. */
    public boolean isTrial() {
        return trial;
    }

    /** Bumped on every published batch and on resets (any thread). */
    public int getVersion() {
        return version;
    }

    public int getChatMode() {
        return LuminaAdShieldManager.getChatMode(account, dialogId);
    }

    /** 跟隨預設 / 在此群開啟 / 在此群關閉 (LuminaAdShieldManager.MODE_*). */
    public void setChatMode(int mode) {
        LuminaAdShieldManager.setChatMode(account, dialogId, mode);
    }

    /** Raw engine decision, or null (not stored / not decided yet). Any thread, O(1). */
    public LuminaAdShield.Decision getDecision(int msgId) {
        return decisions.get(msgId);
    }

    /** Display state of one message id (no album handling; see {@link #getDisplayState(MessageObject)}). */
    public int getDisplayState(int msgId) {
        if (!active || msgId <= 0) {
            return DISPLAY_NORMAL;
        }
        LuminaAdShield.Decision d = decisions.get(msgId);
        if (d == null || !d.fold || notAdLocal.contains(msgId)) {
            return DISPLAY_NORMAL;
        }
        if (trial) {
            return DISPLAY_TRIAL_LABEL;
        }
        if (locked.contains(msgId)) {
            return DISPLAY_NORMAL;
        }
        return expanded.contains(msgId) ? DISPLAY_EXPANDED : DISPLAY_FOLDED;
    }

    /** Display state of a message; album members share one state (an album folds as one unit). */
    public int getDisplayState(MessageObject mo) {
        if (mo == null || mo.messageOwner == null || mo.getDialogId() != dialogId) {
            return DISPLAY_NORMAL;
        }
        int st = getDisplayState(mo.getId());
        long album = mo.messageOwner.grouped_id;
        if (album != 0) {
            if (st == DISPLAY_NORMAL && active && !decisions.isEmpty() && !decisions.containsKey(mo.getId())) {
                // A member whose own decision has not come back yet follows its siblings, so an album is
                // never drawn half folded (the engine decides per unit; this only bridges one publish).
                MessageObject.GroupedMessages g = host.getGroupedMessages(album);
                if (g != null && g.messages != null) {
                    for (int i = 0, n = g.messages.size(); i < n; i++) {
                        MessageObject o = g.messages.get(i);
                        if (o != null && o != mo && o.getId() != mo.getId() && o.getId() > 0) {
                            int other = getDisplayState(o.getId());
                            if (other != DISPLAY_NORMAL) {
                                st = other;
                                break;
                            }
                        }
                    }
                }
            }
            if (st != DISPLAY_NORMAL && st != DISPLAY_TRIAL_LABEL) {
                if (lockedAlbums.contains(album)) {
                    return DISPLAY_NORMAL;
                }
                if (expandedAlbums.contains(album)) {
                    return DISPLAY_EXPANDED;
                }
            }
        }
        return st;
    }

    /**
     * The chat UI bound {@code mo} as a normal, visible message row (not folded and not inside an expanded
     * segment). From then on it never folds in this session, unless it came with a history load whose
     * decision is still outstanding and it has been on screen for less than {@link #SHOWN_LOCK_MS}.
     */
    public void noteShown(MessageObject mo) {
        if (!active || mo == null || mo.messageOwner == null) {
            return;
        }
        int id = mo.getId();
        if (id <= 0) {
            return;
        }
        if (grace.contains(id)) {
            Shown sh = shownInGrace.get(id);
            if (sh == null) {
                shownInGrace.put(id, sh = new Shown());
                sh.at = SystemClock.elapsedRealtime();
            }
            sh.mo = mo;
            sh.album = mo.messageOwner.grouped_id;
            return;
        }
        if (locked.add(id) && mo.messageOwner.grouped_id != 0) {
            lockedAlbums.add(mo.messageOwner.grouped_id);
        }
    }

    /** Tap on the fold row: show the whole segment (runtime only). */
    public void expand(int[] msgIds) {
        setExpanded(msgIds, true);
    }

    /** 「收合」 in the slim header: fold the segment again. */
    public void collapse(int[] msgIds) {
        if (msgIds != null && jumpTarget != 0) {
            for (int id : msgIds) {
                if (id == jumpTarget) {
                    jumpTarget = 0;
                    break;
                }
            }
        }
        setExpanded(msgIds, false);
    }

    public boolean isExpanded(int msgId) {
        return expanded.contains(msgId);
    }

    private void setExpanded(int[] msgIds, boolean on) {
        if (msgIds == null || msgIds.length == 0) {
            return;
        }
        for (int id : msgIds) {
            if (on) {
                expanded.add(id);
            } else {
                expanded.remove(id);
            }
        }
        List<MessageObject> list = host.getLoadedMessages();
        if (list != null) {
            HashSet<Integer> ids = new HashSet<Integer>();
            for (int id : msgIds) {
                ids.add(id);
            }
            for (MessageObject mo : list) {
                if (mo != null && mo.messageOwner != null && mo.messageOwner.grouped_id != 0 && ids.contains(mo.getId())) {
                    if (on) {
                        expandedAlbums.add(mo.messageOwner.grouped_id);
                    } else {
                        expandedAlbums.remove(mo.messageOwner.grouped_id);
                    }
                }
            }
        }
        version++;
        notifyChanged(msgIds);
    }

    /**
     * Jump / reply target: expands the segment containing {@code msgId} when it is folded. Returns true when
     * something was expanded.
     */
    public boolean expandContaining(List<MessageObject> messages, int msgId) {
        if (!active || messages == null) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject mo = messages.get(i);
            if (mo != null && mo.getId() == msgId && mo.getDialogId() == dialogId) {
                if (getDisplayState(mo) != DISPLAY_FOLDED) {
                    return false;
                }
                int[] b = segmentBounds(messages, i, false);
                if (b == null) {
                    return false;
                }
                expand(segmentInfo(messages, b[0], b[1]).msgIds);
                return true;
            }
        }
        return false;
    }

    /**
     * Jump / reply / search / link target {@code msgId}: expands its segment when it is folded now, and keeps
     * doing so for the next {@link #JUMP_MS} ms in case the target's decision (e.g. of a history batch that
     * the jump just loaded) only arrives later.
     */
    public void expandForJump(List<MessageObject> messages, int msgId) {
        if (!active || msgId <= 0) {
            return;
        }
        jumpTarget = msgId;
        jumpTargetAt = SystemClock.elapsedRealtime();
        expandContaining(messages, msgId);
    }

    private void checkJumpTarget() {
        if (jumpTarget == 0) {
            return;
        }
        if (SystemClock.elapsedRealtime() - jumpTargetAt > JUMP_MS) {
            jumpTarget = 0;
            return;
        }
        expandContaining(host.getLoadedMessages(), jumpTarget);
    }

    /**
     * Bounds {first, last} (inclusive indices into {@code messages}) of the fold segment that contains index
     * {@code index}: the maximal run of consecutive entries whose display state is FOLDED (or also EXPANDED
     * when {@code includeExpanded}). Anything else (a normal message, a date or unread separator) ends the
     * run. Null when the entry at {@code index} is not part of a segment.
     */
    public int[] segmentBounds(List<MessageObject> messages, int index, boolean includeExpanded) {
        if (messages == null || index < 0 || index >= messages.size() || !inSegment(messages.get(index), includeExpanded)) {
            return null;
        }
        int first = index;
        while (first - 1 >= 0 && inSegment(messages.get(first - 1), includeExpanded)) {
            first--;
        }
        int last = index;
        while (last + 1 < messages.size() && inSegment(messages.get(last + 1), includeExpanded)) {
            last++;
        }
        return new int[]{first, last};
    }

    private boolean inSegment(MessageObject mo, boolean includeExpanded) {
        int st = getDisplayState(mo);
        return st == DISPLAY_FOLDED || includeExpanded && st == DISPLAY_EXPANDED;
    }

    /** Texts and ids for the fold row / slim header of messages[first..last]. */
    public SegmentInfo segmentInfo(List<MessageObject> messages, int first, int last) {
        SegmentInfo info = new SegmentInfo();
        ArrayList<Integer> ids = new ArrayList<Integer>();
        HashSet<Long> albums = new HashSet<Long>();
        int singles = 0;
        int lastOccurrence = 0;
        for (int i = Math.max(0, first); i <= last && i < messages.size(); i++) {
            MessageObject mo = messages.get(i);
            if (mo == null || mo.messageOwner == null) {
                continue;
            }
            ids.add(mo.getId());
            long album = mo.messageOwner.grouped_id;
            if (album != 0) {
                albums.add(album);
            } else {
                singles++;
            }
            LuminaAdShield.Decision d = decisions.get(mo.getId());
            if (d != null) {
                info.replyToMe |= d.replyToMe;
                lastOccurrence = d.occurrence;
            }
        }
        info.msgIds = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            info.msgIds[i] = ids.get(i);
        }
        info.count = singles + albums.size();
        info.occurrence = info.count == 1 ? lastOccurrence : 0;
        return info;
    }

    /**
     * 「不是廣告」: records the cluster of {@code anchorMsgId} for 90 days (SPEC §6.4) and unfolds
     * {@code segmentMsgIds} right away.
     */
    public void markNotAd(int anchorMsgId, int[] segmentMsgIds) {
        if (!active || anchorMsgId <= 0) {
            return;
        }
        if (segmentMsgIds != null) {
            for (int id : segmentMsgIds) {
                notAdLocal.add(id);
                expanded.remove(id);
            }
        }
        notAdLocal.add(anchorMsgId);
        version++;
        notifyChanged(segmentMsgIds != null ? segmentMsgIds : new int[]{anchorMsgId});
        final LuminaAdShield.ChatContext c = context();
        final ConcurrentHashMap<Integer, LuminaAdShield.Decision> target = decisions;
        LuminaAdShieldManager.post(() -> {
            LuminaAdShieldStore store = engineStore(target, true);
            if (store == null) {
                return;
            }
            long now = LuminaAdShieldManager.now(account);
            try {
                store.markNotAd(anchorMsgId, c, now);
                // A segment can hold more than one cluster (two campaigns posted back to back): record each
                // one that still folds, so 「不是廣告」 on the fold row covers everything the row stood for.
                if (segmentMsgIds != null && c != null) {
                    LuminaAdShield.Settings s = LuminaAdShieldManager.settings();
                    for (int id : segmentMsgIds) {
                        if (id > 0 && id != anchorMsgId && store.decide(id, c, s, now).fold) {
                            store.markNotAd(id, c, now);
                        }
                    }
                }
            } catch (LuminaAdShieldStore.ForgottenException e) {
                return;
            }
            LuminaAdShieldManager.scheduleFlush(LuminaAdShieldManager.engineAccount(account, userId, false), botChatId);
            // the same cluster may fold further back in the loaded history: decide everything again
            enginePublish(store, c, now, target, NO_BATCH, EMPTY, true);
        });
    }

    /** 「最近 7 天已收合 N 則」 for the group menu; 0 when the feature is off here. Callback on the UI thread. */
    public void requestFoldedCount(Utilities.Callback<Integer> callback) {
        if (callback == null) {
            return;
        }
        if (!active) {
            callback.run(0);
            return;
        }
        final LuminaAdShield.ChatContext c = context();
        final ConcurrentHashMap<Integer, LuminaAdShield.Decision> target = decisions;
        LuminaAdShieldManager.post(() -> {
            int n = 0;
            LuminaAdShieldStore store = engineStore(target, true);
            if (store != null) {
                long t0 = SystemClock.elapsedRealtime();
                n = LuminaAdShieldManager.engineFoldedCount7d(store, c, LuminaAdShieldManager.settings(), LuminaAdShieldManager.now(account));
                LuminaAdShield.log("adshield folded count in " + (SystemClock.elapsedRealtime() - t0) + " ms");
            }
            final int result = n;
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    // =====================================================================================
    // Activation
    // =====================================================================================

    /** Settings changed, data cleared, or the account logged out (UI thread). */
    void onManagerChange(int change) {
        if (!closed) {
            refresh(change);
        }
    }

    private void refresh(int change) {
        boolean nowActive = !closed && LuminaAdShieldManager.isActiveForChat(account, dialogId)
                && UserConfig.getInstance(account).getClientUserId() == userId;
        boolean nowTrial = LuminaAdShieldManager.isTrial();
        boolean trialChanged = nowTrial != trial;
        trial = nowTrial;
        if (!nowActive) {
            if (active) {
                active = false;
                setObserving(false);
                cancelAdminRecheck();
                resetUi();
                notifyChanged(null);
            }
            return;
        }
        if (!active || change == LuminaAdShieldManager.CHANGE_CLEARED) {
            boolean was = active;
            active = true;
            activatedAt = SystemClock.elapsedRealtime();
            resetUi();
            setObserving(true);
            full = host.getChatFull();
            if (full == null) {
                full = MessagesController.getInstance(account).getChatFull(chatId);
            }
            ctx = null;
            ctxSignature = Long.MIN_VALUE;
            adminRecheck = 0;
            cancelAdminRecheck();
            if (isChannelChat() && !MessagesController.getInstance(account).isChannelAdminsLoaded(chatId)) {
                MessagesController.getInstance(account).loadChannelAdmins(chatId, true);
            }
            context();
            maybePreDecide();
            observe(host.getLoadedMessages(), true);
            scheduleAdminRecheck();
            final long activation = activatedAt;
            AndroidUtilities.runOnUIThread(() -> {
                if (activatedAt == activation) {
                    endOpening();
                }
            }, OPENING_GRACE_MS + 50);
            if (was) {
                notifyChanged(null);
            }
            return;
        }
        // still active: thresholds or exemptions may have changed
        republish();
        if (trialChanged) {
            version++;
            notifyChanged(null);
        }
    }

    private boolean isChannelChat() {
        return !LuminaAdShield.CHAT_GROUP.equals(chatType);
    }

    private void resetUi() {
        decisions = new ConcurrentHashMap<Integer, LuminaAdShield.Decision>();
        expanded.clear();
        expandedAlbums.clear();
        locked.clear();
        lockedAlbums.clear();
        grace.clear();
        shownInGrace.clear();
        notAdLocal.clear();
        allNewChecked = false;
        openingNewIds = null;
        jumpTarget = 0;
        preDecided = false;
        cancelLive();
        version++;
    }

    private void setObserving(boolean on) {
        if (on == observing) {
            return;
        }
        observing = on;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        int[] ids = {NotificationCenter.messagesDidLoad, NotificationCenter.didReceiveNewMessages,
                NotificationCenter.replaceMessagesObjects, NotificationCenter.messagesDeleted,
                NotificationCenter.chatInfoDidLoad, NotificationCenter.pinnedInfoDidLoad,
                NotificationCenter.didLoadPinnedMessages, NotificationCenter.didLoadChatAdmins};
        for (int id : ids) {
            if (on) {
                nc.addObserver(this, id);
            } else {
                nc.removeObserver(this, id);
            }
        }
        if (!on) {
            cancelLive();
        }
    }

    // =====================================================================================
    // Notifications (UI thread)
    // =====================================================================================

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (!active || closed || acc != account) {
            return;
        }
        try {
            if (id == NotificationCenter.messagesDidLoad) {
                if (args.length > 14 && args[10] instanceof Integer && (Integer) args[10] == host.getClassGuid()
                        && args[14] instanceof Integer && (Integer) args[14] == 0) {
                    observe((List<MessageObject>) args[2], true);
                }
            } else if (id == NotificationCenter.didReceiveNewMessages) {
                if ((Long) args[0] != dialogId || args.length > 2 && Boolean.TRUE.equals(args[2])) {
                    return;
                }
                if (args.length > 3 && args[3] instanceof Integer && (Integer) args[3] != 0) {
                    return;
                }
                observeLive((List<MessageObject>) args[1]);
            } else if (id == NotificationCenter.replaceMessagesObjects) {
                if ((Long) args[0] == dialogId) {
                    observeLive((List<MessageObject>) args[1]);
                }
            } else if (id == NotificationCenter.messagesDeleted) {
                if (args.length > 2 && Boolean.TRUE.equals(args[2])) {
                    return;     // scheduled messages
                }
                long channelId = (Long) args[1];
                boolean mine = isChannelChat() ? channelId == chatId : channelId == 0;
                if (mine) {
                    deleteIds((List<Integer>) args[0]);
                }
            } else if (id == NotificationCenter.chatInfoDidLoad) {
                TLRPC.ChatFull cf = (TLRPC.ChatFull) args[0];
                if (cf != null && cf.id == chatId) {
                    full = cf;
                    contextMaybeChanged();
                }
            } else if (id == NotificationCenter.pinnedInfoDidLoad) {
                if ((Long) args[0] == dialogId) {
                    // ChatActivity swaps in its new pinned map in its own handler: read it after every observer ran
                    AndroidUtilities.runOnUIThread(() -> {
                        if (active && !closed) {
                            contextMaybeChanged();
                        }
                    });
                }
            } else if (id == NotificationCenter.didLoadPinnedMessages) {
                if ((Long) args[0] == dialogId) {
                    onPinsChanged(args.length > 1 && args[1] instanceof List ? (List<Integer>) args[1] : null,
                            args.length > 2 && Boolean.TRUE.equals(args[2]));
                }
            } else if (id == NotificationCenter.didLoadChatAdmins) {
                if ((Long) args[0] == chatId) {
                    contextMaybeChanged();
                }
            }
        } catch (Throwable t) {
            LuminaAdShield.log("adshield notification skipped: " + t.getClass().getSimpleName());
        }
    }

    // =====================================================================================
    // Context
    // =====================================================================================

    private TLRPC.Chat chat() {
        TLRPC.Chat c = MessagesController.getInstance(account).getChat(chatId);
        return c;
    }

    /** Rebuilds the ChatContext when its inputs changed; returns true when roles / context changed. */
    private boolean updateContext() {
        TLRPC.Chat c = chat();
        if (c == null) {
            return false;
        }
        pinned = host.getPinnedMessages();     // ChatActivity replaces its pinned map: never keep an old view
        LuminaAdShieldMapper.Roles r = LuminaAdShieldMapper.roles(account, c, full);
        long sig = r.signature * 31 + LuminaAdShieldMapper.pinnedSignature(pinned);
        if (full != null) {
            sig = sig * 31 + full.linked_chat_id;
            sig = sig * 31 + (full.about != null ? full.about.hashCode() : 0);
        }
        String uname = org.telegram.messenger.ChatObject.getPublicUsername(c);
        sig = sig * 31 + (uname != null ? uname.hashCode() : 0);
        if (ctx != null && sig == ctxSignature) {
            return false;
        }
        roles = r;
        ctx = LuminaAdShieldMapper.context(account, c, chatType, full, pinned, r);
        boolean changed = ctxSignature != Long.MIN_VALUE;
        ctxSignature = sig;
        return changed;
    }

    private LuminaAdShield.ChatContext context() {
        if (ctx == null) {
            updateContext();
        }
        return ctx;
    }

    /**
     * Chat info / pinned / admins changed: re-observe the loaded messages with the new roles. Returns true
     * when the loaded messages were observed again.
     */
    private boolean contextMaybeChanged() {
        boolean changed = updateContext();
        if (changed) {
            observe(host.getLoadedMessages(), false);
        }
        if (roles != null && roles.known) {
            cancelAdminRecheck();
            maybePreDecide();
        }
        return changed;
    }

    /**
     * Pin / unpin in this chat (didLoadPinnedMessages: {@code ids} with {@code pin}, or ids == null with
     * pin == false for "unpin all"). A message pinned now is observed again with pinned=true so it is
     * recorded as endorsing (SPEC §6.3) and its copies do not fold.
     */
    private void onPinsChanged(List<Integer> ids, boolean pin) {
        final HashSet<Integer> changedIds = new HashSet<Integer>();
        if (ids != null) {
            for (Integer id : ids) {
                if (id != null && id > 0) {
                    pinOverride.put(id, pin);
                    changedIds.add(id);
                }
            }
        } else if (!pin) {
            pinOverride.clear();
            ownerPinnedStale = true;
        }
        final boolean all = ids == null && !pin;
        // ChatActivity updates its pinned map in its own handler: continue after every observer ran
        AndroidUtilities.runOnUIThread(() -> {
            if (!active || closed) {
                return;
            }
            if (contextMaybeChanged() || !all && changedIds.isEmpty()) {
                return;     // everything loaded was observed again already (or nothing to do)
            }
            List<MessageObject> loaded = host.getLoadedMessages();
            if (loaded == null) {
                return;
            }
            ArrayList<MessageObject> again = new ArrayList<MessageObject>();
            for (int i = 0, n = loaded.size(); i < n; i++) {
                MessageObject mo = loaded.get(i);
                if (mo != null && mo.messageOwner != null && (all ? mo.messageOwner.pinned : changedIds.contains(mo.getId()))) {
                    again.add(mo);
                }
            }
            observe(again, false);
        });
    }

    /** The message's pinned flag as of now (the loaded MessageObject is not updated on pin changes). */
    private boolean pinnedNow(int id, boolean ownerPinned, HashSet<Integer> pinnedIds) {
        Boolean o = pinOverride.get(id);
        if (o != null) {
            return o;
        }
        return pinnedIds.contains(id) || ownerPinned && !ownerPinnedStale;
    }

    /**
     * Once per activation, as soon as the admin list is known: decide the store's newest rows so the ads
     * it already knows bind as fold rows when the history arrives, instead of folding after being shown.
     */
    private void maybePreDecide() {
        if (preDecided || closed || !active || roles == null || !roles.known || ctx == null) {
            return;
        }
        preDecided = true;
        if (SystemClock.elapsedRealtime() - activatedAt > OPENING_GRACE_MS) {
            return;     // the opening is over: what is on screen stays, nothing to gain
        }
        final LuminaAdShield.ChatContext c = ctx;
        final ConcurrentHashMap<Integer, LuminaAdShield.Decision> target = decisions;
        LuminaAdShieldManager.post(() -> {
            LuminaAdShieldStore store = engineStore(target, true);
            if (store == null) {
                return;
            }
            int[] ids = newestStoredIds(store, PRE_DECIDE);
            if (ids.length > 0) {
                enginePublish(store, c, LuminaAdShieldManager.now(account), target, NO_BATCH, ids, false);
            }
        });
    }

    private void scheduleAdminRecheck() {
        cancelAdminRecheck();
        if (closed || !active || roles != null && roles.known) {
            return;
        }
        // No time-out: while the list is unknown decisions stay pending (nothing folds, SPEC §6.9). Network
        // refreshes of the admin list post no notification, so keep looking at a slow pace.
        long delay = adminRecheck < ADMIN_RECHECK_MS.length
                ? ADMIN_RECHECK_MS[adminRecheck] - (adminRecheck > 0 ? ADMIN_RECHECK_MS[adminRecheck - 1] : 0)
                : ADMIN_SLOW_RECHECK_MS;
        adminRecheck++;
        AndroidUtilities.runOnUIThread(adminRecheckRunnable = () -> {
            adminRecheckRunnable = null;
            if (closed || !active) {
                return;
            }
            contextMaybeChanged();
            scheduleAdminRecheck();
        }, Math.max(500, delay));
    }

    private void cancelAdminRecheck() {
        if (adminRecheckRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(adminRecheckRunnable);
            adminRecheckRunnable = null;
        }
    }

    // =====================================================================================
    // Observation (UI thread -> engine thread)
    // =====================================================================================

    /** New messages and edits: collected for {@link #LIVE_COALESCE_MS} and observed as one batch. */
    private void observeLive(List<MessageObject> list) {
        if (!active || closed || list == null || list.isEmpty()) {
            return;
        }
        for (int i = 0, n = list.size(); i < n; i++) {
            MessageObject mo = list.get(i);
            if (mo != null && mo.messageOwner != null && mo.getId() > 0 && mo.getDialogId() == dialogId) {
                pendingLive.remove(mo.getId());     // the newest object wins (an edit replaces it)
                pendingLive.put(mo.getId(), mo);
            }
        }
        if (liveFlush == null && !pendingLive.isEmpty()) {
            AndroidUtilities.runOnUIThread(liveFlush = () -> {
                liveFlush = null;
                if (pendingLive.isEmpty()) {
                    return;
                }
                ArrayList<MessageObject> batch = new ArrayList<MessageObject>(pendingLive.values());
                pendingLive.clear();
                observe(batch, false);
            }, LIVE_COALESCE_MS);
        }
    }

    private void cancelLive() {
        if (liveFlush != null) {
            AndroidUtilities.cancelRunOnUIThread(liveFlush);
            liveFlush = null;
        }
        pendingLive.clear();
    }

    private void observe(List<MessageObject> list, boolean history) {
        if (!active || closed || list == null || list.isEmpty()) {
            return;
        }
        // admin lists may also arrive silently (network refresh): re-check the roles on every batch
        boolean ctxChanged = updateContext();
        final LuminaAdShield.ChatContext c = ctx;
        TLRPC.Chat ch = chat();
        if (c == null || ch == null) {
            return;
        }
        HashSet<Integer> pinnedIds = new HashSet<Integer>();
        if (pinned != null) {
            for (MessageObject p : pinned) {
                if (p != null) {
                    pinnedIds.add(p.getId());
                }
            }
        }
        ArrayList<LuminaAdShieldMapper.Item> items = new ArrayList<LuminaAdShieldMapper.Item>();
        ArrayList<Integer> graceIds = new ArrayList<Integer>();
        ArrayList<Integer> newIds = new ArrayList<Integer>();
        HashSet<Integer> seen = new HashSet<Integer>();
        for (int i = 0; i < list.size(); i++) {
            MessageObject mo = list.get(i);
            if (mo == null || mo.messageOwner == null) {
                continue;
            }
            int id = mo.getId();
            if (id <= 0 || mo.getDialogId() != dialogId || !seen.add(id)) {
                continue;
            }
            // The keyword filter runs first and unchanged; AdShield only sees what it leaves.
            if (LuminaConfig.isMessageFiltered(mo)) {
                continue;
            }
            LuminaAdShieldMapper.Item it;
            try {
                it = LuminaAdShieldMapper.map(account, ch, full, roles, mo);
            } catch (Throwable t) {
                it = null;
            }
            if (it == null) {
                continue;
            }
            it.input.pinned = pinnedNow(id, it.input.pinned, pinnedIds);
            items.add(it);
            if (history || grace.contains(id)) {
                grace.add(id);
                graceIds.add(id);
            }
            if (history && it.incoming && openReadMax >= 0 && id > openReadMax) {
                newIds.add(id);
            }
        }
        if (items.isEmpty()) {
            if (ctxChanged) {
                observe(host.getLoadedMessages(), false);
            }
            return;
        }
        Collections.sort(items, new Comparator<LuminaAdShieldMapper.Item>() {
            @Override
            public int compare(LuminaAdShieldMapper.Item a, LuminaAdShieldMapper.Item b) {
                if (a.input.date != b.input.date) {
                    return a.input.date < b.input.date ? -1 : 1;
                }
                return Integer.compare(a.input.id, b.input.id);
            }
        });
        final Batch batch = new Batch();
        batch.grace = toArray(graceIds);
        batch.newIds = history ? toArray(newIds) : null;
        final ConcurrentHashMap<Integer, LuminaAdShield.Decision> target = decisions;
        queuedObserves.incrementAndGet();
        LuminaAdShieldManager.post(() -> engineObserve(items, c, target, batch));
        if (ctxChanged) {
            observe(host.getLoadedMessages(), false);
        }
    }

    private void deleteIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        final int[] del = new int[ids.size()];
        for (int i = 0; i < del.length; i++) {
            del[i] = ids.get(i);
            pendingLive.remove(del[i]);
        }
        final LuminaAdShield.ChatContext c = context();
        final ConcurrentHashMap<Integer, LuminaAdShield.Decision> target = decisions;
        LuminaAdShieldManager.post(() -> {
            LuminaAdShieldStore store = engineStore(target, false);
            if (store == null) {
                return;
            }
            long now = LuminaAdShieldManager.now(account);
            try {
                store.delete(del, now);
            } catch (LuminaAdShieldStore.ForgottenException e) {
                return;
            }
            for (int id : del) {
                tracked.remove(id);
                if (deferredIds != null) {
                    deferredIds.remove(id);
                }
            }
            LuminaAdShieldManager.scheduleFlush(LuminaAdShieldManager.engineAccount(account, userId, false), botChatId);
            enginePublish(store, c, now, target, NO_BATCH, del, false);
        });
    }

    /** Sensitivity / exemptions / chat mode changed: decide every loaded message again. */
    private void republish() {
        final LuminaAdShield.ChatContext c = context();
        final ConcurrentHashMap<Integer, LuminaAdShield.Decision> target = decisions;
        if (c == null) {
            return;
        }
        LuminaAdShieldManager.post(() -> {
            LuminaAdShieldStore store = engineStore(target, false);
            if (store != null) {
                enginePublish(store, c, LuminaAdShieldManager.now(account), target, NO_BATCH, EMPTY, true);
            }
        });
    }

    // =====================================================================================
    // Engine thread
    // =====================================================================================

    /** The chat store when the feature is still on here; null otherwise. Engine thread. */
    private LuminaAdShieldStore engineStore(Object target, boolean create) {
        if (closedFlag || !LuminaAdShieldManager.isActiveForChat(account, dialogId)) {
            return null;
        }
        LuminaAdShieldManager.AccountState st = LuminaAdShieldManager.engineAccount(account, userId, create);
        if (st == null) {
            return null;
        }
        int epoch = LuminaAdShieldManager.epochs[account];
        if (engineTarget != target || engineEpoch != epoch) {
            tracked.clear();
            clearDeferred();
            engineTarget = target;
            engineEpoch = epoch;
        }
        try {
            return st.account.chat(botChatId);
        } catch (LuminaAdShieldStore.ForgottenException e) {
            return null;
        }
    }

    private void engineObserve(List<LuminaAdShieldMapper.Item> items, LuminaAdShield.ChatContext c,
                               ConcurrentHashMap<Integer, LuminaAdShield.Decision> target, Batch b) {
        queuedObserves.decrementAndGet();
        LuminaAdShieldStore store = engineStore(target, true);
        if (store == null) {
            postPublished(target, null, null, takeDeferred(target, b));
            return;
        }
        LuminaAdShieldManager.AccountState st = LuminaAdShieldManager.engineAccount(account, userId, false);
        long now = LuminaAdShieldManager.now(account);
        long t0 = SystemClock.elapsedRealtime();
        int[] batch = new int[items.size()];
        int bi = 0;
        LuminaAdShieldImages img = null;
        for (LuminaAdShieldMapper.Item it : items) {
            LuminaAdShield.MessageInput in = it.input;
            if (it.stripped != null && in.media != null && in.media.id != null && in.sender != null) {
                long dh = LuminaAdShieldImages.dhash(it.stripped);
                if (dh != 0) {
                    if (img == null) {
                        img = LuminaAdShieldManager.engineImages(true);
                    }
                    if (img != null) {
                        long sk = LuminaAdShieldManager.engineSenderKey(in.sender.id);
                        long canon = img.canonical(st.dir, botChatId, sk, dh, it.bucket, it.photoId, in.date, now);
                        in.media.id = Long.toString(canon);
                    }
                }
            }
            try {
                store.observe(in, c, now);
            } catch (LuminaAdShieldStore.ForgottenException e) {
                postPublished(target, null, null, takeDeferred(target, b));
                return;
            }
            tracked.remove(in.id);
            tracked.add(in.id);
            batch[bi++] = in.id;
        }
        trimTracked();
        LuminaAdShieldManager.scheduleFlush(st, botChatId);
        long t1 = SystemClock.elapsedRealtime();
        if (queuedObserves.get() > 0 && (deferredBatch == null || t1 - deferredSince < MAX_DEFER_MS)) {
            // Another batch is already queued (a burst of new messages or history pages): it publishes
            // this one too, so a flood costs one decide pass instead of one per batch.
            defer(target, b, batch);
            LuminaAdShield.log("adshield batch " + items.size() + ": observe " + (t1 - t0) + " ms, publish deferred");
            return;
        }
        enginePublish(store, c, now, target, b, batch, false);
        LuminaAdShield.log("adshield batch " + items.size() + ": observe " + (t1 - t0) + " ms, decide "
                + (SystemClock.elapsedRealtime() - t1) + " ms, rows " + store.rowCount());
    }

    /** Keeps the newest {@link #MAX_DECIDE} observed ids (older ones keep their last published decision). */
    private void trimTracked() {
        int extra = tracked.size() - MAX_DECIDE;
        if (extra > 0) {
            Iterator<Integer> it = tracked.iterator();
            while (extra-- > 0 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private void defer(Object target, Batch b, int[] ids) {
        if (deferredBatch != null && deferredTarget != target) {
            clearDeferred();
        }
        if (deferredBatch == null) {
            deferredBatch = b;
            deferredIds = new LinkedHashSet<Integer>();
            deferredTarget = target;
            deferredSince = SystemClock.elapsedRealtime();
        } else {
            deferredBatch = Batch.merge(deferredBatch, b);
        }
        for (int id : ids) {
            deferredIds.add(id);
        }
    }

    /** {@code b} plus the deferred bookkeeping of the same target; clears what was deferred. */
    private Batch takeDeferred(Object target, Batch b) {
        Batch d = deferredBatch;
        Object dt = deferredTarget;
        clearDeferred();
        return d != null && dt == target ? Batch.merge(d, b) : b;
    }

    private void clearDeferred() {
        deferredBatch = null;
        deferredIds = null;
        deferredTarget = null;
        deferredSince = 0;
    }

    /**
     * Decides the batch, the deferred batches, the most recently observed messages and, with {@code all},
     * every message that has a published decision (settings changes and 「不是廣告」 must reach the whole
     * loaded history). Only computes: the UI thread applies the result. Engine thread.
     */
    private void enginePublish(LuminaAdShieldStore store, LuminaAdShield.ChatContext c, long now,
                               ConcurrentHashMap<Integer, LuminaAdShield.Decision> target, Batch b, int[] batch, boolean all) {
        LuminaAdShield.Settings s = LuminaAdShieldManager.settings();
        LinkedHashSet<Integer> ids = new LinkedHashSet<Integer>();
        if (deferredIds != null && deferredTarget == target) {
            ids.addAll(deferredIds);
        }
        b = takeDeferred(target, b);
        if (batch != null) {
            for (int id : batch) {
                ids.add(id);
            }
        }
        ids.addAll(tracked);
        if (all) {
            Map<Integer, LuminaAdShield.Decision> published = target;  // Map view: no KeySetView on old Android
            ids.addAll(published.keySet());
        }
        int[] outIds = new int[ids.size()];
        LuminaAdShield.Decision[] out = new LuminaAdShield.Decision[outIds.length];
        int k = 0;
        for (Integer id : ids) {
            LuminaAdShield.Decision d = store.decide(id, c, s, now);
            boolean keep = !LuminaAdShield.Decision.UNTRACKED.equals(d.state)
                    && !LuminaAdShield.Decision.INELIGIBLE.equals(d.state);
            outIds[k] = id;
            out[k] = keep ? d : null;
            k++;
        }
        postPublished(target, outIds, out, b);
    }

    /** The store's {@code max} newest live message ids, newest first. Engine thread (same package as the store). */
    private static int[] newestStoredIds(LuminaAdShieldStore store, int max) {
        int n = store.byMsg.size();
        if (n == 0 || max <= 0) {
            return EMPTY;
        }
        int[] all = new int[n];
        int k = 0;
        for (Integer id : store.byMsg.keySet()) {
            if (id != null && id > 0 && k < n) {
                all[k++] = id;
            }
        }
        java.util.Arrays.sort(all, 0, k);
        int m = Math.min(max, k);
        int[] out = new int[m];
        for (int i = 0; i < m; i++) {
            out[i] = all[k - 1 - i];
        }
        return out;
    }

    private static boolean sameForUi(LuminaAdShield.Decision a, LuminaAdShield.Decision b) {
        if (a == null || b == null) {
            return a == b || a == null && !b.fold || b == null && !a.fold;
        }
        return a.fold == b.fold && a.replyToMe == b.replyToMe && a.occurrence == b.occurrence;
    }

    private void postPublished(ConcurrentHashMap<Integer, LuminaAdShield.Decision> target, int[] ids,
                               LuminaAdShield.Decision[] ds, Batch b) {
        AndroidUtilities.runOnUIThread(() -> onPublished(target, ids, ds, b));
    }

    // =====================================================================================
    // Back on the UI thread
    // =====================================================================================

    /**
     * Applies one engine publish: first the decisions, then the display rules (what has been on screen
     * stays there, a jump target unfolds), and only then tells the chat UI. All of it runs in one UI-thread
     * task, so no frame ever draws a decision the rules have not seen.
     */
    private void onPublished(ConcurrentHashMap<Integer, LuminaAdShield.Decision> target, int[] ids,
                             LuminaAdShield.Decision[] ds, Batch b) {
        if (closed || target != decisions) {
            return;     // reset since: a stale result
        }
        ArrayList<Integer> changed = new ArrayList<Integer>();
        if (ids != null) {
            for (int i = 0; i < ids.length; i++) {
                LuminaAdShield.Decision d = ds[i];
                LuminaAdShield.Decision old = d != null ? target.put(ids[i], d) : target.remove(ids[i]);
                if (!sameForUi(old, d)) {
                    changed.add(ids[i]);
                }
            }
        }
        long t = SystemClock.elapsedRealtime();
        boolean opening = t - activatedAt < OPENING_GRACE_MS;
        settleShown(t);
        for (int id : b.grace) {
            if (!grace.contains(id)) {
                continue;
            }
            LuminaAdShield.Decision d = target.get(id);
            if (opening && d != null && LuminaAdShield.Decision.PENDING.equals(d.state)) {
                continue;       // admin list still loading: keep the allowance (endOpening() settles it)
            }
            grace.remove(id);
            Shown sh = shownInGrace.remove(id);
            if (sh != null && (d == null || !d.fold)) {
                lock(id, sh.album);     // on screen and not folding: it stays there from now on
            }
        }
        version++;
        if (!changed.isEmpty()) {
            checkJumpTarget();          // before the chat UI hears of it: a jump target never shows folded
            notifyChanged(toArray(changed));
        }
        if (b.newIds != null && b.newIds.length > 0 && openingNewIds == null) {
            openingNewIds = b.newIds;
        }
        if (openingNewIds != null && !allNewChecked) {
            checkAllNewFolded(opening);
        }
    }

    /**
     * A grace message that has been on screen for {@link #SHOWN_LOCK_MS} stays there for the rest of this
     * session, whatever its batch age or pending state. One that already folded within that time is
     * settled as folded (it must not pop back later).
     */
    private void settleShown(long t) {
        if (shownInGrace.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<Integer, Shown>> it = shownInGrace.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Shown> e = it.next();
            int id = e.getKey();
            Shown sh = e.getValue();
            if (t - sh.at >= SHOWN_LOCK_MS) {
                it.remove();
                grace.remove(id);
                lock(id, sh.album);
            } else {
                int st = getDisplayState(sh.mo);
                if (st == DISPLAY_FOLDED || st == DISPLAY_EXPANDED) {
                    it.remove();
                    grace.remove(id);
                }
            }
        }
    }

    private void lock(int id, long album) {
        locked.add(id);
        if (album != 0) {
            lockedAlbums.add(album);    // the whole album, never half of it
        }
    }

    /**
     * End of the opening window: messages still waiting for the admin list keep their place on screen; the
     * ones that were shown never fold in this session.
     */
    private void endOpening() {
        if (closed || !active) {
            return;
        }
        settleShown(SystemClock.elapsedRealtime());
        ArrayList<Integer> settle = new ArrayList<Integer>();
        for (Integer id : grace) {
            LuminaAdShield.Decision d = decisions.get(id);
            if (d != null && LuminaAdShield.Decision.PENDING.equals(d.state)) {
                settle.add(id);
            }
        }
        for (Integer id : settle) {
            grace.remove(id);
            Shown sh = shownInGrace.remove(id);
            if (sh != null) {
                lock(id, sh.album);
            }
        }
        if (openingNewIds != null && !allNewChecked) {
            checkAllNewFolded(false);
        }
    }

    /** "On opening, every new message folded" (once per chat per app session). */
    private void checkAllNewFolded(boolean opening) {
        for (int id : openingNewIds) {
            LuminaAdShield.Decision d = decisions.get(id);
            if (opening && d != null && LuminaAdShield.Decision.PENDING.equals(d.state)) {
                return;     // decide again once the admin list is known
            }
        }
        allNewChecked = true;
        if (trial) {
            return;
        }
        for (int id : openingNewIds) {
            if (getDisplayState(id) != DISPLAY_FOLDED) {
                return;
            }
        }
        String key = account + ":" + dialogId;
        if (allNewNotified.add(key) && listener != null) {
            listener.onAdShieldAllNewFolded();
        }
    }

    private void notifyChanged(int[] ids) {
        Listener l = listener;
        if (l != null && !closed) {
            l.onAdShieldChanged(ids);
        }
    }

    private static int[] concat(int[] a, int[] b) {
        if (a == null || a.length == 0) {
            return b == null ? EMPTY : b;
        }
        if (b == null || b.length == 0) {
            return a;
        }
        int[] r = new int[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static int[] toArray(List<Integer> l) {
        if (l == null || l.isEmpty()) {
            return EMPTY;
        }
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    /** Debug-safe description (counts only). */
    @Override
    public String toString() {
        return "AdShieldSession{active=" + active + ",trial=" + trial + ",decisions=" + decisions.size() + "}";
    }
}
