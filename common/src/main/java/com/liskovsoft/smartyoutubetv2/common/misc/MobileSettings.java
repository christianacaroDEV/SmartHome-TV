package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

/**
 * Settings that belong to the mobile mode (phones/tablets). Stored apart from the player prefs on purpose:
 * they never change by themselves, only when the user flips them.
 */
public final class MobileSettings {
    private static final String PREFS_NAME = "bluetube_defaults";
    private static final String KEY_BACKGROUND_AUDIO = "mobile_background_audio";
    private static final String KEY_COMPACT_BUTTONS_APPLIED = "mobile_compact_buttons_applied";
    private static final String KEY_NORMAL_QUALITY_APPLIED = "mobile_normal_quality_applied";
    private static final String KEY_MOBILE_MENU_APPLIED = "mobile_menu_applied";
    private static final String KEY_TV_DEFAULTS_APPLIED = "tv_defaults_v1";
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

    /**
     * Once per install (TV only): a simple player like YouTube on TV (play/pause, previous/next, quality, subtitles,
     * like and save). Everything else stays in the quality (HQ) dialog and can be enabled again in the settings.
     * The video menu also offers "Watch later" (shared with the phone through the account).
     */
    public static void applyTvDefaultsOnce(Context context) {
        SharedPreferences prefs = getPrefs(context);

        if (prefs.getBoolean(KEY_TV_DEFAULTS_APPLIED, false) || DeviceMode.isMobile(context)) {
            return;
        }

        PlayerTweaksData data = PlayerTweaksData.instance(context);
        int all = 0;

        for (int bit = 0; bit < 28; bit++) {
            all |= 1 << bit;
        }

        data.setPlayerButtonDisabled(all & ~COMPACT_PLAYER_BUTTONS);
        data.setPlayerButtonEnabled(COMPACT_PLAYER_BUTTONS);
        // Next video = the suggestions (like YouTube), not the row of the Home it was opened from
        // (that row showed the video being watched as the first "suggestion")
        data.setSectionPlaylistEnabled(false);
        MainUIData.instance(context).setMenuItemEnabled(MainUIData.MENU_ITEM_ADD_TO_WATCH_LATER);
        prefs.edit().putBoolean(KEY_TV_DEFAULTS_APPLIED, true).apply();
    }

    /**
     * Once per install (phones only): installs that still have the old TV default (4K/1080p) get the normal
     * quality (720p). After that the quality stays whatever the user picks.
     */
    public static void applyNormalQualityOnce(Context context) {
        SharedPreferences prefs = getPrefs(context);

        if (prefs.getBoolean(KEY_NORMAL_QUALITY_APPLIED, false) || !DeviceMode.isMobile(context)) {
            return;
        }

        PlayerData data = PlayerData.instance(context);
        FormatItem current = data.getFormat(FormatItem.TYPE_VIDEO);

        if (current == null || current.equals(FormatItem.VIDEO_4K_VP9_60) || current.equals(FormatItem.VIDEO_FHD_VP9_60)) {
            data.setFormat(data.getDefaultVideoFormat());
        }

        prefs.edit().putBoolean(KEY_NORMAL_QUALITY_APPLIED, true).apply();
    }

    /**
     * Once per install (phones only): the video menu also offers "Share" and "Watch later", like YouTube.
     */
    public static void applyMobileMenuOnce(Context context) {
        SharedPreferences prefs = getPrefs(context);

        if (prefs.getBoolean(KEY_MOBILE_MENU_APPLIED, false) || !DeviceMode.isMobile(context)) {
            return;
        }

        MainUIData.instance(context).setMenuItemEnabled(MainUIData.MENU_ITEM_SHARE_LINK | MainUIData.MENU_ITEM_ADD_TO_WATCH_LATER);
        prefs.edit().putBoolean(KEY_MOBILE_MENU_APPLIED, true).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
