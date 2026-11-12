package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.content.Intent;
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

    /**
     * Content of a section: groups (rows) of videos or settings items.
     */
    private static class SectionContent {
        final LinkedHashMap<Integer, GroupContent> groups = new LinkedHashMap<>();
        List<SettingsItem> settings = new ArrayList<>();
    }

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

        mPresenter = BrowsePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!mIsFirstResume && mPresenter != null) {
            mPresenter.onViewResumed();
        }

        mIsFirstResume = false;
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

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

        switch (group.getAction()) {
            case VideoGroup.ACTION_REPLACE:
                if (group.getVideos() == null || group.getVideos().isEmpty()) {
                    content.groups.clear(); // the presenter starts a section with an empty 'replace' group
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
            mAdapter.setItems(buildItems(content));
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

        if (sectionId == mCurrentSectionId) {
            showMessage(null, null, null);
            mAdapter.setItems(buildItems(content));
        }
    }

    @Override
    public void clearSection(BrowseSection section) {
        if (section == null) {
            return;
        }

        mContents.remove(section.getId());

        if (section.getId() == mCurrentSectionId) {
            mAdapter.setItems(new ArrayList<>());
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

        mAdapter.setItems(new ArrayList<>());
        showMessage(data.getMessage(), data.getActionText(), data::onAction);
    }

    @Override
    public void showProgressBar(boolean show) {
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgress.getVisibility() == View.VISIBLE;
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

        mAdapter.setItems(buildItems(mContents.get(sectionId)));
        mFeed.scrollToPosition(0);
        updateNavigationState();

        mPresenter.onSectionFocused(sectionId);
    }

    private void onSectionTapped(int sectionId) {
        if (sectionId == mCurrentSectionId) {
            mFeed.scrollToPosition(0);
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
            return item instanceof Video ? TYPE_VIDEO : item instanceof SettingsItem ? TYPE_SETTING : TYPE_HEADER;
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
            } else {
                ((TextView) holder.itemView).setText((String) item);
            }
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

            if (!isDestroyed()) {
                Glide.with(MobileBrowseActivity.this)
                        .load(video.cardImageUrl)
                        .apply(ViewUtil.glideOptions())
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
