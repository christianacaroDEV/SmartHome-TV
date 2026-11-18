package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.content.Context;
import android.os.SystemClock;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.together.BlueCast;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * "Send to TV" on the TV player: plays what a phone sends (from the same second) and obeys its remote
 * (play/pause, back/forward, stop). Its state goes back to the phone through {@link BlueCast}.
 */
public class CastController extends BasePlayerController implements BlueCast.PlayerHook {
    private String mPendingVideoId;
    private long mPendingPositionMs = -1;
    private long mPendingSinceMs;

    public CastController() {
        BlueCast.setPlayerHook(this);
    }

    @Override
    public void castPlay(Context context, String videoId, String title, long positionMs) {
        Video current = getVideo();

        if (getPlayer() != null && current != null && videoId.equals(current.videoId) && getPlayer().getDurationMs() > 0) {
            getPlayer().setPositionMs(positionMs);
            getPlayer().setPlayWhenReady(true);
            return;
        }

        mPendingVideoId = videoId;
        mPendingPositionMs = positionMs;
        mPendingSinceMs = SystemClock.elapsedRealtime();
        Video video = Video.from(videoId);
        video.title = title;
        PlaybackPresenter.instance(context).openVideo(video);
    }

    @Override
    public void onVideoLoaded(Video item) {
        if (item == null || mPendingVideoId == null || !mPendingVideoId.equals(item.videoId) || getPlayer() == null) {
            return;
        }

        // From the second the phone was at, plus the time it took to open here
        long loading = Math.max(0, SystemClock.elapsedRealtime() - mPendingSinceMs);
        if (mPendingPositionMs > 0) {
            getPlayer().setPositionMs(mPendingPositionMs + loading);
        }
        getPlayer().setPlayWhenReady(true);
        mPendingVideoId = null;
        mPendingPositionMs = -1;
    }

    @Override
    public void castControl(String action, long value) {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        switch (action) {
            case "toggle":
                getPlayer().setPlayWhenReady(!getPlayer().getPlayWhenReady());
                break;
            case "play":
                getPlayer().setPlayWhenReady(true);
                break;
            case "pause":
            case "stop":
                getPlayer().setPlayWhenReady(false);
                break;
            case "seek":
                long duration = getPlayer().getDurationMs();
                long position = Math.max(0, getPlayer().getPositionMs() + value);
                getPlayer().setPositionMs(duration > 0 ? Math.min(duration - 1_000, position) : position);
                break;
        }
    }

    @Override
    public JSONObject castState() {
        Video video = getVideo();

        if (getPlayer() == null || video == null || video.videoId == null) {
            return null;
        }

        try {
            return new JSONObject()
                    .put("open", true)
                    .put("videoId", video.videoId)
                    .put("title", video.getTitle() != null ? video.getTitle() : "")
                    .put("positionMs", getPlayer().getPositionMs())
                    .put("durationMs", getPlayer().getDurationMs())
                    .put("playing", getPlayer().getPlayWhenReady());
        } catch (JSONException e) {
            return null;
        }
    }
}
