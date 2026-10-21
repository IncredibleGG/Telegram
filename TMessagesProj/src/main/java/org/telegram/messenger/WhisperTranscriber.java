package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * LuminaGram — cloud speech-to-text via the OpenAI-compatible Whisper transcription
 * endpoint (bring-your-own key / base URL / model). POSTs the raw voice file as
 * {@code multipart/form-data} to {@code {base}/audio/transcriptions} and parses the
 * {@code {"text":"..."}} JSON response.
 *
 * The shared JSON helper ({@link LuminaTranslatorUtil#http}) can only send a string
 * body and marshals its callback onto the UI thread with the translator callback type,
 * so this engine writes its own multipart streamer on {@link Utilities#globalQueue} and
 * delivers {@link Callback} on that background thread, as the {@link LuminaTranscriber}
 * contract requires. Only the small {@code readAll}/{@code trimTrailingSlash} utilities
 * are reused.
 *
 * Security (mirrors {@link LuminaTranslatorUtil}): the API key rides only in the
 * Authorization header, a redirect to another host or from https to http is refused instead
 * of replaying it ({@link LuminaTranslatorUtil#open}; same-host redirects and plain http://
 * endpoints keep working), and errors surfaced to the callback carry only a short category or
 * the HTTP status, never the URL, request or response body.
 */
final class WhisperTranscriber implements LuminaTranscriber {

    /** OpenAI hard limit for the transcription endpoint. */
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int TIMEOUT = 60000;
    private static final String CRLF = "\r\n";

    @Override
    public String id() {
        return "whisper";
    }

    @Override
    public String displayName() {
        return "Whisper";
    }

    @Override
    public boolean isOffline() {
        return false;
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
    public void transcribe(File audio, String langHint, Callback cb) {
        // Never touch the network on the caller thread; hop onto the shared background queue.
        Utilities.globalQueue.postRunnable(() -> run(audio, cb));
    }

    private void run(File audio, Callback cb) {
        final String key = LuminaConfig.getString("sttKey_" + id(), "").trim();
        if (TextUtils.isEmpty(key)) {
            cb.onError("Missing API key");
            return;
        }
        if (audio == null || !audio.exists() || !audio.isFile()) {
            cb.onError("Audio file not found");
            return;
        }
        final long fileLen = audio.length();
        if (fileLen <= 0) {
            cb.onError("Audio file is empty");
            return;
        }
        if (fileLen > MAX_BYTES) {
            cb.onError("Audio file too large (max 25 MB)");
            return;
        }

        final String base = LuminaTranslatorUtil.trimTrailingSlash(
                LuminaConfig.getString("sttCloudBaseUrl", "https://api.openai.com/v1"));
        final String model = LuminaConfig.getString("sttModel", "whisper-1");
        final String url = base + "/audio/transcriptions";
        final String boundary = "----LuminaGramBoundary" + System.currentTimeMillis();
        // Voice notes are OGG/Opus; round videos arrive here as an audio-only 16 kHz WAV
        // (LuminaVoiceToText extracts the sound track so video frames are never uploaded).
        // The endpoint picks the decoder from the file name, so name each one after what it is.
        final boolean wav = audio.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
        final String partName = wav ? "voice.wav" : "voice.ogg";
        final String partType = wav ? "audio/wav" : "audio/ogg";

        // Pre-computed multipart envelope: the "model" text part + the "file" part header.
        final byte[] pre = (
                "--" + boundary + CRLF
                        + "Content-Disposition: form-data; name=\"model\"" + CRLF + CRLF
                        + model + CRLF
                        + "--" + boundary + CRLF
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + partName + "\"" + CRLF
                        + "Content-Type: " + partType + CRLF + CRLF
        ).getBytes(StandardCharsets.UTF_8);
        final byte[] post = (CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = null;
        try {
            cb.onProgress(0f);
            final Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + key);
            headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
            c = LuminaTranslatorUtil.open("POST", url, headers, conn -> {
                conn.setDoOutput(true);
                // Known exact length -> stream the file without buffering the whole body in memory.
                conn.setFixedLengthStreamingMode((long) pre.length + fileLen + post.length);

                final OutputStream os = conn.getOutputStream();
                final InputStream fis = new BufferedInputStream(new FileInputStream(audio));
                try {
                    os.write(pre);
                    final byte[] buf = new byte[16 * 1024];
                    long sent = 0;
                    int n;
                    while ((n = fis.read(buf)) != -1) {
                        os.write(buf, 0, n);
                        sent += n;
                        cb.onProgress(Math.min(0.99f, (float) sent / (float) fileLen));
                    }
                    os.write(post);
                    os.flush();
                } finally {
                    try {
                        fis.close();
                    } catch (Exception ignore) {
                    }
                    try {
                        os.close();
                    } catch (Exception ignore) {
                    }
                }
            }, TIMEOUT, TIMEOUT, true);

            final int code = c.getResponseCode();
            final InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            final String raw = LuminaTranslatorUtil.readAll(in);
            if (code >= 400) {
                cb.onError("HTTP " + code);
                return;
            }
            final JSONObject json = new JSONObject(raw);
            final String text = json.optString("text", "");
            cb.onProgress(1f);
            if (TextUtils.isEmpty(text)) {
                cb.onError("Empty transcription");
            } else {
                cb.onResult(text);
            }
        } catch (Exception e) {
            // Category only: an exception message can embed the (user-entered) endpoint URL.
            cb.onError(LuminaTranslatorUtil.errorText(e));
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
