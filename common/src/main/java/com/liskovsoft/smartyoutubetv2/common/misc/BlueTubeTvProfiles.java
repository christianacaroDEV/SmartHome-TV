package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TV (remote control) side of the family profiles: "Who is watching?" and the Favorites topics,
 * as simple lists. The profiles themselves come from the phone through the account (see {@link BlueTubeSync}).
 */
public final class BlueTubeTvProfiles {
    private static boolean sAskedThisRun;

    private BlueTubeTvProfiles() {
    }

    /**
     * TV start: brings the profiles/topics of the account, then asks "Who is watching?" (once per run)
     * when there are several profiles.
     */
    public static void onTvStart(Context context, Runnable onChanged) {
        Context appContext = context.getApplicationContext();

        BlueTubeSync.pull(appContext, onChanged, () -> {
            if (!sAskedThisRun && BlueTubeSync.getProfiles(appContext).size() > 1) {
                sAskedThisRun = true;
                showWhoIsWatching(context, onChanged);
            }
        });
    }

    public static void showWhoIsWatching(Context context, Runnable onChanged) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        String title = context.getString(R.string.mobile_profile_who);
        dialog.appendRadioCategory(title, createProfileOptions(context, dialog, onChanged, true));
        dialog.showDialog(title);
    }

    /**
     * Settings: profile + its Favorites topics.
     */
    public static void showSettings(Context context, Runnable onChanged) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        final boolean[] changed = {false};

        dialog.appendRadioCategory(context.getString(R.string.mobile_profile_who), createProfileOptions(context, dialog, () -> {
            changed[0] = true;
            // the topics list below belongs to the previous profile: show the dialog again
            dialog.closeDialog();
            showSettings(context, onChanged);
        }, false));

        List<OptionItem> topicOptions = new ArrayList<>();
        String[] labels = BlueTubeSync.getTopicLabels(context);
        List<Integer> selected = BlueTubeSync.getSelectedTopics(context);

        for (int i = 0; i < labels.length; i++) {
            int index = i;
            topicOptions.add(UiOptionItem.from(labels[i], option -> {
                setTopic(context, index, option.isSelected());
                changed[0] = true;
            }, selected.contains(i)));
        }

        String topicsTitle = context.getString(R.string.mobile_tv_topics_of, BlueTubeSync.getCurrentProfile(context).name);
        dialog.appendCheckedCategory(topicsTitle, topicOptions);

        dialog.showDialog(context.getString(R.string.mobile_tv_profiles_title), () -> {
            if (changed[0]) {
                BlueTubeSync.push(context);
                if (onChanged != null) {
                    onChanged.run();
                }
            }
        });
    }

    private static List<OptionItem> createProfileOptions(Context context, AppDialogPresenter dialog, Runnable onChanged, boolean closeOnPick) {
        List<OptionItem> options = new ArrayList<>();
        int current = BlueTubeSync.getCurrentProfile(context).id;

        for (BlueTubeSync.Profile profile : BlueTubeSync.getProfiles(context)) {
            options.add(UiOptionItem.from(profile.name, option -> {
                boolean isNew = BlueTubeSync.getCurrentProfile(context).id != profile.id;
                BlueTubeSync.setCurrentProfile(context, profile.id);

                if (closeOnPick) {
                    dialog.closeDialog();
                }

                if (isNew && onChanged != null) {
                    onChanged.run();
                }
            }, profile.id == current));
        }

        return options;
    }

    private static void setTopic(Context context, int index, boolean enabled) {
        String[] keys = context.getResources().getStringArray(R.array.mobile_topic_keys);

        if (index < 0 || index >= keys.length) {
            return;
        }

        SharedPreferences prefs = BlueTubeSync.prefs(context);
        String prefKey = BlueTubeSync.key(context, BlueTubeSync.KEY_TOPICS);
        List<String> saved = new ArrayList<>(Arrays.asList(prefs.getString(prefKey, "").split(",")));
        saved.remove(keys[index]);

        if (enabled) {
            saved.add(keys[index]);
        }

        // Same order as the topics list (like the phone saves them)
        List<String> ordered = new ArrayList<>();
        for (String key : keys) {
            if (saved.contains(key)) {
                ordered.add(key);
            }
        }

        prefs.edit()
                .putString(prefKey, TextUtils.join(",", ordered))
                .putBoolean(BlueTubeSync.key(context, BlueTubeSync.KEY_TOPICS_ASKED), true)
                .apply();
    }
}
