package com.xiaoyuzhuang.recordlite;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_SAVE = 11;

    private static final int HOME = 0;
    private static final int RECORDINGS = 1;
    private static final int SETTINGS = 2;
    private static final int[] RATES = {8000, 16000, 24000, 48000};
    private static final int[] BITS = {4, 6, 8, 12, 16, 24, 32, 48, 64};
    private static final int[] TAILS = {500, 1000, 1200, 2000, 3000};
    private static final int[] CHUNKS = {15, 30, 60, 120};

    private int page = HOME;
    private boolean dark;
    private int bg, surface, foreground, muted, accent, secondary, outline;
    private LinearLayout body;
    private LinearLayout bottomTabs;
    private ScrollView pageScroll;
    private TextView statusLabel, elapsedLabel, bytesLabel, startButton, stopButton;
    private TextView selectionLabel, mergeShareButton, mergeSaveButton, mergeProgress;
    private TextView deleteSelectedButton, playbackTitle, playbackTimes, playbackToggle;
    private SeekBar playbackSeek;
    private LinearLayout playbackCard;
    private ProgressBar mergeBar;
    private File playingFile;
    private boolean seekingPlayback;
    private boolean previousRunning, merging;
    private File pendingExport;
    private MediaPlayer player;
    private final Set<String> selectedPaths = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refreshState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences("appearance", MODE_PRIVATE);
        String appearance = prefs.getString("theme", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        dark = "dark".equals(appearance) || ("system".equals(appearance) && systemDark);
        setTheme(dark ? R.style.AppThemeDark : R.style.AppThemeLight);
        super.onCreate(savedInstanceState);

        page = savedInstanceState == null ? HOME : savedInstanceState.getInt("page", HOME);
        if (savedInstanceState != null && savedInstanceState.getString("pendingExport") != null) {
            pendingExport = new File(savedInstanceState.getString("pendingExport"));
        }

        bg = dark ? 0xFF0D1716 : 0xFFF6F9F8;
        surface = dark ? 0xFF1B302D : Color.WHITE;
        foreground = dark ? 0xFFEEF8F4 : 0xFF173F38;
        muted = dark ? 0xFFA9C2BC : 0xFF667C76;
        accent = dark ? 0xFF73DEC7 : 0xFF087D71;
        secondary = dark ? 0xFF28423D : 0xFFE0F0EB;
        outline = dark ? 0xFF38554F : 0xFFD9E8E3;

        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setStatusBarContrastEnforced(false);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(bg);
        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setBackgroundColor(bg);
        pageScroll.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(18), dp(16), dp(26));
        pageScroll.addView(body);
        outer.addView(pageScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        bottomTabs = new LinearLayout(this);
        bottomTabs.setOrientation(LinearLayout.HORIZONTAL);
        bottomTabs.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottomTabs.setBackgroundColor(surface);
        outer.addView(bottomTabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // The top and bottom inset are applied once to the outer layout, so
        // headings never sit behind the status bar and tabs avoid gesture navigation.
        ViewCompat.setOnApplyWindowInsetsListener(outer, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            outer.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        setContentView(outer);
        ViewCompat.requestApplyInsets(outer);
        renderPage();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + .5f);
    }

    private TextView text(String message, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(message);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(0, dp(3), 0, dp(3));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private GradientDrawable rounded(int color, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private TextView action(String label, boolean primary) {
        TextView t = text(label, 13, primary
                ? (dark ? 0xFF0B4036 : Color.WHITE) : foreground, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        t.setPadding(dp(9), dp(12), dp(9), dp(12));
        t.setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x447FFFFF : 0x33000000),
                rounded(primary ? accent : secondary, 0, 13), null));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private LinearLayout panel() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(15), dp(13), dp(15), dp(14));
        l.setBackground(rounded(surface, outline, 15));
        return l;
    }

    private void addSpace(LinearLayout parent, View child, int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(topDp), 0, dp(bottomDp));
        parent.addView(child, lp);
    }

    private LinearLayout paired(TextView left, TextView right, int gapDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        a.setMargins(0, 0, dp(gapDp / 2), 0);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setMargins(dp(gapDp - gapDp / 2), 0, 0, 0);
        row.addView(left, a);
        row.addView(right, b);
        return row;
    }

    private void title(String title, String subtitle) {
        addSpace(body, text(title, 24, foreground, true), 3, 2);
        if (subtitle != null) {
            addSpace(body, text(subtitle, 12, muted, false), 0, 18);
        }
    }

    private void renderPage() {
        body.removeAllViews();
        statusLabel = null;
        elapsedLabel = null;
        bytesLabel = null;
        startButton = null;
        stopButton = null;
        selectionLabel = null;
        mergeShareButton = null;
        mergeSaveButton = null;
        mergeProgress = null;
        mergeBar = null;
        deleteSelectedButton = null;
        playbackCard = null;
        playbackTitle = null;
        playbackTimes = null;
        playbackToggle = null;
        playbackSeek = null;

        if (page == RECORDINGS) renderRecordings();
        else if (page == SETTINGS) renderSettings();
        else renderHome();
        drawTabs();
        pageScroll.scrollTo(0, 0);
        refreshState();
    }

    private void navigate(int target) {
        if (page == target) {
            if (target == RECORDINGS) renderPage();
            return;
        }
        if (page == RECORDINGS && target != RECORDINGS) stopPlayback();
        page = target;
        renderPage();
    }

    private void drawTabs() {
        bottomTabs.removeAllViews();
        String[] labels = {"⌂\n主页", "▤\n已保存录音", "⚙\n设置"};
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            TextView tab = text(labels[i], 12, page == i ? accent : muted, page == i);
            tab.setGravity(Gravity.CENTER);
            tab.setMinHeight(dp(54));
            tab.setPadding(dp(2), dp(5), dp(2), dp(5));
            if (page == i) tab.setBackground(rounded(secondary, 0, 12));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setContentDescription(new String[]{"主页", "已保存的录音", "设置"}[i]);
            tab.setOnClickListener(v -> navigate(target));
            bottomTabs.addView(tab, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void renderHome() {
        title("RecordLite · 轻录", "轻量全天录音，不错过需要记录的声音");
        LinearLayout metrics = panel();
        statusLabel = text("尚未开始录音", 15, accent, true);
        metrics.addView(statusLabel);
        elapsedLabel = text("录音时长  00:00:00", 20, foreground, true);
        addSpace(metrics, elapsedLabel, 15, 2);
        bytesLabel = text("当前分卷大小  0 B", 14, muted, false);
        metrics.addView(bytesLabel);
        addSpace(body, metrics, 0, 16);

        startButton = action("●  开始录音", true);
        stopButton = action("■  停止录音", false);
        addSpace(body, startButton, 0, 10);
        addSpace(body, stopButton, 0, 8);
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

        RecordingSettings s = RecordingSettings.read(this);
        String encoder = "opus".equals(s.codec) ? "Opus" : "AAC";
        LinearLayout hint = panel();
        hint.addView(text("当前录音配置", 14, foreground, true));
        hint.addView(text(encoder + "  ·  " + s.sampleRate / 1000 + " kHz  ·  " +
                s.bitrate / 1000 + " kbps", 13, muted, false));
        hint.addView(text(s.skipSilence ?
                "静音跳过开启  ·  阈值 " + s.thresholdDb + " dBFS" :
                "静音跳过已关闭", 13, muted, false));
        TextView settingsLink = text("调整录音参数  ›", 13, accent, true);
        settingsLink.setPadding(0, dp(10), 0, dp(6));
        settingsLink.setOnClickListener(v -> navigate(SETTINGS));
        hint.addView(settingsLink);
        addSpace(body, hint, 15, 6);

        body.addView(text("录音时长包含静音；当前大小只显示正在写入的分卷。"
                + "自动分卷与静音过滤不会改变原本的时间顺序。", 12, muted, false));
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
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) requestPermissions(
                missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        else startRecording();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != REQUEST_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startRecording();
        else Toast.makeText(this, "需要麦克风权限才能录音", Toast.LENGTH_LONG).show();
    }

    private void startRecording() {
        try {
            startForegroundService(new Intent(this, RecordingService.class)
                    .setAction(RecordingService.ACTION_START));
            if (statusLabel != null) statusLabel.setText("正在启动录音…");
        } catch (Exception e) {
            Toast.makeText(this, "录音启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String elapsed(long ms) {
        long sec = Math.max(0, ms / 1000L);
        return String.format(Locale.US, "%02d:%02d:%02d",
                sec / 3600, sec / 60 % 60, sec % 60);
    }

    private String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(
                Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / 1048576.0);
    }

    private void refreshState() {
        boolean running = RecordingService.isRunning;
        if (page == HOME && statusLabel != null) {
            statusLabel.setText(RecordingService.status);
            elapsedLabel.setText("录音时长  " + (running
                    ? elapsed(SystemClock.elapsedRealtime() -
                            RecordingService.startedElapsedMs) : "00:00:00"));
            bytesLabel.setText("当前分卷大小  " +
                    size(running ? RecordingService.activeFileBytes : 0));
            startButton.setEnabled(!running);
            startButton.setAlpha(running ? .48f : 1f);
            stopButton.setEnabled(running);
            stopButton.setAlpha(running ? 1f : .48f);
        }
        if (previousRunning != running) {
            previousRunning = running;
            if (page == RECORDINGS && !merging) renderPage();
        }
        if (page == RECORDINGS) updatePlaybackUi();
    }

    private void renderSettings() {
        title("设置", "调整录音与外观；修改将在下次开始录音时生效");
        RecordingSettings s = RecordingSettings.read(this);

        section("录音质量");
        addChoice("编码方式",
                new String[]{"Opus / Ogg（推荐）", "AAC / M4A（兼容）"},
                "opus".equals(s.codec) ? 0 : 1, n -> {
                    RecordingSettings a = RecordingSettings.read(this);
                    a.codec = n == 0 ? "opus" : "aac";
                    a.save(this);
                });
        addChoice("采样率",
                new String[]{"8000 Hz", "16000 Hz", "24000 Hz", "48000 Hz"},
                indexOf(RATES, s.sampleRate, 1), n -> {
                    RecordingSettings a = RecordingSettings.read(this);
                    a.sampleRate = RATES[n];
                    a.save(this);
                });
        addChoice("编码比特率",
                new String[]{"4 kbps", "6 kbps", "8 kbps", "12 kbps", "16 kbps",
                        "24 kbps", "32 kbps", "48 kbps", "64 kbps"},
                indexOf(BITS, s.bitrate / 1000, 3), n -> {
                    RecordingSettings a = RecordingSettings.read(this);
                    a.bitrate = BITS[n] * 1000;
                    a.save(this);
                });

        section("静音检测");
        LinearLayout silencePanel = panel();
        CheckBox enabled = new CheckBox(this);
        enabled.setText("跳过没有明显声音的时段");
        enabled.setTextColor(foreground);
        enabled.setButtonTintList(ColorStateList.valueOf(accent));
        enabled.setChecked(s.skipSilence);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            RecordingSettings a = RecordingSettings.read(this);
            a.skipSilence = checked;
            a.save(this);
        });
        silencePanel.addView(enabled);
        final TextView dbLabel = text("", 13, muted, false);
        silencePanel.addView(dbLabel);
        SeekBar threshold = new SeekBar(this);
        threshold.setMax(45);
        threshold.setProgress(Math.max(0, Math.min(45, s.thresholdDb + 60)));
        threshold.setProgressTintList(ColorStateList.valueOf(accent));
        threshold.setThumbTintList(ColorStateList.valueOf(accent));
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                dbLabel.setText("静音阈值  " + (value - 60)
                        + " dBFS（数值越低越容易保留轻声）");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                RecordingSettings a = RecordingSettings.read(MainActivity.this);
                a.thresholdDb = bar.getProgress() - 60;
                a.save(MainActivity.this);
            }
        });
        dbLabel.setText("静音阈值  " + s.thresholdDb + " dBFS");
        silencePanel.addView(threshold);
        addSpace(body, silencePanel, 0, 5);
        addChoice("静音拖尾（防止截断讲话）",
                new String[]{"0.5 秒", "1 秒", "1.2 秒", "2 秒", "3 秒"},
                indexOf(TAILS, s.tailMs, 2), n -> {
                    RecordingSettings a = RecordingSettings.read(this);
                    a.tailMs = TAILS[n];
                    a.save(this);
                });

        section("录音文件");
        addChoice("自动分卷",
                new String[]{"15 分钟", "30 分钟", "60 分钟", "120 分钟"},
                indexOf(CHUNKS, s.chunkMinutes, 2), n -> {
                    RecordingSettings a = RecordingSettings.read(this);
                    a.chunkMinutes = CHUNKS[n];
                    a.save(this);
                });
        addSpace(body, text("分卷是定时将录音分别保存为多个文件，不会删除声音。"
                + "在“已保存录音”中可以多选分卷，无损合并并分享。", 12, muted, false),
                2, 5);

        section("外观");
        String[] modes = {"跟随系统（默认）", "浅色模式", "深色模式"};
        String current = getSharedPreferences("appearance", MODE_PRIVATE)
                .getString("theme", "system");
        int selected = "dark".equals(current) ? 2 : "light".equals(current) ? 1 : 0;
        addChoice("界面模式", modes, selected, n -> {
            String[] values = {"system", "light", "dark"};
            getSharedPreferences("appearance", MODE_PRIVATE).edit()
                    .putString("theme", values[n]).apply();
            page = SETTINGS;
            recreate();
        });
        addSpace(body, text("默认跟随手机系统，状态栏文字与背景颜色同步适配。",
                12, muted, false), 2, 4);
    }

    private void section(String value) {
        addSpace(body, text(value, 18, foreground, true), 24, 9);
    }

    private static int indexOf(int[] items, int selected, int fallback) {
        for (int i = 0; i < items.length; i++) if (items[i] == selected) return i;
        return fallback;
    }

    private void addChoice(String name, String[] choices, int initial, IntConsumer onChange) {
        final int[] chosen = {Math.max(0, Math.min(initial, choices.length - 1))};
        TextView view = text("", 14, foreground, false);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setMinHeight(dp(62));
        view.setBackground(rounded(surface, outline, 13));
        Runnable refresh = () -> view.setText(name + "\n" + choices[chosen[0]] + "   ▾");
        refresh.run();
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setSingleChoiceItems(choices, chosen[0], (d, index) -> {
                        chosen[0] = index;
                        refresh.run();
                        onChange.accept(index);
                        d.dismiss();
                    })
                    .setNegativeButton("取消", null).create();
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(rounded(surface, 0, 18));
            }
        });
        addSpace(body, view, 5, 3);
    }

    private File folder() {
        File home = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        return home == null ? null : new File(home, "RecordLite");
    }

    private File[] audioFiles() {
        File home = folder();
        File[] files = home == null ? null :
                home.listFiles(f -> f.isFile() &&
                        (f.getName().endsWith(".ogg") || f.getName().endsWith(".m4a")));
        if (files != null) Arrays.sort(files,
                Comparator.comparing(File::getName).reversed());
        return files;
    }

    private void renderRecordings() {
        title("已保存的录音", "勾选多个文件，可合并后分享或保存");
        File[] files = audioFiles();

        LinearLayout selection = new LinearLayout(this);
        selection.setOrientation(LinearLayout.HORIZONTAL);
        selectionLabel = text("", 13, muted, false);
        selection.addView(selectionLabel,
                new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView all = action("全选", false);
        TextView clear = action("清空", false);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                dp(68), LinearLayout.LayoutParams.WRAP_CONTENT);
        actionLp.setMargins(dp(4), 0, 0, 0);
        selection.addView(all, actionLp);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                dp(68), LinearLayout.LayoutParams.WRAP_CONTENT);
        clearLp.setMargins(dp(6), 0, 0, 0);
        selection.addView(clear, clearLp);
        body.addView(selection);
        all.setOnClickListener(v -> {
            File[] current = audioFiles();
            if (current != null) for (File f : current) {
                selectedPaths.add(f.getAbsolutePath());
            }
            renderPage();
        });
        clear.setOnClickListener(v -> {
            selectedPaths.clear();
            renderPage();
        });

        mergeShareButton = action("合并并分享", true);
        mergeSaveButton = action("合并并保存", false);
        addSpace(body, paired(mergeShareButton, mergeSaveButton, 10), 8, 8);
        mergeShareButton.setOnClickListener(v -> mergeSelected(true));
        mergeSaveButton.setOnClickListener(v -> mergeSelected(false));
        mergeProgress = text("", 12, accent, false);
        body.addView(mergeProgress);

        if (files == null || files.length == 0) {
            addSpace(body, text("暂无录音。\n在主页结束录音后，完整文件会显示在这里。",
                    14, muted, false), 30, 15);
            updateSelection();
            return;
        }

        // Entries are full-width cards. Short actions sit in two separate rows;
        // no native Button min-width/insets can cause controls to overlap.
        for (File f : files) {
            LinearLayout card = panel();
            CheckBox choose = new CheckBox(this);
            choose.setButtonTintList(ColorStateList.valueOf(accent));
            choose.setTextColor(foreground);
            choose.setTextSize(13);
            choose.setMinHeight(dp(62));
            choose.setPadding(dp(3), dp(3), dp(3), dp(8));
            choose.setText(f.getName() + "\n" + size(f.length()));
            choose.setChecked(selectedPaths.contains(f.getAbsolutePath()));
            choose.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedPaths.add(f.getAbsolutePath());
                else selectedPaths.remove(f.getAbsolutePath());
                updateSelection();
            });
            card.addView(choose);

            TextView play = action("试听", false);
            TextView share = action("分享", false);
            TextView save = action("保存", false);
            TextView more = action("更多", false);
            addSpace(card, paired(play, share, 10), 3, 8);
            card.addView(paired(save, more, 10));
            play.setOnClickListener(v -> playFile(f));
            share.setOnClickListener(v -> shareFile(f));
            save.setOnClickListener(v -> saveFile(f));
            more.setOnClickListener(v -> showFileOptions(f));
            addSpace(body, card, 8, 4);
        }
        updateSelection();
    }

    private void updateSelection() {
        if (selectionLabel == null) return;
        selectionLabel.setText("已选 " + selectedPaths.size() + " 个");
        boolean enabled = !selectedPaths.isEmpty() && !merging;
        mergeShareButton.setEnabled(enabled);
        mergeSaveButton.setEnabled(enabled);
        mergeShareButton.setAlpha(enabled ? 1f : .42f);
        mergeSaveButton.setAlpha(enabled ? 1f : .42f);
    }

    private void showFileOptions(File file) {
        new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(new String[]{"导出时间对照表 CSV", "删除录音和时间表"},
                        (dialog, which) -> {
                            File csv = new File(file.getAbsolutePath() + ".csv");
                            if (which == 0) {
                                if (csv.isFile()) saveFile(csv);
                                else Toast.makeText(this, "此录音没有时间表",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                new AlertDialog.Builder(this).setTitle("删除这份录音？")
                                        .setMessage("录音与时间表会一起删除，且无法恢复。")
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton("删除", (d, w) -> {
                                            if (player != null) {
                                                player.release();
                                                player = null;
                                            }
                                            selectedPaths.remove(file.getAbsolutePath());
                                            file.delete();
                                            csv.delete();
                                            renderPage();
                                        }).show();
                            }
                        }).show();
    }

    private void mergeSelected(boolean share) {
        if (merging || selectedPaths.isEmpty()) return;
        ArrayList<File> chosen = new ArrayList<>();
        for (String path : selectedPaths) {
            File f = new File(path);
            if (f.isFile()) chosen.add(f);
        }
        if (chosen.isEmpty()) {
            Toast.makeText(this, "所选文件已不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(chosen, Comparator.comparing(File::getName));
        if (chosen.size() == 1) {
            if (share) shareFile(chosen.get(0));
            else saveFile(chosen.get(0));
            return;
        }
        File dir = folder();
        if (dir == null) return;
        merging = true;
        updateSelection();
        if (mergeProgress != null)
            mergeProgress.setText("正在合并 " + chosen.size() + " 个分卷…");
        new Thread(() -> {
            try {
                File merged = AudioMerger.merge(chosen, dir, (done, total) ->
                        runOnUiThread(() -> {
                            if (mergeProgress != null)
                                mergeProgress.setText("正在合并 " + done + " / " + total);
                        }));
                runOnUiThread(() -> {
                    merging = false;
                    selectedPaths.clear();
                    if (page == RECORDINGS) renderPage();
                    Toast.makeText(this, "已生成 " + merged.getName(),
                            Toast.LENGTH_SHORT).show();
                    if (share) shareFile(merged);
                    else saveFile(merged);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    merging = false;
                    updateSelection();
                    if (mergeProgress != null)
                        mergeProgress.setText("合并失败：" + e.getMessage());
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
            Toast.makeText(this, "正在试听", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (player != null) { player.release(); player = null; }
            Toast.makeText(this, "播放失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".files", file);
            Intent send = new Intent(Intent.ACTION_SEND);
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
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(file.getName().endsWith(".csv") ? "text/csv" :
                file.getName().endsWith(".ogg") ? "audio/ogg" : "audio/mp4");
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        startActivityForResult(intent, REQUEST_SAVE);
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
            if (out == null) throw new IllegalStateException("目标位置不可写入");
            byte[] buffer = new byte[16384];
            int len;
            while ((len = in.read(buffer)) >= 0) {
                if (len > 0) out.write(buffer, 0, len);
            }
            Toast.makeText(this, "已保存 " + source.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
        if (pendingExport != null) out.putString(
                "pendingExport", pendingExport.getAbsolutePath());
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        if (page == RECORDINGS && !merging) renderPage();
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
