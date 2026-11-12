package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import com.liskovsoft.smartyoutubetv2.common.misc.MobileSettings;
import com.liskovsoft.smartyoutubetv2.common.misc.BackgroundPlaybackService;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.AccountChangeListener;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Home screen for phones and tablets (mobile mode). Touch friendly, portrait and landscape:<br/>
 * top bar (search, account) + category chips + vertical feed + bottom navigation.<br/>
 * It plugs into the same {@link BrowsePresenter} as the TV (Leanback) screen.
 */
public class MobileBrowseActivity extends MotherActivity implements BrowseView, AccountChangeListener {
    private static final int ACCENT_COLOR = 0xFF2F80FF;
    private static final int LOAD_MORE_THRESHOLD = 5;
    // Bottom navigation: section id -> (icon, label)
    private static final int[][] BOTTOM_NAV = {
            {MediaGroup.TYPE_HOME, R.drawable.ic_m_home, R.string.header_home},
            {MediaGroup.TYPE_SHORTS, R.drawable.ic_m_shorts, R.string.header_shorts},
            {MediaGroup.TYPE_SUBSCRIPTIONS, R.drawable.ic_m_subscriptions, R.string.header_subscriptions},
            {MediaGroup.TYPE_HISTORY, R.drawable.ic_m_library, R.string.header_history},
    };

    private BrowsePresenter mPresenter;
    private MobileWatchSheet mWatchSheet;
    private boolean mIsResumed;
    private RecyclerView mFeed;
    private FeedAdapter mAdapter;
    private LinearLayout mChips;
    private HorizontalScrollView mChipsScroll;
    private LinearLayout mBottomNav;
    private View mProgress;
    private View mMessageGroup;
    private TextView mMessage;
    private TextView mMessageAction;
    private ImageButton mAccountButton;
    private final List<BrowseSection> mSections = new ArrayList<>();
    private final Map<Integer, SectionContent> mContents = new HashMap<>();
    private int mCurrentSectionId = -1;
    private Video mLastScrollEndVideo;
    private boolean mIsFirstResume = true;
    private SwipeRefreshLayout mRefresh;
    private boolean mIsLoading;
    private final Runnable mDropStaleContent = this::dropStaleContent;

    /**
     * Content of a section: groups (rows) of videos or settings items.
     */
    private static class SectionContent {
        final LinkedHashMap<Integer, GroupContent> groups = new LinkedHashMap<>();
        List<SettingsItem> settings = new ArrayList<>();
        // The section is being reloaded: keep showing the old items until the new ones arrive (no blank screen)
        boolean stale;
    }

    private static final Object SKELETON = new Object();
    private static final Object LOADING_FOOTER = new Object();
    private static final int SKELETON_COUNT = 4;
    private static final long STALE_TIMEOUT_MS = 2_500;

    private static class GroupContent {
        String title;
        final List<Video> videos = new ArrayList<>();
        final Set<Video> unique = new HashSet<>();
    }

    @Override
    protected boolean showsMobileBackButton() {
        return false; // root screen
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_browse);

        mFeed = findViewById(R.id.mobile_feed);
        mChips = findViewById(R.id.mobile_chips);
        mChipsScroll = findViewById(R.id.mobile_chips_scroll);
        mBottomNav = findViewById(R.id.mobile_bottom_nav);
        mProgress = findViewById(R.id.mobile_progress);
        mMessageGroup = findViewById(R.id.mobile_message_group);
        mMessage = findViewById(R.id.mobile_message);
        mMessageAction = findViewById(R.id.mobile_message_action);
        mAccountButton = findViewById(R.id.mobile_btn_account);

        mAdapter = new FeedAdapter();
        mFeed.setLayoutManager(new LinearLayoutManager(this));
        mFeed.setAdapter(mAdapter);
        if (mFeed.getItemAnimator() instanceof SimpleItemAnimator) {
            // Changed cards are updated in place: no blink
            ((SimpleItemAnimator) mFeed.getItemAnimator()).setSupportsChangeAnimations(false);
        }

        // Pull to refresh, like YouTube
        mRefresh = findViewById(R.id.mobile_refresh);
        mRefresh.setColorSchemeColors(ACCENT_COLOR);
        mRefresh.setProgressBackgroundColorSchemeColor(0xFF0F1C3A);
        mRefresh.setOnRefreshListener(() -> {
            if (mPresenter != null && mCurrentSectionId != -1) {
                mLastScrollEndVideo = null;
                mPresenter.refresh();
            } else {
                mRefresh.setRefreshing(false);
            }
        });
        mFeed.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    loadMoreIfNeeded();
                }
            }
        });

        findViewById(R.id.mobile_btn_search).setOnClickListener(v -> SearchPresenter.instance(this).startSearch(null));
        mAccountButton.setOnClickListener(v -> startActivity(new Intent(this, MobileAccountActivity.class)));

        MediaServiceManager.instance().addAccountListener(this);
        updateAccountButton();

        // In-app player (expanded / mini / full screen), like YouTube. Every "play video" action of the app comes here.
        mWatchSheet = new MobileWatchSheet(this, mBottomNav);
        PlaybackPresenter.setInAppPlayerHost(this::openInApp);

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private boolean openInApp(Video video) {
        if (isDestroyed() || isFinishing() || mWatchSheet == null) {
            return false;
        }

        // e.g. a search result: bring the home screen (and its player) to the front
        if (!mIsResumed) {
            Intent intent = new Intent(this, MobileBrowseActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        }

        mWatchSheet.open(video);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (mWatchSheet != null && mWatchSheet.onBackPressed()) {
            return; // full screen -> expanded -> mini
        }

        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mWatchSheet != null) {
            mWatchSheet.onConfigurationChanged(newConfig);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        BackgroundPlaybackService.stop(this);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mWatchSheet == null || !mWatchSheet.isPlaying()) {
            return;
        }

        // Leaving the app (or locking the screen): keep the audio only if the user enabled it
        if (MobileSettings.isBackgroundAudioEnabled(this)) {
            BackgroundPlaybackService.start(this);
        } else {
            mWatchSheet.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsResumed = true;

        if (!mIsFirstResume && mPresenter != null) {
            mPresenter.onViewResumed();
        }

        mIsFirstResume = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsResumed = false;

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        PlaybackPresenter.setInAppPlayerHost(null);

        if (mWatchSheet != null) {
            mWatchSheet.release();
        }

        MediaServiceManager.instance().removeAccountListener(this);

        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
    }

    @Override
    public void onAccountChanged(Account account) {
        updateAccountButton();
    }

    private void updateAccountButton() {
        Account account = MediaServiceManager.instance().getSelectedAccount();

        if (account != null && account.getAvatarImageUrl() != null && !isDestroyed()) {
            Glide.with(this)
                    .load(account.getAvatarImageUrl())
                    .apply(ViewUtil.glideOptions())
                    .circleCrop()
                    .into(mAccountButton);
            mAccountButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        } else {
            mAccountButton.setImageResource(R.drawable.ic_m_account);
            mAccountButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // BrowseView
    // ---------------------------------------------------------------------------------------------

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null || indexOfSection(section.getId()) != -1) {
            return;
        }

        if (index < 0 || index > mSections.size()) {
            mSections.add(section);
        } else {
            mSections.add(index, section);
        }

        rebuildNavigation();
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }

        int index = indexOfSection(section.getId());

        if (index != -1) {
            mSections.remove(index);
            mContents.remove(section.getId());
            rebuildNavigation();
        }
    }

    @Override
    public void removeAllSections() {
        mSections.clear();
        mContents.clear();
        mCurrentSectionId = -1;
        mAdapter.setItems(new ArrayList<>());
        rebuildNavigation();
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        if (index >= 0 && index < mSections.size()) {
            selectSectionInternal(mSections.get(index).getId());
        }
    }

    @Override
    public void updateSection(VideoGroup group) {
        if (group == null) {
            return;
        }

        int sectionId = group.getSection() != null ? group.getSection().getId() : mCurrentSectionId;
        SectionContent content = getContent(sectionId);

        boolean hasVideos = group.getVideos() != null && !group.getVideos().isEmpty();

        // First new videos of a reloaded section: now the old ones can go (one single swap, no blank screen)
        if (content.stale && hasVideos && group.getAction() != VideoGroup.ACTION_REMOVE && group.getAction() != VideoGroup.ACTION_REMOVE_AUTHOR) {
            content.stale = false;
            content.groups.clear();
        }

        switch (group.getAction()) {
            case VideoGroup.ACTION_REPLACE:
                if (!hasVideos) {
                    // The presenter starts a section with an empty 'replace' group: keep the old items for now
                    content.stale = !content.groups.isEmpty();
                } else {
                    GroupContent replaced = new GroupContent();
                    replaced.title = group.getTitle();
                    addVideos(replaced, group.getVideos(), false);
                    content.groups.put(group.getId(), replaced);
                }
                break;
            case VideoGroup.ACTION_PREPEND:
                addVideos(getGroup(content, group), group.getVideos(), true);
                break;
            case VideoGroup.ACTION_REMOVE:
            case VideoGroup.ACTION_REMOVE_AUTHOR:
                removeVideos(content, group);
                break;
            case VideoGroup.ACTION_SYNC:
                break;
            case VideoGroup.ACTION_APPEND:
            default:
                addVideos(getGroup(content, group), group.getVideos(), false);
                break;
        }

        if (sectionId == mCurrentSectionId) {
            showMessage(null, null, null);
            showCurrentContent();
        }
    }

    @Override
    public void updateSection(SettingsGroup group) {
        if (group == null) {
            return;
        }

        int sectionId = group.getCategory() != null ? group.getCategory().getId() : mCurrentSectionId;
        SectionContent content = getContent(sectionId);
        content.settings = new ArrayList<>(group.getItems());

        if (content.stale) {
            content.stale = false;
            content.groups.clear();
        }

        if (sectionId == mCurrentSectionId) {
            showMessage(null, null, null);
            showCurrentContent();
        }
    }

    @Override
    public void clearSection(BrowseSection section) {
        if (section == null) {
            return;
        }

        SectionContent content = mContents.get(section.getId());

        if (content != null && !content.groups.isEmpty()) {
            content.stale = true; // replaced when the new items arrive
        } else {
            mContents.remove(section.getId());
        }

        if (section.getId() == mCurrentSectionId) {
            showCurrentContent();
        }
    }

    @Override
    public void selectSectionItem(int index) {
        // Not needed: no focus on touch screens
    }

    @Override
    public void selectSectionItem(Video item) {
        // Not needed: no focus on touch screens
    }

    @Override
    public void showError(ErrorFragmentData data) {
        if (data == null) {
            return;
        }

        mIsLoading = false;
        mRefresh.setRefreshing(false);
        SectionContent content = mContents.get(mCurrentSectionId);
        if (content != null) {
            content.groups.clear();
            content.stale = false;
        }
        mAdapter.setItems(new ArrayList<>());
        showMessage(data.getMessage(), data.getActionText(), data::onAction);
    }

    /**
     * No big spinner over the content: skeleton cards when the screen is empty, a small spinner at the end of the
     * list when loading more, the pull-to-refresh circle when refreshing.
     */
    @Override
    public void showProgressBar(boolean show) {
        mIsLoading = show;
        mProgress.setVisibility(View.GONE);
        mFeed.removeCallbacks(mDropStaleContent);

        if (!show) {
            mRefresh.setRefreshing(false);

            SectionContent content = mContents.get(mCurrentSectionId);
            if (content != null && content.stale) {
                // The new items normally come right after this: if nothing comes, the old ones were really removed
                mFeed.postDelayed(mDropStaleContent, STALE_TIMEOUT_MS);
            }
        }

        showCurrentContent();
    }

    @Override
    public boolean isProgressBarShowing() {
        return mIsLoading;
    }

    private void dropStaleContent() {
        SectionContent content = mContents.get(mCurrentSectionId);

        if (content != null && content.stale) {
            content.stale = false;
            content.groups.clear();
            showCurrentContent();
        }
    }

    /**
     * Shows the items of the current section (only the cards that changed are redrawn).
     */
    private void showCurrentContent() {
        List<Object> items = buildItems(mContents.get(mCurrentSectionId));

        if (items.isEmpty()) {
            if (mIsLoading && mMessageGroup.getVisibility() != View.VISIBLE) {
                for (int i = 0; i < SKELETON_COUNT; i++) {
                    items.add(SKELETON);
                }
            }
        } else if (mIsLoading && !mRefresh.isRefreshing()) {
            items.add(LOADING_FOOTER);
        }

        mAdapter.setItems(items);
    }

    @Override
    public void focusOnContent() {
        // Not needed: no focus on touch screens
    }

    @Override
    public boolean isEmpty() {
        return mSections.isEmpty() || mAdapter.getItemCount() == 0;
    }

    @Override
    public void updateBadge() {
        // Not used in the mobile UI
    }

    // ---------------------------------------------------------------------------------------------
    // Content
    // ---------------------------------------------------------------------------------------------

    private SectionContent getContent(int sectionId) {
        SectionContent content = mContents.get(sectionId);

        if (content == null) {
            content = new SectionContent();
            mContents.put(sectionId, content);
        }

        return content;
    }

    private GroupContent getGroup(SectionContent content, VideoGroup group) {
        GroupContent groupContent = content.groups.get(group.getId());

        if (groupContent == null) {
            groupContent = new GroupContent();
            groupContent.title = group.getTitle();
            content.groups.put(group.getId(), groupContent);
        }

        return groupContent;
    }

    private void addVideos(GroupContent group, List<Video> videos, boolean prepend) {
        if (videos == null) {
            return;
        }

        List<Video> fresh = new ArrayList<>();

        for (Video video : videos) {
            if (video != null && group.unique.add(video)) {
                fresh.add(video);
            }
        }

        group.videos.addAll(prepend ? 0 : group.videos.size(), fresh);
    }

    private void removeVideos(SectionContent content, VideoGroup group) {
        if (group.getVideos() == null) {
            return;
        }

        for (GroupContent groupContent : content.groups.values()) {
            for (Video toRemove : group.getVideos()) {
                if (groupContent.unique.remove(toRemove)) {
                    groupContent.videos.remove(toRemove);
                }
            }
        }
    }

    private List<Object> buildItems(SectionContent content) {
        List<Object> items = new ArrayList<>();

        if (content == null) {
            return items;
        }

        // Several groups = rows (home page): show the title of each group
        boolean showHeaders = content.groups.size() > 1;

        for (GroupContent group : content.groups.values()) {
            if (showHeaders && !TextUtils.isEmpty(group.title) && !group.videos.isEmpty()) {
                items.add(group.title);
            }

            items.addAll(group.videos);
        }

        items.addAll(content.settings);

        return items;
    }

    private void loadMoreIfNeeded() {
        LinearLayoutManager layoutManager = (LinearLayoutManager) mFeed.getLayoutManager();
        int total = mAdapter.getItemCount();

        if (layoutManager == null || total == 0 || layoutManager.findLastVisibleItemPosition() < total - LOAD_MORE_THRESHOLD) {
            return;
        }

        Video last = mAdapter.getLastVideo();

        if (last != null && last != mLastScrollEndVideo) {
            mLastScrollEndVideo = last;
            mPresenter.onScrollEnd(last);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation (chips + bottom bar)
    // ---------------------------------------------------------------------------------------------

    private int indexOfSection(int sectionId) {
        for (int i = 0; i < mSections.size(); i++) {
            if (mSections.get(i).getId() == sectionId) {
                return i;
            }
        }

        return -1;
    }

    private void selectSectionInternal(int sectionId) {
        if (sectionId == mCurrentSectionId) {
            return;
        }

        mCurrentSectionId = sectionId;
        mLastScrollEndVideo = null;
        showMessage(null, null, null);
        mRefresh.setRefreshing(false);
        mFeed.removeCallbacks(mDropStaleContent);

        // A new section: start at the top with its cached items (or skeletons) instead of animating from the old list
        mAdapter.replaceAll(buildItems(mContents.get(sectionId)));
        showCurrentContent();
        mFeed.scrollToPosition(0);
        updateNavigationState();

        mPresenter.onSectionFocused(sectionId);
    }

    private void onSectionTapped(int sectionId) {
        if (sectionId == mCurrentSectionId) {
            mFeed.scrollToPosition(0);
            mLastScrollEndVideo = null;
            mRefresh.setRefreshing(true);
            mPresenter.refresh(); // tapping the current section reloads it
        } else {
            selectSectionInternal(sectionId);
        }
    }

    private void rebuildNavigation() {
        // Chips: every enabled section
        mChips.removeAllViews();

        for (BrowseSection section : mSections) {
            TextView chip = new TextView(this);
            chip.setTag(section.getId());
            chip.setText(section.getTitle());
            chip.setTextSize(14);
            chip.setMaxLines(1);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            params.setMargins(dp(4), 0, dp(4), 0);
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> onSectionTapped(section.getId()));
            chip.setOnLongClickListener(v -> {
                mPresenter.onSectionLongPressed(section.getId());
                return true;
            });
            mChips.addView(chip);
        }

        // Bottom bar: the main sections that are enabled
        mBottomNav.removeAllViews();

        for (int[] entry : BOTTOM_NAV) {
            if (indexOfSection(entry[0]) == -1) {
                continue;
            }

            mBottomNav.addView(createBottomNavItem(entry[0], entry[1], entry[2]));
        }

        updateNavigationState();
    }

    private View createBottomNavItem(int sectionId, int iconResId, int labelResId) {
        LinearLayout item = new LinearLayout(this);
        item.setTag(sectionId);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        item.setOnClickListener(v -> onSectionTapped(sectionId));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResId);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        item.addView(icon);

        TextView label = new TextView(this);
        label.setText(labelResId);
        label.setTextSize(11);
        label.setMaxLines(1);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(2), 0, 0);
        item.addView(label);

        return item;
    }

    private void updateNavigationState() {
        for (int i = 0; i < mChips.getChildCount(); i++) {
            TextView chip = (TextView) mChips.getChildAt(i);
            boolean selected = chip.getTag() != null && (int) chip.getTag() == mCurrentSectionId;

            chip.setTextColor(selected ? Color.WHITE : Color.parseColor("#C5D2EE"));
            chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(17));
            background.setColor(selected ? ACCENT_COLOR : 0x1FFFFFFF);
            chip.setBackground(background);

            if (selected) {
                final View selectedChip = chip;
                mChipsScroll.post(() -> mChipsScroll.smoothScrollTo(Math.max(0, selectedChip.getLeft() - dp(24)), 0));
            }
        }

        for (int i = 0; i < mBottomNav.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) mBottomNav.getChildAt(i);
            boolean selected = item.getTag() != null && (int) item.getTag() == mCurrentSectionId;
            int color = selected ? Color.WHITE : Color.parseColor("#8FA3CC");

            ((ImageView) item.getChildAt(0)).setColorFilter(selected ? ACCENT_COLOR : color);
            TextView label = (TextView) item.getChildAt(1);
            label.setTextColor(color);
            label.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void showMessage(String message, String actionText, Runnable onAction) {
        if (TextUtils.isEmpty(message)) {
            mMessageGroup.setVisibility(View.GONE);
            return;
        }

        mMessage.setText(message);
        mMessageGroup.setVisibility(View.VISIBLE);

        if (TextUtils.isEmpty(actionText) || onAction == null) {
            mMessageAction.setVisibility(View.GONE);
        } else {
            mMessageAction.setText(actionText);
            mMessageAction.setVisibility(View.VISIBLE);
            mMessageAction.setOnClickListener(v -> onAction.run());
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------------------------------------
    // Feed
    // ---------------------------------------------------------------------------------------------

    private void onVideoClicked(Video video) {
        mPresenter.onVideoItemSelected(video);
        mPresenter.onVideoItemClicked(video);
    }

    private void onVideoLongClicked(Video video) {
        mPresenter.onVideoItemSelected(video);
        mPresenter.onVideoItemLongClicked(video);
    }

    private void onSettingsItemClicked(SettingsItem item) {
        String password = GeneralData.instance(this).getSettingsPassword();

        if (password == null) {
            item.onClick.run();
            return;
        }

        SimpleEditDialog.OnChange onChange = newValue -> {
            if (Utils.passwordMatch(password, newValue)) {
                item.onClick.run();
                return true;
            }
            return false;
        };

        if (password.matches("\\d+")) {
            SimpleEditDialog.showPin(this, getString(R.string.enter_settings_password), null, onChange);
        } else {
            SimpleEditDialog.showPassword(this, getString(R.string.enter_settings_password), null, onChange);
        }
    }

    private class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_VIDEO = 1;
        private static final int TYPE_SETTING = 2;
        private static final int TYPE_SKELETON = 3;
        private static final int TYPE_FOOTER = 4;
        private List<Object> mItems = new ArrayList<>();

        /**
         * Partial update: only the inserted/removed/changed cards are touched, the rest stay on screen as they are.
         */
        void setItems(List<Object> items) {
            List<Object> oldItems = mItems;
            mItems = items;

            if (oldItems.isEmpty() || items.isEmpty()) {
                notifyDataSetChanged();
                return;
            }

            // At the top of the list: stay there when new cards are inserted above (e.g. the "Continue watching" row)
            LinearLayoutManager layoutManager = (LinearLayoutManager) mFeed.getLayoutManager();
            boolean atTop = layoutManager != null && layoutManager.findFirstCompletelyVisibleItemPosition() <= 0;

            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return oldItems.size();
                }

                @Override
                public int getNewListSize() {
                    return items.size();
                }

                @Override
                public boolean areItemsTheSame(int oldPosition, int newPosition) {
                    Object oldItem = oldItems.get(oldPosition);
                    Object newItem = items.get(newPosition);
                    return oldItem == newItem || (oldItem.getClass() == newItem.getClass() && oldItem.equals(newItem));
                }

                @Override
                public boolean areContentsTheSame(int oldPosition, int newPosition) {
                    Object oldItem = oldItems.get(oldPosition);
                    Object newItem = items.get(newPosition);

                    if (oldItem instanceof Video && newItem instanceof Video) {
                        Video a = (Video) oldItem;
                        Video b = (Video) newItem;
                        return a == b || (TextUtils.equals(a.getTitle(), b.getTitle())
                                && TextUtils.equals(a.getSecondTitle(), b.getSecondTitle())
                                && TextUtils.equals(a.cardImageUrl, b.cardImageUrl)
                                && TextUtils.equals(a.badge, b.badge)
                                && a.percentWatched == b.percentWatched
                                && a.isLive == b.isLive
                                && a.hasNewContent == b.hasNewContent);
                    }

                    return oldItem != SKELETON; // skeleton -> skeleton: keep the pulsing view as it is
                }
            }, false).dispatchUpdatesTo(this);

            if (atTop) {
                mFeed.scrollToPosition(0);
            }
        }

        /**
         * A different list (e.g. another section): no diff animation.
         */
        void replaceAll(List<Object> items) {
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

            if (item == SKELETON) {
                return TYPE_SKELETON;
            }

            if (item == LOADING_FOOTER) {
                return TYPE_FOOTER;
            }

            return item instanceof Video ? TYPE_VIDEO : item instanceof SettingsItem ? TYPE_SETTING : TYPE_HEADER;
        }

        @Override
        public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof SkeletonHolder) {
                ((SkeletonHolder) holder).startPulse();
            }
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof SkeletonHolder) {
                ((SkeletonHolder) holder).stopPulse();
            }
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
                case TYPE_SETTING:
                    return new SettingHolder(createSettingRow(parent));
                case TYPE_SKELETON:
                    return new SkeletonHolder(createSkeleton(parent));
                case TYPE_FOOTER:
                    return new HeaderHolder(createFooter(parent));
                default:
                    return new HeaderHolder(createHeaderText(parent));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = mItems.get(position);

            if (holder instanceof VideoHolder) {
                ((VideoHolder) holder).bind((Video) item);
            } else if (holder instanceof SettingHolder) {
                ((SettingHolder) holder).bind((SettingsItem) item);
            } else if (item instanceof String) {
                ((TextView) holder.itemView).setText((String) item);
            }
        }

        /**
         * Grey card with the shape of a video card (thumbnail + two text lines), pulsing while the feed loads.
         */
        private View createSkeleton(ViewGroup parent) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setPadding(0, 0, 0, dp(16));

            View thumb = new View(parent.getContext()) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int width = MeasureSpec.getSize(widthMeasureSpec);
                    if (width <= 0) {
                        width = getResources().getDisplayMetrics().widthPixels;
                    }
                    setMeasuredDimension(width, width * 9 / 16);
                }
            };
            thumb.setBackgroundColor(0xFF16264A);
            card.addView(thumb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            card.addView(skeletonLine(parent, 0.85f, 16, 12));
            card.addView(skeletonLine(parent, 0.55f, 12, 8));
            return card;
        }

        private View skeletonLine(ViewGroup parent, float widthFraction, int heightDp, int topMarginDp) {
            View line = new View(parent.getContext());
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(4));
            shape.setColor(0xFF16264A);
            line.setBackground(shape);
            int width = Math.round((parent.getWidth() > 0 ? parent.getWidth() : getResources().getDisplayMetrics().widthPixels) * widthFraction);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(heightDp));
            params.setMargins(dp(14), dp(topMarginDp), 0, 0);
            line.setLayoutParams(params);
            return line;
        }

        private View createFooter(ViewGroup parent) {
            FrameLayout footer = new FrameLayout(parent.getContext());
            footer.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
            ProgressBar spinner = new ProgressBar(parent.getContext());
            footer.addView(spinner, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
            return footer;
        }

        private TextView createHeaderText(ViewGroup parent) {
            TextView header = new TextView(parent.getContext());
            header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            header.setPadding(dp(14), dp(10), dp(14), dp(10));
            header.setTextColor(Color.WHITE);
            header.setTextSize(19);
            header.setTypeface(null, android.graphics.Typeface.BOLD);
            return header;
        }

        private LinearLayout createSettingRow(ViewGroup parent) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(12), 0);

            ImageView icon = new ImageView(parent.getContext());
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
            icon.setColorFilter(Color.parseColor("#B8C7E6"));
            row.addView(icon);

            TextView title = new TextView(parent.getContext());
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            titleParams.setMargins(dp(16), 0, dp(8), 0);
            title.setLayoutParams(titleParams);
            title.setTextColor(Color.WHITE);
            title.setTextSize(17);
            title.setMaxLines(1);
            row.addView(title);

            ImageView chevron = new ImageView(parent.getContext());
            chevron.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            chevron.setImageResource(R.drawable.ic_m_chevron);
            chevron.setAlpha(0.5f);
            row.addView(chevron);

            return row;
        }
    }

    private static class HeaderHolder extends RecyclerView.ViewHolder {
        HeaderHolder(View itemView) {
            super(itemView);
        }
    }

    private static class SkeletonHolder extends RecyclerView.ViewHolder {
        private ObjectAnimator mPulse;

        SkeletonHolder(View itemView) {
            super(itemView);
        }

        void startPulse() {
            stopPulse();
            mPulse = ObjectAnimator.ofFloat(itemView, View.ALPHA, 1f, 0.45f);
            mPulse.setDuration(750);
            mPulse.setRepeatMode(ValueAnimator.REVERSE);
            mPulse.setRepeatCount(ValueAnimator.INFINITE);
            mPulse.start();
        }

        void stopPulse() {
            if (mPulse != null) {
                mPulse.cancel();
                mPulse = null;
            }
            itemView.setAlpha(1f);
        }
    }

    private class SettingHolder extends RecyclerView.ViewHolder {
        private final ImageView mIcon;
        private final TextView mTitle;

        SettingHolder(LinearLayout row) {
            super(row);
            mIcon = (ImageView) row.getChildAt(0);
            mTitle = (TextView) row.getChildAt(1);
        }

        void bind(SettingsItem item) {
            mTitle.setText(item.title);

            if (item.imageResId > 0) {
                mIcon.setImageDrawable(ContextCompat.getDrawable(MobileBrowseActivity.this, item.imageResId));
                mIcon.setVisibility(View.VISIBLE);
            } else {
                mIcon.setVisibility(View.INVISIBLE);
            }

            itemView.setOnClickListener(v -> onSettingsItemClicked(item));
        }
    }

    private class VideoHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumb;
        private final TextView mBadge;
        private final ProgressBar mProgress;
        private final TextView mTitle;
        private final TextView mSubtitle;

        VideoHolder(View itemView) {
            super(itemView);
            mThumb = itemView.findViewById(R.id.mobile_item_thumb);
            mBadge = itemView.findViewById(R.id.mobile_item_badge);
            mProgress = itemView.findViewById(R.id.mobile_item_progress);
            mTitle = itemView.findViewById(R.id.mobile_item_title);
            mSubtitle = itemView.findViewById(R.id.mobile_item_subtitle);
        }

        void bind(Video video) {
            mTitle.setText(video.getTitle());
            mSubtitle.setText(video.getSecondTitle());

            String badge = video.hasNewContent ? getString(R.string.badge_new_content)
                    : video.isLive ? getString(R.string.badge_live)
                    : video.isShorts ? getString(R.string.header_shorts).toUpperCase()
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

            // Same picture already on this card: don't load it again (that is what made the cards blink)
            boolean sameImage = video.cardImageUrl != null && video.cardImageUrl.equals(mThumb.getTag(R.id.mobile_item_thumb))
                    && mThumb.getDrawable() != null;

            if (!sameImage && !isDestroyed()) {
                mThumb.setTag(R.id.mobile_item_thumb, video.cardImageUrl);
                Glide.with(MobileBrowseActivity.this)
                        .load(video.cardImageUrl)
                        .apply(ViewUtil.glideOptions())
                        .transition(DrawableTransitionOptions.withCrossFade(180)) // the picture fades in
                        .into(mThumb);
            }

            itemView.setOnClickListener(v -> onVideoClicked(video));
            itemView.setOnLongClickListener(v -> {
                onVideoLongClicked(video);
                return true;
            });
            itemView.findViewById(R.id.mobile_item_more).setOnClickListener(v -> onVideoLongClicked(video));
        }
    }
}
