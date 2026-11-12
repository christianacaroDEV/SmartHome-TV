package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * ImageView that is always 16:9 (the width is taken from the parent).
 */
@SuppressWarnings("AppCompatCustomView")
public class AspectRatioImageView extends ImageView {
    private static final float ASPECT_RATIO = 16f / 9f;

    public AspectRatioImageView(Context context) {
        super(context);
    }

    public AspectRatioImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatioImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        // An UNSPECIFIED measure pass (e.g. while the window is resized by picture-in-picture) may report a huge width.
        // Glide then requested a gigantic center-crop bitmap and the app aborted natively ("unknown bitmap configuration").
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || width <= 0 || width > screenWidth) {
            width = screenWidth;
        }

        setMeasuredDimension(width, Math.round(width / ASPECT_RATIO));
    }
}
