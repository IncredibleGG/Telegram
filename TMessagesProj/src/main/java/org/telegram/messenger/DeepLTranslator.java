package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * DeepL (bring-your-own key). Free vs Pro endpoint is chosen automatically from the
 * key shape: DeepL free keys carry a ":fx" suffix. Target language is upper-cased with
 * region defaults (see {@link LuminaLang#deepl}).
 */
final class DeepLTranslator implements LuminaTranslator {

    @Override
    public String id() {
        return "deepl";
    }

    @Override
    public CharSequence displayName() {
        return "DeepL";
    }

    @Override
    public boolean needsKey() {
        return true;
    }

    @Override
    public boolean needsBaseUrl() {
        return false;
    }

    @Override
    public boolean needsModel() {
        return false;
    }

    @Override
    public void translate(String text, String toLang, Callback cb) {
        final String key = LuminaConfig.getString("translateKey_" + id(), "").trim();
        if (TextUtils.isEmpty(key)) {
            cb.onError(false, LuminaLocale.getString(R.string.LuminaTranslateNoKey));
            return;
        }
        final String url = (key.endsWith(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com") + "/v2/translate";
        final String body;
        try {
            final JSONObject request = new JSONObject()
                    .put("text", new JSONArray().put(text))
                    .put("target_lang", LuminaLang.deepl(toLang));
            // LuminaGram: DeepL has no free-form tone channel, but it does have one native knob --
            // formality. This chat's register collapses onto it where that is honest (client /
            // colleague / elder -> more formal, friend / family / romance -> less) and is left off
            // entirely otherwise, including for every target language DeepL does not support
            // formality for: sending the parameter there is an error response, not a nicer
            // translation. Null means "say nothing", which is the pre-feature request exactly.
            final String formality = LuminaRegister.deeplFormality(toLang);
            if (formality != null) {
                request.put("formality", formality);
            }
            body = request.toString();
        } catch (Exception e) {
            cb.onError(false, LuminaTranslatorUtil.errorText(e));
            return;
        }
        final Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "DeepL-Auth-Key " + key);
        headers.put("Content-Type", "application/json");
        LuminaTranslatorUtil.http("POST", url, headers, body, cb, raw -> {
            final JSONObject json = new JSONObject(raw);
            final JSONObject t = json.getJSONArray("translations").getJSONObject(0);
            final String translated = t.getString("text");
            final String detected = t.has("detected_source_language") ? t.getString("detected_source_language") : null;
            cb.onResult(translated, detected);
        });
    }
}
