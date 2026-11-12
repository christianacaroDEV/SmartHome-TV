package com.liskovsoft.smartyoutubetv2.tv.util;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.util.ByteBufferUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Simple, safe replacement of Glide's Downsampler for still images (thumbnails, avatars...).<br/>
 * Glide's Downsampler crashed the whole app natively on some devices ("Abort message: 'unknown bitmap configuration'"
 * in BitmapFactory.doDecode) when reusing BitmapFactory.Options between decodes.<br/>
 * This decoder uses fresh options every time: fixed ARGB_8888 + sRGB, power of two sampling, no bitmap reuse and
 * no density scaling. Animated images (GIF/WebP) keep using their own Glide decoders.
 */
public class SafeBitmapDecoder<T> implements ResourceDecoder<T, Bitmap> {
    private final BitmapPool mBitmapPool;
    private final boolean mIsStream;

    private SafeBitmapDecoder(BitmapPool bitmapPool, boolean isStream) {
        mBitmapPool = bitmapPool;
        mIsStream = isStream;
    }

    public static SafeBitmapDecoder<ByteBuffer> forByteBuffer(BitmapPool pool) {
        return new SafeBitmapDecoder<>(pool, false);
    }

    public static SafeBitmapDecoder<InputStream> forStream(BitmapPool pool) {
        return new SafeBitmapDecoder<>(pool, true);
    }

    @Override
    public boolean handles(@NonNull T source, @NonNull Options options) {
        return true;
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(@NonNull T source, int width, int height, @NonNull Options options) throws IOException {
        byte[] data = toBytes(source);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null; // not an image this decoder understands: let the next decoder try
        }

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inPreferredConfig = Config.ARGB_8888;
        decode.inScaled = false;
        decode.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);

        if (Build.VERSION.SDK_INT >= 26) {
            decode.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        }

        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decode);

        return bitmap != null ? BitmapResource.obtain(bitmap, mBitmapPool) : null;
    }

    private byte[] toBytes(T source) throws IOException {
        if (mIsStream) {
            return ByteBufferUtil.toBytes(ByteBufferUtil.fromStream((InputStream) source));
        }

        ByteBuffer buffer = ((ByteBuffer) source).duplicate();
        buffer.rewind();
        return ByteBufferUtil.toBytes(buffer);
    }

    private static int sampleSize(int srcWidth, int srcHeight, int reqWidth, int reqHeight) {
        if (reqWidth == Target.SIZE_ORIGINAL || reqHeight == Target.SIZE_ORIGINAL || reqWidth <= 0 || reqHeight <= 0) {
            return 1;
        }

        int sample = 1;

        while (srcWidth / (sample * 2) >= reqWidth && srcHeight / (sample * 2) >= reqHeight) {
            sample *= 2;
        }

        return sample;
    }
}
