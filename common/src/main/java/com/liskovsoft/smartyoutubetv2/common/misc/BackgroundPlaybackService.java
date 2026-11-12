package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;

/**
 * Keeps the app process alive while the video audio plays with the screen locked or the app minimized
 * (mobile "Background playback" switch). Android requires a visible notification for that: it is a media
 * notification with previous / play-pause / next, also shown on the lock screen (like YouTube).<br/>
 * Rules of recent Android versions this follows:<br/>
 * - the notification must have an icon (Android 14 rejects it otherwise and closes the app)<br/>
 * - the service declares the "mediaPlayback" type (manifest + startForeground, required by Android 14)<br/>
 * - it must be started while the app is still in the foreground (Android 12+), see {@link #start(Context)}
 */
public class BackgroundPlaybackService extends Service {
    private static final String TAG = BackgroundPlaybackService.class.getSimpleName();
    private static final String CHANNEL_ID = "background_playback";
    private static final int NOTIFICATION_ID = 7001;
    private static final String ACTION_PLAY_PAUSE = "bluetube.action.PLAY_PAUSE";
    private static final String ACTION_NEXT = "bluetube.action.NEXT";
    private static final String ACTION_PREVIOUS = "bluetube.action.PREVIOUS";

    /**
     * What the notification buttons do (set by the in-app player).
     */
    public interface Controls {
        void onPlayPause();
        void onNext();
        void onPrevious();
        boolean isPlaying();
    }

    private static Controls sControls;
    private static MediaSessionCompat.Token sSessionToken;
    private static String sTitle;
    private static String sSubtitle;
    private static Bitmap sArtwork;
    private static BackgroundPlaybackService sInstance;

    public static void setControls(Controls controls, MediaSessionCompat.Token sessionToken) {
        sControls = controls;
        sSessionToken = sessionToken;
    }

    /**
     * The current video (title, channel, thumbnail). Refreshes the notification if it is shown.
     */
    public static void setMetadata(String title, String subtitle, Bitmap artwork) {
        sTitle = title;
        sSubtitle = subtitle;
        sArtwork = artwork;
        refresh();
    }

    /**
     * Play/pause changed: the button icon follows.
     */
    public static void refresh() {
        BackgroundPlaybackService instance = sInstance;

        if (instance != null) {
            try {
                ((NotificationManager) instance.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, instance.buildNotification());
            } catch (Exception e) {
                Log.e(TAG, "Can't refresh the notification: %s", e.getMessage());
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        goForeground();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
    }

    private void goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.background_playback_channel), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
            }

            Notification notification = buildNotification();

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

    private Notification buildNotification() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = open != null ? PendingIntent.getActivity(this, 0, open, immutableFlags()) : null;
        boolean playing = sControls != null && sControls.isPlaying();
        String title = sTitle != null ? sTitle : String.valueOf(getApplicationInfo().loadLabel(getPackageManager()));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon_notification)
                .setContentTitle(title)
                .setContentText(sSubtitle != null ? sSubtitle : getString(R.string.background_playback_running))
                .setLargeIcon(sArtwork)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // controls also on the lock screen
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_notif_prev, getString(R.string.mobile_cd_previous), actionIntent(ACTION_PREVIOUS, 1))
                .addAction(playing ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play, getString(R.string.mobile_cd_play_pause), actionIntent(ACTION_PLAY_PAUSE, 2))
                .addAction(R.drawable.ic_notif_next, getString(R.string.mobile_cd_next), actionIntent(ACTION_NEXT, 3));

        androidx.media.app.NotificationCompat.MediaStyle style = new androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2);

        if (sSessionToken != null) {
            style.setMediaSession(sSessionToken); // lock screen / quick settings media player (Android 11+)
        }

        builder.setStyle(style);
        return builder.build();
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, BackgroundPlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, immutableFlags());
    }

    private static int immutableFlags() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
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

        String action = intent != null ? intent.getAction() : null;
        Controls controls = sControls;

        if (action != null && controls != null) {
            if (ACTION_PLAY_PAUSE.equals(action)) {
                controls.onPlayPause();
            } else if (ACTION_NEXT.equals(action)) {
                controls.onNext();
            } else if (ACTION_PREVIOUS.equals(action)) {
                controls.onPrevious();
            }

            refresh();
        }

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
