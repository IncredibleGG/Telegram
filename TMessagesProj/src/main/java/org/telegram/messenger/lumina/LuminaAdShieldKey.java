/*
 * LuminaGram - AdShield ("隱藏重複廣告") per-install hashing key.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.messenger.lumina;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The 16-byte SipHash key of SPEC §7: generated once per install with {@link SecureRandom} and kept in
 * the AdShield prefs file only in Android-Keystore-wrapped form (AES-256-GCM, the wrapping key never
 * leaves the Keystore). Engine thread only (Keystore calls may block).
 *
 * <p>When the Keystore key is gone or invalidated (for example after a screen-lock change) the wrapped
 * value can no longer be opened: a fresh key is generated and the next {@code purgeOrphans} removes the
 * files that were keyed with the old one. When the Keystore is unusable the key lives in memory only for
 * this process (files written with it become orphans on the next start and are purged then).
 */
final class LuminaAdShieldKey {

    static final String PREF_WRAPPED = "wk";
    private static final String ALIAS = "luminagram_adshield_wrap";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int FORMAT = 1;
    private static final int IV_LEN = 12;

    private LuminaAdShieldKey() {
    }

    /** True when a wrapped key was stored (it may still fail to open). */
    static boolean hasStoredKey(SharedPreferences prefs) {
        return prefs != null && prefs.getString(PREF_WRAPPED, null) != null;
    }

    /**
     * The key, or null when none is stored and {@code create} is false. Never logs key material.
     */
    static byte[] load(SharedPreferences prefs, boolean create) {
        String wrapped = prefs != null ? prefs.getString(PREF_WRAPPED, null) : null;
        if (wrapped != null) {
            try {
                byte[] k = unwrap(wrapped);
                if (k != null && k.length == 16) {
                    return k;
                }
            } catch (Throwable t) {
                LuminaAdShield.log("adshield key unwrap failed: " + t.getClass().getSimpleName());
            }
            if (!create) {
                return null;
            }
        } else if (!create) {
            return null;
        }
        byte[] k = new byte[16];
        new SecureRandom().nextBytes(k);
        try {
            String w = wrap(k);
            if (prefs != null) {
                prefs.edit().putString(PREF_WRAPPED, w).commit();
            }
        } catch (Throwable t) {
            // Keystore unusable: keep the key for this process only; never store it unwrapped.
            LuminaAdShield.log("adshield key wrap failed: " + t.getClass().getSimpleName());
            if (prefs != null) {
                prefs.edit().remove(PREF_WRAPPED).commit();
            }
        }
        return k;
    }

    /** Panic wipe: forget the wrapping key as well (best effort). */
    static void deleteWrappingKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(ALIAS)) {
                ks.deleteEntry(ALIAS);
            }
        } catch (Throwable t) {
            LuminaAdShield.log("adshield key delete failed: " + t.getClass().getSimpleName());
        }
    }

    private static SecretKey wrappingKey(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.containsAlias(ALIAS) ? ks.getEntry(ALIAS, null) : null;
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        if (!create) {
            return null;
        }
        if (e != null) {
            ks.deleteEntry(ALIAS);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static String wrap(byte[] raw) throws Exception {
        try {
            return wrapWith(wrappingKey(true), raw);
        } catch (Exception first) {
            // An unusable (e.g. invalidated) wrapping key: replace it once and retry.
            deleteWrappingKey();
            return wrapWith(wrappingKey(true), raw);
        }
    }

    private static String wrapWith(SecretKey sk, byte[] raw) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.ENCRYPT_MODE, sk);
        byte[] iv = c.getIV();
        if (iv == null || iv.length != IV_LEN) {
            throw new IllegalStateException("iv");
        }
        byte[] ct = c.doFinal(raw);
        byte[] out = new byte[1 + IV_LEN + ct.length];
        out[0] = (byte) FORMAT;
        System.arraycopy(iv, 0, out, 1, IV_LEN);
        System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static byte[] unwrap(String s) throws Exception {
        byte[] in = Base64.decode(s, Base64.NO_WRAP);
        if (in.length < 1 + IV_LEN + 16 || in[0] != FORMAT) {
            return null;
        }
        SecretKey sk = wrappingKey(false);
        if (sk == null) {
            return null;
        }
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, sk, new GCMParameterSpec(128, in, 1, IV_LEN));
        return c.doFinal(in, 1 + IV_LEN, in.length - 1 - IV_LEN);
    }
}
