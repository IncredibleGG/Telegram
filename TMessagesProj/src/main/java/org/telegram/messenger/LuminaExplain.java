package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * Context (1.5.2): the target message alone is often ambiguous ("that", slang, an inside
 * joke). {@link #buildContext} adds up to {@value #CONTEXT_MAX_MESSAGES} earlier text / caption
 * messages of the same chat plus the message the target replies to, capped at about
 * {@value #CONTEXT_MAX_CHARS} characters (oldest dropped first). Speakers are only ever labelled
 * "Me" and "A", "B", "C"... -- no names, usernames, phone numbers or ids leave the device. No
 * context at all (exactly the pre-1.5.2 request) in secret chats and in the protected dialogs of
 * {@link LuminaPrivacyGate#isProtectedDialog} (own Saved Messages, Telegram service accounts,
 * BotFather, verification-code and support accounts).
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

    // Appended to PROMPT only when the request carries conversation context.
    private static final String CONTEXT_PROMPT =
            "\n\nThe user message gives some earlier messages from the same chat as context, followed by " +
            "the TARGET message. Explain only the TARGET message, in {lang}. Use the context to infer slang, " +
            "references, irony or sarcasm, and what words like \"it\" or \"that\" refer to; if the context " +
            "is irrelevant, ignore it. Speakers are labelled only as \"Me\" (the person asking you) and " +
            "A, B, C... for other people; refer to them the same way. Be concise.";

    private static final int CONTEXT_MAX_MESSAGES = 8;
    private static final int CONTEXT_MAX_CHARS = 1500;
    private static final int CONTEXT_MAX_PER_MESSAGE = 600;
    private static final String ME = "Me";

    /** Anonymised conversation context for one Explain request. Built by {@link #buildContext}. */
    public static final class ExplainContext {
        /** One "[A]: text" line per message, oldest first. */
        final String lines;
        /** The TARGET's own speaker label ("Me", "A", ...). */
        final String targetLabel;

        ExplainContext(String lines, String targetLabel) {
            this.lines = lines;
            this.targetLabel = targetLabel;
        }
    }

    /**
     * Explain {@code text}. Delivers the model's four-part card to {@code cb.onResult} on the
     * UI thread, or a localized message to {@code cb.onError} (no key, empty input, HTTP or
     * parse error). Never throws to the caller.
     */
    public static void explain(final String text, final Callback cb) {
        explain(text, null, cb);
    }

    /**
     * Same as {@link #explain(String, Callback)}, with the anonymised {@code context} of
     * {@link #buildContext} sent along. A null context sends exactly the single-message request.
     */
    public static void explain(final String text, final ExplainContext context, final Callback cb) {
        if (cb == null) {
            return;
        }
        if (TextUtils.isEmpty(text)) {
            cb.onError(LuminaLocale.getString(R.string.LuminaExplainError));
            return;
        }
        final String key = LuminaLlm.apiKey();
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
        String prompt = PROMPT.replace("{lang}", LuminaLang.name(code)).replace("{code}", code);
        final String userContent;
        if (context == null) {
            userContent = source;
        } else {
            prompt = prompt + CONTEXT_PROMPT.replace("{lang}", LuminaLang.name(code));
            userContent = "Conversation context (earlier messages in this chat, oldest first; for reference only):\n"
                    + context.lines
                    + "\n\nTARGET message to explain:\n[" + context.targetLabel + "]: " + source;
        }

        final String url = base + "/chat/completions";
        final String body;
        try {
            final JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", prompt));
            messages.put(new JSONObject().put("role", "user").put("content", userContent));
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
            final String replyError = LuminaLlm.errorInReply(raw, model);
            if (replyError != null) {
                cb.onError(replyError);
                return;
            }
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
        }, LuminaLlm.chatErrors(model));
    }

    /**
     * Anonymised context for explaining {@code target}: up to {@value #CONTEXT_MAX_MESSAGES} earlier
     * messages of the same chat taken from {@code loaded} (the chat's already loaded messages, any
     * order) that carry text or a caption -- service messages and media without a caption are
     * skipped -- plus the message {@code target} replies to when it is loaded, in this chat and not
     * already among them. Each message is capped at {@value #CONTEXT_MAX_PER_MESSAGE} characters
     * and the whole context at {@value #CONTEXT_MAX_CHARS}, dropping the oldest first (the
     * replied-to message last). Speakers become "Me" and "A", "B", "C"... in order of appearance.
     *
     * <p>Returns null -- explain the target alone -- for secret chats, for protected dialogs
     * ({@link LuminaPrivacyGate#isProtectedDialog}) and when nothing usable precedes the target.
     * Never throws.
     */
    public static ExplainContext buildContext(int account, MessageObject target, List<MessageObject> loaded) {
        if (target == null || target.messageOwner == null) {
            return null;
        }
        try {
            final long dialogId = target.getDialogId();
            if (DialogObject.isEncryptedDialog(dialogId) || target.messageOwner instanceof TLRPC.TL_message_secret) {
                return null;
            }
            if (LuminaPrivacyGate.isProtectedDialog(account, dialogId)) {
                return null;
            }
            final int targetId = target.getId();

            // Most recent earlier messages first.
            final ArrayList<MessageObject> earlier = new ArrayList<>();
            final HashSet<Integer> seen = new HashSet<>();
            if (loaded != null) {
                for (int i = 0; i < loaded.size(); i++) {
                    final MessageObject mo = loaded.get(i);
                    if (mo == null || mo == target || !isContextMessage(mo, dialogId)) {
                        continue;
                    }
                    final int id = mo.getId();
                    if (id == targetId || (targetId > 0 && id > targetId) || !seen.add(id)) {
                        continue;
                    }
                    earlier.add(mo);
                }
            }
            Collections.sort(earlier, (a, b) -> Integer.compare(b.getId(), a.getId()));
            while (earlier.size() > CONTEXT_MAX_MESSAGES) {
                earlier.remove(earlier.size() - 1);
            }

            final MessageObject reply = target.replyMessageObject;
            int replyId = 0;
            if (reply != null && reply != target && reply.getId() != targetId && isContextMessage(reply, dialogId)) {
                replyId = reply.getId();
                boolean included = false;
                for (MessageObject mo : earlier) {
                    if (mo.getId() == replyId) {
                        included = true;
                        break;
                    }
                }
                if (!included) {
                    earlier.add(reply);
                }
            }
            if (earlier.isEmpty()) {
                return null;
            }

            // Oldest first.
            Collections.sort(earlier, (a, b) -> Integer.compare(a.getId(), b.getId()));
            final ArrayList<String> texts = new ArrayList<>();
            int total = 0;
            for (MessageObject mo : earlier) {
                final String t = contextText(mo);
                texts.add(t);
                total += t.length();
            }
            // Over budget: drop the oldest first, keeping the replied-to message for last.
            for (int pass = 0; pass < 2 && total > CONTEXT_MAX_CHARS; pass++) {
                for (int i = 0; i < earlier.size() && total > CONTEXT_MAX_CHARS; ) {
                    if (pass == 0 && earlier.get(i).getId() == replyId) {
                        i++;
                        continue;
                    }
                    total -= texts.get(i).length();
                    earlier.remove(i);
                    texts.remove(i);
                }
            }
            if (earlier.isEmpty()) {
                return null;
            }

            final HashMap<String, String> labels = new HashMap<>();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < earlier.size(); i++) {
                final MessageObject mo = earlier.get(i);
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append('[').append(label(labels, mo)).append(']');
                if (mo.getId() == replyId) {
                    sb.append(" (the message the TARGET replies to)");
                }
                sb.append(": ").append(texts.get(i));
            }
            return new ExplainContext(sb.toString(), label(labels, target));
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean isContextMessage(MessageObject mo, long dialogId) {
        if (mo == null || mo.messageOwner == null || mo.isDateObject || mo.getId() <= 0) {
            return false;
        }
        if (mo.messageOwner instanceof TLRPC.TL_messageService || mo.messageOwner.action != null) {
            return false;
        }
        if (mo.isSponsored() || mo.getDialogId() != dialogId) {
            return false;
        }
        return contextText(mo).length() > 0;
    }

    /** The raw text or caption, whitespace collapsed to one line and capped. */
    private static String contextText(MessageObject mo) {
        final String raw = mo.messageOwner == null ? null : mo.messageOwner.message;
        if (raw == null) {
            return "";
        }
        String t = raw.replaceAll("\\s+", " ").trim();
        if (t.length() > CONTEXT_MAX_PER_MESSAGE) {
            t = t.substring(0, CONTEXT_MAX_PER_MESSAGE) + "…";
        }
        return t;
    }

    /** "Me" for the user, then "A", "B", "C"... per sender in order of first appearance. */
    private static String label(HashMap<String, String> labels, MessageObject mo) {
        if (mo.isOut()) {
            return ME;
        }
        final String key = "s" + mo.getSenderId();
        String l = labels.get(key);
        if (l == null) {
            final int n = labels.size();
            l = n < 26 ? String.valueOf((char) ('A' + n)) : "P" + (n + 1);
            labels.put(key, l);
        }
        return l;
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
