package org.telegram.messenger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared HTTP + parsing plumbing for the network-backed {@link LuminaTranslator}s
 * (DeepL, LLM, …) and the cloud {@link LuminaTranscriber}s. Mirrors
 * {@code TranslateAlert2.alternativeTranslateInternal} but runs on {@link Utilities#globalQueue}
 * with an explicit 15s timeout, and always marshals the translator callback back onto the UI
 * thread.
 *
 * Security:
 * <ul>
 *   <li>Never log request headers, bodies or the raw response — they may carry the user's API
 *       key. Errors surfaced to callbacks are only a short category ("Network error",
 *       "Timeout", "Invalid response", "Redirect blocked") or "HTTP &lt;status&gt;"; never an
 *       exception message, URL, query string, header or response body (see {@link #errorText}).
 *       An {@link ErrorMapper} (the LLM provider's {@link LuminaLlm}) may replace that with a
 *       fixed localized sentence; it reads the body only to classify the failure.</li>
 *   <li>A request that carries a credential (an {@code Authorization} header or any header whose
 *       name mentions a key / token) does not follow redirects blindly: a redirect to the SAME
 *       host is followed (as before), while one to another host or from https to http fails
 *       with "Redirect blocked" instead of replaying the credential (see {@link #open}).
 *       Plain http:// endpoints the user typed in keep working.</li>
 * </ul>
 */
final class LuminaTranslatorUtil {

    private LuminaTranslatorUtil() {}

    private static final int TIMEOUT = 15000;
    /** Same hop limit the platform HttpURLConnection uses. */
    private static final int MAX_REDIRECTS = 5;

    /** Invoked on the UI thread with the successful raw response body. */
    interface OnBody {
        void run(String body) throws Exception;
    }

    /** Writes the request body onto an already configured connection; may run once per hop. */
    interface BodyWriter {
        void write(HttpURLConnection c) throws Exception;
    }

    /** A credential-carrying request was redirected to another host or downgraded to http. */
    static final class RedirectBlockedException extends IOException {
        RedirectBlockedException() {
            super("redirect blocked");
        }
    }

    /**
     * Turns a failure into the text handed to {@code cb.onError}. Lets a provider (the LLM one,
     * see {@link LuminaLlm}) say WHAT went wrong in the user's language instead of "HTTP 404".
     * Implementations must never return the response body, the URL or a header: the body is
     * passed in for classification only.
     */
    interface ErrorMapper {
        /** HTTP status &gt;= 400. Runs on the network thread. */
        String forStatus(int code, String body);

        /** Transport / parse failure. May run on the network thread or on the UI thread. */
        String forThrowable(Throwable t);
    }

    /**
     * Perform an HTTP request off the UI thread. On HTTP < 400, {@code onOk} runs on
     * the UI thread with the response body; any 4xx/5xx or transport error routes to
     * {@code cb.onError(...)} on the UI thread. Both {@code cb} and {@code onOk} fire
     * exactly once.
     */
    static void http(final String method, final String url, final Map<String, String> headers,
                     final String body, final LuminaTranslator.Callback cb, final OnBody onOk) {
        http(method, url, headers, body, cb, onOk, null);
    }

    /**
     * {@link #http(String, String, Map, String, LuminaTranslator.Callback, OnBody)} with the error
     * text chosen by {@code mapper} (null keeps the "HTTP &lt;status&gt;" / category text). The
     * {@code rateLimited} flag is computed exactly as before either way.
     */
    static void http(final String method, final String url, final Map<String, String> headers,
                     final String body, final LuminaTranslator.Callback cb, final OnBody onOk,
                     final ErrorMapper mapper) {
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection c = null;
            try {
                final byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
                c = open(method, url, headers, payload == null ? null : conn -> {
                    conn.setDoOutput(true);
                    final OutputStream os = conn.getOutputStream();
                    try {
                        os.write(payload);
                    } finally {
                        os.close();
                    }
                }, TIMEOUT, TIMEOUT, carriesSecret(headers));
                final int code = c.getResponseCode();
                final InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                final String raw = readAll(in);
                if (code >= 400) {
                    final boolean rl = code == 429 || code == 456 || code == 403;
                    final String msg = mapper != null ? mapper.forStatus(code, raw) : "HTTP " + code;
                    AndroidUtilities.runOnUIThread(() -> cb.onError(rl, msg));
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        onOk.run(raw);
                    } catch (Exception e) {
                        cb.onError(false, mapper != null ? mapper.forThrowable(e) : errorText(e));
                    }
                });
            } catch (Exception e) {
                final String msg = mapper != null ? mapper.forThrowable(e) : errorText(e);
                AndroidUtilities.runOnUIThread(() -> cb.onError(false, msg));
            } finally {
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Exception ignore) {
                    }
                }
            }
        });
    }

    /**
     * Open {@code url}, send the request and return the connection positioned on its final
     * response ({@code getResponseCode()} has already been read). Timeouts, method and headers are
     * applied on every hop and {@code body} (nullable) is re-written on every hop that keeps it.
     *
     * <p>When {@code strict} (the request carries a credential) redirects are handled here instead
     * of by the platform: a redirect to the same host that does not downgrade https to http is
     * followed like before — 307/308 replay method and body, 301/302/303 turn a non-GET into a
     * bodiless GET, as HttpURLConnection does — and anything else throws
     * {@link RedirectBlockedException}. Non-strict requests keep the platform behaviour.
     */
    static HttpURLConnection open(final String method, final String url, final Map<String, String> headers,
                                  final BodyWriter body, final int connectTimeout, final int readTimeout,
                                  final boolean strict) throws Exception {
        URL current = new URI(url).toURL();
        String currentMethod = method;
        boolean withBody = body != null;
        for (int hop = 0; ; hop++) {
            final HttpURLConnection c = (HttpURLConnection) current.openConnection();
            boolean handedOut = false;
            try {
                c.setConnectTimeout(connectTimeout);
                c.setReadTimeout(readTimeout);
                if (strict) {
                    c.setInstanceFollowRedirects(false);
                }
                c.setRequestMethod(currentMethod);
                if (headers != null) {
                    for (Map.Entry<String, String> h : headers.entrySet()) {
                        if (!withBody && "Content-Type".equalsIgnoreCase(h.getKey())) {
                            continue;
                        }
                        c.setRequestProperty(h.getKey(), h.getValue());
                    }
                }
                if (withBody) {
                    body.write(c);
                }
                final int code = c.getResponseCode();
                if (!strict || !isRedirect(code)) {
                    handedOut = true;
                    return c;
                }
                final String location = c.getHeaderField("Location");
                if (location == null || location.length() == 0) {
                    // Nothing to follow: hand the 3xx back exactly like the platform would.
                    handedOut = true;
                    return c;
                }
                if (hop >= MAX_REDIRECTS) {
                    throw new RedirectBlockedException();
                }
                final URL next = new URL(current, location);
                if (!isSafeRedirect(current, next)) {
                    throw new RedirectBlockedException();
                }
                if (code == 303 || ((code == 301 || code == 302)
                        && !"GET".equals(currentMethod) && !"HEAD".equals(currentMethod))) {
                    currentMethod = "GET";
                    withBody = false;
                }
                current = next;
            } finally {
                if (!handedOut) {
                    try {
                        c.disconnect();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /** Same host, http(s) only, no https -> http downgrade, no credentials in the URL. */
    static boolean isSafeRedirect(URL from, URL to) {
        if (from == null || to == null) {
            return false;
        }
        final String fromScheme = lower(from.getProtocol());
        final String toScheme = lower(to.getProtocol());
        if (!"http".equals(toScheme) && !"https".equals(toScheme)) {
            return false;
        }
        if ("https".equals(fromScheme) && !"https".equals(toScheme)) {
            return false;
        }
        if (to.getUserInfo() != null) {
            return false;
        }
        final String fromHost = from.getHost();
        final String toHost = to.getHost();
        return fromHost != null && toHost != null && fromHost.length() > 0
                && fromHost.equalsIgnoreCase(toHost);
    }

    /** True when the request carries a credential that must not be replayed to another host. */
    static boolean carriesSecret(Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        for (String name : headers.keySet()) {
            if (name == null) {
                continue;
            }
            final String n = name.toLowerCase(Locale.ROOT);
            if (n.equals("authorization") || n.equals("proxy-authorization")
                    || n.contains("key") || n.contains("token") || n.contains("secret")) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /**
     * A short, credential-free description of a failure for the UI / callbacks: a category, never
     * the exception message (which can embed the request URL, query string or part of the
     * response body).
     */
    static String errorText(Throwable t) {
        if (t instanceof RedirectBlockedException) {
            return "Redirect blocked";
        }
        if (t instanceof java.net.SocketTimeoutException) {
            return "Timeout";
        }
        if (t instanceof org.json.JSONException) {
            return "Invalid response";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "TLS error";
        }
        if (t instanceof java.net.UnknownHostException
                || t instanceof java.net.ConnectException
                || t instanceof java.net.NoRouteToHostException) {
            return "Network error";
        }
        if (t instanceof java.net.MalformedURLException
                || t instanceof java.net.URISyntaxException
                || t instanceof IllegalArgumentException) {
            return "Invalid endpoint URL";
        }
        if (t instanceof IOException) {
            return "Network error";
        }
        return "Request failed";
    }

    private static final Pattern URL_PATTERN =
            Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S+");
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)\\b(bearer|basic|deepl-auth-key)\\s+\\S+");
    private static final Pattern SECRET_PARAM_PATTERN =
            Pattern.compile("(?i)\\b([a-z0-9_-]*(?:key|token|secret|signature|sig|auth|password)[a-z0-9_-]*)\\s*[=:]\\s*[^\\s&,;]+");

    /**
     * Defence in depth for error text that did not come from {@link #errorText} (for example a
     * local engine's message): strips URLs, auth schemes and key=value pairs and caps the length.
     * Returns null for null.
     */
    static String redact(String message) {
        if (message == null) {
            return null;
        }
        String s = URL_PATTERN.matcher(message).replaceAll("<url>");
        s = BEARER_PATTERN.matcher(s).replaceAll("$1 <redacted>");
        s = SECRET_PARAM_PATTERN.matcher(s).replaceAll("$1=<redacted>");
        if (s.length() > 160) {
            s = s.substring(0, 160) + "…";
        }
        return s;
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    /** Trim a trailing slash so {@code base + "/path"} never doubles up. */
    static String trimTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
