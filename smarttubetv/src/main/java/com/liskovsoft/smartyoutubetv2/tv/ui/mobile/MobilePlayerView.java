package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.embedplayer.EmbedPlayerView;

/**
 * In-app player of the mobile mode. Unlike the card previews it behaves as the full player
 * (user quality, suggestions, history, SponsorBlock...) but lives inside the home screen.
 */
public class MobilePlayerView extends EmbedPlayerView {
    public interface Listener {
        void onVideoChanged(Video video);
        void onSuggestions(VideoGroup group);
        void onSuggestionsCleared();
        void onLoading(boolean loading);
        void onFinished();
        /**
         * Like/subscribe states, channel avatar or the video metadata (views, likes, description...) changed.
         */
        void onInfoChanged();
    }

    private String mChannelIconUrl;

    private Listener mListener;
    // The controllers store the state of the player buttons here (e.g. subtitles on/off)
    private final java.util.Map<Integer, Integer> mButtonStates = new java.util.HashMap<>();

    public MobilePlayerView(Context context) {
        super(context);
        setQuality(QUALITY_AUTO);
        setUseController(false); // own touch controls (MobileControlsView)
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    @Override
    public void openVideo(@Nullable Video video) {
        super.openVideo(video);
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); // the whole video, never cropped
        setAlpha(1);
    }

    /**
     * Behaves like the full screen player (see the class description).
     */
    @Override
    public boolean isEmbed() {
        return false;
    }

    @Override
    public void setVideo(Video item) {
        super.setVideo(item);

        if (mListener != null) {
            mListener.onVideoChanged(item);
        }
    }

    // The touch controls ask these before/after a video exists: never crash without a player

    @Override
    public long getPositionMs() {
        try {
            return super.getPositionMs();
        } catch (NullPointerException e) {
            return 0;
        }
    }

    @Override
    public long getDurationMs() {
        try {
            return super.getDurationMs();
        } catch (NullPointerException e) {
            return 0;
        }
    }

    @Override
    public boolean getPlayWhenReady() {
        try {
            return super.getPlayWhenReady();
        } catch (NullPointerException e) {
            return false;
        }
    }

    @Override
    public void setPositionMs(long positionMs) {
        try {
            super.setPositionMs(positionMs);
        } catch (NullPointerException e) {
            // no player yet
        }
    }

    @Override
    public void setPlayWhenReady(boolean play) {
        try {
            super.setPlayWhenReady(play);
        } catch (NullPointerException e) {
            // no player yet
        }
    }

    @Override
    public int getButtonState(int buttonId) {
        Integer state = mButtonStates.get(buttonId);
        return state != null ? state : -1;
    }

    @Override
    public void setButtonState(int buttonId, int buttonState) {
        Integer old = mButtonStates.put(buttonId, buttonState);

        if ((old == null || old != buttonState) && mListener != null) {
            mListener.onInfoChanged();
        }
    }

    @Override
    public void setChannelIcon(String iconUrl) {
        mChannelIconUrl = iconUrl;

        if (mListener != null) {
            mListener.onInfoChanged();
        }
    }

    public String getChannelIconUrl() {
        return mChannelIconUrl;
    }

    @Override
    public void updateSuggestions(VideoGroup group) {
        if (mListener != null) {
            mListener.onSuggestions(group);
        }
    }

    @Override
    public void clearSuggestions() {
        if (mListener != null) {
            mListener.onSuggestionsCleared();
        }
    }

    @Override
    public void showProgressBar(boolean show) {
        if (mListener != null) {
            mListener.onLoading(show);
        }
    }

    @Override
    public void finish() {
        super.finish();

        if (mListener != null) {
            mListener.onFinished();
        }
    }

    /**
     * Stops the playback without notifying the listener (the sheet is closing itself).
     */
    public void release() {
        super.finish();
    }
}
