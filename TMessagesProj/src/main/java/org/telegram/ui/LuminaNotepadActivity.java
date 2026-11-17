package org.telegram.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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
 * LuminaNotepadActivity — the "notepad vault" decoy lock.
 *
 * A fully self-contained, working single-note notepad that stands in front of the real
 * app whenever the decoy lock is armed ({@link LuminaDecoy#shouldGate}). Its UI is built
 * entirely in code with a plain system theme so it does NOT depend on Telegram's theme or
 * initialisation — it can safely run before the app is set up. It mirrors the structure
 * and fail-open philosophy of {@link LuminaCalculatorActivity}.
 *
 * Behaviour:
 *   - A "Notes" title bar over a full-screen multiline EditText. Whatever the user types
 *     is persisted locally ({@code decoyNotepadContent} in the app-private prefs) on every
 *     edit and on pause, so the note looks lived-in across opens — indistinguishable from
 *     an ordinary notepad.
 *   - UNLOCK (discreet): a long-press on the title bar checks the note body, trimmed,
 *     against the secret code ({@code decoyUnlockCode}). On an EXACT match it hands control
 *     to {@link LuminaDecoy#unlockAndProceed(android.app.Activity)}, which proceeds into the
 *     real app. Any other long-press does nothing visible; there is no hint this is a lock.
 *     If the body is the duress code, {@link LuminaDuress#onVaultEntry} logs out (logout
 *     modes only), with nothing visible either.
 *   - BACK drops to the background (never reveals the real app), so the lock is not
 *     bypassable via BACK; a fresh cold launch re-arms it.
 *
 * Apart from the duress logout, nothing here touches Telegram state, so a crash is impossible
 * to turn into a data leak; the worst case is a normal notepad that fails to unlock. If the UI
 * build itself throws, it fails OPEN into the real app so a decoy crash can never lock the user
 * out.
 */
public class LuminaNotepadActivity extends Activity {

    /** Prefs key: the persisted note body, so the decoy looks real across opens. */
    private static final String KEY_CONTENT = "decoyNotepadContent";

    private EditText noteEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Recents label/icon + window title follow the active camouflage icon, never
        // "LuminaGram" (no-op while no camouflage icon is active).
        LuminaDisguiseController.applyTaskIdentity(this);

        // FAIL-OPEN: building the decoy UI must never brick the app. If anything here throws
        // (theme/OEM quirk, pre-init state), fail OPEN into the real app rather than leaving
        // the user staring at a crashed, unopenable screen — matching the decoy's fail-open
        // philosophy (see LuminaCalculatorActivity).
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFFFFFFFF);

            final TextView titleBar = new TextView(this);
            titleBar.setText(titleText());
            titleBar.setTextColor(0xFF111111);
            titleBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            titleBar.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            titleBar.setBackgroundColor(0xFFF2F2F2);
            int tpad = dp(16);
            titleBar.setPadding(tpad, tpad, tpad, tpad);
            // Discreet unlock trigger: long-pressing the "title" checks the note body against
            // the secret code and, on an exact match, hands off to the real app. Setting the
            // listener also makes the view long-clickable; nothing on screen hints at a lock.
            titleBar.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return tryUnlock();
                }
            });
            root.addView(titleBar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            noteEdit = new EditText(this);
            noteEdit.setBackgroundColor(0xFFFFFFFF);
            noteEdit.setTextColor(0xFF111111);
            noteEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            noteEdit.setGravity(Gravity.TOP | Gravity.START);
            noteEdit.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            int epad = dp(16);
            noteEdit.setPadding(epad, epad, epad, epad);

            // Restore the previously typed note so the decoy looks real across opens.
            try {
                String saved = LuminaConfig.getString(KEY_CONTENT, "");
                if (saved != null && saved.length() > 0) {
                    noteEdit.setText(saved);
                    noteEdit.setSelection(saved.length());
                }
            } catch (Throwable ignore) {
                // Pre-init / prefs error: start with an empty note rather than crash.
            }

            // Persist on every edit so nothing is lost even if onPause never fires.
            noteEdit.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    saveContent();
                }
            });

            root.addView(noteEdit, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            setContentView(root);
        } catch (Throwable t) {
            LuminaDecoy.unlockAndProceed(this);
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Discreet unlock: succeed only when the note body, trimmed, EXACTLY equals the
     * configured non-empty secret code. On a match, hand off to the real app; otherwise
     * the long-press does nothing — indistinguishable from an ordinary notepad.
     *
     * @return true when the code matched and the handoff was triggered, false otherwise.
     */
    private boolean tryUnlock() {
        String code;
        try {
            code = LuminaConfig.getString(LuminaDecoy.KEY_CODE, "");
        } catch (Throwable ignore) {
            code = null;
        }
        String current = noteEdit == null ? "" : noteEdit.getText().toString().trim();
        if (code != null && code.length() > 0 && current.equals(code)) {
            LuminaDecoy.unlockAndProceed(this);
            return true;
        }
        // Duress code: log out when a logout mode is on. The long-press still reports "not
        // handled", exactly like any other long-press, so nothing looks unusual.
        LuminaDuress.onVaultEntry(current);
        return false;
    }

    private void saveContent() {
        try {
            if (noteEdit != null) {
                LuminaConfig.putString(KEY_CONTENT, noteEdit.getText().toString());
            }
        } catch (Throwable ignore) {
            // Never let a persistence hiccup crash the decoy.
        }
    }

    private String titleText() {
        try {
            return LuminaLocale.getString(R.string.LuminaNotepadTitle);
        } catch (Throwable t) {
            return "Notes"; // fall back if localisation is not ready pre-init
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveContent();
    }

    @Override
    public void onBackPressed() {
        // Act like Home: go to background without ever revealing the real app. If the
        // platform ignores this (predictive-back opt-in), the default finish() simply
        // closes the notepad to the home screen — either way the lock is not bypassed.
        moveTaskToBack(true);
    }
}
