package com.realtimedenoise.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service (type mediaProjection) required by Android 14+/HyperOS to
 * acquire & hold a MediaProjection. Without this, getMediaProjection() throws
 * "Media projections require a foreground service of type
 * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION".
 *
 * The obtained MediaProjection is exposed via the static field for the
 * Activity to build an AudioPlaybackCapture AudioRecord.
 */
public class ProjectionService extends Service {

    public static final String ACTION_PROJECT = "com.realtimedenoise.app.PROJECT";
    public static final String ACTION_STOP = "com.realtimedenoise.app.STOP";

    public static volatile MediaProjection projection = null;

    private static final String CHANNEL_ID = "rtd_projection";
    private static final int NOTIF_ID = 1001;

    private MediaProjectionManager mpm;

    @Override
    public void onCreate() {
        super.onCreate();
        mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            releaseProjection();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_PROJECT.equals(action)) {
            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");
            startForegroundWithProjectionType();
            if (projection == null && data != null) {
                try {
                    projection = mpm.getMediaProjection(code, data);
                    projection.registerCallback(new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            projection = null;
                        }
                    }, null);
                } catch (Throwable t) {
                    // Activity observes projection==null and reports failure.
                    projection = null;
                }
            }
        }
        return START_STICKY;
    }

    private void startForegroundWithProjectionType() {
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("实时降噪监听")
                .setContentText("正在捕获系统声音（前台服务运行中）")
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "投屏捕获",
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("MediaProjection 前台服务");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private void releaseProjection() {
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }
    }

    @Override
    public void onDestroy() {
        releaseProjection();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}