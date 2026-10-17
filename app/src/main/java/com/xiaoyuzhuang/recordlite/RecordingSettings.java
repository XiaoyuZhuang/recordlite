package com.xiaoyuzhuang.recordlite;

import android.content.Context;
import android.content.SharedPreferences;

final class RecordingSettings {
    String codec = "opus";
    int sampleRate = 16000;
    int bitrate = 12000;
    boolean skipSilence = true;
    int thresholdDb = -30;
    int tailMs = 1200;
    int chunkMinutes = 60;

    static RecordingSettings read(Context context) {
        SharedPreferences p = context.getSharedPreferences("recording", Context.MODE_PRIVATE);
        RecordingSettings s = new RecordingSettings();
        s.codec = p.getString("codec", s.codec);
        s.sampleRate = p.getInt("sampleRate", s.sampleRate);
        s.bitrate = p.getInt("bitrate", s.bitrate);
        s.skipSilence = p.getBoolean("skipSilence", s.skipSilence);
        s.thresholdDb = p.getInt("thresholdDb", s.thresholdDb);
        s.tailMs = p.getInt("tailMs", s.tailMs);
        s.chunkMinutes = p.getInt("chunkMinutes", s.chunkMinutes);
        return s;
    }

    void save(Context context) {
        context.getSharedPreferences("recording", Context.MODE_PRIVATE).edit()
                .putString("codec", codec).putInt("sampleRate", sampleRate)
                .putInt("bitrate", bitrate).putBoolean("skipSilence", skipSilence)
                .putInt("thresholdDb", thresholdDb).putInt("tailMs", tailMs)
                .putInt("chunkMinutes", chunkMinutes).apply();
    }
}
