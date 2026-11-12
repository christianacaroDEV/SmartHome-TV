package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Family profiles on one phone: each person has its own Favorites topics and its own "Watch later" list.
 * "Who's watching?" is asked when the app opens and there is more than one profile.<br/>
 * The first profile (id 0) keeps the data saved before profiles existed.
 */
public final class MobileProfiles {
    private static final String PREFS_NAME = "bluetube_defaults";
    private static final String KEY_PROFILES = "mobile_profiles";
    private static final String KEY_CURRENT = "mobile_profile_current";
    private static final int MAX_PROFILES = 6;
    private static final int[] COLORS = {0xFF2F80FF, 0xFFE5484D, 0xFF30A46C, 0xFFF5A524, 0xFF8E4EC6, 0xFF12A594};
    private static final int ACCENT = 0xFF2F80FF;

    public interface OnProfileChanged {
        void onProfileChanged();
    }

    public static class Profile {
        public final int id;
        public final String name;
        public final int color;

        Profile(int id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    private MobileProfiles() {
    }

    // ---------------------------------------------------------------------------------------------
    // Data
    // ---------------------------------------------------------------------------------------------

    public static List<Profile> getAll(Context context) {
        List<Profile> result = new ArrayList<>();

        try {
            JSONArray items = new JSONArray(prefs(context).getString(KEY_PROFILES, "[]"));

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                result.add(new Profile(item.getInt("id"), item.getString("name"), item.getInt("color")));
            }
        } catch (JSONException e) {
            // start again with the default profile
        }

        if (result.isEmpty()) {
            result.add(new Profile(0, context.getString(R.string.mobile_profile_default), COLORS[0]));
        }

        return result;
    }

    public static Profile getCurrent(Context context) {
        int id = prefs(context).getInt(KEY_CURRENT, 0);

        for (Profile profile : getAll(context)) {
            if (profile.id == id) {
                return profile;
            }
        }

        return getAll(context).get(0);
    }

    /**
     * Preference key of the current profile: profile 0 keeps the original key (data saved before profiles).
     */
    public static String key(Context context, String baseKey) {
        int id = getCurrent(context).id;
        return id == 0 ? baseKey : baseKey + "_p" + id;
    }

    public static boolean hasSeveral(Context context) {
        return getAll(context).size() > 1;
    }

    private static void save(Context context, List<Profile> profiles) {
        JSONArray items = new JSONArray();

        try {
            for (Profile profile : profiles) {
                items.put(new JSONObject().put("id", profile.id).put("name", profile.name).put("color", profile.color));
            }
        } catch (JSONException e) {
            return;
        }

        prefs(context).edit().putString(KEY_PROFILES, items.toString()).apply();
    }

    private static void setCurrent(Context context, int id) {
        prefs(context).edit().putInt(KEY_CURRENT, id).apply();
    }

    private static Profile add(Context context, String name) {
        List<Profile> profiles = getAll(context);
        int id = 0;

        for (Profile profile : profiles) {
            id = Math.max(id, profile.id + 1);
        }

        Profile profile = new Profile(id, name, COLORS[profiles.size() % COLORS.length]);
        profiles.add(profile);
        save(context, profiles);
        return profile;
    }

    private static void remove(Context context, Profile toRemove) {
        List<Profile> profiles = getAll(context);
        List<Profile> result = new ArrayList<>();

        for (Profile profile : profiles) {
            if (profile.id != toRemove.id) {
                result.add(profile);
            }
        }

        save(context, result);

        // Its own saved data goes too
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String base : new String[] {"mobile_topics", "mobile_topics_asked", "mobile_watch_later"}) {
            editor.remove(base + "_p" + toRemove.id);
        }
        editor.apply();

        if (getCurrent(context).id == toRemove.id || prefs(context).getInt(KEY_CURRENT, 0) == toRemove.id) {
            setCurrent(context, result.isEmpty() ? 0 : result.get(0).id);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // "Who's watching?"
    // ---------------------------------------------------------------------------------------------

    /**
     * Profile picker: tap = choose, long press = delete, "+" = new profile.
     */
    public static void showPicker(Activity activity, boolean cancelable, OnProfileChanged onChanged) {
        if (activity.isFinishing()) {
            return;
        }

        float density = activity.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(cancelable);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(density, 20), dp(density, 26), dp(density, 20), dp(density, 18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF0F1C3A);
        background.setCornerRadius(dp(density, 20));
        root.setBackground(background);

        TextView title = new TextView(activity);
        title.setText(R.string.mobile_profile_who);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView hint = new TextView(activity);
        hint.setText(R.string.mobile_profile_hint);
        hint.setTextColor(0xFF9FB3D9);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(density, 6), 0, dp(density, 16));
        root.addView(hint);

        // Profiles, 3 per row
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        List<Profile> profiles = getAll(activity);
        int currentId = getCurrent(activity).id;
        List<View> cells = new ArrayList<>();

        for (Profile profile : profiles) {
            View cell = profileCell(activity, density, profile.name, profile.color, profile.id == currentId, false);
            cell.setOnClickListener(v -> {
                boolean changed = profile.id != getCurrent(activity).id;
                setCurrent(activity, profile.id);
                dialog.dismiss();
                if (changed && onChanged != null) {
                    onChanged.onProfileChanged();
                }
            });
            cell.setOnLongClickListener(v -> {
                if (getAll(activity).size() <= 1) {
                    return true; // the last profile can't be deleted
                }
                new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage(activity.getString(R.string.mobile_profile_delete_confirm, profile.name))
                        .setNegativeButton(R.string.mobile_cancel, null)
                        .setPositiveButton(R.string.mobile_profile_delete, (d, w) -> {
                            boolean wasCurrent = profile.id == getCurrent(activity).id;
                            remove(activity, profile);
                            dialog.dismiss();
                            if (wasCurrent && onChanged != null) {
                                onChanged.onProfileChanged();
                            }
                            showPicker(activity, cancelable, onChanged);
                        })
                        .show();
                return true;
            });
            cells.add(cell);
        }

        if (profiles.size() < MAX_PROFILES) {
            View add = profileCell(activity, density, activity.getString(R.string.mobile_profile_add), 0x33FFFFFF, false, true);
            add.setOnClickListener(v -> askName(activity, name -> {
                Profile profile = add(activity, name);
                setCurrent(activity, profile.id);
                dialog.dismiss();
                if (onChanged != null) {
                    onChanged.onProfileChanged();
                }
            }));
            cells.add(add);
        }

        LinearLayout row = null;

        for (int i = 0; i < cells.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                grid.addView(row);
            }
            row.addView(cells.get(i), new LinearLayout.LayoutParams(dp(density, 96), ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(grid);
        root.addView(scroll);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(density, 32), dp(density, 420)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static View profileCell(Context context, float density, String name, int color, boolean current, boolean isAdd) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(density, 6), 0, dp(density, 10));

        TextView avatar = new TextView(context);
        avatar.setText(isAdd ? "+" : initial(name));
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(isAdd ? 30 : 26);
        avatar.setTypeface(null, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        if (current) {
            circle.setStroke(dp(density, 3), Color.WHITE);
        }
        avatar.setBackground(circle);
        cell.addView(avatar, new LinearLayout.LayoutParams(dp(density, 64), dp(density, 64)));

        TextView label = new TextView(context);
        label.setText(name);
        label.setTextColor(current ? Color.WHITE : 0xFFC5D2EE);
        label.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
        label.setTextSize(13);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(density, 4), dp(density, 6), dp(density, 4), 0);
        cell.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private interface OnName {
        void onName(String name);
    }

    private static void askName(Activity activity, OnName onName) {
        float density = activity.getResources().getDisplayMetrics().density;
        EditText field = new EditText(activity);
        field.setHint(R.string.mobile_profile_name_hint);
        field.setSingleLine(true);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(20)});
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(dp(density, 20), dp(density, 8), dp(density, 20), 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.mobile_profile_add)
                .setView(box)
                .setNegativeButton(R.string.mobile_cancel, null)
                .setPositiveButton(R.string.mobile_topics_save, (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (!name.isEmpty()) {
                        onName.onName(name);
                    }
                })
                .show();
    }

    public static String initial(String name) {
        return TextUtils.isEmpty(name) ? "?" : name.substring(0, 1).toUpperCase();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static int dp(float density, int value) {
        return Math.round(value * density);
    }
}
