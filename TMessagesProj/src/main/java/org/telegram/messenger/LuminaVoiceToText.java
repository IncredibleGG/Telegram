package org.telegram.messenger;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.TranscribeButton;
import org.telegram.ui.Components.TranslateAlert2;

/**
 * LuminaGram: local (on-device / own-key) voice-to-text orchestrator.
 *
 * <p>Resolves the already-downloaded audio file for a voice / round-video message, runs the
 * currently selected local transcriber (see {@link LuminaTranscribers#current()}), and writes
 * the resulting text under the existing voice bubble by REUSING Telegram's own display path
 * ({@link TranscribeButton#finishTranscription}). Everything is LOCAL: no server RPC is issued,
 * so this is ToS-safe. v1 requires the voice note to already be on disk.
 *
 * <h3>Carrying on into translation</h3>
 * Stopping at a foreign-language transcript is only half the job in a cross-language chat, so
 * once the transcript exists this class continues into the ORDINARY translation pipeline
 * ({@link LuminaTranslators#current()}, the same provider the rest of the fork uses) and
 * rewrites the bubble as two segments:
 * <pre>
 *     &lt;transcript&gt;
 *
 *     &lt;translation&gt;
 * </pre>
 * Gated by {@link #KEY_AUTO_TRANSLATE} (default OFF), and never done for protected chats
 * (Saved Messages, 777000, Telegram official / support accounts; see {@link LuminaPrivacyGate}).
 *
 * <p><b>Why two segments in one string rather than a styled sub-line.</b> The transcript slot
 * is {@code TLRPC.Message.voiceTranscription}, a plain {@code String} that is persisted through
 * {@code MessagesStorage.updateMessageVoiceTranscription} and re-broadcast as a {@code String}
 * in {@code NotificationCenter.voiceTranscriptionUpdate}. Spans cannot survive that round trip,
 * and the dual-language sub-line in {@code ChatMessageCell} is built only for
 * {@code MessageObject.TYPE_TEXT} bubbles (voice bubbles draw their transcript from
 * {@code MessageObject.getVoiceTranscription()} instead). Rendering the transcript small/dimmed
 * would therefore require editing {@code ChatMessageCell}/{@code MessageObject}, so we compose
 * the two segments here and keep the render path untouched.
 *
 * <h3>Not translating twice</h3>
 * When the dialog itself is already being translated, {@code TranslateController} picks up
 * {@code voiceTranscription} on its own (see its {@code pushToTranslate}, which has a dedicated
 * transcription branch) and stores the result in {@code translatedVoiceTranscription}. In that
 * case we hand it the plain transcript and stop: translating here as well would spend quota
 * twice AND feed our own combined "transcript + translation" string back into the translator.
 */
public final class LuminaVoiceToText {

    private LuminaVoiceToText() {
    }

    /** Master switch for the feature (owned by {@code LuminaVoiceToTextActivity}). */
    private static final String KEY_ENABLED = "voiceToTextEnabled";
    /** Engine id; empty means "the user has never picked one". */
    private static final String KEY_ENGINE = "sttEngine";
    /** Translate the transcript right after transcribing. Default OFF. */
    public static final String KEY_AUTO_TRANSLATE = "sttAutoTranslate";
    /** Auto-run the whole pipeline for incoming voice in already-translated chats. Default OFF. */
    public static final String KEY_AUTO_PIPELINE = "sttAutoPipeline";

    /**
     * Separator between the two segments written into the single transcription slot:
     * segment 1 is the raw transcript, segment 2 its translation. A blank line is used because
     * the slot is a plain String (see the class javadoc) — no styling can survive persistence.
     */
    private static final String SEGMENT_SEPARATOR = "\n\n";

    public static void transcribe(final MessageObject mo, final int currentAccount, final BaseFragment fragment) {
        if (mo == null || mo.messageOwner == null) {
            return;
        }
        // Cheap place to arm the receive-side auto-pipeline: this always runs on the UI thread
        // well after startup. No-op when the user has not enabled it.
        ensureAutoPipelineInstalled();
        try {
            // 0. view-once / self-destructing voice and round video never leave the device: a
            //    cloud engine is refused here (the single choke point for the context menu, the
            //    bubble button and the auto pipeline); on-device engines keep working.
            final LuminaTranscriber selected = LuminaTranscribers.current();
            if (selected != null && !selected.isOffline() && LuminaPrivacyGate.isSelfDestructingMedia(mo)) {
                autoPipelineFinished(mo, currentAccount);
                showError(fragment, LuminaLocale.getString(R.string.LuminaSttViewOnceCloud));
                return;
            }

            // 1. resolve the local audio file (v1 requires it already downloaded)
            File f = FileLoader.getInstance(currentAccount).getPathToMessage(mo.messageOwner);
            if (f == null || !f.exists()) {
                final String attach = mo.messageOwner.attachPath;
                if (attach != null && attach.length() > 0) {
                    final File af = new File(attach);
                    if (af.exists()) {
                        f = af;
                    }
                }
            }
            if (f == null || !f.exists()) {
                showError(fragment, LuminaLocale.getString(R.string.LuminaSttUiError));
                return;
            }

            // 2. lightweight "transcribing..." note
            if (fragment != null) {
                try {
                    BulletinFactory.of(fragment)
                            .createSimpleBulletin(R.raw.chats_infotip, LuminaLocale.getString(R.string.LuminaSttUiTranscribing))
                            .show();
                } catch (Exception ignore) {
                }
            }

            // 3. run the currently selected local transcriber
            final String langHint = resolveLangHint(mo, currentAccount);
            final LuminaTranscriber transcriber = selected;
            if (transcriber == null) {
                showError(fragment, LuminaLocale.getString(R.string.LuminaSttUiError));
                return;
            }
            final LuminaTranscriber.Callback callback = new LuminaTranscriber.Callback() {
                @Override
                public void onResult(final String text) {
                    AndroidUtilities.runOnUIThread(() -> {
                        autoPipelineFinished(mo, currentAccount);
                        if (text == null || text.trim().isEmpty()) {
                            showError(fragment, LuminaLocale.getString(R.string.LuminaSttUiNoText));
                        } else {
                            deliver(mo, currentAccount, fragment, text.trim());
                        }
                    });
                }

                @Override
                public void onError(final String message) {
                    AndroidUtilities.runOnUIThread(() -> {
                        autoPipelineFinished(mo, currentAccount);
                        // LuminaGram: surface the reason (decode / model / "HTTP 401" ...) instead of
                        // a generic "failed", so transcription problems are diagnosable in the field.
                        // Cloud engines only report a category + HTTP status; redact() is a second
                        // line of defence so no URL, query string or credential ever reaches the UI.
                        final String safe = LuminaTranslatorUtil.redact(message);
                        showError(fragment, LuminaLocale.getString(R.string.LuminaSttUiError) + ((safe != null && !safe.isEmpty()) ? ("\n" + safe) : ""));
                    });
                }

                @Override
                public void onProgress(float progress) {
                    // optional; ignored in v1
                }
            };
            if (!transcriber.isOffline() && isVideoMedia(mo, f)) {
                // Round video (mp4) + cloud engine: upload the sound track only, never the video
                // frames. On-device engines (Vosk) keep reading the original file as before.
                final File video = f;
                Utilities.globalQueue.postRunnable(() -> transcribeAudioOnly(transcriber, video, langHint, callback));
            } else {
                transcriber.transcribe(f, langHint, callback);
            }
        } catch (Exception e) {
            FileLog.e(e);
            autoPipelineFinished(mo, currentAccount);
            showError(fragment, LuminaLocale.getString(R.string.LuminaSttUiError));
        }
    }

    // ------------------------------------------------------------------
    // round video -> audio only (cloud engines)
    // ------------------------------------------------------------------

    /** Temp folder for the extracted audio; files are deleted as soon as the engine answers. */
    private static final String AUDIO_ONLY_DIR = "lumina_stt_audio";
    /** Leftovers older than this (e.g. the process died mid-upload) are swept on the next run. */
    private static final long AUDIO_ONLY_STALE_MS = 10 * 60 * 1000L;

    /** True for media whose file is a video container (round video / video), not a voice note. */
    private static boolean isVideoMedia(MessageObject mo, File f) {
        if (mo != null && (mo.isRoundVideo() || mo.isVideo())) {
            return true;
        }
        final String name = f == null ? "" : f.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".webm") || name.endsWith(".mkv");
    }

    /**
     * Decode the video's sound track to a 16 kHz mono WAV in the app cache, hand THAT to the
     * cloud engine, and delete it once the engine reports back. Runs on {@link Utilities#globalQueue}.
     * If the audio cannot be extracted the request fails; the video itself is never sent.
     */
    private static void transcribeAudioOnly(final LuminaTranscriber transcriber, final File video,
                                            final String langHint, final LuminaTranscriber.Callback cb) {
        File wav = null;
        try {
            final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), AUDIO_ONLY_DIR);
            sweepStaleAudio(dir);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                cb.onError("Audio extraction failed");
                return;
            }
            wav = new File(dir, "audio_" + System.nanoTime() + ".wav");
            final int samples = LuminaAudioDecoder.writeWav16kMono(video, wav);
            if (samples <= 0) {
                deleteQuietly(wav);
                cb.onResult("");
                return;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            deleteQuietly(wav);
            cb.onError("Audio extraction failed");
            return;
        }
        final File audio = wav;
        try {
            transcriber.transcribe(audio, langHint, new LuminaTranscriber.Callback() {
                @Override
                public void onResult(String text) {
                    deleteQuietly(audio);
                    cb.onResult(text);
                }

                @Override
                public void onError(String message) {
                    deleteQuietly(audio);
                    cb.onError(message);
                }

                @Override
                public void onProgress(float fraction) {
                    cb.onProgress(fraction);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
            deleteQuietly(audio);
            cb.onError(LuminaTranslatorUtil.errorText(e));
        }
    }

    private static void sweepStaleAudio(File dir) {
        try {
            final File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            final long cutoff = System.currentTimeMillis() - AUDIO_ONLY_STALE_MS;
            for (File file : files) {
                if (file.isFile() && file.lastModified() < cutoff) {
                    deleteQuietly(file);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------
    // transcript -> (optional) translation
    // ------------------------------------------------------------------

    /**
     * Publish the transcript, then — when it is not already in the user's reading language —
     * carry on into translation and republish as "transcript + blank line + translation".
     *
     * <p>Fail-safe by construction: the transcript is written FIRST and unconditionally, so a
     * translation that errors out, rate-limits or never returns can only leave the user with
     * the plain transcript, never with an empty bubble.
     */
    private static void deliver(final MessageObject mo, final int currentAccount,
                                final BaseFragment fragment, final String transcript) {
        // 1. always show the transcript (this is the pre-existing behaviour, unchanged)
        writeTranscription(mo, transcript, fragment);

        if (!LuminaConfig.getBoolean(KEY_AUTO_TRANSLATE, false)) {
            return;
        }
        // Auto-translating the transcript is an AUTOMATIC flow: never for Saved Messages, 777000
        // or other Telegram official / support chats (LuminaPrivacyGate). The transcript the user
        // asked for is already on screen; they can still translate it by hand.
        if (mo != null && LuminaPrivacyGate.isProtectedDialog(currentAccount, mo.getDialogId())) {
            return;
        }
        // 2. this dialog is already being translated -> TranslateController owns the
        //    transcription translation. Doing it here too would spend quota twice and would
        //    later re-translate our own combined text.
        if (isDialogTranslating(mo, currentAccount)) {
            return;
        }
        final String target = readLanguage(mo, currentAccount);
        if (target == null || target.length() == 0) {
            return;
        }
        // 3. language detection: reuse the same ML Kit detector TranslateController uses
        //    (LanguageDetector), and SKIP the translation entirely when the transcript is
        //    already in the reading language — that is the quota saver.
        try {
            if (!LanguageDetector.hasSupport()) {
                translateAndAppend(mo, transcript, target);
                return;
            }
            LanguageDetector.detectLanguage(
                    transcript,
                    lng -> AndroidUtilities.runOnUIThread(() -> {
                        if (sameLanguage(lng, target)) {
                            return; // nothing to translate — do not spend a request
                        }
                        translateAndAppend(mo, transcript, target);
                    }),
                    // Detection unavailable: we cannot prove it is the same language, so
                    // translate rather than silently leaving foreign text on screen.
                    e -> AndroidUtilities.runOnUIThread(() -> translateAndAppend(mo, transcript, target))
            );
        } catch (Throwable t) {
            translateAndAppend(mo, transcript, target);
        }
    }

    /**
     * Run the transcript through the user's chosen translation provider — the SAME entry point
     * the rest of the fork uses ({@link LuminaTranslators#current()} +
     * {@link LuminaTranslator#translate(String, String, LuminaTranslator.Callback)}), so the
     * provider, key and quota settings on the translation screen apply here unchanged.
     * The callback is documented to arrive on the UI thread.
     */
    private static void translateAndAppend(final MessageObject mo, final String transcript, final String target) {
        if (mo == null || transcript == null || transcript.length() == 0 || target == null) {
            return;
        }
        try {
            final LuminaTranslator translator = LuminaTranslators.current();
            if (translator == null) {
                return;
            }
            translator.translate(transcript, target, new LuminaTranslator.Callback() {
                @Override
                public void onResult(String translated, String detectedSourceLang) {
                    if (translated == null) {
                        return; // transcript already on screen
                    }
                    final String t = translated.trim();
                    if (t.length() == 0 || t.equals(transcript.trim())) {
                        return; // provider echoed the source — nothing worth a second segment
                    }
                    writeTranscription(mo, transcript + SEGMENT_SEPARATOR + t, null);
                }

                @Override
                public void onError(boolean rateLimited, String message) {
                    // Fail-safe: the transcript is already displayed, so a failed translation
                    // degrades to exactly the old behaviour instead of losing the transcript.
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Write {@code text} into the voice bubble through Telegram's own local display path.
     * Marking the transcription open before the write means the persisted custom params carry
     * {@code voiceTranscriptionOpen = true}, so an auto-transcribed message still shows its
     * text when the chat is opened later (the notification handler that normally sets that flag
     * only runs while the chat is on screen).
     */
    private static void writeTranscription(final MessageObject mo, final String text, final BaseFragment fragment) {
        if (mo == null || mo.messageOwner == null || text == null) {
            return;
        }
        try {
            if (mo.isRoundVideo()) {
                TranscribeButton.openVideoTranscription(mo);
            }
            mo.messageOwner.voiceTranscriptionOpen = true;
            TranscribeButton.finishTranscription(mo, Utilities.random.nextLong(), text);
        } catch (Exception e) {
            FileLog.e(e);
            showError(fragment, LuminaLocale.getString(R.string.LuminaSttUiError));
        }
    }

    // ------------------------------------------------------------------
    // language helpers
    // ------------------------------------------------------------------

    /**
     * The language the user READS in this dialog. Reuses
     * {@code TranslateController.getDialogTranslateTo}, which already resolves (in order) the
     * LuminaGram dual-language read language ({@code trReadLang}), the per-dialog remembered
     * target, the auto target-language guess and finally the app language — so voice follows
     * exactly the same target as text in the same chat.
     */
    private static String readLanguage(final MessageObject mo, final int currentAccount) {
        // LuminaGram: the transcript is text the USER reads, so target the user's READING
        // language (the "I read" setting, else the app language). Do NOT use
        // getDialogTranslateTo here: for a chat with a stored/auto/send target (e.g. an
        // Azerbaijani contact via translate-before-send) it returns the CONTACT's language,
        // which wrong-directions the (already-Chinese) transcript into the contact's language.
        try {
            final String r = LuminaConfig.getString("trReadLang", "");
            if (r != null && r.length() > 0) {
                return r;
            }
        } catch (Exception ignore) {
        }
        try {
            final String app = TranslateController.currentLanguage();
            if (app != null && app.length() > 0) {
                return app;
            }
        } catch (Exception ignore) {
        }
        try {
            return TranslateAlert2.getToLanguage();
        } catch (Exception ignore) {
        }
        return null;
    }

    private static boolean isDialogTranslating(final MessageObject mo, final int currentAccount) {
        try {
            if (mo == null) {
                return false;
            }
            final TranslateController tc = MessagesController.getInstance(currentAccount).getTranslateController();
            final long dialogId = mo.getDialogId();
            return tc != null && tc.isTranslatingDialog(dialogId) && !tc.isTranslateDialogHidden(dialogId);
        } catch (Exception ignore) {
        }
        return false;
    }

    /** "pt-BR" / "zh_Hant" / null -> "pt" / "zh" / "". */
    private static String baseLang(final String s) {
        if (s == null) {
            return "";
        }
        final String n = s.trim().toLowerCase().replace('_', '-');
        final int i = n.indexOf('-');
        return i < 0 ? n : n.substring(0, i);
    }

    /**
     * Compared on the BASE tag so "pt" vs "pt-BR" counts as a match and no request is spent.
     * "und" (ML Kit's undetermined marker) never matches — we would rather translate than
     * silently leave foreign text on screen.
     */
    private static boolean sameLanguage(final String detected, final String target) {
        final String d = baseLang(detected);
        final String t = baseLang(target);
        if (d.length() == 0 || t.length() == 0) {
            return false;
        }
        if (TranslateController.UNKNOWN_LANGUAGE.equals(d)) {
            return false;
        }
        return d.equals(t);
    }

    // ------------------------------------------------------------------
    // optional: receive-side auto pipeline (default OFF)
    // ------------------------------------------------------------------

    private static final int MAX_IN_FLIGHT = 8;
    private static final LinkedHashSet<String> inFlight = new LinkedHashSet<>();
    private static volatile boolean autoPipelineInstalled;
    private static NotificationCenter.NotificationCenterDelegate autoPipelineObserver;

    /**
     * Arm the receive-side auto pipeline: when {@link #KEY_AUTO_PIPELINE} is on, incoming voice
     * messages in a chat that ALREADY has translation enabled are transcribed automatically, and
     * the chat's own translation then renders the transcript in the reading language. Idempotent,
     * cheap, and a hard no-op while the toggle is off.
     *
     * <p>Observing {@code didReceiveNewMessages} here (rather than from the chat screen) keeps the
     * whole feature inside LuminaGram's own files.
     */
    public static void ensureAutoPipelineInstalled() {
        if (autoPipelineInstalled) {
            return;
        }
        if (!LuminaConfig.getBoolean(KEY_AUTO_PIPELINE, false)) {
            return;
        }
        try {
            if (ApplicationLoader.applicationHandler != null
                    && Thread.currentThread() == ApplicationLoader.applicationHandler.getLooper().getThread()) {
                installAutoPipeline();
            } else {
                AndroidUtilities.runOnUIThread(LuminaVoiceToText::installAutoPipeline);
            }
        } catch (Throwable ignore) {
        }
    }

    /** Must run on the main thread — {@code NotificationCenter.addObserver} enforces it. */
    private static void installAutoPipeline() {
        if (autoPipelineInstalled) {
            return;
        }
        try {
            if (autoPipelineObserver == null) {
                autoPipelineObserver = (id, account, args) -> {
                    if (id != NotificationCenter.didReceiveNewMessages) {
                        return;
                    }
                    try {
                        onNewMessages(account, args);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                };
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).addObserver(autoPipelineObserver, NotificationCenter.didReceiveNewMessages);
            }
            autoPipelineInstalled = true;
        } catch (Throwable ignore) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void onNewMessages(final int account, final Object[] args) {
        if (args == null || args.length < 2) {
            return;
        }
        if (!LuminaConfig.getBoolean(KEY_AUTO_PIPELINE, false)
                || !LuminaConfig.getBoolean(KEY_ENABLED, true)) {
            return;
        }
        // No engine chosen yet -> nothing we could run without prompting the user.
        final String engine = LuminaConfig.getString(KEY_ENGINE, "");
        if (engine == null || engine.length() == 0) {
            return;
        }
        if (!(args[1] instanceof ArrayList)) {
            return;
        }
        final ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        if (messages.isEmpty()) {
            return;
        }
        // Scheduled messages are not "received"; skip them.
        if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject mo = messages.get(i);
            if (!isAutoPipelineCandidate(mo, account)) {
                continue;
            }
            final String key = inFlightKey(mo, account);
            if (key == null || inFlight.contains(key) || inFlight.size() >= MAX_IN_FLIGHT) {
                continue;
            }
            inFlight.add(key);
            transcribe(mo, account, null);
        }
    }

    private static boolean isAutoPipelineCandidate(final MessageObject mo, final int account) {
        try {
            if (mo == null || mo.messageOwner == null) {
                return false;
            }
            if (mo.isOutOwner() || !mo.isSent()) {
                return false;
            }
            if (!mo.isVoice() && !mo.isRoundVideo()) {
                return false;
            }
            // Never auto-transcribe protected chats (Saved Messages, 777000, official / support).
            // (View-once media is handled in transcribe(), which refuses cloud engines for it.)
            if (LuminaPrivacyGate.isProtectedDialog(account, mo.getDialogId())) {
                return false;
            }
            // Already transcribed (by us, by Telegram, or restored from storage).
            if (mo.messageOwner.voiceTranscription != null && mo.messageOwner.voiceTranscription.length() > 0) {
                return false;
            }
            // "Full pipeline" only applies where the user already asked for translation.
            return isDialogTranslating(mo, account);
        } catch (Exception ignore) {
        }
        return false;
    }

    private static String inFlightKey(final MessageObject mo, final int account) {
        if (mo == null) {
            return null;
        }
        return account + "_" + mo.getDialogId() + "_" + mo.getId();
    }

    private static void autoPipelineFinished(final MessageObject mo, final int account) {
        try {
            final String key = inFlightKey(mo, account);
            if (key != null) {
                inFlight.remove(key);
            }
        } catch (Exception ignore) {
        }
    }

    // ------------------------------------------------------------------

    private static void showError(final BaseFragment fragment, final String message) {
        if (fragment == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                BulletinFactory.of(fragment).createErrorBulletin(message).show();
            } catch (Exception ignore) {
            }
        });
    }

    // #32: prefer the CHAT's language when we already hold that Vosk model, so a Chinese
    // contact's voice is transcribed with the Chinese model even if the user last picked
    // another one. Switches only among ALREADY-downloaded models -- never starts a download,
    // and falls back to the stored/app default when the chat language has no local model.
    private static String resolveLangHint(final MessageObject mo, final int currentAccount) {
        try {
            if (mo != null) {
                final long dialogId = mo.getDialogId();
                final TranslateController tc =
                        MessagesController.getInstance(currentAccount).getTranslateController();
                if (tc != null) {
                    final String detected = tc.getDialogDetectedLanguage(dialogId);
                    if (detected != null && detected.length() > 0
                            && !TranslateController.UNKNOWN_LANGUAGE.equals(detected)) {
                        final String norm = LuminaVoskModelManager.normalizeLang(detected);
                        if (LuminaVoskModelManager.isModelReady(norm)) {
                            return LuminaVoskModelManager.isoTag(norm);
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return resolveLangHint();
    }

    private static String resolveLangHint() {
        String appLang = "en";
        try {
            final LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.getLangCode() != null && info.getLangCode().length() > 0) {
                appLang = info.getLangCode();
            }
        } catch (Exception ignore) {
        }
        final int dash = appLang.indexOf('-');
        if (dash > 0) {
            appLang = appLang.substring(0, dash);
        }
        // voskModelLang holds a Vosk language id ("cn", "ua", "en-us"), which is not always an
        // ISO code. The hint travels to the cloud engines too (GoogleSpeechTranscriber turns it
        // into a BCP-47 tag), so hand out the standard spelling; VoskTranscriber maps it back.
        final String stored = LuminaConfig.getString(LuminaVoskModelManager.CONFIG_KEY_LANG, appLang);
        return LuminaVoskModelManager.isoTag(stored);
    }
}
