package com.satori.qq.qq;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import com.satori.qq.L;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** Decode common Android audio and encode Tencent SILK_V3 that the QQ NT client can play. */
public final class AudioTranscoder {
    private static final int TARGET_RATE = 8000;
    private static final int TARGET_CHANNELS = 1;
    // SilkCodecWrapper.SilkEncoderNew(sampleRate, bitRate).  The second argument is
    // a bitrate, not a channel count (verified against QQ 9.3.50/9.3.55 JNI wrappers).
    private static final int SILK_BITRATE = 12000;
    private static final int FRAME_MS = 20;
    private static final int PCM_FRAME = TARGET_RATE * 2 * FRAME_MS / 1000; // 320
    private static final int AMR_BITRATE = 12200;
    private static final long CODEC_TIMEOUT_US = 10000;
    private static final String SILK_WRAPPER = "com.tencent.mobileqq.utils.SilkCodecWrapper";
    private static final String MOBILEQQ = "mqq.app.MobileQQ";

    private AudioTranscoder() {}

    static File toSilk(Ref ref, File source, File dir) {
        File pcm = null;
        File silk = null;
        try {
            pcm = File.createTempFile("obpcm", ".pcm", dir);
            silk = File.createTempFile("obsilk", ".silk", dir);
            decodeTo8kMonoPcm(ref, source, pcm);
            encodeSilk(ref, pcm, silk);
            if (silk.length() <= 12) throw new IllegalStateException("silk encoder produced no frames");
            L.i("Transcoded voice to SILK: " + source.length() + " -> " + silk.length() + " bytes");
            return silk;
        } catch (Throwable t) {
            L.e("audio transcode silk", t);
            if (silk != null) silk.delete();
            return null;
        } finally {
            if (pcm != null) pcm.delete();
        }
    }

    /**
     * get_record.out_format. Supported without ffmpeg: silk, wav, amr, pcm, m4a.
     * mp3 falls back to wav when the device has no MPEG encoder.
     */
    public static File convert(Ref ref, File source, String format, File dir) {
        if (source == null || !source.isFile()) return null;
        String want = format == null ? "" : format.trim().toLowerCase();
        if (want.isEmpty() || "silk".equals(want) || "slk".equals(want)) {
            return isSilk(source) ? source : toSilk(ref, source, dir);
        }
        File pcm = null;
        try {
            pcm = File.createTempFile("obpcm", ".pcm", dir);
            decodeTo8kMonoPcm(ref, source, pcm);
            if (pcm.length() < 2) throw new IllegalStateException("decoded PCM is empty");
            if ("pcm".equals(want) || "s16le".equals(want)) return pcm;
            if ("wav".equals(want) || "mp3".equals(want)) {
                File wav = File.createTempFile("obwav", ".wav", dir);
                writeWav(pcm, wav, TARGET_RATE, TARGET_CHANNELS);
                return wav;
            }
            if ("amr".equals(want)) {
                File amr = File.createTempFile("obamr", ".amr", dir);
                encodeAmr(pcm, amr);
                if (amr.length() <= 6) throw new IllegalStateException("AMR encoder produced no frames");
                return amr;
            }
            if ("m4a".equals(want) || "aac".equals(want)) {
                File m4a = File.createTempFile("obm4a", ".m4a", dir);
                encodeM4a(pcm, m4a);
                return m4a;
            }
            throw new IllegalArgumentException("unsupported out_format " + want);
        } catch (IllegalArgumentException e) {
            L.e("audio convert format", e);
            if (pcm != null) pcm.delete();
            return null;
        } catch (Throwable t) {
            L.e("audio convert", t);
            if (pcm != null) pcm.delete();
            return null;
        } finally {
            if (pcm != null && !"pcm".equals(want) && !"s16le".equals(want)) pcm.delete();
        }
    }

    public static boolean isSilk(File file) {
        if (file == null || !file.isFile()) return false;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            byte[] head = new byte[(int) Math.min(16, raf.length())];
            raf.readFully(head);
            return new String(head, "ISO-8859-1").contains("#!SILK");
        } catch (Throwable t) {
            return false;
        }
    }

    /** PCM s16le -> WAV. Public so offline tests can check the container. */
    public static void writeWav(File pcm, File wav, int sampleRate, int channels) throws Exception {
        if (sampleRate <= 0) sampleRate = TARGET_RATE;
        if (channels <= 0) channels = TARGET_CHANNELS;
        long dataLen = pcm.length();
        int byteRate = sampleRate * channels * 2;
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        writeIntLE(header, 4, (int) (36 + dataLen));
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        writeIntLE(header, 16, 16);
        writeShortLE(header, 20, 1);
        writeShortLE(header, 22, channels);
        writeIntLE(header, 24, sampleRate);
        writeIntLE(header, 28, byteRate);
        writeShortLE(header, 32, channels * 2);
        writeShortLE(header, 34, 16);
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        writeIntLE(header, 40, (int) dataLen);
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(pcm);
            out = new FileOutputStream(wav);
            out.write(header);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
            if (out != null) try { out.close(); } catch (Throwable ignore) {}
        }
    }

    /** Kept for leftover AMR samples / tests; NT client cannot play this. */
    static File toAmr(File source, File dir) {
        File pcm = null;
        File amr = null;
        try {
            pcm = File.createTempFile("obpcm", ".pcm", dir);
            amr = File.createTempFile("obamr", ".amr", dir);
            decodeTo8kMonoPcm(null, source, pcm);
            encodeAmr(pcm, amr);
            if (amr.length() <= 6) throw new IllegalStateException("AMR encoder produced no frames");
            L.i("Transcoded voice to AMR: " + source.length() + " -> " + amr.length() + " bytes");
            return amr;
        } catch (Throwable t) {
            L.e("audio transcode", t);
            if (amr != null) amr.delete();
            return null;
        } finally {
            if (pcm != null) pcm.delete();
        }
    }

    /** QQ's own encoder: `\x02#!SILK_V3` + [uint16-LE len][payload] frames, 20ms @ 8 kHz. */
    private static void encodeSilk(Ref ref, File pcm, File silk) throws Exception {
        Object app = appContext(ref);
        if (app == null) throw new IllegalStateException("no MobileQQ context");
        Object codec = silkCodec(ref, app, true);
        if (codec == null) throw new IllegalStateException("SilkCodecWrapper missing");
        long handle = 0;
        boolean initialized = false;
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            // Match QQ's own audio-processor lifecycle. b() initializes the pipe/buffers and
            // creates the native codec handle; calling SilkEncoderNew directly skips state that
            // libcodecsilk expects on recent builds.
            ref.call(codec, "b", TARGET_RATE, SILK_BITRATE, TARGET_CHANNELS);
            initialized = true;
            handle = ref.getLong(codec, "r");
            if (handle == 0) throw new IllegalStateException("SilkEncoderNew returned 0");
            input = new FileInputStream(pcm);
            output = new FileOutputStream(silk);
            output.write(0x02);
            output.write("#!SILK_V3".getBytes("US-ASCII"));
            byte[] pcmBuf = new byte[PCM_FRAME];
            byte[] silkBuf = new byte[PCM_FRAME];
            int n;
            while ((n = input.read(pcmBuf)) > 0) {
                if (n < PCM_FRAME) {
                    for (int i = n; i < PCM_FRAME; i++) pcmBuf[i] = 0;
                }
                int encoded = Ref.asInt(ref.call(codec, "encode", handle, pcmBuf, silkBuf, PCM_FRAME));
                if (encoded <= 0) continue;
                output.write(encoded & 0xff);
                output.write((encoded >> 8) & 0xff);
                output.write(silkBuf, 0, encoded);
            }
        } finally {
            if (input != null) try { input.close(); } catch (Throwable ignore) {}
            if (output != null) try { output.close(); } catch (Throwable ignore) {}
            if (initialized) try { ref.call(codec, "d"); } catch (Throwable ignore) {}
        }
    }

    private static Object appContext(Ref ref) {
        try {
            Object app = ref.callS(MOBILEQQ, "getMobileQQ");
            if (app != null) return app;
        } catch (Throwable ignore) {}
        try {
            return ref.callS("android.app.ActivityThread", "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object silkCodec(Ref ref, Object app, boolean encode) {
        try {
            Object codec = ref.neu(SILK_WRAPPER, app, encode);
            if (codec != null) return codec;
        } catch (Throwable t) {
            L.e("SilkCodecWrapper(ctx, encode)", t);
        }
        try {
            return encode ? ref.neu(SILK_WRAPPER, app) : ref.neu(SILK_WRAPPER, app, false);
        } catch (Throwable t) {
            L.e("SilkCodecWrapper fallback", t);
            return null;
        }
    }

    private static void decodeTo8kMonoPcm(Ref ref, File source, File pcm) throws Exception {
        if (isSilk(source)) {
            if (ref == null) throw new IllegalStateException("SILK decode needs QQ SilkCodecWrapper");
            decodeSilkToPcm(ref, source, pcm);
            return;
        }
        if (tryDecodeWavPcm(source, pcm)) return;
        decodeWithMediaExtractor(source, pcm);
    }

    /** Direct PCM extract for RIFF/WAVE s16le — avoids MediaExtractor failing on `.dat` temps. */
    static boolean tryDecodeWavPcm(File source, File pcm) throws Exception {
        java.io.RandomAccessFile raf = null;
        FileOutputStream out = null;
        try {
            raf = new java.io.RandomAccessFile(source, "r");
            if (raf.length() < 44) return false;
            byte[] riff = new byte[12];
            raf.readFully(riff);
            if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F') return false;
            if (riff[8] != 'W' || riff[9] != 'A' || riff[10] != 'V' || riff[11] != 'E') return false;
            int audioFormat = 0, channels = 0, sampleRate = 0, bits = 0;
            long dataSize = -1;
            long pos = 12;
            while (pos + 8 <= raf.length()) {
                raf.seek(pos);
                byte[] chunk = new byte[8];
                raf.readFully(chunk);
                int size = (chunk[4] & 0xff) | ((chunk[5] & 0xff) << 8)
                        | ((chunk[6] & 0xff) << 16) | ((chunk[7] & 0xff) << 24);
                pos += 8;
                String id = new String(chunk, 0, 4, "US-ASCII");
                if ("fmt ".equals(id)) {
                    byte[] fmt = new byte[Math.min(size, 16)];
                    raf.readFully(fmt);
                    audioFormat = (fmt[0] & 0xff) | ((fmt[1] & 0xff) << 8);
                    channels = (fmt[2] & 0xff) | ((fmt[3] & 0xff) << 8);
                    sampleRate = (fmt[4] & 0xff) | ((fmt[5] & 0xff) << 8)
                            | ((fmt[6] & 0xff) << 16) | ((fmt[7] & 0xff) << 24);
                    bits = (fmt[14] & 0xff) | ((fmt[15] & 0xff) << 8);
                } else if ("data".equals(id)) {
                    dataSize = size & 0xffffffffL;
                    break;
                }
                pos += size + (size & 1);
            }
            if (audioFormat != 1 || bits != 16 || channels <= 0 || sampleRate <= 0 || dataSize < 2)
                return false;
            out = new FileOutputStream(pcm);
            PcmDownsampler down = new PcmDownsampler(sampleRate, channels, out);
            byte[] buf = new byte[4096];
            long remain = dataSize;
            while (remain > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remain));
                if (n <= 0) break;
                down.write(java.nio.ByteBuffer.wrap(buf, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN));
                remain -= n;
            }
            return pcm.length() >= 2;
        } catch (Throwable t) {
            return false;
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable ignore) {}
            if (out != null) try { out.close(); } catch (Throwable ignore) {}
        }
    }

    private static void decodeSilkToPcm(Ref ref, File silk, File pcm) throws Exception {
        Object app = appContext(ref);
        if (app == null) throw new IllegalStateException("no MobileQQ context");
        Object codec = silkCodec(ref, app, false);
        if (codec == null) throw new IllegalStateException("SilkCodecWrapper missing");
        boolean initialized = false;
        java.io.RandomAccessFile raf = null;
        FileOutputStream out = null;
        try {
            // Exactly mirror SilkPlayerThread: b(sampleRate, 0, 1), then c() for each frame.
            // Direct SilkDecoderNew calls bypass wrapper initialization and assert in libcodecsilk.
            ref.call(codec, "b", TARGET_RATE, 0, TARGET_CHANNELS);
            initialized = true;
            if (ref.getLong(codec, "r") == 0) throw new IllegalStateException("Silk decoder not initialized");
            raf = new java.io.RandomAccessFile(silk, "r");
            byte[] head = new byte[(int) Math.min(16, raf.length())];
            raf.readFully(head);
            String h = new String(head, "ISO-8859-1");
            int headerLen = h.indexOf("#!SILK");
            if (headerLen < 0) throw new IllegalArgumentException("not SILK");
            headerLen += 9; // #!SILK_V3
            raf.seek(headerLen);
            out = new FileOutputStream(pcm);
            byte[] silkBuf = new byte[1024];
            byte[] pcmBuf = new byte[PCM_FRAME * 2];
            while (raf.getFilePointer() + 2 <= raf.length()) {
                int lo = raf.read();
                int hi = raf.read();
                if (lo < 0 || hi < 0) break;
                int blk = lo | (hi << 8);
                if (blk <= 0 || blk == 0xFFFF || blk > silkBuf.length) break;
                raf.readFully(silkBuf, 0, blk);
                int n = Ref.asInt(ref.call(codec, "c", silkBuf, pcmBuf, blk, pcmBuf.length));
                if (n > 0) out.write(pcmBuf, 0, Math.min(n, pcmBuf.length));
                else out.write(new byte[PCM_FRAME]);
            }
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable ignore) {}
            if (out != null) try { out.close(); } catch (Throwable ignore) {}
            if (initialized) try { ref.call(codec, "d"); } catch (Throwable ignore) {}
        }
    }

    private static void decodeWithMediaExtractor(File source, File pcm) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        FileOutputStream output = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            MediaFormat inputFormat = null;
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; inputFormat = f; break; }
            }
            if (track < 0 || inputFormat == null) throw new IllegalArgumentException("no audio track");
            extractor.selectTrack(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();
            output = new FileOutputStream(pcm);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            PcmDownsampler downsampler = null;
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        if (buffer == null) throw new IllegalStateException("decoder input buffer null");
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = decoder.getOutputFormat();
                    int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int encoding = f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? f.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT)
                        throw new IllegalArgumentException("unsupported decoder PCM encoding " + encoding);
                    downsampler = new PcmDownsampler(rate, channels, output);
                } else if (index >= 0) {
                    if (info.size > 0) {
                        if (downsampler == null) {
                            MediaFormat f = decoder.getOutputFormat();
                            downsampler = new PcmDownsampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                    f.getInteger(MediaFormat.KEY_CHANNEL_COUNT), output);
                        }
                        ByteBuffer buffer = decoder.getOutputBuffer(index);
                        if (buffer == null) throw new IllegalStateException("decoder output buffer null");
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        downsampler.write(buffer);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(index, false);
                }
            }
        } finally {
            if (output != null) try { output.close(); } catch (Throwable ignore) {}
            if (decoder != null) {
                try { decoder.stop(); } catch (Throwable ignore) {}
                try { decoder.release(); } catch (Throwable ignore) {}
            }
            try { extractor.release(); } catch (Throwable ignore) {}
        }
    }

    private static final class PcmDownsampler {
        private final int sourceRate;
        private final int channels;
        private final FileOutputStream output;
        private long phase;

        PcmDownsampler(int sourceRate, int channels, FileOutputStream output) {
            if (sourceRate <= 0 || channels <= 0) throw new IllegalArgumentException("bad PCM format");
            this.sourceRate = sourceRate;
            this.channels = channels;
            this.output = output;
        }

        void write(ByteBuffer input) throws Exception {
            int frameBytes = channels * 2;
            while (input.remaining() >= frameBytes) {
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int lo = input.get() & 0xff;
                    int hi = input.get();
                    sum += (short) (lo | (hi << 8));
                }
                short mono = (short) (sum / channels);
                phase += TARGET_RATE;
                while (phase >= sourceRate) {
                    output.write(mono & 0xff);
                    output.write((mono >>> 8) & 0xff);
                    phase -= sourceRate;
                }
            }
        }
    }

    private static void encodeAmr(File pcm, File amr) throws Exception {
        MediaCodec encoder = null;
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB,
                    TARGET_RATE, TARGET_CHANNELS);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AMR_BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            input = new FileInputStream(pcm);
            output = new FileOutputStream(amr);
            output.write("#!AMR\n".getBytes("US-ASCII"));

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long samplesQueued = 0;
            byte[] scratch = new byte[8192];
            while (!outputDone) {
                if (!inputDone) {
                    int index = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(index);
                        if (buffer == null) throw new IllegalStateException("encoder input buffer null");
                        buffer.clear();
                        int want = Math.min(buffer.remaining(), scratch.length) & ~1;
                        int size = input.read(scratch, 0, want);
                        long pts = samplesQueued * 1000000L / TARGET_RATE;
                        if (size < 0) {
                            encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            buffer.put(scratch, 0, size);
                            encoder.queueInputBuffer(index, 0, size, pts, 0);
                            samplesQueued += size / 2;
                        }
                    }
                }

                int index = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (index >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer buffer = encoder.getOutputBuffer(index);
                        if (buffer == null) throw new IllegalStateException("encoder output buffer null");
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        byte[] encoded = new byte[info.size];
                        buffer.get(encoded);
                        output.write(encoded);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(index, false);
                }
            }
        } finally {
            if (input != null) try { input.close(); } catch (Throwable ignore) {}
            if (output != null) try { output.close(); } catch (Throwable ignore) {}
            if (encoder != null) {
                try { encoder.stop(); } catch (Throwable ignore) {}
                try { encoder.release(); } catch (Throwable ignore) {}
            }
        }
    }

    /** 8 kHz PCM → 16 kHz AAC inside MPEG-4. */
    private static void encodeM4a(File pcm, File m4a) throws Exception {
        final int aacRate = 16000;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        FileInputStream input = null;
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    aacRate, TARGET_CHANNELS);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(m4a.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            input = new FileInputStream(pcm);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false, muxStarted = false;
            int track = -1;
            long samplesQueued = 0;
            byte[] scratch = new byte[320]; // 20ms @ 8kHz
            while (!outputDone) {
                if (!inputDone) {
                    int index = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(index);
                        if (buffer == null) throw new IllegalStateException("aac input buffer null");
                        buffer.clear();
                        int n = input.read(scratch);
                        long pts = samplesQueued * 1000000L / aacRate;
                        if (n < 0) {
                            encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            if ((n & 1) != 0) n--;
                            // Duplicate each 8 kHz sample so AAC sees 16 kHz.
                            for (int i = 0; i + 1 < n && buffer.remaining() >= 4; i += 2) {
                                byte lo = scratch[i], hi = scratch[i + 1];
                                buffer.put(lo); buffer.put(hi);
                                buffer.put(lo); buffer.put(hi);
                                samplesQueued += 2;
                            }
                            encoder.queueInputBuffer(index, 0, buffer.position(), pts, 0);
                        }
                    }
                }
                int index = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxStarted) {
                        track = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxStarted = true;
                    }
                } else if (index >= 0) {
                    if (info.size > 0 && muxStarted && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer buffer = encoder.getOutputBuffer(index);
                        if (buffer == null) throw new IllegalStateException("aac output buffer null");
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(index, false);
                }
            }
            if (!muxStarted) throw new IllegalStateException("AAC encoder produced no format");
        } finally {
            if (input != null) try { input.close(); } catch (Throwable ignore) {}
            if (muxer != null) {
                try { muxer.stop(); } catch (Throwable ignore) {}
                try { muxer.release(); } catch (Throwable ignore) {}
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Throwable ignore) {}
                try { encoder.release(); } catch (Throwable ignore) {}
            }
        }
    }

    private static void writeIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void writeShortLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
