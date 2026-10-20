package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LuminaGram — shared plumbing for the user's own OpenAI-compatible LLM endpoint, used by the
 * "LLM" translation provider ({@link LlmTranslator}) and by "Explain this message"
 * ({@link LuminaExplain}). Both read the same settings ({@code translateKey_llm},
 * {@code translateBaseUrl}, {@code translateModel}).
 *
 * <ul>
 *   <li>Precise failures: an HTTP status or transport error becomes one fixed, localized sentence
 *       (model does not exist on this service / key rejected / quota or too many requests /
 *       temporary service error / cannot connect ...). The response body is parsed only to tell
 *       "model not found" apart; it is never shown, and neither is the URL nor the key. The model
 *       name the user typed is shown, since it is what they need to fix.</li>
 *   <li>Model discovery: {@link #listModels} calls {@code GET {baseURL}/models} with the same
 *       Authorization header and the same redirect rules as every other credential-carrying
 *       request ({@link LuminaTranslatorUtil#http}: same-host redirects only, no https -> http;
 *       plain http:// endpoints the user typed keep working), 15s timeout.</li>
 * </ul>
 */
public final class LuminaLlm {

    private LuminaLlm() {}

    public static final String KEY_API_KEY = "translateKey_llm";
    public static final String KEY_BASE_URL = "translateBaseUrl";
    public static final String KEY_MODEL = "translateModel";

    /** Ids containing any of these are not chat models; hidden unless nothing else is left. */
    private static final String[] NON_CHAT = {
            "image", "dall-e", "tts", "whisper", "embedding", "moderation", "transcribe", "audio", "realtime"
    };
    /** Families that are almost certainly chat models: listed first. */
    private static final String[] CHAT_HINTS = {
            "gpt", "chat", "claude", "gemini", "gemma", "deepseek", "qwen", "llama", "mistral", "mixtral",
            "glm", "kimi", "moonshot", "grok", "command", "sonnet", "opus", "haiku", "doubao", "ernie",
            "hunyuan", "minimax", "yi-", "turbo"
    };
    /** Chat-capable but rarely what a translator wants (coding / review / search / reserve pools): listed last. */
    private static final String[] LESS_LIKELY = {
            "codex", "review", "reserve", "search", "computer-use", "guard"
    };
    /** "model ... <phrase>" in a 400/404 error message means the model name is wrong. */
    private static final String[] NOT_FOUND_PHRASES = {
            "not found", "does not exist", "not exist", "no such model", "unknown model", "invalid model",
            "model not available"
    };

    public static String apiKey() {
        return LuminaConfig.getString(KEY_API_KEY, "").trim();
    }

    public static String baseUrl() {
        return LuminaTranslatorUtil.trimTrailingSlash(LuminaConfig.getString(KEY_BASE_URL, LuminaTranslators.LLM_DEFAULT_BASE_URL));
    }

    public static String model() {
        return LuminaConfig.getString(KEY_MODEL, LuminaTranslators.LLM_DEFAULT_MODEL);
    }

    static Map<String, String> authHeaders(String key, boolean jsonBody) {
        final Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + key);
        if (jsonBody) {
            headers.put("Content-Type", "application/json");
        }
        return headers;
    }

    // ---------------------------------------------------------------------------------------
    // Error text
    // ---------------------------------------------------------------------------------------

    /** Error mapper for a chat/completions request sent with {@code model}. */
    static LuminaTranslatorUtil.ErrorMapper chatErrors(final String model) {
        return new LuminaTranslatorUtil.ErrorMapper() {
            @Override
            public String forStatus(int code, String body) {
                return statusText(code, body, model);
            }

            @Override
            public String forThrowable(Throwable t) {
                return throwableText(t);
            }
        };
    }

    /** "Model “x” does not exist on this service, tap “Detect available models” to pick one." */
    public static String modelNotFoundText(String model) {
        final String name = model == null ? "" : model.trim();
        try {
            return String.format(LuminaLocale.getString(R.string.LuminaLlmErrModelNotFound), name);
        } catch (Exception e) {
            return LuminaLocale.getString(R.string.LuminaLlmErrModelNotFound).replace("%1$s", name);
        }
    }

    /**
     * True when {@code message} is the "model does not exist" text for the currently configured
     * model, i.e. the UI should offer the model picker next to it.
     */
    public static boolean isModelNotFound(String message) {
        return !TextUtils.isEmpty(message) && message.equals(modelNotFoundText(model()));
    }

    /**
     * Text for a failed request. {@code code} is the HTTP status, or 0 for a successful reply that
     * carries an error object instead of a result. {@code body} is read only to classify.
     */
    static String statusText(int code, String body, String model) {
        if (isModelNotFoundBody(code, body)) {
            return modelNotFoundText(model);
        }
        if (code == 401 || code == 403) {
            return LuminaLocale.getString(R.string.LuminaLlmErrKey);
        }
        if (code == 402 || code == 429) {
            return LuminaLocale.getString(R.string.LuminaLlmErrQuota);
        }
        if (code >= 500) {
            return withCode(R.string.LuminaLlmErrServer, code);
        }
        if (code == 404) {
            return LuminaLocale.getString(R.string.LuminaLlmErrEndpoint);
        }
        if (code <= 0) {
            return LuminaLocale.getString(R.string.LuminaLlmErrBadResponse);
        }
        return withCode(R.string.LuminaLlmErrOther, code);
    }

    /** Text for a transport / parse failure: never the exception message (it can embed the URL). */
    static String throwableText(Throwable t) {
        if (t instanceof LuminaTranslatorUtil.RedirectBlockedException) {
            return LuminaLocale.getString(R.string.LuminaLlmErrRedirect);
        }
        if (t instanceof JSONException) {
            return LuminaLocale.getString(R.string.LuminaLlmErrBadResponse);
        }
        if (t instanceof java.net.MalformedURLException
                || t instanceof java.net.URISyntaxException
                || t instanceof IllegalArgumentException) {
            return LuminaLocale.getString(R.string.LuminaLlmErrBadUrl);
        }
        if (t instanceof java.io.IOException) {
            // Timeout, TLS failure, DNS, connection refused / reset ...
            return LuminaLocale.getString(R.string.LuminaLlmErrNetwork);
        }
        return LuminaTranslatorUtil.errorText(t);
    }

    /**
     * A 2xx reply that carries {@code {"error": ...}} instead of {@code choices} (some relays do
     * this), mapped like an HTTP error. Null when the reply is not such an error object.
     */
    static String errorInReply(String raw, String model) {
        try {
            final JSONObject json = new JSONObject(raw);
            if (!json.has("choices") && json.has("error")) {
                return statusText(0, raw, model);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String withCode(int res, int code) {
        final String fmt = LuminaLocale.getString(res);
        try {
            return String.format(fmt, String.valueOf(code));
        } catch (Exception e) {
            return fmt.replace("%1$s", String.valueOf(code));
        }
    }

    /**
     * Whether an error body says the requested model does not exist. A structured
     * {@code error.type} / {@code error.code} of {@code model_not_found} counts for any status;
     * the looser checks (the message names the model and says it is not found, or
     * {@code error.param} is "model") only for 400 / 404 / an error object in a 2xx reply.
     */
    static boolean isModelNotFoundBody(int code, String body) {
        if (TextUtils.isEmpty(body)) {
            return false;
        }
        String type = "";
        String errCode = "";
        String param = "";
        String message = "";
        try {
            String probe = body.trim();
            if (probe.length() > 65536) {
                probe = probe.substring(0, 65536);
            }
            final JSONObject root = new JSONObject(probe);
            final Object err = root.opt("error");
            if (err instanceof JSONObject) {
                final JSONObject e = (JSONObject) err;
                type = str(e, "type");
                errCode = str(e, "code");
                param = str(e, "param");
                message = str(e, "message");
            } else if (err instanceof String) {
                message = (String) err;
            }
            if (type.isEmpty()) {
                type = str(root, "type");
            }
            if (errCode.isEmpty()) {
                errCode = str(root, "code");
            }
            if (message.isEmpty()) {
                message = str(root, "message");
            }
            if (message.isEmpty()) {
                message = str(root, "detail");
            }
        } catch (Exception ignore) {
            return false;
        }
        final String structured = (type + " " + errCode).toLowerCase(Locale.ROOT);
        if (structured.contains("model_not_found") || structured.contains("model_not_exist")
                || structured.contains("modelnotfound")) {
            return true;
        }
        if (code > 0 && code != 400 && code != 404) {
            return false;
        }
        if ("model".equalsIgnoreCase(param)) {
            return true;
        }
        final String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("model")) {
            for (String phrase : NOT_FOUND_PHRASES) {
                if (m.contains(phrase)) {
                    return true;
                }
            }
            if ((m.startsWith("model ") || m.startsWith("the model ")) && m.contains("not supported")) {
                return true;
            }
        }
        return message.contains("模型") && (message.contains("不存在") || message.contains("不支持")
                || message.contains("不支援") || message.contains("无可用") || message.contains("無可用"));
    }

    private static String str(JSONObject o, String name) {
        final Object v = o.opt(name);
        if (v == null || v == JSONObject.NULL) {
            return "";
        }
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    // ---------------------------------------------------------------------------------------
    // Model discovery
    // ---------------------------------------------------------------------------------------

    /** UI-thread callback for {@link #listModels}. Exactly one method fires, exactly once. */
    public interface ModelsCallback {
        /** Non-empty, ordered for a picker (likely chat models first). */
        void onModels(ArrayList<String> ids);

        /** Localized, credential-free reason. */
        void onError(String message);
    }

    /**
     * {@code GET {baseURL}/models} with the configured key; parses {@code data[].id}. Errors:
     * 401/403 key rejected, 404/405 the service has no model list (type it manually), network /
     * timeout cannot connect, empty list no models returned.
     */
    public static void listModels(final ModelsCallback cb) {
        if (cb == null) {
            return;
        }
        final String key = apiKey();
        if (TextUtils.isEmpty(key)) {
            cb.onError(LuminaLocale.getString(R.string.LuminaTranslateNoKey));
            return;
        }
        final String url = baseUrl() + "/models";
        LuminaTranslatorUtil.http("GET", url, authHeaders(key, false), null, new LuminaTranslator.Callback() {
            @Override
            public void onResult(String translated, String detectedSourceLang) {
                // Not used: success is delivered through the body parser below.
            }

            @Override
            public void onError(boolean rateLimited, String message) {
                cb.onError(message);
            }
        }, raw -> {
            final ArrayList<String> ids = orderModels(parseModelIds(raw));
            if (ids.isEmpty()) {
                cb.onError(LuminaLocale.getString(R.string.LuminaLlmModelsEmpty));
            } else {
                cb.onModels(ids);
            }
        }, new LuminaTranslatorUtil.ErrorMapper() {
            @Override
            public String forStatus(int code, String body) {
                if (code == 404 || code == 405) {
                    return LuminaLocale.getString(R.string.LuminaLlmModelsNoList);
                }
                return statusText(code, null, null);
            }

            @Override
            public String forThrowable(Throwable t) {
                return throwableText(t);
            }
        });
    }

    /** Model ids from an OpenAI-style list ({@code data[].id}); also accepts {@code models[]} / a bare array. */
    static ArrayList<String> parseModelIds(String raw) throws JSONException {
        final String s = raw == null ? "" : raw.trim();
        JSONArray arr;
        if (s.startsWith("[")) {
            arr = new JSONArray(s);
        } else {
            final JSONObject root = new JSONObject(s);
            arr = root.optJSONArray("data");
            if (arr == null) {
                arr = root.optJSONArray("models");
            }
            if (arr == null) {
                arr = new JSONArray();
            }
        }
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            final Object o = arr.opt(i);
            String id = null;
            if (o instanceof String) {
                id = (String) o;
            } else if (o instanceof JSONObject) {
                id = str((JSONObject) o, "id");
                if (id.isEmpty()) {
                    id = str((JSONObject) o, "name");
                }
            }
            if (id != null) {
                id = id.trim();
                if (id.length() > 0 && id.length() <= 200) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Drop image / speech / embedding / moderation / realtime models (unless that leaves nothing),
     * then likely chat models first, newest-looking versions first within a group.
     */
    static ArrayList<String> orderModels(List<String> ids) {
        final ArrayList<String> shown = new ArrayList<>();
        for (String id : ids) {
            if (!containsAny(id, NON_CHAT)) {
                shown.add(id);
            }
        }
        if (shown.isEmpty()) {
            shown.addAll(ids);
        }
        Collections.sort(shown, (a, b) -> {
            final int ra = rank(a);
            final int rb = rank(b);
            if (ra != rb) {
                return ra - rb;
            }
            return naturalCompare(b.toLowerCase(Locale.ROOT), a.toLowerCase(Locale.ROOT));
        });
        return shown;
    }

    private static int rank(String id) {
        if (containsAny(id, LESS_LIKELY)) {
            return 2;
        }
        final String l = id.toLowerCase(Locale.ROOT);
        if (containsAny(id, CHAT_HINTS) || l.startsWith("o1") || l.startsWith("o3") || l.startsWith("o4")) {
            return 0;
        }
        return 1;
    }

    private static boolean containsAny(String id, String[] needles) {
        final String l = id == null ? "" : id.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (l.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** Compares digit runs by value ("gpt-10" > "gpt-9"), everything else by character. */
    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            final char ca = a.charAt(i);
            final char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ei = i;
                while (ei < a.length() && Character.isDigit(a.charAt(ei))) {
                    ei++;
                }
                int ej = j;
                while (ej < b.length() && Character.isDigit(b.charAt(ej))) {
                    ej++;
                }
                String na = a.substring(i, ei).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(j, ej).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) {
                    return na.length() - nb.length();
                }
                final int c = na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
                i = ei;
                j = ej;
            } else {
                if (ca != cb) {
                    return ca - cb;
                }
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
