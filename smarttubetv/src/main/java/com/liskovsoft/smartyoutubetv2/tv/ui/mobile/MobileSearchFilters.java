package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Filter chips of the mobile search results: type (videos, playlists, channels), duration
 * (under 4 min, over 10/20/30/45 min) and upload date (today, this week, this month).<br/>
 * YouTube itself only knows "under 4", "4-20" and "over 20" minutes, so "over 10/30/45" also filter the
 * results on the phone by their length.
 */
public class MobileSearchFilters extends HorizontalScrollView {
    public interface OnChanged {
        void onFiltersChanged();
    }

    private static final int ACCENT = 0xFF2F80FF;
    // Duration chips: minimum length in minutes (0 = "under 4 minutes")
    private static final int DURATION_SHORT = -1;
    private static final int[] DURATION_MINUTES = {DURATION_SHORT, 10, 20, 30, 45};
    private static final int[] TYPES = {SearchOptions.TYPE_VIDEO, SearchOptions.TYPE_PLAYLIST, SearchOptions.TYPE_CHANNEL};
    private static final int[] TYPE_LABELS = {R.string.mobile_filter_videos, R.string.mobile_filter_playlists, R.string.mobile_filter_channels};
    private static final int[] DATES = {SearchOptions.UPLOAD_DATE_TODAY, SearchOptions.UPLOAD_DATE_THIS_WEEK, SearchOptions.UPLOAD_DATE_THIS_MONTH};
    private static final int[] DATE_LABELS = {R.string.mobile_filter_today, R.string.mobile_filter_week, R.string.mobile_filter_month};

    private final OnChanged mOnChanged;
    private final List<TextView> mTypeChips = new ArrayList<>();
    private final List<TextView> mDurationChips = new ArrayList<>();
    private final List<TextView> mDateChips = new ArrayList<>();
    private int mType = -1;
    private int mDuration = -1;
    private int mDate = -1;

    public MobileSearchFilters(Context context, OnChanged onChanged) {
        super(context);
        mOnChanged = onChanged;
        setHorizontalScrollBarEnabled(false);

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(4), dp(10), dp(8));
        addView(row);

        for (int i = 0; i < TYPES.length; i++) {
            final int index = i;
            TextView chip = chip(context.getString(TYPE_LABELS[i]), v -> {
                mType = mType == index ? -1 : index;
                if (mType >= 0 && TYPES[mType] != SearchOptions.TYPE_VIDEO) {
                    mDuration = -1; // playlists/channels have no length
                }
                refresh();
            });
            mTypeChips.add(chip);
            row.addView(chip);
        }

        row.addView(divider(context));

        for (int i = 0; i < DURATION_MINUTES.length; i++) {
            final int index = i;
            String label = DURATION_MINUTES[i] == DURATION_SHORT ? context.getString(R.string.mobile_filter_short)
                    : context.getString(R.string.mobile_filter_over, DURATION_MINUTES[i]);
            TextView chip = chip(label, v -> {
                mDuration = mDuration == index ? -1 : index;
                if (mDuration >= 0 && mType >= 0 && TYPES[mType] != SearchOptions.TYPE_VIDEO) {
                    mType = -1; // a length means videos
                }
                refresh();
            });
            mDurationChips.add(chip);
            row.addView(chip);
        }

        row.addView(divider(context));

        for (int i = 0; i < DATES.length; i++) {
            final int index = i;
            TextView chip = chip(context.getString(DATE_LABELS[i]), v -> {
                mDate = mDate == index ? -1 : index;
                refresh();
            });
            mDateChips.add(chip);
            row.addView(chip);
        }

        style();
    }

    // ---------------------------------------------------------------------------------------------

    public int getTypeOptions() {
        return mType >= 0 ? TYPES[mType] : 0;
    }

    /**
     * What YouTube is asked for. "Over 10" asks for everything and filters here (YouTube has no such filter).
     */
    public int getDurationOptions() {
        if (mDuration < 0) {
            return 0;
        }

        int minutes = DURATION_MINUTES[mDuration];
        return minutes == DURATION_SHORT ? SearchOptions.DURATION_UNDER_4 : minutes >= 20 ? SearchOptions.DURATION_OVER_20 : 0;
    }

    public int getUploadDateOptions() {
        return mDate >= 0 ? DATES[mDate] : 0;
    }

    /**
     * A minimum length is chosen that YouTube can't apply by itself: some results are dropped here.
     */
    public boolean filtersOnPhone() {
        return mDuration >= 0 && DURATION_MINUTES[mDuration] > 0;
    }

    /**
     * Results kept on the phone: with "over N minutes" only videos at least that long (no shorts, no live).
     */
    public boolean accepts(Video video) {
        if (!filtersOnPhone()) {
            return true;
        }

        int seconds = parseDuration(video.badge);
        return !video.isShorts && seconds >= DURATION_MINUTES[mDuration] * 60;
    }

    /**
     * "12:34" or "1:02:03" -> seconds, -1 if it isn't a length.
     */
    private static int parseDuration(String text) {
        if (TextUtils.isEmpty(text)) {
            return -1;
        }

        String[] parts = text.trim().split(":");

        if (parts.length < 2 || parts.length > 3) {
            return -1;
        }

        int seconds = 0;

        for (String part : parts) {
            try {
                seconds = seconds * 60 + Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        return seconds;
    }

    // ---------------------------------------------------------------------------------------------

    private void refresh() {
        style();

        if (mOnChanged != null) {
            mOnChanged.onFiltersChanged();
        }
    }

    private void style() {
        styleGroup(mTypeChips, mType);
        styleGroup(mDurationChips, mDuration);
        styleGroup(mDateChips, mDate);
    }

    private void styleGroup(List<TextView> chips, int selected) {
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            boolean on = i == selected;
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(16));
            shape.setColor(on ? ACCENT : 0x1FFFFFFF);
            chip.setBackground(shape);
            chip.setTextColor(on ? Color.WHITE : 0xFFC5D2EE);
            chip.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private TextView chip(String label, View.OnClickListener listener) {
        TextView chip = new TextView(getContext());
        chip.setText(label);
        chip.setTextSize(13);
        chip.setMaxLines(1);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        params.setMargins(dp(4), 0, dp(4), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(20));
        params.setMargins(dp(6), 0, dp(6), 0);
        divider.setLayoutParams(params);
        return divider;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
