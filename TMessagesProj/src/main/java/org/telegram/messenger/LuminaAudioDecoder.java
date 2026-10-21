package org.telegram.messenger;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Decodes an arbitrary compressed audio file (Telegram voice notes are OGG/Opus;
 * round videos carry an AAC track inside mp4) into 16 kHz mono signed-16-bit PCM,
 * which is what the offline Vosk recognizer expects.
 *
 * <p>Pipeline: {@link MediaExtractor} selects the first audio track →
 * {@link MediaCodec} decoder produces raw PCM (16-bit, or float which we convert) →
 * stereo/multi-channel is down-mixed to mono → the codec's native rate (usually 48000
 * for Opus) is linearly resampled to 16000. All work happens synchronously on the
 * calling thread, so this MUST be invoked off the main thread.
 */
public final class LuminaAudioDecoder {

    private LuminaAudioDecoder() {}

    private static final int TARGET_RATE = 16000;
    private static final long DEQUEUE_TIMEOUT_US = 10000;
    private static final short[] EMPTY = new short[0];

    /**
     * @param input a compressed audio file (OGG/Opus voice note, or mp4 round video).
     * @return 16 kHz mono PCM as signed 16-bit samples; empty array for silent/short input.
     * @throws Exception on unreadable input, missing audio track, or decoder failure.
     */
    public static short[] decodeToPcm16kMono(File input) throws Exception {
        if (input == null || !input.exists() || input.length() == 0) {
            throw new Exception("LuminaAudioDecoder: input file missing or empty");
        }

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int track = selectAudioTrack(extractor);
            if (track < 0) {
                throw new Exception("LuminaAudioDecoder: no audio track in " + input.getName());
            }
            extractor.selectTrack(track);

            MediaFormat inputFormat = extractor.getTrackFormat(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new Exception("LuminaAudioDecoder: track has no mime");
            }

            // Best-effort seed from the input format; the OUTPUT format (read after the first
            // INFO_OUTPUT_FORMAT_CHANGED) is authoritative and can differ.
            int srcRate = getInt(inputFormat, MediaFormat.KEY_SAMPLE_RATE, 48000);
            int srcChannels = getInt(inputFormat, MediaFormat.KEY_CHANNEL_COUNT, 1);
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            final ArrayList<short[]> chunks = new ArrayList<>();
            long totalMono = 0;
            final ResampleState rs = new ResampleState();

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int sampleSize = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            short[] pcm = readPcm(outBuf, pcmEncoding);
                            short[] mono16k = downmixAndResample(pcm, srcChannels, srcRate, rs);
                            if (mono16k.length > 0) {
                                chunks.add(mono16k);
                                totalMono += mono16k.length;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    srcRate = getInt(outFormat, MediaFormat.KEY_SAMPLE_RATE, srcRate);
                    srcChannels = getInt(outFormat, MediaFormat.KEY_CHANNEL_COUNT, srcChannels);
                    if (Build.VERSION.SDK_INT >= 24) {
                        pcmEncoding = getInt(outFormat, MediaFormat.KEY_PCM_ENCODING, pcmEncoding);
                    }
                }
                // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED: just keep looping.
            }

            if (totalMono == 0) {
                return EMPTY;
            }
            short[] result = new short[(int) Math.min(totalMono, Integer.MAX_VALUE)];
            int pos = 0;
            for (short[] c : chunks) {
                System.arraycopy(c, 0, result, pos, c.length);
                pos += c.length;
            }
            return result;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignore) {
                }
                try {
                    codec.release();
                } catch (Throwable ignore) {
                }
            }
            try {
                extractor.release();
            } catch (Throwable ignore) {
            }
        }
    }

    /** Bytes per WAV header written by {@link #writeWav16kMono}: plain 44-byte RIFF/PCM header. */
    public static final int WAV_HEADER_BYTES = 44;

    /**
     * Decode {@code input} (e.g. the AAC track of a round-video mp4) to 16 kHz mono 16-bit PCM
     * and write it to {@code output} as a plain WAV file. Lets a cloud speech-to-text engine
     * receive the AUDIO ONLY, so video frames never leave the device. Same off-main-thread rule
     * as {@link #decodeToPcm16kMono(File)}.
     *
     * @return the number of samples written; 0 for silent / audio-less-but-decodable input.
     */
    public static int writeWav16kMono(File input, File output) throws Exception {
        final short[] pcm = decodeToPcm16kMono(input);
        final int dataBytes = pcm.length * 2;
        final OutputStream os = new BufferedOutputStream(new FileOutputStream(output));
        try {
            final ByteBuffer header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{'R', 'I', 'F', 'F'});
            header.putInt(36 + dataBytes);
            header.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            header.putInt(16);                  // fmt chunk size
            header.putShort((short) 1);         // PCM
            header.putShort((short) 1);         // mono
            header.putInt(TARGET_RATE);
            header.putInt(TARGET_RATE * 2);     // byte rate
            header.putShort((short) 2);         // block align
            header.putShort((short) 16);        // bits per sample
            header.put(new byte[]{'d', 'a', 't', 'a'});
            header.putInt(dataBytes);
            os.write(header.array());

            final ByteBuffer chunk = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN);
            for (short sample : pcm) {
                if (chunk.remaining() < 2) {
                    os.write(chunk.array(), 0, chunk.position());
                    chunk.clear();
                }
                chunk.putShort(sample);
            }
            if (chunk.position() > 0) {
                os.write(chunk.array(), 0, chunk.position());
            }
        } finally {
            os.close();
        }
        return pcm.length;
    }

    // ---- helpers ----

    private static int selectAudioTrack(MediaExtractor extractor) {
        int count = extractor.getTrackCount();
        for (int i = 0; i < count; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static int getInt(MediaFormat format, String key, int def) {
        try {
            if (format != null && format.containsKey(key)) {
                return format.getInteger(key);
            }
        } catch (Throwable ignore) {
        }
        return def;
    }

    /** Read a decoder output buffer as 16-bit PCM shorts, converting from float if needed. */
    private static short[] readPcm(ByteBuffer outBuf, int pcmEncoding) {
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            FloatBuffer fb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            short[] pcm = new short[fb.remaining()];
            for (int i = 0; i < pcm.length; i++) {
                float f = fb.get();
                int v = Math.round(f * 32767f);
                if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                pcm[i] = (short) v;
            }
            return pcm;
        }
        ShortBuffer sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] pcm = new short[sb.remaining()];
        sb.get(pcm);
        return pcm;
    }

    /** Carry state so linear resampling stays continuous across decoder output buffers. */
    private static final class ResampleState {
        double frac;       // fractional read position in the current virtual source array
        short prev;        // last mono sample of the previous buffer (virtual index 0)
        boolean hasPrev;   // false only before the very first buffer
    }

    /**
     * Down-mix interleaved multi-channel 16-bit PCM to mono, then linearly resample from
     * {@code srcRate} to {@link #TARGET_RATE}. The virtual source seen by the resampler is
     * {@code [prev, mono[0], mono[1], ...]} so interpolation is continuous over buffer seams.
     */
    private static short[] downmixAndResample(short[] interleaved, int channels, int srcRate, ResampleState rs) {
        if (interleaved.length == 0) {
            return EMPTY;
        }
        // 1) down-mix to mono
        int ch = Math.max(1, channels);
        int frames = interleaved.length / ch;
        if (frames == 0) {
            return EMPTY;
        }
        short[] mono;
        if (ch == 1) {
            mono = interleaved.length == frames ? interleaved : Arrays.copyOf(interleaved, frames);
        } else {
            mono = new short[frames];
            for (int i = 0; i < frames; i++) {
                int base = i * ch;
                int sum = 0;
                for (int c = 0; c < ch; c++) {
                    sum += interleaved[base + c];
                }
                mono[i] = (short) (sum / ch);
            }
        }

        // 2) linear resample srcRate -> TARGET_RATE
        double step = (double) (srcRate <= 0 ? TARGET_RATE : srcRate) / TARGET_RATE;
        short prev = rs.hasPrev ? rs.prev : mono[0];
        double frac = rs.hasPrev ? rs.frac : 0.0;
        int n = frames; // virtual indices: 0 = prev, 1..n = mono[0..n-1]

        int estimate = (int) ((n - frac) / step) + 2;
        if (estimate < 0) estimate = 0;
        short[] out = new short[estimate];
        int oc = 0;
        while (Math.floor(frac) + 1 <= n) {
            int i0 = (int) Math.floor(frac);
            double w = frac - i0;
            int s0 = (i0 == 0) ? prev : mono[i0 - 1];
            int s1 = mono[i0]; // virtual[i0 + 1] == mono[i0]
            double val = s0 * (1.0 - w) + s1 * w;
            if (oc >= out.length) {
                out = Arrays.copyOf(out, out.length + 32);
            }
            long r = Math.round(val);
            if (r > Short.MAX_VALUE) r = Short.MAX_VALUE;
            else if (r < Short.MIN_VALUE) r = Short.MIN_VALUE;
            out[oc++] = (short) r;
            frac += step;
        }

        rs.prev = mono[n - 1];
        rs.frac = frac - n;
        if (rs.frac < 0) rs.frac = 0;
        rs.hasPrev = true;

        return oc == out.length ? out : Arrays.copyOf(out, oc);
    }
}
