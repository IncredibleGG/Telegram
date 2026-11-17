/*
 * LuminaGram - 「隱藏重複廣告」 chat UI: fold rows, hidden members, the slim header of an expanded segment,
 * the trial label, taps and the fold-row menu.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.lumina.LuminaAdShieldChatSession;
import org.telegram.messenger.lumina.LuminaAdShieldManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.ItemOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * The in-chat part of 「隱藏重複廣告」 for one ChatActivity (main message list only, MODE_DEFAULT).
 *
 * <p>Nothing here adds, removes or reorders entries of ChatActivity.messages. A folded message keeps its
 * adapter position and only its view type changes: the top-most member of a fold segment is rendered as
 * {@link FoldCell} (one service-style row), every other member as a zero-height {@link HiddenCell}. Album
 * members fold together, so a folded album is never laid out as a grid. An expanded segment gets a slim
 * header ({@code 重複訊息（疑似廣告）· 收合 · 不是廣告}) and trial mode a small 「疑似重複廣告」 label: both live in
 * space an {@link RecyclerView.ItemDecoration} reserves above the message, so ChatMessageCell is untouched.
 *
 * <p>Display states come from {@link LuminaAdShieldChatSession#getDisplayState(MessageObject)} (O(1)); while the
 * feature is off for the chat every method here is a no-op and the decoration is not even installed.
 * UI thread only.
 */
public final class LuminaAdShieldChatUi implements LuminaAdShieldChatSession.Listener {

    /** Adapter view types ("LG" prefix, far away from the stock contentType values). */
    public static final int VIEW_TYPE_FOLD = 0x4C470001;
    public static final int VIEW_TYPE_HIDDEN = 0x4C470002;

    /** What the UI needs from ChatActivity. */
    public interface Host {
        LuminaAdShieldChatSession session();

        ChatActivity.ChatActivityAdapter adapter();

        RecyclerView listView();

        /** ChatActivity.reversed: true when messages[0] is at the top of the screen. */
        boolean isReversed();

        Theme.ResourcesProvider resourcesProvider();

        BaseFragment fragment();

        /** Top of {@code child} in chat-background coordinates (what ChatActionCell.setVisiblePart gets). */
        float serviceTop(View child);

        int serviceHeight(View child);

        /** Shows 「這次的新訊息都是重複廣告，已收合」. False = not possible yet (opening animation), try again. */
        boolean showAllNewFoldedHint();
    }

    private static final int NORMAL = LuminaAdShieldChatSession.DISPLAY_NORMAL;
    private static final int FOLDED = LuminaAdShieldChatSession.DISPLAY_FOLDED;
    private static final int EXPANDED = LuminaAdShieldChatSession.DISPLAY_EXPANDED;
    private static final int TRIAL = LuminaAdShieldChatSession.DISPLAY_TRIAL_LABEL;

    private static final int DECOR_NONE = 0;
    private static final int DECOR_HEADER = 1;
    private static final int DECOR_LABEL = 2;
    private static final int HEADER_DP = 36;
    private static final int LABEL_DP = 22;

    private static final int ACTION_COLLAPSE = 1;
    private static final int ACTION_NOT_AD = 2;

    private static final int HINT_RETRIES = 20;
    private static final long HINT_RETRY_MS = 300;
    private static final long VERIFY_DELAY_MS = 32;
    private static final long VERIFY_RETRY_MS = 50;
    private static final int VERIFY_RETRIES = 30;

    private final Host host;
    private RecyclerView attachedTo;
    private final Decoration decoration = new Decoration();
    private final Touch touch = new Touch();

    private final ArrayList<Hit> hits = new ArrayList<Hit>();
    private int hitCount;

    private final TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headerActionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int hintRetries;

    private RecyclerView.Adapter<?> observedAdapter;
    private boolean verifyScheduled;
    private int verifyRetries;
    private final Runnable verifyRunnable = this::verify;
    /** Any change of the message list can move a segment's top row or change its count: re-check the rows. */
    private final RecyclerView.AdapterDataObserver dataObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            scheduleVerify();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            scheduleVerify();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            scheduleVerify();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            scheduleVerify();
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            scheduleVerify();
        }
    };

    public LuminaAdShieldChatUi(Host host) {
        this.host = host;
        headerActionPaint.setTypeface(AndroidUtilities.bold());
    }

    // =====================================================================================
    // Display state helpers
    // =====================================================================================

    /** The session while the feature is on for this chat, else null. */
    private LuminaAdShieldChatSession active() {
        LuminaAdShieldChatSession s = host.session();
        return s != null && s.isActive() ? s : null;
    }

    /** Index step towards the top of the screen. */
    private int up() {
        return host.isReversed() ? -1 : 1;
    }

    private static int stateAt(LuminaAdShieldChatSession s, List<MessageObject> l, int i) {
        if (l == null || i < 0 || i >= l.size()) {
            return NORMAL;
        }
        return s.getDisplayState(l.get(i));
    }

    /**
     * View type of messages[index] in the main list: {@link #VIEW_TYPE_FOLD} for the top-most member of a fold
     * segment, {@link #VIEW_TYPE_HIDDEN} for the other members, 0 for everything else (stock view type).
     */
    public int viewType(List<MessageObject> messages, int index) {
        LuminaAdShieldChatSession s = active();
        if (s == null || stateAt(s, messages, index) != FOLDED) {
            return 0;
        }
        return stateAt(s, messages, index + up()) == FOLDED ? VIEW_TYPE_HIDDEN : VIEW_TYPE_FOLD;
    }

    /** {@link #viewType} by adapter position (0 outside the message rows and in search-filter mode). */
    public int viewTypeAt(int adapterPosition) {
        ChatActivity.ChatActivityAdapter a = host.adapter();
        if (a == null || a.isFiltered) {
            return 0;
        }
        return viewType(a.getMessages(), adapterPosition - a.messagesStartRow);
    }

    public static boolean isOwnRow(View v) {
        return v instanceof FoldCell || v instanceof HiddenCell;
    }

    public static View createRow(Context context, int viewType, Theme.ResourcesProvider resourcesProvider) {
        if (viewType == VIEW_TYPE_FOLD) {
            return new FoldCell(context, resourcesProvider);
        }
        return new HiddenCell(context);
    }

    /** Binds a fold row or a hidden member; false when {@code view} is a stock cell. */
    public boolean bindRow(View view, List<MessageObject> messages, int index) {
        if (view instanceof HiddenCell) {
            ((HiddenCell) view).ui = this;
            return true;
        }
        if (!(view instanceof FoldCell)) {
            return false;
        }
        FoldCell cell = (FoldCell) view;
        cell.ui = this;
        cell.members.clear();
        MessageObject head = messages != null && index >= 0 && index < messages.size() ? messages.get(index) : null;
        String text = null;
        LuminaAdShieldChatSession s = active();
        if (s != null && head != null) {
            int[] b = s.segmentBounds(messages, index, false);
            if (b != null) {
                text = foldText(s.segmentInfo(messages, b[0], b[1]));
                for (int i = b[0]; i <= b[1]; i++) {
                    cell.members.add(messages.get(i));
                }
            }
        }
        if (text == null) {
            text = LuminaLocale.getString(R.string.LuminaAdShieldFoldOnePlain);
        }
        cell.set(head, text, LuminaLocale.getString(R.string.LuminaAdShieldFoldA11yHint));
        return true;
    }

    private static String foldText(LuminaAdShieldChatSession.SegmentInfo info) {
        if (info.replyToMe) {
            return info.count <= 1
                    ? LuminaLocale.getString(R.string.LuminaAdShieldFoldRepliesOne)
                    : String.format(LuminaLocale.getString(R.string.LuminaAdShieldFoldReplies), info.count);
        }
        if (info.count <= 1) {
            return info.occurrence > 0
                    ? String.format(LuminaLocale.getString(R.string.LuminaAdShieldFoldOne), info.occurrence)
                    : LuminaLocale.getString(R.string.LuminaAdShieldFoldOnePlain);
        }
        return String.format(LuminaLocale.getString(R.string.LuminaAdShieldFoldMany), info.count);
    }

    /**
     * A message was bound as a normal ChatMessageCell in the main list. Normal and trial-labelled messages
     * are then "on screen" and never fold in this session (they fold on the next opening).
     */
    public void onMessageBound(MessageObject mo) {
        LuminaAdShieldChatSession s = active();
        if (s == null || mo == null) {
            return;
        }
        int st = s.getDisplayState(mo);
        if (st == NORMAL || st == TRIAL) {
            s.noteShown(mo);
        }
    }

    // =====================================================================================
    // Session listener: partial refreshes
    // =====================================================================================

    @Override
    public void onAdShieldChanged(int[] msgIds) {
        LuminaAdShieldChatSession s = host.session();
        ChatActivity.ChatActivityAdapter a = host.adapter();
        RecyclerView list = host.listView();
        if (s == null || a == null || list == null) {
            return;
        }
        if (list.isComputingLayout()) {
            AndroidUtilities.runOnUIThread(() -> onAdShieldChanged(msgIds));
            return;
        }
        attachIfActive();
        if (a.isFiltered) {
            return;     // the main list is not shown; it is rebound when the filter goes away
        }
        if (msgIds == null || a.animationRunning) {
            a.notifyDataSetChanged(false);  // deferred by the adapter while a scroll animation runs
            return;
        }
        List<MessageObject> l = a.getMessages();
        if (msgIds.length == 0 || l == null || l.isEmpty()) {
            return;
        }
        HashSet<Integer> want = new HashSet<Integer>();
        for (int id : msgIds) {
            want.add(id);
        }
        // A changed message also changes its neighbours' roles (segment head, header, bubble grouping):
        // refresh the whole non-normal run around it plus one row on each side.
        int lo = Integer.MAX_VALUE;
        int hi = -1;
        for (int i = 0; i < l.size(); i++) {
            MessageObject mo = l.get(i);
            if (mo == null || mo.getDialogId() != s.dialogId || !want.contains(mo.getId())) {
                continue;
            }
            int f = i;
            int t = i;
            while (f - 1 >= 0 && s.getDisplayState(l.get(f - 1)) != NORMAL) {
                f--;
            }
            while (t + 1 < l.size() && s.getDisplayState(l.get(t + 1)) != NORMAL) {
                t++;
            }
            lo = Math.min(lo, f - 1);
            hi = Math.max(hi, t + 1);
            i = t;
        }
        lo = Math.max(0, lo);
        hi = Math.min(l.size() - 1, hi);
        if (hi < lo) {
            return;
        }
        a.notifyItemRangeChanged(a.messagesStartRow + lo, hi - lo + 1);
        if (attachedTo == list) {
            list.invalidateItemDecorations();
        }
    }

    @Override
    public void onAdShieldAllNewFolded() {
        hintRetries = 0;
        tryHint();
    }

    private void tryHint() {
        if (host.showAllNewFoldedHint()) {
            return;
        }
        if (++hintRetries <= HINT_RETRIES) {
            AndroidUtilities.runOnUIThread(this::tryHint, HINT_RETRY_MS);
        }
    }

    /**
     * Installs the header / label decoration, its touch handling and the list observer once the feature is on
     * here (never while it is off: the chat list then runs exactly as before).
     */
    public void attachIfActive() {
        RecyclerView list = host.listView();
        if (list == null || active() == null) {
            return;
        }
        if (list != attachedTo) {
            if (list.isComputingLayout()) {
                AndroidUtilities.runOnUIThread(this::attachIfActive);
                return;
            }
            if (attachedTo != null) {
                attachedTo.removeItemDecoration(decoration);
                attachedTo.removeOnItemTouchListener(touch);
            }
            attachedTo = list;
            list.addItemDecoration(decoration);
            list.addOnItemTouchListener(touch);
        }
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (adapter != observedAdapter) {
            if (observedAdapter != null) {
                observedAdapter.unregisterAdapterDataObserver(dataObserver);
            }
            observedAdapter = adapter;
            if (adapter != null) {
                adapter.registerAdapterDataObserver(dataObserver);
            }
        }
    }

    // =====================================================================================
    // Self-check of the visible rows
    // =====================================================================================

    /**
     * ChatActivity inserts and removes rows (history loads, deletions, date separators) without knowing about
     * fold segments, so a segment's top row can move or its count change while no AdShield decision changed.
     * After every list change (and whenever a fold row is re-attached) the visible rows are compared with
     * what they should be and the stale ones refreshed.
     */
    void scheduleVerify() {
        if (verifyScheduled || active() == null) {
            return;
        }
        verifyScheduled = true;
        verifyRetries = 0;
        AndroidUtilities.runOnUIThread(verifyRunnable, VERIFY_DELAY_MS);
    }

    private void verify() {
        verifyScheduled = false;
        LuminaAdShieldChatSession s = active();
        ChatActivity.ChatActivityAdapter a = host.adapter();
        RecyclerView list = host.listView();
        if (s == null || a == null || list == null || list != attachedTo || list.getAdapter() != a || a.isFiltered) {
            return;
        }
        RecyclerView.ItemAnimator animator = list.getItemAnimator();
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        if (lm == null || list.hasPendingAdapterUpdates() || list.isComputingLayout() || list.isLayoutRequested()
                || a.animationRunning || animator != null && animator.isRunning()) {
            if (++verifyRetries <= VERIFY_RETRIES) {
                verifyScheduled = true;
                AndroidUtilities.runOnUIThread(verifyRunnable, VERIFY_RETRY_MS);
            }
            return;
        }
        List<MessageObject> l = a.getMessages();
        int lo = Integer.MAX_VALUE;
        int hi = -1;
        boolean decorStale = false;
        // the layout manager's children: laid-out rows only, not views still animating out
        for (int c = 0, n = lm.getChildCount(); c < n; c++) {
            View child = lm.getChildAt(c);
            if (child == null) {
                continue;
            }
            RecyclerView.ViewHolder holder = list.getChildViewHolder(child);
            int pos = holder != null ? holder.getAdapterPosition() : RecyclerView.NO_POSITION;
            int i = pos - a.messagesStartRow;
            if (pos == RecyclerView.NO_POSITION || l == null || i < 0 || i >= l.size()) {
                continue;
            }
            int expected = viewType(l, i);
            int actual = holder.getItemViewType();
            boolean stale = expected != 0 ? actual != expected : actual == VIEW_TYPE_FOLD || actual == VIEW_TYPE_HIDDEN;
            if (stale) {
                lo = Math.min(lo, i);
                hi = Math.max(hi, i);
                continue;
            }
            if (child instanceof FoldCell) {
                FoldCell cell = (FoldCell) child;
                int[] b = s.segmentBounds(l, i, false);
                if (b != null && (cell.head != l.get(i) || cell.members.size() != b[1] - b[0] + 1
                        || !TextUtils.equals(cell.text, foldText(s.segmentInfo(l, b[0], b[1]))))) {
                    bindRow(cell, l, i);
                }
            } else if (child instanceof ChatMessageCell) {
                int kind = decorKind(child, list);
                int want = kind == DECOR_HEADER ? dp(HEADER_DP) : kind == DECOR_LABEL ? dp(LABEL_DP) : 0;
                if (lm.getTopDecorationHeight(child) != want) {
                    decorStale = true;
                }
            }
        }
        if (hi >= lo) {
            lo = Math.max(0, lo - 1);
            hi = Math.min(l.size() - 1, hi + 1);
            a.notifyItemRangeChanged(a.messagesStartRow + lo, hi - lo + 1);
        }
        if (decorStale || hi >= lo) {
            list.invalidateItemDecorations();
        }
    }

    // =====================================================================================
    // Fold row actions
    // =====================================================================================

    private int indexOf(List<MessageObject> l, MessageObject mo, LuminaAdShieldChatSession s) {
        if (l == null || mo == null) {
            return -1;
        }
        int i = l.indexOf(mo);
        if (i >= 0) {
            return i;
        }
        return indexOf(l, mo.getId(), s);
    }

    private static int indexOf(List<MessageObject> l, int msgId, LuminaAdShieldChatSession s) {
        if (l == null || msgId <= 0) {
            return -1;
        }
        for (int i = 0; i < l.size(); i++) {
            MessageObject o = l.get(i);
            if (o != null && o.getId() == msgId && o.getDialogId() == s.dialogId) {
                return i;
            }
        }
        return -1;
    }

    /** Ids of the fold segment whose fold row shows {@code head}; null when it is not folded any more. */
    private int[] foldSegment(MessageObject head) {
        LuminaAdShieldChatSession s = active();
        ChatActivity.ChatActivityAdapter a = host.adapter();
        if (s == null || a == null || a.isFiltered || head == null) {
            return null;
        }
        List<MessageObject> l = a.getMessages();
        int i = indexOf(l, head, s);
        int[] b = i >= 0 ? s.segmentBounds(l, i, false) : null;
        return b != null ? s.segmentInfo(l, b[0], b[1]).msgIds : null;
    }

    /** Tap on a fold row (also TalkBack's click): show the whole segment. */
    public void onFoldTap(FoldCell cell) {
        showSegment(cell != null ? cell.head : null);
    }

    private void showSegment(MessageObject head) {
        LuminaAdShieldChatSession s = active();
        int[] ids = foldSegment(head);
        if (s != null && ids != null) {
            s.expand(ids);
        }
    }

    /** Long-press / right-click on a fold row: 「顯示」「不是廣告」「在此群關閉」. */
    public boolean onFoldLongPress(FoldCell cell) {
        BaseFragment fragment = host.fragment();
        if (cell == null || cell.head == null || fragment == null || fragment.getParentActivity() == null || active() == null) {
            return false;
        }
        final MessageObject head = cell.head;
        ItemOptions.makeOptions(fragment, cell)
                .add(R.drawable.msg_archive_show, LuminaLocale.getString(R.string.LuminaAdShieldShow), () -> showSegment(head))
                .add(R.drawable.msg_select, LuminaLocale.getString(R.string.LuminaAdShieldNotAd), () -> {
                    LuminaAdShieldChatSession s = active();
                    int[] ids = foldSegment(head);
                    if (s != null && ids != null) {
                        s.markNotAd(head.getId(), ids);
                    }
                })
                .add(R.drawable.msg_cancel, LuminaLocale.getString(R.string.LuminaAdShieldTurnOffHere), () -> {
                    LuminaAdShieldChatSession s = host.session();
                    if (s != null) {
                        s.setChatMode(LuminaAdShieldManager.MODE_OFF);
                    }
                })
                .show();
        return true;
    }

    /** 「收合」 / 「不是廣告」 in the slim header of the expanded run that contains {@code msgId}. */
    private void performHeader(int action, int msgId) {
        LuminaAdShieldChatSession s = active();
        ChatActivity.ChatActivityAdapter a = host.adapter();
        if (s == null || a == null || a.isFiltered) {
            return;
        }
        List<MessageObject> l = a.getMessages();
        int i = indexOf(l, msgId, s);
        if (i < 0 || stateAt(s, l, i) != EXPANDED) {
            return;
        }
        int f = i;
        int t = i;
        while (stateAt(s, l, f - 1) == EXPANDED) {
            f--;
        }
        while (stateAt(s, l, t + 1) == EXPANDED) {
            t++;
        }
        int[] ids = new int[t - f + 1];
        for (int k = f; k <= t; k++) {
            ids[k - f] = l.get(k).getId();
        }
        if (action == ACTION_COLLAPSE) {
            s.collapse(ids);
        } else {
            s.markNotAd(msgId, ids);
        }
    }

    // =====================================================================================
    // Header and trial label (decoration space)
    // =====================================================================================

    private int decorKind(View child, RecyclerView parent) {
        if (!(child instanceof ChatMessageCell)) {
            return DECOR_NONE;
        }
        LuminaAdShieldChatSession s = active();
        ChatActivity.ChatActivityAdapter a = host.adapter();
        if (s == null || a == null || a.isFiltered) {
            return DECOR_NONE;
        }
        int pos = parent.getChildAdapterPosition(child);
        List<MessageObject> l = a.getMessages();
        int i = pos - a.messagesStartRow;
        if (pos == RecyclerView.NO_POSITION || l == null || i < 0 || i >= l.size()) {
            return DECOR_NONE;
        }
        MessageObject mo = l.get(i);
        ChatMessageCell cell = (ChatMessageCell) child;
        MessageObject bound = cell.getMessageObject();
        if (mo == null || bound == null || bound.getId() != mo.getId() || !hasFlag(cell, MessageObject.POSITION_FLAG_TOP)) {
            return DECOR_NONE;
        }
        int st = s.getDisplayState(mo);
        if (st == TRIAL) {
            return DECOR_LABEL;
        }
        if (st == EXPANDED && topOfRun(s, l, i)) {
            return DECOR_HEADER;
        }
        return DECOR_NONE;
    }

    /** messages[i] belongs to the top-most unit of its expanded run (an album counts as one unit). */
    private boolean topOfRun(LuminaAdShieldChatSession s, List<MessageObject> l, int i) {
        int up = up();
        int j = i;
        MessageObject mo = l.get(i);
        long album = mo.messageOwner != null ? mo.messageOwner.grouped_id : 0;
        if (album != 0) {
            while (j + up >= 0 && j + up < l.size()) {
                MessageObject o = l.get(j + up);
                if (o == null || o.messageOwner == null || o.messageOwner.grouped_id != album) {
                    break;
                }
                j += up;
            }
        }
        return stateAt(s, l, j + up) != EXPANDED;
    }

    /** Album cells: the given position flag; a single message has every flag. */
    private static boolean hasFlag(ChatMessageCell cell, int flag) {
        MessageObject.GroupedMessagePosition p = cell.getCurrentMessagesGroup() != null ? cell.getCurrentPosition() : null;
        return p == null || (p.flags & flag) != 0;
    }

    private final class Decoration extends RecyclerView.ItemDecoration {
        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int kind = decorKind(view, parent);
            if (kind == DECOR_HEADER) {
                outRect.top = dp(HEADER_DP);
            } else if (kind == DECOR_LABEL) {
                outRect.top = dp(LABEL_DP);
            }
        }
    }

    /**
     * Draws the slim headers and trial labels into the space the decoration reserved. Called from the chat
     * list's background pass ({@code mainPass} = the on-screen pass, not the blur copy).
     */
    public void draw(Canvas canvas, RecyclerView list, boolean mainPass) {
        if (mainPass) {
            hitCount = 0;
        }
        if (list == null || attachedTo != list || active() == null) {
            return;
        }
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        for (int c = 0, n = list.getChildCount(); c < n; c++) {
            View child = list.getChildAt(c);
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) {
                continue;
            }
            int kind = decorKind(child, list);
            if (kind == DECOR_NONE || !hasFlag((ChatMessageCell) child, MessageObject.POSITION_FLAG_LEFT)) {
                continue;
            }
            int need = dp(kind == DECOR_HEADER ? HEADER_DP : LABEL_DP);
            if (lm == null || lm.getTopDecorationHeight(child) < need) {
                continue;   // insets not laid out yet (state changed this frame)
            }
            if (kind == DECOR_HEADER) {
                drawHeader(canvas, list, (ChatMessageCell) child, mainPass);
            } else {
                drawLabel(canvas, list, (ChatMessageCell) child);
            }
        }
    }

    private void drawServiceBackground(Canvas canvas, RecyclerView list, View child, RectF r, float alpha) {
        Theme.ResourcesProvider rp = host.resourcesProvider();
        Paint bg = Theme.getThemePaint(Theme.key_paint_chatActionBackground, rp);
        float listTop = host.serviceTop(child) - child.getY();
        int bgHeight = host.serviceHeight(child);
        if (rp != null) {
            rp.applyServiceShaderMatrix(list.getMeasuredWidth(), bgHeight, 0, listTop);
        } else {
            Theme.applyServiceShaderMatrix(list.getMeasuredWidth(), bgHeight, 0, listTop);
        }
        float radius = r.height() / 2f;
        int old = bg.getAlpha();
        if (alpha < 1f) {
            bg.setAlpha((int) (old * alpha));
        }
        canvas.drawRoundRect(r, radius, radius, bg);
        bg.setAlpha(old);
        boolean gradient = rp != null ? rp.hasGradientService() : Theme.hasGradientService();
        Paint darken = Theme.chat_actionBackgroundGradientDarkenPaint;
        if (gradient && darken != null) {
            int oldD = darken.getAlpha();
            if (alpha < 1f) {
                darken.setAlpha((int) (oldD * alpha));
            }
            canvas.drawRoundRect(r, radius, radius, darken);
            darken.setAlpha(oldD);
        }
    }

    private static void applyColor(TextPaint p, int color, float alpha) {
        p.setColor(color);
        if (alpha < 1f) {
            p.setAlpha((int) (Color.alpha(color) * alpha));
        }
    }

    private void drawHeader(Canvas canvas, RecyclerView list, ChatMessageCell cell, boolean mainPass) {
        Theme.ResourcesProvider rp = host.resourcesProvider();
        float alpha = cell.getAlpha();
        int color = Theme.getColor(Theme.key_chat_serviceText, rp);
        headerPaint.setTextSize(dp(13));
        headerActionPaint.setTextSize(dp(13));
        applyColor(headerPaint, color, alpha);
        applyColor(headerActionPaint, color, alpha);

        String label = LuminaLocale.getString(R.string.LuminaAdShieldHeader);
        String collapse = LuminaLocale.getString(R.string.LuminaAdShieldCollapse);
        String notAd = LuminaLocale.getString(R.string.LuminaAdShieldNotAd);
        String sep = " · ";
        float ws = headerPaint.measureText(sep);
        float wc = headerActionPaint.measureText(collapse);
        float wn = headerActionPaint.measureText(notAd);
        int padH = dp(12);
        float avail = list.getMeasuredWidth() - dp(24) - padH * 2;
        float actions = wc + ws + wn;
        CharSequence labelText = label;
        float wl = headerPaint.measureText(label);
        if (wl + ws + actions > avail) {
            float room = avail - actions - ws;
            if (room < dp(24)) {
                labelText = null;
                wl = 0;
            } else {
                labelText = TextUtils.ellipsize(label, headerPaint, room, TextUtils.TruncateAt.END);
                wl = headerPaint.measureText(labelText, 0, labelText.length());
            }
        }
        float content = (labelText != null ? wl + ws : 0) + actions;
        int h = dp(HEADER_DP);
        float bottom = cell.getY();
        float top = bottom - h;
        float pillH = dp(24);
        float cx = list.getMeasuredWidth() / 2f;
        rect.set(cx - content / 2f - padH, top + (h - pillH) / 2f, cx + content / 2f + padH, top + (h + pillH) / 2f);
        drawServiceBackground(canvas, list, cell, rect, alpha);

        float baseline = rect.centerY() - (headerPaint.descent() + headerPaint.ascent()) / 2f;
        boolean rtl = LocaleController.isRTL;
        float x = rtl ? rect.right - padH : rect.left + padH;
        if (labelText != null) {
            x = piece(canvas, labelText, headerPaint, wl, x, baseline, rtl);
            x = piece(canvas, sep, headerPaint, ws, x, baseline, rtl);
        }
        float cStart = x;
        x = piece(canvas, collapse, headerActionPaint, wc, x, baseline, rtl);
        float cEnd = x;
        x = piece(canvas, sep, headerPaint, ws, x, baseline, rtl);
        float nStart = x;
        x = piece(canvas, notAd, headerActionPaint, wn, x, baseline, rtl);
        float nEnd = x;
        if (mainPass && alpha >= 1f) {
            MessageObject mo = cell.getMessageObject();
            int id = mo != null ? mo.getId() : 0;
            float slop = dp(8);
            float split = (cEnd + nStart) / 2f;     // middle of the separator between the two actions
            if (rtl) {
                addHit(ACTION_COLLAPSE, id, split, top, cStart + slop, bottom);
                addHit(ACTION_NOT_AD, id, nEnd - slop, top, split, bottom);
            } else {
                addHit(ACTION_COLLAPSE, id, cStart - slop, top, split, bottom);
                addHit(ACTION_NOT_AD, id, split, top, nEnd + slop, bottom);
            }
        }
    }

    /** Draws one piece of a header in reading order; returns where the next piece starts. */
    private static float piece(Canvas canvas, CharSequence text, TextPaint paint, float w, float x, float baseline, boolean rtl) {
        float left = rtl ? x - w : x;
        canvas.drawText(text, 0, text.length(), left, baseline, paint);
        return rtl ? x - w : x + w;
    }

    private void drawLabel(Canvas canvas, RecyclerView list, ChatMessageCell cell) {
        Theme.ResourcesProvider rp = host.resourcesProvider();
        float alpha = cell.getAlpha();
        labelPaint.setTextSize(dp(11));
        applyColor(labelPaint, Theme.getColor(Theme.key_chat_serviceText, rp), alpha);
        String text = LuminaLocale.getString(R.string.LuminaAdShieldTrialLabel);
        float w = labelPaint.measureText(text);
        float padH = dp(7);
        float pillH = dp(17);
        int h = dp(LABEL_DP);
        float bottom = cell.getY();
        float top = bottom - h;
        float left = cell.getX() + cell.getBackgroundDrawableLeft() + dp(2);
        float maxLeft = list.getMeasuredWidth() - w - padH * 2 - dp(6);
        left = Math.max(dp(6), Math.min(left, maxLeft));
        rect.set(left, top + (h - pillH) / 2f, left + w + padH * 2, top + (h + pillH) / 2f);
        drawServiceBackground(canvas, list, cell, rect, alpha);
        float baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f;
        canvas.drawText(text, rect.left + padH, baseline, labelPaint);
    }

    // =====================================================================================
    // Header touch handling
    // =====================================================================================

    private static final class Hit {
        final RectF rect = new RectF();
        int action;
        int msgId;
    }

    private void addHit(int action, int msgId, float l, float t, float r, float b) {
        if (msgId <= 0) {
            return;
        }
        Hit h;
        if (hitCount < hits.size()) {
            h = hits.get(hitCount);
        } else {
            h = new Hit();
            hits.add(h);
        }
        hitCount++;
        h.action = action;
        h.msgId = msgId;
        h.rect.set(l, t, r, b);
    }

    private Hit hitAt(float x, float y) {
        for (int i = 0; i < hitCount; i++) {
            Hit h = hits.get(i);
            if (h.rect.contains(x, y)) {
                return h;
            }
        }
        return null;
    }

    private final class Touch implements RecyclerView.OnItemTouchListener {
        private int action;
        private int msgId;
        private final RectF pressedRect = new RectF();
        private float downX;
        private float downY;

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                action = 0;
                if (active() != null && rv.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                    Hit h = hitAt(e.getX(), e.getY());
                    if (h != null) {
                        action = h.action;
                        msgId = h.msgId;
                        pressedRect.set(h.rect);
                        downX = e.getX();
                        downY = e.getY();
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            if (action == 0) {
                return;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    int slop = ViewConfiguration.get(rv.getContext()).getScaledTouchSlop();
                    if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) {
                        action = 0;
                    }
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    int a = action;
                    action = 0;
                    if (pressedRect.contains(e.getX(), e.getY())) {
                        try {
                            rv.playSoundEffect(SoundEffectConstants.CLICK);
                        } catch (Exception ignore) {
                        }
                        performHeader(a, msgId);
                    }
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    action = 0;
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        }
    }

    // =====================================================================================
    // Rows
    // =====================================================================================

    /**
     * The fold row: one service-style line in place of a whole segment. Clicks come through ChatActivity's
     * item click listeners (the view itself is not clickable, so the list keeps its gestures); TalkBack gets
     * a spoken label with the action hint and click / long-click actions.
     */
    public static final class FoldCell extends View {
        private static final int MARGIN_V_DP = 4;
        private static final int PAD_V_DP = 5;
        private static final int PAD_H_DP = 11;

        private final Theme.ResourcesProvider resourcesProvider;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bg = new RectF();
        private final ArrayList<MessageObject> members = new ArrayList<MessageObject>();
        private LuminaAdShieldChatUi ui;
        private MessageObject head;
        private CharSequence text;
        private StaticLayout layout;
        private int layoutWidth;
        private boolean visiblePartSet;
        private float viewTop;
        private int backgroundHeight;

        public FoldCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void set(MessageObject head, CharSequence text, String hint) {
            this.head = head;
            if (!TextUtils.equals(this.text, text)) {
                this.text = text;
                layout = null;
                requestLayout();
            }
            setContentDescription(text + "\n" + hint);
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (ui != null) {
                ui.scheduleVerify();    // may come back from the view cache without a rebind
            }
        }

        /** The segment's messages (list order); a visible fold row reads them all. */
        public List<MessageObject> getMembers() {
            return Collections.unmodifiableList(members);
        }

        public MessageObject getHeadMessage() {
            return head;
        }

        /** Same contract as ChatActionCell.setVisiblePart (service background shader position). */
        public void setVisiblePart(float visibleTop, int parentHeight) {
            visiblePartSet = true;
            viewTop = visibleTop;
            backgroundHeight = parentHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            float size = Theme.chat_actionTextPaint != null ? Theme.chat_actionTextPaint.getTextSize() : dp(14);
            if (textPaint.getTextSize() != size) {
                textPaint.setTextSize(size);
                layout = null;
            }
            int maxW = Math.max(dp(80), width - dp(72));
            if (layout == null || layoutWidth != maxW) {
                layout = new StaticLayout(text != null ? text : "", textPaint, maxW, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
                layoutWidth = maxW;
            }
            setMeasuredDimension(width, layout.getHeight() + dp(PAD_V_DP) * 2 + dp(MARGIN_V_DP) * 2);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (layout == null) {
                return;
            }
            float tw = 0;
            for (int i = 0; i < layout.getLineCount(); i++) {
                tw = Math.max(tw, layout.getLineWidth(i));
            }
            float cx = getMeasuredWidth() / 2f;
            bg.set(cx - tw / 2f - dp(PAD_H_DP), dp(MARGIN_V_DP), cx + tw / 2f + dp(PAD_H_DP), getMeasuredHeight() - dp(MARGIN_V_DP));
            if (!visiblePartSet && getParent() instanceof View) {
                backgroundHeight = ((View) getParent()).getMeasuredHeight();
            }
            if (resourcesProvider != null) {
                resourcesProvider.applyServiceShaderMatrix(getMeasuredWidth(), backgroundHeight, 0, viewTop);
            } else {
                Theme.applyServiceShaderMatrix(getMeasuredWidth(), backgroundHeight, 0, viewTop);
            }
            float r = Math.min(dp(14), bg.height() / 2f);
            canvas.drawRoundRect(bg, r, r, Theme.getThemePaint(Theme.key_paint_chatActionBackground, resourcesProvider));
            boolean gradient = resourcesProvider != null ? resourcesProvider.hasGradientService() : Theme.hasGradientService();
            if (gradient && Theme.chat_actionBackgroundGradientDarkenPaint != null) {
                canvas.drawRoundRect(bg, r, r, Theme.chat_actionBackgroundGradientDarkenPaint);
            }
            textPaint.setColor(Theme.getColor(Theme.key_chat_serviceText, resourcesProvider));
            canvas.save();
            canvas.translate(cx - layoutWidth / 2f, dp(MARGIN_V_DP) + dp(PAD_V_DP));
            layout.draw(canvas);
            canvas.restore();
        }

        @Override
        public boolean onGenericMotionEvent(MotionEvent event) {
            // a mouse right-click opens the same menu as a long-press
            if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                    && event.getActionButton() == MotionEvent.BUTTON_SECONDARY && ui != null) {
                return ui.onFoldLongPress(this);
            }
            return super.onGenericMotionEvent(event);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.setEnabled(true);  // the chat list disables its rows (SelectionAdapter.isEnabled); this one acts
            info.setClickable(true);
            info.setLongClickable(true);
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (ui != null) {
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    ui.onFoldTap(this);
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                    return ui.onFoldLongPress(this);
                }
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }

    /** A folded member that is not the segment's top row: zero height, invisible to accessibility. */
    public static final class HiddenCell extends View {
        private LuminaAdShieldChatUi ui;

        public HiddenCell(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            setFocusable(false);
            setWillNotDraw(true);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (ui != null) {
                ui.scheduleVerify();
            }
        }
    }
}
