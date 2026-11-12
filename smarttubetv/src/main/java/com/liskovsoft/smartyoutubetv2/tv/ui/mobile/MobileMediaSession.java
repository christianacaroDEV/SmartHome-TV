package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.exoplayer2.ControlDispatcher;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.BackgroundPlaybackService;

/**
 * Android media session of the mobile in-app player: title, channel and thumbnail on the lock screen and in the
 * media notification, and previous / play-pause / next from there, from headphones and Bluetooth (like YouTube).
 */
public class MobileMediaSession {
    private static final String TAG = MobileMediaSession.class.getSimpleName();

    public interface Callback {
        void onNext();
        void onPrevious();
        void onPlayPause();
        boolean isPlaying();
    }

    private final Context mContext;
    private final MobilePlayerView mPlayer;
    private final Callback mCallback;
    private MediaSessionCompat mSession;
    private MediaSessionConnector mConnector;
    private Player mAttachedPlayer;
    private Video mVideo;
    private Bitmap mArtwork;

    public MobileMediaSession(Context context, MobilePlayerView player, Callback callback) {
        mContext = context.getApplicationContext();
        mPlayer = player;
        mCallback = callback;

        if (Build.VERSION.SDK_INT <= 19) {
            return; // Android 4.4 bug: MediaButtonReceiver component may not be null
        }

        try {
            mSession = new MediaSessionCompat(mContext, "BlueTubeMobile");
            mConnector = new MediaSessionConnector(mSession);
            mConnector.setQueueNavigator(new Navigator());
            mConnector.setMediaMetadataProvider(player1 -> buildMetadata());
        } catch (Exception | NoSuchMethodError e) {
            Log.e(TAG, "Media session not available: %s", e.getMessage());
            mSession = null;
            mConnector = null;
        }

        BackgroundPlaybackService.setControls(new BackgroundPlaybackService.Controls() {
            @Override
            public void onPlayPause() {
                mCallback.onPlayPause();
            }

            @Override
            public void onNext() {
                mCallback.onNext();
            }

            @Override
            public void onPrevious() {
                mCallback.onPrevious();
            }

            @Override
            public boolean isPlaying() {
                return mCallback.isPlaying();
            }
        }, mSession != null ? mSession.getSessionToken() : null);
    }

    /**
     * A new video: connect the player (created with the first video) and show the new title/thumbnail.
     */
    public void setVideo(Video video) {
        mVideo = video;
        attachPlayer();

        if (mConnector != null) {
            mConnector.invalidateMediaSessionMetadata();
        }

        BackgroundPlaybackService.setMetadata(title(), subtitle(), mArtwork);
        loadArtwork(video);
    }

    public void setActive(boolean active) {
        if (mSession != null) {
            attachPlayer();
            mSession.setActive(active);
        }
    }

    public void release() {
        BackgroundPlaybackService.setControls(null, null);

        if (mConnector != null) {
            mConnector.setPlayer(null);
        }

        if (mSession != null) {
            mSession.setActive(false);
            mSession.release();
        }
    }

    // ---------------------------------------------------------------------------------------------

    private void attachPlayer() {
        Player player = mPlayer.getExoPlayer();

        if (mConnector != null && player != null && player != mAttachedPlayer) {
            try {
                mConnector.setPlayer(player);
                mAttachedPlayer = player;
            } catch (NoSuchMethodError e) {
                // Android 9, Sony: PlaybackState$Builder.setState missing
            }
        }
    }

    private void loadArtwork(Video video) {
        if (video == null || video.cardImageUrl == null) {
            return;
        }

        try {
            Glide.with(mContext).asBitmap().load(video.cardImageUrl).into(new CustomTarget<Bitmap>(480, 270) {
                @Override
                public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                    if (video != mVideo) {
                        return;
                    }

                    mArtwork = resource;

                    if (mConnector != null) {
                        mConnector.invalidateMediaSessionMetadata();
                    }

                    BackgroundPlaybackService.setMetadata(title(), subtitle(), resource);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    // NOP
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Can't load the artwork: %s", e.getMessage());
        }
    }

    private MediaMetadataCompat buildMetadata() {
        if (mVideo == null) {
            return null;
        }

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mVideo.videoId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Math.max(0, mPlayer.getDurationMs()));

        if (mArtwork != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, mArtwork);
        }

        return builder.build();
    }

    private String title() {
        return mVideo == null ? null : mVideo.getTitleFull() != null ? mVideo.getTitleFull() : mVideo.getTitle();
    }

    private String subtitle() {
        if (mVideo == null) {
            return null;
        }

        String author = mVideo.author != null ? mVideo.author : mVideo.getAuthor();
        return author != null && author.contains("•") ? author.split("•")[0].trim() : author;
    }

    /**
     * Previous / next from the lock screen, headphones and Bluetooth go to the app's own playlist logic.
     */
    private class Navigator implements MediaSessionConnector.QueueNavigator {
        @Override
        public long getSupportedQueueNavigatorActions(Player player) {
            return PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        }

        @Override
        public void onTimelineChanged(Player player) {
        }

        @Override
        public void onCurrentWindowIndexChanged(Player player) {
        }

        @Override
        public long getActiveQueueItemId(@Nullable Player player) {
            return MediaSessionCompat.QueueItem.UNKNOWN_ID;
        }

        @Override
        public void onSkipToPrevious(Player player, ControlDispatcher controlDispatcher) {
            mCallback.onPrevious();
        }

        @Override
        public void onSkipToQueueItem(Player player, ControlDispatcher controlDispatcher, long id) {
        }

        @Override
        public void onSkipToNext(Player player, ControlDispatcher controlDispatcher) {
            mCallback.onNext();
        }

        @Override
        public boolean onCommand(Player player, ControlDispatcher controlDispatcher, String command, Bundle extras, ResultReceiver cb) {
            return false;
        }
    }
}
