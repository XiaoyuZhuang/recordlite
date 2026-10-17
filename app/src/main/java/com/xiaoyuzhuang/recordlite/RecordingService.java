package com.xiaoyuzhuang.recordlite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;
import android.util.Log;

import java.io.File;

public final class RecordingService extends Service {
    static final String ACTION_START = "com.xiaoyuzhuang.recordlite.START";
    static final String ACTION_STOP = "com.xiaoyuzhuang.recordlite.STOP";
    private static final String CHANNEL = "recording";
    private static final int NOTIFICATION_ID = 1207;

    static volatile boolean isRunning;
    static volatile String status = "尚未开始录音";

    private RecorderEngine engine;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "持续录音", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示当前录音状态，允许随时停止");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (engine != null) engine.requestStop();
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || (worker != null && worker.isAlive())) {
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIFICATION_ID, notification("正在准备麦克风…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecordLite:Microphone");
            wakeLock.acquire();

            RecordingSettings settings = RecordingSettings.read(this);
            File base = getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC);
            if (base == null) throw new IllegalStateException("无法访问录音存储空间");
            File dir = new File(base, "RecordLite");
            engine = new RecorderEngine(settings, dir, info -> {
                status = info;
                main.post(() -> {
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    nm.notify(NOTIFICATION_ID, notification(info));
                });
            });
            isRunning = true;
            worker = new Thread(() -> {
                try {
                    engine.run();
                } catch (Exception e) {
                    status = "录音出错：" + e.getMessage();
                    Log.e("RecordLite", status, e);
                    main.post(() -> Toast.makeText(getApplicationContext(),
                            status, Toast.LENGTH_LONG).show());
                } finally {
                    isRunning = false;
                    engine = null;
                    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }, "RecordLite-Audio");
            worker.start();
        } catch (Exception e) {
            isRunning = false;
            status = "启动失败：" + e.getMessage();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            main.post(() -> Toast.makeText(getApplicationContext(),
                    status, Toast.LENGTH_LONG).show());
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, RecordingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.xiaoyuzhuang.recordlite.R.drawable.ic_record)
                .setContentTitle("RecordLite · 正在录音")
                .setContentText(message)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "停止录音", stopPi)
                .build();
    }

    @Override public void onDestroy() {
        if (engine != null) engine.requestStop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
