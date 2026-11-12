package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/**
 * Tells whether the app runs on a phone/tablet ("mobile mode": touch, landscape, smaller UI) or on a TV.<br/>
 * The mode is detected automatically (no TV features + touch screen) and can be forced by the user.
 */
public final class DeviceMode {
    public static final int MODE_AUTO = 0;
    public static final int MODE_MOBILE = 1;
    public static final int MODE_TV = 2;
    private static final String PREFS_NAME = "bluetube_defaults";
    private static final String KEY_MODE = "device_mode";
    private static Boolean sIsMobile;

    private DeviceMode() {
    }

    public static boolean isMobile(Context context) {
        if (sIsMobile == null) {
            sIsMobile = detect(context.getApplicationContext());
        }

        return sIsMobile;
    }

    public static int getMode(Context context) {
        return getPrefs(context).getInt(KEY_MODE, MODE_AUTO);
    }

    /**
     * The new mode is fully applied after the app restart (screen orientation, UI scale).
     */
    public static void setMode(Context context, int mode) {
        getPrefs(context).edit().putInt(KEY_MODE, mode).apply();
        sIsMobile = null;
    }

    private static boolean detect(Context context) {
        int mode = getMode(context);

        if (mode == MODE_MOBILE) {
            return true;
        }

        if (mode == MODE_TV) {
            return false;
        }

        PackageManager pm = context.getPackageManager();
        // NOTE: the string constants are used because PackageManager.FEATURE_LEANBACK needs api 21
        boolean hasTvFeature = pm.hasSystemFeature("android.software.leanback") || pm.hasSystemFeature("android.hardware.type.television");
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        boolean isTvUiMode = uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        boolean hasTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);

        return !hasTvFeature && !isTvUiMode && hasTouch;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
