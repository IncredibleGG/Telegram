/*
 * LuminaGram - AdShield ("隱藏重複廣告") similar-image hook (SPEC §5.4), Android side.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.messenger.lumina;

import android.graphics.Bitmap;

import org.telegram.messenger.ImageLoader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Same sender re-posting a similar photo (SPEC §5.4). The core only matches identical media ids; before
 * {@code observe} the platform replaces the media id of a photo with the id of an earlier photo of the
 * same sender when their 64-bit dHash (from the inline stripped thumbnail) differs in at most 8 bits and
 * the aspect-ratio bucket is equal. "First seen wins": every entry keeps the canonical id it was mapped
 * to, so re-observing a message always yields the same id and a cluster never splits.
 *
 * <p>Per chat at most {@link #MAX_PER_CHAT} entries and 7 days. Each entry holds only keyed hashes, the
 * dHash, the bucket, photo ids and the message date: {@code (H("sender", id), dhash, bucket, photoId,
 * canonicalId, date)}. Files live in their own root next to the engine root
 * ({@code filesDir/luminagram_adshield_img/<hex(H("acct", userId))>/<hex(H("chat", chatId))>.dh}), are
 * excluded from backups, and are deleted with ForgetAll, "clear local data" and the panic wipe.
 *
 * <p>Engine thread only.
 */
final class LuminaAdShieldImages {

    static final int MAX_PER_CHAT = 500;
    static final long WINDOW = 7 * LuminaAdShield.DAY;
    static final int MAX_HAMMING = 8;
    static final int MAX_LOADED = 8;
    static final long TOTAL_CAP_BYTES = 2L << 20;
    static final String EXT = ".dh";

    private static final byte[] MAGIC = {'L', 'G', 'D', 'H'};
    private static final int VERSION = 1;
    private static final int HEADER = 36;
    private static final int ENTRY = 8 + 8 + 1 + 8 + 8 + 8;

    static final class Entry {
        long sender;
        long dhash;
        int bucket;
        long photoId;
        long canonId;
        long date;
    }

    private static final class Table {
        final ArrayList<Entry> entries = new ArrayList<Entry>();
        boolean dirty;
    }

    private final File root;
    private final byte[] key;
    private final LuminaAdShieldAccount.FileOps ops;
    private final LinkedHashMap<String, Table> loaded = new LinkedHashMap<String, Table>(16, 0.75f, true);

    LuminaAdShieldImages(File root, byte[] key, LuminaAdShieldAccount.FileOps ops) {
        this.root = root;
        this.key = key.clone();
        this.ops = ops != null ? ops : LuminaAdShieldAccount.JAVA_IO;
    }

    String dirName(long userId) {
        return AdShieldTables.hex64(AdShieldTables.keyedHash(key, "acct", Long.toString(userId)));
    }

    private String fileName(long chatId) {
        return AdShieldTables.hex64(AdShieldTables.keyedHash(key, "chat", Long.toString(chatId))) + EXT;
    }

    private long keyCheck() {
        return AdShieldTables.keyedHash(key, "check", "LGDH");
    }

    // =====================================================================================
    // dHash
    // =====================================================================================

    /**
     * 64-bit dHash of a stripped inline thumbnail (9x8 luminance, left &lt; right). Returns 0 when the
     * thumbnail cannot be decoded or carries too little information to compare (near-uniform images).
     */
    static long dhash(byte[] stripped) {
        if (stripped == null || stripped.length < 3) {
            return 0;
        }
        Bitmap b = ImageLoader.getStrippedPhotoBitmap(stripped, null);
        if (b == null || b.getWidth() < 2 || b.getHeight() < 2) {
            return 0;       // includes ImageLoader's shared 1x1 fallback, which must never be recycled
        }
        Bitmap s = null;
        try {
            s = Bitmap.createScaledBitmap(b, 9, 8, true);
            int[] px = new int[9 * 8];
            s.getPixels(px, 0, 9, 0, 0, 9, 8);
            long h = 0;
            int bit = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (luma(px[y * 9 + x]) < luma(px[y * 9 + x + 1])) {
                        h |= 1L << bit;
                    }
                    bit++;
                }
            }
            int ones = Long.bitCount(h);
            return ones <= 2 || ones >= 62 ? 0 : h;
        } finally {
            if (s != null && s != b) {
                s.recycle();
            }
            b.recycle();
        }
    }

    private static int luma(int c) {
        return ((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114;
    }

    /** Aspect-ratio bucket 0..16 (steps of 2^(1/4), about 19 %). */
    static int bucket(int w, int h) {
        if (w <= 0 || h <= 0) {
            return 8;
        }
        double b = Math.log((double) w / (double) h) / Math.log(2) * 4.0;
        long r = Math.round(b);
        return (int) (Math.max(-8, Math.min(8, r)) + 8);
    }

    // =====================================================================================
    // Matching
    // =====================================================================================

    /**
     * The photo id to give the engine for this photo: the canonical id of the closest earlier photo of the
     * same sender (Hamming &lt;= 8, same bucket, dates within 7 days), else the photo's own id. Records the
     * photo so later re-posts map to the same id.
     */
    long canonical(String dir, long chatId, long senderKey, long dhash, int bucket, long photoId, long date, long now) {
        Table t = table(dir, chatId);
        prune(t, now);
        Entry best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Entry e : t.entries) {
            if (e.sender != senderKey) {
                continue;
            }
            if (e.photoId == photoId) {
                return e.canonId;       // this photo was seen before: always the same answer
            }
            if (e.bucket != bucket || Math.abs(e.date - date) > WINDOW) {
                continue;
            }
            int d = Long.bitCount(e.dhash ^ dhash);
            if (d > MAX_HAMMING) {
                continue;
            }
            if (best == null || d < bestDist
                    || d == bestDist && (e.date < best.date || e.date == best.date && e.canonId < best.canonId)) {
                best = e;
                bestDist = d;
            }
        }
        long canon = best != null ? best.canonId : photoId;
        if (now - date <= WINDOW) {
            Entry n = new Entry();
            n.sender = senderKey;
            n.dhash = dhash;
            n.bucket = bucket;
            n.photoId = photoId;
            n.canonId = canon;
            n.date = date;
            t.entries.add(n);
            while (t.entries.size() > MAX_PER_CHAT) {
                int oldest = 0;
                for (int i = 1; i < t.entries.size(); i++) {
                    if (t.entries.get(i).date < t.entries.get(oldest).date) {
                        oldest = i;
                    }
                }
                t.entries.remove(oldest);
            }
            t.dirty = true;
        }
        return canon;
    }

    private static void prune(Table t, long now) {
        for (Iterator<Entry> it = t.entries.iterator(); it.hasNext(); ) {
            if (now - it.next().date > WINDOW) {
                it.remove();
                t.dirty = true;
            }
        }
    }

    // =====================================================================================
    // Storage
    // =====================================================================================

    private Table table(String dir, long chatId) {
        String id = dir + "/" + chatId;
        Table t = loaded.get(id);
        if (t != null) {
            return t;
        }
        t = new Table();
        File f = new File(new File(root, dir), fileName(chatId));
        if (ops.exists(f)) {
            try {
                parse(ops.read(f, -1), chatId, t);
            } catch (IOException | RuntimeException e) {
                t.entries.clear();
            }
        }
        loaded.put(id, t);
        while (loaded.size() > MAX_LOADED) {
            Iterator<Map.Entry<String, Table>> it = loaded.entrySet().iterator();
            Map.Entry<String, Table> eldest = it.next();
            write(eldest.getKey(), eldest.getValue());
            it.remove();
        }
        return t;
    }

    private void parse(byte[] b, long chatId, Table t) {
        if (b.length < HEADER || b[0] != MAGIC[0] || b[1] != MAGIC[1] || b[2] != MAGIC[2] || b[3] != MAGIC[3]) {
            return;
        }
        if (u16(b, 4) != VERSION || u16(b, 6) != 0 || i64(b, 8) != keyCheck()
                || i64(b, 16) != AdShieldTables.keyedHash(key, "chat", Long.toString(chatId))) {
            return;
        }
        long count = i32(b, 24) & 0xFFFFFFFFL;
        if (count > MAX_PER_CHAT || b.length != HEADER + count * ENTRY) {
            return;
        }
        byte[] body = new byte[b.length - HEADER];
        System.arraycopy(b, HEADER, body, 0, body.length);
        if (i64(b, 28) != AdShieldTables.keyedHashBytes(key, "body", body)) {
            return;
        }
        ArrayList<Entry> out = new ArrayList<Entry>();
        int p = HEADER;
        for (int i = 0; i < count; i++) {
            Entry e = new Entry();
            e.sender = i64(b, p);
            e.dhash = i64(b, p + 8);
            e.bucket = b[p + 16] & 0xFF;
            e.photoId = i64(b, p + 17);
            e.canonId = i64(b, p + 25);
            e.date = i64(b, p + 33);
            p += ENTRY;
            if (e.bucket > 16 || e.date < 0 || e.date > LuminaAdShield.MAX_DATE) {
                return;
            }
            out.add(e);
        }
        t.entries.addAll(out);
    }

    private byte[] serialize(Table t, long chatId) {
        byte[] body = new byte[t.entries.size() * ENTRY];
        int p = 0;
        for (Entry e : t.entries) {
            put64(body, p, e.sender);
            put64(body, p + 8, e.dhash);
            body[p + 16] = (byte) e.bucket;
            put64(body, p + 17, e.photoId);
            put64(body, p + 25, e.canonId);
            put64(body, p + 33, e.date);
            p += ENTRY;
        }
        byte[] out = new byte[HEADER + body.length];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = (byte) VERSION;
        put64(out, 8, keyCheck());
        put64(out, 16, AdShieldTables.keyedHash(key, "chat", Long.toString(chatId)));
        int n = t.entries.size();
        out[24] = (byte) n;
        out[25] = (byte) (n >>> 8);
        out[26] = (byte) (n >>> 16);
        out[27] = (byte) (n >>> 24);
        put64(out, 28, AdShieldTables.keyedHashBytes(key, "body", body));
        System.arraycopy(body, 0, out, HEADER, body.length);
        return out;
    }

    private void write(String id, Table t) {
        if (!t.dirty) {
            return;
        }
        int slash = id.indexOf('/');
        String dir = id.substring(0, slash);
        long chatId = Long.parseLong(id.substring(slash + 1));
        File d = new File(root, dir);
        File f = new File(d, fileName(chatId));
        try {
            if (t.entries.isEmpty()) {
                if (ops.exists(f)) {
                    ops.delete(f);
                }
            } else {
                if (!ops.mkdirs(d)) {
                    return;
                }
                ops.writeAtomic(f, serialize(t, chatId));
            }
            t.dirty = false;
        } catch (IOException e) {
            LuminaAdShield.log("adshield image table write failed: " + e.getClass().getSimpleName());
        }
    }

    void flushChat(String dir, long chatId) {
        String id = dir + "/" + chatId;
        Table t = loaded.get(id);
        if (t != null) {
            write(id, t);
        }
    }

    void flushAll() {
        for (Map.Entry<String, Table> e : loaded.entrySet()) {
            write(e.getKey(), e.getValue());
        }
    }

    /** ForgetAll for one account: memory and files. */
    void forgetDir(String dir) {
        String prefix = dir + "/";
        for (Iterator<String> it = loaded.keySet().iterator(); it.hasNext(); ) {
            if (it.next().startsWith(prefix)) {
                it.remove();
            }
        }
        File d = new File(root, dir);
        if (ops.isDirectory(d) || ops.exists(d)) {
            ops.deleteRecursive(d);
        }
        removeRootIfEmpty();
    }

    /** "Clear local data" / panic: every table of every account. */
    void wipe() {
        loaded.clear();
        if (ops.isDirectory(root) || ops.exists(root)) {
            ops.deleteRecursive(root);
        }
    }

    /**
     * Startup: keep only the directories of active accounts and only files written with the current key;
     * temp files and anything else are removed.
     */
    void purge(Set<String> keepDirs) {
        if (!ops.isDirectory(root)) {
            if (ops.exists(root)) {
                ops.delete(root);
            }
            return;
        }
        String[] dirs = ops.list(root);
        if (dirs == null) {
            return;
        }
        long want = keyCheck();
        for (String d : dirs) {
            File p = new File(root, d);
            if (!keepDirs.contains(d) || !ops.isDirectory(p) || LuminaAdShieldAccount.isSymlink(p)) {
                ops.deleteRecursive(p);
                continue;
            }
            String[] names = ops.list(p);
            if (names != null) {
                for (String name : names) {
                    File f = new File(p, name);
                    if (!name.endsWith(EXT) || ops.isDirectory(f) || !headerMatches(f, want)) {
                        ops.deleteRecursive(f);
                    }
                }
            }
            String[] left = ops.list(p);
            if (left != null && left.length == 0) {
                ops.delete(p);
            }
        }
        removeRootIfEmpty();
    }

    private boolean headerMatches(File f, long want) {
        try {
            byte[] h = ops.read(f, 16);
            return h.length == 16 && h[0] == MAGIC[0] && h[1] == MAGIC[1] && h[2] == MAGIC[2] && h[3] == MAGIC[3]
                    && i64(h, 8) == want;
        } catch (IOException e) {
            return false;
        }
    }

    /** Size cap over all accounts, oldest file (mtime) first. */
    void enforceCap(long cap) {
        if (!ops.isDirectory(root)) {
            return;
        }
        final ArrayList<Object[]> files = new ArrayList<Object[]>();
        long total = 0;
        String[] dirs = ops.list(root);
        if (dirs == null) {
            return;
        }
        for (String d : dirs) {
            File p = new File(root, d);
            String[] names = ops.isDirectory(p) ? ops.list(p) : null;
            if (names == null) {
                continue;
            }
            for (String name : names) {
                File f = new File(p, name);
                if (name.endsWith(EXT) && !ops.isDirectory(f)) {
                    long size = ops.length(f);
                    files.add(new Object[]{ops.lastModified(f), f, size});
                    total += size;
                }
            }
        }
        if (total <= cap) {
            return;
        }
        Collections.sort(files, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                long ma = (Long) a[0], mb = (Long) b[0];
                return ma < mb ? -1 : (ma == mb ? 0 : 1);
            }
        });
        for (Object[] f : files) {
            if (total <= cap) {
                break;
            }
            if (ops.delete((File) f[1])) {
                total -= (Long) f[2];
            }
        }
        // tables still in memory may hold entries of deleted files; they are rewritten on the next flush
    }

    private void removeRootIfEmpty() {
        String[] left = ops.isDirectory(root) ? ops.list(root) : null;
        if (left != null && left.length == 0) {
            ops.delete(root);
        }
    }

    // =====================================================================================
    // Little-endian helpers
    // =====================================================================================

    private static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8;
    }

    private static int i32(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24;
    }

    private static long i64(byte[] b, int p) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[p + i] & 0xFFL);
        }
        return v;
    }

    private static void put64(byte[] b, int p, long v) {
        for (int i = 0; i < 8; i++) {
            b[p + i] = (byte) (v >>> (8 * i));
        }
    }
}
