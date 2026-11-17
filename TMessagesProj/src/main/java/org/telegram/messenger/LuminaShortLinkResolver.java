package org.telegram.messenger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LuminaGram: finds out where a known short link points WITHOUT opening it.
 *
 * <p>Used by the link safety prompt ({@code org.telegram.ui.LuminaShortLinkDialog}) only for links
 * whose host is on the known-shortener list. Each hop is asked for its {@code Location} header and
 * the redirect is NOT followed automatically:
 * <ul>
 *   <li>at most {@link #MAX_HOPS} hops, {@link #HOP_TIMEOUT_MS} per hop and {@link #TOTAL_TIMEOUT_MS}
 *       in all;</li>
 *   <li>only http/https hops: a Location with any other scheme stops the lookup, unresolved;</li>
 *   <li>HEAD first; GET only when HEAD gave no Location, reading at most {@link #MAX_BODY_BYTES} of
 *       the body to find a {@code <meta http-equiv="refresh" content="0;url=...">} fallback;</li>
 *   <li>it stops as soon as the host is no longer a known shortener, so the real destination is
 *       never contacted — only the short-link services themselves are;</li>
 *   <li>no cookies, no credentials (userinfo is stripped from the request, no Authorization), no
 *       Referer, a generic non-browser User-Agent, no response cache.</li>
 * </ul>
 *
 * <p>Same HTTP stack as the other LuminaGram network features ({@code LuminaTranslatorUtil}): the
 * platform {@link HttpURLConnection}, whose system proxy selection runs on the worker thread here,
 * never on the UI thread. Work runs on a small private executor so a slow short-link service never
 * holds up a shared queue; {@link Request#cancel()} aborts the connection in flight.
 *
 * <p>Privacy: nothing here is logged — not the address, not the destination, not the host, not
 * even an exception message (it can embed the host name).
 *
 * <p>Pure JVM code (no Android classes) so it can be exercised off-device.
 */
public final class LuminaShortLinkResolver {

    private LuminaShortLinkResolver() {}

    public static final int MAX_HOPS = 5;
    public static final int HOP_TIMEOUT_MS = 5000;
    public static final int TOTAL_TIMEOUT_MS = 12000;
    public static final int MAX_BODY_BYTES = 64 * 1024;
    /** Generic and non-browser on purpose: browser UAs get a JavaScript page instead of a 301. */
    static final String USER_AGENT = "LinkResolver/1.0";

    public static final int OK = 0;
    public static final int FAIL_TIMEOUT = 1;
    public static final int FAIL_NETWORK = 2;
    public static final int FAIL_TOO_MANY = 3;
    public static final int FAIL_SCHEME = 4;
    public static final int FAIL_NO_TARGET = 5;
    public static final int FAIL_HTTP = 6;
    public static final int FAIL_INVALID = 7;
    public static final int CANCELLED = 8;

    /** Decides whether a host is a known URL shortener (the lookup continues only while it is). */
    public interface HostFilter {
        boolean isShortener(String host);
    }

    /** Called once, on the worker thread, unless the request was cancelled first. */
    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        /** {@link #OK} or one of the {@code FAIL_*} / {@link #CANCELLED} codes. */
        public final int status;
        /** The first address that is not a known shortener; null unless {@link #OK}. */
        public final String url;
        /** How many redirects led to {@link #url}. */
        public final int redirects;
        /** The HTTP status for {@link #FAIL_HTTP}, else 0. */
        public final int httpCode;

        Result(int status, String url, int redirects, int httpCode) {
            this.status = status;
            this.url = url;
            this.redirects = redirects;
            this.httpCode = httpCode;
        }

        public static Result fail(int status) {
            return new Result(status, null, 0, 0);
        }
    }

    /** Handle of one lookup. {@link #cancel()} is safe from any thread and idempotent. */
    public static final class Request {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();

        public void cancel() {
            cancelled.set(true);
            final HttpURLConnection c = connection.getAndSet(null);
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static volatile ThreadPoolExecutor executor;

    private static ThreadPoolExecutor executor() {
        ThreadPoolExecutor e = executor;
        if (e == null) {
            synchronized (LuminaShortLinkResolver.class) {
                e = executor;
                if (e == null) {
                    // One thread per lookup (they are user-triggered and short), so a lookup stuck in
                    // a slow DNS answer never queues the next prompt's lookup behind it.
                    e = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                        final Thread t = new Thread(r, "LuminaShortLink");
                        t.setDaemon(true);
                        return t;
                    });
                    executor = e;
                }
            }
        }
        return e;
    }

    /** Starts a lookup in the background. {@code callback} runs on the worker thread. */
    public static Request resolve(final String url, final HostFilter filter, final Callback callback) {
        final Request request = new Request();
        try {
            executor().execute(() -> {
                Result result;
                try {
                    result = resolveBlocking(url, filter, request);
                } catch (Throwable t) {
                    result = Result.fail(FAIL_NETWORK);
                }
                if (!request.isCancelled() && result.status != CANCELLED && callback != null) {
                    callback.onResult(result);
                }
            });
        } catch (RejectedExecutionException e) {
            if (callback != null) {
                callback.onResult(Result.fail(FAIL_NETWORK));
            }
        }
        return request;
    }

    /** The lookup itself; blocking, call off the UI thread. */
    static Result resolveBlocking(final String startUrl, final HostFilter filter, final Request request) {
        URL current;
        try {
            current = new URL(startUrl);
        } catch (MalformedURLException e) {
            return Result.fail(FAIL_INVALID);
        }
        if (!isWeb(current)) {
            return Result.fail(FAIL_SCHEME);
        }
        final long deadline = System.nanoTime() + TOTAL_TIMEOUT_MS * 1000000L;
        int redirects = 0;
        while (true) {
            if (request.isCancelled()) {
                return Result.fail(CANCELLED);
            }
            if (!filter.isShortener(current.getHost())) {
                return new Result(OK, current.toExternalForm(), redirects, 0);
            }
            if (redirects >= MAX_HOPS) {
                return Result.fail(FAIL_TOO_MANY);
            }
            Hop hop;
            try {
                hop = fetch(current, "HEAD", deadline, request);
                if (hop.location == null) {
                    // 405 / 400 / 200 / a 3xx without Location: some services only answer GET.
                    hop = fetch(current, "GET", deadline, request);
                }
            } catch (SocketTimeoutException e) {
                return Result.fail(request.isCancelled() ? CANCELLED : FAIL_TIMEOUT);
            } catch (Exception e) {
                return Result.fail(request.isCancelled() ? CANCELLED : FAIL_NETWORK);
            }
            if (hop.location == null) {
                return hop.code >= 400 ? new Result(FAIL_HTTP, null, 0, hop.code) : Result.fail(FAIL_NO_TARGET);
            }
            final String scheme = schemeOf(hop.location);
            if (scheme != null && !"http".equals(scheme) && !"https".equals(scheme)) {
                return Result.fail(FAIL_SCHEME);
            }
            URL next;
            try {
                next = new URL(current, hop.location);
            } catch (MalformedURLException e) {
                return Result.fail(FAIL_INVALID);
            }
            if (!isWeb(next) || next.getHost() == null || next.getHost().isEmpty()) {
                return Result.fail(isWeb(next) ? FAIL_INVALID : FAIL_SCHEME);
            }
            if (next.getRef() == null && current.getRef() != null) {
                // RFC 7231 §7.1.2: a Location without a fragment inherits the one of the request.
                try {
                    next = new URL(next, "#" + current.getRef());
                } catch (MalformedURLException ignore) {
                }
            }
            redirects++;
            current = next;
        }
    }

    private static final class Hop {
        final int code;
        final String location;

        Hop(int code, String location) {
            this.code = code;
            this.location = location;
        }
    }

    private static Hop fetch(final URL url, final String method, final long deadline, final Request request) throws IOException {
        final long remainingMs = (deadline - System.nanoTime()) / 1000000L;
        if (remainingMs <= 0) {
            throw new SocketTimeoutException();
        }
        final int timeout = (int) Math.min(HOP_TIMEOUT_MS, remainingMs);
        // The proxy (if any) is picked by the platform's ProxySelector inside openConnection/connect,
        // i.e. here on the worker thread.
        final HttpURLConnection c = (HttpURLConnection) withoutUserInfo(url).openConnection();
        request.connection.set(c);
        try {
            if (request.isCancelled()) {
                throw new IOException();
            }
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            // Never set: Cookie, Authorization, Referer. The app installs no java.net.CookieHandler,
            // so the platform neither sends nor stores cookies for these requests.
            final int code = c.getResponseCode();
            String location = null;
            if (code >= 300 && code < 400) {
                location = trimToNull(c.getHeaderField("Location"));
            }
            if (location == null && "GET".equals(method) && code >= 200 && code < 300) {
                final String type = c.getContentType();
                if (type == null || type.toLowerCase(Locale.ROOT).contains("html")) {
                    location = metaRefreshTarget(readCapped(c.getInputStream(), MAX_BODY_BYTES, deadline));
                }
            }
            return new Hop(code, location);
        } finally {
            request.connection.compareAndSet(c, null);
            try {
                c.disconnect();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String readCapped(final InputStream in, final int limit, final long deadline) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            final byte[] buffer = new byte[limit];
            int total = 0;
            while (total < limit) {
                if (System.nanoTime() - deadline > 0) {
                    throw new SocketTimeoutException();
                }
                final int n = in.read(buffer, total, limit - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
            return new String(buffer, 0, total, StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_EQUIV_REFRESH = Pattern.compile("\\bhttp-equiv\\s*=\\s*[\"']?\\s*refresh\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_ATTR = Pattern.compile("\\bcontent\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");

    /** The target of the first {@code <meta http-equiv="refresh">} in {@code html}, or null. */
    static String metaRefreshTarget(final String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        final Matcher tag = META_TAG.matcher(html);
        while (tag.find()) {
            final String t = tag.group();
            if (!HTTP_EQUIV_REFRESH.matcher(t).find()) {
                continue;
            }
            final Matcher content = CONTENT_ATTR.matcher(t);
            if (!content.find()) {
                continue;
            }
            String value = content.group(1);
            if (value == null) {
                value = content.group(2);
            }
            if (value == null) {
                value = content.group(3);
            }
            final String target = refreshUrl(htmlUnescape(value));
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /** "0; url='https://x'" -> "https://x" (HTML "shared declarative refresh steps", leniently). */
    static String refreshUrl(final String content) {
        if (content == null) {
            return null;
        }
        String s = content.trim();
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
            i++;
        }
        s = s.substring(i).trim();
        if (s.startsWith(";") || s.startsWith(",")) {
            s = s.substring(1).trim();
        }
        if (s.regionMatches(true, 0, "url", 0, 3)) {
            final String rest = s.substring(3).trim();
            if (rest.startsWith("=")) {
                s = rest.substring(1).trim();
            }
        }
        if (s.startsWith("\"") || s.startsWith("'")) {
            final char quote = s.charAt(0);
            final int end = s.indexOf(quote, 1);
            s = end > 0 ? s.substring(1, end) : s.substring(1);
        }
        return trimToNull(s);
    }

    private static String htmlUnescape(final String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        final StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            final char ch = s.charAt(i);
            final int semi = ch == '&' ? s.indexOf(';', i) : -1;
            if (semi > i && semi - i <= 10) {
                final String entity = s.substring(i + 1, semi);
                String replacement = null;
                if ("amp".equalsIgnoreCase(entity)) {
                    replacement = "&";
                } else if ("quot".equalsIgnoreCase(entity)) {
                    replacement = "\"";
                } else if ("apos".equalsIgnoreCase(entity)) {
                    replacement = "'";
                } else if ("lt".equalsIgnoreCase(entity)) {
                    replacement = "<";
                } else if ("gt".equalsIgnoreCase(entity)) {
                    replacement = ">";
                } else if (entity.startsWith("#")) {
                    try {
                        final int cp = entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')
                                ? Integer.parseInt(entity.substring(2), 16)
                                : Integer.parseInt(entity.substring(1));
                        if (Character.isValidCodePoint(cp)) {
                            replacement = new String(Character.toChars(cp));
                        }
                    } catch (NumberFormatException ignore) {
                    }
                }
                if (replacement != null) {
                    out.append(replacement);
                    i = semi + 1;
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /** Lower-case scheme of an absolute reference, or null for a relative one. */
    static String schemeOf(final String reference) {
        if (reference == null) {
            return null;
        }
        final Matcher m = SCHEME.matcher(reference.trim());
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static boolean isWeb(final URL url) {
        final String p = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
        return "http".equals(p) || "https".equals(p);
    }

    private static URL withoutUserInfo(final URL url) throws MalformedURLException {
        if (url.getUserInfo() == null) {
            return url;
        }
        final String file = url.getFile() == null ? "" : url.getFile();
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), file);
    }

    private static String trimToNull(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
