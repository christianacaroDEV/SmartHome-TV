package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

/**
 * Settings that belong to the mobile mode (phones/tablets). Stored apart from the player prefs on purpose:
 * they never change by themselves, only when the user flips them.
 */
public final class MobileSettings {
    private static final String PREFS_NAME = "smarthome_defaults";
    private static final String KEY_BACKGROUND_AUDIO = "mobile_background_audio";
    private static final String KEY_COMPACT_BUTTONS_APPLIED = "mobile_compact_buttons_applied";
    /**
     * The few player buttons that make sense on a small screen. Everything else is reachable from the
     * quality (HQ) dialog, and the user can still enable more buttons in the settings.
     */
    private static final int COMPACT_PLAYER_BUTTONS =
            PlayerTweaksData.PLAYER_BUTTON_PLAY_PAUSE | PlayerTweaksData.PLAYER_BUTTON_PREVIOUS | PlayerTweaksData.PLAYER_BUTTON_NEXT |
            PlayerTweaksData.PLAYER_BUTTON_SUBTITLES | PlayerTweaksData.PLAYER_BUTTON_HIGH_QUALITY |
            PlayerTweaksData.PLAYER_BUTTON_LIKE | PlayerTweaksData.PLAYER_BUTTON_ADD_TO_PLAYLIST;

    private MobileSettings() {
    }

    /**
     * Same as the "Check for updates" button of the About screen.
     */
    public static void checkForUpdates(Context context) {
        AppUpdatePresenter.instance(context).start(true);
    }

    /**
     * Keep playing the audio when the screen is locked or the app goes to the background.
     * Stays as the user left it (on/off) until the user changes it again.
     */
    public static boolean isBackgroundAudioEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_BACKGROUND_AUDIO, false);
    }

    public static void setBackgroundAudioEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_BACKGROUND_AUDIO, enabled).apply();
    }

    /**
     * Once per install: reduce the player buttons to the compact set (phones only).
     */
    public static void applyCompactPlayerButtonsOnce(Context context) {
        SharedPreferences prefs = getPrefs(context);

        if (prefs.getBoolean(KEY_COMPACT_BUTTONS_APPLIED, false) || !DeviceMode.isMobile(context)) {
            return;
        }

        PlayerTweaksData data = PlayerTweaksData.instance(context);
        int all = 0;

        for (int bit = 0; bit < 28; bit++) {
            all |= 1 << bit;
        }

        data.setPlayerButtonDisabled(all & ~COMPACT_PLAYER_BUTTONS);
        data.setPlayerButtonEnabled(COMPACT_PLAYER_BUTTONS);
        prefs.edit().putBoolean(KEY_COMPACT_BUTTONS_APPLIED, true).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
