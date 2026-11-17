package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaDecoy;
import org.telegram.messenger.LuminaDisguiseController;
import org.telegram.messenger.LuminaDuress;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.R;

/**
 * LuminaVaultDoorActivity — the "password door" front of the disguise vault.
 *
 * A deliberately anonymous password prompt (one password field + a plain confirm
 * button, no branding or hint that this belongs to Telegram/LuminaGram) that stands in
 * front of the real app whenever the vault is armed in {@link LuminaDecoy#MODE_PASSWORD_DOOR}.
 * Its UI is built entirely in code with a plain system theme so it does NOT depend on
 * Telegram's theme or initialisation — it can safely run before the app is set up.
 *
 * Behaviour:
 *   - Entering the secret code ({@code decoyUnlockCode}, compared trimmed) and confirming
 *     flips {@link LuminaDecoy#unlocked} and hands control to {@link LaunchActivity}, which
 *     then proceeds into the real app (see {@link LuminaDecoy#unlockAndProceed}).
 *   - Any other entry routes to the decoy skin selected by {@link LuminaDecoy#resolveDecoySkin}
 *     ("notepad" → {@code org.telegram.ui.LuminaNotepadActivity}, "calculator" →
 *     {@link LuminaCalculatorActivity}) and never reveals the real app. If that entry is the
 *     duress code, {@link LuminaDuress#onVaultEntry} logs out first (logout modes only).
 *   - BACK drops to the background (never reveals the real app); a fresh cold launch re-arms it.
 *
 * Fail-open: if building the door UI throws (theme/inflation/OEM quirk) the activity hands
 * straight through to the real app via {@link LuminaDecoy#unlockAndProceed} rather than
 * leaving the user locked out — matching the vault's fail-open philosophy.
 */
public class LuminaVaultDoorActivity extends Activity {

    private EditText passwordField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Recents label/icon + window title follow the active camouflage icon, never
        // "LuminaGram" (no-op while no camouflage icon is active).
        LuminaDisguiseController.applyTaskIdentity(this);

        // FAIL-SAFE: building the door UI must never brick the app. If anything here throws,
        // fail OPEN into the real app rather than leaving the user staring at a crashed screen.
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(0xFFFFFFFF);
            int pad = dp(24);
            root.setPadding(pad, pad, pad, pad);

            passwordField = new EditText(this);
            passwordField.setHint(hintText());
            passwordField.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passwordField.setSingleLine(true);
            passwordField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE);
            passwordField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    onConfirm();
                    return true;
                }
            });
            root.addView(passwordField, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            Button confirm = new Button(this);
            confirm.setText(confirmText());
            confirm.setAllCaps(false);
            confirm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            confirm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onConfirm();
                }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(16);
            root.addView(confirm, bp);

            setContentView(root);
        } catch (Throwable t) {
            LuminaDecoy.unlockAndProceed(this);
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- confirm handling ------------------------------------------------------------

    private void onConfirm() {
        String entered;
        try {
            entered = passwordField.getText().toString().trim();
        } catch (Throwable t) {
            entered = "";
        }
        String code;
        try {
            code = LuminaConfig.getString(LuminaDecoy.KEY_CODE, "");
        } catch (Throwable t) {
            code = "";
        }
        if (code != null && code.length() > 0 && entered.equals(code)) {
            // Correct code — unlock and proceed into the real app.
            LuminaDecoy.unlockAndProceed(this);
        } else {
            // Duress code: log out when a logout mode is on, then carry on exactly like any
            // other wrong entry so nothing looks unusual. A no-op for every other entry.
            LuminaDuress.onVaultEntry(entered);
            // Wrong/blank entry — present the decoy; never reveal the real app.
            launchDecoy();
        }
    }

    private void launchDecoy() {
        try {
            String skin = LuminaDecoy.resolveDecoySkin(this);
            Intent intent;
            if (LuminaDecoy.SKIN_CALCULATOR.equals(skin)) {
                intent = new Intent(this, LuminaCalculatorActivity.class);
            } else {
                // "notepad" (default). Referenced by class name so this file compiles and
                // merges independently of the parallel agent that adds LuminaNotepadActivity.
                intent = new Intent();
                intent.setClassName(this, "org.telegram.ui.LuminaNotepadActivity");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            finish();
        } catch (Throwable t) {
            // Could not present the decoy (e.g. skin class not present). Do NOT reveal the
            // real app on a wrong code; just drop to background.
            try {
                moveTaskToBack(true);
            } catch (Throwable ignore) {
            }
        }
    }

    private String hintText() {
        try {
            return LuminaLocale.getString(R.string.LuminaVaultDoorHint);
        } catch (Throwable t) {
            return "Password"; // fall back if localisation is not ready pre-init
        }
    }

    private String confirmText() {
        try {
            return LuminaLocale.getString(R.string.LuminaVaultDoorConfirm);
        } catch (Throwable t) {
            return "OK"; // fall back if localisation is not ready pre-init
        }
    }

    @Override
    public void onBackPressed() {
        // Act like Home: go to background without ever revealing the real app. A fresh cold
        // launch re-arms the door, so BACK cannot be used to bypass the vault.
        moveTaskToBack(true);
    }
}
