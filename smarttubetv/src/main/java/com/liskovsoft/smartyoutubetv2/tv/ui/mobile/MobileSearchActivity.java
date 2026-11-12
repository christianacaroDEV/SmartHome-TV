package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.MediaServiceSearchTagProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.SearchTagsProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Search screen for phones and tablets (mobile mode): field with back and microphone buttons,
 * history/suggestions while typing and a vertical list of results.
 */
public class MobileSearchActivity extends MotherActivity implements SearchView {
    private static final int REQUEST_VOICE = 4711;
    private static final int SUGGESTIONS_DELAY_MS = 180;
    private static final int LOAD_MORE_THRESHOLD = 5;

    private SearchPresenter mPresenter;
    private SearchTagsProvider mTagsProvider;
    private EditText mField;
    private View mClearButton;
    private ProgressBar mProgress;
    private RecyclerView mList;
    private ListAdapter mAdapter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Tag> mTags = new ArrayList<>();
    private final LinkedHashMap<Integer, List<Video>> mResultGroups = new LinkedHashMap<>();
    private final Set<Video> mResultUnique = new HashSet<>();
    private boolean mShowingResults;
    private boolean mIgnoreTextChange;
    private Video mLastScrollEndVideo;
    private String mPendingQuery = "";
    private final Runnable mLoadSuggestions = this::loadSuggestions;

    @Override
    protected boolean showsMobileBackButton() {
        return false; // it has its own back button inside the search field bar
    }

    @Override
    protected void initTheme() {
        setTheme(R.style.App_Theme_Mobile_Search);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_search);

        mField = findViewById(R.id.mobile_search_field);
        mClearButton = findViewById(R.id.mobile_search_clear);
        mProgress = findViewById(R.id.mobile_search_progress);
        mList = findViewById(R.id.mobile_search_list);

        mAdapter = new ListAdapter();
        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setAdapter(mAdapter);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && mShowingResults) {
                    loadMoreIfNeeded();
                } else if (dy != 0 && !mShowingResults) {
                    hideKeyboard(); // scrolling the suggestions: the keyboard is in the way
                }
            }
        });

        findViewById(R.id.mobile_search_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.mobile_search_mic).setOnClickListener(v -> startVoiceRecognition());
        mClearButton.setOnClickListener(v -> {
            mField.setText("");
            showKeyboard();
        });

        mField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mClearButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);

                if (!mIgnoreTextChange) {
                    showSuggestionsMode(s.toString());
                }
            }
        });
        mField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit(mField.getText().toString());
                return true;
            }
            return false;
        });

        mPresenter = SearchPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        mHandler.removeCallbacks(mLoadSuggestions);

        if (mPresenter != null) {
            mPresenter.onViewDestroyed();

            if (isFinishing()) {
                mPresenter.onFinish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_VOICE && resultCode == RESULT_OK && data != null) {
            List<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);

            if (results != null && !results.isEmpty()) {
                setFieldText(results.get(0));
                submit(results.get(0));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // SearchView
    // ---------------------------------------------------------------------------------------------

    @Override
    public void updateSearch(VideoGroup group) {
        if (group == null || group.getVideos() == null) {
            return;
        }

        List<Video> videos = mResultGroups.get(group.getId());

        if (videos == null) {
            videos = new ArrayList<>();
            mResultGroups.put(group.getId(), videos);
        }

        for (Video video : group.getVideos()) {
            if (video != null && mResultUnique.add(video)) {
                videos.add(video);
            }
        }

        if (mShowingResults) {
            mAdapter.setItems(buildResultItems());
        }
    }

    @Override
    public void clearSearch() {
        mResultGroups.clear();
        mResultUnique.clear();
        mLastScrollEndVideo = null;

        if (mShowingResults) {
            mAdapter.setItems(new ArrayList<>());
        }
    }

    @Override
    public void clearSearchTags() {
        mTags.clear();

        if (!mShowingResults) {
            mAdapter.setItems(new ArrayList<Object>(mTags));
        }
    }

    @Override
    public void removeSearchTag(Tag tag) {
        mTags.remove(tag);

        if (!mShowingResults) {
            mAdapter.setItems(new ArrayList<Object>(mTags));
        }
    }

    @Override
    public void setTagsProvider(MediaServiceSearchTagProvider provider) {
        mTagsProvider = provider;

        if (!mShowingResults) {
            loadSuggestions();
        }
    }

    @Override
    public void showProgressBar(boolean show) {
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void startSearch(String searchText) {
        if (TextUtils.isEmpty(searchText)) {
            // Empty search: show the history and open the keyboard
            showSuggestionsMode(mField.getText().toString());
            showKeyboard();
        } else {
            setFieldText(searchText);
            submit(searchText);
        }
    }

    @Override
    public String getSearchText() {
        return mField.getText().toString();
    }

    @Override
    public void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.home_search_hint));

        try {
            startActivityForResult(intent, REQUEST_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.mobile_voice_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    // NOTE: the interface method 'finishReally()' is implemented by MotherActivity

    // ---------------------------------------------------------------------------------------------
    // Logic
    // ---------------------------------------------------------------------------------------------

    private void setFieldText(String text) {
        mIgnoreTextChange = true;
        mField.setText(text);
        mField.setSelection(text.length());
        mIgnoreTextChange = false;
        mClearButton.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void submit(String query) {
        if (TextUtils.isEmpty(query == null ? null : query.trim())) {
            return;
        }

        hideKeyboard();
        mHandler.removeCallbacks(mLoadSuggestions);
        mShowingResults = true;
        mResultGroups.clear();
        mResultUnique.clear();
        mAdapter.setItems(new ArrayList<>());
        mPresenter.onSearch(query.trim());
    }

    private void showSuggestionsMode(String query) {
        mShowingResults = false;
        mPendingQuery = query;
        mHandler.removeCallbacks(mLoadSuggestions);
        mHandler.postDelayed(mLoadSuggestions, SUGGESTIONS_DELAY_MS);
        mAdapter.setItems(new ArrayList<Object>(mTags));
    }

    private void loadSuggestions() {
        if (mTagsProvider == null) {
            return;
        }

        final String query = mPendingQuery;

        mTagsProvider.search(query, results -> runOnUiThread(() -> {
            if (mShowingResults || isDestroyed()) {
                return;
            }

            mTags.clear();

            if (results != null) {
                mTags.addAll(results);
            }

            mAdapter.setItems(new ArrayList<Object>(mTags));
        }));
    }

    private List<Object> buildResultItems() {
        List<Object> items = new ArrayList<>();

        for (List<Video> videos : mResultGroups.values()) {
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
            mPresenter.onScrollEnd(last);
        }
    }

    private void showKeyboard() {
        mField.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        if (imm != null) {
            imm.showSoftInput(mField, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        if (imm != null) {
            imm.hideSoftInputFromWindow(mField.getWindowToken(), 0);
        }

        mField.clearFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------------------------------------
    // List
    // ---------------------------------------------------------------------------------------------

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_TAG = 0;
        private static final int TYPE_VIDEO = 1;
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
            return mItems.get(position) instanceof Video ? TYPE_VIDEO : TYPE_TAG;
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_VIDEO) {
                return new VideoHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mobile_video, parent, false));
            }

            return new TagHolder(createTagRow(parent));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = mItems.get(position);

            if (holder instanceof VideoHolder) {
                ((VideoHolder) holder).bind((Video) item);
            } else {
                ((TagHolder) holder).bind((Tag) item);
            }
        }

        private LinearLayout createTagRow(ViewGroup parent) {
            Context context = parent.getContext();
            LinearLayout row = new LinearLayout(context);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(4), 0);

            ImageView icon = new ImageView(context);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            icon.setImageResource(R.drawable.ic_m_history);
            icon.setAlpha(0.8f);
            row.addView(icon);

            TextView text = new TextView(context);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMargins(dp(18), 0, dp(8), 0);
            text.setLayoutParams(textParams);
            text.setTextColor(Color.WHITE);
            text.setTextSize(17);
            text.setMaxLines(1);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(text);

            ImageButton fill = new ImageButton(context);
            fill.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
            fill.setBackground(null);
            fill.setPadding(dp(12), dp(12), dp(12), dp(12));
            fill.setScaleType(ImageView.ScaleType.FIT_CENTER);
            fill.setImageResource(R.drawable.ic_m_fill);
            fill.setAlpha(0.8f);
            row.addView(fill);

            return row;
        }
    }

    private class TagHolder extends RecyclerView.ViewHolder {
        private final TextView mText;
        private final View mFill;

        TagHolder(LinearLayout row) {
            super(row);
            mText = (TextView) row.getChildAt(1);
            mFill = row.getChildAt(2);
        }

        void bind(Tag tag) {
            mText.setText(tag.tag);
            itemView.setOnClickListener(v -> {
                setFieldText(tag.tag);
                submit(tag.tag);
            });
            itemView.setOnLongClickListener(v -> {
                mPresenter.onTagLongClicked(tag);
                return true;
            });
            // Put the suggestion in the field without searching, to keep typing
            mFill.setOnClickListener(v -> {
                setFieldText(tag.tag + " ");
                showSuggestionsMode(tag.tag + " ");
                showKeyboard();
            });
        }
    }

    private class VideoHolder extends RecyclerView.ViewHolder {
        private final ImageView mThumb;
        private final TextView mBadge;
        private final ProgressBar mProgressBar;
        private final TextView mTitle;
        private final TextView mSubtitle;

        VideoHolder(View itemView) {
            super(itemView);
            mThumb = itemView.findViewById(R.id.mobile_item_thumb);
            mBadge = itemView.findViewById(R.id.mobile_item_badge);
            mProgressBar = itemView.findViewById(R.id.mobile_item_progress);
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
                mProgressBar.setProgress(video.percentWatched < 1 ? 1 : Math.round(video.percentWatched));
                mProgressBar.setVisibility(View.VISIBLE);
            } else {
                mProgressBar.setVisibility(View.GONE);
            }

            if (!isDestroyed()) {
                Glide.with(MobileSearchActivity.this)
                        .load(video.cardImageUrl)
                        .apply(ViewUtil.glideOptions())
                        .into(mThumb);
            }

            itemView.setOnClickListener(v -> {
                hideKeyboard();
                mPresenter.onVideoItemSelected(video);
                mPresenter.onVideoItemClicked(video);
            });
            View.OnClickListener more = v -> {
                mPresenter.onVideoItemSelected(video);
                mPresenter.onVideoItemLongClicked(video);
            };
            itemView.setOnLongClickListener(v -> {
                more.onClick(v);
                return true;
            });
            itemView.findViewById(R.id.mobile_item_more).setOnClickListener(more);
        }
    }
}
