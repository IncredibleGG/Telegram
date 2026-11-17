/*
 * LuminaGram - AdShield checker (dev tool; NOT compiled by the app build).
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 *
 * Runs the shared AdShield conformance suite (SPEC v3) against the Android Java port:
 *   SipHash 77, normalize 55, fingerprint 76 (every SPEC §10.2 field), all 138 scenarios exactly like
 *   ref/test_adshield.py run_scenario, shuffled load order, serialize round trip, every SPEC §8.3
 *   rejection rule on crafted blobs, no plaintext in the blob, ForgetAll / orphans / total cap, the v3
 *   unit tests of ref/test_adshield.py (TestV3, TestV3Fixes), plus the TSV vectors and decide() timings
 *   on 3000-row chats.
 * Same package as the engine (separate source root) for package-private access to internals.
 * See ../../../../../README.md and ../../../../../run.sh.
 */
package org.telegram.messenger.lumina;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;

import org.telegram.messenger.lumina.LuminaAdShield.ChatContext;
import org.telegram.messenger.lumina.LuminaAdShield.Decision;
import org.telegram.messenger.lumina.LuminaAdShield.Entity;
import org.telegram.messenger.lumina.LuminaAdShield.Fingerprint;
import org.telegram.messenger.lumina.LuminaAdShield.Forward;
import org.telegram.messenger.lumina.LuminaAdShield.Key;
import org.telegram.messenger.lumina.LuminaAdShield.Link;
import org.telegram.messenger.lumina.LuminaAdShield.Media;
import org.telegram.messenger.lumina.LuminaAdShield.MessageInput;
import org.telegram.messenger.lumina.LuminaAdShield.Sender;
import org.telegram.messenger.lumina.LuminaAdShield.Settings;

@SuppressWarnings("unchecked")
public final class AdShieldCheck {

    static final byte[] TEST_KEY = new byte[16];

    static {
        for (int i = 0; i < 16; i++) {
            TEST_KEY[i] = (byte) i;
        }
    }

    static String dataDir;

    // ------------------------------------------------------------------ accounting
    static final class Sec {
        final String name;
        int checks, failed, cases, casesFailed;
        boolean inCase, caseBad;
        String note = "";

        Sec(String name) {
            this.name = name;
        }
    }

    static final LinkedHashMap<String, Sec> SECTIONS = new LinkedHashMap<String, Sec>();
    static Sec cur;
    static int shown;

    static void section(String name) {
        cur = new Sec(name);
        SECTIONS.put(name, cur);
    }

    static void caseBegin() {
        cur.inCase = true;
        cur.caseBad = false;
    }

    static void caseEnd() {
        cur.cases++;
        if (cur.caseBad) {
            cur.casesFailed++;
        }
        cur.inCase = false;
    }

    static boolean check(boolean ok, String what) {
        cur.checks++;
        if (!ok) {
            cur.failed++;
            cur.caseBad = true;
            if (shown++ < 300) {
                System.out.println("FAIL [" + cur.name + "] " + what);
            }
        }
        return ok;
    }

    static boolean eq(Object want, Object got, String what) {
        boolean ok = want == null ? got == null : want.equals(got);
        return check(ok, what + ": want=" + want + " got=" + got);
    }

    // ------------------------------------------------------------------ JSON helpers
    static Object load(String rel) throws IOException {
        byte[] b = Files.readAllBytes(new File(dataDir, rel).toPath());
        return MiniJson.parse(new String(b, StandardCharsets.UTF_8));
    }

    static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    static List<Object> list(Object o) {
        return o == null ? Collections.emptyList() : (List<Object>) o;
    }

    static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : (String) v;
    }

    static long lng(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        return v == null ? def : ((Number) v).longValue();
    }

    static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v == null ? def : (Boolean) v;
    }

    static List<String> strList(Object o) {
        ArrayList<String> out = new ArrayList<String>();
        for (Object x : list(o)) {
            out.add((String) x);
        }
        return out;
    }

    static byte[] fromHex(String h) {
        if (h.equals("-")) {
            return new byte[0];
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }

    static String hex(long v) {
        return AdShieldTables.hex64(v);
    }

    static List<String> hexList(long[] a) {
        ArrayList<String> out = new ArrayList<String>();
        for (long v : a) {
            out.add(hex(v));
        }
        return out;
    }

    static MessageInput msg(Map<String, Object> m) {
        MessageInput mi = new MessageInput();
        mi.id = (int) lng(m, "id", 0);
        mi.date = lng(m, "date", 0);
        Map<String, Object> s = obj(m.get("sender"));
        if (s != null) {
            Sender sd = new Sender();
            sd.id = lng(s, "id", 0);
            sd.role = s.containsKey("role") ? str(s, "role") : LuminaAdShield.ROLE_MEMBER;
            sd.username = str(s, "username");
            mi.sender = sd;
        }
        String text = str(m, "text");
        mi.text = text != null ? text : "";
        if (m.get("entities") != null) {
            mi.entities = new ArrayList<Entity>();
            for (Object o : list(m.get("entities"))) {
                Map<String, Object> e = obj(o);
                Entity en = new Entity();
                en.type = str(e, "type");
                en.url = str(e, "url");
                en.userId = e.get("userId") == null ? null : ((Number) e.get("userId")).longValue();
                mi.entities.add(en);
            }
        }
        Map<String, Object> md = obj(m.get("media"));
        if (md != null) {
            Media me = new Media();
            me.kind = str(md, "kind");
            me.id = str(md, "id");
            me.userId = lng(md, "userId", 0);
            me.phone = str(md, "phone");
            mi.media = me;
        }
        mi.album = lng(m, "album", 0);
        Map<String, Object> f = obj(m.get("forward"));
        if (f != null) {
            Forward fw = new Forward();
            fw.fromKind = str(f, "fromKind");
            fw.fromId = lng(f, "fromId", 0);
            fw.postId = lng(f, "postId", 0);
            fw.fromName = str(f, "fromName");
            fw.username = str(f, "username");
            fw.date = lng(f, "date", 0);
            mi.forward = fw;
        }
        if (m.get("buttons") != null) {
            mi.buttons = strList(m.get("buttons"));
        }
        mi.viaBot = bool(m, "viaBot", false);
        mi.replyToMe = bool(m, "replyToMe", false);
        mi.mentionsMe = bool(m, "mentionsMe", false);
        mi.pinned = bool(m, "pinned", false);
        mi.autoForward = bool(m, "autoForward", false);
        mi.service = bool(m, "service", false);
        mi.sponsored = bool(m, "sponsored", false);
        mi.scheduled = bool(m, "scheduled", false);
        mi.replyTo = (int) lng(m, "replyTo", 0);
        return mi;
    }

    static ChatContext ctx(Map<String, Object> d) {
        Map<String, Object> lc = obj(d.get("linkedChannel"));
        return ChatContext.builder(lng(d, "chatId", 0))
                .type(d.containsKey("type") ? str(d, "type") : LuminaAdShield.CHAT_SUPERGROUP)
                .username(str(d, "username"))
                .linkedChannel(lc == null ? 0 : lng(lc, "id", 0), lc == null ? null : str(lc, "username"))
                .description(d.containsKey("description") ? str(d, "description") : "")
                .pinnedTexts(strList(d.get("pinnedTexts")))
                .adminsKnown(bool(d, "adminsKnown", true))
                .memberHandles(strList(d.get("memberHandles")))
                .selfUsername(str(d, "selfUsername"))
                .build();
    }

    static Settings settings(Map<String, Object> sc, String sens) {
        Settings st = new Settings(sens);
        Map<String, Object> s = obj(sc.get("settings"));
        if (s != null) {
            st.exemptContacts = bool(s, "exemptContacts", true);
            st.exemptBots = bool(s, "exemptBots", true);
            st.exemptReplies = bool(s, "exemptReplies", true);
            st.dryRun = bool(s, "dryRun", false);
            st.foldPrivilegedRepeats = bool(s, "foldPrivilegedRepeats", false);
        }
        return st;
    }

    // ------------------------------------------------------------------ main
    public static void main(String[] args) throws Exception {
        dataDir = args.length > 0 ? args[0] : "lumina-dev/adshield-check";
        int failures = LuminaAdShieldThread.call(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return runAll();
            }
        });
        System.exit(failures == 0 ? 0 : 1);
    }

    static int runAll() throws Exception {
        long t0 = System.nanoTime();
        section("engine-thread");
        check(LuminaAdShieldThread.isEngineThread(), "checker runs on the 16 MB engine thread");
        check(LuminaAdShield.ALGO_VERSION == AdShieldTables.ALGO_VERSION, "ALGO_VERSION matches the generated tables");
        sipHash();
        tsv();
        tables();
        normalize();
        fingerprints();
        scenarios();
        loadOrder();
        reverseOrder();
        storage();
        rejection();
        accountTests();
        robustness();
        v3Rules();
        userCase();
        perf();
        perfV3();

        System.out.println();
        System.out.println("AdShield Java checker - tables " + AdShieldTables.TABLES_VERSION + ", algo "
                + LuminaAdShield.ALGO_VERSION + ", format " + LuminaAdShield.FORMAT_VERSION);
        int fails = 0;
        for (Sec s : SECTIONS.values()) {
            fails += s.failed;
            String cases = s.cases > 0 ? String.format("%5d/%-5d cases  ", s.cases - s.casesFailed, s.cases)
                    : "                   ";
            System.out.println(String.format("  %-26s %s%6d/%-6d checks  %s %s", s.name, cases,
                    s.checks - s.failed, s.checks, s.failed == 0 ? "OK  " : "FAIL", s.note));
        }
        System.out.println(String.format("TOTAL: %d failed check(s); %.1f s", fails, (System.nanoTime() - t0) / 1e9));
        return fails;
    }

    // ------------------------------------------------------------------ SipHash
    static long[] k01(byte[] key) {
        long k0 = 0, k1 = 0;
        for (int j = 7; j >= 0; j--) {
            k0 = (k0 << 8) | (key[j] & 0xFFL);
        }
        for (int j = 15; j >= 8; j--) {
            k1 = (k1 << 8) | (key[j] & 0xFFL);
        }
        return new long[]{k0, k1};
    }

    static void sipHash() throws IOException {
        section("siphash-paper");
        long[] k = k01(TEST_KEY);
        eq("726fdb47dd0e0e31", hex(AdShieldTables.sipHash24(k[0], k[1], new byte[0])), "SipHash(empty)");
        byte[] m15 = new byte[15];
        for (int i = 0; i < 15; i++) {
            m15[i] = (byte) i;
        }
        eq("a129ca6149be45e5", hex(AdShieldTables.sipHash24(k[0], k[1], m15)), "SipHash(00..0e)");

        section("siphash-77");
        Map<String, Object> v = obj(load("vectors/siphash_vectors.json"));
        byte[] key = fromHex(str(v, "key"));
        long[] kk = k01(key);
        for (Object o : list(v.get("raw"))) {
            Map<String, Object> r = obj(o);
            caseBegin();
            eq(str(r, "hash"), hex(AdShieldTables.sipHash24(kk[0], kk[1], fromHex(str(r, "messageHex")))),
                    "raw " + str(r, "messageHex"));
            caseEnd();
        }
        for (Object o : list(v.get("tagged"))) {
            Map<String, Object> r = obj(o);
            caseBegin();
            eq(str(r, "hash"), hex(AdShieldTables.keyedHash(key, str(r, "tag"), str(r, "payload"))),
                    "tagged " + str(r, "tag"));
            caseEnd();
        }
    }

    // ------------------------------------------------------------------ TSV (as check/CheckVectors.java, LF and CRLF)
    static void tsv() throws IOException {
        section("tsv-131 (LF+CRLF)");
        for (int variant = 0; variant < 2; variant++) {
            for (String name : new String[]{"vectors/normalize_vectors.tsv", "vectors/siphash_vectors.tsv"}) {
                String text = new String(Files.readAllBytes(new File(dataDir, name).toPath()), StandardCharsets.UTF_8);
                if (variant == 1) {
                    text = text.replace("\n", "\r\n");
                }
                for (String line : text.split("\n", -1)) {
                    int n = line.length();
                    while (n > 0 && line.charAt(n - 1) == '\r') {
                        n--;
                    }
                    line = line.substring(0, n);
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] f = line.split("\t", -1);
                    caseBegin();
                    if (name.contains("normalize")) {
                        String input = new String(fromHex(f[1]), StandardCharsets.UTF_8);
                        String folded = AdShieldTables.fold(input);
                        String t = AdShieldTables.textForm(folded);
                        String c = AdShieldTables.cjkForm(t);
                        check(toHex(folded.getBytes(StandardCharsets.UTF_8)).equals(f[2])
                                && toHex(t.getBytes(StandardCharsets.UTF_8)).equals(f[3])
                                && toHex(c.getBytes(StandardCharsets.UTF_8)).equals(f[4])
                                && String.valueOf(AdShieldTables.wlen(t)).equals(f[5]), "tsv normalize " + f[0]);
                    } else {
                        String got;
                        if (f[0].equals("raw")) {
                            long[] k = k01(TEST_KEY);
                            got = hex(AdShieldTables.sipHash24(k[0], k[1], fromHex(f[2])));
                        } else {
                            got = hex(AdShieldTables.keyedHash(TEST_KEY, new String(fromHex(f[1]), StandardCharsets.UTF_8),
                                    new String(fromHex(f[2]), StandardCharsets.UTF_8)));
                        }
                        eq(f[3], got, "tsv siphash " + line);
                    }
                    caseEnd();
                }
            }
        }
        cur.note = "(" + cur.cases / 2 + " per variant)";
    }

    // ------------------------------------------------------------------ table invariants
    static void tables() {
        section("tables");
        int bad = 0;
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            String f = AdShieldTables.fold(new String(Character.toChars(cp)));
            if (!AdShieldTables.fold(f).equals(f)) {
                bad++;
            }
            for (int i = 0; i < f.length(); ) {
                int c = f.codePointAt(i);
                i += Character.charCount(c);
                if (AdShieldTables.isDrop(c)) {
                    bad++;
                }
            }
        }
        check(bad == 0, "fold is idempotent and never outputs DROP characters over all scalars (bad=" + bad + ")");
        eq("가", AdShieldTables.fold("ㄱㅏ"), "hangul L+V");
        eq("갃", AdShieldTables.fold("가ㄳ"), "hangul LV+T");
        eq("갃", AdShieldTables.fold("ㄱㅏㄳ"), "hangul L+V+T");
        eq("ᄏᄏᄏ", AdShieldTables.fold("ㅋㅋㅋ"), "hangul jamo run");
        int[] inv = {0x3164, 0x115F, 0x1160, 0xFFA0, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202E,
                0x2060, 0x2066, 0xFEFF, 0x00AD, 0x034F, 0x180E, 0xFE0F, 0x20E3, 0xE0067, 0xE0100, 0x2800};
        for (int cp : inv) {
            eq("ab", AdShieldTables.fold("a" + new String(Character.toChars(cp)) + "b"), String.format("invisible U+%04X", cp));
        }
        // keyword table is pre-sorted by the generator (length desc, code points, class, kind); never re-sorted here
        String[] w = AdShieldTables.KEYWORD_WORDS;
        boolean sorted = true;
        for (int i = 1; i < w.length; i++) {
            int la = w[i - 1].codePointCount(0, w[i - 1].length()), lb = w[i].codePointCount(0, w[i].length());
            if (la < lb || (la == lb && LuminaAdShield.compareCodePoints(w[i - 1], w[i]) > 0)) {
                sorted = false;
            }
        }
        check(sorted, "KEYWORD_WORDS pre-sorted");
        check(LuminaAdShield.compareCodePoints("￿", "😀") < 0
                && "￿".compareTo("😀") > 0, "code point order differs from UTF-16 order where it must");
    }

    // ------------------------------------------------------------------ normalize 55
    static String scalarsOf(String s) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(String.format("%04x", c));
        }
        return sb.append("]").toString();
    }

    static void normalize() throws IOException {
        section("normalize-55");
        Map<String, Object> v = obj(load("vectors/normalize_vectors.json"));
        eq(AdShieldTables.TABLES_VERSION, str(v, "tablesVersion"), "vector tablesVersion");
        for (Object o : list(v.get("cases"))) {
            Map<String, Object> c = obj(o);
            String id = str(c, "id");
            caseBegin();
            String input;
            if (c.get("input") != null) {
                input = str(c, "input");
            } else {
                StringBuilder sb = new StringBuilder();
                for (Object u : list(c.get("inputUtf16"))) {
                    sb.append((char) ((Number) u).intValue());
                }
                input = sb.toString();
            }
            String f = AdShieldTables.fold(input);
            eq(str(c, "fold"), f, id + " fold");
            ArrayList<String> want = new ArrayList<String>();
            for (Object x : list(c.get("foldScalars"))) {
                want.add((String) x);
            }
            eq(want.toString(), scalarsOf(f), id + " foldScalars");
            String t = AdShieldTables.textForm(f);
            eq(str(c, "text"), t, id + " text");
            eq(str(c, "cjk"), AdShieldTables.cjkForm(t), id + " cjk");
            eq(lng(c, "wlen", -1), (long) AdShieldTables.wlen(t), id + " wlen");
            int[] lc = LuminaAdShield.letterCounts(t);
            eq(lng(c, "cjkCount", -1), (long) lc[0], id + " cjkCount");
            eq(lng(c, "otherLetterCount", -1), (long) lc[1], id + " otherLetterCount");
            eq(strList(c.get("paragraphs")), LuminaAdShield.paragraphs(LuminaAdShield.extractLinks(f).foldNoLinks),
                    id + " paragraphs");
            caseEnd();
        }
    }

    // ------------------------------------------------------------------ fingerprint 57
    static void fingerprints() throws IOException {
        section("fingerprint-76");
        Map<String, Object> v = obj(load("vectors/fingerprint_vectors.json"));
        byte[] key = fromHex(str(v, "key"));
        ChatContext ctx = ctx(obj(v.get("context")));
        eq(AdShieldTables.TABLES_VERSION, str(v, "tablesVersion"), "vector tablesVersion");
        for (Object o : list(v.get("cases"))) {
            Map<String, Object> c = obj(o);
            String id = str(c, "id");
            caseBegin();
            Fingerprint fp = LuminaAdShield.fingerprint(msg(obj(c.get("message"))), ctx, key, Collections.<String>emptySet());
            Map<String, Object> e = obj(c.get("expect"));
            eq(str(e, "fold"), fp.fold, id + " fold");
            eq(str(e, "foldNoLinks"), fp.foldNoLinks, id + " foldNoLinks");
            eq(str(e, "text"), fp.text, id + " text");
            eq(str(e, "cjk"), fp.cjk, id + " cjk");
            eq(lng(e, "wlen", -1), (long) fp.wlen, id + " wlen");
            eq(lng(e, "cjkCount", -1), (long) fp.cjkCount, id + " cjkCount");
            eq(lng(e, "otherLetterCount", -1), (long) fp.letterCount, id + " otherLetterCount");
            eq(strList(e.get("paragraphs")), fp.paragraphs, id + " paragraphs");
            eq(strList(e.get("contentLines")), fp.contentLines, id + " contentLines");
            eq(strList(e.get("blocks")), fp.blocks, id + " blocks");
            ArrayList<String> wantLinks = new ArrayList<String>();
            for (Object lo : list(e.get("links"))) {
                Map<String, Object> l = obj(lo);
                wantLinks.add(str(l, "token") + "|" + str(l, "type") + "|" + str(l, "kind") + "|" + str(l, "source") + "|"
                        + str(l, "feature") + "|" + str(l, "hash"));
            }
            ArrayList<String> gotLinks = new ArrayList<String>();
            for (int i = 0; i < fp.links.size(); i++) {
                Link l = fp.links.get(i);
                gotLinks.add(l.token + "|" + l.type + "|" + l.kind + "|" + l.source + "|" + fp.linkFeatures.get(i) + "|"
                        + hex(AdShieldTables.keyedHash(key, "link", l.token)));
            }
            eq(wantLinks, gotLinks, id + " links (token|type|kind|source|feature|hash, in order)");
            eq(strList(e.get("strong")), fp.strong, id + " strong");
            eq(strList(e.get("weak")), fp.weak, id + " weak");
            eq(lng(e, "mentionCount", -1), (long) fp.mentionCount, id + " mentionCount");
            ArrayList<String> wantKeys = new ArrayList<String>();
            for (Object ko : list(e.get("keys"))) {
                Map<String, Object> k = obj(ko);
                wantKeys.add(str(k, "kind") + "|" + str(k, "tag") + "|" + str(k, "payload") + "|" + str(k, "hash"));
            }
            ArrayList<String> gotKeys = new ArrayList<String>();
            for (int i = 0; i < fp.keyKinds.length; i++) {
                gotKeys.add(LuminaAdShield.KIND_NAME[fp.keyKinds[i]] + "|" + LuminaAdShield.KIND_TAG[fp.keyKinds[i]] + "|"
                        + fp.keyPayloads[i] + "|" + hex(fp.keyHashes[i]));
            }
            eq(wantKeys, gotKeys, id + " keys (kind|tag|payload|hash, in order)");
            eq(lng(e, "flags", -1), (long) fp.flags, id + " flags");
            eq(strList(e.get("subsetLinks")), hexList(fp.linkHashes), id + " subsetLinks");
            eq(strList(e.get("pending")), hexList(fp.pending), id + " pending");
            eq(strList(e.get("resolvedStrong")), fp.resolvedStrong, id + " resolvedStrong");
            eq(strList(e.get("resolvedWeak")), fp.resolvedWeak, id + " resolvedWeak");
            eq(lng(e, "resolvedFlags", -1), (long) fp.resolvedFlags, id + " resolvedFlags");
            eq(str(e, "senderKey"), hex(fp.senderKey), id + " senderKey");
            eq(bool(e, "store", false), fp.store, id + " store");
            eq(bool(e, "endorsing", false), fp.endorsing, id + " endorsing");
            eq(bool(e, "privileged", false), fp.privileged, id + " privileged");
            caseEnd();
        }
    }

    // ------------------------------------------------------------------ scenarios (README runner)
    static final class Result {
        final int idx;
        final String sens;
        final List<Integer> want;
        final List<Integer> got;
        final List<String> badStates;

        Result(int idx, String sens, List<Integer> want, List<Integer> got, List<String> badStates) {
            this.idx = idx;
            this.sens = sens;
            this.want = want;
            this.got = got;
            this.badStates = badStates;
        }
    }

    static List<Result> runScenario(Map<String, Object> sc, List<Object> order) {
        LuminaAdShieldAccount acct = new LuminaAdShieldAccount(424242, TEST_KEY, null);
        ChatContext ctx = ctx(obj(sc.get("context")));
        LuminaAdShieldStore chat = acct.chat(ctx.chatId);
        ArrayList<Integer> seen = new ArrayList<Integer>();
        long now = 0;
        ArrayList<Result> results = new ArrayList<Result>();
        List<Object> events = order == null ? list(sc.get("events")) : order;
        for (int idx = 0; idx < events.size(); idx++) {
            Map<String, Object> ev = obj(events.get(idx));
            String op = str(ev, "op");
            if (op.equals("observe")) {
                Map<String, Object> m = obj(ev.get("message"));
                now = ev.containsKey("now") ? lng(ev, "now", 0) : Math.max(now, lng(m, "date", 0));
                chat.observe(msg(m), ctx, now);
                int id = (int) lng(m, "id", 0);
                if (!seen.contains(id)) {
                    seen.add(id);
                }
            } else if (op.equals("delete")) {
                now = ev.containsKey("now") ? lng(ev, "now", 0) : now;
                List<Object> ids = list(ev.get("ids"));
                int[] arr = new int[ids.size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = ((Number) ids.get(i)).intValue();
                }
                chat.delete(arr, now);
            } else if (op.equals("notAd")) {
                now = ev.containsKey("now") ? lng(ev, "now", 0) : now;
                chat.markNotAd((int) lng(ev, "id", 0), ctx, now);
            } else if (op.equals("context")) {
                ctx = ctx(obj(ev.get("context")));
            } else if (op.equals("expect")) {
                now = ev.containsKey("now") ? lng(ev, "now", 0) : now;
                for (String sens : LuminaAdShield.SENSITIVITIES) {
                    Settings st = settings(sc, sens);
                    LinkedHashMap<Integer, Decision> decisions = new LinkedHashMap<Integer, Decision>();
                    ArrayList<Integer> got = new ArrayList<Integer>();
                    for (int id : seen) {
                        Decision d = chat.decide(id, ctx, st, now);
                        decisions.put(id, d);
                        if (d.fold) {
                            got.add(id);
                        }
                    }
                    Collections.sort(got);
                    ArrayList<Integer> want = new ArrayList<Integer>();
                    for (Object x : list(obj(ev.get("fold")).get(sens))) {
                        want.add(((Number) x).intValue());
                    }
                    Collections.sort(want);
                    ArrayList<String> bad = new ArrayList<String>();
                    Map<String, Object> states = obj(ev.get("states"));
                    if (states != null && states.get(sens) != null) {
                        for (Map.Entry<String, Object> se : obj(states.get(sens)).entrySet()) {
                            Decision d = decisions.get(Integer.parseInt(se.getKey()));
                            String gotState = d == null ? "<unseen>" : d.state;
                            if (!se.getValue().equals(gotState)) {
                                bad.add("(" + se.getKey() + ", " + se.getValue() + ", " + gotState + ")");
                            }
                        }
                    }
                    results.add(new Result(idx, sens, want, got, bad));
                }
            }
        }
        return results;
    }

    static void scenarios() throws IOException {
        section("scenarios-138");
        List<Object> scs = list(obj(load("corpus/scenarios.json")).get("scenarios"));
        int expects = 0;
        for (Object o : scs) {
            Map<String, Object> sc = obj(o);
            String id = str(sc, "id");
            caseBegin();
            for (Result r : runScenario(sc, null)) {
                expects++;
                eq(r.want, r.got, id + " event#" + r.idx + " " + r.sens + " fold set");
                check(r.badStates.isEmpty(), id + " event#" + r.idx + " " + r.sens + " states (id, want, got)=" + r.badStates);
            }
            caseEnd();
        }
        cur.note = "(" + expects + " expect x sensitivity evaluations)";
    }

    static Map<String, Object> withNow(Map<String, Object> ev, long now) {
        LinkedHashMap<String, Object> c = new LinkedHashMap<String, Object>(ev);
        c.put("now", now);
        return c;
    }

    static List<String> runsOf(List<Result> rs) {
        ArrayList<String> out = new ArrayList<String>();
        for (Result r : rs) {
            out.add(r.sens + "=" + r.got);
        }
        return out;
    }

    static void loadOrder() throws IOException {
        section("load-order-shuffle");
        List<Object> scs = list(obj(load("corpus/scenarios.json")).get("scenarios"));
        Random rnd = new Random(20261117L);
        HashSet<String> covered = new HashSet<String>();
        int runs = 0;
        for (Object o : scs) {
            Map<String, Object> sc = obj(o);
            List<Object> evs = list(sc.get("events"));
            boolean skip = false;
            ArrayList<Object> obs = new ArrayList<Object>();
            HashSet<Long> ids = new HashSet<Long>();
            Map<String, Object> lastExpect = null;
            for (Object eo : evs) {
                Map<String, Object> e = obj(eo);
                String op = str(e, "op");
                if (op.equals("delete") || op.equals("notAd") || op.equals("context")) {
                    skip = true;
                }
                if (op.equals("observe")) {
                    obs.add(e);
                    ids.add(lng(obj(e.get("message")), "id", 0));
                }
                if (op.equals("expect")) {
                    lastExpect = e;
                }
            }
            if (skip || ids.size() != obs.size()) {
                continue;
            }
            long maxNow = Long.MIN_VALUE;
            for (Object eo : obs) {
                Map<String, Object> e = obj(eo);
                maxNow = Math.max(maxNow, e.containsKey("now") ? lng(e, "now", 0) : lng(obj(e.get("message")), "date", 0));
            }
            Map<String, Object> probe = withNow(lastExpect, maxNow);
            probe.put("states", new LinkedHashMap<String, Object>());
            ArrayList<Object> base = new ArrayList<Object>();
            for (Object eo : obs) {
                base.add(withNow(obj(eo), maxNow));
            }
            base.add(probe);
            List<String> baseline = runsOf(runScenario(sc, base));
            covered.add(str(sc, "id"));
            caseBegin();
            for (int k = 0; k < 5; k++) {
                ArrayList<Object> shuffled = new ArrayList<Object>(obs);
                Collections.shuffle(shuffled, rnd);
                ArrayList<Object> order = new ArrayList<Object>();
                for (Object eo : shuffled) {
                    order.add(withNow(obj(eo), maxNow));
                }
                order.add(probe);
                runs++;
                eq(baseline, runsOf(runScenario(sc, order)), str(sc, "id") + " shuffled load order changed the result");
            }
            caseEnd();
        }
        for (String need : new String[]{"FP-20a", "FP-20b", "RG-01", "RG-02", "EV-25"}) {
            check(covered.contains(need), need + " covered by the shuffle test");
        }
        cur.note = "(" + runs + " shuffled runs)";
    }

    static void reverseOrder() throws IOException {
        section("reverse-order FP-20a/b");
        LinkedHashMap<String, Map<String, Object>> byId = new LinkedHashMap<String, Map<String, Object>>();
        for (Object o : list(obj(load("corpus/scenarios.json")).get("scenarios"))) {
            byId.put(str(obj(o), "id"), obj(o));
        }
        eq(runsOf(runScenario(byId.get("FP-20a"), null)), runsOf(runScenario(byId.get("FP-20b"), null)),
                "FP-20a and FP-20b (reverse load order) give the same result");
    }

    // ------------------------------------------------------------------ storage (ports TestStorage)
    static Map<String, Object> scenario(String id) throws IOException {
        for (Object o : list(obj(load("corpus/scenarios.json")).get("scenarios"))) {
            if (id.equals(str(obj(o), "id"))) {
                return obj(o);
            }
        }
        throw new IllegalStateException("no scenario " + id);
    }

    static final class Filled {
        Map<String, Object> sc;
        ChatContext ctx;
        LuminaAdShieldStore chat;
        long now;
    }

    static Filled filled(LuminaAdShieldAccount acct) throws IOException {
        Filled f = new Filled();
        f.sc = scenario("TR-03");
        f.ctx = ctx(obj(f.sc.get("context")));
        f.chat = acct.chat(f.ctx.chatId);
        for (Object o : list(f.sc.get("events"))) {
            Map<String, Object> ev = obj(o);
            if (str(ev, "op").equals("observe")) {
                Map<String, Object> m = obj(ev.get("message"));
                f.now = Math.max(f.now, lng(m, "date", 0));
                f.chat.observe(msg(m), f.ctx, f.now);
            }
        }
        return f;
    }

    static boolean containsBytes(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static MessageInput simple(int id, long date, long senderId, String username, String role, String text) {
        MessageInput m = new MessageInput();
        m.id = id;
        m.date = date;
        m.sender = new Sender(senderId, role, username);
        m.text = text;
        return m;
    }

    static File tempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix).toFile();
    }

    static void rmrf(File f) {
        LuminaAdShieldAccount.JAVA_IO.deleteRecursive(f);
    }

    static List<String> sortedNames(File dir) {
        String[] n = dir.list();
        ArrayList<String> out = new ArrayList<String>(n == null ? Collections.<String>emptyList() : Arrays.asList(n));
        Collections.sort(out);
        return out;
    }

    interface StoreMut {
        void apply(LuminaAdShieldStore c);
    }

    static void storage() throws IOException {
        section("storage");
        // round trip: same bytes, same decisions
        {
            Filled f = filled(new LuminaAdShieldAccount(1, TEST_KEY, null));
            byte[] blob = f.chat.serialize();
            LuminaAdShieldStore back = LuminaAdShieldStore.deserialize(blob, f.ctx.chatId, TEST_KEY);
            if (check(back != null, "round trip: deserialize(serialize()) accepted")) {
                check(Arrays.equals(back.serialize(), blob), "round trip: re-serialized bytes identical");
                for (int mid = 1; mid <= 4; mid++) {
                    for (String sens : LuminaAdShield.SENSITIVITIES) {
                        eq(f.chat.decide(mid, f.ctx, new Settings(sens), f.now), back.decide(mid, f.ctx, new Settings(sens), f.now),
                                "round trip decision msg " + mid + " " + sens);
                    }
                }
            }
            check(LuminaAdShieldStore.deserialize(blob, f.ctx.chatId, new byte[16]) == null, "wrong key discarded");
            check(LuminaAdShieldStore.deserialize(blob, f.ctx.chatId + 1, TEST_KEY) == null, "wrong chat discarded");
            check(LuminaAdShieldStore.deserialize(Arrays.copyOf(blob, blob.length - 1), f.ctx.chatId, TEST_KEY) == null,
                    "truncated blob discarded");
            for (int pos : new int[]{60, blob.length / 2, blob.length - 3}) {
                byte[] bad = blob.clone();
                bad[pos] ^= 0x40;
                check(LuminaAdShieldStore.deserialize(bad, f.ctx.chatId, TEST_KEY) == null, "corrupt body byte " + pos + " discarded");
            }
            byte[] many = blob.clone();
            many[32] = (byte) 0xB9;          // rowCount = 3001
            many[33] = 0x0B;
            many[34] = 0;
            many[35] = 0;
            check(LuminaAdShieldStore.deserialize(many, f.ctx.chatId, TEST_KEY) == null, "rowCount 3001 in header discarded");
        }
        // no plaintext in the blob
        {
            Filled f = filled(new LuminaAdShieldAccount(1, TEST_KEY, null));
            byte[] blob = f.chat.serialize();
            int n = 0;
            for (Object o : list(f.sc.get("events"))) {
                Map<String, Object> ev = obj(o);
                if (!str(ev, "op").equals("observe")) {
                    continue;
                }
                Map<String, Object> m = obj(ev.get("message"));
                String folded = AdShieldTables.fold(str(m, "text"));
                String head = folded.substring(0, folded.offsetByCodePoints(0, Math.min(6, folded.codePointCount(0, folded.length()))));
                long sid = lng(obj(m.get("sender")), "id", 0);
                for (String frag : new String[]{head, "viproom9", "VipRoom9", Long.toString(sid), "本群提供"}) {
                    check(!containsBytes(blob, frag.getBytes(StandardCharsets.UTF_8)), "plaintext fragment found in blob");
                    n++;
                }
                byte[] le = new byte[8];
                for (int i = 0; i < 8; i++) {
                    le[i] = (byte) (sid >>> (8 * i));
                }
                check(!containsBytes(blob, le), "sender id (int64 LE) found in blob");
                n++;
            }
            check(n > 0, "privacy check ran");
        }
        // delete anonymizes (keeps keys, link hashes; counts not senders)
        {
            Filled f = filled(new LuminaAdShieldAccount(1, TEST_KEY, null));
            LuminaAdShieldStore.Row before = null;
            for (LuminaAdShieldStore.Row r : f.chat.rows.values()) {
                if (r.msgId == 1) {
                    before = r;
                }
            }
            long[] links = before.links.clone();
            f.chat.delete(new int[]{1}, f.now + 1);
            ArrayList<LuminaAdShieldStore.Row> anon = new ArrayList<LuminaAdShieldStore.Row>();
            for (LuminaAdShieldStore.Row r : f.chat.rows.values()) {
                if (!r.live()) {
                    anon.add(r);
                }
            }
            if (check(anon.size() == 1, "delete: exactly one anonymous row")) {
                LuminaAdShieldStore.Row a = anon.get(0);
                check(a.msgId == 0 && a.sender == 0, "delete: msgId and sender cleared");
                check((a.flags & LuminaAdShield.F_ANON) != 0, "delete: ANON flag");
                check((a.flags & ~(LuminaAdShield.ANON_KEEP | LuminaAdShield.F_ANON)) == 0, "delete: only ANON_KEEP flags kept");
                check(Arrays.equals(a.links, links), "delete: link hashes kept");
            }
            eq(Decision.UNTRACKED, f.chat.decide(1, f.ctx, null, f.now + 1).state, "deleted message is untracked");
            check(LuminaAdShieldStore.deserialize(f.chat.serialize(), f.ctx.chatId, TEST_KEY) != null, "store with anon row round-trips");
        }
        // row cap (3000 in production; 5 here like the reference test)
        {
            LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
            chat.maxRows = 5;
            ChatContext c = ChatContext.builder(-1).build();
            for (int i = 1; i <= 8; i++) {
                chat.observe(simple(i, 1000 + i, i, null, "member", "私聊我 第" + i + "號 t.me/+x" + i), c, 2000);
            }
            ArrayList<Integer> ids = new ArrayList<Integer>();
            for (LuminaAdShieldStore.Row r : chat.rows.values()) {
                ids.add(r.msgId);
            }
            Collections.sort(ids);
            eq(Arrays.asList(4, 5, 6, 7, 8), ids, "row cap keeps the newest by (max(date, seen), rowId)");
        }
        // eviction by max(date, seen)
        {
            LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
            ChatContext c = ChatContext.builder(-1).build();
            long day = LuminaAdShield.DAY;
            chat.observe(simple(1, 0, 1, null, "member", "私聊我領取 t.me/+abc"), c, 100 * day);
            chat.compact(100 * day + 8 * day);
            eq(1, chat.rowCount(), "row kept at exactly 8 days after seen");
            chat.compact(100 * day + 8 * day + 1);
            eq(0, chat.rowCount(), "row evicted after 8 days");
        }
        // value-range checks on forged stores (mutate, serialize: bodyHash is recomputed)
        {
            LinkedHashMap<String, StoreMut> cases = new LinkedHashMap<String, StoreMut>();
            cases.put("date<0", c -> c.rows.firstEntry().getValue().date = -1);
            cases.put("seen>2^40", c -> c.rows.firstEntry().getValue().seen = (1L << 40) + 1);
            cases.put("msgId>2^31-1", c -> c.rows.firstEntry().getValue().msgId = 0x80000000);
            cases.put("bad kind", c -> {
                LuminaAdShieldStore.Row r = c.rows.firstEntry().getValue();
                r.keys = Arrays.copyOf(r.keys, r.keys.length + 1);
                r.keys[r.keys.length - 1] = new Key(12, 1);       // 9..11 are VARL / VARS / BLOCK since v3
            });
            cases.put("PRIV without ENDORSING", c -> c.rows.firstEntry().getValue().flags |= LuminaAdShield.F_PRIV);
            cases.put("PRIV_STRONG without PRIV", c -> c.rows.firstEntry().getValue().flags |= LuminaAdShield.F_PRIV_STRONG);
            cases.put("anon flag mismatch", c -> c.rows.firstEntry().getValue().flags |= LuminaAdShield.F_ANON);
            cases.put("unknown flag bit", c -> c.rows.firstEntry().getValue().flags |= 1 << 31);
            cases.put("record stamp", c -> c.records.add(new LuminaAdShieldStore.Record(2, -5, 0, new Key[0], new long[0])));
            cases.put("record kind", c -> c.records.add(new LuminaAdShieldStore.Record(3, 5, 0, new Key[0], new long[0])));
            cases.put("notAd with author", c -> c.records.add(new LuminaAdShieldStore.Record(2, 5, 0, 7, new Key[0], new long[0])));
            cases.put("notAd with msgKey", c -> c.records.add(new LuminaAdShieldStore.Record(2, 5, 9, new Key[0], new long[0])));
            cases.put("handle first>last", c -> c.handles.put(123L, new LuminaAdShieldStore.HandleInfo(10, 5, 10)));
            for (Map.Entry<String, StoreMut> e : cases.entrySet()) {
                Filled f = filled(new LuminaAdShieldAccount(1, TEST_KEY, null));
                e.getValue().apply(f.chat);
                check(LuminaAdShieldStore.deserialize(f.chat.serialize(), f.ctx.chatId, TEST_KEY) == null, "forged store rejected: " + e.getKey());
            }
        }
        // observe rejects out-of-range ids / dates / now
        {
            LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
            ChatContext c = ChatContext.builder(-1).build();
            String t = "私聊我 t.me/+abc";
            check(chat.observe(simple(0, 1000, 1, null, "member", t), c, 2000) == null, "observe id 0");
            check(chat.observe(simple(-5, 1000, 1, null, "member", t), c, 2000) == null, "observe id -5");
            check(chat.observe(simple(0x80000000, 1000, 1, null, "member", t), c, 2000) == null, "observe id 2^31");
            check(chat.observe(simple(1, -1, 1, null, "member", t), c, 2000) == null, "observe date -1");
            check(chat.observe(simple(1, (1L << 40) + 1, 1, null, "member", t), c, 2000) == null, "observe date 2^40+1");
            check(chat.observe(simple(1, 1000, 1, null, "member", t), c, -1) == null, "observe now -1");
            eq(0, chat.rowCount(), "nothing stored");
            ChatContext priv = ChatContext.builder(77).type("private").build();
            check(chat.observe(simple(1, 1000, 1, null, "member", t), priv, 2000) == null, "observe in a private chat");
            eq(Decision.INELIGIBLE, chat.decide(1, priv, null, 2000).state, "decide in a private chat");
        }
        // endorsement record upsert
        {
            LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
            ChatContext c = ChatContext.builder(-1).build();
            String ann = "今晚八點語音聊天室開放，歡迎大家來玩";
            for (int k = 0; k < 300; k++) {
                chat.observe(simple(5, 1000, 9, null, "admin", ann), c, 2000 + k);
            }
            int endorse = 0;
            for (LuminaAdShieldStore.Record r : chat.records) {
                endorse += r.kind == LuminaAdShieldStore.REC_ENDORSE ? 1 : 0;
            }
            eq(1, endorse, "300 re-observes -> one endorsement record");
            eq(2299L, chat.records.get(0).stamp, "endorsement stamp = max(now)");
            chat.observe(simple(5, 1000, 9, null, "admin", "改過的公告：今晚九點語音聊天室開放"), c, 3000);
            eq(1, chat.records.size(), "edit -> same record upserted");
            chat.markNotAd(5, c, 3001);
            chat.markNotAd(5, c, 3002);
            int notAd = 0;
            for (LuminaAdShieldStore.Record r : chat.records) {
                notAd += r.kind == LuminaAdShieldStore.REC_NOTAD ? 1 : 0;
            }
            eq(1, notAd, "identical not-ad records only refresh the stamp");
        }
        // not-ad record determinism over load order (> 64 keys)
        {
            Random rnd = new Random(7);
            String[] commonP = new String[2];
            String cn = "一二";
            for (int x = 0; x < 2; x++) {
                commonP[x] = "共用段落第" + cn.charAt(x) + "段的內容要超過十六個中文字才會被當成段落鍵喔";
            }
            String nums = "一二三四五";
            String stems = "甲乙丙丁戊己庚辛壬癸子丑寅卯";
            ArrayList<MessageInput> msgs = new ArrayList<MessageInput>();
            for (int i = 0; i < 5; i++) {
                StringBuilder sb = new StringBuilder();
                sb.append(commonP[0]).append('\n').append(commonP[1]);
                for (int j = 0; j < 14; j++) {
                    sb.append('\n').append("第").append(nums.charAt(i)).append("則訊息自己的段落之")
                            .append(stems.charAt(j)).append("，內容同樣要超過十六個中文字才算");
                }
                msgs.add(simple(i + 1, 1000 + i, 100 + i, null, "member", sb.toString()));
            }
            ChatContext c = ChatContext.builder(-1).build();
            ArrayList<String> results = new ArrayList<String>();
            LuminaAdShieldStore.Record rec = null;
            for (int k = 0; k < 6; k++) {
                ArrayList<MessageInput> order = new ArrayList<MessageInput>(msgs);
                Collections.shuffle(order, rnd);
                LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
                for (MessageInput m : order) {
                    chat.observe(m, c, 5000);
                }
                check(chat.markNotAd(3, c, 5001), "markNotAd(3)");
                for (LuminaAdShieldStore.Record r : chat.records) {
                    if (r.kind == LuminaAdShieldStore.REC_NOTAD) {
                        rec = r;
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (Key kk : rec.keys) {
                    sb.append(kk.kind).append(':').append(hex(kk.hash)).append(',');
                }
                sb.append('|');
                for (long l : rec.links) {
                    sb.append(hex(l)).append(',');
                }
                results.add(sb.toString());
            }
            eq(1, new HashSet<String>(results).size(), "not-ad record identical for 6 load orders");
            eq(LuminaAdShield.MAX_RECORD_KEYS, rec.keys.length, "not-ad record capped at 128 keys");
            boolean ordered = true;
            int lastRank = 0, v2Keys = 0;
            for (Key kk : rec.keys) {
                int rank = LuminaAdShield.isExactKind(kk.kind) ? 0 : kk.kind == LuminaAdShield.KIND_PARA ? 1 : 2;
                if (rank < lastRank) {
                    ordered = false;
                }
                lastRank = rank;
                v2Keys += LuminaAdShield.isV2Kind(kk.kind) ? 1 : 0;
            }
            check(ordered, "record keys: v2 exact keys, then paragraph keys, then v3 keys");
            eq(LuminaAdShield.RECORD_V2_KEYS, v2Keys, "the record starts with the 64 keys v2 would have stored");
            Fingerprint fp3 = LuminaAdShield.fingerprint(msgs.get(2), c, TEST_KEY);
            HashSet<Key> recKeys = new HashSet<Key>(Arrays.asList(rec.keys));
            boolean own = true;
            for (Key kk : fp3.keys()) {
                if (kk.kind == LuminaAdShield.KIND_PARA && !recKeys.contains(kk)) {
                    own = false;
                }
            }
            check(own, "m's own paragraph keys always kept");
        }
        // handles serialized sorted by unsigned hash
        {
            LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
            ChatContext c = ChatContext.builder(-1).build();
            for (int i = 0; i < 40; i++) {
                chat.observe(simple(i + 1, 1000 + i, i, String.format("user_%03d", i), "member", "私聊我 t.me/+x" + i), c, 2000);
            }
            byte[] blob = chat.serialize();
            int nH = (blob[40] & 0xFF) | ((blob[41] & 0xFF) << 8);
            long[] hs = new long[nH];
            for (int i = 0; i < nH; i++) {
                int off = blob.length - 32 * (nH - i);
                long v = 0;
                for (int b = 7; b >= 0; b--) {
                    v = (v << 8) | (blob[off + b] & 0xFFL);
                }
                hs[i] = v;
            }
            boolean sorted = true, hi = false, lo = false;
            for (int i = 0; i < nH; i++) {
                if (i > 0 && AdShieldTables.compareUnsigned(hs[i - 1], hs[i]) > 0) {
                    sorted = false;
                }
                if (hs[i] < 0) {
                    hi = true;
                } else {
                    lo = true;
                }
            }
            eq(40, nH, "40 handles stored");
            check(sorted && hi && lo, "handles sorted unsigned and both halves of the u64 range present");
        }
    }

    // ------------------------------------------------------------------ SPEC §8.3: every rejection rule on crafted blobs
    static final class RowSpec {
        long msgId, album, date, seen, sender, flags;
        int[] kinds = new int[0];
        long[] hashes = new long[0];
        long[] links = new long[0];
        long[] pend = new long[0];
    }

    static final class RecSpec {
        int kind;
        long stamp, msgKey, author;
        int[] kinds = new int[0];
        long[] hashes = new long[0];
        long[] links = new long[0];
    }

    static final class HSpec {
        long hash, first, last, seen;

        HSpec(long hash, long first, long last, long seen) {
            this.hash = hash;
            this.first = first;
            this.last = last;
            this.seen = seen;
        }
    }

    /** Independent writer of the SPEC §8.3 layout (does not use the engine's serializer). */
    static final class Blob {
        static final long CHAT = -1001234L;
        byte[] magic = {'L', 'G', 'A', 'S'};
        int fv = 3, av = 3;
        long tv = AdShieldTables.TABLES_VERSION_U32 & 0xFFFFFFFFL;
        Long keyCheck, chatTag, nRows, nRec, nHandles;
        long nextRid = 100;
        long bodyHashXor = 0;
        int truncate = 0;
        byte[] trailing = new byte[0];
        ArrayList<RowSpec> rows = new ArrayList<RowSpec>();
        ArrayList<RecSpec> recs = new ArrayList<RecSpec>();
        ArrayList<HSpec> handles = new ArrayList<HSpec>();

        static void u8(java.io.ByteArrayOutputStream o, long v) {
            o.write((int) (v & 0xFF));
        }

        static void le(java.io.ByteArrayOutputStream o, long v, int n) {
            for (int i = 0; i < n; i++) {
                o.write((int) ((v >>> (8 * i)) & 0xFF));
            }
        }

        byte[] build() {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            for (RowSpec r : rows) {
                le(b, r.msgId, 4);
                le(b, r.album, 8);
                le(b, r.date, 8);
                le(b, r.seen, 8);
                le(b, r.sender, 8);
                le(b, r.flags, 4);
                u8(b, r.kinds.length);
                for (int i = 0; i < r.kinds.length; i++) {
                    u8(b, r.kinds[i]);
                    le(b, r.hashes[i], 8);
                }
                u8(b, r.links.length);
                for (long l : r.links) {
                    le(b, l, 8);
                }
                u8(b, r.pend.length);
                for (long l : r.pend) {
                    le(b, l, 8);
                }
            }
            for (RecSpec r : recs) {
                u8(b, r.kind);
                le(b, r.stamp, 8);
                le(b, r.msgKey, 8);
                le(b, r.author, 8);
                u8(b, r.kinds.length);
                for (int i = 0; i < r.kinds.length; i++) {
                    u8(b, r.kinds[i]);
                    le(b, r.hashes[i], 8);
                }
                u8(b, r.links.length);
                for (long l : r.links) {
                    le(b, l, 8);
                }
            }
            for (HSpec h : handles) {
                le(b, h.hash, 8);
                le(b, h.first, 8);
                le(b, h.last, 8);
                le(b, h.seen, 8);
            }
            byte[] body = b.toByteArray();
            if (truncate > 0) {
                body = Arrays.copyOf(body, body.length - truncate);
            }
            if (trailing.length > 0) {
                byte[] t = Arrays.copyOf(body, body.length + trailing.length);
                System.arraycopy(trailing, 0, t, body.length, trailing.length);
                body = t;
            }
            java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream();
            h.write(magic, 0, magic.length);
            le(h, fv, 2);
            le(h, av, 2);
            le(h, tv, 4);
            le(h, 0, 4);
            le(h, keyCheck != null ? keyCheck : AdShieldTables.keyedHash(TEST_KEY, "check", "LGAS"), 8);
            le(h, chatTag != null ? chatTag : AdShieldTables.keyedHash(TEST_KEY, "chat", Long.toString(CHAT)), 8);
            le(h, nRows != null ? nRows : rows.size(), 4);
            le(h, nRec != null ? nRec : recs.size(), 4);
            le(h, nHandles != null ? nHandles : handles.size(), 4);
            le(h, nextRid, 4);
            le(h, AdShieldTables.keyedHashBytes(TEST_KEY, "body", body) ^ bodyHashXor, 8);
            h.write(body, 0, body.length);
            return h.toByteArray();
        }
    }

    static RowSpec liveRow(long msgId) {
        RowSpec r = new RowSpec();
        r.msgId = msgId;
        r.album = 0;
        r.date = 1000;
        r.seen = 2000;
        r.sender = 0x8123456789abcdefL;
        r.flags = LuminaAdShield.F_HAS_TEXT | 2;
        r.kinds = new int[]{3, 6, 6};
        r.hashes = new long[]{0xf000000000000001L, 0x0000000000000002L, 0x8000000000000003L};
        r.links = new long[]{0x9999999999999999L};
        r.pend = new long[]{0x1111111111111111L};
        return r;
    }

    static RowSpec anonRow() {
        RowSpec r = new RowSpec();
        r.msgId = 0;
        r.date = 1100;
        r.seen = 2100;
        r.sender = 0;
        r.flags = LuminaAdShield.F_ANON | LuminaAdShield.F_HAS_MEDIA_KEY;
        r.kinds = new int[]{2};
        r.hashes = new long[]{0xabcdef0123456789L};
        return r;
    }

    static RecSpec rec(int kind, long stamp) {
        RecSpec r = new RecSpec();
        r.kind = kind;
        r.stamp = stamp;
        r.msgKey = kind == 1 ? 0xfeedfacecafebeefL : 0;
        r.kinds = new int[]{3};
        r.hashes = new long[]{0xf000000000000001L};
        r.links = new long[]{0x9999999999999999L};
        return r;
    }

    static Blob baseline() {
        Blob b = new Blob();
        b.rows.add(liveRow(5));
        b.rows.add(anonRow());
        b.recs.add(rec(1, 1500));
        b.recs.add(rec(2, 1600));
        b.handles.add(new HSpec(0x0123456789abcdefL, 900, 1000, 2000));      // sorted unsigned
        b.handles.add(new HSpec(0xfedcba9876543210L, 800, 1000, 2000));
        return b;
    }

    interface BlobMut {
        void apply(Blob b);
    }

    static long[] seq(int n, long base) {
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = base + i;
        }
        return a;
    }

    static int[] fill(int n, int v) {
        int[] a = new int[n];
        Arrays.fill(a, v);
        return a;
    }

    static void rejection() {
        section("spec-8.3-rejections");
        byte[] base = baseline().build();
        LuminaAdShieldStore ok = LuminaAdShieldStore.deserialize(base, Blob.CHAT, TEST_KEY);
        if (check(ok != null, "baseline crafted blob accepted")) {
            check(Arrays.equals(ok.serialize(), base), "engine re-serializes the independently crafted blob byte for byte");
        }
        final long MAXD = LuminaAdShield.MAX_DATE;
        LinkedHashMap<String, BlobMut> reject = new LinkedHashMap<String, BlobMut>();
        LinkedHashMap<String, BlobMut> accept = new LinkedHashMap<String, BlobMut>();
        // header
        reject.put("magic", b -> b.magic = new byte[]{'L', 'G', 'A', 'X'});
        reject.put("formatVersion 2", b -> b.fv = 2);
        reject.put("formatVersion 4", b -> b.fv = 4);
        reject.put("algoVersion 2", b -> b.av = 2);
        reject.put("algoVersion 4", b -> b.av = 4);
        reject.put("tablesVersion", b -> b.tv ^= 1);
        reject.put("keyCheck", b -> b.keyCheck = AdShieldTables.keyedHash(new byte[16], "check", "LGAS"));
        reject.put("chatTag", b -> b.chatTag = AdShieldTables.keyedHash(TEST_KEY, "chat", "-1001235"));
        reject.put("bodyHash", b -> b.bodyHashXor = 1);
        // counts
        reject.put("rowCount 3001", b -> {
            b.rows.clear();
            for (int i = 0; i < 3001; i++) {
                RowSpec r = liveRow(i + 1);
                r.pend = new long[0];
                b.rows.add(r);
            }
            b.nextRid = 5000;
        });
        accept.put("rowCount 3000", b -> {
            b.rows.clear();
            for (int i = 0; i < 3000; i++) {
                RowSpec r = liveRow(i + 1);
                r.pend = new long[0];
                b.rows.add(r);
            }
            b.nextRid = 5000;
        });
        reject.put("recordCount 401 (201+200)", b -> {
            b.recs.clear();
            for (int i = 0; i < 201; i++) {
                b.recs.add(rec(1, 1500));
            }
            for (int i = 0; i < 200; i++) {
                b.recs.add(rec(2, 1500));
            }
        });
        accept.put("recordCount 400 (200+200)", b -> {
            b.recs.clear();
            for (int i = 0; i < 200; i++) {
                b.recs.add(rec(1, 1500));
                b.recs.add(rec(2, 1500));
            }
        });
        reject.put("endorsement records 201", b -> {
            b.recs.clear();
            for (int i = 0; i < 201; i++) {
                b.recs.add(rec(1, 1500));
            }
        });
        reject.put("not-ad records 201", b -> {
            b.recs.clear();
            for (int i = 0; i < 201; i++) {
                b.recs.add(rec(2, 1500));
            }
        });
        reject.put("handleCount 2001", b -> {
            b.handles.clear();
            for (int i = 0; i < 2001; i++) {
                b.handles.add(new HSpec(i + 1, 900, 1000, 2000));
            }
        });
        accept.put("handleCount 2000", b -> {
            b.handles.clear();
            for (int i = 0; i < 2000; i++) {
                b.handles.add(new HSpec(i + 1, 900, 1000, 2000));
            }
        });
        reject.put("header rowCount > body (truncation)", b -> b.nRows = 3L);
        reject.put("header rowCount < body (trailing)", b -> b.nRows = 1L);
        // dates
        reject.put("row date -1", b -> b.rows.get(0).date = -1);
        reject.put("row date 2^40+1", b -> b.rows.get(0).date = MAXD + 1);
        reject.put("row seen -1", b -> b.rows.get(0).seen = -1);
        reject.put("row seen 2^40+1", b -> b.rows.get(0).seen = MAXD + 1);
        accept.put("row date 2^40, seen 0", b -> {
            b.rows.get(0).date = MAXD;
            b.rows.get(0).seen = 0;
        });
        reject.put("record stamp -1", b -> b.recs.get(0).stamp = -1);
        reject.put("record stamp 2^40+1", b -> b.recs.get(1).stamp = MAXD + 1);
        reject.put("handle first -1", b -> b.handles.get(0).first = -1);
        reject.put("handle last 2^40+1", b -> b.handles.get(0).last = MAXD + 1);
        reject.put("handle seen -1", b -> b.handles.get(1).seen = -1);
        reject.put("handle seen 2^40+1", b -> b.handles.get(1).seen = MAXD + 1);
        reject.put("handle first > last", b -> b.handles.get(0).first = 1001);
        accept.put("handle first == last", b -> b.handles.get(0).first = 1000);
        // ids / anonymity / duplicates
        reject.put("msgId 2^31", b -> b.rows.get(0).msgId = 0x80000000L);
        reject.put("msgId 2^32-1", b -> b.rows.get(0).msgId = 0xFFFFFFFFL);
        accept.put("msgId 2^31-1", b -> b.rows.get(0).msgId = 0x7FFFFFFFL);
        reject.put("msgId 0 without ANON", b -> b.rows.get(1).flags &= ~LuminaAdShield.F_ANON);
        reject.put("ANON with msgId", b -> b.rows.get(0).flags |= LuminaAdShield.F_ANON);
        reject.put("anonymous row senderKey != 0", b -> b.rows.get(1).sender = 7);
        reject.put("duplicate msgId", b -> b.rows.add(liveRow(5)));
        accept.put("two anonymous rows", b -> b.rows.add(anonRow()));
        reject.put("duplicate handle", b -> b.handles.add(new HSpec(0xfedcba9876543210L, 800, 1000, 2000)));
        // flags
        for (final int bit : new int[]{17, 31}) {
            reject.put("flags reserved bit " + bit, b -> b.rows.get(0).flags |= 1L << bit);
        }
        reject.put("flags PRIV without ENDORSING", b -> b.rows.get(0).flags |= LuminaAdShield.F_PRIV);
        reject.put("flags PRIV_STRONG without PRIV", b -> b.rows.get(0).flags |= LuminaAdShield.F_PRIV_STRONG);
        reject.put("flags PRIV_STRONG + ENDORSING without PRIV",
                b -> b.rows.get(0).flags |= LuminaAdShield.F_PRIV_STRONG | LuminaAdShield.F_ENDORSING);
        accept.put("flags PRIV + ENDORSING", b -> b.rows.get(0).flags |= LuminaAdShield.F_PRIV | LuminaAdShield.F_ENDORSING);
        accept.put("flags PRIV_STRONG + PRIV + ENDORSING", b -> b.rows.get(0).flags |=
                LuminaAdShield.F_PRIV_STRONG | LuminaAdShield.F_PRIV | LuminaAdShield.F_ENDORSING);
        // list limits
        reject.put("row nKeys 73", b -> {
            b.rows.get(0).kinds = fill(73, 6);
            b.rows.get(0).hashes = seq(73, 10);
        });
        accept.put("row nKeys 72", b -> {
            b.rows.get(0).kinds = fill(72, 6);
            b.rows.get(0).hashes = seq(72, 10);
        });
        reject.put("row nLinks 17", b -> b.rows.get(0).links = seq(17, 50));
        accept.put("row nLinks 16", b -> b.rows.get(0).links = seq(16, 50));
        reject.put("row nPend 17", b -> b.rows.get(0).pend = seq(17, 70));
        accept.put("row nPend 16", b -> b.rows.get(0).pend = seq(16, 70));
        reject.put("record nKeys 129", b -> {
            b.recs.get(0).kinds = fill(129, 6);
            b.recs.get(0).hashes = seq(129, 10);
        });
        accept.put("record nKeys 128", b -> {
            b.recs.get(0).kinds = fill(128, 6);
            b.recs.get(0).hashes = seq(128, 10);
        });
        reject.put("record nLinks 65", b -> b.recs.get(1).links = seq(65, 90));
        accept.put("record nLinks 64", b -> b.recs.get(1).links = seq(64, 90));
        // kinds
        reject.put("row key kind 0", b -> b.rows.get(0).kinds[0] = 0);
        reject.put("row key kind 12", b -> b.rows.get(0).kinds[1] = 12);
        accept.put("row key kinds 1..11", b -> {
            b.rows.get(0).kinds = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
            b.rows.get(0).hashes = seq(11, 30);
        });
        reject.put("record key kind 0", b -> b.recs.get(0).kinds[0] = 0);
        reject.put("record key kind 12", b -> b.recs.get(1).kinds[0] = 12);
        accept.put("record key kinds 1..11", b -> {
            b.recs.get(1).kinds = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
            b.recs.get(1).hashes = seq(11, 30);
        });
        reject.put("record kind 0", b -> b.recs.get(0).kind = 0);
        reject.put("record kind 3", b -> b.recs.get(1).kind = 3);
        reject.put("not-ad record with authorKey", b -> b.recs.get(1).author = 5);
        reject.put("not-ad record with msgKey", b -> b.recs.get(1).msgKey = 5);
        accept.put("endorsement record with authorKey", b -> b.recs.get(0).author = 0x8000000000000005L);
        // truncation / trailing bytes (bodyHash recomputed over the damaged body, so only the parser can catch it)
        reject.put("truncated body (1 byte)", b -> b.truncate = 1);
        reject.put("truncated body (one handle)", b -> b.truncate = 32);
        reject.put("trailing byte", b -> b.trailing = new byte[]{0});
        reject.put("trailing handle-sized garbage", b -> b.trailing = new byte[32]);

        int nRules = 0;
        for (Map.Entry<String, BlobMut> e : reject.entrySet()) {
            Blob b = baseline();
            e.getValue().apply(b);
            caseBegin();
            check(LuminaAdShieldStore.deserialize(b.build(), Blob.CHAT, TEST_KEY) == null, "must reject: " + e.getKey());
            caseEnd();
            nRules++;
        }
        for (Map.Entry<String, BlobMut> e : accept.entrySet()) {
            Blob b = baseline();
            e.getValue().apply(b);
            caseBegin();
            check(LuminaAdShieldStore.deserialize(b.build(), Blob.CHAT, TEST_KEY) != null, "boundary must be accepted: " + e.getKey());
            caseEnd();
        }
        // short files and whole-blob variants
        caseBegin();
        check(LuminaAdShieldStore.deserialize(Arrays.copyOf(base, 55), Blob.CHAT, TEST_KEY) == null, "length 55 rejected");
        check(LuminaAdShieldStore.deserialize(new byte[0], Blob.CHAT, TEST_KEY) == null, "length 0 rejected");
        check(LuminaAdShieldStore.deserialize(Arrays.copyOf(base, 56), Blob.CHAT, TEST_KEY) == null, "header only (counts > 0) rejected");
        check(LuminaAdShieldStore.deserialize(base, Blob.CHAT, new byte[16]) == null, "other key rejected");
        check(LuminaAdShieldStore.deserialize(base, Blob.CHAT + 1, TEST_KEY) == null, "other chat rejected");
        caseEnd();
        cur.note = "(" + nRules + " reject variants + " + accept.size() + " boundary accepts + 1 short/other-key group)";
    }

    // ------------------------------------------------------------------ account level: ForgetAll, orphans, wipe, cap, LRU
    static void accountTests() throws IOException {
        section("account (ForgetAll etc.)");
        // ForgetAll: files deleted, writes refused afterwards
        {
            File d = tempDir("adshield_forget");
            try {
                LuminaAdShieldAccount acct = new LuminaAdShieldAccount(777, TEST_KEY, d);
                Filled f = filled(acct);
                check(acct.flush(), "flush writes");
                File acctDir = new File(d, acct.dirName());
                check(sortedNames(acctDir).size() > 0, "account dir has files");
                for (String name : sortedNames(acctDir)) {
                    check(!name.contains(Long.toString(f.ctx.chatId).replace("-", "")), "file name leaks no chat id");
                }
                check(!acct.dirName().contains("777"), "dir name leaks no user id");
                acct.forgetAll();
                check(!acctDir.exists(), "forgetAll deletes the account dir");
                check(!acct.flush(), "flush after forgetAll refused");
                check(!acctDir.exists(), "delayed flush did not recreate files");
                boolean refusedObserve = false, refusedChat = false, refusedDelete = false, refusedNotAd = false;
                Map<String, Object> first = obj(obj(list(f.sc.get("events")).get(0)).get("message"));
                try {
                    f.chat.observe(msg(first), f.ctx, f.now);
                } catch (LuminaAdShieldStore.ForgottenException e) {
                    refusedObserve = true;
                }
                try {
                    acct.chat(f.ctx.chatId);
                } catch (LuminaAdShieldStore.ForgottenException e) {
                    refusedChat = true;
                }
                try {
                    f.chat.delete(new int[]{1}, f.now);
                } catch (LuminaAdShieldStore.ForgottenException e) {
                    refusedDelete = true;
                }
                try {
                    f.chat.markNotAd(1, f.ctx, f.now);
                } catch (LuminaAdShieldStore.ForgottenException e) {
                    refusedNotAd = true;
                }
                check(refusedObserve && refusedChat && refusedDelete && refusedNotAd,
                        "after ForgetAll: observe/chat/delete/markNotAd all refused");
                check(!acctDir.exists() && d.exists(), "root kept, account dir still gone");
            } finally {
                rmrf(d);
            }
        }
        // per-account directory by userId
        {
            LuminaAdShieldAccount a = new LuminaAdShieldAccount(111, TEST_KEY, null);
            LuminaAdShieldAccount b = new LuminaAdShieldAccount(222, TEST_KEY, null);
            check(!a.dirName().equals(b.dirName()) && !a.dirName().contains("111"), "dir per userId, hashed");
        }
        // reload from disk
        {
            File d = tempDir("adshield_reload");
            try {
                LuminaAdShieldAccount acct = new LuminaAdShieldAccount(9, TEST_KEY, d);
                Filled f = filled(acct);
                acct.flush();
                LuminaAdShieldAccount acct2 = new LuminaAdShieldAccount(9, TEST_KEY, d);
                check(Arrays.equals(acct2.chat(f.ctx.chatId).serialize(), f.chat.serialize()), "reload from disk: identical store");
            } finally {
                rmrf(d);
            }
        }
        ChatContext c1 = ChatContext.builder(-1).build();
        String t = "私聊我 t.me/+abc";
        // purge orphans after a key change; other accounts survive
        {
            File d = tempDir("adshield_purge");
            try {
                byte[] oldKey = new byte[16];
                Arrays.fill(oldKey, (byte) 1);
                LuminaAdShieldAccount old = new LuminaAdShieldAccount(777, oldKey, d);
                old.chat(-1).observe(simple(1, 1000, 5, null, "member", t), c1, 2000);
                old.flush();
                Files.write(new File(new File(d, old.dirName()), "junk.tmp").toPath(), new byte[]{'x'});
                LuminaAdShieldAccount neu = new LuminaAdShieldAccount(777, TEST_KEY, d);
                LuminaAdShieldAccount other = new LuminaAdShieldAccount(888, TEST_KEY, d);
                ChatContext c2 = ChatContext.builder(-2).build();
                other.chat(-2).observe(simple(1, 1000, 5, null, "member", t), c2, 2000);
                other.flush();
                check(!neu.dirName().equals(old.dirName()), "new key -> new dir name");
                Files.write(new File(d, "stray-file").toPath(), new byte[]{'y'});
                int removed = LuminaAdShieldAccount.purgeOrphans(d, TEST_KEY, LuminaAdShieldAccount.JAVA_IO);
                eq(Collections.singletonList(other.dirName()), sortedNames(d), "only the current-key account dir survives purgeOrphans");
                check(removed >= 3, "orphan chat file, temp file and stray file removed (removed=" + removed + ")");
            } finally {
                rmrf(d);
            }
        }
        // logout purges orphans; panic wipes the whole root
        {
            File d = tempDir("adshield_wipe");
            try {
                File root = new File(d, "luminagram_adshield");
                byte[] k2 = new byte[16];
                Arrays.fill(k2, (byte) 2);
                LuminaAdShieldAccount old = new LuminaAdShieldAccount(1, k2, root);
                old.chat(-1).observe(simple(1, 1000, 5, null, "member", t), c1, 2000);
                old.flush();
                LuminaAdShieldAccount a = new LuminaAdShieldAccount(1, TEST_KEY, root);
                LuminaAdShieldAccount b = new LuminaAdShieldAccount(2, TEST_KEY, root);
                for (LuminaAdShieldAccount acct : new LuminaAdShieldAccount[]{a, b}) {
                    acct.chat(-1).observe(simple(1, 1000, 5, null, "member", t), c1, 2000);
                    acct.flush();
                }
                a.forgetAll();
                eq(Collections.singletonList(b.dirName()), sortedNames(root), "logout: own dir + orphans gone, other account kept");
                b.forgetAll(true);
                check(!root.exists(), "panic wipe removes the whole root");
                check(!b.flush(), "no flush after panic");
            } finally {
                rmrf(d);
            }
        }
        // total cap: LRU by file mtime
        {
            File d = tempDir("adshield_cap");
            try {
                LuminaAdShieldAccount acct = new LuminaAdShieldAccount(1, TEST_KEY, d);
                for (int i = 0; i < 4; i++) {
                    ChatContext c = ChatContext.builder(-i - 1).build();
                    acct.chat(-i - 1).observe(simple(1, 1000, 5, null, "member", t + i), c, 2000);
                }
                acct.flush();
                File ad = new File(d, acct.dirName());
                List<String> files = sortedNames(ad);
                for (int k = 0; k < files.size(); k++) {
                    new File(ad, files.get(k)).setLastModified(1_700_000_000_000L + k * 10_000L);
                }
                long size = new File(ad, files.get(0)).length();
                eq(2, LuminaAdShieldAccount.enforceTotalCap(d, 2 * size, LuminaAdShieldAccount.JAVA_IO), "two oldest files removed");
                eq(files.subList(2, 4), sortedNames(ad), "newest two kept");
            } finally {
                rmrf(d);
            }
        }
        // feature off / untouched chat creates no files
        {
            File d = tempDir("adshield_off");
            try {
                LuminaAdShieldAccount acct = new LuminaAdShieldAccount(5, TEST_KEY, d);
                check(sortedNames(d).isEmpty(), "constructing an account creates nothing");
                acct.chat(-9);
                acct.flush();
                check(sortedNames(d).isEmpty(), "loading an untouched chat and flushing creates nothing");
                ChatContext priv = ChatContext.builder(-9).type("private").build();
                acct.chat(-9).observe(simple(1, 1000, 5, null, "member", t), priv, 2000);
                acct.flush();
                check(sortedNames(d).isEmpty(), "ineligible chat: nothing written");
            } finally {
                rmrf(d);
            }
        }
        // memory cap: at most 8 chats loaded; evicted chats are flushed and reload intact
        {
            File d = tempDir("adshield_lru");
            try {
                LuminaAdShieldAccount acct = new LuminaAdShieldAccount(5, TEST_KEY, d);
                byte[] firstBlob = null;
                for (int i = 0; i < 9; i++) {
                    ChatContext c = ChatContext.builder(-100 - i).build();
                    LuminaAdShieldStore s = acct.chat(-100 - i);
                    s.observe(simple(1, 1000, 5, null, "member", t + i), c, 2000);
                    if (i == 0) {
                        firstBlob = s.serialize();
                    }
                }
                eq(8, acct.loadedChatCount(), "at most 8 chats in memory");
                File evicted = new File(new File(d, acct.dirName()), acct.chatFileName(-100));
                check(evicted.exists(), "evicted chat was flushed");
                check(Arrays.equals(firstBlob, acct.chat(-100).serialize()), "evicted chat reloads intact");
            } finally {
                rmrf(d);
            }
        }
        // in-memory FileOps seam is honoured (no java.io use when injected)
        {
            final ArrayList<String> calls = new ArrayList<String>();
            LuminaAdShieldAccount.FileOps fake = new LuminaAdShieldAccount.FileOps() {
                public String[] list(File dir) {
                    calls.add("list");
                    return null;
                }

                public boolean isDirectory(File f) {
                    calls.add("isDirectory");
                    return false;
                }

                public boolean exists(File f) {
                    calls.add("exists");
                    return false;
                }

                public byte[] read(File f, int maxBytes) {
                    calls.add("read");
                    return new byte[0];
                }

                public void writeAtomic(File target, byte[] data) {
                    calls.add("write");
                }

                public boolean delete(File f) {
                    return true;
                }

                public boolean deleteRecursive(File f) {
                    return true;
                }

                public boolean mkdirs(File dir) {
                    calls.add("mkdirs");
                    return true;
                }

                public long length(File f) {
                    return 0;
                }

                public long lastModified(File f) {
                    return 0;
                }
            };
            LuminaAdShieldAccount acct = new LuminaAdShieldAccount(5, TEST_KEY, new File("/nonexistent/adshield"), fake);
            acct.chat(-1).observe(simple(1, 1000, 5, null, "member", t), c1, 2000);
            check(acct.flush() && calls.contains("write") && calls.contains("exists"), "FileOps seam used for read/write");
        }
    }

    // ------------------------------------------------------------------ robustness: overflow on a small stack is contained
    static void robustness() throws Exception {
        section("robustness");
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("a.");
        }
        sb.append("com");
        final LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
        final ChatContext c = ChatContext.builder(-1).build();
        final String[] outcome = new String[1];
        Thread th = new Thread(null, new Runnable() {
            @Override
            public void run() {
                try {
                    Fingerprint fp = chat.observe(simple(1, 1000, 5, null, "member", sb.toString()), c, 2000);
                    outcome[0] = fp == null ? "skipped" : "processed";
                } catch (Throwable t) {
                    outcome[0] = "escaped:" + t.getClass().getSimpleName();
                }
            }
        }, "tiny-stack", 64 * 1024);
        th.start();
        th.join();
        check(outcome[0] != null && !outcome[0].startsWith("escaped"), "deep-recursion input on a 64 KB stack never escapes observe ("
                + outcome[0] + ")");
        chat.observe(simple(2, 1000, 5, null, "member", "私聊我 t.me/+abc"), c, 2000);
        check(chat.byMsg.containsKey(2), "store still usable afterwards");
        // the same input on the 16 MB engine thread is processed
        LuminaAdShieldStore chat2 = new LuminaAdShieldStore(-1, TEST_KEY);
        check(chat2.observe(simple(1, 1000, 5, null, "member", sb.toString()), c, 2000) != null,
                "20000-label domain processed on the engine thread");
        cur.note = "(64 KB stack: " + outcome[0] + ")";
    }

    // ------------------------------------------------------------------ performance: decide() on a 3000-row chat
    static void perf() {
        section("perf (3000 rows)");
        final long chatId = -100777L;
        ChatContext c = ChatContext.builder(chatId).type("supergroup").username("perf_grp")
                .memberHandles(Arrays.asList("admin_one", "helper_bot")).build();
        LuminaAdShieldStore chat = new LuminaAdShieldStore(chatId, TEST_KEY);
        long base = 1_795_000_000L;
        int n = 3000;
        long tObs = System.nanoTime();
        for (int i = 0; i < n; i++) {
            String text;
            switch (i % 3) {
                case 0:   // one flood ad, 1000 copies
                    text = "限時優惠！加V：dealhub_88 日賺三千不是夢，名額有限速來";
                    break;
                case 1:   // 100 templates x 10
                    text = "本週特價第" + (i % 100) + "款商品，私聊我了解詳情 t.me/+promo" + (i % 100);
                    break;
                default:  // 1000 unique messages with a link
                    text = "今天看到一則新聞覺得很有意思" + new String(Character.toChars(0x4E00 + i))
                            + new String(Character.toChars(0x6000 + i)) + " https://news" + i + ".example.com/a" + i;
            }
            chat.observe(simple(i + 1, base + i * 120L, 5000 + (i % 400), "user_" + (i % 400), "member", text), c, base + n * 120L);
        }
        double obsMs = (System.nanoTime() - tObs) / 1e6;
        long now = base + n * 120L;
        eq(n, chat.rowCount(), "3000 rows stored");
        Settings st = new Settings(LuminaAdShield.SENS_STANDARD);
        // warm up the JIT
        for (int rep = 0; rep < 3; rep++) {
            for (int id = 1; id <= n; id++) {
                chat.decide(id, c, st, now);
            }
            chat.mutations++;
        }
        // cold decide: the cached unit view is rebuilt (first decide after any observe/delete)
        int coldRuns = 30;
        double coldMax = 0, coldSum = 0;
        Random r = new Random(1);
        for (int k = 0; k < coldRuns; k++) {
            chat.mutations++;
            int id = 1 + r.nextInt(n);
            long a = System.nanoTime();
            chat.decide(id, c, st, now);
            double ms = (System.nanoTime() - a) / 1e6;
            coldSum += ms;
            coldMax = Math.max(coldMax, ms);
        }
        // warm decide: every message of the chat once (UI recompute pass)
        chat.mutations++;
        chat.decide(1, c, st, now);
        int folded = 0;
        double warmMax = 0;
        long a = System.nanoTime();
        for (int id = 1; id <= n; id++) {
            long b = System.nanoTime();
            if (chat.decide(id, c, st, now).fold) {
                folded++;
            }
            warmMax = Math.max(warmMax, (System.nanoTime() - b) / 1e6);
        }
        double passMs = (System.nanoTime() - a) / 1e6;
        // typical UI step: one new message, then decide the ~30 visible messages
        long b = System.nanoTime();
        chat.observe(simple(n + 1, now, 9999, null, "member", "限時優惠！加V：dealhub_88 日賺三千不是夢，名額有限速來"), c, now);
        for (int id = n - 29; id <= n + 1; id++) {
            chat.decide(id, c, st, now);
        }
        double stepMs = (System.nanoTime() - b) / 1e6;
        check(coldSum / coldRuns < 100, "cold decide average < 100 ms");
        check(passMs / n < 10, "warm decide average < 10 ms");
        check(folded > 0, "the flood is folded");
        cur.note = String.format("observe x3000 %.0f ms (%.3f ms each); cold decide avg %.2f ms / max %.2f ms; "
                        + "warm decide avg %.3f ms / max %.2f ms (pass of 3000: %.0f ms, %d folded); observe+30 decides %.2f ms",
                obsMs, obsMs / n, coldSum / coldRuns, coldMax, passMs / n, warmMax, passMs, folded, stepMs);
    }

    // ------------------------------------------------------------------ v3 unit tests (ref TestV3 / TestV3Fixes)
    /** JSON with single quotes (the test texts never contain one). */
    static Map<String, Object> J(String singleQuoted) {
        return obj(MiniJson.parse(singleQuoted.replace('\'', '"')));
    }

    static MessageInput M(String singleQuoted) {
        return msg(J(singleQuoted));
    }

    static String joinAll(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            sb.append(x);
        }
        return sb.toString();
    }

    static List<String> varList(List<LuminaAdShield.VarKey> vs) {
        ArrayList<String> out = new ArrayList<String>();
        for (LuminaAdShield.VarKey v : vs) {
            out.add(LuminaAdShield.KIND_NAME[v.kind] + ":" + v.payload);
        }
        return out;
    }

    static List<Integer> groupIds(String[] texts) {
        LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
        ChatContext c = ChatContext.builder(-1).build();
        for (int i = 0; i < texts.length; i++) {
            chat.observe(simple(i + 1, 1000 + i, 5, null, "member", texts[i]), c, 2000);
        }
        LuminaAdShieldStore.View v = chat.view(c, 2000);
        LuminaAdShieldStore.Unit m = chat.unitOf(v, 1);
        ArrayList<Integer> ids = new ArrayList<Integer>();
        for (LuminaAdShieldStore.Unit u : LuminaAdShieldStore.group(v, m).units) {
            for (LuminaAdShieldStore.Row r : u.rows) {
                ids.add(r.msgId);
            }
        }
        Collections.sort(ids);
        return ids;
    }

    static void v3Rules() throws IOException {
        section("v3-rules");
        final String V = "VAR:", VL = "VARL:", VS = "VARS:";
        // test_var_key_rules
        {
            List<String> lines = Arrays.asList("丝滑提交", "限时特价云端会员年卡全网最低价格", "有需要的请私聊我下单先到先得");
            eq(Arrays.asList(V + joinAll(lines), VS + joinAll(lines.subList(1, 3))),
                    varList(LuminaAdShield.varKeys(lines)), "var keys: full + strict drop of the short first line");
            List<String> loose = Arrays.asList("一二三四五六七八九十", lines.get(1), lines.get(2));
            eq(Arrays.asList(V + joinAll(loose), VL + joinAll(loose.subList(1, 3))),
                    varList(LuminaAdShield.varKeys(loose)), "var keys: loose drop (2D <= K < 4D)");
            eq(Collections.emptyList(), varList(LuminaAdShield.varKeys(Arrays.asList("短"))), "var keys: K < 24 -> none");
            eq(Arrays.asList(V + "一二三四五六七八九十一二"),
                    varList(LuminaAdShield.varKeys(Arrays.asList("一二三四五六七八九十一二"))), "var keys: single line");
            List<LuminaAdShield.VarKey> dup = LuminaAdShield.varKeys(Arrays.asList("价目表如下请参考", "#件起", "#件起", "详情请私聊客服人员"));
            HashSet<String> ps = new HashSet<String>();
            for (LuminaAdShield.VarKey vk : dup) {
                ps.add(vk.payload);
            }
            eq(dup.size(), ps.size(), "var keys deduplicated by payload");
            ArrayList<String> many = new ArrayList<String>();
            for (int i = 0; i < 50; i++) {
                many.add(String.format("第%02d行商品名称", i));
            }
            List<LuminaAdShield.VarKey> vs = LuminaAdShield.varKeys(many);
            eq(LuminaAdShield.MAX_VAR, vs.size(), "var keys capped at 34 (full + 16 + 16 + head/tail)");
            boolean kindsOk = vs.get(0).kind == LuminaAdShield.KIND_VAR;
            ArrayList<String> pays = new ArrayList<String>();
            for (int i = 0; i < vs.size(); i++) {
                pays.add(vs.get(i).payload);
                if (i > 0 && vs.get(i).kind != LuminaAdShield.KIND_VARS) {
                    kindsOk = false;
                }
            }
            check(kindsOk, "var keys of 50 short lines: VAR then 33 VARS");
            ArrayList<String> mid = new ArrayList<String>(many);
            mid.remove(25);
            check(!pays.contains(joinAll(mid)), "no leave-one-out for middle lines");
            ArrayList<String> l15 = new ArrayList<String>(many);
            l15.remove(15);
            check(pays.contains(joinAll(l15)), "leave-one-out for line 15");
            ArrayList<String> l34 = new ArrayList<String>(many);
            l34.remove(34);
            check(pays.contains(joinAll(l34)), "leave-one-out for line 34 (n - 16)");
        }
        // test_var_matching_rules
        {
            String head = "【社區公告】本週社區活動與重要事項", tail = "如有疑問請私聊管理員\n感謝各位鄰居配合";
            eq(Arrays.asList(1), groupIds(new String[]{head + "\n週六上午九點大樓消防演練\n" + tail,
                    head + "\n週日下午兩點社區二手市集\n" + tail}), "replaced longer middle line (only VARL) does not link (P2a)");
            eq(Arrays.asList(1, 2), groupIds(new String[]{head + "\n" + tail, head + "\n週六上午九點大樓消防演練\n" + tail}),
                    "added line links (VAR = VARL/VARS)");
            String long_ = "本店雲端會員年卡限時特價全網最低價格\n季卡月卡全部現貨下單後五分鐘內開通\n企業採購量大可議歡迎私聊客服詢價\n"
                    + "售後保固一年有任何問題隨時找我們";
            eq(Arrays.asList(1, 2), groupIds(new String[]{long_ + "\n今日特價", long_ + "\n明日特價"}),
                    "replaced short line links (shared VARS)");
            // other senders never link through VAR
            LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
            ChatContext c = ChatContext.builder(-1).build();
            chat.observe(simple(1, 1000, 5, null, "member", head + "\n" + tail), c, 2000);
            chat.observe(simple(2, 1001, 6, null, "member", head + "\n週六上午九點大樓消防演練\n" + tail), c, 2000);
            LuminaAdShieldStore.View v = chat.view(c, 2000);
            eq(1, LuminaAdShieldStore.group(v, chat.unitOf(v, 1)).units.size(), "VAR does not link different senders");
        }
        // test_block_rules
        {
            String f = "標題\n\n第一區塊的內容長度足夠\n第二行\n🔥🔥🔥\n第二區塊的內容長度也足夠\n\n短\n\n第一區塊的內容長度足夠\n第二行";
            String fn = LuminaAdShield.extractLinks(AdShieldTables.fold(f)).foldNoLinks;
            List<String> bs = LuminaAdShield.blocks(fn);
            eq(Arrays.asList("标题", "第一区块的内容长度足够第二行", "第二区块的内容长度也足够", "短", "第一区块的内容长度足够第二行"), bs, "blocks");
            eq(Arrays.asList("第一区块的内容长度足够第二行", "第二区块的内容长度也足够"), LuminaAdShield.blockPayloads(bs), "block payloads");
            eq(Collections.emptyList(), LuminaAdShield.blockPayloads(Arrays.asList("第一区块的内容长度足够第二行", "短")),
                    "one qualifying block -> no BLOCK keys");
            check(LuminaAdShield.blockContained(5, 6, 6) && LuminaAdShield.blockContained(2, 2, 9)
                    && LuminaAdShield.blockContained(4, 5, 5), "block containment accepts");
            check(!LuminaAdShield.blockContained(2, 3, 3) && !LuminaAdShield.blockContained(1, 1, 1)
                    && !LuminaAdShield.blockContained(3, 4, 4), "block containment rejects (P5c: 3 of 4)");
            eq(Arrays.asList("标题", "第一区块的内容长度足够", "第二行", "第二区块的内容长度也足够", "短", "第一区块的内容长度足够", "第二行"),
                    LuminaAdShield.contentLines(fn), "content lines");
        }
        // test_row_key_cap_reached_and_serializable
        {
            String digits = "零一二三四五六七八九", stems = "甲乙丙丁戊";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                if (i > 0) {
                    sb.append("\n\n");
                }
                sb.append("第").append(digits.charAt(i % 10)).append("段是一行足夠長的內容，用來產生段落鍵與區塊鍵，順便測試上限")
                        .append(stems.charAt(i / 10));
            }
            sb.append("\n\n客服電話 0912-345-678 有需要私聊");
            MessageInput m = simple(1, 1000, 5, null, "member", sb.toString());
            m.media = new LuminaAdShield.Media();
            m.media.kind = "photo";
            m.media.id = "-42";
            m.forward = new LuminaAdShield.Forward();
            m.forward.fromKind = "user";
            m.forward.fromId = 77;
            m.forward.date = 900;
            LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
            Fingerprint fp = chat.observe(m, ChatContext.builder(-1).build(), 2000);
            eq(LuminaAdShield.MAX_ROW_KEYS, fp.keyKinds.length, "72 keys reached");
            int para = 0, var = 0, block = 0;
            int[] rank = {-1, 0, 1, 2, 3, 4, 6, 5, 7, 7, 7, 8};
            boolean sorted = true;
            for (int i = 0; i < fp.keyKinds.length; i++) {
                int k = fp.keyKinds[i];
                para += k == LuminaAdShield.KIND_PARA ? 1 : 0;
                var += LuminaAdShield.isVarKind(k) ? 1 : 0;
                block += k == LuminaAdShield.KIND_BLOCK ? 1 : 0;
                if (i > 0 && rank[fp.keyKinds[i - 1]] > rank[k]) {
                    sorted = false;
                }
            }
            eq("16/34/16", para + "/" + var + "/" + block, "PARA / VAR family / BLOCK counts at the cap");
            check(sorted, "key order FWD MEDIA TEXT CJK TMPL CONTACT PARA VAR-family BLOCK");
            byte[] blob = chat.serialize();
            LuminaAdShieldStore back = LuminaAdShieldStore.deserialize(blob, -1, TEST_KEY);
            check(back != null && Arrays.equals(back.serialize(), blob), "72-key row round-trips byte for byte");
            eq(0, chat.recordCount(), "no endorsement record for a member");
        }
        // test_record_author_and_format
        {
            LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
            ChatContext c = ChatContext.builder(-1).linkedChannel(-100777, null).build();
            chat.observe(simple(1, 1000, 9, null, "admin", "本群合作推廣：雲端會員特價，私聊管理員"), c, 2000);
            MessageInput pin = simple(2, 1001, 8, null, "member", "本群公告：雲端會員特價，私聊管理員");
            pin.pinned = true;
            chat.observe(pin, c, 2000);
            chat.observe(simple(3, 1002, -100777, null, "channel", "頻道公告：私聊客服"), c, 2000);
            chat.observe(simple(4, 1003, 10, null, "self", "我的公告：私聊我報名"), c, 2000);
            java.util.HashMap<Long, Long> authors = new java.util.HashMap<Long, Long>();
            for (LuminaAdShieldStore.Record r : chat.records) {
                authors.put(r.msgKey, r.author);
            }
            java.util.HashMap<Long, Long> want = new java.util.HashMap<Long, Long>();
            want.put(AdShieldTables.keyedHash(TEST_KEY, "msg", "1"), LuminaAdShield.senderKey(TEST_KEY, 9));
            want.put(AdShieldTables.keyedHash(TEST_KEY, "msg", "2"), 0L);
            want.put(AdShieldTables.keyedHash(TEST_KEY, "msg", "3"), LuminaAdShield.senderKey(TEST_KEY, -100777));
            want.put(AdShieldTables.keyedHash(TEST_KEY, "msg", "4"), 0L);
            eq(want, authors, "endorsement record authors (admin, pinned member, linked channel, self)");
            byte[] blob = chat.serialize();
            LuminaAdShieldStore back = LuminaAdShieldStore.deserialize(blob, -1, TEST_KEY);
            if (check(back != null, "store with authors round-trips")) {
                StringBuilder a = new StringBuilder(), b = new StringBuilder();
                for (LuminaAdShieldStore.Record r : chat.records) {
                    a.append(hex(r.msgKey)).append(':').append(hex(r.author)).append(',');
                }
                for (LuminaAdShieldStore.Record r : back.records) {
                    b.append(hex(r.msgKey)).append(':').append(hex(r.author)).append(',');
                }
                eq(a.toString(), b.toString(), "authors survive the round trip");
            }
            eq("3/3", (blob[4] & 0xFF | (blob[5] & 0xFF) << 8) + "/" + (blob[6] & 0xFF | (blob[7] & 0xFF) << 8),
                    "header formatVersion / algoVersion");
            byte[] old = blob.clone();
            old[4] = 2;
            check(LuminaAdShieldStore.deserialize(old, -1, TEST_KEY) == null, "a v2 header is discarded");
        }
        // test_privileged_definition
        {
            ChatContext c = ChatContext.builder(-100).linkedChannel(-200, null).build();
            String[][] cases = {
                    {"'sender': {'id': 1, 'role': 'admin'}", "11"},
                    {"'sender': {'id': 1, 'role': 'creator'}", "11"},
                    {"'sender': {'id': -100, 'role': 'anon_admin'}", "11"},
                    {"'sender': {'id': -200, 'role': 'channel'}", "11"},
                    {"'sender': {'id': -300, 'role': 'channel'}", "00"},
                    {"'sender': {'id': -200, 'role': 'channel'}, 'autoForward': true", "11"},
                    {"'sender': {'id': 1, 'role': 'self'}", "10"},
                    {"'sender': {'id': 1, 'role': 'member'}, 'pinned': true", "10"},
                    {"'sender': {'id': 1, 'role': 'member'}, 'forward': {'fromKind': 'channel', 'fromId': -200, 'postId': 5, 'date': 1}", "10"},
                    {"'sender': {'id': 1, 'role': 'admin'}, 'forward': {'fromKind': 'user', 'fromId': 5, 'date': 1}", "00"},
                    {"'sender': {'id': 1, 'role': 'admin'}, 'forward': {'fromKind': 'channel', 'fromId': -900, 'postId': 5, 'date': 1}", "11"},
                    {"'sender': {'id': -200, 'role': 'self'}", "10"},
                    {"'sender': {'id': -100, 'role': 'self'}", "10"},
            };
            for (String[] cs : cases) {
                Fingerprint fp = LuminaAdShield.fingerprint(M("{'id': 1, 'date': 1, 'text': '本群公告：雲端會員特價，私聊客服', "
                        + cs[0] + "}"), c, TEST_KEY);
                String got = (fp.endorsing ? "1" : "0") + (fp.privileged ? "1" : "0");
                eq(cs[1], got, "endorsing/privileged of {" + cs[0] + "}");
                eq(fp.privileged, (fp.flags & LuminaAdShield.F_PRIV) != 0, "F_PRIV matches privileged {" + cs[0] + "}");
            }
        }
        // test_crlf_collapsed_before_fold
        {
            ChatContext c = ChatContext.builder(-1).build();
            String lf = "雲端會員限時調價\n企業採購請私聊\n\n雲端硬碟會員年卡特價\n季卡特價月卡特價";
            Fingerprint a = LuminaAdShield.fingerprint(simple(1, 1, 5, null, "member", lf), c, TEST_KEY);
            Fingerprint b = LuminaAdShield.fingerprint(simple(1, 1, 5, null, "member", lf.replace("\n", "\r\n")), c, TEST_KEY);
            eq(a.fold, b.fold, "CR LF: same fold");
            eq(Arrays.toString(a.keyHashes) + Arrays.toString(a.keyKinds), Arrays.toString(b.keyHashes) + Arrays.toString(b.keyKinds),
                    "CR LF: same keys");
            eq(a.blocks, b.blocks, "CR LF: same blocks");
            eq(a.flags, b.flags, "CR LF: same flags");
            eq("a\r\nb\rc\n\r", LuminaAdShield.collapseCrlf("a\r\r\nb\rc\n\r"), "collapseCrlf: one pass, only CR before LF");
            String sep = "一" + (char) 0x2028 + "二\n \t\n三\n🔥\n四";
            eq(Arrays.asList("一二", "三", "四"), LuminaAdShield.blocks(AdShieldTables.fold(sep)), "U+2028 / blank / emoji-only lines");
        }
        // test_author_and_priv_strong
        {
            ChatContext c = ctx(J("{'chatId': -100, 'username': 'grp_demo', 'linkedChannel': {'id': -200, 'username': 'ch_demo'},"
                    + " 'description': '官方客服 @desk_demo_helper', 'pinnedTexts': ['最新價目 私聊 @seller_demo_a 交流群 t.me/+PinInvDemo']}"));
            String text = "最新價目 有需要聯繫 @seller_demo_a 交流群 t.me/+PinInvDemo";
            String fwd = "'forward': {'fromKind': 'channel', 'fromId': -200, 'postId': 9, 'username': 'ch_demo', 'date': 1}";
            Fingerprint adm = LuminaAdShield.fingerprint(M("{'id': 1, 'date': 1, 'text': '" + text + "', 'sender': {'id': 7, 'role': 'admin'}}"), c, TEST_KEY);
            eq(Collections.emptyList(), adm.strong, "pinned tokens: no strong feature for the admin");
            check((adm.flags & LuminaAdShield.F_PRIV_STRONG) != 0, "pinned-only tokens -> PRIV_STRONG");
            eq(LuminaAdShield.senderKey(TEST_KEY, 7), adm.senderKey, "admin senderKey");
            Fingerprint mem = LuminaAdShield.fingerprint(M("{'id': 1, 'date': 1, 'text': '" + text + "', 'sender': {'id': 8, 'role': 'member'}}"), c, TEST_KEY);
            eq(0, mem.flags & (LuminaAdShield.F_PRIV | LuminaAdShield.F_PRIV_STRONG), "member: neither PRIV nor PRIV_STRONG");
            eq(LuminaAdShield.senderKey(TEST_KEY, 8), mem.senderKey, "member senderKey");
            eq(LuminaAdShield.senderKey(TEST_KEY, -200), LuminaAdShield.fingerprint(M("{'id': 1, 'date': 1, 'text': '" + text
                    + "', 'sender': {'id': 7, 'role': 'admin'}, " + fwd + "}"), c, TEST_KEY).senderKey, "admin forwarding the linked channel: author = channel");
            eq(LuminaAdShield.senderKey(TEST_KEY, 8), LuminaAdShield.fingerprint(M("{'id': 1, 'date': 1, 'text': '" + text
                    + "', 'sender': {'id': 8, 'role': 'member'}, " + fwd + "}"), c, TEST_KEY).senderKey, "member forwarding the linked channel: own id");
            eq(LuminaAdShield.senderKey(TEST_KEY, 7), LuminaAdShield.fingerprint(M("{'id': 1, 'date': 1, 'text': '" + text
                    + "', 'sender': {'id': 7, 'role': 'admin'}, " + fwd.replace("-200", "-900") + "}"), c, TEST_KEY).senderKey,
                    "admin forwarding another channel: own id");
            Fingerprint auto = LuminaAdShield.fingerprint(M("{'id': 2, 'date': 1, 'sender': {'id': -200, 'role': 'channel'}, 'autoForward': true, "
                    + fwd + ", 'text': '新品上架 私訊 @other_seller_demo'}"), c, TEST_KEY);
            check((auto.flags & LuminaAdShield.F_PRIV_STRONG) != 0, "auto-forward: downgraded handle is PRIV_STRONG");
            Fingerprint desc = LuminaAdShield.fingerprint(M("{'id': 1, 'date': 1, 'sender': {'id': 7, 'role': 'admin'}, "
                    + "'text': '有問題找 @desk_demo_helper 或看 @ch_demo'}"), c, TEST_KEY);
            check((desc.flags & LuminaAdShield.F_PRIV_STRONG) == 0, "description / own usernames never PRIV_STRONG");
        }
        // test_tolerant_record_overlap_never_impersonation
        {
            ChatContext c = ChatContext.builder(-1).build();
            LuminaAdShieldStore chat = new LuminaAdShieldStore(-1, TEST_KEY);
            String ann = "本週六下午兩點社區讀書會\n地點在市立圖書館三樓會議室\n歡迎大家帶朋友一起參加";
            chat.observe(simple(1, 1000, 1, null, "admin", ann), c, 9000);
            chat.observe(simple(2, 2000, 2, null, "member", ann + "\n我整理的書單 https://notes-alpha.example/l"), c, 9000);
            chat.observe(simple(3, 3000, 3, null, "member", ann + "\n我整理的書單 https://notes-beta.example/l"), c, 9000);
            chat.observe(simple(4, 4000, 4, null, "member", ann + "\n讀書會加油"), c, 9000);
            for (String sens : LuminaAdShield.SENSITIVITIES) {
                Decision d2 = chat.decide(2, c, new Settings(sens), 9000);
                Decision d3 = chat.decide(3, c, new Settings(sens), 9000);
                Decision d4 = chat.decide(4, c, new Settings(sens), 9000);
                eq("weak/weak", d2.cls + "/" + d3.cls, "tolerant record overlap is never impersonation " + sens);
                check(!d2.fold && !d3.fold && !d4.fold, "nothing folds " + sens);
                eq(Decision.ENDORSED, d4.state, "edited copy without new links is endorsed " + sens);
            }
        }
        // test_option_only_affects_privileged
        {
            int checkedPriv = 0;
            for (Object o : list(obj(load("corpus/scenarios.json")).get("scenarios"))) {
                Map<String, Object> sc = obj(o);
                LuminaAdShieldAccount acct = new LuminaAdShieldAccount(424242, TEST_KEY, null);
                ChatContext c = ctx(obj(sc.get("context")));
                LuminaAdShieldStore chat = acct.chat(c.chatId);
                ArrayList<Integer> seen = new ArrayList<Integer>();
                long now = 0;
                for (Object eo : list(sc.get("events"))) {
                    Map<String, Object> ev = obj(eo);
                    String op = str(ev, "op");
                    if (op.equals("observe")) {
                        Map<String, Object> m = obj(ev.get("message"));
                        now = ev.containsKey("now") ? lng(ev, "now", 0) : Math.max(now, lng(m, "date", 0));
                        chat.observe(msg(m), c, now);
                        int id = (int) lng(m, "id", 0);
                        if (!seen.contains(id)) {
                            seen.add(id);
                        }
                    } else if (op.equals("delete")) {
                        now = ev.containsKey("now") ? lng(ev, "now", 0) : now;
                        List<Object> ids = list(ev.get("ids"));
                        int[] arr = new int[ids.size()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = ((Number) ids.get(i)).intValue();
                        }
                        chat.delete(arr, now);
                    } else if (op.equals("notAd")) {
                        now = ev.containsKey("now") ? lng(ev, "now", 0) : now;
                        chat.markNotAd((int) lng(ev, "id", 0), c, now);
                    } else if (op.equals("context")) {
                        c = ctx(obj(ev.get("context")));
                    } else if (op.equals("expect")) {
                        now = ev.containsKey("now") ? lng(ev, "now", 0) : now;
                        for (int id : seen) {
                            Long rid = chat.byMsg.get(id);
                            boolean priv = rid != null && (chat.rows.get(rid).flags & LuminaAdShield.F_PRIV) != 0;
                            for (String sens : LuminaAdShield.SENSITIVITIES) {
                                Settings off = settings(sc, sens);
                                off.foldPrivilegedRepeats = false;
                                Settings on = settings(sc, sens);
                                on.foldPrivilegedRepeats = true;
                                Decision dOff = chat.decide(id, c, off, now);
                                Decision dOn = chat.decide(id, c, on, now);
                                if (priv) {
                                    checkedPriv++;
                                    if (c.adminsKnown && LuminaAdShield.eligibleChat(c)) {
                                        eq(Decision.ENDORSED, dOff.state, str(sc, "id") + " msg " + id + " " + sens + " PRIV with the option off");
                                    }
                                } else {
                                    eq(dOff, dOn, str(sc, "id") + " msg " + id + " " + sens + " non-PRIV independent of the option");
                                }
                            }
                        }
                    }
                }
            }
            check(checkedPriv > 50, "option test covered > 50 privileged decisions (" + checkedPriv + ")");
            cur.note = "(option test: " + checkedPriv + " privileged decisions)";
        }
    }

    // ------------------------------------------------------------------ performance: v3 keys (multi-line price lists)
    static void perfV3() {
        section("perf v3 (3000 price lists)");
        final long chatId = -100778L;
        ChatContext c = ChatContext.builder(chatId).type("supergroup").username("perf_grp")
                .memberHandles(Arrays.asList("admin_one", "helper_bot")).build();
        LuminaAdShieldStore chat = new LuminaAdShieldStore(chatId, TEST_KEY);
        long base = 1_795_000_000L;
        int n = 3000;
        String[] items = {"雲端會員年卡", "影音會員季卡", "網盤會員月卡", "音樂會員半年卡", "閱讀會員年卡", "遊戲點數卡",
                "外送會員卡", "旅遊住宿券", "電商優惠券", "線上課程卡", "健身會員卡", "影城觀影券"};
        long tObs = System.nanoTime();
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            if (i % 5 == 1) {
                sb.append("絲滑提交：\n\n");                    // the user's case: a short first line added
            }
            int variant = i % 3 == 2 ? i : i % 40;             // a third unique, the rest 40 lists reposted
            sb.append("【價目表 第").append(variant).append("期】\n\n");
            for (int k = 0; k < items.length; k++) {
                sb.append(items[(k + variant) % items.length]).append("特價").append(10 + (k * 7 + variant) % 90)
                        .append("元起，現貨秒發\n\n");
            }
            sb.append("下單請私聊 @vendor_demo_").append(i % 40).append(" 或看 t.me/+promo").append(variant % 40);
            chat.observe(simple(i + 1, base + i * 120L, 5000 + (i % 400), "user_" + (i % 400), "member", sb.toString()), c,
                    base + n * 120L);
        }
        double obsMs = (System.nanoTime() - tObs) / 1e6;
        long now = base + n * 120L;
        eq(n, chat.rowCount(), "3000 rows stored");
        int keys = 0;
        for (LuminaAdShieldStore.Row r : chat.rows.values()) {
            keys += r.keys.length;
        }
        Settings st = new Settings(LuminaAdShield.SENS_STANDARD);
        for (int rep = 0; rep < 3; rep++) {
            for (int id = 1; id <= n; id++) {
                chat.decide(id, c, st, now);
            }
            chat.mutations++;
        }
        int coldRuns = 30;
        double coldMax = 0, coldSum = 0;
        Random r = new Random(1);
        for (int k = 0; k < coldRuns; k++) {
            chat.mutations++;
            int id = 1 + r.nextInt(n);
            long a = System.nanoTime();
            chat.decide(id, c, st, now);
            double ms = (System.nanoTime() - a) / 1e6;
            coldSum += ms;
            coldMax = Math.max(coldMax, ms);
        }
        chat.mutations++;
        chat.decide(1, c, st, now);
        int folded = 0;
        double warmMax = 0;
        long a = System.nanoTime();
        for (int id = 1; id <= n; id++) {
            long b = System.nanoTime();
            if (chat.decide(id, c, st, now).fold) {
                folded++;
            }
            warmMax = Math.max(warmMax, (System.nanoTime() - b) / 1e6);
        }
        double passMs = (System.nanoTime() - a) / 1e6;
        int blob = chat.serialize().length;
        check(coldSum / coldRuns < 200, "cold decide average < 200 ms");
        check(passMs / n < 10, "warm decide average < 10 ms");
        check(folded > 0, "reposted price lists are folded");
        cur.note = String.format("%.1f keys/row, blob %d KB; observe x3000 %.0f ms; cold decide (view rebuild) avg %.1f ms / max %.1f ms; "
                        + "warm decide avg %.3f ms / max %.2f ms (%d folded)",
                keys / (double) n, blob / 1024, obsMs, coldSum / coldRuns, coldMax, passMs / n, warmMax, folded);
    }

    // ------------------------------------------------------------------ owner's case (synthetic copy), vs the reference
    static final String USER_CASE_BODY = String.join("\n",
            "价格下调：", "企业用户 私聊 价格全网最低", "标准版 和 高级版 一切正常", "",
            "🔥 云盘会员", "年卡特惠：300", "季卡特惠：106", "月卡 特惠：64", "",
            "⭐ 影音会员  118", "【10起117】", "【20起116】", "【30起115】", "",
            "⭐ 音乐会员 【35】", "",
            "⭐ 阅读点数", "250点数=75", "500点数=145", "1000点数=276", "",
            "📍 立即访问", "👉 https://shop-sample.example/ （需要登录访问 ）", "",
            "有任何问题 随时私聊 @sample_vendor", "",
            "频道： https://t.me/sample_vendor_ch", "交流群： https://t.me/sample_vendor_grp", "交流2群： https://t.me/sample_vendor_grp2");

    static String userCaseRun(String role, long senderId, int[] pinned, boolean ctxPinned, boolean auto, boolean crlf, boolean option) {
        Map<String, Object> cj = J("{'chatId': -1001000000777, 'type': 'supergroup', 'username': 'sample_vendor_grp',"
                + " 'linkedChannel': {'id': -1002000000888, 'username': 'sample_vendor_ch'}, 'adminsKnown': true,"
                + " 'memberHandles': ['sample_vendor']}");
        if (ctxPinned) {
            cj.put("pinnedTexts", new ArrayList<Object>(Collections.singletonList(USER_CASE_BODY)));
        }
        ChatContext c = ctx(cj);
        LuminaAdShieldStore chat = new LuminaAdShieldAccount(1, TEST_KEY, null).chat(c.chatId);
        String body = crlf ? USER_CASE_BODY.replace("\n", "\r\n") : USER_CASE_BODY;
        long[] dates = {1793620800L, 1794744000L, 1794969780L, 1794970800L};    // 2026-11-02 12:00 .. 11-18 03:00 UTC
        long now = 1794971340L;                                                 // 2026-11-18 03:09 UTC
        for (int i = 0; i < 4; i++) {
            MessageInput m = simple(i + 1, dates[i], senderId, null, role, i == 3 ? "丝滑提交：\n\n" + body : body);
            for (int p : pinned) {
                m.pinned |= p == i + 1;
            }
            m.autoForward = auto;
            chat.observe(m, c, now);
        }
        StringBuilder sb = new StringBuilder();
        for (String sens : LuminaAdShield.SENSITIVITIES) {
            Settings st = new Settings(sens);
            st.foldPrivilegedRepeats = option;
            if (sb.length() > 0) {
                sb.append('|');
            }
            for (int id = 1; id <= 4; id++) {
                sb.append(id > 1 ? "," : "").append(chat.decide(id, c, st, now).state);
            }
        }
        return sb.toString();
    }

    static void userCase() {
        section("owner case (synthetic)");
        String endorsed = "endorsed,endorsed,endorsed,endorsed";
        String allEndorsed = endorsed + "|" + endorsed + "|" + endorsed;
        String folds = "none,none,none,none|none,fold,fold,fold|none,fold,fold,fold";
        int[] none = {};
        // expected values: tools/usercase.py on the reference engine (same text, same setups)
        Object[][] runs = {
                {"linked channel, #1 #2 #4 pinned, pinned text in context", "channel", -1002000000888L, new int[]{1, 2, 4}, true, false, false},
                {"linked channel", "channel", -1002000000888L, none, false, false, false},
                {"auto-forwarded channel posts", "channel", -1002000000888L, none, false, true, false},
                {"admin", "admin", 555L, none, false, false, false},
                {"linked channel, CR LF text", "channel", -1002000000888L, none, false, false, true},
        };
        for (Object[] r : runs) {
            caseBegin();
            eq(allEndorsed, userCaseRun((String) r[1], (Long) r[2], (int[]) r[3], (Boolean) r[4], (Boolean) r[5], (Boolean) r[6], false),
                    r[0] + ": option off (= v2)");
            eq(folds, userCaseRun((String) r[1], (Long) r[2], (int[]) r[3], (Boolean) r[4], (Boolean) r[5], (Boolean) r[6], true),
                    r[0] + ": option on");
            caseEnd();
        }
        caseBegin();
        eq(folds, userCaseRun("member", 777, none, false, false, false, false), "ordinary member, option off");
        eq(folds, userCaseRun("member", 777, none, false, false, false, true), "ordinary member, option on");
        caseEnd();
        cur.note = "(loose|standard|aggressive x msgs #1..#4; #4 has a short first line added)";
    }
}
