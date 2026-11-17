/*
 * LuminaGram - AdShield ("隱藏重複廣告") account-level storage, Android port.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 *
 * Port of AccountStore / purge_orphans / wipe_root / enforce_total_cap in adshield/ref/adshield.py
 * (SPEC §8.1, §8.2, §8.4).
 */
package org.telegram.messenger.lumina;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Telegram account (keyed by userId, never by slot number). Owns the loaded chat stores and the
 * account directory {@code <root>/hex(H("acct", userId))/hex(H("chat", chatId)).bin}.
 *
 * <p>All file access goes through {@link FileOps} so the checker can run without the app; the
 * default implementation only uses {@code java.io} (minSdk 23, no {@code java.nio.file}).
 *
 * <p><b>Threading:</b> confined to the AdShield engine thread like {@link LuminaAdShieldStore}.
 * Callers must not keep {@link LuminaAdShieldStore} references across tasks: {@link #chat} may evict
 * (flush + drop) the least recently used chat when more than {@link LuminaAdShield#MAX_LOADED_CHATS}
 * are loaded.
 */
public final class LuminaAdShieldAccount {

    /** Minimal file-system seam (java.io only). Paths are plain {@link File}s. */
    public interface FileOps {
        /** Names in dir, or null when dir is not a readable directory. */
        String[] list(File dir);

        boolean isDirectory(File f);

        boolean exists(File f);

        /** Up to maxBytes from the start of the file (maxBytes < 0: whole file). */
        byte[] read(File f, int maxBytes) throws IOException;

        /** Write tmp ("&lt;target&gt;.tmp") + fsync + rename over target. */
        void writeAtomic(File target, byte[] data) throws IOException;

        boolean delete(File f);

        /** Deletes a file or a whole tree; symlinks are unlinked, never followed. */
        boolean deleteRecursive(File f);

        boolean mkdirs(File dir);

        long length(File f);

        long lastModified(File f);
    }

    /** Whole-file reads above this size are refused (treated as a corrupt file). */
    static final long MAX_FILE_BYTES = 16L << 20;

    /** Default {@link FileOps} on java.io.File. */
    public static final FileOps JAVA_IO = new FileOps() {
        @Override
        public String[] list(File dir) {
            return dir.list();
        }

        @Override
        public boolean isDirectory(File f) {
            return f.isDirectory();
        }

        @Override
        public boolean exists(File f) {
            return f.exists();
        }

        @Override
        public byte[] read(File f, int maxBytes) throws IOException {
            long len = f.length();
            if (maxBytes < 0 && len > MAX_FILE_BYTES) {
                throw new IOException("file too large");      // a valid chat file is ~2 MB at most
            }
            FileInputStream in = new FileInputStream(f);
            try {
                int cap = maxBytes >= 0 ? maxBytes : (int) len;
                byte[] buf = new byte[Math.max(cap, 0)];
                int n = 0;
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) {
                        break;
                    }
                    n += r;
                }
                if (maxBytes < 0 && in.read() >= 0) {
                    throw new IOException("file grew while reading");
                }
                if (n == buf.length) {
                    return buf;
                }
                byte[] out = new byte[n];
                System.arraycopy(buf, 0, out, 0, n);
                return out;
            } finally {
                in.close();
            }
        }

        @Override
        public void writeAtomic(File target, byte[] data) throws IOException {
            File tmp = new File(target.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("rename failed");
            }
        }

        @Override
        public boolean delete(File f) {
            return f.delete();
        }

        @Override
        public boolean deleteRecursive(File f) {
            if (!isSymlink(f) && f.isDirectory()) {
                String[] names = f.list();
                if (names != null) {
                    for (String n : names) {
                        deleteRecursive(new File(f, n));
                    }
                }
            }
            return f.delete() || !f.exists();
        }

        @Override
        public boolean mkdirs(File dir) {
            return dir.isDirectory() || dir.mkdirs();
        }

        @Override
        public long length(File f) {
            return f.length();
        }

        @Override
        public long lastModified(File f) {
            return f.lastModified();
        }
    };

    static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            File canonParentChild = parent == null ? f : new File(parent.getCanonicalFile(), f.getName());
            return !canonParentChild.getCanonicalFile().equals(canonParentChild.getAbsoluteFile());
        } catch (IOException e) {
            return true;    // be conservative: never recurse into something we cannot resolve
        }
    }

    public final long userId;
    private final byte[] key;
    private final File root;                // e.g. filesDir/luminagram_adshield; null = memory only
    private final FileOps ops;
    private final LinkedHashMap<Long, LuminaAdShieldStore> chats =
            new LinkedHashMap<Long, LuminaAdShieldStore>(16, 0.75f, true);   // access order (LRU)
    private final HashMap<Long, Long> savedMutations = new HashMap<Long, Long>();
    private boolean forgotten;

    public LuminaAdShieldAccount(long userId, byte[] key, File root, FileOps ops) {
        if (key == null || key.length != 16) {
            throw new IllegalArgumentException("key must be 16 bytes");
        }
        this.userId = userId;
        this.key = key.clone();
        this.root = root;
        this.ops = ops != null ? ops : JAVA_IO;
    }

    public LuminaAdShieldAccount(long userId, byte[] key, File root) {
        this(userId, key, root, JAVA_IO);
    }

    public boolean isForgotten() {
        return forgotten;
    }

    /** hex(H("acct", userId)): the per-install key makes the name unlinkable to the user id. */
    public String dirName() {
        return AdShieldTables.hex64(AdShieldTables.keyedHash(key, "acct", Long.toString(userId)));
    }

    public String chatFileName(long chatId) {
        return AdShieldTables.hex64(AdShieldTables.keyedHash(key, "chat", Long.toString(chatId))) + ".bin";
    }

    File accountDir() {
        return root == null ? null : new File(root, dirName());
    }

    /** Loaded chat count (safe to log). */
    public int loadedChatCount() {
        return chats.size();
    }

    /**
     * The chat's store, loading it from disk when present (any SPEC §8.3 rejection = start empty).
     * Creates no file by itself. Throws {@link LuminaAdShieldStore.ForgottenException} after forgetAll.
     */
    public LuminaAdShieldStore chat(long chatId) {
        if (forgotten) {
            throw new LuminaAdShieldStore.ForgottenException();
        }
        LuminaAdShieldStore cs = chats.get(chatId);
        if (cs != null) {
            return cs;
        }
        if (root != null) {
            File p = new File(accountDir(), chatFileName(chatId));
            if (ops.exists(p)) {
                try {
                    cs = LuminaAdShieldStore.deserialize(ops.read(p, -1), chatId, key, this);
                } catch (IOException e) {
                    cs = null;
                } catch (RuntimeException e) {
                    cs = null;
                }
            }
        }
        if (cs == null) {
            cs = new LuminaAdShieldStore(chatId, key, this);
        }
        chats.put(chatId, cs);
        savedMutations.put(chatId, cs.getMutations());
        evictIfNeeded();
        return cs;
    }

    private boolean isDirty(long chatId, LuminaAdShieldStore cs) {
        Long saved = savedMutations.get(chatId);
        return saved == null || saved != cs.getMutations();
    }

    /** SPEC §8.2: at most 8 chats in memory (only when backed by files; otherwise nothing could be reloaded). */
    private void evictIfNeeded() {
        if (root == null) {
            return;
        }
        while (chats.size() > LuminaAdShield.MAX_LOADED_CHATS) {
            Iterator<Map.Entry<Long, LuminaAdShieldStore>> it = chats.entrySet().iterator();
            Map.Entry<Long, LuminaAdShieldStore> eldest = it.next();
            if (isDirty(eldest.getKey(), eldest.getValue())) {
                writeChat(eldest.getKey(), eldest.getValue());
            }
            it.remove();
            savedMutations.remove(eldest.getKey());
        }
    }

    private boolean writeChat(long chatId, LuminaAdShieldStore cs) {
        File dir = accountDir();
        if (!ops.mkdirs(dir)) {
            return false;
        }
        try {
            ops.writeAtomic(new File(dir, chatFileName(chatId)), cs.serialize());
            savedMutations.put(chatId, cs.getMutations());
            return true;
        } catch (IOException e) {
            LuminaAdShield.log("adshield write failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Atomically writes every loaded chat that changed since it was loaded / last written.
     * Returns false when forgotten, memory-only, or a write failed (the debounce timer of the
     * integration step calls this; after forgetAll it must never recreate files).
     */
    public boolean flush() {
        if (forgotten || root == null) {
            return false;
        }
        boolean ok = true;
        for (Map.Entry<Long, LuminaAdShieldStore> e : new ArrayList<Map.Entry<Long, LuminaAdShieldStore>>(chats.entrySet())) {
            if (isDirty(e.getKey(), e.getValue())) {
                ok &= writeChat(e.getKey(), e.getValue());
            }
        }
        return ok;
    }

    /** Flush a single chat (per-chat 5 s debounce). */
    public boolean flushChat(long chatId) {
        if (forgotten || root == null) {
            return false;
        }
        LuminaAdShieldStore cs = chats.get(chatId);
        if (cs == null || !isDirty(chatId, cs)) {
            return true;
        }
        return writeChat(chatId, cs);
    }

    /**
     * ForgetAll (logout / account removal, SPEC §8.4): refuse further writes, drop memory, delete the
     * account directory, purge orphans. panic = true (emergency wipe): delete the whole root instead.
     * The integration step must cancel its debounce timers first; they would be no-ops anyway.
     */
    public void forgetAll(boolean panic) {
        forgotten = true;
        chats.clear();
        savedMutations.clear();
        if (root == null) {
            return;
        }
        if (panic) {
            wipeRoot(root, ops);
            return;
        }
        File d = accountDir();
        if (ops.isDirectory(d)) {
            ops.deleteRecursive(d);
        }
        purgeOrphans(root, key, ops);
    }

    public void forgetAll() {
        forgetAll(false);
    }

    /** Startup cleanup for this install's key. */
    public int purgeOrphans() {
        return root == null ? 0 : purgeOrphans(root, key, ops);
    }

    // =====================================================================================
    // Root-level helpers
    // =====================================================================================

    private static boolean keyCheckMatches(File f, long want, FileOps ops) {
        try {
            if (ops.isDirectory(f)) {
                return false;
            }
            byte[] head = ops.read(f, 24);
            if (head.length < 24 || head[0] != 'L' || head[1] != 'G' || head[2] != 'A' || head[3] != 'S') {
                return false;
            }
            long v = 0;
            for (int i = 23; i >= 16; i--) {
                v = (v << 8) | (head[i] & 0xFFL);
            }
            return v == want;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Startup (and ForgetAll): delete chat files whose keyCheck does not match the current key (left over
     * after a key loss / change), unreadable files, temp files and stray entries, then empty account
     * directories. Returns the number of entries removed.
     */
    public static int purgeOrphans(File root, byte[] key, FileOps ops) {
        if (root == null || !ops.isDirectory(root)) {
            return 0;
        }
        long want = AdShieldTables.keyedHash(key, "check", "LGAS");
        int removed = 0;
        String[] dirs = ops.list(root);
        if (dirs == null) {
            return 0;
        }
        for (String d : dirs) {
            File p = new File(root, d);
            if (!ops.isDirectory(p) || isSymlink(p)) {
                ops.delete(p);
                removed++;
                continue;
            }
            String[] names = ops.list(p);
            if (names != null) {
                for (String name : names) {
                    File fp = new File(p, name);
                    if (!name.endsWith(".bin") || !keyCheckMatches(fp, want, ops)) {
                        if (ops.isDirectory(fp)) {
                            ops.deleteRecursive(fp);
                        } else {
                            ops.delete(fp);
                        }
                        removed++;
                    }
                }
            }
            String[] left = ops.list(p);
            if (left != null && left.length == 0) {
                ops.delete(p);
            }
        }
        return removed;
    }

    /** Emergency wipe: the whole AdShield root (all accounts, orphans, temp files). */
    public static void wipeRoot(File root, FileOps ops) {
        if (root != null && ops.isDirectory(root)) {
            ops.deleteRecursive(root);
        }
    }

    /**
     * Total cap over all accounts (SPEC §8.2): while the sum of chat files exceeds cap, delete whole files
     * oldest-mtime first (mtime is refreshed by every atomic rename). Returns the number of files removed.
     */
    public static int enforceTotalCap(File root, long cap, FileOps ops) {
        if (root == null || !ops.isDirectory(root)) {
            return 0;
        }
        final ArrayList<Object[]> files = new ArrayList<Object[]>();   // {mtime, path, size}
        String[] dirs = ops.list(root);
        if (dirs == null) {
            return 0;
        }
        long total = 0;
        for (String d : dirs) {
            File p = new File(root, d);
            if (!ops.isDirectory(p)) {
                continue;
            }
            String[] names = ops.list(p);
            if (names == null) {
                continue;
            }
            for (String name : names) {
                File fp = new File(p, name);
                if (name.endsWith(".bin") && !ops.isDirectory(fp)) {
                    long size = ops.length(fp);
                    files.add(new Object[]{ops.lastModified(fp), fp.getPath(), size});
                    total += size;
                }
            }
        }
        Collections.sort(files, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                long ma = (Long) a[0], mb = (Long) b[0];
                if (ma != mb) {
                    return ma < mb ? -1 : 1;
                }
                return LuminaAdShield.compareCodePoints((String) a[1], (String) b[1]);
            }
        });
        int removed = 0;
        for (Object[] f : files) {
            if (total <= cap) {
                break;
            }
            if (ops.delete(new File((String) f[1]))) {
                total -= (Long) f[2];
                removed++;
            }
        }
        return removed;
    }
}
