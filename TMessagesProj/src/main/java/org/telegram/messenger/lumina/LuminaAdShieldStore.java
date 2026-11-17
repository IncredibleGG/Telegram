/*
 * LuminaGram - AdShield ("隱藏重複廣告") per-chat store, Android port.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 *
 * Port of ChatStore in adshield/ref/adshield.py (SPEC §5.5, §6, §8.2, §8.3), algoVersion 3 / formatVersion 3.
 */
package org.telegram.messenger.lumina;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One chat's AdShield state: stored rows (keyed hashes only), endorsement / "not an ad" records and
 * the known-member table, plus the decision logic.
 *
 * <p><b>Threading:</b> not thread-safe by design. Every call (observe, delete, markNotAd, decide,
 * compact, serialize) must run on the single AdShield engine thread ({@link LuminaAdShieldThread});
 * the UI thread only copies the fields it needs into a {@link LuminaAdShield.MessageInput} and posts.
 *
 * <p><b>Failure policy:</b> observe / markNotAd / decide catch {@link Throwable} (SPEC §4.2: deep
 * regex recursion on inputs like {@code a.a.a....com}) and skip the message; only
 * {@link ForgottenException} (writes after ForgetAll) and bad arguments propagate.
 *
 * <p><b>Determinism:</b> no result depends on HashMap/HashSet iteration order. Units with exactly
 * equal SPEC §6.7 order tuples (possible only for anonymous units with the same date and the same
 * smallest key hash) are tie-broken by row id, which the reference leaves unspecified.
 *
 * <p><b>Index (SPEC §6.6):</b> the units, their "(kind, hash) -> units" index and the record index form a
 * {@link View} over primitive arrays ({@link KeyIndex}). It is built once per store change (an observe /
 * delete / notAd batch, expiry) and then shared by every decide of the batch with the same {@code now} and
 * member handles; decide itself never rebuilds it and only visits the units that share a key with m.
 */
public final class LuminaAdShieldStore {

    /** Thrown by write entry points after {@link LuminaAdShieldAccount#forgetAll}. */
    public static final class ForgottenException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ForgottenException() {
            super("AdShield data was cleared; writes are refused");
        }
    }

    static final int REC_ENDORSE = 1;
    static final int REC_NOTAD = 2;
    static final int HEADER_SIZE = 56;
    static final byte[] MAGIC = {'L', 'G', 'A', 'S'};

    static final class Row {
        long rid;
        int msgId;              // 0 = deleted (anonymous)
        long album;             // grouped_id, 0 = none
        long date;
        long seen;
        long sender;            // keyed hash, 0 = anonymous
        int flags;
        LuminaAdShield.Key[] keys;
        long[] links;           // subset links (fixed part)
        long[] pending;         // pending handle hashes

        boolean live() {
            return msgId != 0;
        }
    }

    static final class Record {
        int kind;               // 1 = endorsement, 2 = not an ad
        long stamp;
        long msgKey;            // endorsement: H("msg", msgId); not-ad: 0
        /** v3: endorsement by a privileged author: its senderKey; otherwise (self, pinned member, member forward, not-ad) 0. */
        long author;
        LuminaAdShield.Key[] keys;
        long[] links;

        Record(int kind, long stamp, long msgKey, LuminaAdShield.Key[] keys, long[] links) {
            this(kind, stamp, msgKey, 0, keys, links);
        }

        Record(int kind, long stamp, long msgKey, long author, LuminaAdShield.Key[] keys, long[] links) {
            this.kind = kind;
            this.stamp = stamp;
            this.msgKey = msgKey;
            this.author = author;
            this.keys = keys;
            this.links = links;
        }
    }

    static final class HandleInfo {
        long first;             // earliest message date of this username
        long last;              // latest message date
        long seen;              // last observation time (now)

        HandleInfo(long first, long last, long seen) {
            this.first = first;
            this.last = last;
            this.seen = seen;
        }
    }

    /** Counting unit (one album = one unit); keys/links/flags are post-resolve values. */
    static final class Unit {
        int idx;                // creation order (first row id ascending)
        Row[] rows;
        boolean album;
        long date;
        long sender;
        int flags;
        LuminaAdShield.Key[] keys;      // distinct
        long[] links;                   // distinct
        boolean live;
        int minMsgId;                   // order tuple: (date, live, minMsgId, minKeyHash unsigned)
        long minKeyHash;
        int nBlocks;                    // v3: number of BLOCK keys
        private HashSet<LuminaAdShield.Key> keySet;

        boolean hasKey(LuminaAdShield.Key k) {
            if (keys.length <= 8) {
                for (LuminaAdShield.Key x : keys) {
                    if (x.equals(k)) {
                        return true;
                    }
                }
                return false;
            }
            if (keySet == null) {
                keySet = new HashSet<LuminaAdShield.Key>(Arrays.asList(keys));
            }
            return keySet.contains(k);
        }
    }

    /** Cached unit view; valid for one (mutation counter, now, member-handle set). */
    static final class View {
        long mutations;
        long now;
        long[] memberHashes;
        ArrayList<Unit> units = new ArrayList<Unit>();
        Unit[] unitArr;
        HashMap<Long, Unit> byAlbum = new HashMap<Long, Unit>();
        HashMap<Long, Unit> byRid = new HashMap<Long, Unit>();
        final KeyIndex index = new KeyIndex();              // (kind, hash) -> unit idx
        ArrayList<Record> liveRecords = new ArrayList<Record>();
        final KeyIndex recordIndex = new KeyIndex();        // (kind, hash) -> live record index
        final KeyIndex recordVarIndex = new KeyIndex();     // (VAR, hash) of any VAR-family key -> live record index
        int[] mark;             // == gen: in G(m)
        int[] v2Mark;           // == gen: in G2(m) (linked by the v2 rules)
        int[] paraGen;
        int[] paraCnt;
        int[] blockGen;
        int[] blockCnt;
        int gen;
    }

    /**
     * (kind, hash) -> posting list of ints (unit or record indices, in insertion order) over primitive arrays:
     * an open-addressing table of the distinct keys plus one flat postings array (SPEC §6.6: no
     * {@code HashMap<Key, List<Unit>>}). Filled with {@link #add}, then {@link #build} once.
     */
    static final class KeyIndex {
        private long[] eHash = new long[16];
        private byte[] eKind = new byte[16];
        private int[] eVal = new int[16];
        private int n;
        private int[] table;            // distinct-key id + 1; 0 = empty slot
        private long[] dHash;
        private byte[] dKind;
        private int[] dStart;           // postings of key d: [dStart[d], dStart[d + 1])
        private int[] postings;
        private int mask;

        void add(int kind, long hash, int val) {
            if (n == eHash.length) {
                int cap = n * 2;
                eHash = Arrays.copyOf(eHash, cap);
                eKind = Arrays.copyOf(eKind, cap);
                eVal = Arrays.copyOf(eVal, cap);
            }
            eHash[n] = hash;
            eKind[n] = (byte) kind;
            eVal[n] = val;
            n++;
        }

        private static int slot(int kind, long hash, int mask) {
            long x = (hash ^ (kind * 0x9E3779B97F4A7C15L)) * 0xBF58476D1CE4E5B9L;
            return (int) (x ^ (x >>> 32)) & mask;
        }

        void build() {
            int cap = 16;
            while (cap < n * 2) {
                cap <<= 1;
            }
            mask = cap - 1;
            table = new int[cap];
            long[] hs = new long[n];
            byte[] ks = new byte[n];
            int[] count = new int[n];
            int[] idOf = new int[n];
            int nd = 0;
            for (int e = 0; e < n; e++) {
                long h = eHash[e];
                byte k = eKind[e];
                int i = slot(k, h, mask);
                int id;
                while (true) {
                    int t = table[i];
                    if (t == 0) {
                        id = nd++;
                        table[i] = id + 1;
                        hs[id] = h;
                        ks[id] = k;
                        break;
                    }
                    id = t - 1;
                    if (ks[id] == k && hs[id] == h) {
                        break;
                    }
                    i = (i + 1) & mask;
                }
                count[id]++;
                idOf[e] = id;
            }
            dHash = Arrays.copyOf(hs, nd);
            dKind = Arrays.copyOf(ks, nd);
            dStart = new int[nd + 1];
            for (int d = 0; d < nd; d++) {
                dStart[d + 1] = dStart[d] + count[d];
            }
            int[] fill = Arrays.copyOf(dStart, nd);
            postings = new int[n];
            for (int e = 0; e < n; e++) {
                postings[fill[idOf[e]]++] = eVal[e];
            }
            eHash = null;
            eKind = null;
            eVal = null;
        }

        /** The distinct-key id of (kind, hash), or -1. */
        int find(int kind, long hash) {
            int i = slot(kind, hash, mask);
            while (true) {
                int t = table[i];
                if (t == 0) {
                    return -1;
                }
                if (dKind[t - 1] == (byte) kind && dHash[t - 1] == hash) {
                    return t - 1;
                }
                i = (i + 1) & mask;
            }
        }

        int start(int id) {
            return dStart[id];
        }

        int end(int id) {
            return dStart[id + 1];
        }

        int at(int i) {
            return postings[i];
        }
    }

    private static final class Rule {
        final long window;
        final int hits;
        final int senders;

        Rule(long window, int hits, int senders) {
            this.window = window;
            this.hits = hits;
            this.senders = senders;
        }
    }

    private static final class RuleSet {
        final Rule[] strong;
        final Rule[] weak;
        final Rule sameSender;  // senders unused (1)
        final Rule plain;

        RuleSet(Rule[] strong, Rule[] weak, Rule sameSender, Rule plain) {
            this.strong = strong;
            this.weak = weak;
            this.sameSender = sameSender;
            this.plain = plain;
        }
    }

    private static final long H72 = 72 * LuminaAdShield.HOUR;
    private static final long H24 = 24 * LuminaAdShield.HOUR;
    private static final long D7 = 7 * LuminaAdShield.DAY;
    private static final RuleSet RULES_LOOSE = new RuleSet(
            new Rule[]{new Rule(H72, 4, 1), new Rule(H72, 2, 3)}, new Rule[0], null, null);
    private static final RuleSet RULES_STANDARD = new RuleSet(
            new Rule[]{new Rule(D7, 3, 1), new Rule(D7, 2, 2)}, new Rule[]{new Rule(H24, 4, 3)},
            new Rule(D7, 3, 1), null);
    private static final RuleSet RULES_AGGRESSIVE = new RuleSet(
            new Rule[]{new Rule(D7, 2, 1)}, new Rule[]{new Rule(D7, 3, 2)},
            new Rule(D7, 3, 1), new Rule(D7, 3, 3));

    private static RuleSet rules(String sensitivity) {
        if (LuminaAdShield.SENS_LOOSE.equals(sensitivity)) {
            return RULES_LOOSE;
        }
        if (LuminaAdShield.SENS_STANDARD.equals(sensitivity)) {
            return RULES_STANDARD;
        }
        if (LuminaAdShield.SENS_AGGRESSIVE.equals(sensitivity)) {
            return RULES_AGGRESSIVE;
        }
        throw new IllegalArgumentException("unknown sensitivity");
    }

    public final long chatId;
    final byte[] key;
    final LuminaAdShieldAccount owner;
    final TreeMap<Long, Row> rows = new TreeMap<Long, Row>();
    final HashMap<Integer, Long> byMsg = new HashMap<Integer, Long>();
    long nextRid = 1;
    final ArrayList<Record> records = new ArrayList<Record>();
    final HashMap<Long, HandleInfo> handles = new HashMap<Long, HandleInfo>();
    int version;            // same meaning as the reference (bumped by observe/delete/markNotAd/expiry)
    long mutations;         // any state change (also record/handle expiry); drives caches and dirty state
    int maxRows = LuminaAdShield.MAX_ROWS;          // overridable by the checker only
    int maxHandles = LuminaAdShield.MAX_HANDLES;
    private View cache;

    /** A detached store (no account). Production code goes through {@link LuminaAdShieldAccount#chat}. */
    public LuminaAdShieldStore(long chatId, byte[] key) {
        this(chatId, key, null);
    }

    LuminaAdShieldStore(long chatId, byte[] key, LuminaAdShieldAccount owner) {
        if (key == null || key.length != 16) {
            throw new IllegalArgumentException("key must be 16 bytes");
        }
        this.chatId = chatId;
        this.key = key.clone();
        this.owner = owner;
    }

    public int getVersion() {
        return version;
    }

    long getMutations() {
        return mutations;
    }

    /** Counts only (safe to log). */
    public int rowCount() {
        return rows.size();
    }

    public int recordCount() {
        return records.size();
    }

    public int handleCount() {
        return handles.size();
    }

    private void checkWritable() {
        if (owner != null && owner.isForgotten()) {
            throw new ForgottenException();
        }
    }

    // =====================================================================================
    // Writes
    // =====================================================================================

    /**
     * New message, history load, or edit (same msgId = upsert: the old row is removed and recomputed).
     * Returns the fingerprint, or null when the chat/message is not eligible, out of range, or failed.
     */
    public LuminaAdShield.Fingerprint observe(LuminaAdShield.MessageInput msg, LuminaAdShield.ChatContext ctx, long now) {
        checkWritable();
        if (msg == null || !LuminaAdShield.eligibleChat(ctx) || !LuminaAdShield.eligibleMessage(msg)
                || !LuminaAdShield.validMessage(msg)) {
            return null;
        }
        if (now < 0 || now > LuminaAdShield.MAX_DATE) {
            return null;
        }
        LuminaAdShield.Fingerprint fp;
        try {
            compact(now);
            fp = LuminaAdShield.fingerprint(msg, ctx, key, null, false);
        } catch (Throwable t) {
            LuminaAdShield.log("adshield observe skipped: " + t.getClass().getSimpleName());
            return null;
        }
        int mid = msg.id;
        long date = msg.date;
        Long old = byMsg.remove(mid);
        if (old != null) {
            rows.remove(old);           // edit: drop the old row entirely, recompute
        }
        String username = LuminaAdShield.uname(msg.sender != null ? msg.sender.username : null);
        if (LuminaAdShield.validUsername(username)) {
            long h = LuminaAdShield.handleHash(key, username);
            HandleInfo hi = handles.get(h);
            if (hi == null) {
                handles.put(h, new HandleInfo(date, date, now));
            } else {
                hi.first = Math.min(hi.first, date);
                hi.last = Math.max(hi.last, date);
                hi.seen = Math.max(hi.seen, now);
            }
        }
        LuminaAdShield.Key[] keys = fp.keys();
        if (fp.endorsing && keys.length > 0) {
            long[] links = fp.linkHashes;
            for (long h : fp.pending) {
                if (!contains(fp.linkHashes, h)) {
                    links = Arrays.copyOf(links, links.length + 1);
                    links[links.length - 1] = h;
                }
            }
            upsertEndorse(now, AdShieldTables.keyedHash(key, "msg", Integer.toString(mid)), Arrays.asList(keys), links,
                    fp.privileged ? fp.senderKey : 0);
        }
        if (fp.store) {
            Row r = new Row();
            r.rid = nextRid++;
            r.msgId = mid;
            r.album = msg.album;
            r.date = date;
            r.seen = now;
            r.sender = fp.senderKey;
            r.flags = fp.flags;
            r.keys = keys;
            r.links = fp.linkHashes.clone();
            r.pending = fp.pending.clone();
            rows.put(r.rid, r);
            byMsg.put(mid, r.rid);
        }
        enforceCaps();
        version++;
        mutations++;
        return fp;
    }

    /** Deleted / auto-deleted messages become anonymous rows: counts, dates, keys and link hashes stay. */
    public void delete(int[] msgIds, long now) {
        checkWritable();
        for (int mid : msgIds) {
            Long rid = byMsg.remove(mid);
            if (rid == null) {
                continue;
            }
            Row r = rows.get(rid);
            r.msgId = 0;
            r.sender = 0;
            r.flags = (r.flags & LuminaAdShield.ANON_KEEP) | LuminaAdShield.F_ANON;
            r.seen = now;
        }
        version++;
        mutations++;
    }

    /**
     * "Not an ad" (SPEC §6.4): records the whole cluster of the message for 90 days.
     * Units: m first, then the rest of G(m) by order; keys per unit by (kind, hash unsigned), links by
     * hash unsigned; deduped; record key order (v2 exact, paragraph, then v3 keys; at most 128); links at most 64.
     */
    public boolean markNotAd(int msgId, LuminaAdShield.ChatContext ctx, long now) {
        checkWritable();
        try {
            View v = view(ctx, now);
            Unit m = unitOf(v, msgId);
            if (m == null) {
                return false;
            }
            ArrayList<Unit> grp = group(v, m).units;
            ArrayList<Unit> ordered = new ArrayList<Unit>(grp.size());
            ordered.add(m);
            for (Unit u : grp) {
                if (u != m) {
                    ordered.add(u);
                }
            }
            ArrayList<LuminaAdShield.Key> keySeq = new ArrayList<LuminaAdShield.Key>();
            ArrayList<Long> linkSeq = new ArrayList<Long>();
            for (Unit u : ordered) {
                LuminaAdShield.Key[] ks = u.keys.clone();
                Arrays.sort(ks, LuminaAdShield.Key.ORDER);
                keySeq.addAll(Arrays.asList(ks));
                long[] ls = u.links.clone();
                LuminaAdShield.sortUnsigned(ls);
                for (long l : ls) {
                    linkSeq.add(l);
                }
            }
            LuminaAdShield.Key[] keys = orderedRecordKeys(keySeq);
            long[] links = dedupe(linkSeq, LuminaAdShield.MAX_RECORD_LINKS);
            addNotAd(now, keys, links);
            version++;
            mutations++;
            return true;
        } catch (ForgottenException e) {
            throw e;
        } catch (Throwable t) {
            LuminaAdShield.log("adshield markNotAd failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean contains(long[] a, long v) {
        for (long x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static long[] dedupe(List<Long> xs, int cap) {
        long[] out = new long[Math.min(xs.size(), cap)];
        int n = 0;
        HashSet<Long> seen = new HashSet<Long>();
        for (Long x : xs) {
            if (seen.add(x)) {
                out[n++] = x;
                if (n == cap) {
                    break;
                }
            }
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    /**
     * Record keys (SPEC §6.4): deduped (first occurrence wins); first what v2 stored (exact keys, then
     * paragraph keys, each in order, at most 64), then the v3 keys (VAR VARL VARS BLOCK) in order; at most 128.
     * So the paragraph keys are never pushed out by the many VAR keys.
     */
    static LuminaAdShield.Key[] orderedRecordKeys(List<LuminaAdShield.Key> seq) {
        LinkedHashSet<LuminaAdShield.Key> seen = new LinkedHashSet<LuminaAdShield.Key>(seq);
        ArrayList<LuminaAdShield.Key> out = new ArrayList<LuminaAdShield.Key>(seen.size());
        for (LuminaAdShield.Key k : seen) {
            if (LuminaAdShield.isExactKind(k.kind)) {
                out.add(k);
            }
        }
        for (LuminaAdShield.Key k : seen) {
            if (k.kind == LuminaAdShield.KIND_PARA) {
                out.add(k);
            }
        }
        if (out.size() > LuminaAdShield.RECORD_V2_KEYS) {
            out.subList(LuminaAdShield.RECORD_V2_KEYS, out.size()).clear();
        }
        for (LuminaAdShield.Key k : seen) {
            if (!LuminaAdShield.isV2Kind(k.kind)) {
                out.add(k);
            }
        }
        int n = Math.min(out.size(), LuminaAdShield.MAX_RECORD_KEYS);
        return out.subList(0, n).toArray(new LuminaAdShield.Key[n]);
    }

    private static long[] capLinks(long[] links) {
        return links.length > LuminaAdShield.MAX_RECORD_LINKS
                ? Arrays.copyOf(links, LuminaAdShield.MAX_RECORD_LINKS) : links.clone();
    }

    private void evictRecords(int kind) {
        int cap = kind == REC_ENDORSE ? LuminaAdShield.MAX_ENDORSE_RECORDS : LuminaAdShield.MAX_NOTAD_RECORDS;
        while (true) {
            int count = 0;
            int oldest = -1;
            for (int i = 0; i < records.size(); i++) {
                Record r = records.get(i);
                if (r.kind != kind) {
                    continue;
                }
                count++;
                if (oldest < 0 || r.stamp < records.get(oldest).stamp) {
                    oldest = i;             // ties: the earlier one in the list
                }
            }
            if (count <= cap) {
                return;
            }
            records.remove(oldest);
        }
    }

    private void upsertEndorse(long stamp, long msgKey, List<LuminaAdShield.Key> keys, long[] links, long author) {
        LuminaAdShield.Key[] ks = orderedRecordKeys(keys);
        long[] ls = capLinks(links);
        for (Record r : records) {
            if (r.kind == REC_ENDORSE && r.msgKey == msgKey) {
                r.keys = ks;
                r.links = ls;
                r.author = author;
                r.stamp = Math.max(r.stamp, stamp);
                return;
            }
        }
        records.add(new Record(REC_ENDORSE, stamp, msgKey, author, ks, ls));
        evictRecords(REC_ENDORSE);
    }

    private void addNotAd(long stamp, LuminaAdShield.Key[] keys, long[] links) {
        LuminaAdShield.Key[] ks = orderedRecordKeys(Arrays.asList(keys));
        long[] ls = capLinks(links);
        for (Record r : records) {
            if (r.kind == REC_NOTAD && Arrays.equals(r.keys, ks) && Arrays.equals(r.links, ls)) {
                r.stamp = Math.max(r.stamp, stamp);
                return;
            }
        }
        records.add(new Record(REC_NOTAD, stamp, 0, ks, ls));
        evictRecords(REC_NOTAD);
    }

    /** Expiry (SPEC §8.2): rows 8 days after max(date, seen), records 90 days, handles 30 days. */
    public void compact(long now) {
        boolean expired = false;
        boolean changed = false;
        for (Iterator<Row> it = rows.values().iterator(); it.hasNext(); ) {
            Row r = it.next();
            if (now - Math.max(r.date, r.seen) > LuminaAdShield.EVICT_AFTER) {
                it.remove();
                if (r.live()) {
                    byMsg.remove(r.msgId);
                }
                expired = true;
            }
        }
        for (Iterator<Record> it = records.iterator(); it.hasNext(); ) {
            if (now - it.next().stamp > LuminaAdShield.RECORD_TTL) {
                it.remove();
                changed = true;
            }
        }
        for (Iterator<HandleInfo> it = handles.values().iterator(); it.hasNext(); ) {
            HandleInfo hi = it.next();
            if (now - Math.max(hi.last, hi.seen) > LuminaAdShield.HANDLE_TTL) {
                it.remove();
                changed = true;
            }
        }
        if (expired) {
            version++;
        }
        if (expired || changed) {
            mutations++;
        }
    }

    private void enforceCaps() {
        while (rows.size() > maxRows) {
            Row victim = null;
            long best = 0;
            for (Row r : rows.values()) {          // ascending rid: first minimum = smallest rid
                long t = Math.max(r.date, r.seen);
                if (victim == null || t < best) {
                    victim = r;
                    best = t;
                }
            }
            rows.remove(victim.rid);
            if (victim.live()) {
                byMsg.remove(victim.msgId);
            }
        }
        while (handles.size() > maxHandles) {
            Long victim = null;
            long best = 0;
            for (Map.Entry<Long, HandleInfo> e : handles.entrySet()) {
                long t = Math.max(e.getValue().last, e.getValue().seen);
                if (victim == null || t < best
                        || (t == best && AdShieldTables.compareUnsigned(e.getKey(), victim) < 0)) {
                    victim = e.getKey();
                    best = t;
                }
            }
            handles.remove(victim);
        }
    }

    // =====================================================================================
    // Decide (SPEC §6)
    // =====================================================================================

    View view(LuminaAdShield.ChatContext ctx, long now) {
        long[] mh = ctx.memberHashes(key);
        View v = cache;
        if (v != null && v.mutations == mutations && v.now == now && Arrays.equals(v.memberHashes, mh)) {
            return v;
        }
        v = buildView(ctx.memberHashSet(key), mh, now);
        cache = v;
        return v;
    }

    private static final class Item {
        final Row row;
        final LuminaAdShield.Resolved res;

        Item(Row row, LuminaAdShield.Resolved res) {
            this.row = row;
            this.res = res;
        }
    }

    private static final LuminaAdShield.KnownFn NONE_KNOWN = new LuminaAdShield.KnownFn() {
        @Override
        public boolean isKnown(long handleHash) {
            return false;
        }
    };

    private View buildView(final HashSet<Long> ctxKnown, long[] mh, long now) {
        View v = new View();
        v.mutations = mutations;
        v.now = now;
        v.memberHashes = mh;
        ArrayList<ArrayList<Item>> groups = new ArrayList<ArrayList<Item>>();
        HashMap<Long, ArrayList<Item>> albumGroups = new HashMap<Long, ArrayList<Item>>();
        for (Row r : rows.values()) {
            if (now - Math.max(r.date, r.seen) > LuminaAdShield.EVICT_AFTER) {
                continue;
            }
            LuminaAdShield.KnownFn known = NONE_KNOWN;
            if (r.pending.length > 0) {
                final long d = r.date;
                known = new LuminaAdShield.KnownFn() {
                    @Override
                    public boolean isKnown(long h) {
                        if (ctxKnown.contains(h)) {
                            return true;
                        }
                        HandleInfo hi = handles.get(h);
                        return hi != null && hi.first <= d - LuminaAdShield.KNOWN_MEMBER_MIN_AGE;
                    }
                };
            }
            LuminaAdShield.Resolved res = LuminaAdShield.resolve(r.flags, r.keys, r.links, r.pending, known);
            ArrayList<Item> g;
            if (r.album != 0) {
                g = albumGroups.get(r.album);
                if (g == null) {
                    g = new ArrayList<Item>(2);
                    albumGroups.put(r.album, g);
                    groups.add(g);
                }
            } else {
                g = new ArrayList<Item>(1);
                groups.add(g);
            }
            g.add(new Item(r, res));
        }
        for (int gi = 0; gi < groups.size(); gi++) {
            ArrayList<Item> items = groups.get(gi);
            Unit u = new Unit();
            u.idx = gi;
            u.rows = new Row[items.size()];
            Row first = items.get(0).row;
            u.album = first.album != 0;
            int flags = 0;
            long date = Long.MAX_VALUE;
            Row minLive = null;
            if (items.size() == 1) {
                Item it = items.get(0);
                u.rows[0] = it.row;
                flags = it.res.flags;
                date = it.row.date;
                if (it.row.live()) {
                    minLive = it.row;
                }
                u.keys = distinctKeys(it.res.keys);
                u.links = distinctLinks(it.res.links);
            } else {
                LinkedHashSet<LuminaAdShield.Key> ks = new LinkedHashSet<LuminaAdShield.Key>();
                LinkedHashSet<Long> ls = new LinkedHashSet<Long>();
                for (int i = 0; i < items.size(); i++) {
                    Item it = items.get(i);
                    u.rows[i] = it.row;
                    flags |= it.res.flags;
                    date = Math.min(date, it.row.date);
                    if (it.row.live() && (minLive == null || it.row.msgId < minLive.msgId)) {
                        minLive = it.row;
                    }
                    ks.addAll(Arrays.asList(it.res.keys));
                    for (long l : it.res.links) {
                        ls.add(l);
                    }
                }
                u.keys = ks.toArray(new LuminaAdShield.Key[ks.size()]);
                u.links = new long[ls.size()];
                int i = 0;
                for (Long l : ls) {
                    u.links[i++] = l;
                }
            }
            u.live = minLive != null;
            if (u.live) {
                flags &= ~LuminaAdShield.F_ANON;
            }
            if ((flags & LuminaAdShield.STRONG_MASK) != 0) {
                flags &= ~LuminaAdShield.F_MEDIA_ONLY;     // album: any strong photo -> not media-only
            }
            u.flags = flags;
            u.date = date;
            u.sender = u.live ? minLive.sender : 0;
            u.minMsgId = u.live ? minLive.msgId : 0;
            long minHash = 0;
            int nBlocks = 0;
            for (int i = 0; i < u.keys.length; i++) {
                if (i == 0 || AdShieldTables.compareUnsigned(u.keys[i].hash, minHash) < 0) {
                    minHash = u.keys[i].hash;
                }
                if (u.keys[i].kind == LuminaAdShield.KIND_BLOCK) {
                    nBlocks++;
                }
            }
            u.minKeyHash = minHash;
            u.nBlocks = nBlocks;
            v.units.add(u);
            if (u.album) {
                v.byAlbum.put(first.album, u);
            } else {
                v.byRid.put(first.rid, u);
            }
            for (LuminaAdShield.Key k : u.keys) {
                v.index.add(k.kind, k.hash, u.idx);
            }
        }
        v.index.build();
        int n = v.units.size();
        v.unitArr = v.units.toArray(new Unit[n]);
        v.mark = new int[n];
        v.v2Mark = new int[n];
        v.paraGen = new int[n];
        v.paraCnt = new int[n];
        v.blockGen = new int[n];
        v.blockCnt = new int[n];
        for (Record r : records) {
            if (now - r.stamp <= LuminaAdShield.RECORD_TTL) {
                v.liveRecords.add(r);
            }
        }
        for (int ri = 0; ri < v.liveRecords.size(); ri++) {
            for (LuminaAdShield.Key k : distinctKeys(v.liveRecords.get(ri).keys)) {
                v.recordIndex.add(k.kind, k.hash, ri);
                if (LuminaAdShield.isVarKind(k.kind)) {
                    v.recordVarIndex.add(LuminaAdShield.KIND_VAR, k.hash, ri);
                }
            }
        }
        v.recordIndex.build();
        v.recordVarIndex.build();
        return v;
    }

    private static LuminaAdShield.Key[] distinctKeys(LuminaAdShield.Key[] keys) {
        for (int i = 1; i < keys.length; i++) {
            for (int j = 0; j < i; j++) {
                if (keys[i].equals(keys[j])) {
                    return new LinkedHashSet<LuminaAdShield.Key>(Arrays.asList(keys)).toArray(new LuminaAdShield.Key[0]);
                }
            }
        }
        return keys;
    }

    private static long[] distinctLinks(long[] links) {
        for (int i = 1; i < links.length; i++) {
            for (int j = 0; j < i; j++) {
                if (links[i] == links[j]) {
                    LinkedHashSet<Long> s = new LinkedHashSet<Long>();
                    for (long l : links) {
                        s.add(l);
                    }
                    long[] out = new long[s.size()];
                    int k = 0;
                    for (Long l : s) {
                        out[k++] = l;
                    }
                    return out;
                }
            }
        }
        return links;
    }

    Unit unitOf(View v, int msgId) {
        Long rid = byMsg.get(msgId);
        if (rid == null) {
            return null;
        }
        Row r = rows.get(rid);
        if (r == null) {
            return null;
        }
        return r.album != 0 ? v.byAlbum.get(r.album) : v.byRid.get(r.rid);
    }

    static int compareOrder(Unit a, Unit b) {
        if (a.date != b.date) {
            return a.date < b.date ? -1 : 1;
        }
        if (a.live != b.live) {
            return a.live ? 1 : -1;
        }
        if (a.minMsgId != b.minMsgId) {
            return a.minMsgId < b.minMsgId ? -1 : 1;
        }
        return AdShieldTables.compareUnsigned(a.minKeyHash, b.minKeyHash);
    }

    private static final Comparator<Unit> BY_ORDER = new Comparator<Unit>() {
        @Override
        public int compare(Unit a, Unit b) {
            int c = compareOrder(a, b);
            if (c != 0) {
                return c;
            }
            return a.idx < b.idx ? -1 : (a.idx == b.idx ? 0 : 1);
        }
    };

    private static final Comparator<Unit> BY_DATE = new Comparator<Unit>() {
        @Override
        public int compare(Unit a, Unit b) {
            return a.date < b.date ? -1 : (a.date == b.date ? 0 : 1);
        }
    };

    /** G(m) by order, plus which of its units are in G2(m) ({@code v.v2Mark[u.idx] == gen}). */
    static final class Group {
        final ArrayList<Unit> units;
        final int gen;

        Group(ArrayList<Unit> units, int gen) {
            this.units = units;
            this.gen = gen;
        }
    }

    /** v3: VAR / BLOCK only link live units of the same (known) sender. */
    private static boolean sameSender(Unit u, Unit m) {
        return u.live && m.live && u.sender != 0 && u.sender == m.sender;
    }

    /**
     * G(m) (m included), by order. A unit is in G(m) when
     * <ul>
     *   <li>v2 (G2(m)): it shares an exact key (FWD MEDIA TEXT CJK TMPL CONTACT) or >= 2 paragraph keys with m;</li>
     *   <li>v3, only live units of m's sender: added / removed line (m's VAR = its VARL or VARS, or the other way
     *       round); replaced line (a shared VARS); block containment (>= 2 shared BLOCK keys and
     *       5 * shared >= 4 * min(block keys of both)).</li>
     * </ul>
     * Only the units sharing a key with m are visited (index lookups).
     */
    static Group group(View v, Unit m) {
        int gen = ++v.gen;
        ArrayList<Unit> hit = new ArrayList<Unit>();
        hit.add(m);
        v.mark[m.idx] = gen;
        v.v2Mark[m.idx] = gen;
        ArrayList<Unit> blockTouched = null;
        KeyIndex ix = v.index;
        Unit[] all = v.unitArr;
        for (LuminaAdShield.Key k : m.keys) {
            int kind = k.kind;
            if (kind == LuminaAdShield.KIND_VAR) {          // m's full text = the other one minus a line (m is shorter)
                varHit(v, m, LuminaAdShield.KIND_VARL, k.hash, gen, hit);
                varHit(v, m, LuminaAdShield.KIND_VARS, k.hash, gen, hit);
                continue;
            }
            if (kind == LuminaAdShield.KIND_VARL || kind == LuminaAdShield.KIND_VARS) {
                varHit(v, m, LuminaAdShield.KIND_VAR, k.hash, gen, hit);    // m minus a line = the other's full text
                if (kind == LuminaAdShield.KIND_VARS) {
                    varHit(v, m, LuminaAdShield.KIND_VARS, k.hash, gen, hit);   // replaced line: strict on both sides
                }
                continue;
            }
            int d = ix.find(kind, k.hash);
            if (d < 0) {
                continue;
            }
            for (int p = ix.start(d), e = ix.end(d); p < e; p++) {
                Unit u = all[ix.at(p)];
                if (kind == LuminaAdShield.KIND_BLOCK) {
                    if (v.blockGen[u.idx] != gen) {
                        v.blockGen[u.idx] = gen;
                        v.blockCnt[u.idx] = 0;
                        if (blockTouched == null) {
                            blockTouched = new ArrayList<Unit>();
                        }
                        blockTouched.add(u);
                    }
                    v.blockCnt[u.idx]++;
                    continue;
                }
                if (v.v2Mark[u.idx] == gen) {
                    continue;
                }
                boolean link;
                if (LuminaAdShield.isExactKind(kind)) {
                    link = true;
                } else {                                    // PARA
                    if (v.paraGen[u.idx] != gen) {
                        v.paraGen[u.idx] = gen;
                        v.paraCnt[u.idx] = 0;
                    }
                    link = ++v.paraCnt[u.idx] >= 2;
                }
                if (link) {
                    v.v2Mark[u.idx] = gen;
                    if (v.mark[u.idx] != gen) {
                        v.mark[u.idx] = gen;
                        hit.add(u);
                    }
                }
            }
        }
        if (blockTouched != null) {
            for (Unit u : blockTouched) {
                if (v.mark[u.idx] != gen && sameSender(u, m)
                        && LuminaAdShield.blockContained(v.blockCnt[u.idx], m.nBlocks, u.nBlocks)) {
                    v.mark[u.idx] = gen;
                    hit.add(u);
                }
            }
        }
        Collections.sort(hit, BY_ORDER);
        return new Group(hit, gen);
    }

    private static void varHit(View v, Unit m, int kind, long hash, int gen, ArrayList<Unit> hit) {
        KeyIndex ix = v.index;
        int d = ix.find(kind, hash);
        if (d < 0) {
            return;
        }
        for (int p = ix.start(d), e = ix.end(d); p < e; p++) {
            Unit u = v.unitArr[ix.at(p)];
            if (v.mark[u.idx] != gen && sameSender(u, m)) {
                v.mark[u.idx] = gen;
                hit.add(u);
            }
        }
    }

    /**
     * Overlap of m with each live record (SPEC §6.3), any sender. {@code exact}: shares a v2 exact key or >= 2
     * paragraph keys (= the v2 overlap). {@code tolerant} (v3): shares any VAR-family hash (across the three
     * kinds), or m has >= 2 BLOCK keys and 5 * (those in the record) >= 4 * (m's BLOCK keys). A tolerant
     * overlap can only protect m (endorsed / not an ad), never make it an impersonation.
     */
    private static void recordOverlaps(View v, Unit m, boolean[] exact, boolean[] tolerant) {
        int nr = v.liveRecords.size();
        if (nr == 0) {
            return;
        }
        int[] para = null;
        int[] blk = null;
        for (LuminaAdShield.Key k : m.keys) {
            int kind = k.kind;
            if (LuminaAdShield.isVarKind(kind)) {
                int d = v.recordVarIndex.find(LuminaAdShield.KIND_VAR, k.hash);
                if (d >= 0) {
                    for (int p = v.recordVarIndex.start(d), e = v.recordVarIndex.end(d); p < e; p++) {
                        tolerant[v.recordVarIndex.at(p)] = true;
                    }
                }
                continue;
            }
            int d = v.recordIndex.find(kind, k.hash);
            if (d < 0) {
                continue;
            }
            for (int p = v.recordIndex.start(d), e = v.recordIndex.end(d); p < e; p++) {
                int ri = v.recordIndex.at(p);
                if (kind == LuminaAdShield.KIND_BLOCK) {
                    if (blk == null) {
                        blk = new int[nr];
                    }
                    blk[ri]++;
                } else if (LuminaAdShield.isExactKind(kind)) {
                    exact[ri] = true;
                } else {                                    // PARA
                    if (para == null) {
                        para = new int[nr];
                    }
                    if (++para[ri] >= 2) {
                        exact[ri] = true;
                    }
                }
            }
        }
        if (blk != null) {
            for (int ri = 0; ri < nr; ri++) {
                if (LuminaAdShield.blockContained(blk[ri], m.nBlocks, m.nBlocks)) {
                    tolerant[ri] = true;
                }
            }
        }
    }

    private static boolean hasAnyKey(Unit u, List<LuminaAdShield.Key> ks) {
        for (LuminaAdShield.Key k : ks) {
            if (u.hasKey(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean subsetOf(long[] links, HashSet<Long> allowed) {
        for (long l : links) {
            if (!allowed.contains(l)) {
                return false;
            }
        }
        return true;
    }

    private static boolean subsetOf(long[] links, long[] allowed) {
        for (long l : links) {
            if (!contains(allowed, l)) {
                return false;
            }
        }
        return true;
    }

    private static void addAll(HashSet<Long> to, long[] links) {
        for (long l : links) {
            to.add(l);
        }
    }

    private static final class Candidate {
        final String name;
        final Rule rule;
        final List<Unit> pool;

        Candidate(String name, Rule rule, List<Unit> pool) {
            this.name = name;
            this.rule = rule;
            this.pool = pool;
        }
    }

    /** SPEC §6.10. Never throws for valid arguments; internal failures yield state "none". */
    public LuminaAdShield.Decision decide(int msgId, LuminaAdShield.ChatContext ctx, LuminaAdShield.Settings settings,
                                          long now) {
        LuminaAdShield.Settings st = settings != null ? settings : new LuminaAdShield.Settings();
        RuleSet rs = rules(st.sensitivity);
        try {
            return decideImpl(msgId, ctx, st, rs, now);
        } catch (Throwable t) {
            LuminaAdShield.log("adshield decide failed: " + t.getClass().getSimpleName());
            return new LuminaAdShield.Decision();
        }
    }

    private LuminaAdShield.Decision decideImpl(int msgId, LuminaAdShield.ChatContext ctx, LuminaAdShield.Settings st,
                                               RuleSet rs, long now) {
        LuminaAdShield.Decision res = new LuminaAdShield.Decision();
        if (!LuminaAdShield.eligibleChat(ctx)) {
            res.state = LuminaAdShield.Decision.INELIGIBLE;
            return res;
        }
        View v = view(ctx, now);
        Unit m = unitOf(v, msgId);
        if (m == null) {
            res.state = LuminaAdShield.Decision.UNTRACKED;
            return res;
        }
        if (!ctx.adminsKnown) {
            res.state = LuminaAdShield.Decision.PENDING;
            return res;
        }
        res.replyToMe = (m.flags & LuminaAdShield.F_REPLY_ME) != 0;
        // v3 §6.12: with the per-group option on, a privileged author's message (PRIV, never self) is no longer
        // endorsed by itself; only the same author's own repeats are counted. Option off: exactly v2.
        boolean privMode = st.foldPrivilegedRepeats && (m.flags & LuminaAdShield.F_PRIV) != 0
                && (m.flags & LuminaAdShield.F_SELF) == 0;
        if ((m.flags & LuminaAdShield.F_ENDORSING) != 0 && !privMode) {
            res.state = LuminaAdShield.Decision.ENDORSED;
            return res;
        }
        Group g = group(v, m);
        ArrayList<Unit> group = g.units;
        int nr = v.liveRecords.size();
        boolean[] recExact = new boolean[nr];
        boolean[] recTol = new boolean[nr];
        recordOverlaps(v, m, recExact, recTol);

        boolean impersonating = false;
        HashSet<Long> endorseLinks = null;
        if (privMode) {
            // Only another privileged author's endorsement protects m (another admin posted the same: kept).
            // The same author's own, and author-0 endorsements (self, pinned member, member forward) do not.
            for (int ri = 0; ri < nr; ri++) {
                Record r = v.liveRecords.get(ri);
                if (r.kind == REC_ENDORSE && r.author != 0 && r.author != m.sender && (recExact[ri] || recTol[ri])) {
                    res.state = LuminaAdShield.Decision.ENDORSED;
                    return res;
                }
            }
            for (Unit u : group) {
                if (u != m && u.live && (u.flags & LuminaAdShield.F_PRIV) != 0 && u.sender != m.sender) {
                    res.state = LuminaAdShield.Decision.ENDORSED;
                    return res;
                }
            }
        } else {
            // E1 = exact overlaps (v2): endorsed or impersonation as in v2. E2 = only tolerant overlaps (VAR /
            // BLOCK): endorsed when m's links are a subset of E2's links, otherwise nothing (never impersonation).
            boolean exactHit = false, tolHit = false;
            HashSet<Long> exactLinks = new HashSet<Long>();
            HashSet<Long> tolLinks = new HashSet<Long>();
            for (int ri = 0; ri < nr; ri++) {
                Record r = v.liveRecords.get(ri);
                if (r.kind != REC_ENDORSE) {
                    continue;
                }
                if (recExact[ri]) {
                    exactHit = true;
                    addAll(exactLinks, r.links);
                } else if (recTol[ri]) {
                    tolHit = true;
                    addAll(tolLinks, r.links);
                }
            }
            for (Unit u : group) {
                if (u != m && (u.flags & LuminaAdShield.F_ENDORSING) != 0) {
                    if (v.v2Mark[u.idx] == g.gen) {
                        exactHit = true;
                        addAll(exactLinks, u.links);
                    } else {
                        tolHit = true;
                        addAll(tolLinks, u.links);
                    }
                }
            }
            if (exactHit) {
                if (subsetOf(m.links, exactLinks)) {
                    res.state = LuminaAdShield.Decision.ENDORSED;
                    return res;
                }
                impersonating = true;
                endorseLinks = exactLinks;
            } else if (tolHit && subsetOf(m.links, tolLinks)) {
                res.state = LuminaAdShield.Decision.ENDORSED;
                return res;
            }
        }

        // not an ad (90 days): exact overlap (v2); or tolerant overlap and m's links within that record's links
        for (int ri = 0; ri < nr; ri++) {
            Record r = v.liveRecords.get(ri);
            if (r.kind == REC_NOTAD && (recExact[ri] || (recTol[ri] && subsetOf(m.links, r.links)))) {
                res.state = LuminaAdShield.Decision.NOT_AD;
                return res;
            }
        }

        // privileged mode: strong features trusted only by a pin / downgraded by the own channel still count
        boolean hasStrong = (m.flags & LuminaAdShield.STRONG_MASK) != 0
                || (privMode && (m.flags & LuminaAdShield.F_PRIV_STRONG) != 0);
        List<Unit> gc;
        String cls;
        if (impersonating) {
            ArrayList<Unit> others = new ArrayList<Unit>();
            for (Unit u : group) {
                if ((u.flags & LuminaAdShield.F_ENDORSING) == 0 && !subsetOf(u.links, endorseLinks)) {
                    others.add(u);       // incl. deleted anonymous impersonators (link hashes kept)
                }
            }
            gc = others;
            cls = LuminaAdShield.Decision.CLASS_STRONG;
        } else {
            if (privMode) {
                // privileged mode counts only the same author's live units (members' copies and anonymous units never)
                ArrayList<Unit> same = new ArrayList<Unit>();
                for (Unit u : group) {
                    if (u.live && u.sender == m.sender) {
                        same.add(u);
                    }
                }
                gc = same;
            } else {
                gc = group;
            }
            if (hasStrong) {
                cls = LuminaAdShield.Decision.CLASS_STRONG;
            } else {
                ArrayList<LuminaAdShield.Key> mf = new ArrayList<LuminaAdShield.Key>(2);
                for (LuminaAdShield.Key k : m.keys) {
                    if (k.kind == LuminaAdShield.KIND_MEDIA || k.kind == LuminaAdShield.KIND_FWD) {
                        mf.add(k);
                    }
                }
                boolean sharesMediaFwd = false;
                if (!mf.isEmpty()) {
                    for (Unit u : gc) {
                        if (u != m && hasAnyKey(u, mf)) {
                            sharesMediaFwd = true;
                            break;
                        }
                    }
                }
                if ((m.flags & LuminaAdShield.WEAK_MASK) != 0 || sharesMediaFwd) {
                    cls = LuminaAdShield.Decision.CLASS_WEAK;
                } else {
                    cls = LuminaAdShield.Decision.CLASS_PLAIN;
                }
            }
        }
        res.cls = impersonating ? LuminaAdShield.Decision.CLASS_IMPERSONATION : cls;
        int groupSize = 0, occurrence = 0;
        for (Unit u : gc) {
            if (Math.abs(u.date - m.date) <= D7) {
                groupSize++;
            }
            if (m.date - D7 <= u.date && compareOrder(u, m) <= 0) {
                occurrence++;
            }
        }
        res.groupSize = groupSize;
        res.occurrence = occurrence;

        ArrayList<Candidate> candidates = new ArrayList<Candidate>();
        if (cls == LuminaAdShield.Decision.CLASS_STRONG) {
            for (Rule r : rs.strong) {
                candidates.add(new Candidate(LuminaAdShield.Decision.RULE_STRONG, r, gc));
            }
        } else if (cls == LuminaAdShield.Decision.CLASS_WEAK) {
            for (Rule r : rs.weak) {
                candidates.add(new Candidate(LuminaAdShield.Decision.RULE_WEAK, r, gc));
            }
            Rule ss = rs.sameSender;
            if (ss != null && (m.flags & LuminaAdShield.F_MEDIA_ONLY) != 0) {
                ArrayList<LuminaAdShield.Key> mediaKeys = new ArrayList<LuminaAdShield.Key>(1);
                for (LuminaAdShield.Key k : m.keys) {
                    if (k.kind == LuminaAdShield.KIND_MEDIA) {
                        mediaKeys.add(k);
                    }
                }
                ArrayList<Unit> same = new ArrayList<Unit>();
                for (Unit u : gc) {
                    if (u.live && u.sender == m.sender && hasAnyKey(u, mediaKeys)) {
                        same.add(u);
                    }
                }
                candidates.add(new Candidate(LuminaAdShield.Decision.RULE_MEDIA_SAME_SENDER, ss, same));
            }
            ArrayList<LuminaAdShield.Key> fwdKeys = new ArrayList<LuminaAdShield.Key>(1);
            for (LuminaAdShield.Key k : m.keys) {
                if (k.kind == LuminaAdShield.KIND_FWD) {
                    fwdKeys.add(k);
                }
            }
            if (ss != null && !fwdKeys.isEmpty()) {
                ArrayList<Unit> same = new ArrayList<Unit>();
                for (Unit u : gc) {
                    if (u.live && u.sender == m.sender && hasAnyKey(u, fwdKeys)) {
                        same.add(u);
                    }
                }
                candidates.add(new Candidate(LuminaAdShield.Decision.RULE_FWD_SAME_SENDER, ss, same));
            }
        } else {
            Rule pr = rs.plain;
            if (pr != null && (m.flags & LuminaAdShield.F_PURE_TEXT) != 0 && (m.flags & LuminaAdShield.F_ACK) == 0) {
                ArrayList<Unit> counted = new ArrayList<Unit>();
                for (Unit u : gc) {
                    if ((u.flags & LuminaAdShield.F_ACK) == 0) {
                        counted.add(u);
                    }
                }
                candidates.add(new Candidate(LuminaAdShield.Decision.RULE_PLAIN, pr, counted));
            }
        }

        boolean protectedOriginal = false;
        Candidate triggered = null;
        for (Candidate c : candidates) {
            if (!windowSatisfied(c.pool, m, c.rule.window, c.rule.hits, c.rule.senders)) {
                continue;
            }
            // first-message protection (copied original is kept); not for impersonation clusters
            if (!impersonating && isFirst(c.pool, m, c.rule.window) && !repostedBySelf(c.pool, m, c.rule.window)) {
                protectedOriginal = true;
                continue;
            }
            triggered = c;
            break;
        }
        if (triggered == null) {
            res.state = protectedOriginal ? LuminaAdShield.Decision.ORIGINAL : LuminaAdShield.Decision.NONE;
            return res;
        }
        res.rule = triggered.name;

        // per-message exemptions (still counted)
        int f = m.flags;
        if ((f & LuminaAdShield.F_SELF) != 0) {
            res.state = LuminaAdShield.Decision.ENDORSED;
            return res;
        }
        if ((f & LuminaAdShield.F_CONTACT) != 0 && st.exemptContacts) {
            res.state = LuminaAdShield.Decision.EXEMPT;
            return res;
        }
        if ((f & LuminaAdShield.F_BOT) != 0 && st.exemptBots) {
            res.state = LuminaAdShield.Decision.EXEMPT;
            return res;
        }
        if ((f & (LuminaAdShield.F_REPLY_ME | LuminaAdShield.F_MENTION_ME)) != 0 && st.exemptReplies
                && !hasStrong && !impersonating
                && (f & LuminaAdShield.W_MASS_MENTION) == 0) {
            res.state = LuminaAdShield.Decision.EXEMPT;
            return res;
        }
        res.state = LuminaAdShield.Decision.FOLD;
        res.fold = true;
        return res;
    }

    /**
     * Sliding window by message date (SPEC §6.6): some t among pool dates in [m.date - W, m.date] such that
     * the units dated in [t, t + W] reach min hits and min distinct live senders. Two pointers, O(n log n).
     */
    static boolean windowSatisfied(List<Unit> pool, Unit m, long window, int minHits, int minSenders) {
        ArrayList<Unit> ds = new ArrayList<Unit>(pool.size() + 1);
        boolean hasM = false;
        for (Unit u : pool) {
            if (u == m) {
                hasM = true;
            }
            ds.add(u);
        }
        if (!hasM) {
            ds.add(m);
        }
        Collections.sort(ds, BY_DATE);
        int n = ds.size();
        HashMap<Long, int[]> counts = new HashMap<Long, int[]>();
        int distinct = 0;
        int j = 0;
        for (int i = 0; i < n; i++) {
            long t0 = ds.get(i).date;
            while (j < n && ds.get(j).date <= t0 + window) {
                Unit u = ds.get(j);
                if (u.live && u.sender != 0) {
                    int[] c = counts.get(u.sender);
                    if (c == null) {
                        c = new int[1];
                        counts.put(u.sender, c);
                    }
                    if (c[0]++ == 0) {
                        distinct++;
                    }
                }
                j++;
            }
            if ((i == 0 || ds.get(i - 1).date < t0) && m.date - window <= t0 && t0 <= m.date) {
                if (j - i >= minHits && distinct >= minSenders) {
                    return true;
                }
            }
            Unit u = ds.get(i);
            if (u.live && u.sender != 0) {
                int[] c = counts.get(u.sender);
                if (--c[0] == 0) {
                    distinct--;
                }
            }
        }
        return false;
    }

    static boolean isFirst(List<Unit> pool, Unit m, long window) {
        for (Unit u : pool) {
            if (u != m && m.date - window <= u.date && compareOrder(u, m) < 0) {
                return false;
            }
        }
        return true;
    }

    static boolean repostedBySelf(List<Unit> pool, Unit m, long window) {
        for (Unit u : pool) {
            if (u != m && u.live && u.sender == m.sender && compareOrder(u, m) > 0 && u.date <= m.date + window) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================================
    // Serialization (SPEC §8.3, little endian)
    // =====================================================================================

    private static final class Sink {
        byte[] buf = new byte[1024];
        int len;

        private void ensure(int n) {
            if (len + n > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
            }
        }

        void u8(int v) {
            ensure(1);
            buf[len++] = (byte) v;
        }

        void u16(int v) {
            ensure(2);
            buf[len++] = (byte) v;
            buf[len++] = (byte) (v >>> 8);
        }

        void u32(long v) {
            ensure(4);
            for (int i = 0; i < 4; i++) {
                buf[len++] = (byte) (v >>> (8 * i));
            }
        }

        void u64(long v) {
            ensure(8);
            for (int i = 0; i < 8; i++) {
                buf[len++] = (byte) (v >>> (8 * i));
            }
        }

        void bytes(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, buf, len, b.length);
            len += b.length;
        }

        byte[] toArray() {
            return Arrays.copyOf(buf, len);
        }
    }

    private byte[] body() {
        Sink b = new Sink();
        for (Row r : rows.values()) {               // ascending row id
            b.u32(r.msgId & 0xFFFFFFFFL);
            b.u64(r.album);
            b.u64(r.date);
            b.u64(r.seen);
            b.u64(r.sender);
            b.u32(r.flags & 0xFFFFFFFFL);
            b.u8(r.keys.length);
            for (LuminaAdShield.Key k : r.keys) {
                b.u8(k.kind);
                b.u64(k.hash);
            }
            b.u8(r.links.length);
            for (long h : r.links) {
                b.u64(h);
            }
            b.u8(r.pending.length);
            for (long h : r.pending) {
                b.u64(h);
            }
        }
        for (Record rec : records) {                // insertion order
            b.u8(rec.kind);
            b.u64(rec.stamp);
            b.u64(rec.msgKey);
            b.u64(rec.author);                      // v3
            b.u8(rec.keys.length);
            for (LuminaAdShield.Key k : rec.keys) {
                b.u8(k.kind);
                b.u64(k.hash);
            }
            b.u8(rec.links.length);
            for (long h : rec.links) {
                b.u64(h);
            }
        }
        long[] hs = new long[handles.size()];
        int i = 0;
        for (Long h : handles.keySet()) {
            hs[i++] = h;
        }
        LuminaAdShield.sortUnsigned(hs);            // by hash unsigned
        for (long h : hs) {
            HandleInfo hi = handles.get(h);
            b.u64(h);
            b.u64(hi.first);
            b.u64(hi.last);
            b.u64(hi.seen);
        }
        return b.toArray();
    }

    public byte[] serialize() {
        byte[] body = body();
        Sink h = new Sink();
        h.bytes(MAGIC);
        h.u16(LuminaAdShield.FORMAT_VERSION);
        h.u16(LuminaAdShield.ALGO_VERSION);
        h.u32(AdShieldTables.TABLES_VERSION_U32 & 0xFFFFFFFFL);
        h.u32(0);
        h.u64(AdShieldTables.keyedHash(key, "check", "LGAS"));
        h.u64(AdShieldTables.keyedHash(key, "chat", Long.toString(chatId)));
        h.u32(rows.size());
        h.u32(records.size());
        h.u32(handles.size());
        h.u32(nextRid & 0xFFFFFFFFL);
        h.u64(AdShieldTables.keyedHashBytes(key, "body", body));
        h.bytes(body);
        return h.toArray();
    }

    /** Pre-allocated: thrown on truncated input (no per-throw stack trace). */
    private static final class Truncated extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final Truncated TRUNCATED = new Truncated();

    private static final class Reader {
        final byte[] b;
        int off;

        Reader(byte[] b, int off) {
            this.b = b;
            this.off = off;
        }

        private void need(int n) throws Truncated {
            if (n < 0 || off + n > b.length || off + n < 0) {
                throw TRUNCATED;
            }
        }

        int u8() throws Truncated {
            need(1);
            return b[off++] & 0xFF;
        }

        int u16() throws Truncated {
            need(2);
            int v = (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
            off += 2;
            return v;
        }

        long u32() throws Truncated {
            need(4);
            long v = 0;
            for (int i = 3; i >= 0; i--) {
                v = (v << 8) | (b[off + i] & 0xFFL);
            }
            off += 4;
            return v;
        }

        long u64() throws Truncated {
            need(8);
            long v = 0;
            for (int i = 7; i >= 0; i--) {
                v = (v << 8) | (b[off + i] & 0xFFL);
            }
            off += 8;
            return v;
        }
    }

    private static boolean inRange(long v) {
        return v >= 0 && v <= LuminaAdShield.MAX_DATE;
    }

    /**
     * Returns null (caller discards the file and starts over) on any SPEC §8.3 rejection: short file,
     * magic / format / algo / tables version / keyCheck / chatTag / bodyHash mismatch, count limits,
     * out-of-range dates, first > last, msgId > 2^31-1, anon/msgId/sender inconsistencies, duplicate
     * msgId or handle, reserved flag bits (17, 31), PRIV without ENDORSING, PRIV_STRONG without PRIV,
     * per-row / per-record list limits, bad kinds (not 1..11), a not-ad record with a msgKey or authorKey,
     * truncation or trailing bytes.
     */
    public static LuminaAdShieldStore deserialize(byte[] data, long chatId, byte[] key) {
        return deserialize(data, chatId, key, null);
    }

    static LuminaAdShieldStore deserialize(byte[] data, long chatId, byte[] key, LuminaAdShieldAccount owner) {
        try {
            return deserializeImpl(data, chatId, key, owner);
        } catch (Truncated e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LuminaAdShieldStore deserializeImpl(byte[] data, long chatId, byte[] key, LuminaAdShieldAccount owner)
            throws Truncated {
        if (data == null || data.length < HEADER_SIZE || key == null || key.length != 16) {
            return null;
        }
        Reader h = new Reader(data, 0);
        for (int i = 0; i < 4; i++) {
            if (h.u8() != (MAGIC[i] & 0xFF)) {
                return null;
            }
        }
        int fv = h.u16();
        int av = h.u16();
        long tv = h.u32();
        h.u32();                                    // reserved (not checked, as in the reference)
        long check = h.u64();
        long chatTag = h.u64();
        long nRows = h.u32();
        long nRec = h.u32();
        long nHandles = h.u32();
        long nextRid = h.u32();
        long bodyHash = h.u64();
        if (fv != LuminaAdShield.FORMAT_VERSION || av != LuminaAdShield.ALGO_VERSION
                || tv != (AdShieldTables.TABLES_VERSION_U32 & 0xFFFFFFFFL)) {
            return null;
        }
        if (check != AdShieldTables.keyedHash(key, "check", "LGAS")
                || chatTag != AdShieldTables.keyedHash(key, "chat", Long.toString(chatId))) {
            return null;
        }
        if (bodyHash != AdShieldTables.keyedHashBytes(key, "body", Arrays.copyOfRange(data, HEADER_SIZE, data.length))) {
            return null;
        }
        if (nRows > LuminaAdShield.MAX_ROWS
                || nRec > LuminaAdShield.MAX_ENDORSE_RECORDS + LuminaAdShield.MAX_NOTAD_RECORDS
                || nHandles > LuminaAdShield.MAX_HANDLES) {
            return null;
        }
        LuminaAdShieldStore cs = new LuminaAdShieldStore(chatId, key, owner);
        Reader r = new Reader(data, HEADER_SIZE);
        long rid = 0;
        for (long i = 0; i < nRows; i++) {
            long msgId = r.u32();
            long album = r.u64();
            long date = r.u64();
            long seen = r.u64();
            long sender = r.u64();
            long flags = r.u32();
            if (msgId > LuminaAdShield.MAX_MSG_ID || !inRange(date) || !inRange(seen)
                    || (flags & ~(LuminaAdShield.STORED_FLAGS_MASK & 0xFFFFFFFFL)) != 0) {
                return null;
            }
            boolean anonFlag = (flags & LuminaAdShield.F_ANON) != 0;
            if ((msgId == 0) != anonFlag || (msgId == 0 && sender != 0)) {
                return null;
            }
            if ((flags & LuminaAdShield.F_PRIV) != 0 && (flags & LuminaAdShield.F_ENDORSING) == 0) {
                return null;
            }
            if ((flags & LuminaAdShield.F_PRIV_STRONG) != 0 && (flags & LuminaAdShield.F_PRIV) == 0) {
                return null;
            }
            int nk = r.u8();
            if (nk > LuminaAdShield.MAX_ROW_KEYS) {
                return null;
            }
            LuminaAdShield.Key[] keys = new LuminaAdShield.Key[nk];
            for (int k = 0; k < nk; k++) {
                int kind = r.u8();
                long hash = r.u64();
                if (!LuminaAdShield.isValidKind(kind)) {
                    return null;
                }
                keys[k] = new LuminaAdShield.Key(kind, hash);
            }
            int nl = r.u8();
            if (nl > LuminaAdShield.MAX_LINKS_PER_ROW) {
                return null;
            }
            long[] links = new long[nl];
            for (int k = 0; k < nl; k++) {
                links[k] = r.u64();
            }
            int np = r.u8();
            if (np > LuminaAdShield.MAX_PENDING_PER_ROW) {
                return null;
            }
            long[] pending = new long[np];
            for (int k = 0; k < np; k++) {
                pending[k] = r.u64();
            }
            rid++;
            Row row = new Row();
            row.rid = rid;
            row.msgId = (int) msgId;
            row.album = album;
            row.date = date;
            row.seen = seen;
            row.sender = sender;
            row.flags = (int) flags;
            row.keys = keys;
            row.links = links;
            row.pending = pending;
            cs.rows.put(rid, row);
            if (msgId != 0) {
                if (cs.byMsg.containsKey((int) msgId)) {
                    return null;
                }
                cs.byMsg.put((int) msgId, rid);
            }
        }
        cs.nextRid = Math.max(nextRid, rid + 1);
        int endorse = 0, notAd = 0;
        for (long i = 0; i < nRec; i++) {
            int kind = r.u8();
            long stamp = r.u64();
            long msgKey = r.u64();
            long author = r.u64();
            if ((kind != REC_ENDORSE && kind != REC_NOTAD) || !inRange(stamp)
                    || (kind == REC_NOTAD && (author != 0 || msgKey != 0))) {
                return null;
            }
            if (kind == REC_ENDORSE) {
                endorse++;
            } else {
                notAd++;
            }
            int nk = r.u8();
            if (nk > LuminaAdShield.MAX_RECORD_KEYS) {
                return null;
            }
            LuminaAdShield.Key[] keys = new LuminaAdShield.Key[nk];
            for (int k = 0; k < nk; k++) {
                int kk = r.u8();
                long hash = r.u64();
                if (!LuminaAdShield.isValidKind(kk)) {
                    return null;
                }
                keys[k] = new LuminaAdShield.Key(kk, hash);
            }
            int nl = r.u8();
            if (nl > LuminaAdShield.MAX_RECORD_LINKS) {
                return null;
            }
            long[] links = new long[nl];
            for (int k = 0; k < nl; k++) {
                links[k] = r.u64();
            }
            cs.records.add(new Record(kind, stamp, msgKey, author, keys, links));
        }
        if (endorse > LuminaAdShield.MAX_ENDORSE_RECORDS || notAd > LuminaAdShield.MAX_NOTAD_RECORDS) {
            return null;
        }
        for (long i = 0; i < nHandles; i++) {
            long hh = r.u64();
            long first = r.u64();
            long last = r.u64();
            long seen = r.u64();
            if (!inRange(first) || !inRange(last) || !inRange(seen) || first > last || cs.handles.containsKey(hh)) {
                return null;
            }
            cs.handles.put(hh, new HandleInfo(first, last, seen));
        }
        if (r.off != data.length) {
            return null;
        }
        return cs;
    }
}
