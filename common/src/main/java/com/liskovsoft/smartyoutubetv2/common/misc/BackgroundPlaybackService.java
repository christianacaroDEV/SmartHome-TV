package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;

/**
 * Keeps the app process alive while the video audio plays with the screen locked or the app minimized
 * (mobile "Background playback" switch). Android requires a visible notification for that.<br/>
 * Rules of recent Android versions this follows:<br/>
 * - the notification must have an icon (Android 14 rejects it otherwise and closes the app)<br/>
 * - the service declares the "mediaPlayback" type (manifest + startForeground, required by Android 14)<br/>
 * - it must be started while the app is still in the foreground (Android 12+), see {@link #start(Context)}
 */
public class BackgroundPlaybackService extends Service {
    private static final String TAG = BackgroundPlaybackService.class.getSimpleName();
    private static final String CHANNEL_ID = "background_playback";
    private static final int NOTIFICATION_ID = 7001;

    @Override
    public void onCreate() {
        super.onCreate();
        goForeground();
    }

    private void goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.background_playback_channel), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
            }

            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            PendingIntent contentIntent = open != null ? PendingIntent.getActivity(this, 0, open,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT) : null;

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.icon_notification)
                    .setContentTitle(getApplicationInfo().loadLabel(getPackageManager()))
                    .setContentText(getString(R.string.background_playback_running))
                    .setContentIntent(contentIntent)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // Never crash the app because of the notification: worst case the audio stops in the background
            Log.e(TAG, "Can't start the background playback notification: %s", e.getMessage());
            stopSelf();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: %s", Helpers.toString(intent));

        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: %s", Helpers.toString(intent));

        return START_NOT_STICKY;
    }

    /**
     * Call it while the app is still visible (e.g. Activity.onPause): Android 12+ doesn't allow starting it later.
     */
    public static void start(Context context) {
        try {
            Intent serviceIntent = new Intent(context, BackgroundPlaybackService.class);

            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            // ForegroundServiceStartNotAllowedException (Android 12+) and similar: don't crash
            Log.e(TAG, "Can't start the background playback service: %s", e.getMessage());
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, BackgroundPlaybackService.class));
        } catch (Exception e) {
            Log.e(TAG, "Can't stop the background playback service: %s", e.getMessage());
        }
    }
}
