package com.liskovsoft.smartyoutubetv2.tv.util;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPoolAdapter;

/**
 * Bitmap pool that never pre-allocates bitmaps for the decoder.<br/>
 * On Android 8+ Glide's Downsampler asks the pool for a bitmap with the config reported by the image itself
 * ({@code BitmapFactory.Options.outConfig}). Some thumbnails report a config the device can't allocate and the whole
 * app dies with a native abort: "Abort message: 'unknown bitmap configuration'" (it can't be caught in Java).<br/>
 * Returning no reusable bitmap lets BitmapFactory allocate the bitmap itself, with a config it supports.
 */
public class SafeBitmapPool extends BitmapPoolAdapter {
    private static final String TAG = SafeBitmapPool.class.getSimpleName();
    private static final int MAX_SIDE = 4096;
    private static final long MAX_PIXELS = 4096L * 2304L; // 4K

    @SuppressWarnings("ConstantConditions")
    @NonNull
    @Override
    public Bitmap getDirty(int width, int height, Config config) {
        // NOTE: null is a valid value for BitmapFactory.Options.inBitmap (no reuse)
        return null;
    }

    @NonNull
    @Override
    public Bitmap get(int width, int height, Config config) {
        // Used by the transformations (e.g. center crop): only allow the common configs
        Config safeConfig = config == Config.ARGB_8888 || config == Config.RGB_565 || config == Config.ALPHA_8 ? config : Config.ARGB_8888;

        if (safeConfig != config) {
            Log.w(TAG, "Unsupported bitmap config replaced: " + config);
        }

        // Safety net: an impossible size can't be allocated and aborts the app natively (it can't be caught)
        if ((long) width * height > MAX_PIXELS || width > MAX_SIDE || height > MAX_SIDE) {
            Log.e(TAG, "Bitmap size too big, clamped: " + width + "x" + height);
            float scale = Math.min((float) MAX_SIDE / width, (float) MAX_SIDE / height);
            scale = Math.min(scale, (float) Math.sqrt((double) MAX_PIXELS / ((double) width * height)));
            width = Math.max(1, (int) (width * scale));
            height = Math.max(1, (int) (height * scale));
        }

        return Bitmap.createBitmap(width, height, safeConfig);
    }
}
