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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AdShield side of one open group chat (ChatActivity in MODE_DEFAULT, including forum topics and
 * comment threads; never private chats, secret chats, Saved Messages or broadcast channels).
 *
 * <p>Observation pipeline (only while the feature is effectively on for this chat): the session registers
 * its own NotificationCenter observers for history loads, new messages, edits, deletions, chat info,
 * pinned messages and admin lists. The UI thread copies plain fields into engine inputs (the keyword
 * filter runs first: what it hides is never observed) and posts them to the engine thread, which observes,
 * applies the similar-image hook, debounces the flush and decides the loaded messages with one {@code now}
 * per batch. The decisions land in a {@link ConcurrentHashMap} that the chat UI reads in O(1); changed ids
 * are reported to the {@link Listener} on the UI thread.
 *
 * <p>Display rules kept here so the chat UI only asks {@link #getDisplayState}: trial mode never folds;
 * "not an ad" unfolds at once; expanded segments are runtime-only; a message that was shown unfolded in
 * this session never folds afterwards (it folds the next time the chat is opened). Messages that arrive
 * with a history load are still allowed to fold when their first decision comes in (the engine answers a
 * few milliseconds after the load; pending decisions keep that allowance for the opening seconds while the
 * admin list loads).
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
    private static final long OPENING_GRACE_MS = 8000;
    /** A history batch whose decisions come back later than this no longer folds messages already shown. */
    private static final long FRESH_MS = 3000;
    private static final long[] ADMIN_RECHECK_MS = {2000, 5000, 12000, 30000};
    /** A jump target (reply quote, search, link) still unfolds its segment if its decision folds it this late. */
    private static final long JUMP_MS = 15000;
    private static final int[] EMPTY = new int[0];

    /** Bookkeeping that travels with one engine batch and comes back to the UI thread. */
    private static final class Batch {
        int[] grace = EMPTY;        // ids allowed to fold although they may already be bound
        int[] newIds;               // unread incoming ids of a history batch (all-new-folded check)
        long postedAt;              // SystemClock.elapsedRealtime() when the UI posted it
    }

    private static final Batch NO_BATCH = new Batch();
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
    private final HashSet<Integer> shownInGrace = new HashSet<Integer>();
    private final java.util.HashMap<Integer, Long> shownInGraceAlbum = new java.util.HashMap<Integer, Long>();
    private final HashSet<Integer> notAdLocal = new HashSet<Integer>();
    private LuminaAdShield.ChatContext ctx;
    private LuminaAdShieldMapper.Roles roles;
    private TLRPC.ChatFull full;
    private Collection<MessageObject> pinned;
    private long ctxSignature = Long.MIN_VALUE;
    private boolean adminsForced;
    private int adminRecheck;
    private Runnable adminRecheckRunnable;
    private final int openReadMax;
    private boolean allNewChecked;
    private int[] openingNewIds;
    private int jumpTarget;
    private long jumpTargetAt;

    // ---- engine thread ----
    private final LinkedHashSet<Integer> tracked = new LinkedHashSet<Integer>();
    private Object engineTarget;
    private int engineEpoch = -1;

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
     * decision is still outstanding.
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
            shownInGrace.add(id);
            if (mo.messageOwner.grouped_id != 0) {
                shownInGraceAlbum.put(id, mo.messageOwner.grouped_id);
            }
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
            enginePublish(store, c, now, target, NO_BATCH, EMPTY);
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
            pinned = host.getPinnedMessages();
            ctx = null;
            ctxSignature = Long.MIN_VALUE;
            adminsForced = false;
            adminRecheck = 0;
            cancelAdminRecheck();
            if (isChannelChat() && !MessagesController.getInstance(account).isChannelAdminsLoaded(chatId)) {
                MessagesController.getInstance(account).loadChannelAdmins(chatId, true);
            }
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
        shownInGraceAlbum.clear();
        notAdLocal.clear();
        allNewChecked = false;
        openingNewIds = null;
        jumpTarget = 0;
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
                NotificationCenter.didLoadChatAdmins};
        for (int id : ids) {
            if (on) {
                nc.addObserver(this, id);
            } else {
                nc.removeObserver(this, id);
            }
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
                observe((List<MessageObject>) args[1], false);
            } else if (id == NotificationCenter.replaceMessagesObjects) {
                if ((Long) args[0] == dialogId) {
                    observe((List<MessageObject>) args[1], false);
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
                if ((Long) args[0] == dialogId && args.length > 2 && args[2] instanceof java.util.Map) {
                    pinned = new ArrayList<MessageObject>(((java.util.Map<Integer, MessageObject>) args[2]).values());
                    contextMaybeChanged();
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
        LuminaAdShieldMapper.Roles r = LuminaAdShieldMapper.roles(account, c, full, adminsForced);
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

    /** Chat info / pinned / admins changed: re-observe the loaded messages with the new roles. */
    private void contextMaybeChanged() {
        if (updateContext()) {
            observe(host.getLoadedMessages(), false);
        }
        if (roles != null && roles.known) {
            cancelAdminRecheck();
        }
    }

    private void scheduleAdminRecheck() {
        cancelAdminRecheck();
        if (closed || !active || adminsForced || roles != null && roles.known) {
            return;
        }
        if (adminRecheck >= ADMIN_RECHECK_MS.length) {
            // an admin-less group (or a list that never loads): stop waiting
            adminsForced = true;
            contextMaybeChanged();
            return;
        }
        long delay = ADMIN_RECHECK_MS[adminRecheck] - (adminRecheck > 0 ? ADMIN_RECHECK_MS[adminRecheck - 1] : 0);
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
        batch.postedAt = SystemClock.elapsedRealtime();
        final ConcurrentHashMap<Integer, LuminaAdShield.Decision> target = decisions;
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
            }
            LuminaAdShieldManager.scheduleFlush(LuminaAdShieldManager.engineAccount(account, userId, false), botChatId);
            enginePublish(store, c, now, target, NO_BATCH, del);
        });
    }

    /** Sensitivity / exemptions changed: decide the tracked messages again. */
    private void republish() {
        final LuminaAdShield.ChatContext c = context();
        final ConcurrentHashMap<Integer, LuminaAdShield.Decision> target = decisions;
        if (c == null) {
            return;
        }
        LuminaAdShieldManager.post(() -> {
            LuminaAdShieldStore store = engineStore(target, false);
            if (store != null) {
                enginePublish(store, c, LuminaAdShieldManager.now(account), target, NO_BATCH, EMPTY);
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
        LuminaAdShieldStore store = engineStore(target, true);
        if (store == null) {
            postPublished(target, EMPTY, b);
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
                postPublished(target, EMPTY, b);
                return;
            }
            tracked.remove(in.id);
            tracked.add(in.id);
            batch[bi++] = in.id;
        }
        LuminaAdShieldManager.scheduleFlush(st, botChatId);
        long t1 = SystemClock.elapsedRealtime();
        enginePublish(store, c, now, target, b, batch);
        LuminaAdShield.log("adshield batch " + items.size() + ": observe " + (t1 - t0) + " ms, decide "
                + (SystemClock.elapsedRealtime() - t1) + " ms, rows " + store.rowCount());
    }

    /** Decides the batch plus the most recently observed messages and publishes changes. Engine thread. */
    private void enginePublish(LuminaAdShieldStore store, LuminaAdShield.ChatContext c, long now,
                               ConcurrentHashMap<Integer, LuminaAdShield.Decision> target, Batch b, int[] batch) {
        LuminaAdShield.Settings s = LuminaAdShieldManager.settings();
        LinkedHashSet<Integer> ids = new LinkedHashSet<Integer>();
        if (batch != null) {
            for (int id : batch) {
                ids.add(id);
            }
        }
        int skip = Math.max(0, tracked.size() - MAX_DECIDE);
        int i = 0;
        for (Integer id : tracked) {
            if (i++ >= skip) {
                ids.add(id);
            }
        }
        ArrayList<Integer> changed = new ArrayList<Integer>();
        for (Integer id : ids) {
            LuminaAdShield.Decision d = store.decide(id, c, s, now);
            boolean keep = !LuminaAdShield.Decision.UNTRACKED.equals(d.state)
                    && !LuminaAdShield.Decision.INELIGIBLE.equals(d.state);
            LuminaAdShield.Decision old = keep ? target.put(id, d) : target.remove(id);
            if (!sameForUi(old, keep ? d : null)) {
                changed.add(id);
            }
        }
        postPublished(target, toArray(changed), b);
    }

    private static boolean sameForUi(LuminaAdShield.Decision a, LuminaAdShield.Decision b) {
        if (a == null || b == null) {
            return a == b || a == null && !b.fold || b == null && !a.fold;
        }
        return a.fold == b.fold && a.replyToMe == b.replyToMe && a.occurrence == b.occurrence;
    }

    private void postPublished(ConcurrentHashMap<Integer, LuminaAdShield.Decision> target, int[] changed, Batch b) {
        AndroidUtilities.runOnUIThread(() -> onPublished(target, changed, b));
    }

    // =====================================================================================
    // Back on the UI thread
    // =====================================================================================

    private void onPublished(ConcurrentHashMap<Integer, LuminaAdShield.Decision> target, int[] changed, Batch b) {
        if (closed || target != decisions) {
            return;     // reset since: a stale result
        }
        long t = SystemClock.elapsedRealtime();
        boolean opening = t - activatedAt < OPENING_GRACE_MS;
        boolean fresh = t - b.postedAt < FRESH_MS;
        for (int id : b.grace) {
            LuminaAdShield.Decision d = target.get(id);
            if (opening && d != null && LuminaAdShield.Decision.PENDING.equals(d.state)) {
                continue;       // admin list still loading: keep the allowance (endOpening() settles it)
            }
            grace.remove(id);
            Long album = shownInGraceAlbum.remove(id);
            if (shownInGrace.remove(id) && (!fresh || d == null || !d.fold)) {
                locked.add(id); // it was on screen and stays there from now on
                if (album != null) {
                    lockedAlbums.add(album);    // the whole album, never half of it
                }
            }
        }
        version++;
        if (changed != null && changed.length > 0) {
            notifyChanged(changed);
            checkJumpTarget();
        }
        if (b.newIds != null && b.newIds.length > 0 && openingNewIds == null) {
            openingNewIds = b.newIds;
        }
        if (openingNewIds != null && !allNewChecked) {
            checkAllNewFolded(opening);
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
        ArrayList<Integer> settle = new ArrayList<Integer>();
        for (Integer id : grace) {
            LuminaAdShield.Decision d = decisions.get(id);
            if (d != null && LuminaAdShield.Decision.PENDING.equals(d.state)) {
                settle.add(id);
            }
        }
        for (Integer id : settle) {
            grace.remove(id);
            Long album = shownInGraceAlbum.remove(id);
            if (shownInGrace.remove(id)) {
                locked.add(id);
                if (album != null) {
                    lockedAlbums.add(album);
                }
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
