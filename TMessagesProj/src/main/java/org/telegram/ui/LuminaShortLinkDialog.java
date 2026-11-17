package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.common.net.InternetDomainName;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LuminaLocale;
import org.telegram.messenger.LuminaShortLinkResolver;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * LuminaGram 1.6: the link safety prompt for a KNOWN SHORT LINK (host on the shortener list).
 *
 * <p>Shown by {@code Browser.luminaShowLinkSafetySheet} instead of the classic sheet, as the one and
 * only confirmation of the click. While it is open the real address is looked up in the background
 * ({@link LuminaShortLinkResolver}: Location headers only, nothing is opened, the destination itself is
 * never contacted). The address is editable: it is prefilled with the resolved address (or the
 * original one when it cannot be resolved), "Open" opens exactly what is in the field, and an edited
 * address gets the same offline link safety checks, shown inline before it is opened.
 *
 * <p>The shortener's own domain is never presented as the real destination. Dismissing the prompt in
 * any way cancels the lookup. Nothing is logged about the address.
 */
public final class LuminaShortLinkDialog {

    /** Shows the prompt; false when it could not be built (the caller then shows the classic sheet). */
    public static boolean show(Context context, Uri original, Utilities.Callback<Uri> onOpen) {
        final LuminaShortLinkDialog prompt;
        try {
            prompt = new LuminaShortLinkDialog(context, original, onOpen);
        } catch (Exception e) {
            FileLog.e("lumina link safety: short link prompt unavailable");
            return false;
        }
        prompt.start();
        return true;
    }

    private static final int STATE_RESOLVING = 0;
    private static final int STATE_RESOLVED = 1;
    private static final int STATE_FAILED = 2;

    private final Utilities.Callback<Uri> onOpen;
    private final String originalText;
    private final AlertDialog dialog;
    private final RadialProgressView spinner;
    private final TextView statusView;
    private final AlertsCreator.ThemedCursorEditText field;
    private final TextView errorView;
    private final TextView warningsView;

    /** What the field was last filled with by the prompt itself; anything else is an edit. */
    private String prefilledText;
    /** An edited address whose warnings were pointed out once; the next "Open" opens it. */
    private String armedText;
    private int state = STATE_RESOLVING;
    private String resolvedUrl;
    private int resolvedRedirects;
    private int failure;
    private int failureHttpCode;
    private boolean finished;
    private LuminaShortLinkResolver.Request request;
    private final Runnable watchdog = this::onWatchdog;

    private LuminaShortLinkDialog(Context context, Uri original, Utilities.Callback<Uri> onOpen) {
        this.onOpen = onOpen;
        this.originalText = original.toString();
        this.prefilledText = originalText;

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        // Keep the initial focus off the address field: the keyboard comes up only on a tap.
        container.setFocusable(true);
        container.setFocusableInTouchMode(true);

        final LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new RadialProgressView(context);
        spinner.setSize(dp(14));
        spinner.setStrokeWidth(1.5f);
        spinner.setProgressColor(Theme.getColor(Theme.key_dialogTextGray));
        statusRow.addView(spinner, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusRow.addView(statusView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        container.addView(statusRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 10));

        final TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        ThemeBind.textColor(label, Theme.key_dialogTextGray);
        label.setText(LuminaLocale.getString(R.string.LuminaShortLinkFieldLabel));
        container.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        field = new AlertsCreator.ThemedCursorEditText(context);
        field.setBackground(null);
        ThemeBind.lineColors(field, Theme.key_dialogInputField, Theme.key_dialogInputFieldActivated, Theme.key_text_RedBold);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        ThemeBind.textColor(field, Theme.key_dialogTextBlack);
        // A URL keyboard with a "Go" key, wrapped over several lines so the whole address is visible.
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(false);
        field.setHorizontallyScrolling(false);
        field.setMaxLines(6);
        // setSingleLine(false) adds TYPE_TEXT_FLAG_MULTI_LINE, and for a multi-line field the IME shows
        // a newline key instead of "Go". Set the type again without touching the wrapping layout.
        field.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setGravity(Gravity.LEFT | Gravity.TOP);
        field.setTextDirection(View.TEXT_DIRECTION_LTR); // an address reads left to right in every locale
        field.setCursorColorKey(Theme.key_windowBackgroundWhiteBlackText, null);
        field.setCursorSize(dp(20));
        field.setCursorWidth(1.5f);
        field.setPadding(0, dp(6), 0, dp(8));
        field.setText(originalText);
        container.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 0));

        errorView = new TextView(context);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        ThemeBind.textColor(errorView, Theme.key_text_RedRegular);
        errorView.setVisibility(View.GONE);
        container.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));

        warningsView = new TextView(context);
        warningsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        ThemeBind.textColor(warningsView, Theme.key_dialogTextBlack);
        warningsView.setVisibility(View.GONE);
        container.addView(warningsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 4));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LuminaLocale.getString(R.string.LuminaLinkSafetyTitle));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Open), (d, which) -> tryOpen());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> d.dismiss());
        builder.setOnDismissListener(d -> onDismissed());
        dialog = builder.create();
        // "Open" may refuse (non-web address, warnings not seen yet); both buttons dismiss by hand.
        dialog.setDismissDialogByButtons(false);

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                armedText = null;
                errorView.setVisibility(View.GONE);
                emphasise(s);
                updateWarnings();
                if (state == STATE_RESOLVED) {
                    updateStatus();
                }
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                tryOpen();
                return true;
            }
            return false;
        });

        emphasise(field.getText());
        updateWarnings();
        updateStatus();
        container.requestFocus();
    }

    private void start() {
        // If this throws (no window to attach to) nothing is on screen and the caller's fallback runs.
        dialog.show();
        // From here on the prompt is visible: never let a failure escape to that fallback, which
        // would open the link behind the user's back.
        try {
            if (!publicSuffixesReady) {
                // Guava builds its public suffix table on first use: do that off the UI thread.
                Utilities.globalQueue.postRunnable(() -> {
                    registrableDomain("example.com");
                    publicSuffixesReady = true;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!finished) {
                            emphasise(field.getText());
                            if (state == STATE_RESOLVED) {
                                updateStatus();
                            }
                        }
                    });
                });
            }
            request = LuminaShortLinkResolver.resolve(originalText, Browser::luminaIsKnownShortener,
                    result -> AndroidUtilities.runOnUIThread(() -> onResolved(result)));
            AndroidUtilities.runOnUIThread(watchdog, LuminaShortLinkResolver.TOTAL_TIMEOUT_MS + 500);
        } catch (Exception e) {
            FileLog.e("lumina link safety: short link lookup not started");
            onResolved(LuminaShortLinkResolver.Result.fail(LuminaShortLinkResolver.FAIL_NETWORK));
        }
    }

    private void onResolved(LuminaShortLinkResolver.Result result) {
        if (finished || state != STATE_RESOLVING) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(watchdog);
        if (result.status == LuminaShortLinkResolver.OK && result.url != null && result.redirects > 0
                && showsRealDestination(result.url)) {
            state = STATE_RESOLVED;
            resolvedUrl = result.url;
            resolvedRedirects = result.redirects;
            if (currentText().equals(prefilledText)) {
                prefilledText = resolvedUrl;
                field.setText(resolvedUrl);
            }
        } else {
            state = STATE_FAILED;
            failure = result.status == LuminaShortLinkResolver.OK ? LuminaShortLinkResolver.FAIL_NO_TARGET : result.status;
            failureHttpCode = result.httpCode;
        }
        updateStatus();
        updateWarnings();
    }

    /** Hard stop for the whole lookup (DNS is not covered by the per-hop socket timeouts). */
    private void onWatchdog() {
        if (finished || state != STATE_RESOLVING) {
            return;
        }
        if (request != null) {
            request.cancel();
        }
        state = STATE_FAILED;
        failure = LuminaShortLinkResolver.FAIL_TIMEOUT;
        updateStatus();
        updateWarnings();
    }

    private void onDismissed() {
        finished = true;
        AndroidUtilities.cancelRunOnUIThread(watchdog);
        if (request != null) {
            request.cancel();
        }
    }

    private void tryOpen() {
        if (finished) {
            return;
        }
        final String text = currentText();
        final Uri uri = parseWebUrl(text);
        if (uri == null) {
            showError(LuminaLocale.getString(R.string.LuminaShortLinkOnlyWeb));
            AndroidUtilities.shakeViewSpring(field);
            return;
        }
        if (!text.equals(prefilledText) && !text.equals(armedText) && !warningsFor(text).isEmpty()) {
            // An edited address with warnings: make sure they were seen before it is opened.
            armedText = text;
            showError(LuminaLocale.getString(R.string.LuminaShortLinkCheckWarnings));
            AndroidUtilities.shakeViewSpring(warningsView);
            return;
        }
        AndroidUtilities.hideKeyboard(field);
        dialog.dismiss();
        if (onOpen != null) {
            onOpen.run(uri);
        }
    }

    private void showError(String text) {
        errorView.setText(text);
        errorView.setVisibility(View.VISIBLE);
    }

    private String currentText() {
        final Editable e = field.getText();
        if (e == null) {
            return "";
        }
        // Pasted addresses often carry a line break; it is never part of a URL.
        return e.toString().replace("\r", "").replace("\n", "").replace("\t", "").trim();
    }

    /** The field as an http(s) address, or null: any other scheme, no host or embedded spaces. */
    private static Uri parseWebUrl(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i)) || Character.isISOControl(text.charAt(i))) {
                return null;
            }
        }
        try {
            final Uri uri = Uri.parse(text);
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            final String host = uri.getHost();
            return host == null || host.isEmpty() ? null : uri;
        } catch (Exception e) {
            return null;
        }
    }

    /** The offline link safety warnings for an address typed in the field (empty when not a web URL). */
    private ArrayList<String> warningsFor(String text) {
        final Uri uri = parseWebUrl(text);
        if (uri == null) {
            return new ArrayList<>();
        }
        // The shortener warning for the clicked address itself is redundant with the status line.
        return Browser.luminaLinkSafetyWarnings(uri, !text.equals(originalText));
    }

    private void updateWarnings() {
        final ArrayList<String> warnings = warningsFor(currentText());
        if (warnings.isEmpty()) {
            warningsView.setVisibility(View.GONE);
            warningsView.setText("");
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("⚠ ").append(warnings.get(i));
        }
        warningsView.setText(sb.toString());
        warningsView.setVisibility(View.VISIBLE);
    }

    private void updateStatus() {
        if (state == STATE_RESOLVING) {
            spinner.setVisibility(View.VISIBLE);
            ThemeBind.textColor(statusView, Theme.key_dialogTextGray);
            statusView.setText(LuminaLocale.getString(R.string.LuminaShortLinkResolving));
        } else if (state == STATE_RESOLVED) {
            spinner.setVisibility(View.GONE);
            ThemeBind.textColor(statusView, Theme.key_dialogTextBlack);
            final String domain = registrableDomainOfUrl(resolvedUrl);
            final SpannableStringBuilder text = new SpannableStringBuilder(String.format(Locale.getDefault(),
                    LuminaLocale.getString(R.string.LuminaShortLinkResolved), domain, resolvedRedirects));
            final int at = text.toString().indexOf(domain);
            if (at >= 0 && !domain.isEmpty()) {
                text.setSpan(new StyleSpan(Typeface.BOLD), at, at + domain.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!currentText().equals(resolvedUrl)) {
                // The field was edited before the answer came: still show the full real address.
                final int start = text.length() + 1;
                text.append('\n').append(resolvedUrl);
                final int[] range = registrableRange(resolvedUrl);
                if (range != null) {
                    text.setSpan(new StyleSpan(Typeface.BOLD), start + range[0], start + range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            statusView.setText(text);
        } else {
            spinner.setVisibility(View.GONE);
            ThemeBind.textColor(statusView, Theme.key_text_RedRegular);
            statusView.setText(String.format(Locale.getDefault(),
                    LuminaLocale.getString(R.string.LuminaShortLinkFailed), failureReason()));
        }
    }

    private String failureReason() {
        switch (failure) {
            case LuminaShortLinkResolver.FAIL_TIMEOUT:
                return LuminaLocale.getString(R.string.LuminaShortLinkErrTimeout);
            case LuminaShortLinkResolver.FAIL_TOO_MANY:
                return LuminaLocale.getString(R.string.LuminaShortLinkErrTooMany);
            case LuminaShortLinkResolver.FAIL_SCHEME:
                return LuminaLocale.getString(R.string.LuminaShortLinkErrScheme);
            case LuminaShortLinkResolver.FAIL_HTTP:
                return String.format(Locale.getDefault(), LuminaLocale.getString(R.string.LuminaShortLinkErrHttp), failureHttpCode);
            case LuminaShortLinkResolver.FAIL_NO_TARGET:
            case LuminaShortLinkResolver.FAIL_INVALID:
                return LuminaLocale.getString(R.string.LuminaShortLinkErrNoTarget);
            case LuminaShortLinkResolver.FAIL_NETWORK:
            default:
                return LuminaLocale.getString(R.string.LuminaShortLinkErrNetwork);
        }
    }

    // ----- registrable domain ("eTLD+1") emphasis -----

    private static volatile boolean publicSuffixesReady;

    /** Marks our bold span so a re-emphasis removes only it. */
    private static final class DomainSpan extends StyleSpan {
        DomainSpan() {
            super(Typeface.BOLD);
        }
    }

    private static void emphasise(Editable s) {
        if (s == null) {
            return;
        }
        final DomainSpan[] old = s.getSpans(0, s.length(), DomainSpan.class);
        for (DomainSpan span : old) {
            s.removeSpan(span);
        }
        final int[] range = registrableRange(s.toString());
        if (range != null) {
            s.setSpan(new DomainSpan(), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static String registrableDomainOfUrl(String url) {
        final int[] range = registrableRange(url);
        return range == null ? "" : url.substring(range[0], range[1]);
    }

    /**
     * Character range of the registrable domain inside {@code url} ("example.co.uk" in
     * "https://a.b.example.co.uk:8443/x"), the whole host when it has none (an IP address, a public
     * suffix, an unknown TLD), or null when the text has no host.
     */
    static int[] registrableRange(String url) {
        final int[] hostRange = hostRange(url);
        if (hostRange == null) {
            return null;
        }
        final int hostStart = hostRange[0];
        final int hostEnd = hostRange[1];
        if (url.charAt(hostStart) == '[') {
            return hostRange;
        }
        final String host = url.substring(hostStart, hostEnd);
        final String registrable = publicSuffixesReady ? registrableDomain(host) : null;
        if (registrable == null || registrable.length() > host.length()
                || !host.regionMatches(true, host.length() - registrable.length(), registrable, 0, registrable.length())) {
            return hostRange;
        }
        return new int[]{hostEnd - registrable.length(), hostEnd};
    }

    /**
     * A resolved address may be presented as the real destination only when the host this prompt
     * shows (and bolds) and the host Android opens are the same and neither is a short-link service.
     * The resolver already guarantees this; checked again here because it is the one promise of the
     * prompt: the shortener's own domain is never labelled as the real address.
     */
    static boolean showsRealDestination(String url) {
        final int[] range = hostRange(url);
        if (range == null) {
            return false;
        }
        final String shown = LuminaShortLinkResolver.canonicalHost(url.substring(range[0], range[1]));
        final String opened;
        try {
            opened = LuminaShortLinkResolver.canonicalHost(Uri.parse(url).getHost());
        } catch (Exception e) {
            return false;
        }
        return shown != null && shown.equals(opened)
                && !LuminaShortLinkResolver.isShortenerDomain(Browser::luminaIsKnownShortener, shown);
    }

    /** Character range of the host inside {@code url} (trailing dots excluded), or null without one. */
    static int[] hostRange(String url) {
        if (url == null) {
            return null;
        }
        final int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        final int authorityStart = schemeEnd + 3;
        int authorityEnd = url.length();
        for (int i = authorityStart; i < url.length(); i++) {
            final char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                authorityEnd = i;
                break;
            }
        }
        final int at = url.lastIndexOf('@', authorityEnd - 1);
        final int hostStart = at >= authorityStart ? at + 1 : authorityStart;
        if (hostStart >= authorityEnd) {
            return null;
        }
        int hostEnd = authorityEnd;
        if (url.charAt(hostStart) == '[') {
            final int close = url.indexOf(']', hostStart);
            return new int[]{hostStart, close > 0 && close < authorityEnd ? close + 1 : authorityEnd};
        }
        final int colon = url.indexOf(':', hostStart);
        if (colon >= 0 && colon < authorityEnd) {
            hostEnd = colon;
        }
        while (hostEnd > hostStart && url.charAt(hostEnd - 1) == '.') {
            hostEnd--;
        }
        if (hostEnd <= hostStart) {
            return null;
        }
        return new int[]{hostStart, hostEnd};
    }

    /** Public-suffix-list registrable domain of {@code host} (lower case), or null when there is none. */
    private static String registrableDomain(String host) {
        try {
            final String h = host.toLowerCase(Locale.ROOT);
            if (h.indexOf(':') >= 0 || h.matches("[0-9.]+")) {
                return null; // IP literal
            }
            final InternetDomainName name = InternetDomainName.from(h);
            return name.isUnderPublicSuffix() ? name.topPrivateDomain().toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
