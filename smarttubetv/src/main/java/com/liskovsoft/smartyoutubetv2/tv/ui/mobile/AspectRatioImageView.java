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
        setMeasuredDimension(width, Math.round(width / ASPECT_RATIO));
    }
}
