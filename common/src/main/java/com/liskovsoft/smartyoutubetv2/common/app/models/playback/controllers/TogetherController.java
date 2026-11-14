package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.together.TogetherManager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * "Watch together" on the player (phone and TV): sends what this person does (play, pause, seek, another video)
 * and applies what the others do. Changes made here because of the room are not sent back (no echo loop).
 */
public class TogetherController extends BasePlayerController implements TogetherManager.PlayerSide {
    /** Drift tolerated before seeking to the room position */
    private static final long MAX_DRIFT_MS = 2_500;
    /** Events right after applying a room change are its consequence, not the user's */
    private static final long QUIET_MS = 2_000;
    private long mQuietUntil;
    private String mPendingVideoId;
    private long mPendingPositionMs = -1;
    private boolean mPendingPlaying = true;
    private float mVolumeBeforeDuck = -1;
    /** Play state the room is known to have (applied from it or sent to it): the player repeating it isn't news */
    private Boolean mRoomPlaying;

    private TogetherManager getManager() {
        return TogetherManager.instance(getContext());
    }

    @Override
    public void onInit() {
        getManager().setPlayerSide(this);
    }

    // ---------------------------------------------------------------------------------------------
    // This device -> room
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onVideoLoaded(Video item) {
        if (item != null && mPendingVideoId != null && mPendingVideoId.equals(item.videoId) && getPlayer() != null) {
            // The video the room asked for: same position and play state as the others
            mQuietUntil = System.currentTimeMillis() + QUIET_MS;
            if (mPendingPositionMs >= 0) {
                getPlayer().setPositionMs(mPendingPositionMs);
            }
            getPlayer().setPlayWhenReady(mPendingPlaying);
            mRoomPlaying = mPendingPlaying;
            mPendingVideoId = null;
            mPendingPositionMs = -1;
            return;
        }

        publish();
    }

    @Override
    public void onPlay() {
        publishIfChanged();
    }

    @Override
    public void onPause() {
        publishIfChanged();
    }

    /**
     * The player reports "playing"/"paused" also when it just finishes loading or buffering: only a real change
     * (e.g. this person pressed play) goes to the room. Else a video that loaded paused here could pause everybody
     * right after someone else pressed play.
     */
    private void publishIfChanged() {
        if (getPlayer() != null && mRoomPlaying != null && mRoomPlaying == getPlayer().getPlayWhenReady()) {
            return;
        }

        publish();
    }

    @Override
    public void onSeekEnd() {
        publish();
    }

    private void publish() {
        if (!getManager().isActive() || getPlayer() == null || getVideo() == null || System.currentTimeMillis() < mQuietUntil) {
            return;
        }

        Video video = getVideo();
        mRoomPlaying = getPlayer().getPlayWhenReady();
        getManager().publishState(video.videoId, video.getTitle(), getPlayer().getPositionMs(), mRoomPlaying);
    }

    @Override
    public JSONObject getLocalState() {
        Video video = getVideo();

        if (video == null || video.videoId == null || getPlayer() == null) {
            return null;
        }

        try {
            return new JSONObject()
                    .put("videoId", video.videoId)
                    .put("title", video.getTitle() != null ? video.getTitle() : "")
                    .put("positionMs", getPlayer().getPositionMs())
                    .put("playing", getPlayer().getPlayWhenReady());
        } catch (JSONException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Room -> this device
    // ---------------------------------------------------------------------------------------------

    @Override
    public void applyRemoteState(String videoId, String title, long positionMs, boolean playing) {
        mQuietUntil = System.currentTimeMillis() + QUIET_MS;
        mRoomPlaying = playing;
        Video current = getVideo();

        boolean sameVideo = current != null && videoId.equals(current.videoId);

        if (getPlayer() == null || !sameVideo || getPlayer().getDurationMs() <= 0) {
            // Another video (or this one still loading): the position is applied once it has loaded
            mPendingVideoId = videoId;
            mPendingPositionMs = positionMs;
            mPendingPlaying = playing;

            if (!sameVideo) {
                Video video = Video.from(videoId);
                video.title = title;
                PlaybackPresenter.instance(getContext()).openVideo(video);
            }
            return;
        }

        if (Math.abs(getPlayer().getPositionMs() - positionMs) > MAX_DRIFT_MS) {
            getPlayer().setPositionMs(positionMs);
        }

        if (getPlayer().getPlayWhenReady() != playing) {
            getPlayer().setPlayWhenReady(playing);
        }
    }

    @Override
    public void pauseForRoom() {
        if (getPlayer() != null) {
            mQuietUntil = System.currentTimeMillis() + QUIET_MS;
            getPlayer().setPlayWhenReady(false);
        }
    }

    @Override
    public Boolean isPlaying() {
        return getPlayer() != null && getVideo() != null ? getPlayer().getPlayWhenReady() : null;
    }

    @Override
    public boolean control(int action) {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        mQuietUntil = 0; // the user's own action: always sent

        if (action == TogetherManager.ACTION_TOGGLE_PLAY) {
            getPlayer().setPlayWhenReady(!getPlayer().getPlayWhenReady());
        } else if (action == TogetherManager.ACTION_REWIND) {
            getPlayer().setPositionMs(Math.max(0, getPlayer().getPositionMs() - TogetherManager.REWIND_MS));
        }

        publish();
        return true;
    }

    @Override
    public void setDucked(boolean ducked) {
        if (getPlayer() == null) {
            return;
        }

        if (ducked && mVolumeBeforeDuck < 0) {
            mVolumeBeforeDuck = getPlayer().getVolume();
            getPlayer().setVolume(Math.min(mVolumeBeforeDuck, 0.2f));
        } else if (!ducked && mVolumeBeforeDuck >= 0) {
            getPlayer().setVolume(mVolumeBeforeDuck);
            mVolumeBeforeDuck = -1;
        }
    }
}
