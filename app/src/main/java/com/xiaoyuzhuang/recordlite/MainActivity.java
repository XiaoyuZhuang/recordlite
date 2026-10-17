package com.xiaoyuzhuang.recordlite;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_SAVE = 11;
    private static final int[] RATES = {8000, 16000, 24000, 48000};
    private static final int[] BITS = {4, 6, 8, 12, 16, 24, 32, 48, 64};
    private static final int[] TAILS = {500, 1000, 1200, 2000, 3000};
    private static final int[] CHUNKS = {15, 30, 60, 120};

    private boolean dark;
    private int bg, surface, foreground, muted, accent, secondary;
    private Choice codecChoice, rateChoice, bitChoice, tailChoice, chunkChoice;
    private SeekBar thresholdBar;
    private TextView thresholdLabel, statusLabel, elapsedLabel, bytesLabel, selectionLabel;
    private CheckBox silenceBox;
    private Button startButton, stopButton, mergeShareButton, mergeSaveButton;
    private LinearLayout recordingList;
    private File pendingExport;
    private MediaPlayer player;
    private boolean previousRunning, merging;
    private final Set<String> selectedPaths = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refreshState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        SharedPreferences themePrefs = getSharedPreferences("appearance", MODE_PRIVATE);
        String theme = themePrefs.getString("theme", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        dark = "dark".equals(theme) || ("system".equals(theme) && systemDark);
        setTheme(dark ? R.style.AppThemeDark : R.style.AppThemeLight);
        super.onCreate(state);

        bg = dark ? 0xFF0D1716 : 0xFFF6F9F8;
        surface = dark ? 0xFF1B302D : 0xFFFFFFFF;
        foreground = dark ? 0xFFEEF8F4 : 0xFF173F38;
        muted = dark ? 0xFFA9C2BC : 0xFF667C76;
        accent = dark ? 0xFF73DEC7 : 0xFF087D71;
        secondary = dark ? 0xFF28423D : 0xFFE0F0EB;

        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setStatusBarContrastEnforced(false);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat windowControls = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        windowControls.setAppearanceLightStatusBars(!dark);
        windowControls.setAppearanceLightNavigationBars(!dark);

        if (state != null && state.getString("pendingExport") != null) {
            pendingExport = new File(state.getString("pendingExport"));
        }

        RecordingSettings s = RecordingSettings.read(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(36), dp(20), dp(36));
        scroll.addView(root);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(dp(20), dp(20) + bars.top, dp(20),
                    dp(36) + bars.bottom);
            return insets;
        });
        setContentView(scroll);

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = text("RecordLite · 轻录", 25, foreground, true);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, dp(45), 1f));
        Button appearance = button("◐ 外观", false);
        titleBar.addView(appearance, new LinearLayout.LayoutParams(dp(88), dp(44)));
        root.addView(titleBar);
        appearance.setOnClickListener(v -> showAppearanceChooser());
        addWithSpace(root, text("全天低码率录音 · 静音跳过 · 本地保存",
                13, muted, false), 0, 14);

        LinearLayout statusCard = panel();
        statusLabel = text("尚未开始录音", 14, accent, true);
        statusCard.addView(statusLabel);
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        elapsedLabel = text("已录制  00:00:00", 14, foreground, true);
        bytesLabel = text("当前文件  0 B", 13, muted, false);
        metrics.addView(elapsedLabel, new LinearLayout.LayoutParams(0, dp(42), 1.3f));
        metrics.addView(bytesLabel, new LinearLayout.LayoutParams(0, dp(42), 1f));
        statusCard.addView(metrics);
        root.addView(statusCard);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("● 开始录音", true);
        stopButton = button("■ 停止录音", false);
        controls.addView(startButton, new LinearLayout.LayoutParams(0, dp(54), 1f));
        controls.addView(stopButton, new LinearLayout.LayoutParams(0, dp(54), 1f));
        addWithSpace(root, controls, 12, 4);
        startButton.setOnClickListener(v -> {
            vibrate();
            requestStart();
        });
        stopButton.setOnClickListener(v -> {
            vibrate();
            startService(new Intent(this, RecordingService.class)
                    .setAction(RecordingService.ACTION_STOP));
            statusLabel.setText("正在停止并完成文件写入…");
        });

        addWithSpace(root, text("录音质量", 19, foreground, true), 22, 8);
        codecChoice = choice(root, "编码方式",
                new String[]{"Opus / Ogg（推荐）", "AAC / M4A（兼容）"},
                "opus".equals(s.codec) ? 0 : 1);
        rateChoice = choice(root, "采样率",
                new String[]{"8000 Hz", "16000 Hz", "24000 Hz", "48000 Hz"},
                indexOf(RATES, s.sampleRate, 1));
        bitChoice = choice(root, "编码比特率",
                new String[]{"4 kbps", "6 kbps", "8 kbps", "12 kbps", "16 kbps",
                        "24 kbps", "32 kbps", "48 kbps", "64 kbps"},
                indexOf(BITS, s.bitrate / 1000, 3));

        addWithSpace(root, text("静音检测", 19, foreground, true), 20, 8);
        silenceBox = new CheckBox(this);
        silenceBox.setButtonTintList(ColorStateList.valueOf(accent));
        silenceBox.setTextColor(foreground);
        silenceBox.setText("跳过没有明显声音的时段");
        silenceBox.setChecked(s.skipSilence);
        root.addView(silenceBox);
        thresholdLabel = text("", 13, muted, false);
        root.addView(thresholdLabel);
        thresholdBar = new SeekBar(this);
        thresholdBar.setMax(45);
        thresholdBar.setProgress(Math.max(0, Math.min(45, s.thresholdDb + 60)));
        thresholdBar.setProgressTintList(ColorStateList.valueOf(accent));
        thresholdBar.setThumbTintList(ColorStateList.valueOf(accent));
        thresholdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                thresholdLabel.setText("声音阈值  " + (value - 60)
                        + " dBFS  ·  越低越容易保留轻声");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        thresholdLabel.setText("声音阈值  " + s.thresholdDb + " dBFS");
        root.addView(thresholdBar);
        tailChoice = choice(root, "静音拖尾（防止截断讲话）",
                new String[]{"0.5 秒", "1 秒", "1.2 秒", "2 秒", "3 秒"},
                indexOf(TAILS, s.tailMs, 2));

        addWithSpace(root, text("录音文件", 19, foreground, true), 22, 8);
        chunkChoice = choice(root, "自动分卷（每隔多久单独保存一个文件）",
                new String[]{"15 分钟", "30 分钟", "60 分钟", "120 分钟"},
                indexOf(CHUNKS, s.chunkMinutes, 2));
        root.addView(text("分卷只会拆成多个文件，不会删除声音。可以在下方多选分卷，"
                + "无损合并为一个大文件，再分享到微信或其他应用。",
                12, muted, false));
        addWithSpace(root, text("已保存的录音", 19, foreground, true), 22, 8);

        LinearLayout batchBar = new LinearLayout(this);
        batchBar.setOrientation(LinearLayout.HORIZONTAL);
        selectionLabel = text("已选 0 个", 13, muted, false);
        batchBar.addView(selectionLabel, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button selectAll = button("全选", false);
        Button clear = button("清空", false);
        batchBar.addView(selectAll, new LinearLayout.LayoutParams(dp(70), dp(42)));
        batchBar.addView(clear, new LinearLayout.LayoutParams(dp(70), dp(42)));
        root.addView(batchBar);
        selectAll.setOnClickListener(v -> {
            File[] files = listAudioFiles();
            if (files != null) for (File f : files) selectedPaths.add(f.getAbsolutePath());
            renderFiles();
        });
        clear.setOnClickListener(v -> {
            selectedPaths.clear();
            renderFiles();
        });
        LinearLayout batchActions = new LinearLayout(this);
        batchActions.setOrientation(LinearLayout.HORIZONTAL);
        mergeShareButton = button("合并并分享", true);
        mergeSaveButton = button("合并并保存", false);
        batchActions.addView(mergeShareButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        batchActions.addView(mergeSaveButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(batchActions);
        mergeShareButton.setOnClickListener(v -> mergeSelected(true));
        mergeSaveButton.setOnClickListener(v -> mergeSelected(false));

        recordingList = new LinearLayout(this);
        recordingList.setOrientation(LinearLayout.VERTICAL);
        root.addView(recordingList);

        addWithSpace(root, text("说明：默认 Opus 16 kHz / 12 kbps、-30 dBFS。录音时长包含静音；"
                + "文件大小只统计当前分卷。录音参数修改后，下一次开始录音才会生效。"
                + "多选合并只支持相同编码方式和采样率的录音。合并时不影响原有分卷。",
                12, muted, false), 16, 4);
        renderFiles();
        refreshState();
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        text.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (bold) text.setTypeface(null, Typeface.BOLD);
        text.setPadding(0, dp(3), 0, dp(3));
        return text;
    }

    private void addWithSpace(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(top), 0, dp(bottom));
        parent.addView(child, params);
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(14));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private LinearLayout panel() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(rounded(surface, dark ? 0xFF34534E : 0xFFDEEBE7));
        return card;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(primary ? (dark ? 0xFF0B4036 : Color.WHITE) : foreground);
        button.setBackground(rounded(primary ? accent : secondary, 0));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private Choice choice(LinearLayout parent, String label, String[] options, int index) {
        Choice c = new Choice(label, options, index);
        addWithSpace(parent, c.view, 7, 4);
        return c;
    }

    private final class Choice {
        final String label;
        final String[] values;
        final TextView view;
        int selected;
        Choice(String label, String[] values, int index) {
            this.label = label;
            this.values = values;
            selected = Math.max(0, Math.min(index, values.length - 1));
            view = text("", 14, foreground, false);
            view.setPadding(dp(14), dp(10), dp(12), dp(10));
            view.setBackground(rounded(surface, dark ? 0xFF35534E : 0xFFD9E8E3));
            update();
            view.setOnClickListener(v -> {
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(label)
                        .setSingleChoiceItems(values, selected, (d, which) -> {
                            selected = which;
                            update();
                            d.dismiss();
                        })
                        .setNegativeButton("取消", null)
                        .create();
                dialog.setOnShowListener(ignored -> {
                    if (dialog.getWindow() != null) {
                        dialog.getWindow().setBackgroundDrawable(
                                rounded(surface, 0));
                    }
                });
                dialog.show();
            });
        }
        void update() {
            view.setText(label + "\n" + values[selected] + "    ⌄");
        }
        int position() { return selected; }
    }

    private void showAppearanceChooser() {
        String[] names = {"跟随系统（默认）", "浅色模式", "深色模式"};
        String[] values = {"system", "light", "dark"};
        String current = getSharedPreferences("appearance", MODE_PRIVATE)
                .getString("theme", "system");
        int selected = Arrays.asList(values).indexOf(current);
        new AlertDialog.Builder(this).setTitle("外观模式")
                .setSingleChoiceItems(names, Math.max(selected, 0), (d, which) -> {
                    getSharedPreferences("appearance", MODE_PRIVATE)
                            .edit().putString("theme", values[which]).apply();
                    d.dismiss();
                    recreate();
                }).setNegativeButton("取消", null).show();
    }

    private static int indexOf(int[] values, int target, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return fallback;
    }

    private RecordingSettings readForm() {
        RecordingSettings s = new RecordingSettings();
        s.codec = codecChoice.position() == 0 ? "opus" : "aac";
        s.sampleRate = RATES[rateChoice.position()];
        s.bitrate = BITS[bitChoice.position()] * 1000;
        s.skipSilence = silenceBox.isChecked();
        s.thresholdDb = thresholdBar.getProgress() - 60;
        s.tailMs = TAILS[tailChoice.position()];
        s.chunkMinutes = CHUNKS[chunkChoice.position()];
        return s;
    }

    private void vibrate() {
        try {
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(45,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) { }
    }

    private void requestStart() {
        if (RecordingService.isRunning) return;
        readForm().save(this);
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else startRecording();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != REQUEST_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startRecording();
        else Toast.makeText(this, "请允许使用麦克风", Toast.LENGTH_LONG).show();
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

    private static String formatDuration(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d",
                sec / 3600, (sec / 60) % 60, sec % 60);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / 1048576.0);
    }

    private void refreshState() {
        boolean running = RecordingService.isRunning;
        statusLabel.setText(RecordingService.status);
        elapsedLabel.setText("已录制  " + (running ?
                formatDuration(SystemClock.elapsedRealtime() -
                        RecordingService.startedElapsedMs) : "00:00:00"));
        bytesLabel.setText("当前文件  " +
                formatSize(running ? RecordingService.activeFileBytes : 0));
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        if (running != previousRunning) {
            previousRunning = running;
            renderFiles();
        }
    }

    private File folder() {
        File home = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        return home == null ? null : new File(home, "RecordLite");
    }

    private File[] listAudioFiles() {
        File dir = folder();
        File[] all = dir == null ? null :
                dir.listFiles(f -> f.isFile() && (f.getName().endsWith(".ogg") ||
                        f.getName().endsWith(".m4a")));
        if (all != null) Arrays.sort(all, Comparator.comparing(File::getName).reversed());
        return all;
    }

    private void renderFiles() {
        if (recordingList == null) return;
        recordingList.removeAllViews();
        File[] files = listAudioFiles();
        if (files == null || files.length == 0) {
            recordingList.addView(text("尚无录音。停止录音后即可试听、导出或分享。",
                    13, muted, false));
            updateSelection();
            return;
        }
        for (int i = 0; i < Math.min(files.length, 100); i++) {
            File file = files[i];
            LinearLayout entry = panel();
            CheckBox choice = new CheckBox(this);
            choice.setButtonTintList(ColorStateList.valueOf(accent));
            choice.setTextColor(foreground);
            choice.setTextSize(12);
            choice.setText(file.getName() + "\n" + formatSize(file.length()));
            choice.setChecked(selectedPaths.contains(file.getAbsolutePath()));
            choice.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedPaths.add(file.getAbsolutePath());
                else selectedPaths.remove(file.getAbsolutePath());
                updateSelection();
            });
            entry.addView(choice);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button play = button("试听", false);
            Button share = button("分享", false);
            Button save = button("保存", false);
            Button more = button("更多", false);
            row.addView(play, new LinearLayout.LayoutParams(0, dp(42), 1f));
            row.addView(share, new LinearLayout.LayoutParams(0, dp(42), 1f));
            row.addView(save, new LinearLayout.LayoutParams(0, dp(42), 1f));
            row.addView(more, new LinearLayout.LayoutParams(0, dp(42), 1f));
            entry.addView(row);
            play.setOnClickListener(v -> playFile(file));
            share.setOnClickListener(v -> shareFile(file));
            save.setOnClickListener(v -> saveFile(file));
            more.setOnClickListener(v -> {
                String[] actions = {"导出原始时间表 CSV", "删除录音及时间表"};
                new AlertDialog.Builder(this).setTitle(file.getName())
                        .setItems(actions, (d, which) -> {
                            File csv = new File(file.getAbsolutePath() + ".csv");
                            if (which == 0) {
                                if (csv.exists()) saveFile(csv);
                                else Toast.makeText(this, "没有时间表文件",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                new AlertDialog.Builder(this).setTitle("永久删除这份录音？")
                                        .setMessage("会同时删除对应的时间表，不可恢复。")
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton("删除", (d2, w) -> {
                                            if (player != null) {
                                                player.release();
                                                player = null;
                                            }
                                            selectedPaths.remove(file.getAbsolutePath());
                                            file.delete();
                                            csv.delete();
                                            renderFiles();
                                        }).show();
                            }
                        }).show();
            });
            addWithSpace(recordingList, entry, 7, 5);
        }
        updateSelection();
    }

    private void updateSelection() {
        if (selectionLabel == null) return;
        selectionLabel.setText("已选 " + selectedPaths.size() + " 个");
        boolean enabled = !selectedPaths.isEmpty() && !merging;
        mergeShareButton.setEnabled(enabled);
        mergeSaveButton.setEnabled(enabled);
    }

    private void mergeSelected(boolean share) {
        if (merging || selectedPaths.isEmpty()) return;
        ArrayList<File> chosen = new ArrayList<>();
        for (String path : selectedPaths) {
            File f = new File(path);
            if (f.isFile()) chosen.add(f);
        }
        if (chosen.isEmpty()) {
            Toast.makeText(this, "所选文件已经不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(chosen, Comparator.comparing(File::getName));
        if (chosen.size() == 1) {
            if (share) shareFile(chosen.get(0));
            else saveFile(chosen.get(0));
            return;
        }

        File directory = folder();
        if (directory == null) return;
        merging = true;
        updateSelection();
        statusLabel.setText("正在无损合并 " + chosen.size() + " 个分卷…");
        new Thread(() -> {
            try {
                File merged = AudioMerger.merge(chosen, directory, (done, total) ->
                        runOnUiThread(() -> statusLabel.setText(
                                "正在合并 · " + done + " / " + total + " 个文件")));
                runOnUiThread(() -> {
                    merging = false;
                    selectedPaths.clear();
                    renderFiles();
                    Toast.makeText(this, "已生成 " + merged.getName(),
                            Toast.LENGTH_SHORT).show();
                    if (share) shareFile(merged);
                    else saveFile(merged);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    merging = false;
                    statusLabel.setText("合并失败：" + e.getMessage());
                    updateSelection();
                    Toast.makeText(this, "合并失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "RecordLite-Merge").start();
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
            Toast.makeText(this, "正在播放录音", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (player != null) { player.release(); player = null; }
            Toast.makeText(this, "无法播放：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".files", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            // Generic file transfer makes chat apps such as WeChat more likely to appear.
            send.setType("application/octet-stream");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newUri(getContentResolver(), file.getName(), uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享到微信或其他应用"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveFile(File file) {
        pendingExport = file;
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType(file.getName().endsWith(".csv") ? "text/csv" :
                file.getName().endsWith(".ogg") ? "audio/ogg" : "audio/mp4");
        save.putExtra(Intent.EXTRA_TITLE, file.getName());
        startActivityForResult(save, REQUEST_SAVE);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQUEST_SAVE) return;
        if (result != RESULT_OK || data == null || data.getData() == null ||
                pendingExport == null) {
            pendingExport = null;
            return;
        }
        File source = pendingExport;
        pendingExport = null;
        try (InputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException("无法写入目的地");
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count != 0) out.write(buffer, 0, count);
            }
            Toast.makeText(this, "已保存 " + source.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingExport != null) out.putString(
                "pendingExport", pendingExport.getAbsolutePath());
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
