package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI-compatible chat-completions provider. One implementation covers OpenAI/GPT,
 * Gemini (OpenAI-compat endpoint), DeepSeek and any self-hosted/relay gateway — the
 * user only changes the base URL, model and key. The target language is injected into
 * the system prompt as an English language name (see {@link LuminaLang#name}).
 */
final class LlmTranslator implements LuminaTranslator {

    @Override
    public String id() {
        return "llm";
    }

    @Override
    public CharSequence displayName() {
        return "LLM (OpenAI-compatible)";
    }

    @Override
    public boolean needsKey() {
        return true;
    }

    @Override
    public boolean needsBaseUrl() {
        return true;
    }

    @Override
    public boolean needsModel() {
        return true;
    }

    @Override
    public void translate(String text, String toLang, Callback cb) {
        final String key = LuminaConfig.getString("translateKey_" + id(), "").trim();
        if (TextUtils.isEmpty(key)) {
            cb.onError(false, LuminaLocale.getString(R.string.LuminaTranslateNoKey));
            return;
        }
        final String base = LuminaTranslatorUtil.trimTrailingSlash(LuminaConfig.getString("translateBaseUrl", LuminaTranslators.LLM_DEFAULT_BASE_URL));
        final String model = LuminaConfig.getString("translateModel", LuminaTranslators.LLM_DEFAULT_MODEL);
        String prompt = LuminaConfig.getString("translatePrompt", LuminaTranslators.LLM_DEFAULT_PROMPT);
        if (TextUtils.isEmpty(prompt)) {
            prompt = LuminaTranslators.LLM_DEFAULT_PROMPT;
        }
        prompt = prompt.replace("{lang}", LuminaLang.name(toLang));
        // LuminaGram: this chat's register (client / friend / elder / …) is layered ON TOP of the
        // user's own system prompt, never in place of it -- whatever they told the model to do it
        // still does, now in the tone the relationship calls for. Null when no register is set for
        // this chat, or when the chat cannot be identified, which leaves the request byte-identical
        // to what it was before this feature existed.
        final String register = LuminaRegister.promptSuffix();
        if (register != null) {
            prompt = prompt + register;
        }

        final String url = base + "/chat/completions";
        final String body;
        try {
            final JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", prompt));
            messages.put(new JSONObject().put("role", "user").put("content", text));
            body = new JSONObject()
                    .put("model", model)
                    .put("temperature", 0.2)
                    .put("messages", messages)
                    .toString();
        } catch (Exception e) {
            cb.onError(false, LuminaTranslatorUtil.errorText(e));
            return;
        }
        final Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + key);
        headers.put("Content-Type", "application/json");
        // LuminaGram: failures map to one precise, localized sentence (model does not exist on this
        // service / key rejected / quota / temporary error / cannot connect), see LuminaLlm. The
        // body is read only to classify; it, the URL and the key are never shown.
        LuminaTranslatorUtil.http("POST", url, headers, body, cb, raw -> {
            final String replyError = LuminaLlm.errorInReply(raw, model);
            if (replyError != null) {
                cb.onError(false, replyError);
                return;
            }
            final JSONObject json = new JSONObject(raw);
            final String content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();
            cb.onResult(content, null);
        }, LuminaLlm.chatErrors(model));
    }
}
