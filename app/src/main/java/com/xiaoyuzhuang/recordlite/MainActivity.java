package com.xiaoyuzhuang.recordlite;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_EXPORT = 11;
    private static final int[] RATES = {8000, 16000, 24000, 48000};
    private static final int[] BITS = {4, 6, 8, 12, 16, 24, 32, 48, 64};
    private static final int[] TAILS = {500, 1000, 1200, 2000, 3000};
    private static final int[] CHUNKS = {15, 30, 60, 120};

    private Spinner codecSpinner, rateSpinner, bitSpinner, tailSpinner, chunkSpinner;
    private SeekBar thresholdBar;
    private TextView thresholdLabel, statusLabel;
    private CheckBox silenceBox;
    private Button startButton, stopButton;
    private LinearLayout recordingList;
    private File pendingExport;
    private MediaPlayer player;
    private boolean previousRunning;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refreshState();
            handler.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.getString("pendingExport") != null) {
            pendingExport = new File(state.getString("pendingExport"));
        }
        RecordingSettings s = RecordingSettings.read(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(246, 249, 248));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("RecordLite · 轻录", 27, Color.rgb(10, 70, 65), true);
        root.addView(title);
        TextView desc = text("全天低码率录音 · 仅保留有意义的声音", 13, Color.GRAY, false);
        addWithSpace(root, desc, 2, 22);

        statusLabel = text("尚未开始录音", 14, Color.rgb(26, 95, 81), true);
        addWithSpace(root, statusLabel, 2, 16);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("● 开始录音", true);
        stopButton = button("■ 停止", false);
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        buttons.addView(stopButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(buttons);
        startButton.setOnClickListener(v -> requestStart());
        stopButton.setOnClickListener(v -> {
            startService(new Intent(this, RecordingService.class)
                    .setAction(RecordingService.ACTION_STOP));
            statusLabel.setText("正在停止并完成文件写入…");
        });

        addWithSpace(root, text("录音质量", 19, Color.rgb(16, 45, 43), true), 26, 6);
        root.addView(text("编码方式", 13, Color.DKGRAY, false));
        codecSpinner = spinner(new String[]{"Opus / Ogg（优先推荐）", "AAC / M4A（兼容）"});
        codecSpinner.setSelection("opus".equals(s.codec) ? 0 : 1);
        root.addView(codecSpinner);

        root.addView(text("采样率（Hz）", 13, Color.DKGRAY, false));
        rateSpinner = spinner(new String[]{"8000 Hz", "16000 Hz", "24000 Hz", "48000 Hz"});
        rateSpinner.setSelection(indexOf(RATES, s.sampleRate, 1));
        root.addView(rateSpinner);

        root.addView(text("编码比特率（kbps）", 13, Color.DKGRAY, false));
        bitSpinner = spinner(new String[]{"4 kbps", "6 kbps", "8 kbps", "12 kbps",
                "16 kbps", "24 kbps", "32 kbps", "48 kbps", "64 kbps"});
        bitSpinner.setSelection(indexOf(BITS, s.bitrate / 1000, 1));
        root.addView(bitSpinner);

        addWithSpace(root, text("静音跳过", 19, Color.rgb(16, 45, 43), true), 22, 6);
        silenceBox = new CheckBox(this);
        silenceBox.setText("开启音量检测：静音不保存");
        silenceBox.setChecked(s.skipSilence);
        root.addView(silenceBox);

        thresholdLabel = text("", 13, Color.DKGRAY, false);
        root.addView(thresholdLabel);
        thresholdBar = new SeekBar(this);
        thresholdBar.setMax(45);
        thresholdBar.setProgress(s.thresholdDb + 60);
        thresholdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                thresholdLabel.setText("静音阈值：" + (progress - 60) +
                        " dBFS（越接近 -60 越容易保留轻声）");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        thresholdLabel.setText("静音阈值：" + s.thresholdDb + " dBFS");
        root.addView(thresholdBar);

        root.addView(text("静音拖尾（防止一句话被截断）", 13, Color.DKGRAY, false));
        tailSpinner = spinner(new String[]{"0.5 秒", "1 秒", "1.2 秒", "2 秒", "3 秒"});
        tailSpinner.setSelection(indexOf(TAILS, s.tailMs, 2));
        root.addView(tailSpinner);

        root.addView(text("文件分卷间隔", 13, Color.DKGRAY, false));
        chunkSpinner = spinner(new String[]{"15 分钟", "30 分钟", "60 分钟", "120 分钟"});
        chunkSpinner.setSelection(indexOf(CHUNKS, s.chunkMinutes, 2));
        root.addView(chunkSpinner);

        addWithSpace(root, text("使用提醒", 14, Color.rgb(45, 85, 80), true), 20, 2);
        root.addView(text("默认 Opus 16 kHz / 6 kbps。音质因手机而异，请先试听测试。" +
                " 如果轻声被跳过，请把阈值调到 -50 dBFS，或关闭静音跳过。" +
                " 修改设置后仅对下一次录音生效；保存的 CSV 是原始时间对照表。",
                12, Color.GRAY, false));

        addWithSpace(root, text("本机录音", 19, Color.rgb(16, 45, 43), true), 24, 4);
        recordingList = new LinearLayout(this);
        recordingList.setOrientation(LinearLayout.VERTICAL);
        root.addView(recordingList);
        renderFiles();
        refreshState();
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + .5f);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void addWithSpace(LinearLayout parent, View v, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(top), 0, dp(bottom));
        parent.addView(v, p);
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values);
        s.setAdapter(adapter);
        s.setMinimumHeight(dp(42));
        return s;
    }

    private Button button(String caption, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(caption);
        b.setTextSize(13);
        b.setTextColor(primary ? Color.WHITE : Color.rgb(13, 79, 74));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? Color.rgb(9, 125, 108) : Color.rgb(220, 238, 233));
        shape.setCornerRadius(dp(12));
        b.setBackground(shape);
        return b;
    }

    private static int indexOf(int[] values, int target, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return fallback;
    }

    private RecordingSettings readForm() {
        RecordingSettings s = new RecordingSettings();
        s.codec = codecSpinner.getSelectedItemPosition() == 0 ? "opus" : "aac";
        s.sampleRate = RATES[rateSpinner.getSelectedItemPosition()];
        s.bitrate = BITS[bitSpinner.getSelectedItemPosition()] * 1000;
        s.skipSilence = silenceBox.isChecked();
        s.thresholdDb = thresholdBar.getProgress() - 60;
        s.tailMs = TAILS[tailSpinner.getSelectedItemPosition()];
        s.chunkMinutes = CHUNKS[chunkSpinner.getSelectedItemPosition()];
        return s;
    }

    private void requestStart() {
        if (RecordingService.isRunning) return;
        readForm().save(this);
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startRecording();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != REQUEST_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startRecording();
        else Toast.makeText(this, "需要麦克风权限才能录音", Toast.LENGTH_LONG).show();
    }

    private void startRecording() {
        try {
            startForegroundService(new Intent(this, RecordingService.class)
                    .setAction(RecordingService.ACTION_START));
            statusLabel.setText("正在启动录音…");
        } catch (Exception e) {
            Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshState() {
        statusLabel.setText(RecordingService.status);
        boolean now = RecordingService.isRunning;
        startButton.setEnabled(!now);
        stopButton.setEnabled(now);
        if (now != previousRunning) {
            previousRunning = now;
            renderFiles();
        }
    }

    private File folder() {
        File root = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        return root == null ? null : new File(root, "RecordLite");
    }

    private void renderFiles() {
        recordingList.removeAllViews();
        File folder = folder();
        File[] files = folder == null ? null :
                folder.listFiles(f -> f.isFile() &&
                        (f.getName().endsWith(".ogg") || f.getName().endsWith(".m4a")));
        if (files == null || files.length == 0) {
            recordingList.addView(text("暂无录音。停止录音后，完整文件会显示在这里。",
                    13, Color.GRAY, false));
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        for (int i = 0; i < Math.min(files.length, 60); i++) {
            File file = files[i];
            LinearLayout entry = new LinearLayout(this);
            entry.setOrientation(LinearLayout.VERTICAL);
            entry.setPadding(dp(10), dp(8), dp(10), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(10));
            entry.setBackground(bg);
            String readableSize = String.format(Locale.US, "%.2f MB", file.length() / 1048576.0);
            entry.addView(text(file.getName() + "\n" + readableSize,
                    12, Color.rgb(28, 55, 51), false));
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button play = button("试听", false);
            Button export = button("导出音频", false);
            Button meta = button("导出时间表", false);
            Button delete = button("删除", false);
            actions.addView(play, new LinearLayout.LayoutParams(0, dp(42), 1f));
            actions.addView(export, new LinearLayout.LayoutParams(0, dp(42), 1.25f));
            actions.addView(meta, new LinearLayout.LayoutParams(0, dp(42), 1.4f));
            actions.addView(delete, new LinearLayout.LayoutParams(0, dp(42), 1f));
            entry.addView(actions);
            play.setOnClickListener(v -> playFile(file));
            export.setOnClickListener(v -> exportFile(file));
            File csv = new File(file.getAbsolutePath() + ".csv");
            meta.setEnabled(csv.exists());
            meta.setOnClickListener(v -> exportFile(csv));
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("删除这份录音？")
                    .setMessage("将同时删除该录音对应的时间表，且无法恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> {
                        if (player != null) { player.release(); player = null; }
                        file.delete();
                        csv.delete();
                        renderFiles();
                    }).show());
            addWithSpace(recordingList, entry, 7, 7);
        }
    }

    private void playFile(File file) {
        try {
            if (player != null) { player.release(); player = null; }
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(mp -> {
                mp.release();
                if (player == mp) player = null;
            });
            player.prepare();
            player.start();
            Toast.makeText(this, "正在试听：" + file.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (player != null) { player.release(); player = null; }
            Toast.makeText(this, "无法播放：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void exportFile(File file) {
        pendingExport = file;
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType(file.getName().endsWith(".csv") ? "text/csv" :
                file.getName().endsWith(".ogg") ? "audio/ogg" : "audio/mp4");
        save.putExtra(Intent.EXTRA_TITLE, file.getName());
        startActivityForResult(save, REQUEST_EXPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK ||
                data == null || data.getData() == null || pendingExport == null) return;
        Uri dest = data.getData();
        try (InputStream in = new FileInputStream(pendingExport);
             OutputStream out = getContentResolver().openOutputStream(dest)) {
            if (out == null) throw new IllegalStateException("无法写入目标文件");
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            Toast.makeText(this, "已导出：" + pendingExport.getName(),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        pendingExport = null;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingExport != null) out.putString("pendingExport", pendingExport.getAbsolutePath());
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        renderFiles();
        handler.post(refresher);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
