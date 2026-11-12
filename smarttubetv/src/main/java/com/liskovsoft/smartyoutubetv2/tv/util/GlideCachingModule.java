package com.liskovsoft.smartyoutubetv2.tv.util;

import java.io.InputStream;
import java.nio.ByteBuffer;
import android.graphics.Bitmap;
import com.bumptech.glide.Registry;
import com.bumptech.glide.Glide;
import androidx.annotation.NonNull;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.load.DecodeFormat;
import android.content.Context;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.module.AppGlideModule;

/**
 * https://bumptech.github.io/glide/doc/configuration.html#disk-cache<br/>
 * https://stackoverflow.com/questions/46108915/how-to-increase-the-cache-size-in-glide-android
 */
@GlideModule
public class GlideCachingModule extends AppGlideModule {
    private final static long CACHE_SIZE = 10 * 1024 * 1024; // 10 MB

    @Override
    public void applyOptions(Context context, GlideBuilder builder) {
        //if (MyApplication.from(context).isTest())
        //    return; // NOTE: StatFs will crash on robolectric.

        // Limit cache size
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, CACHE_SIZE));

        // Hardware bitmaps + the bitmap pool crash the whole app on some Android versions after the app comes back
        // from the background/PIP: "Abort message: 'unknown bitmap configuration'" (native SIGABRT in Downsampler.setInBitmap).
        // The bitmap pool itself is the crashing part (LruBitmapPool.getDirty -> Bitmap.createBitmap): do not reuse bitmaps.
        builder.setBitmapPool(new SafeBitmapPool());

        builder.setDefaultRequestOptions(new RequestOptions().format(DecodeFormat.PREFER_ARGB_8888).disallowHardwareConfig());
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        // Still images go through a safe decoder instead of Glide's Downsampler (native crash, see SafeBitmapDecoder)
        registry.prepend(Registry.BUCKET_BITMAP, ByteBuffer.class, Bitmap.class, SafeBitmapDecoder.forByteBuffer(glide.getBitmapPool()));
        registry.prepend(Registry.BUCKET_BITMAP, InputStream.class, Bitmap.class, SafeBitmapDecoder.forStream(glide.getBitmapPool()));
    }
}
