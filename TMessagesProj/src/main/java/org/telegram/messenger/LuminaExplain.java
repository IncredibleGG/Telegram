package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * LuminaGram — cultural annotation "Explain this message".
 *
 * Sends one chat message to the user's OWN OpenAI-compatible LLM — the very same
 * key / base URL / model the "LLM" translate provider uses ({@code translateKey_llm},
 * {@code translateBaseUrl}, {@code translateModel}) — and asks for a four-part
 * breakdown: literal meaning, real tone / intent, cultural or slang notes, and a
 * suggested reply. The model is asked to answer in the reader's in-app language.
 *
 * Nothing leaves the device except to the endpoint the user configured and pays for:
 * this reuses {@link LuminaTranslatorUtil#http} exactly like {@link LlmTranslator}, so
 * the request runs off the UI thread with a 15s timeout and the callback is marshalled
 * back onto the UI thread. When no key is configured the caller is handed a localized
 * notice instead of a network call, so the feature degrades to a hint rather than
 * crashing.
 *
 * This class performs no UI work; ChatActivity owns the loading spinner and result card.
 */
public final class LuminaExplain {

    private LuminaExplain() {}

    /** UI-thread callback. Exactly one method fires, exactly once. */
    public interface Callback {
        void onResult(String text);
        void onError(String message);
    }

    // Guard against pathological input running up the user's token bill; the tail of a very
    // long message rarely changes the cultural reading.
    private static final int MAX_INPUT = 4000;

    // English system prompt. The model localizes every section heading into {lang}; {code}
    // is the raw locale tag so it can tell e.g. Traditional from Simplified Chinese apart.
    private static final String PROMPT =
            "You are a cross-cultural communication assistant. A user received the chat message " +
            "below and wants to understand what it really means. Reply in {lang} (the reader's " +
            "language; locale code {code}). Translate every section heading into that language too.\n\n" +
            "Give exactly these four short sections, each with a clear heading:\n" +
            "1. Literal meaning — a plain, faithful reading of the words as written.\n" +
            "2. Real tone and intent — what the sender most likely actually means and the emotional " +
            "register (sincere, joking, sarcastic, passive-aggressive, flirtatious, curt, warm, ...).\n" +
            "3. Cultural or slang notes — unpack any idioms, slang, memes, abbreviations, emoji or " +
            "cultural references a non-native reader could miss; if there are none, say so in one line.\n" +
            "4. Suggested reply — one or two natural replies the user could send back, in a fitting tone.\n\n" +
            "Be concise and easy to skim. Do not add anything before section 1 or after section 4.";

    /**
     * Explain {@code text}. Delivers the model's four-part card to {@code cb.onResult} on the
     * UI thread, or a localized message to {@code cb.onError} (no key, empty input, HTTP or
     * parse error). Never throws to the caller.
     */
    public static void explain(final String text, final Callback cb) {
        if (cb == null) {
            return;
        }
        if (TextUtils.isEmpty(text)) {
            cb.onError(LuminaLocale.getString(R.string.LuminaExplainError));
            return;
        }
        final String key = LuminaConfig.getString("translateKey_llm", "").trim();
        if (TextUtils.isEmpty(key)) {
            // No LLM key configured: hand back the "set your key in Translation settings" hint
            // instead of attempting a request that could only fail.
            cb.onError(LuminaLocale.getString(R.string.LuminaExplainNoKey));
            return;
        }
        String source = text;
        if (source.length() > MAX_INPUT) {
            source = source.substring(0, MAX_INPUT);
        }
        final String base = LuminaTranslatorUtil.trimTrailingSlash(
                LuminaConfig.getString("translateBaseUrl", LuminaTranslators.LLM_DEFAULT_BASE_URL));
        final String model = LuminaConfig.getString("translateModel", LuminaTranslators.LLM_DEFAULT_MODEL);
        final String code = readingLangCode();
        final String prompt = PROMPT.replace("{lang}", LuminaLang.name(code)).replace("{code}", code);

        final String url = base + "/chat/completions";
        final String body;
        try {
            final JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", prompt));
            messages.put(new JSONObject().put("role", "user").put("content", source));
            body = new JSONObject()
                    .put("model", model)
                    .put("temperature", 0.3)
                    .put("messages", messages)
                    .toString();
        } catch (Exception e) {
            cb.onError(LuminaTranslatorUtil.errorText(e));
            return;
        }
        final Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + key);
        headers.put("Content-Type", "application/json");

        // Reuse the translator HTTP plumbing: onError already lands on the UI thread, and the
        // OnBody success handler runs on the UI thread too. The wrapped LuminaTranslator.Callback
        // only forwards errors; success is delivered from the body parser below.
        LuminaTranslatorUtil.http("POST", url, headers, body, new LuminaTranslator.Callback() {
            @Override
            public void onResult(String translated, String detectedSourceLang) {
                // Not used: LuminaTranslatorUtil.http delivers success through OnBody, never here.
            }

            @Override
            public void onError(boolean rateLimited, String message) {
                cb.onError(message);
            }
        }, raw -> {
            final JSONObject json = new JSONObject(raw);
            final String content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();
            if (TextUtils.isEmpty(content)) {
                cb.onError(LuminaLocale.getString(R.string.LuminaExplainError));
            } else {
                cb.onResult(content);
            }
        });
    }

    /** The reader's in-app language tag (e.g. "en", "zh-hans", "pt-br"); "en" when unknown. */
    private static String readingLangCode() {
        try {
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.getLangCode() != null && info.getLangCode().length() > 0) {
                return info.getLangCode();
            }
        } catch (Throwable ignore) {
        }
        return "en";
    }
}
