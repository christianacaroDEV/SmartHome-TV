# BlueTube release shrinking: CONSERVATIVE on purpose.
# Only code that nothing ever calls (unused parts of third-party libraries) is removed. Nothing is renamed or
# "optimized", and every package that is used through reflection, JNI, JavaScript bridges or JSON mapping is kept
# whole, so the app behaves exactly as without shrinking.

-dontobfuscate
-dontoptimize
-keepattributes *

# App, SmartTube modules (YouTube client, prefs by reflection, presenters started by name) and local forks
-keep class com.liskovsoft.** { *; }
-keep interface com.liskovsoft.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-keep class androidx.leanback.** { *; }
-keep class androidx.fragment.** { *; }
-keep class com.stfalcon.chatkit.** { *; }
-keep class com.github.vkay94.** { *; }
-keep class com.codekidlabs.** { *; }
-keep class com.r0adkll.slidr.** { *; }

# Native code (JNI calls Java by name): V8 (YouTube signatures), Cronet, Conscrypt
-keep class com.eclipsesource.v8.** { *; }
-keep class org.chromium.** { *; }
-keep class org.conscrypt.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# JSON / HTTP / reflection based libraries
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter { *; }
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class com.jayway.jsonpath.** { *; }
-keep class net.minidev.** { *; }
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class io.reactivex.** { *; }
-keep class com.bumptech.glide.** { *; }
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep class org.jsoup.** { *; }
-keep class org.mozilla.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class app.cash.quickjs.** { *; }
-keep class com.squareup.duktape.** { *; }
-keep class org.xbill.DNS.** { *; }
-keep class androidx.work.** { *; }
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }
-keep class info.guardianproject.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class com.jakewharton.** { *; }
-keep class net.gotev.speech.** { *; }

# WebView JavaScript bridges, enums, serializable and parcelable data
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class * implements java.io.Serializable { *; }
-keep class * implements android.os.Parcelable { *; }

-dontwarn **
-ignorewarnings
