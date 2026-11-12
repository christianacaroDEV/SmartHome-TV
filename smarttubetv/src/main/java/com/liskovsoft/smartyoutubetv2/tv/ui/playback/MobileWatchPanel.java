package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.os.Looper;
import android.os.Handler;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
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
 * "Watch page" of the mobile mode (like the original YouTube app): in portrait the video is shrunk to a 16:9 area
 * at the top and the rest of the screen shows the title, the channel and the suggested videos.<br/>
 * Landscape = full screen player. Also adds the minimize (picture-in-picture) and the full screen buttons.
 */
class MobileWatchPanel {
    private static final int LOAD_MORE_THRESHOLD = 4;
    private static final float CONTROLS_SCALE = 0.55f;
    private final PlaybackActivity mActivity;
    private final FrameLayout mContent;
    private final RecyclerView mList;
    private final ImageButton mMinimizeButton;
    private final ImageButton mFullscreenButton;
    private final TextView mLockButton;
    private final TextView mUnlockButton;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideUnlock = this::hideUnlockButton;
    private boolean mLocked;
    private final PanelAdapter mAdapter = new PanelAdapter();
    private final LinkedHashMap<Integer, GroupData> mGroups = new LinkedHashMap<>();
    private String mTitle;
    private CharSequence mSubtitle;
    private boolean mPortrait;
    private boolean mInPip;
    private Video mLastScrollEndVideo;

    private static class GroupData {
        String title;
        final List<Video> videos = new ArrayList<>();
        final Set<Video> unique = new HashSet<>();
    }

    MobileWatchPanel(PlaybackActivity activity) {
        mActivity = activity;
        mContent = activity.findViewById(android.R.id.content);

        mList = new RecyclerView(activity);
        mList.setBackgroundResource(R.drawable.zemix21_background);
        mList.setLayoutManager(new LinearLayoutManager(activity));
        mList.setAdapter(mAdapter);
        mList.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    loadMoreIfNeeded();
                }
            }
        });
        mContent.addView(mList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mMinimizeButton = createRoundButton(R.drawable.ic_m_expand_more, R.string.mobile_minimize, Gravity.TOP | Gravity.START);
        mMinimizeButton.setOnClickListener(v -> mActivity.minimizeToPip());
        mFullscreenButton = createRoundButton(R.drawable.ic_m_fullscreen, R.string.mobile_fullscreen, Gravity.TOP | Gravity.END);
        mFullscreenButton.setOnClickListener(v -> mActivity.toggleOrientation());

        // "Lock" (full screen only): ignores every touch so the video can be watched freely
        mLockButton = createPillButton(R.drawable.ic_m_lock, R.string.mobile_pin);
        mLockButton.setOnClickListener(v -> setLocked(true));
        mUnlockButton = createPillButton(R.drawable.ic_m_lock_open, R.string.mobile_unpin);
        mUnlockButton.setVisibility(View.GONE);
        mUnlockButton.setOnClickListener(v -> setLocked(false));

        apply();
    }

    boolean isPortraitPanelShown() {
        return mPortrait && !mInPip;
    }

    /**
     * @return height (px) of the video area when the panel is shown, otherwise 0
     */
    int getVideoAreaHeight() {
        return isPortraitPanelShown() ? videoHeight() : 0;
    }

    void onConfigurationChanged() {
        if (mPortrait != (mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT)) {
            mLocked = false; // the lock only exists in full screen
            mUnlockButton.setVisibility(View.GONE);
        }

        apply();
    }

    private void hideUnlockButton() {
        mUnlockButton.setVisibility(View.GONE);
    }

    boolean isLocked() {
        return mLocked;
    }

    private void setLocked(boolean locked) {
        mLocked = locked;
        mHandler.removeCallbacks(mHideUnlock);
        mUnlockButton.setVisibility(View.GONE);

        if (locked) {
            mActivity.hideControls();
        }

        apply();
    }

    /**
     * A touch while the screen is locked: shows the unlock button for a moment.
     * @return true if the touch is on the unlock button (it must reach the button)
     */
    boolean onLockedTouch(float x, float y) {
        if (mUnlockButton.getVisibility() == View.VISIBLE) {
            int[] location = new int[2];
            mUnlockButton.getLocationOnScreen(location);

            if (x >= location[0] && x <= location[0] + mUnlockButton.getWidth() && y >= location[1] && y <= location[1] + mUnlockButton.getHeight()) {
                return true;
            }
        }

        mUnlockButton.setVisibility(View.VISIBLE);
        mUnlockButton.bringToFront();
        mHandler.removeCallbacks(mHideUnlock);
        mHandler.postDelayed(mHideUnlock, 2_500);

        return false;
    }

    void onPipChanged(boolean inPip) {
        mInPip = inPip;
        apply();
    }

    void setVideo(Video video) {
        if (video == null) {
            return;
        }

        mTitle = video.getTitleFull() != null ? video.getTitleFull() : video.getTitle();
        mSubtitle = video.getSecondTitleFull() != null ? video.getSecondTitleFull() : video.getSecondTitle();
        mAdapter.setItems(buildItems());
    }

    void clearSuggestions() {
        mGroups.clear();
        mLastScrollEndVideo = null;
        mAdapter.setItems(buildItems());
    }

    void updateSuggestions(VideoGroup group) {
        if (group == null || group.getVideos() == null) {
            return;
        }

        if (group.getAction() == VideoGroup.ACTION_REPLACE) {
            mGroups.remove(group.getId());
        }

        GroupData data = mGroups.get(group.getId());

        if (data == null) {
            data = new GroupData();
            data.title = group.getTitle();
            mGroups.put(group.getId(), data);
        }

        for (Video video : group.getVideos()) {
            if (video != null && data.unique.add(video)) {
                data.videos.add(video);
            }
        }

        mAdapter.setItems(buildItems());
    }

    // ---------------------------------------------------------------------------------------------

    private void apply() {
        mPortrait = mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        boolean showPanel = isPortraitPanelShown();

        // Video area: top 16:9 in portrait, the whole screen otherwise
        int height = showPanel ? videoHeight() : ViewGroup.LayoutParams.MATCH_PARENT;

        for (int id : new int[] {R.id.texture_view, R.id.playback_controls_fragment}) {
            View view = mActivity.findViewById(id);

            if (view != null && view.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
                params.height = height;
                params.gravity = showPanel ? Gravity.TOP : Gravity.CENTER;

                if (id == R.id.playback_controls_fragment) {
                    // The TV controls are too big for the small video area: lay them out on a bigger virtual screen
                    // and scale it down, so they fit like on a TV.
                    int width = mActivity.getResources().getDisplayMetrics().widthPixels;
                    params.width = showPanel ? Math.round(width / CONTROLS_SCALE) : ViewGroup.LayoutParams.MATCH_PARENT;
                    params.height = showPanel ? Math.round(videoHeight() / CONTROLS_SCALE) : height;
                    view.setPivotX(0);
                    view.setPivotY(0);
                    view.setScaleX(showPanel ? CONTROLS_SCALE : 1f);
                    view.setScaleY(showPanel ? CONTROLS_SCALE : 1f);
                }

                view.setLayoutParams(params);
            }
        }

        FrameLayout.LayoutParams listParams = (FrameLayout.LayoutParams) mList.getLayoutParams();
        listParams.topMargin = showPanel ? videoHeight() : 0;
        mList.setLayoutParams(listParams);
        mList.setVisibility(showPanel ? View.VISIBLE : View.GONE);

        mMinimizeButton.setVisibility(mInPip || mLocked ? View.GONE : View.VISIBLE);
        mFullscreenButton.setVisibility(mInPip || mLocked ? View.GONE : View.VISIBLE);
        mLockButton.setVisibility(!mInPip && !mLocked && !mPortrait ? View.VISIBLE : View.GONE);
        mLockButton.bringToFront();
        mFullscreenButton.setImageResource(mPortrait ? R.drawable.ic_m_fullscreen : R.drawable.ic_m_fullscreen_exit);

        mMinimizeButton.bringToFront();
        mFullscreenButton.bringToFront();
    }

    private TextView createPillButton(int iconResId, int textResId) {
        TextView button = new TextView(mActivity);
        button.setText(textResId);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setCompoundDrawablesWithIntrinsicBounds(iconResId, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(8));
        button.setPadding(dp(18), dp(10), dp(22), dp(10));

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(26));
        background.setColor(0x99000000);
        button.setBackground(background);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(28);
        mContent.addView(button, params);

        return button;
    }

    private int videoHeight() {
        int width = mActivity.getResources().getDisplayMetrics().widthPixels;
        return Math.round(width * 9f / 16f);
    }

    private ImageButton createRoundButton(int iconResId, int descriptionResId, int gravity) {
        float density = mActivity.getResources().getDisplayMetrics().density;
        int size = Math.round(44 * density);
        int margin = Math.round(10 * density);

        ImageButton button = new ImageButton(mActivity);
        button.setImageResource(iconResId);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(mActivity.getString(descriptionResId));
        int padding = Math.round(size * 0.2f);
        button.setPadding(padding, padding, padding, padding);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x99000000);
        button.setBackground(background);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, gravity);
        params.setMargins(margin, margin, margin, margin);
        mContent.addView(button, params);

        return button;
    }

    private List<Object> buildItems() {
        List<Object> items = new ArrayList<>();

        if (!TextUtils.isEmpty(mTitle)) {
            items.add(new String[] {mTitle, mSubtitle != null ? mSubtitle.toString() : ""});
        }

        boolean showHeaders = mGroups.size() > 1;

        for (GroupData group : mGroups.values()) {
            if (showHeaders && !TextUtils.isEmpty(group.title) && !group.videos.isEmpty()) {
                items.add(group.title);
            }

            items.addAll(group.videos);
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

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------------------------------------

    private class PanelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_INFO = 0;
        private static final int TYPE_HEADER = 1;
        private static final int TYPE_VIDEO = 2;
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
            return item instanceof Video ? TYPE_VIDEO : item instanceof String[] ? TYPE_INFO : TYPE_HEADER;
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            switch (viewType) {
                case TYPE_VIDEO:
                    return new VideoHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mobile_video, parent, false));
                case TYPE_INFO:
                    return new InfoHolder(createInfoView(parent));
                default:
                    return new RecyclerView.ViewHolder(createHeaderView(parent)) {
                    };
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = mItems.get(position);

            if (holder instanceof VideoHolder) {
                ((VideoHolder) holder).bind((Video) item);
            } else if (holder instanceof InfoHolder) {
                ((InfoHolder) holder).bind((String[]) item);
            } else {
                ((TextView) holder.itemView).setText((String) item);
            }
        }

        private TextView createHeaderView(ViewGroup parent) {
            TextView header = new TextView(parent.getContext());
            header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            header.setPadding(dp(14), dp(10), dp(14), dp(10));
            header.setTextColor(Color.WHITE);
            header.setTextSize(18);
            header.setTypeface(null, android.graphics.Typeface.BOLD);
            return header;
        }

        private ViewGroup createInfoView(ViewGroup parent) {
            android.widget.LinearLayout box = new android.widget.LinearLayout(parent.getContext());
            box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setPadding(dp(14), dp(12), dp(14), dp(14));

            TextView title = new TextView(parent.getContext());
            title.setTextColor(Color.WHITE);
            title.setTextSize(18);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setMaxLines(3);
            title.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(title);

            TextView subtitle = new TextView(parent.getContext());
            subtitle.setTextColor(Color.parseColor("#9FB3D9"));
            subtitle.setTextSize(13);
            subtitle.setPadding(0, dp(6), 0, 0);
            subtitle.setMaxLines(3);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(subtitle);

            return box;
        }
    }

    private class InfoHolder extends RecyclerView.ViewHolder {
        private final TextView mInfoTitle;
        private final TextView mInfoSubtitle;

        InfoHolder(ViewGroup box) {
            super(box);
            mInfoTitle = (TextView) box.getChildAt(0);
            mInfoSubtitle = (TextView) box.getChildAt(1);
        }

        void bind(String[] info) {
            mInfoTitle.setText(info[0]);
            mInfoSubtitle.setText(info[1]);
        }
    }

    private class VideoHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumb;
        private final TextView mBadge;
        private final ProgressBar mProgress;
        private final TextView mItemTitle;
        private final TextView mItemSubtitle;

        VideoHolder(View itemView) {
            super(itemView);
            mThumb = itemView.findViewById(R.id.mobile_item_thumb);
            mBadge = itemView.findViewById(R.id.mobile_item_badge);
            mProgress = itemView.findViewById(R.id.mobile_item_progress);
            mItemTitle = itemView.findViewById(R.id.mobile_item_title);
            mItemSubtitle = itemView.findViewById(R.id.mobile_item_subtitle);
        }

        void bind(Video video) {
            mItemTitle.setText(video.getTitle());
            mItemSubtitle.setText(video.getSecondTitle());

            String badge = video.hasNewContent ? mActivity.getString(R.string.badge_new_content)
                    : video.isLive ? mActivity.getString(R.string.badge_live)
                    : video.isShorts ? mActivity.getString(R.string.header_shorts).toUpperCase()
                    : video.badge;

            if (TextUtils.isEmpty(badge)) {
                mBadge.setVisibility(View.GONE);
            } else {
                mBadge.setText(badge);
                mBadge.setBackgroundColor(video.hasNewContent || video.isLive || video.isUpcoming ? 0xFFB31217 : 0xCC000000);
                mBadge.setVisibility(View.VISIBLE);
            }

            if (video.percentWatched > 0) {
                mProgress.setProgress(video.percentWatched < 1 ? 1 : Math.round(video.percentWatched));
                mProgress.setVisibility(View.VISIBLE);
            } else {
                mProgress.setVisibility(View.GONE);
            }

            if (!mActivity.isDestroyed()) {
                Glide.with(mActivity).load(video.cardImageUrl).apply(ViewUtil.glideOptions()).into(mThumb);
            }

            PlaybackPresenter presenter = PlaybackPresenter.instance(mActivity);
            itemView.setOnClickListener(v -> presenter.onSuggestionItemClicked(video));
            View.OnClickListener more = v -> presenter.onSuggestionItemLongClicked(video);
            itemView.setOnLongClickListener(v -> {
                more.onClick(v);
                return true;
            });
            itemView.findViewById(R.id.mobile_item_more).setOnClickListener(more);
        }
    }
}
