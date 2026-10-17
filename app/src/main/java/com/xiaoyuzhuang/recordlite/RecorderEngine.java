package com.xiaoyuzhuang.recordlite;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 20 ms PCM capture -> RMS silence gate -> platform encoder -> Ogg/M4A.
 * Only retained PCM is passed to MediaCodec. A CSV maps its compact time to UTC.
 */
final class RecorderEngine {
    interface Listener {
        void onInfo(String info);
        void onProgress(long currentBytes, long savedDurationMs);
    }

    private static final int FRAME_MS = 20;
    private static final int PRE_ROLL_MS = 400;

    private final RecordingSettings settings;
    private final File directory;
    private final Listener listener;
    private final ArrayDeque<Frame> preRoll = new ArrayDeque<>();
    private volatile boolean running = true;
    private AudioRecord recorder;
    private EncodedFile writer;
    private boolean inSegment;
    private int quietMs;
    private long segmentWallStart;
    private long segmentAudioStart;
    private long latestWallEnd;
    private int completedFiles;
    private long lastProgressMs;

    private static final class Frame {
        final short[] pcm;
        final long timeMs;
        Frame(short[] pcm, long timeMs) { this.pcm = pcm; this.timeMs = timeMs; }
    }

    RecorderEngine(RecordingSettings settings, File directory, Listener listener) {
        this.settings = settings;
        this.directory = directory;
        this.listener = listener;
    }

    void requestStop() { running = false; }

    void run() throws Exception {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("不能建立录音目录");
        }

        int min = AudioRecord.getMinBufferSize(settings.sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalArgumentException("设备不支持此采样率");
        // Approximately one second of PCM buffering; not a one-second latency.
        int bufferBytes = Math.max(min * 2, settings.sampleRate * 2);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                settings.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("麦克风初始化失败，请更换采样率");
        }

        final int frameSamples = settings.sampleRate * FRAME_MS / 1000;
        try {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("录音启动失败，请检查麦克风权限");
            }
            listener.onInfo("正在录音 · " + settings.codec.toUpperCase(Locale.US) +
                    " · " + (settings.bitrate / 1000) + " kbps");

            while (running) {
                short[] pcm = new short[frameSamples];
                int filled = 0;
                while (running && filled < frameSamples) {
                    int got = recorder.read(pcm, filled, frameSamples - filled, AudioRecord.READ_BLOCKING);
                    if (got < 0) throw new IllegalStateException("麦克风读取失败：" + got);
                    if (got == 0) continue;
                    filled += got;
                }
                if (!running || filled < frameSamples) break;
                long now = System.currentTimeMillis();
                if (writer != null && now - writer.startedMs >= settings.chunkMinutes * 60_000L) {
                    closeSegment();
                    finishFile();
                    preRoll.clear();
                }
                process(new Frame(pcm, now - FRAME_MS));
                if (now - lastProgressMs >= 700) {
                    lastProgressMs = now;
                    listener.onProgress(writer == null ? 0 : writer.pendingFile.length(),
                            writer == null ? 0 : writer.durationMs());
                }
            }
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) { }
                recorder.release();
                recorder = null;
            }
            try {
                closeSegment();
                finishFile();
            } finally {
                listener.onInfo("已停止，共保存 " + completedFiles + " 个录音文件");
            }
        }
    }

    private void process(Frame frame) throws Exception {
        if (!settings.skipSilence || dbfs(frame.pcm) >= settings.thresholdDb) {
            if (!inSegment) {
                Frame first = preRoll.peekFirst();
                startSegment(first != null ? first.timeMs : frame.timeMs);
                while (!preRoll.isEmpty()) write(preRoll.removeFirst());
            }
            inSegment = true;
            quietMs = 0;
            write(frame);
        } else if (inSegment && quietMs < settings.tailMs) {
            write(frame);
            quietMs += FRAME_MS;
        } else {
            if (inSegment) closeSegment();
            preRoll.addLast(frame);
            while (preRoll.size() > PRE_ROLL_MS / FRAME_MS) preRoll.removeFirst();
        }
    }

    private void startSegment(long wallTime) throws Exception {
        if (writer == null) {
            writer = new EncodedFile(directory, settings, wallTime);
            listener.onInfo("保存中 · " + writer.finalFile.getName());
        }
        segmentWallStart = wallTime;
        segmentAudioStart = writer.durationMs();
        latestWallEnd = wallTime;
        inSegment = true;
    }

    private void write(Frame frame) throws Exception {
        writer.write(frame.pcm);
        latestWallEnd = frame.timeMs + FRAME_MS;
    }

    private void closeSegment() throws Exception {
        if (!inSegment) return;
        inSegment = false;
        quietMs = 0;
        if (writer != null) {
            writer.logSegment(segmentAudioStart, writer.durationMs(), segmentWallStart, latestWallEnd);
        }
    }

    private void finishFile() throws Exception {
        if (writer == null) return;
        EncodedFile closing = writer;
        writer = null;
        if (closing.finish()) completedFiles++;
        listener.onProgress(0, 0);
    }

    private static double dbfs(short[] pcm) {
        double squares = 0;
        for (short sample : pcm) {
            double amplitude = sample / 32768.0;
            squares += amplitude * amplitude;
        }
        return 20.0 * Math.log10(Math.max(1e-9, Math.sqrt(squares / pcm.length)));
    }

    private static final class EncodedFile {
        final File finalFile;
        final File pendingFile;
        final File csvFile;
        final long startedMs;
        final int sampleRate;
        final MediaCodec codec;
        final MediaMuxer muxer;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        long submittedSamples;
        boolean muxerStarted;
        boolean hasBytes;
        boolean finished;

        EncodedFile(File dir, RecordingSettings s, long startedMs) throws Exception {
            this.startedMs = startedMs;
            this.sampleRate = s.sampleRate;
            boolean opus = "opus".equals(s.codec);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date(startedMs));
            String name = "RecordLite_" + stamp + (opus ? ".ogg" : ".m4a");
            finalFile = new File(dir, name);
            pendingFile = new File(dir, name + ".pending");
            csvFile = new File(dir, name + ".csv");

            String mime = opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
            MediaCodec encoder = MediaCodec.createEncoderByType(mime);
            MediaMuxer localMuxer = null;
            try {
                MediaFormat format = MediaFormat.createAudioFormat(mime, s.sampleRate, 1);
                format.setInteger(MediaFormat.KEY_BIT_RATE, s.bitrate);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                if (!opus) {
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
                localMuxer = new MediaMuxer(pendingFile.getAbsolutePath(),
                        opus ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG :
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (Exception e) {
                try { encoder.release(); } catch (Exception ignored) { }
                if (localMuxer != null) localMuxer.release();
                pendingFile.delete();
                throw new IllegalStateException("编码器不支持所选参数，请调整码率/采样率或切换 AAC", e);
            }
            codec = encoder;
            muxer = localMuxer;
            try (FileWriter fw = new FileWriter(csvFile)) {
                fw.write("audio_start_ms,audio_end_ms,wall_start_utc,wall_end_utc\n");
            }
        }

        long durationMs() { return submittedSamples * 1000L / sampleRate; }

        void write(short[] pcm) throws Exception {
            int inputIndex = -1;
            for (int tries = 0; tries < 100 && inputIndex < 0; tries++) {
                inputIndex = codec.dequeueInputBuffer(10_000);
                if (inputIndex < 0) drain(false);
            }
            if (inputIndex < 0) throw new IllegalStateException("编码器输入超时");
            ByteBuffer input = codec.getInputBuffer(inputIndex);
            if (input == null || input.capacity() < pcm.length * 2) {
                throw new IllegalStateException("编码器输入缓冲区太小");
            }
            input.clear();
            input.order(ByteOrder.nativeOrder());
            for (short value : pcm) input.putShort(value);
            long presentationUs = submittedSamples * 1_000_000L / sampleRate;
            codec.queueInputBuffer(inputIndex, 0, pcm.length * 2, presentationUs, 0);
            submittedSamples += pcm.length;
            drain(false);
        }

        private boolean drain(boolean waitForEos) {
            for (int attempts = 0; attempts < (waitForEos ? 250 : 16); attempts++) {
                int index = codec.dequeueOutputBuffer(info, waitForEos ? 10_000 : 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (waitForEos) continue;
                    return false;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("编码器重复改变输出格式");
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    continue;
                }
                if (index < 0) continue;
                ByteBuffer output = codec.getOutputBuffer(index);
                if (output != null && info.size > 0 &&
                        (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if (!muxerStarted) throw new IllegalStateException("容器尚未初始化");
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    muxer.writeSampleData(track, output, info);
                    hasBytes = true;
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(index, false);
                if (eos) return true;
            }
            return false;
        }

        void logSegment(long audioStart, long audioEnd, long wallStart, long wallEnd)
                throws Exception {
            try (FileWriter fw = new FileWriter(csvFile, true)) {
                fw.write(audioStart + "," + audioEnd + "," +
                        Instant.ofEpochMilli(wallStart) + "," +
                        Instant.ofEpochMilli(wallEnd) + "\n");
            }
        }

        boolean finish() throws Exception {
            if (finished) return hasBytes;
            finished = true;
            Exception error = null;
            try {
                int index = -1;
                for (int tries = 0; tries < 100 && index < 0; tries++) {
                    index = codec.dequeueInputBuffer(10_000);
                    if (index < 0) drain(false);
                }
                if (index < 0) throw new IllegalStateException("编码器无法结束录音");
                codec.queueInputBuffer(index, 0, 0,
                        submittedSamples * 1_000_000L / sampleRate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                if (!drain(true)) throw new IllegalStateException("编码器结束超时");
            } catch (Exception e) {
                error = e;
            } finally {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
                try { if (muxerStarted) muxer.stop(); }
                catch (Exception e) { if (error == null) error = e; }
                muxer.release();
            }
            if (error != null) throw error;
            if (!hasBytes) {
                pendingFile.delete();
                csvFile.delete();
                return false;
            }
            if (!pendingFile.renameTo(finalFile)) {
                throw new IllegalStateException("保存音频文件失败：" + pendingFile.getName());
            }
            return true;
        }
    }
}
