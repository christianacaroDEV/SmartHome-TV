package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Grey placeholder with the shape of a video card (thumbnail + two text lines) that pulses while a list
 * loads. The same one is used everywhere (home sections, search, watch page suggestions).
 */
public final class MobileSkeleton {
    /**
     * Put this object in a list to get a skeleton card.
     */
    public static final Object ITEM = new Object();
    public static final int COUNT = 4;
    private static final int COLOR = 0xFF16264A;

    private MobileSkeleton() {
    }

    public static Holder createHolder(@NonNull ViewGroup parent) {
        return new Holder(createView(parent));
    }

    private static View createView(ViewGroup parent) {
        Context context = parent.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setPadding(0, 0, 0, dp(density, 16));

        View thumb = new View(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                if (width <= 0) {
                    width = getResources().getDisplayMetrics().widthPixels;
                }
                setMeasuredDimension(width, width * 9 / 16);
            }
        };
        thumb.setBackgroundColor(COLOR);
        card.addView(thumb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = parent.getWidth() > 0 ? parent.getWidth() : context.getResources().getDisplayMetrics().widthPixels;
        card.addView(line(context, density, Math.round(width * 0.85f), 16, 12));
        card.addView(line(context, density, Math.round(width * 0.55f), 12, 8));
        return card;
    }

    private static View line(Context context, float density, int width, int heightDp, int topMarginDp) {
        View line = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(density, 4));
        shape.setColor(COLOR);
        line.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(density, heightDp));
        params.setMargins(dp(density, 14), dp(density, topMarginDp), 0, 0);
        line.setLayoutParams(params);
        return line;
    }

    private static int dp(float density, int value) {
        return Math.round(value * density);
    }

    /**
     * Call {@link #startPulse()} / {@link #stopPulse()} from the adapter's attach/detach callbacks.
     */
    public static class Holder extends RecyclerView.ViewHolder {
        private ObjectAnimator mPulse;

        Holder(View itemView) {
            super(itemView);
        }

        public void startPulse() {
            stopPulse();
            mPulse = ObjectAnimator.ofFloat(itemView, View.ALPHA, 1f, 0.45f);
            mPulse.setDuration(750);
            mPulse.setRepeatMode(ValueAnimator.REVERSE);
            mPulse.setRepeatCount(ValueAnimator.INFINITE);
            mPulse.start();
        }

        public void stopPulse() {
            if (mPulse != null) {
                mPulse.cancel();
                mPulse = null;
            }
            itemView.setAlpha(1f);
        }
    }
}
