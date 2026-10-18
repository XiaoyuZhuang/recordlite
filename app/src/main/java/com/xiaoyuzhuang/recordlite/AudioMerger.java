package com.xiaoyuzhuang.recordlite;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lossless compressed packet remux. Source files must share audio codec,
 * sample rate and channel count. A MediaMuxer stream cannot safely be built
 * by appending complete Ogg or MP4 containers byte-for-byte.
 */
final class AudioMerger {
    interface Progress { void onProgress(int completed, int total, int percent); }

    static File merge(List<File> ordered, File directory, Progress callback) throws Exception {
        if (ordered.size() < 2) throw new IllegalArgumentException("至少选择两个分卷");
        long totalSourceBytes = 0;
        for (File audio : ordered) {
            if (!audio.isFile()) throw new IllegalArgumentException("录音文件不存在：" + audio.getName());
            totalSourceBytes += Math.max(1L, audio.length());
        }
        final long allBytes = Math.max(1L, totalSourceBytes);
        long completedBytes = 0L;
        long lastReported = 0L;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建合并文件目录");
        }
        String firstName = ordered.get(0).getName().toLowerCase(Locale.US);
        final boolean ogg = firstName.endsWith(".ogg");
        if (!ogg && !firstName.endsWith(".m4a")) {
            throw new IllegalArgumentException("只能合并 Ogg 或 M4A 录音");
        }
        String ext = ogg ? ".ogg" : ".m4a";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File target = new File(directory, "RecordLite_Merged_" + stamp + ext);
        File partial = new File(directory, target.getName() + ".pending");
        File combinedCsv = new File(directory, target.getName() + ".csv");
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean successful = false;
        long offsetUs = 0;
        String firstMime = null;
        int firstRate = -1, firstChannels = -1;
        try {
            muxer = new MediaMuxer(partial.getAbsolutePath(), ogg
                    ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                    : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int trackIndex = -1;
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo sampleInfo = new MediaCodec.BufferInfo();
            try (BufferedWriter csv = new BufferedWriter(new FileWriter(combinedCsv))) {
                csv.write("audio_start_ms,audio_end_ms,wall_start_utc,wall_end_utc\n");
                for (int n = 0; n < ordered.size(); n++) {
                    File file = ordered.get(n);
                    if (!file.isFile() || !file.getName().toLowerCase(Locale.US).endsWith(ext)) {
                        throw new IllegalArgumentException("请选择编码格式相同的录音分卷");
                    }
                    MediaExtractor extractor = new MediaExtractor();
                    try {
                        extractor.setDataSource(file.getAbsolutePath());
                        int audioTrack = -1;
                        MediaFormat format = null;
                        for (int t = 0; t < extractor.getTrackCount(); t++) {
                            MediaFormat candidate = extractor.getTrackFormat(t);
                            String mime = candidate.getString(MediaFormat.KEY_MIME);
                            if (mime != null && mime.startsWith("audio/")) {
                                audioTrack = t;
                                format = candidate;
                                break;
                            }
                        }
                        if (audioTrack < 0 || format == null) {
                            throw new IllegalArgumentException("不能读取音频：" + file.getName());
                        }
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (!muxerStarted) {
                            firstMime = mime;
                            firstRate = rate;
                            firstChannels = channels;
                            trackIndex = muxer.addTrack(format);
                            muxer.start();
                            muxerStarted = true;
                        } else if (!firstMime.equals(mime) || firstRate != rate ||
                                firstChannels != channels) {
                            throw new IllegalArgumentException(
                                    "选中的分卷采样率或编码方式不同，无法无损合并。请只选择参数相同的录音。");
                        }
                        extractor.selectTrack(audioTrack);
                        long firstSampleUs = -1;
                        long previousUs = offsetUs - 1;
                        long frameDurationUs = mime.equals("audio/mp4a-latm")
                                ? Math.max(1, 1_024_000_000L / rate) : 20_000L;
                        long sampleCount = 0;
                        long sampledBytes = 0;
                        while (true) {
                            buffer.clear();
                            int size = extractor.readSampleData(buffer, 0);
                            if (size < 0) break;
                            if (size > buffer.capacity()) {
                                throw new IllegalStateException("音频数据块超过缓冲区");
                            }
                            long sourceUs = extractor.getSampleTime();
                            if (firstSampleUs < 0) firstSampleUs = Math.max(0, sourceUs);
                            sampleInfo.offset = 0;
                            sampleInfo.size = size;
                            sampleInfo.presentationTimeUs = Math.max(previousUs + 1,
                                    offsetUs + Math.max(0, sourceUs - firstSampleUs));
                            sampleInfo.flags = extractor.getSampleFlags() &
                                    MediaExtractor.SAMPLE_FLAG_SYNC;
                            buffer.position(0);
                            buffer.limit(size);
                            muxer.writeSampleData(trackIndex, buffer, sampleInfo);
                            previousUs = sampleInfo.presentationTimeUs;
                            sampleCount++;
                            sampledBytes += size;
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastReported >= 250) {
                                lastReported = now;
                                long currentBytes = completedBytes +
                                        Math.min(file.length(), sampledBytes);
                                int percent = (int) Math.min(99L,
                                        currentBytes * 100L / allBytes);
                                callback.onProgress(n, ordered.size(), percent);
                            }
                            extractor.advance();
                        }
                        long nextOffset = sampleCount == 0
                                ? offsetUs : previousUs + frameDurationUs;
                        appendCsv(new File(file.getAbsolutePath() + ".csv"), csv, offsetUs / 1000L);
                        offsetUs = nextOffset;
                    } finally {
                        extractor.release();
                    }
                    completedBytes += Math.max(1L, file.length());
                    callback.onProgress(n + 1, ordered.size(),
                            (int) Math.min(99L, completedBytes * 100L / allBytes));
                }
            }
            if (!muxerStarted || offsetUs <= 0) {
                throw new IllegalStateException("这些分卷没有可合并的声音");
            }
            muxer.stop();
            muxerStarted = false;
            muxer.release();
            muxer = null;
            if (!partial.renameTo(target)) {
                throw new IllegalStateException("无法完成合并文件保存");
            }
            successful = true;
            callback.onProgress(ordered.size(), ordered.size(), 100);
            return target;
        } finally {
            if (muxer != null) {
                try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) { }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (!successful) {
                partial.delete();
                combinedCsv.delete();
            }
        }
    }

    private static void appendCsv(File source, BufferedWriter output, long offsetMs) throws Exception {
        if (!source.isFile()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(source))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] cols = line.split(",", 4);
                if (cols.length != 4) continue;
                long start = Long.parseLong(cols[0]);
                long end = Long.parseLong(cols[1]);
                output.write((offsetMs + start) + "," + (offsetMs + end) + ","
                        + cols[2] + "," + cols[3] + "\n");
            }
        }
    }
}
