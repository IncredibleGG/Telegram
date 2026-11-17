package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LuminaConfig;
import org.telegram.messenger.LuminaDecoy;
import org.telegram.messenger.LuminaDuress;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Locale;

/**
 * LuminaCalculatorActivity — the "calculator vault" decoy lock.
 *
 * A fully self-contained, working 4-function calculator that stands in front of the
 * real app whenever the decoy lock is armed ({@link LuminaDecoy#shouldGate}). Its UI
 * is built entirely in code with a plain system theme so it does NOT depend on
 * Telegram's theme or initialisation — it can safely run before the app is set up.
 *
 * Behaviour:
 *   - Digits / "." / operators build an expression shown in the display.
 *   - "=" first checks whether the current entry exactly equals the secret unlock code
 *     ({@code decoyUnlockCode}). If so it flips {@link LuminaDecoy#unlocked} and hands
 *     control to {@link LaunchActivity}, which then proceeds into the real app. If not,
 *     it just evaluates the arithmetic — indistinguishable from an ordinary calculator. If
 *     the entry is the duress code, {@link LuminaDuress#onVaultEntry} logs out first (logout
 *     modes only) and the arithmetic result is shown all the same.
 *   - BACK drops to the background (never reveals the real app), so the lock is not
 *     bypassable via BACK; a fresh cold launch re-arms it.
 *
 * Apart from the duress logout, nothing here touches Telegram state, so a crash is
 * impossible to turn into a data leak; the worst case is a normal calculator that fails
 * to unlock.
 */
public class LuminaCalculatorActivity extends Activity {

    private TextView display;
    private final StringBuilder expr = new StringBuilder();
    private boolean showingResult = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // FAIL-SAFE: building the decoy UI must never brick the app. If anything here throws
        // (theme/inflation/OEM quirk), fail OPEN into the real app rather than leaving the user
        // staring at a crashed, unopenable screen — matching the decoy's fail-open philosophy.
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFFF2F2F2);

            display = new TextView(this);
            display.setText("0");
            display.setTextColor(0xFF111111);
            display.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
            display.setGravity(Gravity.END | Gravity.BOTTOM);
            display.setMaxLines(2);
            int pad = dp(20);
            display.setPadding(pad, pad, pad, pad);
            root.addView(display, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f));

            addRow(root, new String[]{"C", "/", "*", "-"});
            addRow(root, new String[]{"7", "8", "9", "+"});
            addRow(root, new String[]{"4", "5", "6", "="});
            addRow(root, new String[]{"1", "2", "3", "."});
            addRow(root, new String[]{"0"});

            setContentView(root);
        } catch (Throwable t) {
            proceedIntoApp();
        }
    }

    private void addRow(LinearLayout root, String[] labels) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final String label = labels[i];
            Button b = new Button(this);
            b.setText(label);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onButton(label);
                }
            });
            row.addView(b, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        }
        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- input handling ------------------------------------------------------------

    private void onButton(String label) {
        if ("C".equals(label)) {
            onClear();
        } else if ("=".equals(label)) {
            onEquals();
        } else if ("+".equals(label) || "-".equals(label) || "*".equals(label) || "/".equals(label)) {
            onOperator(label.charAt(0));
        } else {
            onDigit(label); // "0".."9" and "."
        }
    }

    private void onDigit(String d) {
        if (showingResult) {
            expr.setLength(0);
            showingResult = false;
        }
        if (".".equals(d)) {
            if (currentSegmentHasDot()) {
                return; // one decimal point per number
            }
            if (expr.length() == 0 || isOperator(expr.charAt(expr.length() - 1))) {
                expr.append("0."); // naked/leading dot -> "0."
            } else {
                expr.append('.');
            }
        } else {
            expr.append(d);
        }
        updateDisplay();
    }

    private void onOperator(char op) {
        if (showingResult) {
            showingResult = false; // keep operating on the shown result
        }
        if (expr.length() == 0) {
            if (op == '-') {
                expr.append('-'); // allow a leading negative
            }
            // ignore a leading + * /
        } else {
            char last = expr.charAt(expr.length() - 1);
            if (isOperator(last)) {
                expr.setCharAt(expr.length() - 1, op); // replace a trailing operator
            } else {
                expr.append(op);
            }
        }
        updateDisplay();
    }

    private void onClear() {
        expr.setLength(0);
        showingResult = false;
        updateDisplay();
    }

    private void onEquals() {
        String current = expr.toString();

        // Decoy unlock: the current entry must EXACTLY equal the configured secret code.
        String code = null;
        try {
            code = LuminaConfig.getString(LuminaDecoy.KEY_CODE, "");
        } catch (Throwable ignore) {
            code = null;
        }
        if (code != null && code.length() > 0 && current.equals(code)) {
            proceedIntoApp();
            return;
        }

        // Duress code: log out when a logout mode is on, then compute as usual below so the
        // display looks like any other "=". A no-op for every other entry.
        LuminaDuress.onVaultEntry(current);

        // Otherwise behave as an ordinary calculator.
        String result = evaluate(current);
        if (result == null) {
            display.setText(errorText());
            expr.setLength(0);
            showingResult = false;
            return;
        }
        expr.setLength(0);
        expr.append(result);
        showingResult = true;
        updateDisplay();
    }

    private void proceedIntoApp() {
        LuminaDecoy.unlocked = true;
        try {
            Intent intent = new Intent(this, LaunchActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        } catch (Throwable ignore) {
        }
        finish();
    }

    private void updateDisplay() {
        display.setText(expr.length() == 0 ? "0" : expr.toString());
    }

    @Override
    public void onBackPressed() {
        // Act like Home: go to background without ever revealing the real app. If the
        // platform ignores this (predictive-back opt-in), the default finish() simply
        // closes the calculator to the home screen — either way the lock is not bypassed.
        moveTaskToBack(true);
    }

    // ---- calculator maths ----------------------------------------------------------

    private static boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }

    private boolean currentSegmentHasDot() {
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (isOperator(c)) {
                break;
            }
            if (c == '.') {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluate a "number (op number)*" expression with normal * / over + - precedence.
     * Returns the formatted result, or {@code null} on any error (bad input, divide by
     * zero, overflow) so the caller can show a plain "Error".
     */
    private String evaluate(String s) {
        try {
            if (s == null || s.length() == 0) {
                return "0";
            }
            ArrayList<Double> nums = new ArrayList<>();
            ArrayList<Character> ops = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            int i = 0;
            if (s.charAt(0) == '-') {
                sb.append('-');
                i = 1;
            }
            for (; i < s.length(); i++) {
                char c = s.charAt(i);
                if (isOperator(c)) {
                    String seg = sb.toString();
                    if (seg.length() == 0 || "-".equals(seg)) {
                        return null; // operator with no preceding number
                    }
                    nums.add(Double.parseDouble(seg));
                    sb.setLength(0);
                    ops.add(c);
                } else {
                    sb.append(c);
                }
            }
            String tail = sb.toString();
            if (tail.length() == 0 || "-".equals(tail)) {
                if (ops.size() > 0) {
                    ops.remove(ops.size() - 1); // trailing operator: drop it
                }
            } else {
                nums.add(Double.parseDouble(tail));
            }
            if (nums.isEmpty()) {
                return "0";
            }
            // pass 1: * and /
            ArrayList<Double> n2 = new ArrayList<>();
            ArrayList<Character> o2 = new ArrayList<>();
            n2.add(nums.get(0));
            for (int k = 0; k < ops.size(); k++) {
                char op = ops.get(k);
                double rhs = nums.get(k + 1);
                if (op == '*') {
                    n2.set(n2.size() - 1, n2.get(n2.size() - 1) * rhs);
                } else if (op == '/') {
                    if (rhs == 0.0) {
                        return null; // divide by zero
                    }
                    n2.set(n2.size() - 1, n2.get(n2.size() - 1) / rhs);
                } else {
                    o2.add(op);
                    n2.add(rhs);
                }
            }
            // pass 2: + and -
            double result = n2.get(0);
            for (int k = 0; k < o2.size(); k++) {
                char op = o2.get(k);
                double rhs = n2.get(k + 1);
                if (op == '+') {
                    result += rhs;
                } else {
                    result -= rhs;
                }
            }
            return formatResult(result);
        } catch (Throwable t) {
            return null;
        }
    }

    private String formatResult(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return null;
        }
        if (Math.abs(d) < 1e15 && d == Math.rint(d)) {
            return Long.toString((long) d); // whole number -> no decimals
        }
        String out = String.format(Locale.US, "%.8f", d);
        int dot = out.indexOf('.');
        if (dot >= 0) {
            int end = out.length();
            while (end > 0 && out.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && out.charAt(end - 1) == '.') {
                end--;
            }
            out = out.substring(0, end);
        }
        return out;
    }

    private String errorText() {
        try {
            return LuminaLocale.getString(R.string.LuminaCalculatorError);
        } catch (Throwable t) {
            return "Error"; // fall back if localisation is not ready pre-init
        }
    }
}
