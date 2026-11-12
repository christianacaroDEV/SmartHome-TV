package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HQDialogController extends BasePlayerController {
    private static final String TAG = HQDialogController.class.getSimpleName();
    private static final int QUALITY_ID = 131;
    private static final int VIDEO_FORMATS_ID = 132;
    private static final int AUDIO_FORMATS_ID = 133;
    // NOTE: using map, because same item could be changed time to time
    private final Map<Integer, OptionCategory> mCategories = new LinkedHashMap<>();
    private final Map<Integer, OptionCategory> mCategoriesInt = new LinkedHashMap<>();
    private final Set<Runnable> mHideListeners = new HashSet<>();
    private AppDialogPresenter mAppDialogPresenter;

    @Override
    public void onInit() {
        mAppDialogPresenter = AppDialogPresenter.instance(getContext());
    }

    @Override
    public void onViewResumed() {
        //updateBackgroundPlayback();
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId == R.id.lb_control_high_quality) {
            onHighQualityClicked();
        }
    }

    private void onHighQualityClicked() {
        fitVideoIntoDialog();

        addSimpleQualityCategory();
        addQualityCategories();
        addAudioLanguage();
        addPresetsCategory();
        addVideoZoomCategory();
        addNetworkEngine();
        addVideoBufferCategory();
        addAudioDelayCategory();
        addPitchEffectCategory();
        addSleepTimerCategory();
        //addBackgroundPlaybackCategory();

        appendOptions(mCategoriesInt);
        appendOptions(mCategories);

        mAppDialogPresenter.showDialog(getContext().getString(R.string.playback_settings), this::onDialogHide);
    }

    private void addQualityCategories() {
        if (getPlayer() == null) {
            return;
        }

        List<FormatItem> videoFormats = getPlayer().getVideoFormats();
        String videoFormatsTitle = getContext().getString(R.string.title_video_formats);

        List<FormatItem> audioFormats = getPlayer().getAudioFormats();
        String audioFormatsTitle = getContext().getString(R.string.title_audio_formats);

        addCategoryInt(OptionCategory.from(
                VIDEO_FORMATS_ID,
                OptionCategory.TYPE_RADIO_LIST,
                videoFormatsTitle,
                UiOptionItem.from(videoFormats, this::selectFormatOption, getContext().getString(R.string.option_disabled))));
        addCategoryInt(OptionCategory.from(
                AUDIO_FORMATS_ID,
                OptionCategory.TYPE_RADIO_LIST,
                audioFormatsTitle,
                UiOptionItem.from(audioFormats, this::selectFormatOption, getContext().getString(R.string.option_disabled))));
    }

    /**
     * BlueTube: "Quality" like YouTube, first in the dialog: Automatic (the default quality) or one entry per
     * resolution the video has (up to 4K/8K). The technical "Video formats" list stays below for advanced users.
     */
    private void addSimpleQualityCategory() {
        if (getPlayer() == null) {
            return;
        }

        List<FormatItem> formats = getPlayer().getVideoFormats();

        if (formats == null || formats.isEmpty()) {
            return;
        }

        FormatItem defaultFormat = getPlayerData().getDefaultVideoFormat();
        FormatItem saved = getPlayerData().getFormat(FormatItem.TYPE_VIDEO);
        boolean isAuto = saved == null || saved.equals(defaultFormat);
        java.util.TreeMap<Integer, FormatItem> byQuality = new java.util.TreeMap<>(java.util.Collections.reverseOrder());
        int selectedQuality = -1;

        for (FormatItem format : formats) {
            int quality = qualityOf(format);

            if (quality <= 0) {
                continue;
            }

            if (format.isSelected()) {
                selectedQuality = quality;
            }

            FormatItem best = byQuality.get(quality);
            // Up to 1080p the AVC stream is lighter for phones and old TVs; above that only VP9/AV1 exist
            if (best == null || (quality <= 1080 && isAvc(format) && !isAvc(best))) {
                byQuality.put(quality, format);
            }
        }

        List<OptionItem> options = new java.util.ArrayList<>();
        options.add(UiOptionItem.from(getContext().getString(R.string.mobile_quality_auto),
                option -> applySimpleQuality(defaultFormat), isAuto));

        for (Map.Entry<Integer, FormatItem> entry : byQuality.entrySet()) {
            FormatItem format = entry.getValue();
            options.add(UiOptionItem.from(qualityLabel(entry.getKey()),
                    option -> applySimpleQuality(format), !isAuto && entry.getKey() == selectedQuality));
        }

        addCategoryInt(OptionCategory.from(QUALITY_ID, OptionCategory.TYPE_RADIO_LIST,
                getContext().getString(R.string.mobile_quality), options));
    }

    private void applySimpleQuality(FormatItem format) {
        if (getPlayer() == null || format == null) {
            return;
        }

        getPlayer().setFormat(format);
        persistFormat(format); // also for the next videos, until the user picks another one

        if (!getPlayer().containsMedia()) {
            getPlayer().reloadPlayback();
        }

        mAppDialogPresenter.closeDialog();
        getPlayer().showOverlay(false);
    }

    /**
     * The short side of the picture: a vertical 1080x1920 video is "1080p", like YouTube shows it.
     */
    private static int qualityOf(FormatItem format) {
        if (format == null) {
            return -1;
        }

        int width = format.getWidth();
        int height = format.getHeight();
        return width > 0 && height > 0 ? Math.min(width, height) : height;
    }

    private static boolean isAvc(FormatItem format) {
        CharSequence title = format.getTitle();
        return title != null && title.toString().contains("avc");
    }

    private static String qualityLabel(int quality) {
        String label = quality + "p";

        if (quality >= 4320) {
            return label + " (8K)";
        } else if (quality >= 2160) {
            return label + " (4K)";
        } else if (quality >= 1440) {
            return label + " (2K)";
        } else if (quality >= 720) {
            return label + " HD";
        }

        return label;
    }

    private void selectFormatOption(OptionItem option) {
        if (getPlayer() == null) {
            return;
        }

        FormatItem formatItem = UiOptionItem.toFormat(option);
        getPlayer().setFormat(formatItem);
        persistFormat(formatItem);

        if (getPlayerData().getFormat(formatItem.getType()).isPreset()) {
            // Preset currently active. Show warning about format reset.
            MessageHelpers.showMessage(getContext(), R.string.video_preset_enabled);
        }

        if (!getPlayer().containsMedia()) {
            getPlayer().reloadPlayback();
        }

        // Make result easily be spotted by the user
        if (formatItem.getType() == FormatItem.TYPE_VIDEO) {
            getPlayer().showOverlay(false);
        }
    }

    private void persistFormat(FormatItem formatItem) {
        if (formatItem.getType() == FormatItem.TYPE_VIDEO) {
            if (!getPlayerData().getFormat(FormatItem.TYPE_VIDEO).isPreset()) {
                getPlayerData().setFormat(formatItem);
            } else {
                getPlayerData().setTempVideoFormat(formatItem);
            }
        } else {
            getPlayerData().setFormat(formatItem);
        }
    }

    private void addVideoBufferCategory() {
        if (getPlayer() == null) {
            return;
        }
        addCategoryInt(AppDialogUtil.createVideoBufferCategory(getContext(),
                () -> getPlayer().restartEngine()));
    }

    private void addAudioDelayCategory() {
        if (getPlayer() == null) {
            return;
        }
        addCategoryInt(AppDialogUtil.createAudioDelayCategory(getContext(),
                () -> getPlayer().restartEngine()));
    }

    private void addPitchEffectCategory() {
        addCategoryInt(AppDialogUtil.createPitchEffectCategory(getContext()));
    }

    private void addSleepTimerCategory() {
        addCategoryInt(AppDialogUtil.createSleepCountdownCategory(getContext()));
        addCategoryInt(AppDialogUtil.createSleepTimerCategory(getContext()));
    }

    private void addAudioLanguage() {
        if (getPlayer() == null) {
            return;
        }
        addCategoryInt(AppDialogUtil.createAudioLanguageCategory(getContext(),
                () -> getPlayer().restartEngine()));
    }

    private void addNetworkEngine() {
        if (getPlayer() == null) {
            return;
        }
        addCategoryInt(AppDialogUtil.createNetworkEngineCategory(getContext(),
                () -> getPlayer().restartEngine()));
    }

    private void onDialogHide() {
        //updateBackgroundPlayback();

        for (Runnable listener : mHideListeners) {
            listener.run();
        }

        mHideListeners.clear();
        mCategories.clear();
        mCategoriesInt.clear();
    }

    //private void updateBackgroundPlayback() {
    //    ViewManager.instance(getContext()).blockTop(null);
    //
    //    if (getPlayer() != null) {
    //        getPlayer().setBackgroundMode(getPlayerData().getBackgroundMode());
    //    }
    //}

    //private void addBackgroundPlaybackCategory() {
    //    OptionCategory category =
    //            AppDialogUtil.createBackgroundPlaybackCategory(getContext(), getPlayerData(), GeneralData.instance(getContext()), this::updateBackgroundPlayback);
    //
    //    addCategoryInt(category);
    //}

    private void addPresetsCategory() {
        addCategoryInt(AppDialogUtil.createVideoPresetsCategory(
                getContext(), () -> {
                    if (getPlayer() == null) {
                        return;
                    }

                    FormatItem format = getPlayerData().getFormat(FormatItem.TYPE_VIDEO);
                    getPlayer().setFormat(format);

                    if (!getPlayer().containsMedia()) {
                        getPlayer().reloadPlayback();
                    }

                    // Make result easily be spotted by the user
                    getPlayer().showOverlay(false);
                }
        ));
    }

    private void addVideoZoomCategory() {
        if (getPlayer() == null) {
            return;
        }

        addCategoryInt(AppDialogUtil.createVideoZoomCategory(
                getContext(), () -> {
                    getPlayer().setResizeMode(getPlayerData().getResizeMode());
                    getPlayer().setZoomPercents(getPlayerData().getZoomPercents());

                    // Make result easily be spotted by the user
                    getPlayer().showOverlay(false);
                }));
    }

    private void removeCategoryInt(int id) {
        mCategoriesInt.remove(id);
    }

    private void addCategoryInt(OptionCategory category) {
        mCategoriesInt.put(category.id, category);
    }

    public void removeCategory(int id) {
        mCategories.remove(id);
    }

    public void addCategory(OptionCategory category) {
        mCategories.put(category.id, category);
    }

    public void addOnDialogHide(Runnable listener) {
        mHideListeners.add(listener);
    }

    public void removeOnDialogHide(Runnable listener) {
        mHideListeners.remove(listener);
    }

    private void appendOptions(Map<Integer, OptionCategory> categories) {
        for (OptionCategory category : categories.values()) {
            mAppDialogPresenter.appendCategory(category);
        }
    }
}
