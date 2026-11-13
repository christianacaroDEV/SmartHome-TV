package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * YouTube-like in-app player of the mobile home screen. Three states:<br/>
 * EXPANDED - video on top (16:9), title and suggested videos below, covers the home screen<br/>
 * MINI - small bar docked above the bottom navigation (thumbnail video, title, play/pause, close)<br/>
 * FULLSCREEN - landscape, only the video<br/>
 * Gestures on the video: drag down = minimize (or leave full screen), drag up = full screen. Rotating the phone
 * enters/leaves full screen. Tap on the mini bar = expand.
 */
public class MobileWatchSheet implements MobilePlayerView.Listener {
    public static final int STATE_HIDDEN = 0;
    public static final int STATE_EXPANDED = 1;
    public static final int STATE_MINI = 2;
    public static final int STATE_FULLSCREEN = 3;
    private static final int MINI_HEIGHT_DP = 64;
    private static final int LOAD_MORE_THRESHOLD = 4;
    private static final Object INFO_HEADER = new Object();
    private static final Object CHAPTERS_HEADER = new Object();
    private static final long SUGGESTIONS_TIMEOUT_MS = 8_000;
    private boolean mSuggestionsTimedOut;
    private final Runnable mSuggestionsTimeout = this::onSuggestionsTimeout;

    private final Activity mActivity;
    private final FrameLayout mRoot;
    private final View mBottomNav;
    private final FrameLayout mSheet;
    private final PlayerHolder mPlayerHolder;
    private final MobilePlayerView mPlayer;
    private final ProgressBar mLoading;
    private final MobileControlsView mControls;
    private final MobileMediaSession mMediaSession;
    private final TextView mShortsInfo;
    /** Locked in portrait: big time + battery + date above the video, like a lock screen */
    private final LinearLayout mLockClock;
    private final TextView mLockTime;
    private final TextView mLockInfo;
    private final Runnable mLockClockUpdate = new Runnable() {
        @Override
        public void run() {
            updateLockClock();
            mLockClock.postDelayed(this, 15_000);
        }
    };
    private boolean mShortsMode;
    private float mShortsDownY;
    private float mShortsDownX;
    private final LinearLayout mMiniBar;
    private final TextView mMiniTitle;
    private final TextView mMiniSubtitle;
    private final ImageButton mMiniPlayPause;
    private final RecyclerView mList;
    private final ListAdapter mAdapter = new ListAdapter();
    private final LinkedHashMap<Integer, List<Video>> mGroups = new LinkedHashMap<>();
    private final Set<Video> mUnique = new HashSet<>();
    private int mState = STATE_HIDDEN;
    private Video mVideo;
    private Video mLastScrollEndVideo;
    private int mAwaitedOrientation; // 1 landscape, 2 portrait
    private OrientationEventListener mOrientationListener;
    private int mSavedSystemUi = -1;

    public MobileWatchSheet(Activity activity, View bottomNav) {
        mActivity = activity;
        mRoot = activity.findViewById(android.R.id.content);
        mBottomNav = bottomNav;

        mSheet = new FrameLayout(activity);
        mSheet.setBackgroundResource(R.drawable.bluetube_background);
        mSheet.setClickable(true); // the home screen behind must not get the touches
        mSheet.setVisibility(View.GONE);
        androidx.core.view.ViewCompat.setElevation(mSheet, dp(8)); // no-op before Android 5
        mRoot.addView(mSheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Suggestions (expanded state), below the video
        mList = new RecyclerView(activity);
        mList.setLayoutManager(new LinearLayoutManager(activity));
        mList.setAdapter(mAdapter);
        if (mList.getItemAnimator() instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            // The info block is refreshed in place (likes, subscribe...): no blink
            ((androidx.recyclerview.widget.SimpleItemAnimator) mList.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    loadMoreIfNeeded();
                }
            }
        });
        mSheet.addView(mList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Mini bar (mini state)
        mMiniBar = new LinearLayout(activity);
        mMiniBar.setOrientation(LinearLayout.HORIZONTAL);
        mMiniBar.setGravity(Gravity.CENTER_VERTICAL);
        mMiniBar.setBackgroundColor(0xFF0F1C3A);
        mMiniBar.setOnClickListener(v -> setState(STATE_EXPANDED));
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        mMiniTitle = createText(14, Color.WHITE, true);
        mMiniSubtitle = createText(12, Color.parseColor("#9FB3D9"), false);
        texts.addView(mMiniTitle);
        texts.addView(mMiniSubtitle);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.setMargins(dp(12), 0, dp(4), 0);
        mMiniBar.addView(texts, textsParams);
        mMiniPlayPause = createIconButton(R.drawable.ic_m_pause);
        mMiniPlayPause.setOnClickListener(v -> togglePlay());
        mMiniBar.addView(mMiniPlayPause);
        ImageButton miniClose = createIconButton(R.drawable.ic_m_close);
        miniClose.setOnClickListener(v -> close());
        mMiniBar.addView(miniClose);
        mSheet.addView(mMiniBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MINI_HEIGHT_DP)));

        // Player (always the same view: it only changes its size, so the playback never restarts)
        mPlayerHolder = new PlayerHolder(activity);
        mPlayerHolder.setBackgroundColor(Color.BLACK);
        mPlayer = new MobilePlayerView(activity);
        mPlayer.setListener(this);
        // Lock screen / notification / headphones controls
        mMediaSession = new MobileMediaSession(activity, mPlayer, new MobileMediaSession.Callback() {
            @Override
            public void onNext() {
                PlaybackPresenter.instance(mActivity).onNextClicked();
            }

            @Override
            public void onPrevious() {
                PlaybackPresenter presenter = PlaybackPresenter.instance(mActivity);
                presenter.onPreviousClicked();
            }

            @Override
            public void onPlayPause() {
                mPlayer.post(MobileWatchSheet.this::togglePlay);
            }

            @Override
            public boolean isPlaying() {
                return MobileWatchSheet.this.isPlaying();
            }
        });
        mPlayer.setBackgroundColor(Color.BLACK);
        mPlayerHolder.addView(mPlayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLoading = new ProgressBar(activity);
        mLoading.setVisibility(View.GONE);
        mPlayerHolder.addView(mLoading, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        // Own touch controls instead of the ExoPlayer controller
        mPlayer.setUseController(false);
        mControls = new MobileControlsView(activity, mPlayer, new MobileControlsView.Callback() {
            @Override
            public void onMinimizeClicked() {
                setState(mState == STATE_FULLSCREEN ? STATE_EXPANDED : STATE_MINI);
            }

            @Override
            public void onFullscreenClicked() {
                setState(mState == STATE_FULLSCREEN ? STATE_EXPANDED : STATE_FULLSCREEN);
            }

            @Override
            public void onPanelVisibilityChanged(boolean visible) {
                // Full screen: the corner clock steps aside while the player buttons are shown
                if (mClock != null && mState == STATE_FULLSCREEN) {
                    if (visible) {
                        mClock.hide();
                    } else {
                        mClock.show();
                    }
                }
            }

            @Override
            public void onLockChanged(boolean locked) {
                // The holder stops the drag gestures and BACK while locked.
                // Portrait: the screen stays vertical and only the video is shown.
                if (mState == STATE_EXPANDED) {
                    mActivity.setRequestedOrientation(locked ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    applyLayout();
                }
            }
        });
        mPlayerHolder.addView(mControls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLoading.bringToFront();
        // Shorts: channel + title over a dark gradient at the bottom
        mShortsInfo = createText(15, Color.WHITE, true);
        mShortsInfo.setMaxLines(3);
        mShortsInfo.setPadding(dp(16), dp(40), dp(16), dp(28));
        mShortsInfo.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xCC000000, 0x00000000}));
        mShortsInfo.setVisibility(View.GONE);
        mPlayerHolder.addView(mShortsInfo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        mSheet.addView(mPlayerHolder);

        mLockClock = new LinearLayout(activity);
        // Small, one line, in the black band above the video: it never covers the picture
        mLockClock.setOrientation(LinearLayout.HORIZONTAL);
        mLockClock.setGravity(Gravity.CENTER_VERTICAL);
        mLockTime = createText(15, 0xE6FFFFFF, true);
        mLockClock.addView(mLockTime);
        mLockInfo = createText(13, 0xFF9FB3D9, false);
        mLockInfo.setPadding(dp(8), 0, 0, 0);
        mLockClock.addView(mLockInfo);
        mLockClock.setVisibility(View.GONE);
        FrameLayout.LayoutParams lockClockParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lockClockParams.topMargin = dp(14);
        mSheet.addView(mLockClock, lockClockParams);

        // Re-layout whenever the real size changes (rotation, split screen...)
        mRoot.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                mRoot.post(this::applyLayout);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    public void open(Video video) {
        if (video == null) {
            return;
        }

        mVideo = video;
        // Shorts: full screen vertical, swipe up/down for the next/previous one (like YouTube)
        mShortsMode = video.isShorts && !isLandscape();
        clearSuggestionsList();
        restartSuggestionsTimeout();
        mAdapter.setItems(buildItems());
        mPlayer.openVideo(video);
        mList.scrollToPosition(0);
        setState(isLandscape() ? STATE_FULLSCREEN : STATE_EXPANDED);
        if (!mShortsMode) {
            mControls.flash();
        }
        updateShortsInfo();
    }

    public int getState() {
        return mState;
    }

    public boolean isActive() {
        return mState != STATE_HIDDEN;
    }

    public boolean isPlaying() {
        return isActive() && mPlayer.getPlayWhenReady();
    }

    private boolean mAudioOnly;
    private boolean mEdgeGesture;

    /**
     * Audio only while the app is in the background (the picture comes back when the user returns).
     */
    public void setAudioOnly(boolean audioOnly) {
        if (mAudioOnly == audioOnly || !isActive()) {
            return;
        }

        mAudioOnly = audioOnly;
        mPlayer.setVideoEnabled(!audioOnly);
    }

    public void pause() {
        if (isActive()) {
            mPlayer.setPlayWhenReady(false);
            updatePlayPauseIcon();
        }
    }

    /**
     * @return true if BACK was consumed (full screen -> expanded -> mini)
     */
    public boolean onBackPressed() {
        if (mState != STATE_MINI && mControls.isLocked()) {
            return true; // locked ("Fijar"): BACK does nothing until unlocked
        }

        if (mShortsMode && mState == STATE_EXPANDED) {
            close(); // leaving the Shorts player stops it, like YouTube
            return true;
        }

        if (mState == STATE_FULLSCREEN) {
            setState(STATE_EXPANDED);
            return true;
        }

        if (mState == STATE_EXPANDED) {
            setState(STATE_MINI);
            return true;
        }

        return false;
    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (!isActive()) {
            return;
        }

        // NOTE: use the new configuration: the views still have the old size at this moment
        boolean landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;

        // Rotating the phone: landscape = full screen, portrait = expanded (like YouTube)
        if (landscape && mState != STATE_FULLSCREEN && mState != STATE_MINI) {
            setState(STATE_FULLSCREEN);
        } else if (!landscape && mState == STATE_FULLSCREEN) {
            setState(STATE_EXPANDED);
        } else {
            applyLayout();
        }
    }

    public void close() {
        mMediaSession.setActive(false);
        mPlayer.release();
        setState(STATE_HIDDEN);
        mVideo = null;
    }

    public void release() {
        if (mOrientationListener != null) {
            mOrientationListener.disable();
        }

        mMediaSession.release();

        mPlayer.release();
    }

    // ---------------------------------------------------------------------------------------------
    // States
    // ---------------------------------------------------------------------------------------------

    /**
     * The screen behind leaves room for the mini player (its lists can scroll to the last item).
     */
    public interface OnStateChangedListener {
        void onStateChanged(int state, int miniHeightPx);
    }

    private OnStateChangedListener mOnStateChangedListener;

    public void setOnStateChangedListener(OnStateChangedListener listener) {
        mOnStateChangedListener = listener;
    }

    private void setState(int state) {
        int oldState = mState;
        mState = state;

        if (mOnStateChangedListener != null && oldState != state) {
            mOnStateChangedListener.onStateChanged(state, dp(MINI_HEIGHT_DP));
        }

        if (state == STATE_FULLSCREEN && !isLandscape()) {
            requestOrientation(true);
        } else if (oldState == STATE_FULLSCREEN && state != STATE_FULLSCREEN && isLandscape()) {
            requestOrientation(false);
        }

        setSystemBarsHidden(state == STATE_FULLSCREEN);
        applyLayout();
    }

    private void applyLayout() {
        if (mState == STATE_HIDDEN) {
            mSheet.setVisibility(View.GONE);
            return;
        }

        mSheet.setVisibility(View.VISIBLE);
        mSheet.bringToFront();
        int width = mRoot.getWidth() > 0 ? mRoot.getWidth() : mActivity.getResources().getDisplayMetrics().widthPixels;
        int videoHeight = Math.round(width * 9f / 16f);

        FrameLayout.LayoutParams sheetParams = (FrameLayout.LayoutParams) mSheet.getLayoutParams();
        FrameLayout.LayoutParams holderParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, videoHeight, Gravity.TOP);
        FrameLayout.LayoutParams listParams = (FrameLayout.LayoutParams) mList.getLayoutParams();

        switch (mState) {
            case STATE_MINI:
                int miniHeight = dp(MINI_HEIGHT_DP);
                sheetParams.height = miniHeight;
                sheetParams.gravity = Gravity.BOTTOM;
                sheetParams.bottomMargin = mBottomNav != null && mBottomNav.getVisibility() == View.VISIBLE ? mBottomNav.getHeight() + 1 : 0;
                holderParams = new FrameLayout.LayoutParams(Math.round(miniHeight * 16f / 9f), miniHeight, Gravity.START | Gravity.CENTER_VERTICAL);
                mMiniBar.setPadding(holderParams.width, 0, dp(4), 0);
                mMiniBar.setVisibility(View.VISIBLE);
                mList.setVisibility(View.GONE);
                break;
            case STATE_FULLSCREEN:
                sheetParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                sheetParams.bottomMargin = 0;
                holderParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                mMiniBar.setVisibility(View.GONE);
                mList.setVisibility(View.GONE);
                break;
            case STATE_EXPANDED:
            default:
                sheetParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                sheetParams.bottomMargin = 0;
                listParams.topMargin = videoHeight;
                mMiniBar.setVisibility(View.GONE);
                if (mShortsMode) {
                    // Shorts: the whole screen is the video
                    holderParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    mList.setVisibility(View.GONE);
                } else if (mControls.isLocked()) {
                    // Locked in portrait: only the video, centered, nothing else to tap
                    holderParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, videoHeight, Gravity.CENTER);
                    mList.setVisibility(View.GONE);
                } else {
                    mList.setVisibility(View.VISIBLE);
                }
                break;
        }

        // Locked in portrait: time and battery above the video (landscape: the corner clock of full screen)
        boolean lockClock = mState == STATE_EXPANDED && mControls.isLocked() && !mShortsMode;
        mLockClock.removeCallbacks(mLockClockUpdate);
        mLockClock.setVisibility(lockClock ? View.VISIBLE : View.GONE);
        if (lockClock) {
            mLockClock.bringToFront();
            mLockClockUpdate.run();
        }

        // Black page behind a locked portrait video, the app background otherwise
        if (mState == STATE_EXPANDED && (mControls.isLocked() || mShortsMode)) {
            mSheet.setBackgroundColor(Color.BLACK);
        } else {
            mSheet.setBackgroundResource(R.drawable.bluetube_background);
        }

        // Shorts: no control panel (tap = pause, swipe = next/previous), only the thin progress line
        boolean shorts = mShortsMode && mState == STATE_EXPANDED;
        mControls.setMode(mState == STATE_MINI || shorts, mState == STATE_FULLSCREEN, mVideo != null ? mVideo.getTitle() : null);
        mShortsInfo.setVisibility(shorts ? View.VISIBLE : View.GONE);

        mSheet.setTranslationY(0);
        mSheet.setLayoutParams(sheetParams);
        mList.setLayoutParams(listParams);
        mPlayerHolder.setLayoutParams(holderParams);
        mPlayerHolder.bringToFront();
        updatePlayPauseIcon();
    }

    private boolean isLandscape() {
        if (mRoot.getWidth() > 0 && mRoot.getHeight() > 0) {
            return mRoot.getWidth() > mRoot.getHeight();
        }

        return mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Locks the chosen orientation until the phone is physically held that way, then follows the sensor again
     * (if the system auto-rotate is on).
     */
    private void requestOrientation(boolean landscape) {
        mActivity.setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        mAwaitedOrientation = landscape ? 1 : 2;

        if (mOrientationListener == null) {
            mOrientationListener = new OrientationEventListener(mActivity) {
                @Override
                public void onOrientationChanged(int degrees) {
                    if (degrees == ORIENTATION_UNKNOWN || mAwaitedOrientation == 0) {
                        return;
                    }

                    boolean isLandscape = (degrees >= 60 && degrees <= 120) || (degrees >= 240 && degrees <= 300);
                    boolean isPortrait = degrees <= 30 || degrees >= 330;

                    if ((mAwaitedOrientation == 1 && isLandscape) || (mAwaitedOrientation == 2 && isPortrait)) {
                        mAwaitedOrientation = 0;
                        disable();

                        if (Settings.System.getInt(mActivity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1) {
                            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                        }
                    }
                }
            };
        }

        mOrientationListener.enable();
    }

    @SuppressWarnings("deprecation")
    private com.liskovsoft.smartyoutubetv2.tv.ui.widgets.ClockOverlay mClock;

    private void updateLockClock() {
        mLockTime.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date()));
        String date = new java.text.SimpleDateFormat("EEE d MMM", mActivity.getResources().getConfiguration().locale).format(new java.util.Date());
        String battery = com.liskovsoft.smartyoutubetv2.tv.ui.widgets.ClockOverlay.batteryText(mActivity);
        mLockInfo.setText(battery != null ? "·  " + battery + "  ·  " + date : "·  " + date);
    }

    private void setSystemBarsHidden(boolean hidden) {
        View decor = mActivity.getWindow().getDecorView();

        if (!hidden) {
            android.view.WindowManager.LayoutParams params = mActivity.getWindow().getAttributes();
            if (params.screenBrightness >= 0) {
                params.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                mActivity.getWindow().setAttributes(params);
            }
        }

        // Full screen: the status bar is hidden, so the time (24 h) and the battery go in a corner
        if (mClock == null) {
            mClock = new com.liskovsoft.smartyoutubetv2.tv.ui.widgets.ClockOverlay(mActivity, true);
        }
        if (hidden && !mControls.isPanelVisible()) {
            mClock.show();
        } else {
            mClock.hide();
        }

        // The app content sits between the status and navigation bars (camera cutouts): in full screen the video
        // takes the whole screen instead (except the cutout itself, like YouTube)
        View content = mActivity.findViewById(android.R.id.content);
        if (content != null) {
            content.setFitsSystemWindows(!hidden);
            if (hidden) {
                content.setPadding(0, 0, 0, 0);
            } else {
                androidx.core.view.ViewCompat.requestApplyInsets(content);
            }
        }

        if (hidden && android.os.Build.VERSION.SDK_INT >= 30) {
            // Android 11+: the window keeps the bar margins unless told otherwise
            mActivity.getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = mActivity.getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        if (hidden) {
            if (mSavedSystemUi == -1) {
                mSavedSystemUi = decor.getSystemUiVisibility(); // the app's own flags, restored when leaving full screen
            }
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            mActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            if (mSavedSystemUi != -1) {
                decor.setSystemUiVisibility(mSavedSystemUi);
                mSavedSystemUi = -1;
            }

            // The app's own system bars mode (hidden navigation bar when the full screen mode setting is on)
            if (mActivity instanceof MotherActivity) {
                ((MotherActivity) mActivity).applyFullscreenModeIfNeeded();
            }
            if (mState == STATE_HIDDEN) {
                mActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    }

    private void togglePlay() {
        mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady());
        updatePlayPauseIcon();
        com.liskovsoft.smartyoutubetv2.common.misc.BackgroundPlaybackService.refresh();
    }

    private void updatePlayPauseIcon() {
        mMiniPlayPause.setImageResource(mPlayer.getPlayWhenReady() ? R.drawable.ic_m_pause : R.drawable.ic_m_play);
    }

    // ---------------------------------------------------------------------------------------------
    // MobilePlayerView.Listener
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onVideoChanged(Video video) {
        if (video == null) {
            return;
        }

        mVideo = video;
        updateShortsInfo();
        mMediaSession.setVideo(video);
        mMediaSession.setActive(true);
        mMiniTitle.setText(video.getTitle());
        mMiniSubtitle.setText(video.getSecondTitle());
        mAdapter.setItems(buildItems());
        mControls.setMode(mState == STATE_MINI, mState == STATE_FULLSCREEN, video.getTitle());
    }

    @Override
    public void onSuggestions(VideoGroup group) {
        if (group == null || group.getVideos() == null) {
            return;
        }

        if (group.getAction() == VideoGroup.ACTION_REPLACE) {
            List<Video> old = mGroups.remove(group.getId());
            if (old != null) {
                mUnique.removeAll(old);
            }
        }

        List<Video> videos = mGroups.get(group.getId());

        if (videos == null) {
            videos = new ArrayList<>();
            mGroups.put(group.getId(), videos);
        }

        for (Video video : group.getVideos()) {
            if (video != null && mUnique.add(video)) {
                videos.add(video);
            }
        }

        mAdapter.setItems(buildItems());
    }

    @Override
    public void onSuggestionsCleared() {
        clearSuggestionsList();
        restartSuggestionsTimeout(); // a new video (e.g. next/previous): skeletons until its suggestions arrive
        mAdapter.setItems(buildItems());
    }

    @Override
    public void onLoading(boolean loading) {
        mLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onFinished() {
        mMediaSession.setActive(false);
        setState(STATE_HIDDEN);
    }

    @Override
    public void onInfoChanged() {
        // Likes, subscribe state, avatar, metadata: refresh only the info block
        if (mAdapter.getItemCount() > 0 && mVideo != null) {
            mAdapter.notifyItemChanged(0);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // List (title + suggestions)
    // ---------------------------------------------------------------------------------------------

    private void onSuggestionsTimeout() {
        mSuggestionsTimedOut = true; // no suggestions for this video: drop the skeletons
        mAdapter.setItems(buildItems());
    }

    private void restartSuggestionsTimeout() {
        mSuggestionsTimedOut = false;
        mRoot.removeCallbacks(mSuggestionsTimeout);
        mRoot.postDelayed(mSuggestionsTimeout, SUGGESTIONS_TIMEOUT_MS);
    }

    private void clearSuggestionsList() {
        mGroups.clear();
        mUnique.clear();
        mLastScrollEndVideo = null;
    }

    private List<Object> buildItems() {
        List<Object> items = new ArrayList<>();

        if (mVideo != null) {
            items.add(INFO_HEADER); // title, channel, actions, description
        }

        boolean hasVideos = false;

        for (List<Video> videos : mGroups.values()) {
            // Chapters of the current video: a compact list under a "Chapters" title, not big video cards
            if (!videos.isEmpty() && videos.get(0).isChapter) {
                items.add(CHAPTERS_HEADER);
                items.addAll(videos);
                continue;
            }

            items.addAll(videos);
            hasVideos |= !videos.isEmpty();
        }

        // Suggestions not here yet: skeleton cards (like the home screen)
        if (!hasVideos && mVideo != null && !mSuggestionsTimedOut) {
            for (int i = 0; i < MobileSkeleton.COUNT; i++) {
                items.add(MobileSkeleton.ITEM);
            }
        }

        return items;
    }

    private void loadMoreIfNeeded() {
        LinearLayoutManager layoutManager = (LinearLayoutManager) mList.getLayoutManager();
        int total = mAdapter.getItemCount();

        if (layoutManager == null || total == 0 || layoutManager.findLastVisibleItemPosition() < total - LOAD_MORE_THRESHOLD) {
            return;
        }

        Video last = mAdapter.getLastVideo();

        if (last != null && last != mLastScrollEndVideo) {
            mLastScrollEndVideo = last;
            PlaybackPresenter.instance(mActivity).onScrollEnd(last);
        }
    }

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Object> mItems = new ArrayList<>();

        void setItems(List<Object> items) {
            mItems = items;
            notifyDataSetChanged();
        }

        Video getLastVideo() {
            for (int i = mItems.size() - 1; i >= 0; i--) {
                if (mItems.get(i) instanceof Video) {
                    return (Video) mItems.get(i);
                }
            }
            return null;
        }

        @Override
        public int getItemViewType(int position) {
            Object item = mItems.get(position);
            if (item == CHAPTERS_HEADER) {
                return 4;
            }
            if (item instanceof Video && ((Video) item).isChapter) {
                return 3;
            }
            return item == MobileSkeleton.ITEM ? 2 : item instanceof Video ? 1 : 0;
        }

        @Override
        public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof MobileSkeleton.Holder) {
                ((MobileSkeleton.Holder) holder).startPulse();
            }
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof MobileSkeleton.Holder) {
                ((MobileSkeleton.Holder) holder).stopPulse();
            }
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 2) {
                return MobileSkeleton.createHolder(parent);
            }

            if (viewType == 3) {
                return new ChapterHolder(parent);
            }

            if (viewType == 4) {
                TextView header = createText(17, Color.WHITE, true);
                header.setText(R.string.chapters);
                header.setPadding(dp(14), dp(14), dp(14), dp(6));
                header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(header) {
                };
            }

            if (viewType == 1) {
                return new VideoHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mobile_video, parent, false));
            }

            MobileWatchInfoView info = new MobileWatchInfoView(mActivity, mPlayer);
            info.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(info) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = mItems.get(position);

            if (holder instanceof ChapterHolder) {
                ((ChapterHolder) holder).bind((Video) item);
            } else if (holder instanceof VideoHolder) {
                ((VideoHolder) holder).bind((Video) item);
            } else if (holder.itemView instanceof MobileWatchInfoView) {
                ((MobileWatchInfoView) holder.itemView).bind(mVideo);
            }
        }
    }

    /**
     * One chapter: small thumbnail, name and start time. Tap = jump to that moment.
     */
    private class ChapterHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumb;
        private final TextView mTitle;
        private final TextView mTime;

        ChapterHolder(ViewGroup parent) {
            super(new LinearLayout(parent.getContext()));
            LinearLayout row = (LinearLayout) itemView;
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(6), dp(14), dp(6));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mThumb = new ImageView(parent.getContext());
            mThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mThumb.setBackgroundColor(0xFF16264A);
            row.addView(mThumb, new LinearLayout.LayoutParams(dp(112), dp(63)));
            LinearLayout texts = new LinearLayout(parent.getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12), 0, 0, 0);
            mTitle = createText(15, Color.WHITE, true);
            mTitle.setMaxLines(2);
            texts.addView(mTitle);
            mTime = createText(13, 0xFF7FB6FF, false);
            texts.addView(mTime);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        void bind(Video chapter) {
            mTitle.setText(chapter.getTitle());
            mTime.setText(chapter.badge);
            if (!mActivity.isDestroyed()) {
                Glide.with(mActivity).load(chapter.cardImageUrl).apply(ViewUtil.glideOptions()).into(mThumb);
            }
            itemView.setOnClickListener(v -> PlaybackPresenter.instance(mActivity).onSuggestionItemClicked(chapter));
        }
    }

    private class VideoHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumb;
        private final TextView mBadge;
        private final TextView mTitle;
        private final TextView mSubtitle;

        VideoHolder(View itemView) {
            super(itemView);
            mThumb = itemView.findViewById(R.id.mobile_item_thumb);
            mBadge = itemView.findViewById(R.id.mobile_item_badge);
            mTitle = itemView.findViewById(R.id.mobile_item_title);
            mSubtitle = itemView.findViewById(R.id.mobile_item_subtitle);
        }

        void bind(Video video) {
            mTitle.setText(video.getTitle());
            mSubtitle.setText(video.getSecondTitle());
            String badge = video.isLive ? mActivity.getString(R.string.badge_live) : video.badge;
            mBadge.setVisibility(TextUtils.isEmpty(badge) ? View.GONE : View.VISIBLE);
            mBadge.setText(badge);

            if (!mActivity.isDestroyed()) {
                Glide.with(mActivity).load(video.cardImageUrl).apply(ViewUtil.glideOptions()).into(mThumb);
            }

            PlaybackPresenter presenter = PlaybackPresenter.instance(mActivity);
            itemView.setOnClickListener(v -> {
                presenter.onSuggestionItemClicked(video);
                mList.scrollToPosition(0);
            });
            View.OnClickListener more = v -> presenter.onSuggestionItemLongClicked(video);
            itemView.setOnLongClickListener(v -> {
                more.onClick(v);
                return true;
            });
            itemView.findViewById(R.id.mobile_item_more).setOnClickListener(more);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Gestures: drag the video down (minimize / leave full screen) or up (full screen)
    // ---------------------------------------------------------------------------------------------

    /**
     * Shorts gestures: swipe up = next short, swipe down = previous one, tap = pause/play.
     */
    private boolean onShortsTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mShortsDownY = event.getRawY();
                mShortsDownX = event.getRawX();
                return true;
            case MotionEvent.ACTION_MOVE:
                mPlayerHolder.setTranslationY((event.getRawY() - mShortsDownY) * 0.35f); // follows the finger a little
                return true;
            case MotionEvent.ACTION_UP:
                float dy = event.getRawY() - mShortsDownY;
                float dx = event.getRawX() - mShortsDownX;
                mPlayerHolder.animate().translationY(0).setDuration(150).start();
                float threshold = mPlayerHolder.getHeight() * 0.12f;
                PlaybackPresenter presenter = PlaybackPresenter.instance(mActivity);

                if (dy < -threshold && Math.abs(dy) > Math.abs(dx)) {
                    presenter.onNextClicked();
                } else if (dy > threshold && Math.abs(dy) > Math.abs(dx)) {
                    // "previous" restarts a video watched for more than 10 s: then a second one goes back
                    boolean restartsFirst = mPlayer.getPositionMs() > 10_000;
                    presenter.onPreviousClicked();
                    if (restartsFirst) {
                        presenter.onPreviousClicked();
                    }
                } else if (Math.abs(dy) < dp(12) && Math.abs(dx) < dp(12)) {
                    togglePlay();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                mPlayerHolder.animate().translationY(0).setDuration(150).start();
                return true;
        }

        return true;
    }

    /**
     * Channel and title of the short, over the bottom of the video.
     */
    private void updateShortsInfo() {
        if (mVideo == null) {
            return;
        }

        String author = mVideo.author != null ? mVideo.author : mVideo.getAuthor();
        if (author != null && author.contains("\u2022")) {
            author = author.split("\u2022")[0].trim();
        }
        mShortsInfo.setText(author != null ? "@" + author.replace("@", "") + "\n" + mVideo.getTitle() : mVideo.getTitle());
    }

    private class PlayerHolder extends FrameLayout {
        private final int mTouchSlop;
        private float mDownX;
        private float mDownY;
        private boolean mDragging;

        PlayerHolder(Activity activity) {
            super(activity);
            mTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop() * 3;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (mState == STATE_MINI) {
                return event.getActionMasked() == MotionEvent.ACTION_UP; // a tap on the mini player expands it
            }

            if (mShortsMode && mState == STATE_EXPANDED) {
                return true; // Shorts: every touch is a swipe or a tap of the Shorts player itself
            }

            if (mControls.isLocked()) {
                return false; // locked full screen: no drag gestures
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // Full screen: a drag on the sides is brightness/volume (the controls handle it), not "leave full screen"
                mEdgeGesture = mState == STATE_FULLSCREEN && mControls.isFullscreenGesturesEnabled()
                        && mControls.isEdge(event.getX());
            }

            if (mEdgeGesture) {
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = event.getRawX();
                    mDownY = event.getRawY();
                    mDragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - mDownY;
                    float dx = event.getRawX() - mDownX;
                    if (Math.abs(dy) > mTouchSlop && Math.abs(dy) > 2 * Math.abs(dx)) {
                        mDragging = true;
                        return true;
                    }
                    break;
            }

            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (mState == STATE_MINI) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    setState(STATE_EXPANDED);
                }
                return true;
            }

            if (mShortsMode && mState == STATE_EXPANDED) {
                return onShortsTouch(event);
            }

            float dy = event.getRawY() - mDownY;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (mDragging && dy > 0 && mState == STATE_EXPANDED) {
                        mSheet.setTranslationY(dy); // the whole page follows the finger, like YouTube
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mDragging) {
                        mDragging = false;
                        float threshold = getHeight() * 0.25f;

                        if (dy > threshold) {
                            setState(mState == STATE_FULLSCREEN ? STATE_EXPANDED : STATE_MINI);
                        } else if (dy < -threshold && mState == STATE_EXPANDED) {
                            setState(STATE_FULLSCREEN);
                        } else {
                            mSheet.animate().translationY(0).setDuration(150).start();
                        }
                    }
                    return true;
            }

            return true;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private TextView createText(int sizeSp, int color, boolean bold) {
        TextView text = new TextView(mActivity);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) {
            text.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        return text;
    }

    private ImageButton createIconButton(int iconResId) {
        ImageButton button = new ImageButton(mActivity);
        button.setImageResource(iconResId);
        button.setBackground(null);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
