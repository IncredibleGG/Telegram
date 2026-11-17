/*
 * LuminaGram - AdShield ("隱藏重複廣告"): settings, storage lifecycle and the engine queue.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.messenger.lumina;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.ScheduledFuture;

/**
 * Glue between the AdShield engine and the app (stage 1 of the Android integration):
 * <ul>
 *   <li>global settings (in the "luminagram" prefs) and per-group modes (in the separate
 *       "luminagram_adshield" prefs file, together with the Keystore-wrapped key; that file and both
 *       file roots are excluded from backups and device transfer);</li>
 *   <li>account objects keyed by userId, created only while the feature is effectively on for a
 *       logged-in account; 5 s debounced per-chat flushes; flush on background; total caps;</li>
 *   <li>ForgetAll on logout and when an account becomes fully inactive (global off and no group set to
 *       on), "clear local data", and the panic wipe;</li>
 *   <li>startup cleanup ({@code purgeOrphans}, directories of accounts that are gone or inactive).</li>
 * </ul>
 *
 * <p>Feature OFF (the default) and no files on disk: nothing here ever posts to the engine thread, so no
 * engine object, key, file or thread is created.
 *
 * <p>Threading: public static methods are UI-thread entry points unless noted. Everything named
 * {@code engine*} and the fields marked "engine thread" are confined to {@link LuminaAdShieldThread}.
 */
public final class LuminaAdShieldManager {

    private LuminaAdShieldManager() {
    }

    // ---- per-group modes ----
    public static final int MODE_DEFAULT = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_OFF = 2;

    // ---- global settings ("luminagram" prefs via LuminaConfig) ----
    public static final String KEY_ENABLED = "adShieldEnabled";
    public static final String KEY_SENSITIVITY = "adShieldSensitivity";
    public static final String KEY_EXEMPT_CONTACTS = "adShieldExemptContacts";
    public static final String KEY_EXEMPT_BOTS = "adShieldExemptBots";
    public static final String KEY_EXEMPT_REPLIES = "adShieldExemptReplies";
    public static final String KEY_TRIAL = "adShieldTrial";
    public static final String KEY_EXPLAINED = "adShieldExplained";

    // ---- own prefs file (excluded from backups) ----
    static final String PREFS_NAME = "luminagram_adshield";
    private static final String PREF_MODES = "modes";

    static final String ROOT_DIR = "luminagram_adshield";
    static final String IMG_DIR = "luminagram_adshield_img";
    static final long FLUSH_DELAY_MS = 5000;

    /** Why sessions are told to re-evaluate. */
    static final int CHANGE_SETTINGS = 1;
    static final int CHANGE_CLEARED = 2;

    // =====================================================================================
    // Settings (any thread: SharedPreferences reads are thread-safe)
    // =====================================================================================

    public static boolean isGloballyEnabled() {
        return LuminaConfig.getBoolean(KEY_ENABLED, false);
    }

    public static String getSensitivity() {
        String s = LuminaConfig.getString(KEY_SENSITIVITY, LuminaAdShield.SENS_STANDARD);
        for (String v : LuminaAdShield.SENSITIVITIES) {
            if (v.equals(s)) {
                return v;
            }
        }
        return LuminaAdShield.SENS_STANDARD;
    }

    public static boolean isExemptContacts() {
        return LuminaConfig.getBoolean(KEY_EXEMPT_CONTACTS, true);
    }

    public static boolean isExemptBots() {
        return LuminaConfig.getBoolean(KEY_EXEMPT_BOTS, true);
    }

    public static boolean isExemptReplies() {
        return LuminaConfig.getBoolean(KEY_EXEMPT_REPLIES, true);
    }

    /** 試運行: nothing is hidden; messages that would fold only get a small label. UI-only (SPEC §6.10). */
    public static boolean isTrial() {
        return LuminaConfig.getBoolean(KEY_TRIAL, false);
    }

    public static boolean isExplained() {
        return LuminaConfig.getBoolean(KEY_EXPLAINED, false);
    }

    public static void setExplained() {
        LuminaConfig.putBoolean(KEY_EXPLAINED, true);
    }

    /** A fresh engine settings object (callers own it). */
    static LuminaAdShield.Settings settings() {
        LuminaAdShield.Settings s = new LuminaAdShield.Settings(getSensitivity());
        s.exemptContacts = isExemptContacts();
        s.exemptBots = isExemptBots();
        s.exemptReplies = isExemptReplies();
        s.dryRun = isTrial();
        return s;
    }

    public static void setGloballyEnabled(boolean enabled) {
        LuminaConfig.putBoolean(KEY_ENABLED, enabled);
        onSettingsChanged();
    }

    public static void setSensitivity(String sensitivity) {
        LuminaConfig.putString(KEY_SENSITIVITY, sensitivity);
        onSettingsChanged();
    }

    public static void setExemptContacts(boolean v) {
        LuminaConfig.putBoolean(KEY_EXEMPT_CONTACTS, v);
        onSettingsChanged();
    }

    public static void setExemptBots(boolean v) {
        LuminaConfig.putBoolean(KEY_EXEMPT_BOTS, v);
        onSettingsChanged();
    }

    public static void setExemptReplies(boolean v) {
        LuminaConfig.putBoolean(KEY_EXEMPT_REPLIES, v);
        onSettingsChanged();
    }

    public static void setTrial(boolean v) {
        LuminaConfig.putBoolean(KEY_TRIAL, v);
        onSettingsChanged();
    }

    // =====================================================================================
    // Per-group modes: {"<slot>:<dialogId>": 1|2} in the AdShield prefs file
    // =====================================================================================

    private static SharedPreferences prefs() {
        try {
            return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject readModes() {
        SharedPreferences p = prefs();
        String raw = p != null ? p.getString(PREF_MODES, null) : null;
        if (raw != null && !raw.isEmpty()) {
            try {
                return new JSONObject(raw);
            } catch (Throwable ignore) {
            }
        }
        return new JSONObject();
    }

    private static void writeModes(JSONObject o) {
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        if (o.length() == 0) {
            if (p.contains(PREF_MODES)) {
                p.edit().remove(PREF_MODES).apply();
            }
        } else {
            p.edit().putString(PREF_MODES, o.toString()).apply();
        }
    }

    private static String modeKey(int slot, long dialogId) {
        return slot + ":" + dialogId;
    }

    public static int getChatMode(int slot, long dialogId) {
        int m = readModes().optInt(modeKey(slot, dialogId), MODE_DEFAULT);
        return m == MODE_ON || m == MODE_OFF ? m : MODE_DEFAULT;
    }

    /** Per-group mode (B). Turning the last "on" group back to default/off with global off forgets the account. */
    public static void setChatMode(int slot, long dialogId, int mode) {
        JSONObject o = readModes();
        String k = modeKey(slot, dialogId);
        try {
            if (mode == MODE_ON || mode == MODE_OFF) {
                o.put(k, mode);
            } else {
                o.remove(k);
            }
        } catch (Throwable ignore) {
        }
        writeModes(o);
        onSettingsChanged();
    }

    private static boolean hasModeOn(int slot) {
        JSONObject o = readModes();
        String prefix = slot + ":";
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith(prefix) && o.optInt(k, 0) == MODE_ON) {
                return true;
            }
        }
        return false;
    }

    private static void clearModes(int slot) {
        SharedPreferences p = prefs();
        if (p == null || !p.contains(PREF_MODES)) {
            return;
        }
        JSONObject o = readModes();
        String prefix = slot + ":";
        ArrayList<String> drop = new ArrayList<String>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (slot < 0 || k.startsWith(prefix)) {
                drop.add(k);
            }
        }
        for (String k : drop) {
            o.remove(k);
        }
        writeModes(o);
    }

    /** Global on and the group not set to off, or the group set to on (any thread). */
    public static boolean isActiveForChat(int slot, long dialogId) {
        int m = getChatMode(slot, dialogId);
        return m == MODE_ON || m == MODE_DEFAULT && isGloballyEnabled();
    }

    /** False = "fully inactive" for this account: global off and no group set to on. */
    public static boolean isAccountActive(int slot) {
        return isGloballyEnabled() || hasModeOn(slot);
    }

    // =====================================================================================
    // Sessions (UI thread)
    // =====================================================================================

    private static final ArrayList<LuminaAdShieldChatSession> sessions = new ArrayList<LuminaAdShieldChatSession>();

    static void addSession(LuminaAdShieldChatSession s) {
        sessions.add(s);
    }

    static void removeSession(LuminaAdShieldChatSession s) {
        sessions.remove(s);
    }

    private static void notifySessions(int slot, int change) {
        for (LuminaAdShieldChatSession s : new ArrayList<LuminaAdShieldChatSession>(sessions)) {
            if (slot < 0 || s.account == slot) {
                s.onManagerChange(change);
            }
        }
    }

    /** Every setting change: sessions re-evaluate, then fully inactive accounts are forgotten. */
    public static void onSettingsChanged() {
        notifySessions(-1, CHANGE_SETTINGS);
        reconcileAccounts();
    }

    private static void reconcileAccounts() {
        if (!engineUsed && !rootsExist()) {
            return;
        }
        boolean anyActive = false;
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            UserConfig uc = UserConfig.getInstance(slot);
            if (!uc.isClientActivated()) {
                continue;
            }
            if (isAccountActive(slot)) {
                anyActive = true;
            } else {
                final int s = slot;
                final long userId = uc.getClientUserId();
                post(() -> engineForgetAccount(s, userId));
            }
        }
        if (!anyActive) {
            post(LuminaAdShieldManager::engineReleaseIfUnused);
        }
    }

    // =====================================================================================
    // Paths
    // =====================================================================================

    static File root() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), ROOT_DIR);
    }

    static File imgRoot() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), IMG_DIR);
    }

    private static boolean rootsExist() {
        try {
            return root().exists() || imgRoot().exists();
        } catch (Throwable t) {
            return false;
        }
    }

    // =====================================================================================
    // App lifecycle hooks
    // =====================================================================================

    private static volatile boolean engineUsed;
    private static boolean startupPosted;

    /**
     * App start (UI thread): drop per-group modes of slots that are no longer logged in; when the feature is
     * active for some account or files exist, run the startup cleanup on the engine thread.
     * Feature off and no files: returns without touching the engine.
     */
    public static void onAppStarted() {
        try {
            if (startupPosted) {
                return;
            }
            startupPosted = true;
            for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
                if (!UserConfig.getInstance(slot).isClientActivated()) {
                    clearModes(slot);
                }
            }
            final long[] keep = activeUserIds();
            boolean any = false;
            for (long u : keep) {
                any |= u != 0;
            }
            if (!any && !rootsExist()) {
                return;
            }
            post(() -> engineStartup(keep));
        } catch (Throwable t) {
            FileLog.e("lumina adshield startup: " + t.getClass().getSimpleName());
        }
    }

    /** userId per slot when the account is logged in and the feature is active for it, else 0. */
    private static long[] activeUserIds() {
        long[] out = new long[UserConfig.MAX_ACCOUNT_COUNT];
        for (int slot = 0; slot < out.length; slot++) {
            UserConfig uc = UserConfig.getInstance(slot);
            if (uc.isClientActivated() && isAccountActive(slot)) {
                out[slot] = uc.getClientUserId();
            }
        }
        return out;
    }

    /** App goes to background (or is about to be killed): write every pending chat now. */
    public static void onAppBackground() {
        if (!engineUsed) {
            return;
        }
        post(LuminaAdShieldManager::engineFlushAll);
    }

    /**
     * Logout of one account (MessagesController.performLogout, every logout type; also reached by the panic
     * wipe, which logs every account out first). Must be called before the UserConfig is cleared.
     */
    public static void onLogout(int slot, final long userId) {
        try {
            clearModes(slot);
            AndroidUtilities.runOnUIThread(() -> notifySessions(slot, CHANGE_CLEARED));
            if (!engineUsed && !rootsExist()) {
                return;
            }
            post(() -> engineForgetAccount(slot, userId));
        } catch (Throwable t) {
            FileLog.e("lumina adshield logout: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 清除本機資料 (settings page): ForgetAll for every logged-in account, every per-group mode, every
     * similar-image table. The feature settings themselves stay as they are.
     */
    public static void clearLocalData() {
        clearModes(-1);
        final long[] users = new long[UserConfig.MAX_ACCOUNT_COUNT];
        for (int slot = 0; slot < users.length; slot++) {
            UserConfig uc = UserConfig.getInstance(slot);
            users[slot] = uc.isClientActivated() ? uc.getClientUserId() : 0;
        }
        if (engineUsed || rootsExist()) {
            // queued before the sessions re-observe below, so their fresh data is not wiped again
            post(() -> {
                for (int slot = 0; slot < users.length; slot++) {
                    engineForgetAccount(slot, users[slot]);
                }
                LuminaAdShieldAccount.wipeRoot(root(), LuminaAdShieldAccount.JAVA_IO);
                engineImages(false);
                if (images != null) {
                    images.wipe();
                } else {
                    LuminaAdShieldAccount.JAVA_IO.deleteRecursive(imgRoot());
                }
            });
        }
        notifySessions(-1, CHANGE_CLEARED);
        reconcileAccounts();
    }

    /**
     * Emergency wipe (LuminaSecurityActivity): everything AdShield ever stored, in prefs and on disk,
     * including the wrapped key and the Keystore wrapping key. Call after the accounts were logged out.
     */
    public static void panicWipe() {
        try {
            SharedPreferences p = prefs();
            if (p != null) {
                p.edit().clear().commit();
            }
            post(() -> {
                for (int slot = 0; slot < states.length; slot++) {
                    AccountState st = states[slot];
                    if (st != null) {
                        cancelFlushes(st);
                        st.account.forgetAll(true);
                        states[slot] = null;
                        epochs[slot]++;
                    }
                }
                LuminaAdShieldAccount.wipeRoot(root(), LuminaAdShieldAccount.JAVA_IO);
                LuminaAdShieldAccount.JAVA_IO.deleteRecursive(imgRoot());
                images = null;
                key = null;
                LuminaAdShieldKey.deleteWrappingKey();
                deletePrefsFile();
            });
            notifySessions(-1, CHANGE_CLEARED);
        } catch (Throwable t) {
            FileLog.e("lumina adshield panic: " + t.getClass().getSimpleName());
        }
    }

    private static void deletePrefsFile() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                ApplicationLoader.applicationContext.deleteSharedPreferences(PREFS_NAME);
            }
        } catch (Throwable ignore) {
        }
    }

    // =====================================================================================
    // Engine thread
    // =====================================================================================

    static final class AccountState {
        final int slot;
        final long userId;
        final LuminaAdShieldAccount account;
        final String dir;
        final HashMap<Long, ScheduledFuture<?>> flushes = new HashMap<Long, ScheduledFuture<?>>();

        AccountState(int slot, long userId, LuminaAdShieldAccount account) {
            this.slot = slot;
            this.userId = userId;
            this.account = account;
            this.dir = account.dirName();
        }
    }

    // engine thread
    private static final AccountState[] states = new AccountState[UserConfig.MAX_ACCOUNT_COUNT];
    // engine thread: bumped whenever an account's data is forgotten, so sessions drop their tracked ids
    static final int[] epochs = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private static byte[] key;              // engine thread
    private static LuminaAdShieldImages images;   // engine thread
    private static boolean startupDone;    // engine thread

    static void post(Runnable r) {
        if (!engineUsed) {
            engineUsed = true;
            LuminaAdShield.setLogger(event -> FileLog.d("lumina " + event));
        }
        LuminaAdShieldThread.post(r);
    }

    /** Server-adjusted Unix seconds, clamped to the engine's range. */
    static long now(int slot) {
        long t = ConnectionsManager.getInstance(slot).getCurrentTime() & 0xFFFFFFFFL;
        if (t <= 0) {
            t = System.currentTimeMillis() / 1000L;
        }
        return Math.min(t, LuminaAdShield.MAX_DATE);
    }

    /** H("sender", id) with the install key; only after an account object exists (key loaded). */
    static long engineSenderKey(long senderId) {
        return LuminaAdShield.senderKey(key, senderId);
    }

    private static byte[] engineKey(boolean create) {
        if (key == null) {
            key = LuminaAdShieldKey.load(prefs(), create);
        }
        return key;
    }

    static LuminaAdShieldImages engineImages(boolean create) {
        if (images == null) {
            byte[] k = engineKey(create);
            if (k != null) {
                images = new LuminaAdShieldImages(imgRoot(), k, LuminaAdShieldAccount.JAVA_IO);
            }
        }
        return images;
    }

    private static void engineStartup(long[] keepBySlot) {
        if (startupDone) {
            return;
        }
        startupDone = true;
        boolean any = false;
        for (long u : keepBySlot) {
            any |= u != 0;
        }
        if (!any) {
            // fully inactive everywhere: nothing may stay on disk
            engineReleaseIfUnused();
            return;
        }
        File root = root();
        byte[] k = engineKey(true);
        if (k == null) {
            // no key: nothing on disk can be valid
            LuminaAdShieldAccount.wipeRoot(root, LuminaAdShieldAccount.JAVA_IO);
            LuminaAdShieldAccount.JAVA_IO.deleteRecursive(imgRoot());
            return;
        }
        long t0 = System.currentTimeMillis();
        int removed = LuminaAdShieldAccount.purgeOrphans(root, k, LuminaAdShieldAccount.JAVA_IO);
        HashSet<String> keep = new HashSet<String>();
        for (long u : keepBySlot) {
            if (u != 0) {
                keep.add(AdShieldTables.hex64(AdShieldTables.keyedHash(k, "acct", Long.toString(u))));
            }
        }
        String[] dirs = root.isDirectory() ? root.list() : null;
        if (dirs != null) {
            for (String d : dirs) {
                if (!keep.contains(d)) {
                    LuminaAdShieldAccount.JAVA_IO.deleteRecursive(new File(root, d));
                    removed++;
                }
            }
        }
        removeIfEmpty(root);
        LuminaAdShieldImages img = engineImages(false);
        if (img != null) {
            img.purge(keep);
        }
        LuminaAdShield.log("adshield startup: removed " + removed + " in " + (System.currentTimeMillis() - t0) + " ms");
    }

    private static void removeIfEmpty(File dir) {
        String[] left = dir.isDirectory() ? dir.list() : null;
        if (left != null && left.length == 0) {
            dir.delete();
        }
    }

    /**
     * The account object for a logged-in slot. With {@code create}, makes one (and the key) when missing;
     * the caller has already checked that the feature is active for this account.
     */
    static AccountState engineAccount(int slot, long userId, boolean create) {
        if (slot < 0 || slot >= states.length || userId == 0) {
            return null;
        }
        AccountState st = states[slot];
        if (st != null && (st.userId != userId || st.account.isForgotten())) {
            cancelFlushes(st);
            if (!st.account.isForgotten()) {
                st.account.flush();
            }
            states[slot] = null;
            epochs[slot]++;
            st = null;
        }
        if (st != null || !create) {
            return st;
        }
        if (!startupDone) {
            engineStartup(activeUserIds());
        }
        byte[] k = engineKey(true);
        if (k == null) {
            return null;
        }
        st = new AccountState(slot, userId, new LuminaAdShieldAccount(userId, k, root()));
        states[slot] = st;
        return st;
    }

    /** SPEC §8.4: per-chat 5 s debounce; rescheduling cancels the pending write. */
    static void scheduleFlush(final AccountState st, final long chatId) {
        if (st == null) {
            return;
        }
        ScheduledFuture<?> f = st.flushes.remove(chatId);
        if (f != null) {
            f.cancel(false);
        }
        st.flushes.put(chatId, LuminaAdShieldThread.postDelayed(() -> {
            st.flushes.remove(chatId);
            if (st.account.isForgotten() || states[st.slot] != st) {
                return;
            }
            st.account.flushChat(chatId);
            LuminaAdShieldImages img = images;
            if (img != null) {
                img.flushChat(st.dir, chatId);
            }
            engineEnforceCaps();
        }, FLUSH_DELAY_MS));
    }

    private static void cancelFlushes(AccountState st) {
        for (ScheduledFuture<?> f : st.flushes.values()) {
            f.cancel(false);
        }
        st.flushes.clear();
    }

    private static void engineFlushAll() {
        boolean any = false;
        for (AccountState st : states) {
            if (st != null && !st.account.isForgotten()) {
                cancelFlushes(st);
                st.account.flush();
                any = true;
            }
        }
        if (images != null) {
            images.flushAll();
        }
        if (any) {
            engineEnforceCaps();
        }
    }

    private static void engineEnforceCaps() {
        LuminaAdShieldAccount.enforceTotalCap(root(), LuminaAdShield.TOTAL_CAP_BYTES, LuminaAdShieldAccount.JAVA_IO);
        if (images != null) {
            images.enforceCap(LuminaAdShieldImages.TOTAL_CAP_BYTES);
        }
    }

    /**
     * ForgetAll for one account (SPEC §8.4): timers stop, memory is dropped, the account directory and its
     * similar-image tables are deleted, orphans are purged. Works without a live account object too.
     */
    static void engineForgetAccount(int slot, long userId) {
        AccountState st = slot >= 0 && slot < states.length ? states[slot] : null;
        if (st != null) {
            cancelFlushes(st);
            st.account.forgetAll();
            states[slot] = null;
            epochs[slot]++;
            LuminaAdShieldImages img = engineImages(false);
            if (img != null) {
                img.forgetDir(st.dir);
            }
        }
        if (userId != 0 && (st == null || st.userId != userId)) {
            File root = root();
            File img = imgRoot();
            if (root.exists() || img.exists()) {
                byte[] k = engineKey(false);
                if (k != null) {
                    LuminaAdShieldAccount tmp = new LuminaAdShieldAccount(userId, k, root);
                    tmp.forgetAll();
                    LuminaAdShieldImages im = engineImages(false);
                    if (im != null) {
                        im.forgetDir(tmp.dirName());
                    }
                }
            }
        }
        removeIfEmpty(root());
        removeIfEmpty(imgRoot());
    }

    /**
     * The feature is fully off for every account (global off, no group set to on): after the per-account
     * ForgetAll above, also drop the emptied roots and the wrapped key, so nothing of AdShield stays in the
     * app's data. Groups explicitly set to off keep their prefs entry (a setting, not data).
     */
    private static void engineReleaseIfUnused() {
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            if (states[slot] != null || UserConfig.getInstance(slot).isClientActivated() && isAccountActive(slot)) {
                return;
            }
        }
        LuminaAdShieldAccount.wipeRoot(root(), LuminaAdShieldAccount.JAVA_IO);
        LuminaAdShieldAccount.JAVA_IO.deleteRecursive(imgRoot());
        images = null;
        key = null;
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().remove(LuminaAdShieldKey.PREF_WRAPPED).commit();
            if (p.getAll().isEmpty()) {
                deletePrefsFile();
            }
        }
    }

    // =====================================================================================
    // Small engine queries for sessions
    // =====================================================================================

    /** Distinct units (an album counts once) folded among this chat's messages of the last 7 days. */
    static int engineFoldedCount7d(LuminaAdShieldStore store, LuminaAdShield.ChatContext ctx,
                                   LuminaAdShield.Settings settings, long now) {
        HashSet<Long> units = new HashSet<Long>();
        int singles = 0;
        long from = now - 7 * LuminaAdShield.DAY;
        ArrayList<LuminaAdShieldStore.Row> rows = new ArrayList<LuminaAdShieldStore.Row>(store.rows.values());
        for (LuminaAdShieldStore.Row r : rows) {
            if (!r.live() || r.date < from) {
                continue;
            }
            if (r.album != 0 && units.contains(r.album)) {
                continue;
            }
            if (store.decide(r.msgId, ctx, settings, now).fold) {
                if (r.album != 0) {
                    units.add(r.album);
                } else {
                    singles++;
                }
            }
        }
        return singles + units.size();
    }
}
