package com.liskovsoft.smartyoutubetv2.tv.ui.main;

import android.content.SharedPreferences;
import android.os.Build.VERSION;

import android.app.Activity;
import com.liskovsoft.smartyoutubetv2.common.misc.DeviceMode;
import com.liskovsoft.smartyoutubetv2.tv.ui.mobile.MobileBrowseActivity;
import androidx.annotation.NonNull;
import androidx.multidex.MultiDexApplication;
import androidx.work.Configuration;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AddDeviceView;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SplashView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.app.views.WebBrowserView;
import com.liskovsoft.smartyoutubetv2.common.prefs.NetworkData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.tv.ui.adddevice.AddDeviceActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.BrowseActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.channel.ChannelActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.channeluploads.ChannelUploadsActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.AppDialogActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.AppDialogActivityOpaque;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.search.tags.SearchTagsActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.signin.SignInActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.webbrowser.WebBrowserActivity;

import org.conscrypt.Conscrypt;

import java.lang.Thread.UncaughtExceptionHandler;
import java.security.Provider;
import java.security.Security;

public class MainApplication extends MultiDexApplication implements Configuration.Provider { // fix: Didn't find class "com.google.firebase.provider.FirebaseInitProvider"
    private static final String DEFAULT_LANGUAGE = "es";
    private static final String DEFAULTS_MARKER_PREFS = "smarthome_defaults";
    private static final String CLOCK_24H_APPLIED_KEY = "clock_24h_applied";

    static {
        // fix YouTube bandwidth throttling (best - false)???
        // false is better for streams (less buffering)
        System.setProperty("http.keepAlive", "false");
        // fix ipv6 infinite video buffering???
        // Better to remove this fix at all. Users complain about infinite loading.
        //System.setProperty("java.net.preferIPv6Addresses", "true");
        // Another IPv6 fix (no effect)
        // https://stackoverflow.com/questions/1920623/sometimes-httpurlconnection-getinputstream-executes-too-slowly
        //System.setProperty("java.net.preferIPv4Stack" , "true");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // ByeByeDPI fix
        // https://android-review.googlesource.com/c/platform/external/conscrypt/+/89408/
        // NOTE: Android 10+ (API 29+) uses system Conscrypt TLS; custom Security providers are unnecessary
        // NOTE: May cause 'Unexpected playback error null'
        //if (Build.VERSION.SDK_INT < 29 && Conscrypt.isAvailable()) {
        //    Security.insertProviderAt(Conscrypt.newProvider(), 1);
        //}

        // Important: Initialize the native Conscrypt provider BEFORE reading any configs/SharedPreferences.
        // Otherwise, early disk I/O shifts the ClassLoader on some Android TV devices, causing silent JNI linking errors.
        Provider conscryptProvider = null;
        try {
            conscryptProvider = Conscrypt.newProvider();
        } catch (Throwable e) {
            // UnsatisfiedLinkError
        }

        if (conscryptProvider != null && NetworkData.instance(this).isConscryptEnabled()) {
            try {
                Security.insertProviderAt(conscryptProvider, 1);
            } catch (Throwable e) {
                // UnsatisfiedLinkError
            }
        }

        setupDefaultLanguage();
        setupDefaultClockFormat();
        setupGlobalExceptionHandler();
        setupViewManager();
    }

    /**
     * Spanish is the default UI/content language.<br/>
     * Applied only while the user hasn't chosen a language yet ('null' means never set; the 'Default' option in the
     * language dialog stores an empty string, so a deliberate choice is preserved).
     */
    private void setupDefaultLanguage() {
        try {
            GlobalPreferences prefs = GlobalPreferences.instance(this);

            if (prefs != null && prefs.getPreferredLanguage() == null) {
                prefs.setPreferredLanguage(DEFAULT_LANGUAGE);
            }
        } catch (Throwable e) {
            // Not critical: fallback to the system language
        }
    }

    /**
     * The clock (home screen, player) uses the 24-hour format by default.<br/>
     * Applied only once (tracked with a marker), so the user can still switch back to 12 hours in the settings.
     */
    private void setupDefaultClockFormat() {
        try {
            SharedPreferences marker = getSharedPreferences(DEFAULTS_MARKER_PREFS, MODE_PRIVATE);

            if (!marker.getBoolean(CLOCK_24H_APPLIED_KEY, false)) {
                GlobalPreferences prefs = GlobalPreferences.instance(this);

                if (prefs != null) {
                    prefs.set24HourLocaleEnabled(true);
                    marker.edit().putBoolean(CLOCK_24H_APPLIED_KEY, true).apply();
                }
            }
        } catch (Throwable e) {
            // Not critical: fallback to the locale format
        }
    }

    /**
     * Lazy WorkManager init (auto-init is removed in the manifest).<br/>
     * Fix: 'IllegalStateException: Cannot initialize WorkManager in direct boot mode' thrown at process start on some Android 9 devices.
     * With the lazy init the exception is thrown from WorkManager.getInstance() where it's caught by SafeWorkManager.
     */
    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().build();
    }

    private void setupViewManager() {
        ViewManager viewManager = ViewManager.instance(this);

        Class<? extends AppDialogActivity> dialogClass = getDialogClass();
        // Phones/tablets use their own touch home screen
        Class<? extends Activity> browseClass = DeviceMode.isMobile(this) ? MobileBrowseActivity.class : BrowseActivity.class;

        viewManager.setRoot(browseClass);
        viewManager.register(SplashView.class, SplashActivity.class); // no parent, because it's root activity
        viewManager.register(BrowseView.class, browseClass); // no parent, because it's root activity
        viewManager.register(PlaybackView.class, PlaybackActivity.class, browseClass);
        viewManager.register(AppDialogView.class, dialogClass, browseClass);
        viewManager.register(SearchView.class, SearchTagsActivity.class, browseClass);
        viewManager.register(SignInView.class, SignInActivity.class, browseClass);
        viewManager.register(AddDeviceView.class, AddDeviceActivity.class, browseClass);
        viewManager.register(ChannelView.class, ChannelActivity.class, browseClass);
        viewManager.register(ChannelUploadsView.class, ChannelUploadsActivity.class, browseClass);
        viewManager.register(WebBrowserView.class, WebBrowserActivity.class, browseClass);
    }

    /**
     * Fix for the 'IllegalStateException: Only fullscreen opaque activities can request orientation'
     */
    private static Class<? extends AppDialogActivity> getDialogClass() {
        Class<? extends AppDialogActivity> dialogClass;

        if (VERSION.SDK_INT == 26
                && Helpers.equalsAny(Helpers.getCrashlyticsDeviceName(),
                "4S806_Z51S1 (Panasonic)",
                "5S72Z_G20S (Panasonic)",
                "9S60Z_ZQ21S (Panasonic)",
                "Android Smart TV (MStar)",
                "Xgimi TV (Xgimi Technology Co.,Ltd)"
        )) {
            // The fix: Only fullscreen opaque activities can request orientation
            dialogClass = AppDialogActivityOpaque.class;
        } else {
            dialogClass = AppDialogActivity.class;
        }
        return dialogClass;
    }

    private void setupGlobalExceptionHandler() {
        UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        if (defaultHandler == null) {
            return;
        }

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (shouldIgnore(e)) {
                MessageHelpers.showLongMessage(this, e.getMessage());
                return;
            }

            applyCrashFixes(e);
            //e = wrapWithAdditionalInfo(e);

            defaultHandler.uncaughtException(t, e);
        });
    }

    private boolean shouldIgnore(Throwable e) {
        // IllegalStateException: Not allowed to start service Intent { act=android.service.voice.VoiceInteractionService
        // cmp=com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService (has extras) }:
        // app is in background uid UidRecord{40e7240 u0a19 CEM idle change:cached procs:1 seq(0,0,0)}

        // java.lang.NoSuchMethodError: No interface method addListener(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)V
        // in class Lcom/google/common/util/concurrent/ListenableFuture; or its super classes
        // (declaration of 'com.google.common.util.concurrent.ListenableFuture' appears in /system/framework/libsetting.jar)

        // org.chromium.base.JniAndroid$UncaughtExceptionException
        // 1) Caused by java.lang.InterruptedException
        // 2) Caused by java.lang.SecurityException: The calling process has already registered an InputDevicesChangedListener.

        // IllegalArgumentException: Missing android.support.FILE_PROVIDER_PATHS meta-data (Shield Android TV 95%, MiTV-AFKR0 5%)

        // RuntimeException: Error receiving broadcast Intent { act=android.hardware.usb.action.USB_DEVICE_ATTACHED flg=0x10 }
        // Caused by NullPointerException: Attempt to invoke virtual method 'int android.hardware.usb.UsbDevice.getInterfaceCount()' on a null object reference

        return Helpers.containsAny(e.getMessage(),
                "KatnissVoiceInteractionService", "ListenableFuture", "Missing android.support.FILE_PROVIDER_PATHS meta-data",
                "Error receiving broadcast Intent { act=android.hardware.usb.action.USB_DEVICE_ATTACHED flg=0x10 }")
                || e.getClass().getName().startsWith("org.chromium");
    }

    private Throwable wrapWithAdditionalInfo(Throwable e) {
        if (Helpers.equalsAny(e.getMessage(),
                "parameter must be a descendant of this view",
                "Attempt to invoke virtual method 'android.view.ViewGroup$LayoutParams android.view.View.getLayoutParams()' on a null object reference")) {
            Class<?> view = ViewManager.instance(getApplicationContext()).getTopView();
            BrowseSection section = null;

            if (view == BrowseView.class) {
                section = BrowsePresenter.instance(getApplicationContext()).getCurrentSection();
            }

            e = new RuntimeException("A crash in the view " + view.getSimpleName() + ", section id " + (section != null ? section.getId() : "-1"), e);
        }
        return e;
    }

    private void applyCrashFixes(Throwable e) {
        if (e instanceof OutOfMemoryError || e.getCause() instanceof OutOfMemoryError) {
            Class<?> view = ViewManager.instance(getApplicationContext()).getTopView();
            if (view == PlaybackView.class) {
                PlayerTweaksData tweaksData = PlayerTweaksData.instance(getApplicationContext());
                PlayerData playerData = PlayerData.instance(getApplicationContext());
                int playerDataSource = tweaksData.getPlayerDataSource();
                int videoBufferType = playerData.getVideoBufferType();
                if (playerDataSource == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP) {
                    tweaksData.setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT);
                    tweaksData.persistNow();
                } else if (videoBufferType == PlayerData.BUFFER_HIGH || videoBufferType == PlayerData.BUFFER_HIGHEST) {
                    playerData.setVideoBufferType(PlayerData.BUFFER_MEDIUM);
                    playerData.persistNow();
                }
            }
        }
    }
}
