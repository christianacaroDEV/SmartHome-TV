package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.AppDialogActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Touch version of the app dialogs (settings, video menu, player options) for phones and tablets.<br/>
 * A bottom sheet with plain lists: switches for on/off options, radio rows for "pick one", a chevron for
 * sub-lists, a back arrow inside nested lists and a close button. Same data as the TV dialogs
 * ({@link AppDialogPresenter} / {@link OptionCategory}), so every existing menu works unchanged.<br/>
 * Chat and comments keep the TV dialog (they have their own complex UI).
 */
public class MobileDialogActivity extends MotherActivity implements AppDialogView {
    private static final int ACCENT = 0xFF2F80FF;
    private static final int PANEL_COLOR = 0xFF0F1C3A;
    private static final int TEXT_SECONDARY = 0xFF9FB3D9;
    private static final int ROW_SWITCH = 0;
    private static final int ROW_RADIO = 1;
    private static final int ROW_NAV = 2;
    private static final int ROW_BUTTON = 3;
    private static final int ROW_TEXT = 4;

    private AppDialogPresenter mPresenter;
    private final List<Page> mPages = new ArrayList<>();
    private LinearLayout mPanel;
    private TextView mTitle;
    private ImageButton mBack;
    private RecyclerView mList;
    private final RowAdapter mAdapter = new RowAdapter();
    private boolean mIsTransparent;
    private boolean mIsOverlay;
    private boolean mIsPaused;
    private boolean mDelegated;
    private int mId;

    /**
     * One level of the dialog: either the list of categories or the options of one category.
     */
    private static class Page {
        final CharSequence title;
        final List<OptionCategory> categories;
        final OptionCategory category;

        Page(CharSequence title, List<OptionCategory> categories, OptionCategory category) {
            this.title = title;
            this.categories = categories;
            this.category = category;
        }
    }

    private static class Row {
        final int type;
        final CharSequence title;
        final CharSequence summary;
        final OptionCategory category;
        final OptionItem item;
        boolean radioChecked;

        Row(int type, CharSequence title, CharSequence summary, OptionCategory category, OptionItem item) {
            this.type = type;
            this.title = title;
            this.summary = summary;
            this.category = category;
            this.item = item;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(createContent());

        mPresenter = AppDialogPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void initTheme() {
        // Keep the translucent theme of the manifest
    }

    @Override
    protected boolean showsMobileBackButton() {
        return false; // own header
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsPaused = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsPaused = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }
    }

    @Override
    public void onBackPressed() {
        if (mPages.size() > 1) {
            popPage();
        } else {
            finish();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        finish();
    }

    @Override
    public void finish() {
        if (!mDelegated && mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }

        finishReally();
    }

    // ---------------------------------------------------------------------------------------------
    // AppDialogView
    // ---------------------------------------------------------------------------------------------

    @Override
    public void show(List<OptionCategory> categories, CharSequence title, boolean isExpandable, boolean isTransparent, boolean isOverlay, int id) {
        if (isFinishing() || categories == null) {
            return;
        }

        for (OptionCategory category : categories) {
            if (category.type == OptionCategory.TYPE_CHAT || category.type == OptionCategory.TYPE_COMMENTS) {
                delegateToTvDialog();
                return;
            }
        }

        mIsTransparent = mPages.isEmpty() ? isTransparent : mIsTransparent;
        mIsOverlay = isOverlay;
        mId = id;

        if (isExpandable && categories.size() == 1) {
            OptionCategory category = categories.get(0);

            if (category.type == OptionCategory.TYPE_SINGLE_BUTTON) {
                // A single action: just run it (like the TV dialog does)
                int pages = mPages.size();
                if (category.options != null && !category.options.isEmpty()) {
                    category.options.get(0).onSelect(true);
                }
                if (pages == 0 && mPages.isEmpty() && !isFinishing()) {
                    finish();
                }
                return;
            }

            if (category.type != OptionCategory.TYPE_SINGLE_SWITCH) {
                pushPage(new Page(category.title != null ? category.title : title, null, category));
                return;
            }
        }

        pushPage(new Page(title, categories, null));
    }

    @Override
    public void goBack() {
        if (mPages.size() > 1) {
            popPage();
        } else {
            finish();
        }
    }

    @Override
    public void clearBackstack() {
        while (mPages.size() > 1) {
            mPages.remove(0);
        }
        updateHeader();
    }

    @Override
    public boolean canGoBack() {
        return mPages.size() > 1;
    }

    @Override
    public boolean isShown() {
        return !isFinishing() && !mIsPaused;
    }

    @Override
    public boolean isTransparent() {
        return mIsTransparent;
    }

    @Override
    public boolean isOverlay() {
        return mIsOverlay;
    }

    @Override
    public boolean isPaused() {
        return mIsPaused;
    }

    @Override
    public int getViewId() {
        return mId;
    }

    /**
     * Chat/comments: open the TV dialog instead. It takes the same pending data from the presenter.
     */
    private void delegateToTvDialog() {
        mDelegated = true;
        Intent intent = new Intent(this, AppDialogActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finishReally();
    }

    // ---------------------------------------------------------------------------------------------
    // Pages
    // ---------------------------------------------------------------------------------------------

    private void pushPage(Page page) {
        mPages.add(page);
        refresh();
        mList.scrollToPosition(0);
    }

    private void popPage() {
        if (!mPages.isEmpty()) {
            mPages.remove(mPages.size() - 1);
        }

        if (mPages.isEmpty()) {
            finish();
        } else {
            refresh();
        }
    }

    private void refresh() {
        updateHeader();
        mAdapter.setRows(buildRows(mPages.isEmpty() ? null : mPages.get(mPages.size() - 1)));
    }

    private void updateHeader() {
        Page page = mPages.isEmpty() ? null : mPages.get(mPages.size() - 1);
        mTitle.setText(page != null ? page.title : null);
        mTitle.setVisibility(page != null && !TextUtils.isEmpty(page.title) ? View.VISIBLE : View.INVISIBLE);
        mBack.setVisibility(mPages.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private List<Row> buildRows(Page page) {
        List<Row> rows = new ArrayList<>();

        if (page == null) {
            return rows;
        }

        if (page.category != null) {
            addOptionRows(rows, page.category);
            return rows;
        }

        for (OptionCategory category : page.categories) {
            if (category.options == null || category.options.isEmpty()) {
                continue;
            }

            switch (category.type) {
                case OptionCategory.TYPE_SINGLE_SWITCH: {
                    OptionItem item = category.options.get(0);
                    rows.add(new Row(ROW_SWITCH, item.getTitle(), item.getDescription(), category, item));
                    break;
                }
                case OptionCategory.TYPE_SINGLE_BUTTON: {
                    OptionItem item = category.options.get(0);
                    rows.add(new Row(ROW_BUTTON, item.getTitle(), item.getDescription(), category, item));
                    break;
                }
                default:
                    rows.add(new Row(ROW_NAV, category.title, summaryOf(category), category, null));
                    break;
            }
        }

        return rows;
    }

    private void addOptionRows(List<Row> rows, OptionCategory category) {
        if (category.options == null) {
            return;
        }

        switch (category.type) {
            case OptionCategory.TYPE_RADIO_LIST:
                OptionItem radioSelected = selectedRadio(category);
                for (OptionItem item : category.options) {
                    Row radio = new Row(ROW_RADIO, item.getTitle(), item.getDescription(), category, item);
                    radio.radioChecked = item == radioSelected;
                    rows.add(radio);
                }
                break;
            case OptionCategory.TYPE_CHECKBOX_LIST:
            case OptionCategory.TYPE_SINGLE_SWITCH:
                for (OptionItem item : category.options) {
                    rows.add(new Row(ROW_SWITCH, item.getTitle(), item.getDescription(), category, item));
                }
                break;
            case OptionCategory.TYPE_LONG_TEXT:
                rows.add(new Row(ROW_TEXT, category.options.get(0).getTitle(), null, category, category.options.get(0)));
                break;
            default:
                for (OptionItem item : category.options) {
                    rows.add(new Row(ROW_BUTTON, item.getTitle(), item.getDescription(), category, item));
                }
                break;
        }
    }

    /**
     * The current value shown under a sub-list row (e.g. "Quality · 1080p"), like the Android settings.
     */
    private static CharSequence summaryOf(OptionCategory category) {
        if (category.type != OptionCategory.TYPE_RADIO_LIST && category.type != OptionCategory.TYPE_CHECKBOX_LIST) {
            return null;
        }

        if (category.type == OptionCategory.TYPE_RADIO_LIST) {
            OptionItem item = selectedRadio(category);
            return item != null ? item.getTitle() : null;
        }

        List<CharSequence> selected = new ArrayList<>();

        for (OptionItem item : category.options) {
            if (item.isSelected() && item.getTitle() != null) {
                selected.add(item.getTitle());
            }
        }

        return selected.isEmpty() ? null : TextUtils.join(", ", selected);
    }

    /**
     * One choice only: if several items say they are selected, the last one wins (same as the TV list).
     */
    private static OptionItem selectedRadio(OptionCategory category) {
        OptionItem result = null;

        for (OptionItem item : category.options) {
            if (item.isSelected()) {
                result = item;
            }
        }

        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private void onRowClicked(Row row, boolean checked) {
        int pages = mPages.size();

        switch (row.type) {
            case ROW_NAV:
                pushPage(new Page(row.category.title, null, row.category));
                return;
            case ROW_RADIO:
                row.item.onSelect(true);
                // Picked: back to the previous level (like YouTube), unless the option opened something new
                if (mPages.size() == pages && !isFinishing()) {
                    goBack();
                }
                return;
            case ROW_SWITCH:
                onSwitchChanged(row, checked);
                break;
            case ROW_BUTTON:
                row.item.onSelect(true);
                break;
            default:
                return;
        }

        if (!isFinishing() && mPages.size() == pages) {
            refresh();
        }
    }

    /**
     * Same rules as the TV checkbox lists: required options and options that turn others off.
     */
    private void onSwitchChanged(Row row, boolean checked) {
        OptionItem item = row.item;

        if (checked) {
            OptionItem[] requiredItems = item.getRequired();

            if (requiredItems != null) {
                for (OptionItem requiredItem : requiredItems) {
                    if (!requiredItem.isSelected()) {
                        MessageHelpers.showMessage(this, getString(R.string.require_checked, requiredItem.getTitle()));
                    }
                }
            }

            OptionItem[] radioItems = item.getRadio();

            if (radioItems != null) {
                for (OptionItem radioItem : radioItems) {
                    radioItem.onSelect(false);
                }
            }
        }

        item.onSelect(checked);
    }

    // ---------------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------------

    private View createContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> {
            if (!mIsOverlay) {
                finish(); // tap outside the sheet
            }
        });

        mPanel = new LinearLayout(this);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setClickable(true); // the touches inside the sheet don't close it
        GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL_COLOR);
        float radius = dp(18);
        background.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
        mPanel.setBackground(background);
        androidx.core.view.ViewCompat.setElevation(mPanel, dp(12)); // no-op before Android 5

        // Grab handle
        View handle = new View(this);
        GradientDrawable handleShape = new GradientDrawable();
        handleShape.setColor(0x55FFFFFF);
        handleShape.setCornerRadius(dp(2));
        handle.setBackground(handleShape);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.setMargins(0, dp(8), 0, 0);
        mPanel.addView(handle, handleParams);

        // Header: back | title | close
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(2), dp(4), dp(2));
        mBack = headerButton(R.drawable.ic_nav_back);
        mBack.setOnClickListener(v -> popPage());
        header.addView(mBack);
        mTitle = new TextView(this);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(18);
        mTitle.setTypeface(null, Typeface.BOLD);
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setPadding(dp(12), 0, dp(4), 0);
        header.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton close = headerButton(R.drawable.ic_m_close);
        close.setOnClickListener(v -> finish());
        header.addView(close);
        mPanel.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        View divider = new View(this);
        divider.setBackgroundColor(0x1FFFFFFF);
        mPanel.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        mList = new RecyclerView(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                // Wrap the content, up to 80% of the screen
                int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.8f) - dp(80);
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setAdapter(mAdapter);
        mList.setPadding(0, dp(4), 0, dp(16));
        mList.setClipToPadding(false);
        mPanel.addView(mList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Phones: full width at the bottom. Landscape/tablets: a centered sheet, not wider than 600dp.
        int maxWidth = dp(600);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                Math.min(screenWidth, maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(mPanel, panelParams);

        // Slide up
        mPanel.setTranslationY(dp(400));
        mPanel.animate().translationY(0).setDuration(220).start();

        return root;
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mPanel != null) {
            ViewGroup.LayoutParams params = mPanel.getLayoutParams();
            params.width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(600));
            mPanel.setLayoutParams(params);
            mList.requestLayout();
        }
    }

    private ImageButton headerButton(int iconResId) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResId);
        button.setBackground(null);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setColorFilter(Color.WHITE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class RowAdapter extends RecyclerView.Adapter<RowHolder> {
        private List<Row> mRows = new ArrayList<>();

        void setRows(List<Row> rows) {
            mRows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).type;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return viewType == ROW_TEXT ? new RowHolder(createTextRow()) : new RowHolder(createRow(viewType));
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            holder.bind(mRows.get(position));
        }
    }

    private View createRow(int type) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(20), dp(10), dp(16), dp(10));
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        if (ripple.resourceId != 0) {
            row.setBackgroundResource(ripple.resourceId);
        }

        if (type == ROW_RADIO) {
            ImageView check = new ImageView(this);
            check.setId(R.id.mobile_dialog_check);
            row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setId(R.id.mobile_dialog_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        texts.addView(title);
        TextView summary = new TextView(this);
        summary.setId(R.id.mobile_dialog_summary);
        summary.setTextColor(TEXT_SECONDARY);
        summary.setTextSize(13);
        summary.setMaxLines(2);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setPadding(0, dp(2), 0, 0);
        texts.addView(summary);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.setMargins(type == ROW_RADIO ? dp(16) : 0, 0, dp(8), 0);
        row.addView(texts, textsParams);

        if (type == ROW_SWITCH) {
            Switch toggle = new Switch(this);
            toggle.setId(R.id.mobile_dialog_switch);
            toggle.setClickable(false); // the whole row toggles it
            toggle.setFocusable(false);
            if (VERSION.SDK_INT >= 23) {
                int[][] states = {new int[] {android.R.attr.state_checked}, new int[] {}};
                toggle.setThumbTintList(new ColorStateList(states, new int[] {ACCENT, 0xFFB8C7E6}));
                toggle.setTrackTintList(new ColorStateList(states, new int[] {0x882F80FF, 0x44FFFFFF}));
            }
            row.addView(toggle);
        } else if (type == ROW_NAV) {
            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_m_chevron);
            chevron.setAlpha(0.6f);
            row.addView(chevron, new LinearLayout.LayoutParams(dp(22), dp(22)));
        }

        return row;
    }

    private View createTextRow() {
        TextView text = new TextView(this);
        text.setId(R.id.mobile_dialog_title);
        text.setTextColor(0xFFDDE6F7);
        text.setTextSize(15);
        text.setLineSpacing(0, 1.2f);
        text.setTextIsSelectable(true);
        text.setPadding(dp(20), dp(12), dp(20), dp(12));
        text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return text;
    }

    private class RowHolder extends RecyclerView.ViewHolder {
        RowHolder(View itemView) {
            super(itemView);
        }

        void bind(Row row) {
            TextView title = itemView.findViewById(R.id.mobile_dialog_title);
            title.setText(row.title);

            if (row.type == ROW_TEXT) {
                return;
            }

            TextView summary = itemView.findViewById(R.id.mobile_dialog_summary);
            summary.setText(row.summary);
            summary.setVisibility(TextUtils.isEmpty(row.summary) ? View.GONE : View.VISIBLE);

            Switch toggle = itemView.findViewById(R.id.mobile_dialog_switch);
            ImageView check = itemView.findViewById(R.id.mobile_dialog_check);
            boolean selected = row.item != null && row.item.isSelected();

            if (toggle != null) {
                toggle.setChecked(selected);
            }

            if (check != null) {
                check.setImageResource(row.radioChecked ? R.drawable.ic_m_radio_on : R.drawable.ic_m_radio_off);
                title.setTypeface(null, row.radioChecked ? Typeface.BOLD : Typeface.NORMAL);
            }

            itemView.setOnClickListener(v -> {
                boolean checked = toggle != null && !toggle.isChecked();
                if (toggle != null) {
                    toggle.setChecked(checked);
                }
                onRowClicked(row, checked);
            });
        }
    }
}
