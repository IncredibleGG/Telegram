package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only checks for {@link LuminaHistoryPaging} (LuminaGram #22 paging). Plain JVM, no Android:
 *   sh lumina-dev/history-paging-check/run.sh
 * Unit checks for every helper, plus a small model of ChatActivity's paging decisions (end-of-history
 * from the page size, older cursor, newer cursor, request spacing) run against a fake history with
 * hidden runs, before and after the fix.
 */
public final class LuminaHistoryPagingCheck {

    private static int passed;
    private static int failed;

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(long expected, long actual, String what) {
        check(expected == actual, what + " expected " + expected + " got " + actual);
    }

    public static void main(String[] args) {
        pageBounds();
        pageEncrypted();
        olderCursor();
        newerCursor();
        throttle();
        retrySlot();
        simulateOlder();
        simulateNewer();
        simulateThrottle();
        System.out.println("history-paging-check: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            System.exit(1);
        }
    }

    // ---- unit checks ----

    private static void pageBounds() {
        LuminaHistoryPaging.Page p = new LuminaHistoryPaging.Page(false);
        p.add(120, 1200, false, false);
        p.add(100, 1000, false, false);
        p.add(110, 0, false, false);          // date 0 does not move the date bounds
        p.add(90, 900, true, false);          // TL_messageEmpty: counted, moves nothing
        p.add(50, 500, false, true);          // ephemeral id: dates move, ids do not
        p.add(-7, 1300, false, false);        // negative id outside a secret chat: dates only
        eq(6, p.removed(), "removed count");
        eq(100, p.olderCursor(Integer.MAX_VALUE), "older from unset");
        eq(95, p.olderCursor(95), "older keeps an older cursor");
        eq(120, p.newerCursor(Integer.MIN_VALUE), "newer from unset");
        eq(130, p.newerCursor(130), "newer keeps a newer cursor");
        eq(500, p.olderDate(0), "older date from unset");
        eq(400, p.olderDate(400), "older date keeps an older date");
        eq(500, p.olderDate(800), "older date moves back");
        eq(1300, p.newerDate(Integer.MIN_VALUE), "newer date");
        check(p.contains(90) && p.contains(-7) && !p.contains(91), "contains");
        eq(4, p.countAtOrBelow(100), "countAtOrBelow 100 (100, 90, 50, -7)");
        eq(6, p.countAtOrBelow(120), "countAtOrBelow max");
        LuminaHistoryPaging.Page big = new LuminaHistoryPaging.Page(false);
        for (int i = 1; i <= 100; i++) {
            big.add(i, i, false, false);
        }
        eq(100, big.removed(), "grows past the initial capacity");
        eq(100, big.countAtOrBelow(1000), "keeps every id");
        check(big.contains(1) && big.contains(100), "keeps first and last id");
        LuminaHistoryPaging.Page onlyEmpty = new LuminaHistoryPaging.Page(false);
        onlyEmpty.add(5, 50, true, false);
        eq(Integer.MAX_VALUE, onlyEmpty.olderCursor(Integer.MAX_VALUE), "empty message leaves older cursor");
        eq(0, onlyEmpty.olderDate(0), "empty message leaves date");
    }

    private static void pageEncrypted() {
        // secret chats: local negative ids, newer = more negative (ChatActivity uses max for the older side)
        LuminaHistoryPaging.Page p = new LuminaHistoryPaging.Page(true);
        p.add(-10, 100, false, false);
        p.add(-30, 300, false, false);
        p.add(-20, 200, false, false);
        eq(-10, p.olderCursor(Integer.MIN_VALUE), "secret older from unset");
        eq(-5, p.olderCursor(-5), "secret older keeps an older cursor");
        eq(-30, p.newerCursor(Integer.MAX_VALUE), "secret newer from unset");
        eq(-40, p.newerCursor(-40), "secret newer keeps a newer cursor");
    }

    private static void olderCursor() {
        LuminaHistoryPaging.OlderCursor c = new LuminaHistoryPaging.OlderCursor();
        check(!c.usable(Integer.MAX_VALUE), "older cursor unset");
        c.record(500);
        check(c.usable(500), "older cursor recorded");
        check(!c.usable(Integer.MAX_VALUE), "reset invalidates");
        check(!c.usable(450), "any other value invalidates");
    }

    private static void newerCursor() {
        final int unset = Integer.MIN_VALUE;
        LuminaHistoryPaging.NewerCursor c = new LuminaHistoryPaging.NewerCursor();
        LuminaHistoryPaging.Page p = new LuminaHistoryPaging.Page(false);
        p.add(150, 1500, false, false);
        p.add(140, 1400, false, false);
        c.record(p, unset, Integer.MIN_VALUE, unset);
        check(!c.usable(unset), "never recorded while minMessageId is unset");
        eq(unset, c.id(unset), "unset passes through");
        c.record(p, 130, 1300, unset);
        check(c.usable(130), "recorded");
        eq(150, c.id(130), "id skips hidden run");
        eq(1500, c.date(130, 1300), "date skips hidden run");
        eq(131, c.id(131), "minMessageId moved: upstream cursor");
        eq(1310, c.date(131, 1310), "minMessageId moved: upstream date");
        // next fully hidden page continues from the Lumina cursor
        LuminaHistoryPaging.Page p2 = new LuminaHistoryPaging.Page(false);
        p2.add(200, 2000, false, false);
        c.record(p2, 130, 1300, unset);
        eq(200, c.id(130), "advances from the Lumina cursor");
        eq(2000, c.date(130, 1300), "date advances");
        // hidden messages that are not newer change nothing
        LuminaHistoryPaging.Page old = new LuminaHistoryPaging.Page(false);
        old.add(120, 1200, false, false);
        c.record(old, 130, 1300, unset);
        eq(200, c.id(130), "older hidden messages keep the cursor");
        // secret chat sentinel
        LuminaHistoryPaging.NewerCursor s = new LuminaHistoryPaging.NewerCursor();
        LuminaHistoryPaging.Page sp = new LuminaHistoryPaging.Page(true);
        sp.add(-50, 500, false, false);
        s.record(sp, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        check(!s.usable(Integer.MAX_VALUE), "secret: not recorded while unset");
        s.record(sp, -40, 400, Integer.MAX_VALUE);
        eq(-50, s.id(-40), "secret: newer is more negative");
    }

    private static void throttle() {
        LuminaHistoryPaging.Throttle t = new LuminaHistoryPaging.Throttle();
        t.onLoadSent(0);
        for (int i = 0; i < 4; i++) {
            t.onPageLoaded(true, 50, 0);
        }
        check(!t.isActive(), "4 hidden server pages: not yet");
        t.onPageLoaded(false, 50, 0);
        check(!t.isActive(), "cache pages do not count");
        t.onPageLoaded(true, 0, 0);
        check(!t.isActive(), "pages without hidden messages do not count");
        t.onPageLoaded(true, 50, 0);
        check(t.isActive(), "5 hidden server pages: active");
        t.onLoadSent(10_000);
        eq(1000, t.delayBeforeLoad(10_000), "full spacing right after a request");
        eq(400, t.delayBeforeLoad(10_600), "remaining spacing");
        eq(0, t.delayBeforeLoad(11_000), "spacing elapsed");
        eq(1000, t.delayBeforeLoad(5_000), "clock going back is clamped");
        t.onPageLoaded(true, 10, 3);
        check(!t.isActive(), "a page with new rows resets");
        eq(0, t.delayBeforeLoad(10_001), "inactive: no delay");
        LuminaHistoryPaging.Throttle fresh = new LuminaHistoryPaging.Throttle();
        for (int i = 0; i < 6; i++) {
            fresh.onPageLoaded(true, 50, 0);
        }
        eq(0, fresh.delayBeforeLoad(123), "active but nothing sent yet: no delay");
    }

    private static void retrySlot() {
        LuminaHistoryPaging.RetrySlot r = new LuminaHistoryPaging.RetrySlot();
        eq(700, r.request(1000, 700), "first request posts");
        eq(-1, r.request(1100, 900), "later request keeps the pending one");
        eq(-1, r.request(1100, 600), "same due time keeps the pending one");
        eq(200, r.request(1200, 200), "earlier request replaces it");
        r.clear();
        eq(900, r.request(1500, 900), "after it ran, posts again");
        // a runnable cancelled without clear(): its due time passes and the next request reposts
        eq(300, r.request(5000, 300), "stale slot reposts");
        eq(0, r.request(6000, -5), "negative delay is clamped");
    }

    // ---- model of ChatActivity's paging, with and without the fix ----

    /** Fake history: ids 1..n, date = id * 10, hidden(id) = keyword match. */
    private interface Hidden {
        boolean test(int id);
    }

    /** One server/cache page older than offset (offset 0 = newest), newest first, like getHistory. */
    private static List<Integer> olderPage(int n, int offset, int count) {
        final List<Integer> out = new ArrayList<>();
        for (int id = offset == 0 ? n : offset - 1; id >= 1 && out.size() < count; id--) {
            out.add(id);
        }
        return out;
    }

    /** One page newer than offset, oldest first. */
    private static List<Integer> newerPage(int n, int offset, int count) {
        final List<Integer> out = new ArrayList<>();
        for (int id = offset + 1; id <= n && out.size() < count; id++) {
            out.add(id);
        }
        return out;
    }

    /**
     * Opens the chat at the bottom (first page 30) and keeps scrolling up (pages of 50), like
     * checkScrollForLoad does while the top is visible. Returns the visible ids loaded.
     */
    private static List<Integer> runOlder(int n, Hidden hidden, boolean fixed, int[] requests, List<Integer> offsets) {
        final List<Integer> visible = new ArrayList<>();
        int maxMessageId = Integer.MAX_VALUE;
        boolean endReached = false;
        final LuminaHistoryPaging.OlderCursor olderCursor = new LuminaHistoryPaging.OlderCursor();
        int loadType = 2;
        int count = 30;
        int offset = 0;
        for (int guard = 0; guard < 10_000 && !endReached; guard++) {
            requests[0]++;
            offsets.add(offset);
            final List<Integer> raw = olderPage(n, offset, count);
            LuminaHistoryPaging.Page page = null;
            final List<Integer> kept = new ArrayList<>();
            for (int id : raw) {
                if (hidden.test(id)) {
                    if (page == null) {
                        page = new LuminaHistoryPaging.Page(false);
                    }
                    page.add(id, id * 10, false, false);
                } else {
                    kept.add(id);
                }
            }
            final int removed = fixed && page != null ? page.removed() : 0;
            for (int id : kept) {
                if (!visible.contains(id)) {
                    visible.add(id);
                }
                maxMessageId = Math.min(maxMessageId, id);
            }
            if (fixed && page != null) {
                final int older = page.olderCursor(maxMessageId);
                if (older != maxMessageId) {
                    maxMessageId = older;
                    olderCursor.record(older);
                }
            }
            // end-of-history (server page): load_type 2 only when empty, otherwise a short page
            if (kept.size() + removed < count) {
                if (loadType != 2 || kept.size() + removed == 0 && visible.isEmpty()) {
                    endReached = true;
                }
            }
            if (raw.isEmpty()) {
                endReached = true; // nothing older at all
            }
            loadType = 0;
            count = 50;
            final boolean haveCursor = !visible.isEmpty() || fixed && olderCursor.usable(maxMessageId);
            offset = haveCursor ? maxMessageId : 0;
            if (!fixed && offset == 0 && !endReached && guard > 3) {
                // the unfixed client would re-request the newest page forever; stop the model
                break;
            }
        }
        return visible;
    }

    private static void simulateOlder() {
        final int n = 600;
        final Hidden everyTenth = id -> id % 10 == 0;
        final Hidden middleRun = id -> id > 200 && id <= 330;           // 130 hidden in a row
        final Hidden newestRun = id -> id > 560;                         // first page fully hidden
        final Hidden none = id -> false;
        final Hidden[] cases = {everyTenth, middleRun, newestRun, none};
        final String[] names = {"every 10th hidden", "hidden run in the middle", "newest 40 hidden", "nothing hidden"};
        for (int c = 0; c < cases.length; c++) {
            int expectedVisible = 0;
            for (int id = 1; id <= n; id++) {
                if (!cases[c].test(id)) {
                    expectedVisible++;
                }
            }
            final int[] reqFixed = {0};
            final List<Integer> offFixed = new ArrayList<>();
            final List<Integer> fixed = runOlder(n, cases[c], true, reqFixed, offFixed);
            eq(expectedVisible, fixed.size(), names[c] + ": fixed client reaches every visible message");
            boolean decreasing = true;
            for (int i = 2; i < offFixed.size(); i++) {
                if (offFixed.get(i) >= offFixed.get(i - 1)) {
                    decreasing = false;
                }
            }
            check(decreasing, names[c] + ": older offsets strictly decrease (no page fetched twice) " + offFixed);
            check(reqFixed[0] <= n / 50 + 3, names[c] + ": request count bounded (" + reqFixed[0] + ")");
            final int[] reqOld = {0};
            final List<Integer> old = runOlder(n, cases[c], false, reqOld, new ArrayList<Integer>());
            if (c == cases.length - 1) {
                eq(expectedVisible, old.size(), names[c] + ": unfixed client also complete (regression baseline)");
                check(offFixed.equals(offsetsOf(n, cases[c], false)), names[c] + ": identical request sequence without hidden messages");
            } else {
                check(old.size() < expectedVisible, names[c] + ": unfixed client stops early (the bug) " + old.size() + "/" + expectedVisible);
            }
        }
    }

    private static List<Integer> offsetsOf(int n, Hidden hidden, boolean fixed) {
        final List<Integer> offsets = new ArrayList<>();
        runOlder(n, hidden, fixed, new int[1], offsets);
        return offsets;
    }

    /** Jumped to an old message (visible up to `from`), then scrolling down: newer pages of 50. */
    private static void simulateNewer() {
        final int n = 700;
        final Hidden hidden = id -> id > 300 && id <= 460 || id % 7 == 0;
        final int from = 250;
        int minMessageId = from;       // upstream newest visible id, also used by read receipts
        int maxDate = from * 10;
        boolean forwardEndReached = false;
        final LuminaHistoryPaging.NewerCursor cursor = new LuminaHistoryPaging.NewerCursor();
        final List<Integer> visible = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        final int count = 50;
        for (int guard = 0; guard < 1000 && !forwardEndReached; guard++) {
            final int offset = cursor.id(minMessageId);
            offsets.add(offset);
            check(cursor.date(minMessageId, maxDate) >= maxDate, "newer date never goes back");
            final List<Integer> raw = newerPage(n, offset, count);
            LuminaHistoryPaging.Page page = null;
            int kept = 0;
            for (int id : raw) {
                if (hidden.test(id)) {
                    if (page == null) {
                        page = new LuminaHistoryPaging.Page(false);
                    }
                    page.add(id, id * 10, false, false);
                } else {
                    kept++;
                    visible.add(id);
                    minMessageId = Math.max(minMessageId, id);
                    maxDate = Math.max(maxDate, id * 10);
                }
            }
            cursor.record(page, minMessageId, maxDate, Integer.MIN_VALUE);
            final int removed = page != null ? page.removed() : 0;
            if (kept + removed != count) {
                forwardEndReached = true;
            }
        }
        int expected = 0;
        int newestVisible = 0;
        for (int id = from + 1; id <= n; id++) {
            if (!hidden.test(id)) {
                expected++;
                newestVisible = id;
            }
        }
        check(forwardEndReached, "newer: reaches the bottom");
        eq(expected, visible.size(), "newer: every visible message, no gap");
        eq(newestVisible, minMessageId, "newer: upstream minMessageId only ever holds a visible id");
        boolean increasing = true;
        for (int i = 1; i < offsets.size(); i++) {
            if (offsets.get(i) <= offsets.get(i - 1)) {
                increasing = false;
            }
        }
        check(increasing, "newer: offsets strictly increase " + offsets);
    }

    /** 2000 hidden messages in a row, served instantly: request times after the 5th hidden server page. */
    private static void simulateThrottle() {
        final LuminaHistoryPaging.Throttle t = new LuminaHistoryPaging.Throttle();
        final LuminaHistoryPaging.RetrySlot slot = new LuminaHistoryPaging.RetrySlot();
        long now = 0;
        final List<Long> sent = new ArrayList<>();
        int pages = 0;
        while (pages < 40) {
            final long wait = t.delayBeforeLoad(now);
            if (wait > 0) {
                final long delay = slot.request(now, wait);
                check(delay >= 0, "throttle: one pending retry");
                now += delay;   // the retry runnable fires
                slot.clear();
                continue;
            }
            t.onLoadSent(now);
            sent.add(now);
            pages++;
            now += 120;     // response time
            t.onPageLoaded(true, 50, 0);
        }
        for (int i = 1; i < sent.size(); i++) {
            final long gap = sent.get(i) - sent.get(i - 1);
            if (i <= LuminaHistoryPaging.THROTTLE_AFTER_HIDDEN_PAGES - 1) {
                check(gap == 120, "throttle: first pages go out back to back (" + i + ": " + gap + ")");
            } else {
                check(gap >= LuminaHistoryPaging.THROTTLE_SPACING_MS, "throttle: spaced after 5 hidden pages (" + i + ": " + gap + ")");
            }
        }
        t.onPageLoaded(true, 20, 5);
        eq(0, t.delayBeforeLoad(now), "throttle: visible rows end the spacing");
    }
}
