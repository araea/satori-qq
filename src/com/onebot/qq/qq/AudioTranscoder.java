package com.onebot.qq.qq;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import com.onebot.qq.L;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** Decode common Android audio containers and encode an AMR-NB voice file accepted by QQ NT. */
final class AudioTranscoder {
    private static final int TARGET_RATE = 8000;
    private static final int TARGET_CHANNELS = 1;
    private static final int AMR_BITRATE = 12200;
    private static final long CODEC_TIMEOUT_US = 10000;

    private AudioTranscoder() {}

    static File toAmr(File source, File dir) {
        File pcm = null;
        File amr = null;
        try {
            pcm = File.createTempFile("obpcm", ".pcm", dir);
            amr = File.createTempFile("obamr", ".amr", dir);
            decodeTo8kMonoPcm(source, pcm);
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

    private static void decodeTo8kMonoPcm(File source, File pcm) throws Exception {
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
}
