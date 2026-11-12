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

    private final Activity mActivity;
    private final FrameLayout mRoot;
    private final View mBottomNav;
    private final FrameLayout mSheet;
    private final PlayerHolder mPlayerHolder;
    private final MobilePlayerView mPlayer;
    private final ProgressBar mLoading;
    private final ImageButton mMinimizeButton;
    private final ImageButton mFullscreenButton;
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
        mSheet.setBackgroundResource(R.drawable.zemix21_background);
        mSheet.setClickable(true); // the home screen behind must not get the touches
        mSheet.setVisibility(View.GONE);
        mSheet.setElevation(dp(8));
        mRoot.addView(mSheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Suggestions (expanded state), below the video
        mList = new RecyclerView(activity);
        mList.setLayoutManager(new LinearLayoutManager(activity));
        mList.setAdapter(mAdapter);
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
        mPlayer.setBackgroundColor(Color.BLACK);
        mPlayerHolder.addView(mPlayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLoading = new ProgressBar(activity);
        mLoading.setVisibility(View.GONE);
        mPlayerHolder.addView(mLoading, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        mMinimizeButton = createRoundButton(R.drawable.ic_m_expand_more, Gravity.TOP | Gravity.START);
        mMinimizeButton.setOnClickListener(v -> setState(mState == STATE_FULLSCREEN ? STATE_EXPANDED : STATE_MINI));
        mFullscreenButton = createRoundButton(R.drawable.ic_m_fullscreen, Gravity.TOP | Gravity.END);
        mFullscreenButton.setOnClickListener(v -> setState(mState == STATE_FULLSCREEN ? STATE_EXPANDED : STATE_FULLSCREEN));
        mPlayer.setControllerVisibilityListener(visibility -> {
            mMinimizeButton.setVisibility(mState == STATE_MINI ? View.GONE : visibility);
            mFullscreenButton.setVisibility(mState == STATE_MINI ? View.GONE : visibility);
        });
        mSheet.addView(mPlayerHolder);

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
        clearSuggestionsList();
        mAdapter.setItems(buildItems());
        mPlayer.openVideo(video);
        mList.scrollToPosition(0);
        setState(isLandscape() ? STATE_FULLSCREEN : STATE_EXPANDED);
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
        mPlayer.release();
        setState(STATE_HIDDEN);
        mVideo = null;
    }

    public void release() {
        if (mOrientationListener != null) {
            mOrientationListener.disable();
        }

        mPlayer.release();
    }

    // ---------------------------------------------------------------------------------------------
    // States
    // ---------------------------------------------------------------------------------------------

    private void setState(int state) {
        int oldState = mState;
        mState = state;

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
                mPlayer.setUseController(false);
                mMinimizeButton.setVisibility(View.GONE);
                mFullscreenButton.setVisibility(View.GONE);
                break;
            case STATE_FULLSCREEN:
                sheetParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                sheetParams.bottomMargin = 0;
                holderParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                mMiniBar.setVisibility(View.GONE);
                mList.setVisibility(View.GONE);
                mPlayer.setUseController(true);
                mFullscreenButton.setImageResource(R.drawable.ic_m_fullscreen_exit);
                break;
            case STATE_EXPANDED:
            default:
                sheetParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                sheetParams.bottomMargin = 0;
                listParams.topMargin = videoHeight;
                mMiniBar.setVisibility(View.GONE);
                mList.setVisibility(View.VISIBLE);
                mPlayer.setUseController(true);
                mFullscreenButton.setImageResource(R.drawable.ic_m_fullscreen);
                break;
        }

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
    private void setSystemBarsHidden(boolean hidden) {
        View decor = mActivity.getWindow().getDecorView();

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
        mMiniTitle.setText(video.getTitle());
        mMiniSubtitle.setText(video.getSecondTitle());
        mAdapter.setItems(buildItems());
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
        mAdapter.setItems(buildItems());
    }

    @Override
    public void onLoading(boolean loading) {
        mLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onFinished() {
        setState(STATE_HIDDEN);
    }

    // ---------------------------------------------------------------------------------------------
    // List (title + suggestions)
    // ---------------------------------------------------------------------------------------------

    private void clearSuggestionsList() {
        mGroups.clear();
        mUnique.clear();
        mLastScrollEndVideo = null;
    }

    private List<Object> buildItems() {
        List<Object> items = new ArrayList<>();

        if (mVideo != null) {
            items.add(new CharSequence[] {mVideo.getTitleFull() != null ? mVideo.getTitleFull() : mVideo.getTitle(),
                    mVideo.getSecondTitleFull() != null ? mVideo.getSecondTitleFull() : mVideo.getSecondTitle()});
        }

        for (List<Video> videos : mGroups.values()) {
            items.addAll(videos);
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
            return mItems.get(position) instanceof Video ? 1 : 0;
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 1) {
                return new VideoHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mobile_video, parent, false));
            }

            LinearLayout box = new LinearLayout(parent.getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setPadding(dp(14), dp(12), dp(14), dp(16));
            TextView title = createText(18, Color.WHITE, true);
            title.setMaxLines(3);
            box.addView(title);
            TextView subtitle = createText(13, Color.parseColor("#9FB3D9"), false);
            subtitle.setPadding(0, dp(6), 0, 0);
            subtitle.setMaxLines(3);
            box.addView(subtitle);
            return new RecyclerView.ViewHolder(box) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = mItems.get(position);

            if (holder instanceof VideoHolder) {
                ((VideoHolder) holder).bind((Video) item);
            } else {
                CharSequence[] info = (CharSequence[]) item;
                LinearLayout box = (LinearLayout) holder.itemView;
                ((TextView) box.getChildAt(0)).setText(info[0]);
                ((TextView) box.getChildAt(1)).setText(info[1]);
            }
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

    private ImageButton createRoundButton(int iconResId, int gravity) {
        ImageButton button = new ImageButton(mActivity);
        button.setImageResource(iconResId);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x80000000);
        button.setBackground(background);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(40), dp(40), gravity);
        params.setMargins(dp(8), dp(8), dp(8), dp(8));
        mPlayerHolder.addView(button, params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
