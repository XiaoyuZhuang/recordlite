package com.xiaoyuzhuang.recordlite;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
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
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
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

    private boolean isTouchFeedbackEnabled() {
        return getSharedPreferences("interaction", MODE_PRIVATE)
                .getBoolean("touch_feedback", true);
    }

    /**
     * View haptics follow the system-wide touch feedback switch automatically.
     * Do not use flags that override the user's Android haptic preferences.
     */
    private void feedback(View view) {
        if (isTouchFeedbackEnabled() && view.isEnabled()) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    private final class HapticTextView extends TextView {
        HapticTextView(Context context) { super(context); }

        @Override public boolean performClick() {
            if (isEnabled() && hasOnClickListeners()) feedback(this);
            return super.performClick();
        }
    }

    private final class HapticCheckBox extends CheckBox {
        HapticCheckBox(Context context) { super(context); }

        @Override public boolean performClick() {
            if (isEnabled()) feedback(this);
            return super.performClick();
        }
    }

    private final class HapticSwitch extends Switch {
        HapticSwitch(Context context) { super(context); }

        @Override public boolean performClick() {
            if (isEnabled()) feedback(this);
            return super.performClick();
        }
    }

    private TextView text(String message, int sp, int color, boolean bold) {
        TextView t = new HapticTextView(this);
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
        startButton.setOnClickListener(v -> requestStart());
        stopButton.setOnClickListener(v -> {
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
        CheckBox enabled = new HapticCheckBox(this);
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

        section("触觉反馈");
        LinearLayout feedbackPanel = panel();
        HapticSwitch feedbackSwitch = new HapticSwitch(this);
        feedbackSwitch.setText("点击时震动");
        feedbackSwitch.setTextColor(foreground);
        feedbackSwitch.setTextSize(14);
        feedbackSwitch.setShowText(false);
        feedbackSwitch.setThumbTintList(ColorStateList.valueOf(accent));
        feedbackSwitch.setChecked(isTouchFeedbackEnabled());
        feedbackSwitch.setOnCheckedChangeListener((view, checked) ->
                getSharedPreferences("interaction", MODE_PRIVATE).edit()
                        .putBoolean("touch_feedback", checked).apply());
        feedbackPanel.addView(feedbackSwitch);
        feedbackPanel.addView(text("默认开启，并遵循手机系统的触感设置。" +
                "关闭后主页按钮、底部标签页和录音管理操作均不再提供点击震动。",
                12, muted, false));
        addSpace(body, feedbackPanel, 2, 5);

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

        section("关于与更新");
        String currentVersion;
        try {
            currentVersion = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            currentVersion = "未知";
        }
        addSpace(body, text("当前版本：v" + currentVersion, 13, muted, false), 0, 8);
        TextView update = action("检查更新", false);
        addSpace(body, update, 0, 8);
        update.setOnClickListener(v -> checkForUpdate(update));
        addSpace(body, text("仅在点击时查询 GitHub 最新 Release。" +
                "有新版本会询问是否跳转至发布页，由你自行下载和安装。" +
                "应用不会自动下载安装包。", 12, muted, false), 0, 4);
    }

    private void checkForUpdate(TextView button) {
        button.setEnabled(false);
        button.setText("正在检查 GitHub Release…");
        new Thread(() -> {
            try {
                ReleaseChecker.Result result = ReleaseChecker.check(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    button.setEnabled(true);
                    button.setText("检查更新");
                    if (result.hasUpdate) {
                        new AlertDialog.Builder(this)
                                .setTitle("发现新版本 " + result.latest)
                                .setMessage("当前版本：v" + result.installed +
                                        "\n最新版本：" + result.latest +
                                        "\n\n是否打开 GitHub Release 页面自行下载？")
                                .setNegativeButton("暂不", null)
                                .setPositiveButton("前往发布页", (dialog, which) -> {
                                    Intent intent = new Intent(Intent.ACTION_VIEW,
                                            Uri.parse(result.releasePage));
                                    try {
                                        startActivity(intent);
                                    } catch (Exception e) {
                                        Toast.makeText(this, "无法打开浏览器，请稍后再试",
                                                Toast.LENGTH_LONG).show();
                                    }
                                }).show();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("已经是最新版本")
                                .setMessage("当前版本：v" + result.installed +
                                        "\nGitHub 最新版本：" + result.latest)
                                .setPositiveButton("好的", null).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    button.setEnabled(true);
                    button.setText("检查更新");
                    new AlertDialog.Builder(this)
                            .setTitle("检查更新失败")
                            .setMessage("无法获取 GitHub Release 信息。请确认网络可访问 GitHub。" +
                                    "\n\n" + error.getMessage())
                            .setPositiveButton("好的", null).show();
                });
            }
        }, "RecordLite-ReleaseCheck").start();
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
        // Sort by actual file modification time, not lexicographical names.
        // Merged files use a different filename prefix, so name sorting put
        // older recordings ahead of the latest recordings.
        if (files != null) Arrays.sort(files,
                Comparator.comparingLong(File::lastModified).reversed()
                        .thenComparing(Comparator.comparing(File::getName).reversed()));
        return files;
    }

    private void renderRecordings() {
        title("已保存的录音", "勾选多个文件，可合并、导出或批量删除");
        renderPlaybackControls();
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
        deleteSelectedButton = action("删除已选录音", false);
        deleteSelectedButton.setTextColor(dark ? 0xFFFFABAE : 0xFFAF3840);
        addSpace(body, deleteSelectedButton, 0, 8);
        deleteSelectedButton.setOnClickListener(v -> deleteSelected());
        mergeProgress = text("", 13, accent, true);
        mergeProgress.setVisibility(merging ? View.VISIBLE : View.GONE);
        body.addView(mergeProgress);
        mergeBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mergeBar.setMax(100);
        mergeBar.setProgressTintList(ColorStateList.valueOf(accent));
        mergeBar.setProgressBackgroundTintList(ColorStateList.valueOf(secondary));
        mergeBar.setVisibility(merging ? View.VISIBLE : View.GONE);
        addSpace(body, mergeBar, 4, 8);

        if (files == null || files.length == 0) {
            addSpace(body, text("暂无录音。\n在主页结束录音后，完整文件会显示在这里。",
                    14, muted, false), 30, 15);
            updateSelection();
            return;
        }

        // Compact full-width cards with four evenly spaced actions in one row.
        // TextView buttons have no platform minimum button width.
        for (File f : files) {
            LinearLayout card = panel();
            card.setPadding(dp(12), dp(9), dp(12), dp(10));
            CheckBox choose = new HapticCheckBox(this);
            choose.setButtonTintList(ColorStateList.valueOf(accent));
            choose.setTextColor(foreground);
            choose.setTextSize(13);
            choose.setMinHeight(dp(48));
            choose.setPadding(dp(2), dp(2), dp(2), dp(3));
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
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            TextView[] items = {play, share, save, more};
            for (int k = 0; k < items.length; k++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, dp(44), 1f);
                if (k > 0) lp.setMargins(dp(5), 0, 0, 0);
                items[k].setTextSize(12);
                items[k].setMinHeight(0);
                items[k].setPadding(dp(1), 0, dp(1), 0);
                actions.addView(items[k], lp);
            }
            addSpace(card, actions, 3, 0);
            play.setOnClickListener(v -> playFile(f));
            share.setOnClickListener(v -> shareFile(f));
            save.setOnClickListener(v -> saveFile(f));
            more.setOnClickListener(v -> showFileOptions(f));
            addSpace(body, card, 5, 2);
        }
        updateSelection();
    }

    private void renderPlaybackControls() {
        playbackCard = panel();
        playbackTitle = text("当前试听", 13, foreground, true);
        playbackTitle.setMaxLines(2);
        playbackTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        playbackCard.addView(playbackTitle);
        playbackTimes = text("00:00 / 00:00", 12, muted, false);
        addSpace(playbackCard, playbackTimes, 5, 1);
        playbackSeek = new SeekBar(this);
        playbackSeek.setMax(1000);
        playbackSeek.setProgressTintList(ColorStateList.valueOf(accent));
        playbackSeek.setThumbTintList(ColorStateList.valueOf(accent));
        playbackSeek.setProgressBackgroundTintList(ColorStateList.valueOf(secondary));
        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                seekingPlayback = true;
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && player != null && playbackTimes != null) {
                    try {
                        playbackTimes.setText(elapsed(value) + " / " +
                                elapsed(Math.max(0, player.getDuration())));
                    } catch (IllegalStateException ignored) { }
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (player != null) {
                    try { player.seekTo(bar.getProgress()); }
                    catch (IllegalStateException ignored) { }
                }
                seekingPlayback = false;
                updatePlaybackUi();
            }
        });
        playbackCard.addView(playbackSeek);
        playbackToggle = action("Ⅱ  暂停", false);
        TextView stop = action("■  停止", false);
        addSpace(playbackCard, paired(playbackToggle, stop, 10), 4, 2);
        playbackToggle.setOnClickListener(v -> togglePlayback());
        stop.setOnClickListener(v -> stopPlayback());
        playbackCard.setVisibility(player == null ? View.GONE : View.VISIBLE);
        addSpace(body, playbackCard, 0, 12);
    }

    private void updatePlaybackUi() {
        if (playbackCard == null) return;
        boolean available = player != null && playingFile != null;
        playbackCard.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available) return;
        try {
            int duration = Math.max(1, player.getDuration());
            int position = Math.max(0, Math.min(duration, player.getCurrentPosition()));
            playbackTitle.setText("正在试听 · " + playingFile.getName());
            playbackTimes.setText(elapsed(position) + " / " + elapsed(duration));
            if (!seekingPlayback) {
                playbackSeek.setMax(duration);
                playbackSeek.setProgress(position);
            }
            playbackToggle.setText(player.isPlaying() ? "Ⅱ  暂停" : "▶  继续");
        } catch (IllegalStateException ignored) {
            stopPlayback();
        }
    }

    private void togglePlayback() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.pause();
            else player.start();
            updatePlaybackUi();
        } catch (IllegalStateException e) {
            stopPlayback();
        }
    }

    private void stopPlayback() {
        MediaPlayer old = player;
        player = null;
        playingFile = null;
        seekingPlayback = false;
        if (old != null) {
            try { old.release(); } catch (Exception ignored) { }
        }
        updatePlaybackUi();
    }

    private void deleteSelected() {
        if (merging || selectedPaths.isEmpty()) return;
        ArrayList<File> chosen = new ArrayList<>();
        long totalBytes = 0;
        for (String path : selectedPaths) {
            File f = new File(path);
            if (f.isFile() && (f.getName().endsWith(".ogg") ||
                    f.getName().endsWith(".m4a"))) {
                chosen.add(f);
                totalBytes += f.length();
            }
        }
        if (chosen.isEmpty()) {
            selectedPaths.clear();
            renderPage();
            return;
        }
        final int count = chosen.size();
        final long bytes = totalBytes;
        new AlertDialog.Builder(this)
                .setTitle("删除选中的 " + count + " 个录音？")
                .setMessage("共 " + size(bytes) + "。对应的 CSV 时间表也会一并删除，且不可恢复。" +
                        "\n\n如果刚合并完，请先试听并确认合并结果能正常播放，再清理原分卷。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", (dialog, which) -> {
                    int removed = 0, failed = 0;
                    for (File f : chosen) {
                        if (playingFile != null && playingFile.equals(f)) stopPlayback();
                        if (f.delete()) {
                            removed++;
                            new File(f.getAbsolutePath() + ".csv").delete();
                            selectedPaths.remove(f.getAbsolutePath());
                        } else failed++;
                    }
                    Toast.makeText(this, "已删除 " + removed + " 个录音" +
                            (failed > 0 ? "，失败 " + failed + " 个" : ""),
                            Toast.LENGTH_LONG).show();
                    renderPage();
                }).show();
    }

    private void updateSelection() {
        if (selectionLabel == null) return;
        selectionLabel.setText("已选 " + selectedPaths.size() + " 个");
        boolean enabled = !selectedPaths.isEmpty() && !merging;
        mergeShareButton.setEnabled(enabled);
        mergeSaveButton.setEnabled(enabled);
        deleteSelectedButton.setEnabled(enabled);
        mergeShareButton.setAlpha(enabled ? 1f : .42f);
        mergeSaveButton.setAlpha(enabled ? 1f : .42f);
        deleteSelectedButton.setAlpha(enabled ? 1f : .42f);
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
                                            if (file.equals(playingFile)) stopPlayback();
                                            selectedPaths.remove(file.getAbsolutePath());
                                            if (file.delete()) {
                                                csv.delete();
                                            } else {
                                                Toast.makeText(this, "删除失败，请重试",
                                                        Toast.LENGTH_SHORT).show();
                                            }
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
        if (mergeProgress != null) {
            mergeProgress.setVisibility(View.VISIBLE);
            mergeProgress.setText("准备合并 " + chosen.size() + " 个分卷 · 0%");
        }
        if (mergeBar != null) {
            mergeBar.setVisibility(View.VISIBLE);
            mergeBar.setProgress(0);
        }
        new Thread(() -> {
            try {
                File merged = AudioMerger.merge(chosen, dir, (done, total, percent) ->
                        runOnUiThread(() -> {
                            if (mergeProgress != null) {
                                mergeProgress.setText("合并进度 " + percent + "% · 已处理 "
                                        + done + "/" + total + " 个分卷");
                            }
                            if (mergeBar != null) mergeBar.setProgress(percent);
                        }));
                runOnUiThread(() -> {
                    merging = false;
                    // Keep the original chunks selected so they can be deleted
                    // together after the user verifies the merged file.
                    if (page == RECORDINGS) renderPage();
                    Toast.makeText(this, "合并成功，原分卷仍被选中；确认无误后可批量删除",
                            Toast.LENGTH_LONG).show();
                    if (share) shareFile(merged);
                    else saveFile(merged);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    merging = false;
                    updateSelection();
                    if (mergeBar != null) mergeBar.setVisibility(View.GONE);
                    if (mergeProgress != null)
                        mergeProgress.setText("合并失败：" + e.getMessage());
                    Toast.makeText(this, "合并失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "RecordLite-Merge").start();
    }

    private void playFile(File file) {
        if (!file.isFile()) {
            Toast.makeText(this, "文件已不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        if (player != null && file.equals(playingFile)) {
            togglePlayback();
            return;
        }
        stopPlayback();
        MediaPlayer fresh = new MediaPlayer();
        try {
            fresh.setDataSource(file.getAbsolutePath());
            fresh.setOnCompletionListener(mp -> {
                if (mp != player) return;
                try { mp.seekTo(0); }
                catch (IllegalStateException ignored) { }
                updatePlaybackUi();
            });
            fresh.setOnErrorListener((mp, what, extra) -> {
                if (mp == player) stopPlayback();
                Toast.makeText(this, "播放发生错误，请检查音频文件",
                        Toast.LENGTH_LONG).show();
                return true;
            });
            fresh.prepare();
            player = fresh;
            playingFile = file;
            player.start();
            updatePlaybackUi();
            pageScroll.smoothScrollTo(0, 0);
        } catch (Exception e) {
            if (player == fresh) {
                stopPlayback();
            } else {
                try { fresh.release(); } catch (Exception ignored) { }
            }
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
        // Recording is independent of this activity. Only preview playback pauses.
        if (player != null) {
            try { if (player.isPlaying()) player.pause(); }
            catch (IllegalStateException ignored) { }
            updatePlaybackUi();
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }
}
