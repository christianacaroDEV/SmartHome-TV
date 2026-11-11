package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.leanback.widget.PageRow;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.List;

/**
 * Slim, always visible icon rail drawn at the left edge of the browse screen while the full sidebar (headers) is collapsed.<br/>
 * It is display only (not focusable): it shows where the user is. Moving the focus to the left still opens the full menu.<br/>
 * To fit any screen only a window of {@link #MAX_VISIBLE_ITEMS} sections around the selected one is shown.
 */
class NavigationRail {
    static final int WIDTH_DP = 80;
    private static final int CAPSULE_WIDTH_DP = 56;
    private static final int CAPSULE_MARGIN_START_DP = 14;
    private static final int CAPSULE_PADDING_V_DP = 10;
    private static final int MAX_VISIBLE_ITEMS = 9;
    private static final int ITEM_SIZE_DP = 40;
    private static final int ITEM_GAP_DP = 8;
    private static final int ICON_PADDING_DP = 4; // icons are normalized: the glyph fills ~78% of its canvas
    private static final float UNSELECTED_ALPHA = 0.55f;
    private final Context mContext;
    private final float mDensity;
    private final LinearLayout mContainer;

    NavigationRail(ViewGroup root) {
        mContext = root.getContext();
        mDensity = mContext.getResources().getDisplayMetrics().density;

        mContainer = new LinearLayout(mContext);
        mContainer.setOrientation(LinearLayout.VERTICAL);
        mContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        mContainer.setFocusable(false);
        mContainer.setClickable(false);

        // Capsule floating at the left edge, vertically centered
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(CAPSULE_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        params.leftMargin = dp(CAPSULE_MARGIN_START_DP);
        mContainer.setLayoutParams(params);
        mContainer.setPadding(0, dp(CAPSULE_PADDING_V_DP), 0, dp(CAPSULE_PADDING_V_DP));

        GradientDrawable capsule = new GradientDrawable();
        capsule.setShape(GradientDrawable.RECTANGLE);
        capsule.setCornerRadius(dp(CAPSULE_WIDTH_DP / 2));
        capsule.setColor(0x14FFFFFF);
        capsule.setStroke(dp(1), 0x24FFFFFF);
        mContainer.setBackground(capsule);

        root.addView(mContainer);
    }

    /**
     * Redraw the rail.
     * @param rows sections in the same order as in the sidebar
     * @param selected index of the current section
     */
    void update(List<?> rows, int selected) {
        mContainer.removeAllViews();

        if (rows == null || rows.isEmpty()) {
            return;
        }

        selected = Math.max(0, Math.min(selected, rows.size() - 1));

        // Window of visible items that always contains the selected one
        int count = Math.min(MAX_VISIBLE_ITEMS, rows.size());
        int start = Math.max(0, Math.min(selected - count / 2, rows.size() - count));

        for (int i = start; i < start + count; i++) {
            Object row = rows.get(i);

            if (!(row instanceof PageRow) || !(((PageRow) row).getHeaderItem() instanceof SectionHeaderItem)) {
                continue;
            }

            mContainer.addView(createItem((SectionHeaderItem) ((PageRow) row).getHeaderItem(), i == selected));
        }
    }

    /**
     * Fade the rail in or out (it is hidden while the full sidebar is open).
     */
    void setVisible(boolean visible, boolean animate) {
        float alpha = visible ? 1f : 0f;

        if (animate) {
            mContainer.animate().alpha(alpha).setDuration(180).start();
        } else {
            mContainer.animate().cancel();
            mContainer.setAlpha(alpha);
        }
    }

    private View createItem(SectionHeaderItem item, boolean selected) {
        ImageView icon = new ImageView(mContext);
        int size = dp(ITEM_SIZE_DP);
        int padding = dp(ICON_PADDING_DP);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.topMargin = dp(ITEM_GAP_DP) / 2;
        params.bottomMargin = dp(ITEM_GAP_DP) / 2;
        icon.setLayoutParams(params);
        icon.setPadding(padding, padding, padding, padding);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setFocusable(false);
        icon.setClickable(false);

        String iconUrl = item.getIconUrl();
        if (iconUrl != null) {
            Glide.with(mContext)
                    .load(iconUrl)
                    .apply(ViewUtil.glideOptions())
                    .into(icon);
        } else if (item.getResId() > 0) {
            Drawable drawable = ContextCompat.getDrawable(mContext, item.getResId());
            icon.setImageDrawable(drawable);
        }

        if (selected) {
            // Highlight the current section: rounded accent "pill" behind the icon
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(dp(12));
            background.setColor(ContextCompat.getColor(mContext, R.color.sh_accent));
            background.setAlpha(120);
            icon.setBackground(background);
            icon.setAlpha(1f);
        } else {
            icon.setAlpha(UNSELECTED_ALPHA);
        }

        return icon;
    }

    private int dp(int value) {
        return Math.round(value * mDensity);
    }
}
