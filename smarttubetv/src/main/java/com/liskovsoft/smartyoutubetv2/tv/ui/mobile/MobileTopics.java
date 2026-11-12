package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "My topics" (favorite topics picked by the user, each one becomes a row of suggested videos) and
 * "Watch later" (kept on the phone, and also in the YouTube Watch later playlist when signed in).
 */
public final class MobileTopics {
    private static final String PREFS_NAME = "bluetube_defaults";
    private static final String KEY_TOPICS = "mobile_topics";
    private static final String KEY_TOPICS_ASKED = "mobile_topics_asked";
    private static final String KEY_WATCH_LATER = "mobile_watch_later";
    private static final int ACCENT = 0xFF2F80FF;

    public interface OnTopicsSaved {
        void onSaved(List<Integer> topics);
    }

    private MobileTopics() {
    }

    // ---------------------------------------------------------------------------------------------
    // Topics
    // ---------------------------------------------------------------------------------------------

    public static String[] getLabels(Context context) {
        return context.getResources().getStringArray(R.array.mobile_topic_labels);
    }

    public static String[] getQueries(Context context) {
        return context.getResources().getStringArray(R.array.mobile_topic_queries);
    }

    /**
     * Stable ids of the topics (saved choices survive new topics or a different order).
     */
    private static String[] getKeys(Context context) {
        return context.getResources().getStringArray(R.array.mobile_topic_keys);
    }

    /**
     * Indexes of the chosen topics, in the order of the list (grouped by section).
     */
    public static List<Integer> getSelected(Context context) {
        List<Integer> result = new ArrayList<>();
        List<String> saved = new ArrayList<>();

        for (String part : prefs(context).getString(KEY_TOPICS, "").split(",")) {
            saved.add(part.trim());
        }

        String[] keys = getKeys(context);

        for (int i = 0; i < keys.length; i++) {
            if (saved.contains(keys[i])) {
                result.add(i);
            }
        }

        return result;
    }

    private static void setSelected(Context context, List<Integer> topics) {
        String[] keys = getKeys(context);
        List<String> selectedKeys = new ArrayList<>();

        for (int index : topics) {
            selectedKeys.add(keys[index]);
        }

        prefs(context).edit().putString(KEY_TOPICS, TextUtils.join(",", selectedKeys)).putBoolean(KEY_TOPICS_ASKED, true).apply();
    }

    /**
     * The topics were never asked (first use of the app).
     */
    public static boolean shouldAsk(Context context) {
        return !prefs(context).getBoolean(KEY_TOPICS_ASKED, false);
    }

    /**
     * "What do you like to watch?": a grid of topics to tap, plus Done / Not now.
     */
    public static void showPicker(Activity activity, OnTopicsSaved onSaved) {
        if (activity.isFinishing()) {
            return;
        }

        String[] labels = getLabels(activity);
        Set<Integer> selected = new LinkedHashSet<>(getSelected(activity));
        float density = activity.getResources().getDisplayMetrics().density;

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(density, 20), dp(density, 22), dp(density, 20), dp(density, 14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF0F1C3A);
        background.setCornerRadius(dp(density, 20));
        root.setBackground(background);

        TextView title = new TextView(activity);
        title.setText(R.string.mobile_topics_pick_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(activity);
        subtitle.setText(R.string.mobile_topics_pick_subtitle);
        subtitle.setTextColor(0xFF9FB3D9);
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(density, 6), 0, dp(density, 14));
        root.addView(subtitle);

        // Grouped by section (Technology, Money, Movies...), two columns
        int[] sections = activity.getResources().getIntArray(R.array.mobile_topic_sections);
        String[] sectionLabels = activity.getResources().getStringArray(R.array.mobile_topic_section_labels);
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        int column = 0;
        int lastSection = -1;

        for (int i = 0; i < labels.length; i++) {
            int section = i < sections.length ? sections[i] : 0;

            if (section != lastSection) {
                // Fill the last row of the previous section, then the section title
                if (row != null && column == 1) {
                    row.addView(new android.view.View(activity), new LinearLayout.LayoutParams(0, dp(density, 44), 1f));
                }
                lastSection = section;
                column = 0;
                TextView header = new TextView(activity);
                header.setText(section < sectionLabels.length ? sectionLabels[section] : "");
                header.setTextColor(Color.WHITE);
                header.setTextSize(15);
                header.setTypeface(null, Typeface.BOLD);
                header.setPadding(dp(density, 6), dp(density, grid.getChildCount() == 0 ? 2 : 14), 0, dp(density, 4));
                grid.addView(header);
            }

            if (column == 0) {
                row = new LinearLayout(activity);
                row.setBaselineAligned(false); // one-line and two-line chips side by side stay aligned
                grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            column = (column + 1) % 2;
            final int index = i;
            TextView chip = new TextView(activity);
            chip.setText(labels[i]);
            chip.setTextSize(13);
            chip.setMaxLines(2); // long names ("Programming and software") use two lines instead of being cut
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setMinHeight(dp(density, 54)); // room for two lines, every chip the same height
            chip.setPadding(dp(density, 12), dp(density, 6), dp(density, 8), dp(density, 6));
            styleChip(chip, selected.contains(index), density);
            chip.setOnClickListener(v -> {
                if (!selected.remove(index)) {
                    selected.add(index);
                }
                styleChip(chip, selected.contains(index), density);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(dp(density, 4), dp(density, 4), dp(density, 4), dp(density, 4));
            row.addView(chip, params);
        }

        if (column == 1 && row != null) {
            row.addView(new android.view.View(activity), new LinearLayout.LayoutParams(0, dp(density, 44), 1f));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(grid);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(density, 12), 0, 0);
        TextView skip = button(activity, R.string.mobile_topics_skip, false, density);
        skip.setOnClickListener(v -> {
            prefs(activity).edit().putBoolean(KEY_TOPICS_ASKED, true).apply();
            dialog.dismiss();
        });
        buttons.addView(skip);
        TextView save = button(activity, R.string.mobile_topics_save, true, density);
        save.setOnClickListener(v -> {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < labels.length; i++) {
                if (selected.contains(i)) {
                    result.add(i);
                }
            }
            setSelected(activity, result);
            dialog.dismiss();
            if (onSaved != null) {
                onSaved.onSaved(result);
            }
        });
        buttons.addView(save);
        root.addView(buttons);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(density, 32), dp(density, 520));
            int height = Math.min(activity.getResources().getDisplayMetrics().heightPixels - dp(density, 80), dp(density, 640));
            window.setLayout(width, height);
        }
        dialog.show();
    }

    private static void styleChip(TextView chip, boolean selected, float density) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(density, 22));
        shape.setColor(selected ? ACCENT : 0x1FFFFFFF);
        if (!selected) {
            shape.setStroke(dp(density, 1), 0x33FFFFFF);
        }
        chip.setBackground(shape);
        chip.setTextColor(selected ? Color.WHITE : 0xFFDDE6F7);
        chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private static TextView button(Context context, int textResId, boolean primary, float density) {
        TextView button = new TextView(context);
        button.setText(textResId);
        button.setTextSize(15);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : 0xFF9FB3D9);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(density, 20), 0, dp(density, 20), 0);
        if (primary) {
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(density, 22));
            shape.setColor(ACCENT);
            button.setBackground(shape);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(density, 44));
        params.setMargins(dp(density, 8), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    // ---------------------------------------------------------------------------------------------
    // Watch later
    // ---------------------------------------------------------------------------------------------

    public static boolean isInWatchLater(Context context, Video video) {
        if (video == null || video.videoId == null) {
            return false;
        }

        JSONArray items = loadWatchLater(context);

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && video.videoId.equals(item.optString("id"))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Adds or removes the video. Also updates the YouTube Watch later playlist when the user is signed in.
     */
    public static void toggleWatchLater(Context context, Video video) {
        if (video == null || video.videoId == null) {
            return;
        }

        JSONArray items = loadWatchLater(context);
        JSONArray result = new JSONArray();
        boolean removed = false;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && video.videoId.equals(item.optString("id"))) {
                removed = true;
            } else if (item != null) {
                result.put(item);
            }
        }

        if (!removed) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", video.videoId);
                item.put("title", video.getTitle());
                item.put("author", !TextUtils.isEmpty(video.author) ? video.author : video.getAuthor());
                item.put("image", video.cardImageUrl != null ? video.cardImageUrl : "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg");
                // Newest first
                JSONArray withNew = new JSONArray();
                withNew.put(item);
                for (int i = 0; i < result.length(); i++) {
                    withNew.put(result.get(i));
                }
                result = withNew;
            } catch (JSONException e) {
                return;
            }
        }

        prefs(context).edit().putString(KEY_WATCH_LATER, result.toString()).apply();

        if (YouTubeSignInService.instance().isSigned()) {
            MediaServiceManager.instance().addRemoveFromWatchLaterPlaylist(video, !removed);
        }

        MessageHelpers.showMessage(context, removed ? R.string.mobile_watch_later_removed : R.string.mobile_watch_later_added);
    }

    public static List<Video> getWatchLater(Context context) {
        List<Video> result = new ArrayList<>();
        JSONArray items = loadWatchLater(context);

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);

            if (item == null || TextUtils.isEmpty(item.optString("id"))) {
                continue;
            }

            Video video = Video.from(item.optString("id"));
            video.title = item.optString("title");
            video.author = item.optString("author");
            video.secondTitle = video.author;
            video.cardImageUrl = item.optString("image");
            result.add(video);
        }

        return result;
    }

    private static JSONArray loadWatchLater(Context context) {
        try {
            return new JSONArray(prefs(context).getString(KEY_WATCH_LATER, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static int dp(float density, int value) {
        return Math.round(value * density);
    }
}
