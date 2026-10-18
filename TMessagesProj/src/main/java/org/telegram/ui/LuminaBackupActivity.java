package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * LuminaBackupActivity — passphrase-encrypted local backup of all LuminaGram data.
 *
 * Everything LuminaGram stores lives in a single app-private "luminagram" prefs file
 * (bookmarks, contactNotes, quickReplies, textReplacements and every toggle/int/string
 * key). Export takes a type-tagged snapshot of {@link LuminaConfig#exportAll()},
 * encrypts it under a user passphrase and writes a small ".lgbak" JSON envelope that the
 * user can share or save via {@link Intent#ACTION_SEND}. Import picks such a file
 * ({@link Intent#ACTION_GET_CONTENT}), decrypts it with the passphrase and writes the
 * keys back through {@link LuminaConfig#importAll}. Nothing is ever sent to Telegram, and
 * Telegram's own message storage is never touched.
 *
 * Crypto: AES/CBC/PKCS5Padding with a 256-bit key derived from the passphrase via
 * PBKDF2WithHmacSHA256 (random 16-byte salt + 16-byte IV, both stored in the envelope).
 */
public class LuminaBackupActivity extends BaseFragment {

    private static final int ID_EXPORT = 1;
    private static final int ID_IMPORT = 2;

    private static final int REQUEST_IMPORT_FILE = 9021;
    private static final int REQUEST_SHARE = 9022;

    // Backup file / crypto container format.
    private static final String MAGIC = "LuminaGramBackup";
    private static final int FORMAT_VERSION = 1;
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int PBKDF2_ITERATIONS = 120000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 16;
    private static final int MIN_PASSPHRASE_LENGTH = 4;
    private static final int MAX_BACKUP_BYTES = 8 * 1024 * 1024; // backups are tiny; cap defensively

    private UniversalRecyclerView listView;

    private interface PassphraseCallback {
        void onPassphrase(String passphrase);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuminaLocale.getString(R.string.LuminaBackupTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaBackupExportHeader)));
        items.add(UItem.asButton(ID_EXPORT, LuminaLocale.getString(R.string.LuminaBackupExport)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaBackupExportInfo)));

        items.add(UItem.asHeader(LuminaLocale.getString(R.string.LuminaBackupImportHeader)));
        items.add(UItem.asButton(ID_IMPORT, LuminaLocale.getString(R.string.LuminaBackupImport)));
        items.add(UItem.asShadow(LuminaLocale.getString(R.string.LuminaBackupImportInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_EXPORT:
                promptPassphrase(R.string.LuminaBackupExportPassphraseTitle, this::doExport);
                break;
            case ID_IMPORT:
                startImportPicker();
                break;
        }
    }

    // ---- Export ----

    private void doExport(String passphrase) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        try {
            byte[] plaintext = LuminaConfig.exportAll().toString().getBytes(StandardCharsets.UTF_8);
            byte[] salt = randomBytes(SALT_LENGTH);
            byte[] iv = randomBytes(IV_LENGTH);
            SecretKey key = deriveKey(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            org.json.JSONObject env = new org.json.JSONObject();
            env.put("magic", MAGIC);
            env.put("version", FORMAT_VERSION);
            env.put("kdf", KDF_ALGORITHM);
            env.put("iter", PBKDF2_ITERATIONS);
            env.put("cipher", TRANSFORMATION);
            env.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
            env.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
            env.put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP));

            File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            File outFile = new File(dir, "luminagram-backup-" + fileTimestamp() + ".lgbak");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(outFile);
                fos.write(env.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
            shareFile(outFile);
        } catch (Exception e) {
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupExportFailed);
        }
    }

    private void shareFile(File file) {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileProvider.getUriForFile(activity,
                        ApplicationLoader.getApplicationId() + ".provider", file);
            } else {
                uri = Uri.fromFile(file);
            }
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(
                    Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)),
                    REQUEST_SHARE);
        } catch (Exception e) {
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupExportFailed);
        }
    }

    // ---- Import ----

    private void startImportPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(
                    Intent.createChooser(intent, LuminaLocale.getString(R.string.LuminaBackupImport)),
                    REQUEST_IMPORT_FILE);
        } catch (Exception e) {
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupImportFailed);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMPORT_FILE && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            readAndPromptImport(data.getData());
        }
    }

    private void readAndPromptImport(Uri uri) {
        String text = readTextFromUri(uri);
        if (text == null) {
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupImportFailed);
            return;
        }
        final org.json.JSONObject envelope;
        try {
            envelope = new org.json.JSONObject(text);
        } catch (org.json.JSONException e) {
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupInvalidFile);
            return;
        }
        if (!MAGIC.equals(envelope.optString("magic"))
                || envelope.optString("data", "").length() == 0
                || envelope.optString("salt", "").length() == 0
                || envelope.optString("iv", "").length() == 0) {
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupInvalidFile);
            return;
        }
        promptPassphrase(R.string.LuminaBackupPassphraseTitle,
                passphrase -> doImport(envelope, passphrase));
    }

    private void doImport(org.json.JSONObject envelope, String passphrase) {
        try {
            byte[] salt = Base64.decode(envelope.optString("salt"), Base64.NO_WRAP);
            byte[] iv = Base64.decode(envelope.optString("iv"), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(envelope.optString("data"), Base64.NO_WRAP);
            int iterations = envelope.optInt("iter", PBKDF2_ITERATIONS);

            SecretKey key = deriveKey(passphrase.toCharArray(), salt, iterations);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            org.json.JSONObject snapshot = new org.json.JSONObject(
                    new String(plaintext, StandardCharsets.UTF_8));
            // LuminaGram: a backup that would change where translation / AI / speech-to-text
            // content goes (endpoint, custom-endpoint provider, API key) is confirmed first,
            // naming the destination host(s). Declining restores everything else and keeps the
            // current providers, endpoints and keys.
            final java.util.List<String> destinations = LuminaConfig.reviewProviderRoutingImport(snapshot);
            if (destinations.isEmpty()) {
                applyImport(snapshot, true);
            } else {
                confirmProviderRouting(snapshot, destinations);
            }
        } catch (javax.crypto.BadPaddingException | javax.crypto.IllegalBlockSizeException
                 | org.json.JSONException e) {
            // Wrong passphrase (bad padding) or garbled plaintext that will not parse.
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupWrongPassphrase);
        } catch (Exception e) {
            // Missing algorithm on old devices, unreadable envelope, etc.
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupImportFailed);
        }
    }

    private void applyImport(org.json.JSONObject snapshot, boolean includeProviderRouting) {
        try {
            LuminaConfig.importAll(snapshot, includeProviderRouting);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupImportSuccess);
        } catch (Exception e) {
            showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupImportFailed);
        }
    }

    // Ask before a backup re-routes translation / AI / STT traffic or swaps API keys. Any way of
    // leaving the dialog other than "Apply" (Keep current, back, tap outside) restores the rest of
    // the backup and leaves the current provider settings untouched.
    private void confirmProviderRouting(final org.json.JSONObject snapshot, final java.util.List<String> destinations) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final StringBuilder message = new StringBuilder(LuminaLocale.getString(R.string.LuminaBackupProviderMessage));
        message.append("\n");
        for (String host : destinations) {
            message.append("\n• ").append(LuminaConfig.UNRECOGNIZED_ENDPOINT.equals(host)
                    ? LuminaLocale.getString(R.string.LuminaBackupProviderUnrecognized) : host);
        }
        message.append("\n\n").append(LuminaLocale.getString(R.string.LuminaBackupProviderQuestion));

        final boolean[] decided = new boolean[1];
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LuminaLocale.getString(R.string.LuminaBackupProviderTitle));
        builder.setMessage(message.toString());
        builder.setPositiveButton(LuminaLocale.getString(R.string.LuminaBackupProviderApply), (dialog, which) -> {
            if (decided[0]) {
                return;
            }
            decided[0] = true;
            applyImport(snapshot, true);
        });
        builder.setNegativeButton(LuminaLocale.getString(R.string.LuminaBackupProviderKeep), (dialog, which) -> {
            if (decided[0]) {
                return;
            }
            decided[0] = true;
            applyImport(snapshot, false);
        });
        final Dialog shown = showDialog(builder.create(), d -> {
            if (!decided[0]) {
                decided[0] = true;
                // Post: the dismissing dialog must be gone before the result dialog is shown.
                AndroidUtilities.runOnUIThread(() -> applyImport(snapshot, false));
            }
        });
        if (shown == null && !decided[0]) {
            // The confirmation could not be shown (screen transition): fail safe.
            decided[0] = true;
            applyImport(snapshot, false);
        }
    }

    private String readTextFromUri(Uri uri) {
        InputStream in = null;
        try {
            in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BACKUP_BYTES) {
                    return null;
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    // ---- Crypto / helpers ----

    private static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        new SecureRandom().nextBytes(out);
        return out;
    }

    private static String fileTimestamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private void promptPassphrase(int titleRes, PassphraseCallback callback) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintText(LuminaLocale.getString(R.string.LuminaBackupPassphraseHint));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setCursorSize(AndroidUtilities.dp(20));
        edit.setCursorWidth(1.5f);
        edit.setBackgroundDrawable(null);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        edit.setSingleLine(true);

        final FrameLayout container = new FrameLayout(context);
        container.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 24, 6, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LuminaLocale.getString(titleRes));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            String passphrase = edit.getText().toString();
            AndroidUtilities.hideKeyboard(edit);
            if (passphrase.length() < MIN_PASSPHRASE_LENGTH) {
                showMessage(R.string.LuminaBackupTitle, R.string.LuminaBackupPassphraseTooShort);
                return;
            }
            callback.onPassphrase(passphrase);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showMessage(int titleRes, int messageRes) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LuminaLocale.getString(titleRes));
        builder.setMessage(LuminaLocale.getString(messageRes));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }
}
