package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Locale;

/**
 * Touch controls of the mobile in-app player (YouTube-like):<br/>
 * tap = show/hide, double tap on the left/right third = -10/+10 s, big play/pause, previous/next,
 * draggable seek bar with times, subtitles, speed, quality, full screen and (full screen only) the lock.
 */
@SuppressLint("ViewConstructor")
public class MobileControlsView extends FrameLayout {
    public interface Callback {
        void onMinimizeClicked();
        void onFullscreenClicked();
        void onLockChanged(boolean locked);

        /** The control panel appeared or went away (the full screen clock makes room for its buttons) */
        default void onPanelVisibilityChanged(boolean visible) {
        }
    }

    private static final int AUTO_HIDE_MS = 3_000;
    private static final int SEEK_STEP_MS = 10_000;
    private static final int TICK_MS = 500;
    private static final int ACCENT = 0xFF38E1FF;

    private final MobilePlayerView mPlayer;
    private final Callback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final View mPanel;
    private final LinearLayout mTopRow;
    private final TextView mTitle;
    private final ImageButton mPlayPause;
    private final ImageButton mSubtitles;
    private final ImageButton mFullscreen;
    private final ImageButton mLock;
    private final ImageButton mSpeed;
    private final ImageButton mQuality;
    private final LinearLayout mActionsRow;
    private final LinearLayout mSubtitlesAction;
    private final LevelBar mBrightnessBar;
    private final LevelBar mVolumeBar;
    private final TextView mTime;
    private final SeekBar mSeekBar;
    private final ProgressBar mMiniProgress;
    private final TextView mSeekBubble;
    private final TextView mUnlock;
    private final GestureDetector mGestures;
    private boolean mIsMini;
    private boolean mIsFullscreen;
    private boolean mLocked;
    private boolean mUserSeeking;
    private int mSeekAccumulatedMs;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            mHandler.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable mAutoHide = this::autoHide;
    private final Runnable mHideBubble = this::hideBubble;
    private final Runnable mHideUnlock = this::hideUnlock;

    private void autoHide() {
        if (mPlayer.getPlayWhenReady() && !mUserSeeking) {
            showPanel(false);
        }
    }

    private void hideBubble() {
        mSeekAccumulatedMs = 0;
        mSeekBubbleVisible(false);
    }

    private void hideUnlock() {
        mUnlockVisible(false);
    }

    public MobileControlsView(Context context, MobilePlayerView player, Callback callback) {
        super(context);
        mPlayer = player;
        mCallback = callback;

        // Panel with all the buttons over a dark scrim
        FrameLayout panel = new FrameLayout(context);
        panel.setBackgroundColor(0x80000000);
        mPanel = panel;
        addView(panel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top row: minimize | title | subtitles | speed | quality | lock
        LinearLayout top = new LinearLayout(context);
        mTopRow = top;
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(4), dp(4), 0);
        ImageButton minimize = iconButton(R.drawable.ic_m_expand_more, 44, R.string.mobile_cd_minimize);
        minimize.setOnClickListener(v -> mCallback.onMinimizeClicked());
        top.addView(minimize);
        mTitle = new TextView(context);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(15);
        mTitle.setMaxLines(1);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mSubtitles = iconButton(R.drawable.ic_m_cc, 44, R.string.mobile_cd_subtitles);
        mSubtitles.setOnClickListener(v -> pressButton(R.id.lb_control_closed_captioning));
        top.addView(mSubtitles);
        mSpeed = iconButton(R.drawable.ic_m_speed, 44, R.string.mobile_cd_speed);
        mSpeed.setOnClickListener(v -> pressButton(R.id.action_video_speed));
        top.addView(mSpeed);
        mQuality = iconButton(R.drawable.ic_m_settings, 44, R.string.mobile_cd_quality);
        mQuality.setOnClickListener(v -> pressButton(R.id.lb_control_high_quality));
        top.addView(mQuality);
        mLock = iconButton(R.drawable.ic_m_lock, 44, R.string.mobile_pin);
        mLock.setOnClickListener(v -> setLocked(true));
        top.addView(mLock);
        panel.addView(top, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Center row: previous | play/pause | next
        LinearLayout center = new LinearLayout(context);
        center.setGravity(Gravity.CENTER);
        ImageButton previous = iconButton(R.drawable.ic_m_skip_prev, 52, R.string.mobile_cd_previous);
        previous.setOnClickListener(v -> {
            PlaybackPresenter.instance(getContext()).onPreviousClicked();
            scheduleAutoHide();
        });
        center.addView(previous);
        mPlayPause = iconButton(R.drawable.ic_m_pause, 72, R.string.mobile_cd_play_pause);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(0x66000000);
        mPlayPause.setBackground(round);
        LinearLayout.LayoutParams playParams = (LinearLayout.LayoutParams) mPlayPause.getLayoutParams();
        playParams.setMargins(dp(28), 0, dp(28), 0);
        mPlayPause.setOnClickListener(v -> togglePlay());
        center.addView(mPlayPause);
        ImageButton next = iconButton(R.drawable.ic_m_skip_next, 52, R.string.mobile_cd_next);
        next.setOnClickListener(v -> {
            PlaybackPresenter.instance(getContext()).onNextClicked();
            scheduleAutoHide();
        });
        center.addView(next);
        panel.addView(center, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Bottom: time | full screen, then the seek bar
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeRow.setPadding(dp(14), 0, dp(4), 0);
        mTime = new TextView(context);
        mTime.setTextColor(Color.WHITE);
        mTime.setTextSize(13);
        timeRow.addView(mTime, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mFullscreen = iconButton(R.drawable.ic_m_fullscreen, 44, R.string.mobile_cd_fullscreen);
        mFullscreen.setOnClickListener(v -> mCallback.onFullscreenClicked());
        timeRow.addView(mFullscreen);
        bottom.addView(timeRow);
        mSeekBar = new SeekBar(context);
        mSeekBar.setMax(1000);
        if (Build.VERSION.SDK_INT >= 21) { // older Android: default colors
            mSeekBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            mSeekBar.setThumbTintList(ColorStateList.valueOf(ACCENT));
            mSeekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        }
        mSeekBar.setPadding(dp(14), dp(6), dp(14), dp(10));
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long duration = mPlayer.getDurationMs();
                    mTime.setText(formatTime(duration * progress / 1000) + " / " + formatTime(duration));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mUserSeeking = true;
                mHandler.removeCallbacks(mAutoHide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mUserSeeking = false;
                mPlayer.setPositionMs(mPlayer.getDurationMs() * seekBar.getProgress() / 1000);
                scheduleAutoHide();
            }
        });
        bottom.addView(mSeekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Full screen only: Subtitles · Speed · Quality and more · Pin, with names, under the seek bar
        mActionsRow = new LinearLayout(context);
        mActionsRow.setGravity(Gravity.CENTER);
        mActionsRow.setPadding(dp(8), 0, dp(8), dp(10));
        mSubtitlesAction = actionButton(R.drawable.ic_m_cc, R.string.mobile_cd_subtitles, v -> pressButton(R.id.lb_control_closed_captioning));
        mActionsRow.addView(mSubtitlesAction);
        mActionsRow.addView(actionButton(R.drawable.ic_m_speed, R.string.mobile_action_speed, v -> pressButton(R.id.action_video_speed)));
        mActionsRow.addView(actionButton(R.drawable.ic_m_settings, R.string.mobile_action_quality, v -> pressButton(R.id.lb_control_high_quality)));
        mActionsRow.addView(actionButton(R.drawable.ic_m_lock_open, R.string.mobile_pin, v -> setLocked(true)));
        mActionsRow.setVisibility(View.GONE);
        bottom.addView(mActionsRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(bottom, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Full screen: brightness bar (left) and volume bar (right), like YouTube / TV boxes
        mBrightnessBar = new LevelBar(context, R.drawable.ic_m_brightness, Color.WHITE);
        LayoutParams brightnessParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        brightnessParams.leftMargin = dp(26);
        brightnessParams.bottomMargin = dp(36); // above the time and the seek bar
        addView(mBrightnessBar, brightnessParams);
        mVolumeBar = new LevelBar(context, R.drawable.ic_m_volume, ACCENT);
        LayoutParams volumeParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        volumeParams.rightMargin = dp(26);
        volumeParams.bottomMargin = dp(36);
        addView(mVolumeBar, volumeParams);
        mBrightnessBar.setVisibility(View.GONE);
        mVolumeBar.setVisibility(View.GONE);

        // Thin progress line (mini player)
        mMiniProgress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        mMiniProgress.setMax(1000);
        if (Build.VERSION.SDK_INT >= 21) {
            mMiniProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
            mMiniProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        }
        addView(mMiniProgress, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM));

        // "+10 s" / "-10 s" bubble
        mSeekBubble = pill("");
        addView(mSeekBubble, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        mSeekBubbleVisible(false);

        // Unlock pill (locked full screen)
        mUnlock = pill(context.getString(R.string.mobile_unpin));
        mUnlock.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_m_lock_open, 0, 0, 0);
        mUnlock.setCompoundDrawablePadding(dp(8));
        mUnlock.setOnClickListener(v -> setLocked(false));
        LayoutParams unlockParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        unlockParams.bottomMargin = dp(28);
        addView(mUnlock, unlockParams);
        mUnlockVisible(false);

        mGestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mLocked) {
                    mUnlockVisible(true);
                    mHandler.removeCallbacks(mHideUnlock);
                    mHandler.postDelayed(mHideUnlock, 2_500);
                } else if (!mIsMini) {
                    showPanel(mPanel.getVisibility() != View.VISIBLE);
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                // Full screen, like YouTube: left side = brightness, right side = volume (not while locked)
                if (!mIsFullscreen || mLocked || mIsMini || e1 == null || !isEdge(e1.getX())) {
                    return false;
                }

                if (mAdjusting == 0 && Math.abs(e2.getY() - e1.getY()) > Math.abs(e2.getX() - e1.getX())) {
                    mAdjusting = e1.getX() < getWidth() / 2f ? ADJUST_BRIGHTNESS : ADJUST_VOLUME;
                    mAdjustStartY = e1.getY();
                    mAdjustStartValue = mAdjusting == ADJUST_BRIGHTNESS ? getBrightness() : getVolume();
                }

                if (mAdjusting != 0) {
                    // a drag of 70% of the height goes from 0 to 100%
                    float delta = (mAdjustStartY - e2.getY()) / (getHeight() * 0.7f);
                    float value = Math.max(0f, Math.min(1f, mAdjustStartValue + delta));
                    if (mAdjusting == ADJUST_BRIGHTNESS) {
                        setBrightness(value);
                    } else {
                        setVolume(value);
                    }
                    showAdjustBubble(value);
                    return true;
                }

                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mLocked || mIsMini) {
                    return false;
                }

                float third = getWidth() / 3f;

                if (e.getX() < third) {
                    seekBy(-SEEK_STEP_MS);
                } else if (e.getX() > 2 * third) {
                    seekBy(SEEK_STEP_MS);
                } else {
                    togglePlay();
                }
                return true;
            }
        });

        showPanel(false);
    }

    // ---------------------------------------------------------------------------------------------

    public void setMode(boolean mini, boolean fullscreen, CharSequence title) {
        mIsMini = mini;
        mIsFullscreen = fullscreen;

        if (mini && mLocked) {
            setLocked(false); // no lock on the mini player
        }

        mTitle.setText(fullscreen ? title : null);
        // Full screen: keep the buttons away from the edge (a swipe there shows the system bars)
        mTopRow.setPadding(dp(fullscreen ? 16 : 4), dp(fullscreen ? 14 : 8), dp(fullscreen ? 16 : 4), 0);
        // Full screen: the options move to the bottom row with their names; small player: icons at the top
        int topIcons = mini || fullscreen ? View.GONE : View.VISIBLE;
        mSubtitles.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        mSpeed.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        mQuality.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        // Portrait player: locks and keeps only the video (no suggestions)
        mLock.setVisibility(topIcons);
        mActionsRow.setVisibility(fullscreen && !mini ? View.VISIBLE : View.GONE);
        // Portrait (vertical full screen): icon above the name so the 4 fit; landscape: icon beside the name
        boolean portrait = getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        for (int i = 0; i < mActionsRow.getChildCount(); i++) {
            LinearLayout action = (LinearLayout) mActionsRow.getChildAt(i);
            action.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            action.setLayoutParams(portrait ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((TextView) action.getChildAt(1)).setPadding(portrait ? 0 : dp(8), portrait ? dp(4) : 0, 0, 0);
        }
        updateLevelBars();
        mFullscreen.setImageResource(fullscreen ? R.drawable.ic_m_fullscreen_exit : R.drawable.ic_m_fullscreen);
        mMiniProgress.setVisibility(mini ? View.VISIBLE : View.GONE);

        if (mini) {
            showPanel(false);
        }
    }

    public boolean isLocked() {
        return mLocked;
    }

    /**
     * Shows the controls for a moment (e.g. a new video was opened).
     */
    public void flash() {
        if (!mIsMini && !mLocked) {
            showPanel(true);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHandler.post(mTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacksAndMessages(null);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mIsMini) {
            return false; // the sheet expands the mini player itself
        }

        mGestures.onTouchEvent(event);

        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && mAdjusting != 0) {
            mAdjusting = 0;
            mHandler.removeCallbacks(mHideAdjust);
            mHandler.postDelayed(mHideAdjust, 700);
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------

    private void setLocked(boolean locked) {
        mLocked = locked;
        mHandler.removeCallbacks(mHideUnlock);
        mUnlockVisible(false);
        showPanel(!locked);
        mCallback.onLockChanged(locked);
    }

    // ---------------------------------------------------------------------------------------------
    // Brightness (left) and volume (right) by sliding a finger up/down in full screen
    // ---------------------------------------------------------------------------------------------

    private static final int ADJUST_BRIGHTNESS = 1;
    private static final int ADJUST_VOLUME = 2;
    private int mAdjusting;
    private float mAdjustStartY;
    private float mAdjustStartValue;
    private final Runnable mHideAdjust = this::updateLevelBars;

    /**
     * Both bars are shown with the controls in full screen; while sliding a finger, the one being changed.
     */
    private void updateLevelBars() {
        boolean withPanel = mIsFullscreen && !mIsMini && !mLocked && mPanel.getVisibility() == View.VISIBLE;
        boolean brightness = withPanel || mAdjusting == ADJUST_BRIGHTNESS;
        boolean volume = withPanel || mAdjusting == ADJUST_VOLUME;

        mBrightnessBar.setVisibility(brightness ? View.VISIBLE : View.GONE);
        mVolumeBar.setVisibility(volume ? View.VISIBLE : View.GONE);

        if (brightness) {
            mBrightnessBar.setLevel(getBrightness());
            mBrightnessBar.bringToFront();
        }
        if (volume) {
            mVolumeBar.setLevel(getVolume());
            mVolumeBar.bringToFront();
        }
    }

    /**
     * The outer 40% of each side: the middle keeps its own gestures (drag down = leave full screen).
     */
    public boolean isEdge(float x) {
        return x < getWidth() * 0.4f || x > getWidth() * 0.6f;
    }

    public boolean isFullscreenGesturesEnabled() {
        return mIsFullscreen && !mLocked && !mIsMini;
    }

    private float getBrightness() {
        android.app.Activity activity = (android.app.Activity) getContext();
        float value = activity.getWindow().getAttributes().screenBrightness;

        if (value < 0) { // follows the system: start from the system level
            try {
                value = android.provider.Settings.System.getInt(getContext().getContentResolver(),
                        android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
            } catch (android.provider.Settings.SettingNotFoundException e) {
                value = 0.5f;
            }
        }

        return value;
    }

    private void setBrightness(float value) {
        // Only this window (like YouTube): the phone setting doesn't change and it is back when leaving the player
        android.view.Window window = ((android.app.Activity) getContext()).getWindow();
        android.view.WindowManager.LayoutParams params = window.getAttributes();
        params.screenBrightness = Math.max(0.01f, value);
        window.setAttributes(params);
    }

    private android.media.AudioManager audio() {
        return (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
    }

    private float getVolume() {
        android.media.AudioManager audio = audio();
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        return max > 0 ? audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (float) max : 0;
    }

    private void setVolume(float value) {
        android.media.AudioManager audio = audio();
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(value * max), 0);
    }

    private void showAdjustBubble(float value) {
        mHandler.removeCallbacks(mHideAdjust);
        updateLevelBars();
        (mAdjusting == ADJUST_BRIGHTNESS ? mBrightnessBar : mVolumeBar).setLevel(value);
        if (mPanel.getVisibility() == View.VISIBLE) {
            scheduleAutoHide(); // keep the controls while adjusting
        }
    }

    /**
     * Full screen option with its name: icon + "Subtitles" / "Speed" / "Quality and more" / "Pin".
     */
    private LinearLayout actionButton(int iconResId, int labelResId, View.OnClickListener listener) {
        LinearLayout button = new LinearLayout(getContext());
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setClickable(true);
        button.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setOnClickListener(listener);
        button.setContentDescription(getContext().getString(labelResId));

        ImageView icon = new ImageView(getContext());
        icon.setImageResource(iconResId);
        button.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView label = new TextView(getContext());
        label.setText(labelResId);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        button.addView(label);
        return button;
    }

    /**
     * Vertical level bar: icon, a white/blue column that fills from the bottom, and the percentage.
     */
    private class LevelBar extends LinearLayout {
        private final View mFill;
        private final TextView mPercent;
        private final int mTrackHeight = dp(110);

        LevelBar(Context context, int iconResId, int color) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setMinimumWidth(dp(48)); // room for "100%"

            ImageView icon = new ImageView(context);
            icon.setImageResource(iconResId);
            addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

            FrameLayout track = new FrameLayout(context);
            GradientDrawable trackShape = new GradientDrawable();
            trackShape.setCornerRadius(dp(3));
            trackShape.setColor(0x55FFFFFF);
            track.setBackground(trackShape);
            LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(dp(6), mTrackHeight);
            trackParams.setMargins(0, dp(10), 0, dp(8));
            addView(track, trackParams);

            mFill = new View(context);
            GradientDrawable fillShape = new GradientDrawable();
            fillShape.setCornerRadius(dp(3));
            fillShape.setColor(color);
            mFill.setBackground(fillShape);
            track.addView(mFill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));

            mPercent = new TextView(context);
            mPercent.setTextColor(Color.WHITE);
            mPercent.setTextSize(12);
            addView(mPercent);
        }

        void setLevel(float value) {
            float level = Math.max(0f, Math.min(1f, value));
            ViewGroup.LayoutParams params = mFill.getLayoutParams();
            params.height = Math.round(mTrackHeight * level);
            mFill.setLayoutParams(params);
            mPercent.setText(Math.round(level * 100) + "%");
        }
    }

    public boolean isPanelVisible() {
        return mPanel.getVisibility() == View.VISIBLE;
    }

    private void showPanel(boolean show) {
        boolean visible = show && !mLocked;
        mPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        mCallback.onPanelVisibilityChanged(visible);
        updateProgress();
        updateLevelBars();

        if (show) {
            scheduleAutoHide();
        } else {
            mHandler.removeCallbacks(mAutoHide);
        }
    }

    private void scheduleAutoHide() {
        mHandler.removeCallbacks(mAutoHide);
        mHandler.postDelayed(mAutoHide, AUTO_HIDE_MS);
    }

    private void togglePlay() {
        boolean play = !mPlayer.getPlayWhenReady();
        PlaybackPresenter presenter = PlaybackPresenter.instance(getContext());

        if (play) {
            presenter.onPlayClicked();
        } else {
            presenter.onPauseClicked();
        }

        mPlayer.setPlayWhenReady(play);
        updateProgress();
        scheduleAutoHide();
    }

    private void seekBy(int deltaMs) {
        long duration = mPlayer.getDurationMs();
        long position = Math.max(0, Math.min(duration > 0 ? duration : Long.MAX_VALUE, mPlayer.getPositionMs() + deltaMs));
        mPlayer.setPositionMs(position);

        // Consecutive double taps add up, like YouTube ("+20 s", "+30 s"...)
        mSeekAccumulatedMs = Integer.signum(mSeekAccumulatedMs) == Integer.signum(deltaMs) ? mSeekAccumulatedMs + deltaMs : deltaMs;
        mSeekBubble.setText(String.format(Locale.US, "%s%d s", mSeekAccumulatedMs > 0 ? "+" : "−", Math.abs(mSeekAccumulatedMs / 1000)));
        LayoutParams params = (LayoutParams) mSeekBubble.getLayoutParams();
        params.gravity = Gravity.CENTER_VERTICAL | (deltaMs > 0 ? Gravity.END : Gravity.START);
        params.setMargins(dp(40), 0, dp(40), 0);
        mSeekBubble.setLayoutParams(params);
        mSeekBubbleVisible(true);
        mHandler.removeCallbacks(mHideBubble);
        mHandler.postDelayed(mHideBubble, 900);
        updateProgress();
    }

    private void pressButton(int buttonId) {
        PlaybackPresenter.instance(getContext()).onButtonClicked(buttonId, mPlayer.getButtonState(buttonId));
        scheduleAutoHide();
    }

    private void updateProgress() {
        long duration = mPlayer.getDurationMs();
        long position = mPlayer.getPositionMs();
        int progress = duration > 0 ? (int) (position * 1000 / duration) : 0;

        if (!mUserSeeking) {
            mSeekBar.setProgress(progress);
            mTime.setText(formatTime(position) + " / " + formatTime(duration));
        }

        mMiniProgress.setProgress(progress);
        mPlayPause.setImageResource(mPlayer.getPlayWhenReady() ? R.drawable.ic_m_pause : R.drawable.ic_m_play);
        int subtitlesColor = mPlayer.getButtonState(R.id.lb_control_closed_captioning) == 1 ? ACCENT : Color.WHITE;
        mSubtitles.setColorFilter(subtitlesColor);
        ((ImageView) mSubtitlesAction.getChildAt(0)).setColorFilter(subtitlesColor);
    }

    private static String formatTime(long ms) {
        if (ms <= 0) {
            return "0:00";
        }

        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private ImageButton iconButton(int iconResId, int sizeDp, int descriptionResId) {
        ImageButton button = new ImageButton(getContext());
        button.setContentDescription(getContext().getString(descriptionResId)); // read by TalkBack
        button.setImageResource(iconResId);
        button.setBackground(null);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int padding = Math.round(sizeDp * 0.22f);
        button.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return button;
    }

    private TextView pill(String text) {
        TextView pill = new TextView(getContext());
        pill.setText(text);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(15);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(18), dp(10), dp(20), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(24));
        background.setColor(0x99000000);
        pill.setBackground(background);
        return pill;
    }

    private void mSeekBubbleVisible(boolean visible) {
        mSeekBubble.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void mUnlockVisible(boolean visible) {
        mUnlock.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
