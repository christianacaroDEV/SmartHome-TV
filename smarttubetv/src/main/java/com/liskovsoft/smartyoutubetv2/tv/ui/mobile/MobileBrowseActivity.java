package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import com.liskovsoft.smartyoutubetv2.common.misc.MobileSettings;
import com.liskovsoft.smartyoutubetv2.common.misc.BlueTubeSync;
import com.liskovsoft.smartyoutubetv2.common.misc.BackgroundPlaybackService;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
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
            {MobileBrowseActivity.MY_TOPICS_ID, R.drawable.ic_m_star, R.string.mobile_topics_title},
            {MediaGroup.TYPE_SUBSCRIPTIONS, R.drawable.ic_m_subscriptions, R.string.header_subscriptions},
            {MediaGroup.TYPE_HISTORY, R.drawable.ic_m_library, R.string.header_history},
            {MediaGroup.TYPE_SETTINGS, R.drawable.ic_m_settings, R.string.header_settings},
    };
    /**
     * Sections that live in the bottom bar only (not repeated as chips at the top). Shorts stays at the top.
     */
    private static final int[] BOTTOM_ONLY = {MobileBrowseActivity.MY_TOPICS_ID, MediaGroup.TYPE_SETTINGS};

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
    private TextView mProfileButton;
    private boolean mIsLoading;
    private boolean mTopicsLoading;
    private int mTopicsPending;
    private final List<Disposable> mTopicActions = new ArrayList<>();
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

    /**
     * "My topics": a section of this screen only (not from the presenter), made of one search per favorite topic.
     */
    private static final int MY_TOPICS_ID = -2001;
    private static final int WATCH_LATER_GROUP_ID = -2002;
    private static final int TOPIC_GROUP_BASE = -3000;
    private static final int VIDEOS_PER_TOPIC = 4;
    private static final Object EDIT_TOPICS = new Object();

    /**
     * Title of a "My topics" row, with "See all".
     */
    private static class TopicHeader {
        final String title;
        final String query;

        TopicHeader(String title, String query) {
            this.title = title;
            this.query = query;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof TopicHeader && TextUtils.equals(title, ((TopicHeader) obj).title);
        }

        @Override
        public int hashCode() {
            return title != null ? title.hashCode() : 0;
        }
    }

    private static final Object SKELETON = new Object();
    private static final Object LOADING_FOOTER = new Object();
    private static final int SKELETON_COUNT = 4;
    private static final long STALE_TIMEOUT_MS = 2_500;

    private static class GroupContent {
        String title;
        String query; // "My topics" rows: the search behind "See all"
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
        // Phone in portrait: one column. Landscape/tablets: a grid of cards (2-3 columns) like YouTube
        GridLayoutManager grid = new GridLayoutManager(this, feedColumns(getResources().getConfiguration()));
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                // Video cards take one cell, titles/buttons/skeletons/settings the whole row
                return mAdapter.getItemViewType(position) == FeedAdapter.TYPE_VIDEO ? 1 : grid.getSpanCount();
            }
        });
        mFeed.setLayoutManager(grid);
        mFeed.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (grid.getSpanCount() > 1 && position != RecyclerView.NO_POSITION && mAdapter.getItemViewType(position) == FeedAdapter.TYPE_VIDEO) {
                    outRect.left = dp(6);
                    outRect.right = dp(6);
                }
            }
        });
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
            if (mCurrentSectionId == MY_TOPICS_ID) {
                loadMyTopics();
            } else if (mPresenter != null && mCurrentSectionId != -1) {
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
        addProfileButton();

        MediaServiceManager.instance().addAccountListener(this);
        updateAccountButton();

        // In-app player (expanded / mini / full screen), like YouTube. Every "play video" action of the app comes here.
        mWatchSheet = new MobileWatchSheet(this, mBottomNav);
        final int feedPaddingBottom = mFeed.getPaddingBottom();
        mWatchSheet.setOnStateChangedListener((state, miniHeight) -> mFeed.setPadding(mFeed.getPaddingLeft(), mFeed.getPaddingTop(),
                mFeed.getPaddingRight(), feedPaddingBottom + (state == MobileWatchSheet.STATE_MINI ? miniHeight : 0)));
        PlaybackPresenter.setInAppPlayerHost(this::openInApp);
        setOnAppHiddenListener(this::onAppHidden);

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();

        // Several family profiles: "Who's watching?" first. Then, for a profile without topics yet, its favorite topics.
        if (savedInstanceState == null && MobileProfiles.hasSeveral(this)) {
            mFeed.postDelayed(() -> {
                if (!isFinishing()) {
                    MobileProfiles.showPicker(this, true, this::onProfileChanged);
                }
            }, 600);
        } else {
            askTopicsIfNeeded();
        }

        // Same account on the TV/another phone: bring its profiles and favorites
        BlueTubeSync.pull(this, this::onAccountSynced);
    }

    private void onAccountSynced() {
        if (isFinishing()) {
            return;
        }

        updateProfileButton();
        mContents.remove(MY_TOPICS_ID);

        if (mCurrentSectionId == MY_TOPICS_ID) {
            loadMyTopics();
        }
    }

    private void askTopicsIfNeeded() {
        if (MobileTopics.shouldAsk(this)) {
            mFeed.postDelayed(() -> {
                if (!isFinishing() && mIsResumed && MobileTopics.shouldAsk(this)) {
                    MobileTopics.showPicker(this, topics -> selectSectionInternal(MY_TOPICS_ID));
                }
            }, 3_000);
        }
    }

    /**
     * Another family member: its own Favorites and Watch later.
     */
    private void onProfileChanged() {
        updateProfileButton();
        mContents.remove(MY_TOPICS_ID);

        if (mCurrentSectionId == MY_TOPICS_ID) {
            loadMyTopics();
        }

        askTopicsIfNeeded();
    }

    private void updateProfileButton() {
        if (mProfileButton == null) {
            return;
        }

        MobileProfiles.Profile profile = MobileProfiles.getCurrent(this);
        mProfileButton.setText(MobileProfiles.initial(profile.name));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(profile.color);
        mProfileButton.setBackground(circle);
        mProfileButton.setContentDescription(profile.name);
    }

    /**
     * Small circle with the initial of the current profile, left of the account button: tap = "Who's watching?".
     */
    private void addProfileButton() {
        ViewGroup topBar = (ViewGroup) mAccountButton.getParent();
        mProfileButton = new TextView(this);
        mProfileButton.setTextColor(Color.WHITE);
        mProfileButton.setTextSize(15);
        mProfileButton.setTypeface(null, android.graphics.Typeface.BOLD);
        mProfileButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
        params.setMargins(dp(4), 0, dp(6), 0);
        topBar.addView(mProfileButton, topBar.indexOfChild(mAccountButton), params);
        mProfileButton.setOnClickListener(v -> MobileProfiles.showPicker(this, true, this::onProfileChanged));
        updateProfileButton();
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

        // Like YouTube: from another tab BACK goes to Home first
        if (!mSections.isEmpty() && mCurrentSectionId != mSections.get(0).getId()) {
            selectSectionInternal(mSections.get(0).getId());
            return;
        }

        // Home: send the app to the background instead of destroying it (the mini player and the audio survive,
        // like Android 12+ does by itself; on Android 11 and older finishing the root activity killed the video)
        if (!moveTaskToBack(true)) {
            super.onBackPressed();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mFeed != null && mFeed.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) mFeed.getLayoutManager()).setSpanCount(feedColumns(newConfig));
            mFeed.invalidateItemDecorations();
        }

        if (mWatchSheet != null) {
            mWatchSheet.onConfigurationChanged(newConfig);
        }
    }

    /**
     * About 340dp per card: 1 column on phones in portrait, 2-3 in landscape or on tablets.
     */
    private static int feedColumns(android.content.res.Configuration config) {
        return Math.max(1, Math.min(4, config.screenWidthDp / 340));
    }

    @Override
    protected void onStart() {
        super.onStart();
        BackgroundPlaybackService.stop(this);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Another screen of the app is on top (search, account, a channel...): keep playing, like YouTube
        if (isAppVisible()) {
            BackgroundPlaybackService.stop(this);
        }
    }

    /**
     * The whole app went to the background (home button, screen locked), from this screen or any other one.
     * Keep the audio only if the user enabled it. The keep-alive service was started in onPause while the app
     * was still visible (Android 12+ doesn't allow starting it from the background).
     */
    private void onAppHidden() {
        if (mWatchSheet == null || !mWatchSheet.isPlaying()) {
            return;
        }

        if (MobileSettings.isBackgroundAudioEnabled(this)) {
            BackgroundPlaybackService.start(this); // no-op if already running; tried in case onPause didn't
        } else {
            mWatchSheet.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsResumed = true;
        BackgroundPlaybackService.stop(this);

        if (!mIsFirstResume && mPresenter != null && mCurrentSectionId != MY_TOPICS_ID) {
            mPresenter.onViewResumed();
        }

        if (mCurrentSectionId == MY_TOPICS_ID) {
            updateWatchLaterRow(); // something may have been saved on the watch page
        }

        mIsFirstResume = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsResumed = false;

        // Background playback: start the keep-alive service while the app is still visible (Android 12+ rule).
        // If the app stays in the foreground (a dialog, search...) onStart/onResume stops it again.
        if (mWatchSheet != null && mWatchSheet.isPlaying() && !isChangingConfigurations()
                && MobileSettings.isBackgroundAudioEnabled(this)) {
            BackgroundPlaybackService.start(this);
        }

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        PlaybackPresenter.setInAppPlayerHost(null);
        setOnAppHiddenListener(null);
        disposeTopicActions();

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

        if (sectionId == MY_TOPICS_ID) {
            return; // local section, never filled by the presenter
        }

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

        // Signed in and the section simply has nothing yet (new account): a friendly message, not a network error
        if (YouTubeSignInService.instance().isSigned()) {
            int emptyMessage = emptyMessageOf(mCurrentSectionId);

            if (emptyMessage != 0) {
                showMessage(getString(emptyMessage), getString(R.string.mobile_empty_explore),
                        () -> SearchPresenter.instance(this).startSearch(null));
                return;
            }
        }

        showMessage(data.getMessage(), data.getActionText(), data::onAction);
    }

    private static int emptyMessageOf(int sectionId) {
        switch (sectionId) {
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.string.mobile_empty_subscriptions;
            case MediaGroup.TYPE_HISTORY:
                return R.string.mobile_empty_history;
            case MediaGroup.TYPE_USER_PLAYLISTS:
                return R.string.mobile_empty_playlists;
            case MediaGroup.TYPE_MY_VIDEOS:
                return R.string.mobile_empty_my_videos;
            default:
                return 0;
        }
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
        boolean isLoading = mCurrentSectionId == MY_TOPICS_ID ? mTopicsLoading : mIsLoading;

        if (mCurrentSectionId == MY_TOPICS_ID && items.size() <= 1 && isLoading) {
            items.clear(); // nothing yet: skeletons instead of the lone "Edit" button
        }

        if (items.isEmpty()) {
            if (isLoading && mMessageGroup.getVisibility() != View.VISIBLE) {
                for (int i = 0; i < SKELETON_COUNT; i++) {
                    items.add(SKELETON);
                }
            }
        } else if (isLoading && !mRefresh.isRefreshing()) {
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

        boolean isMyTopics = content == mContents.get(MY_TOPICS_ID);

        if (isMyTopics) {
            items.add(EDIT_TOPICS);
        }

        // Several groups = rows (home page): show the title of each group
        boolean showHeaders = content.groups.size() > 1 || isMyTopics;

        for (GroupContent group : content.groups.values()) {
            if (showHeaders && !TextUtils.isEmpty(group.title) && !group.videos.isEmpty()) {
                items.add(group.query != null ? new TopicHeader(group.title, group.query) : group.title);
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

        if (sectionId == MY_TOPICS_ID) {
            loadMyTopics(); // local section: the presenter knows nothing about it
        } else {
            mPresenter.onSectionFocused(sectionId);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // My topics
    // ---------------------------------------------------------------------------------------------

    private void loadMyTopics() {
        disposeTopicActions();
        showMessage(null, null, null);

        List<Integer> topics = MobileTopics.getSelected(this);
        String[] labels = MobileTopics.getLabels(this);
        String[] queries = MobileTopics.getQueries(this);

        // Built aside: the old rows stay on screen until the new videos arrive
        SectionContent fresh = new SectionContent();
        fresh.groups.put(WATCH_LATER_GROUP_ID, watchLaterGroup());
        loadAccountWatchLater(fresh);

        for (int topic : topics) {
            GroupContent group = new GroupContent();
            group.title = labels[topic];
            group.query = queries[topic];
            fresh.groups.put(TOPIC_GROUP_BASE - topic, group);
        }

        SectionContent old = mContents.get(MY_TOPICS_ID);
        if (old == null || topics.isEmpty() || !hasVideos(old)) {
            mContents.put(MY_TOPICS_ID, fresh);
        }

        if (topics.isEmpty()) {
            mTopicsLoading = false;
            mRefresh.setRefreshing(false);
            showCurrentContent();
            if (fresh.groups.get(WATCH_LATER_GROUP_ID).videos.isEmpty()) {
                showMessage(getString(R.string.mobile_topics_empty), getString(R.string.mobile_topics_choose), this::editTopics);
            }
            return;
        }

        mTopicsLoading = true;
        mTopicsPending = topics.size();
        showCurrentContent();

        for (int topic : topics) {
            GroupContent group = fresh.groups.get(TOPIC_GROUP_BASE - topic);
            Disposable action = YouTubeServiceManager.instance().getContentService().getSearchObserve(group.query)
                    .take(1)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            mediaGroups -> {
                                addTopicVideos(group, mediaGroups);
                                mContents.put(MY_TOPICS_ID, fresh);
                                if (mCurrentSectionId == MY_TOPICS_ID) {
                                    showCurrentContent();
                                }
                            },
                            error -> onTopicLoaded(),
                            this::onTopicLoaded);
            mTopicActions.add(action);
        }
    }

    private void addTopicVideos(GroupContent group, List<MediaGroup> mediaGroups) {
        if (mediaGroups == null) {
            return;
        }

        List<Video> videos = new ArrayList<>();

        for (MediaGroup mediaGroup : mediaGroups) {
            VideoGroup videoGroup = VideoGroup.from(mediaGroup);

            if (videoGroup.getVideos() == null) {
                continue;
            }

            for (Video video : videoGroup.getVideos()) {
                // Only real videos (no channels/playlists), no shorts
                if (video != null && video.videoId != null && !video.isShorts && videos.size() < VIDEOS_PER_TOPIC) {
                    videos.add(video);
                }
            }
        }

        addVideos(group, videos, false);
    }

    private void onTopicLoaded() {
        mTopicsPending--;

        if (mTopicsPending <= 0) {
            mTopicsLoading = false;
            mRefresh.setRefreshing(false);

            if (mCurrentSectionId == MY_TOPICS_ID) {
                showCurrentContent();
            }
        }
    }

    private GroupContent watchLaterGroup() {
        GroupContent group = new GroupContent();
        group.title = "🕒 " + getString(R.string.mobile_watch_later);
        addVideos(group, MobileTopics.getWatchLater(this), false);
        return group;
    }

    private void updateWatchLaterRow() {
        SectionContent content = mContents.get(MY_TOPICS_ID);

        if (content != null && content.groups.containsKey(WATCH_LATER_GROUP_ID)) {
            content.groups.put(WATCH_LATER_GROUP_ID, watchLaterGroup());
            showCurrentContent();
            // the account playlist needs a moment to apply what was just saved
            mFeed.postDelayed(() -> {
                if (!isFinishing() && mContents.get(MY_TOPICS_ID) == content) {
                    loadAccountWatchLater(content);
                }
            }, 2_000);
        }
    }

    /**
     * Signed in: "Watch later" is the YouTube Watch later playlist of the account (the same one the TV shows).
     * The list saved on the phone stays on screen until the account answers, or if it fails.
     */
    private void loadAccountWatchLater(SectionContent target) {
        if (!YouTubeSignInService.instance().isSigned()) {
            return;
        }

        Disposable action = YouTubeServiceManager.instance().getContentService().getChannelObserve(BlueTubeSync.WATCH_LATER_BROWSE_ID)
                .take(1)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        mediaGroups -> {
                            GroupContent group = new GroupContent();
                            group.title = watchLaterGroup().title;

                            if (mediaGroups != null) {
                                for (MediaGroup mediaGroup : mediaGroups) {
                                    VideoGroup videoGroup = VideoGroup.from(mediaGroup);
                                    if (videoGroup.getVideos() == null) {
                                        continue;
                                    }
                                    List<Video> videos = new ArrayList<>();
                                    for (Video video : videoGroup.getVideos()) {
                                        if (video != null && video.videoId != null) {
                                            videos.add(video);
                                        }
                                    }
                                    addVideos(group, videos, false);
                                }
                            }

                            target.groups.put(WATCH_LATER_GROUP_ID, group);
                            SectionContent current = mContents.get(MY_TOPICS_ID);
                            if (current != null && current != target) {
                                current.groups.put(WATCH_LATER_GROUP_ID, group);
                            }
                            if (mCurrentSectionId == MY_TOPICS_ID) {
                                showCurrentContent();
                            }
                        },
                        error -> com.liskovsoft.sharedutils.mylogger.Log.e("MobileBrowseActivity", "Account watch later error: %s", error.getMessage()));
        mTopicActions.add(action);
    }

    private void editTopics() {
        MobileTopics.showPicker(this, topics -> {
            if (mCurrentSectionId == MY_TOPICS_ID) {
                loadMyTopics();
            } else {
                selectSectionInternal(MY_TOPICS_ID);
            }
        });
    }

    private static boolean hasVideos(SectionContent content) {
        for (GroupContent group : content.groups.values()) {
            if (!group.videos.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private void disposeTopicActions() {
        for (Disposable action : mTopicActions) {
            if (action != null && !action.isDisposed()) {
                action.dispose();
            }
        }

        mTopicActions.clear();
    }

    private void onSectionTapped(int sectionId) {
        if (sectionId == mCurrentSectionId && sectionId == MY_TOPICS_ID) {
            mFeed.scrollToPosition(0);
            mRefresh.setRefreshing(true);
            loadMyTopics();
        } else if (sectionId == mCurrentSectionId) {
            mFeed.scrollToPosition(0);
            mLastScrollEndVideo = null;
            mRefresh.setRefreshing(true);
            mPresenter.refresh(); // tapping the current section reloads it
        } else {
            selectSectionInternal(sectionId);
        }
    }

    private void rebuildNavigation() {
        // Chips: every enabled section ("My topics" right after the first one)
        mChips.removeAllViews();

        for (BrowseSection section : mSections) {
            if (isBottomOnly(section.getId())) {
                continue; // Favorites and Settings are in the bottom bar
            }

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
            if (entry[0] != MY_TOPICS_ID && indexOfSection(entry[0]) == -1) {
                continue;
            }

            mBottomNav.addView(createBottomNavItem(entry[0], entry[1], entry[2]));
        }

        updateNavigationState();
    }

    private static boolean isBottomOnly(int sectionId) {
        for (int id : BOTTOM_ONLY) {
            if (id == sectionId) {
                return true;
            }
        }

        return false;
    }

    private TextView createMyTopicsChip() {
        TextView chip = new TextView(this);
        chip.setTag(MY_TOPICS_ID);
        chip.setText("⭐ " + getString(R.string.mobile_topics_title));
        chip.setTextSize(14);
        chip.setMaxLines(1);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        params.setMargins(dp(4), 0, dp(4), 0);
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> onSectionTapped(MY_TOPICS_ID));
        chip.setOnLongClickListener(v -> {
            editTopics();
            return true;
        });
        return chip;
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
        private static final int TYPE_EDIT_TOPICS = 5;
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

            if (item == EDIT_TOPICS) {
                return TYPE_EDIT_TOPICS;
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
                case TYPE_EDIT_TOPICS:
                    return new HeaderHolder(createEditTopicsButton(parent));
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
            } else if (item instanceof TopicHeader) {
                // "Technology            See all ›": opens the full search of the topic
                TopicHeader topic = (TopicHeader) item;
                TextView header = (TextView) holder.itemView;
                header.setText(topic.title);
                Drawable seeAll = ContextCompat.getDrawable(MobileBrowseActivity.this, R.drawable.ic_m_chevron);
                if (seeAll != null) {
                    seeAll = seeAll.mutate();
                    seeAll.setAlpha(170);
                }
                header.setCompoundDrawablesWithIntrinsicBounds(null, null, seeAll, null);
                header.setOnClickListener(v -> SearchPresenter.instance(MobileBrowseActivity.this).startSearch(topic.query));
            } else if (item instanceof String) {
                TextView header = (TextView) holder.itemView;
                header.setText((String) item);
                header.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                header.setOnClickListener(null);
                header.setClickable(false);
            }
        }

        private View createEditTopicsButton(ViewGroup parent) {
            FrameLayout box = new FrameLayout(parent.getContext());
            box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setPadding(dp(14), dp(4), dp(14), dp(4));
            TextView button = new TextView(parent.getContext());
            button.setText("✏️  " + getString(R.string.mobile_topics_edit));
            button.setTextColor(Color.WHITE);
            button.setTextSize(14);
            button.setGravity(Gravity.CENTER_VERTICAL);
            button.setPadding(dp(16), 0, dp(18), 0);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(18));
            shape.setColor(0x1FFFFFFF);
            shape.setStroke(dp(1), 0x332F80FF);
            button.setBackground(shape);
            button.setOnClickListener(v -> editTopics());
            box.addView(button, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            return box;
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
