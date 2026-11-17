package org.telegram.messenger;

/**
 * LuminaGram #22 paging: pure bookkeeping for history pages that the keyword filter shortened.
 *
 * ChatActivity drops keyword-matched messages from every loaded page before the visible list is
 * built ({@link LuminaHistoryFilter#filterPage}). Upstream paging decides "end of history" from the
 * page size and advances its cursors from the messages it keeps, so a page with hidden messages used
 * to look like the end of the chat, and a fully hidden page left the cursors where they were. The
 * classes here keep what the filter removed so ChatActivity can page by the unfiltered page:
 * <ul>
 * <li>{@link Page}: count, ids and cursor bounds of the removed messages, computed exactly like the
 * cursor loop in ChatActivity's messagesDidLoad handler.</li>
 * <li>{@link OlderCursor}: remembers that the older-page cursor (upstream maxMessageId[0]) was moved
 * by the filter, so a request with no visible message yet continues from it instead of max_id 0.</li>
 * <li>{@link NewerCursor}: a separate newer-page cursor. It never writes upstream minMessageId/maxDate,
 * which also feed read receipts, and is only valid while minMessageId still has the value it was
 * recorded against.</li>
 * <li>{@link Throttle} and {@link RetrySlot}: after a run of fully hidden server pages, requests are
 * spaced out, with a single pending retry.</li>
 * </ul>
 * No Android dependency, so lumina-dev/history-paging-check can run it on a plain JVM.
 */
public final class LuminaHistoryPaging {

    /** Consecutive fully hidden server pages (per direction) before requests are spaced out. */
    public static final int THROTTLE_AFTER_HIDDEN_PAGES = 5;
    /** Minimum time between two requests in one direction while throttled. */
    public static final long THROTTLE_SPACING_MS = 1000L;

    private LuminaHistoryPaging() {
    }

    /** What the keyword filter removed from one loaded page. */
    public static final class Page {
        private final boolean encrypted;
        private int removed;
        private int[] removedIds = new int[8];
        private boolean hasPositive;
        private int positiveMin;
        private int positiveMax;
        private boolean hasLocal;
        private int localMax;
        private int localMin;
        private boolean hasDate;
        private int dateMin;
        private int dateMax;

        public Page(boolean encrypted) {
            this.encrypted = encrypted;
        }

        /**
         * Records one removed message. Mirrors the cursor update in ChatActivity's messagesDidLoad loop:
         * an empty message (TL_messageEmpty) updates nothing, an ephemeral id does not move the id
         * cursors, positive ids move them as min/max, and other ids only count in a secret chat (where
         * the order is reversed). A zero date does not move the date cursors.
         */
        public void add(int id, int date, boolean emptyMessage, boolean ephemeralId) {
            if (removed == removedIds.length) {
                final int[] grown = new int[removedIds.length * 2];
                System.arraycopy(removedIds, 0, grown, 0, removed);
                removedIds = grown;
            }
            removedIds[removed++] = id;
            if (emptyMessage) {
                return;
            }
            if (!ephemeralId) {
                if (id > 0) {
                    positiveMin = hasPositive ? Math.min(positiveMin, id) : id;
                    positiveMax = hasPositive ? Math.max(positiveMax, id) : id;
                    hasPositive = true;
                } else if (encrypted) {
                    localMax = hasLocal ? Math.max(localMax, id) : id;
                    localMin = hasLocal ? Math.min(localMin, id) : id;
                    hasLocal = true;
                }
            }
            if (date != 0) {
                dateMin = hasDate ? Math.min(dateMin, date) : date;
                dateMax = hasDate ? Math.max(dateMax, date) : date;
                hasDate = true;
            }
        }

        /** Number of messages the filter removed from the page. */
        public int removed() {
            return removed;
        }

        /** Older-page cursor (upstream maxMessageId) after also counting the removed messages. */
        public int olderCursor(int maxMessageId) {
            int v = maxMessageId;
            if (hasPositive) {
                v = Math.min(v, positiveMin);
            }
            if (hasLocal) {
                v = Math.max(v, localMax);
            }
            return v;
        }

        /** Newer-page cursor (upstream minMessageId) after also counting the removed messages. */
        public int newerCursor(int minMessageId) {
            int v = minMessageId;
            if (hasPositive) {
                v = Math.max(v, positiveMax);
            }
            if (hasLocal) {
                v = Math.min(v, localMin);
            }
            return v;
        }

        /** Oldest date (upstream minDate, 0 = unset) after also counting the removed messages. */
        public int olderDate(int minDate) {
            if (!hasDate) {
                return minDate;
            }
            return minDate == 0 || dateMin < minDate ? dateMin : minDate;
        }

        /** Newest date (upstream maxDate) after also counting the removed messages. */
        public int newerDate(int maxDate) {
            return hasDate ? Math.max(maxDate, dateMax) : maxDate;
        }

        /** True when a message with this id was removed from the page. */
        public boolean contains(int id) {
            for (int i = 0; i < removed; i++) {
                if (removedIds[i] == id) {
                    return true;
                }
            }
            return false;
        }

        /** Removed messages with an id at or below {@code id} (the "before" side of a thread page). */
        public int countAtOrBelow(int id) {
            int n = 0;
            for (int i = 0; i < removed; i++) {
                if (removedIds[i] <= id) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public String toString() {
            return "removed=" + removed + " older=" + (hasPositive ? positiveMin : hasLocal ? localMax : 0)
                    + " newer=" + (hasPositive ? positiveMax : hasLocal ? localMin : 0);
        }
    }

    /**
     * The older-page cursor (upstream maxMessageId[0]) is written directly, because nothing but the
     * request uses it. This only remembers that the filter moved it, so the first older request while
     * no visible message is loaded yet continues from it. Any upstream reset of the cursor (a new
     * value) invalidates it by itself.
     */
    public static final class OlderCursor {
        private boolean set;
        private int value;

        public void record(int maxMessageId) {
            set = true;
            value = maxMessageId;
        }

        public boolean usable(int maxMessageId) {
            return set && value == maxMessageId;
        }
    }

    /**
     * Newer-page cursor that skips a run of hidden messages without touching upstream
     * minMessageId/maxDate (read receipts use them). It is valid only while minMessageId still has the
     * value it was recorded against, and it is never recorded while minMessageId is still unset, so a
     * chat reset cannot revive it.
     */
    public static final class NewerCursor {
        private boolean set;
        private int id;
        private int date;
        private int base;

        public boolean usable(int minMessageId) {
            return set && base == minMessageId;
        }

        /** The id to request newer messages after. */
        public int id(int minMessageId) {
            return usable(minMessageId) ? id : minMessageId;
        }

        /** The date to request newer messages from. */
        public int date(int minMessageId, int maxDate) {
            return usable(minMessageId) ? date : maxDate;
        }

        /**
         * After a page was processed: moves the cursor past removed messages that are newer than
         * everything visible. {@code unset} is the value minMessageId has while nothing is loaded.
         */
        public void record(Page page, int minMessageId, int maxDate, int unset) {
            if (page == null || minMessageId == unset) {
                return;
            }
            final int fromId = id(minMessageId);
            final int toId = page.newerCursor(fromId);
            if (toId == fromId) {
                return;
            }
            date = page.newerDate(date(minMessageId, maxDate));
            id = toId;
            base = minMessageId;
            set = true;
        }
    }

    /** Spaces out requests in one direction after a run of fully hidden server pages. */
    public static final class Throttle {
        private int hiddenServerPagesInRow;
        private boolean hasLastLoad;
        private long lastLoadAt;

        /** After every page in this direction. */
        public void onPageLoaded(boolean fromServer, int removed, int newRows) {
            if (newRows > 0) {
                hiddenServerPagesInRow = 0;
            } else if (fromServer && removed > 0 && hiddenServerPagesInRow < Integer.MAX_VALUE) {
                hiddenServerPagesInRow++;
            }
        }

        public boolean isActive() {
            return hiddenServerPagesInRow >= THROTTLE_AFTER_HIDDEN_PAGES;
        }

        /** Milliseconds to wait before the next request may be sent; 0 = send now. */
        public long delayBeforeLoad(long now) {
            if (!isActive() || !hasLastLoad) {
                return 0;
            }
            final long wait = lastLoadAt + THROTTLE_SPACING_MS - now;
            if (wait <= 0) {
                return 0;
            }
            return Math.min(wait, THROTTLE_SPACING_MS);
        }

        /** A request in this direction was sent now. */
        public void onLoadSent(long now) {
            hasLastLoad = true;
            lastLoadAt = now;
        }
    }

    /**
     * Bookkeeping for the single pending retry runnable. A request that would fire later than the
     * pending one keeps the pending one; an earlier one replaces it. A value left behind by a
     * cancelled runnable is in the past and is replaced by the next request.
     */
    public static final class RetrySlot {
        private boolean pending;
        private long dueAt;

        /** Returns the delay to (re)post the runnable with, or -1 to keep the pending one. */
        public long request(long now, long delay) {
            final long at = now + Math.max(0, delay);
            if (pending && dueAt > now && dueAt <= at) {
                return -1;
            }
            pending = true;
            dueAt = at;
            return at - now;
        }

        /** The runnable ran or was cancelled. */
        public void clear() {
            pending = false;
        }
    }
}
