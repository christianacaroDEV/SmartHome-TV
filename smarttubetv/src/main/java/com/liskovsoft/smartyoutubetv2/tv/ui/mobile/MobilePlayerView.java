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
    }

    private Listener mListener;

    public MobilePlayerView(Context context) {
        super(context);
        setQuality(QUALITY_AUTO);
        setUseController(true);
        setControllerShowTimeoutMs(3_000);
        setControllerHideOnTouch(true);
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
