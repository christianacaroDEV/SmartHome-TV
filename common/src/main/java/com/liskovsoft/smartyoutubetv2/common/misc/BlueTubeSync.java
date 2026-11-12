package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Family profiles and their Favorites topics, shared by the phone and the TV.<br/>
 * Data lives on the device (prefs) and, when the user is signed in, also in the YouTube account:
 * every profile is a private playlist named {@code "BlueTube · <name> [topic1,topic2]"}.
 * So the same account sees the same profiles and topics on every device.<br/>
 * "Watch later" is the real YouTube Watch later playlist of the account.
 */
public final class BlueTubeSync {
    private static final String TAG = BlueTubeSync.class.getSimpleName();
    public static final String PREFS_NAME = "bluetube_defaults";
    public static final String KEY_PROFILES = "mobile_profiles";
    public static final String KEY_CURRENT = "mobile_profile_current";
    public static final String KEY_TOPICS = "mobile_topics";
    public static final String KEY_TOPICS_ASKED = "mobile_topics_asked";
    /** Browse id of the account Watch later playlist */
    public static final String WATCH_LATER_BROWSE_ID = "VLWL";
    /** TV sidebar section with the Favorites (id outside the MediaGroup types) */
    public static final int FAVORITES_SECTION_ID = 99;
    private static final String PREFIX = "BlueTube · ";
    /** The playlists request needs some video (any one): the first YouTube video, it will not disappear */
    private static final String ANY_VIDEO_ID = "jNQXAC9IVRw";
    private static final int MAX_PROFILES = 6;
    public static final int[] COLORS = {0xFF2F80FF, 0xFFE5484D, 0xFF30A46C, 0xFFF5A524, 0xFF8E4EC6, 0xFF12A594};

    public static class Profile {
        public final int id;
        public final String name;
        public final int color;

        public Profile(int id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    private BlueTubeSync() {
    }

    // ---------------------------------------------------------------------------------------------
    // Local data
    // ---------------------------------------------------------------------------------------------

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static List<Profile> getProfiles(Context context) {
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

    public static void saveProfiles(Context context, List<Profile> profiles) {
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

    public static Profile getCurrentProfile(Context context) {
        int id = prefs(context).getInt(KEY_CURRENT, 0);
        List<Profile> profiles = getProfiles(context);

        for (Profile profile : profiles) {
            if (profile.id == id) {
                return profile;
            }
        }

        return profiles.get(0);
    }

    public static void setCurrentProfile(Context context, int id) {
        prefs(context).edit().putInt(KEY_CURRENT, id).apply();
    }

    /**
     * Preference key of a profile: profile 0 keeps the original key (data saved before profiles).
     */
    public static String key(int profileId, String baseKey) {
        return profileId == 0 ? baseKey : baseKey + "_p" + profileId;
    }

    public static String key(Context context, String baseKey) {
        return key(getCurrentProfile(context).id, baseKey);
    }

    /**
     * Indexes (in the topics arrays) of the Favorites topics of the current profile.
     */
    public static List<Integer> getSelectedTopics(Context context) {
        List<Integer> result = new ArrayList<>();
        List<String> saved = Arrays.asList(prefs(context).getString(key(context, KEY_TOPICS), "").split(","));
        String[] keys = context.getResources().getStringArray(R.array.mobile_topic_keys);

        for (int i = 0; i < keys.length; i++) {
            if (saved.contains(keys[i])) {
                result.add(i);
            }
        }

        return result;
    }

    /**
     * Topic names without the leading emoji: older TVs show some of them as empty boxes.
     */
    public static String[] getTopicLabels(Context context) {
        String[] labels = context.getResources().getStringArray(R.array.mobile_topic_labels);
        String[] result = new String[labels.length];

        for (int i = 0; i < labels.length; i++) {
            String clean = labels[i].replaceFirst("^[^\\p{L}\\p{N}]+", "").trim();
            result[i] = clean.isEmpty() ? labels[i] : clean;
        }

        return result;
    }

    public static String[] getTopicQueries(Context context) {
        return context.getResources().getStringArray(R.array.mobile_topic_queries);
    }

    // ---------------------------------------------------------------------------------------------
    // Account sync
    // ---------------------------------------------------------------------------------------------

    private static boolean isSigned() {
        try {
            return YouTubeSignInService.instance().isSigned();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static MediaItemService itemService() {
        return YouTubeServiceManager.instance().getMediaItemService();
    }

    /**
     * Saves the current profile (name + topics) in the account.
     */
    public static void push(Context context) {
        push(context, getCurrentProfile(context));
    }

    public static void push(Context context, Profile profile) {
        if (!isSigned() || profile == null) {
            return;
        }

        Context appContext = context.getApplicationContext();
        String topics = prefs(appContext).getString(key(profile.id, KEY_TOPICS), "");
        String title = PREFIX + cleanName(profile.name) + " [" + topics + "]";

        RxHelper.runAsyncUser(() -> {
            PlaylistInfo existing = findPlaylist(itemService().getPlaylistsInfo(ANY_VIDEO_ID), profile.name);
            Log.d(TAG, "push %s: exists %s", title, existing != null);

            if (existing == null) {
                // YouTube doesn't create an empty playlist: create it with a video and take the video out
                RxHelper.runBlocking(itemService().createPlaylistObserve(title, ANY_VIDEO_ID));
                PlaylistInfo created = findPlaylist(itemService().getPlaylistsInfo(ANY_VIDEO_ID), profile.name);
                Log.d(TAG, "Profile playlist created: %s", created != null);
                if (created != null) {
                    itemService().removeFromPlaylist(created.getPlaylistId(), ANY_VIDEO_ID);
                }
            } else if (!title.equals(existing.getTitle())) {
                itemService().renamePlaylist(existing.getPlaylistId(), title);
            }
        }, error -> Log.e(TAG, "push error: %s", error.getMessage()), null);
    }

    /**
     * Removes the playlist of a deleted profile from the account.
     */
    public static void remove(Context context, String profileName) {
        if (!isSigned() || profileName == null) {
            return;
        }

        RxHelper.runAsyncUser(() -> {
            PlaylistInfo existing = findPlaylist(itemService().getPlaylistsInfo(ANY_VIDEO_ID), profileName);

            if (existing != null) {
                itemService().removePlaylist(existing.getPlaylistId());
            }
        }, error -> Log.e(TAG, "remove error: %s", error.getMessage()), null);
    }

    /**
     * Brings the profiles and topics saved in the account to this device (new profiles are added,
     * the topics of the existing ones are updated). Local profiles missing in the account are uploaded.<br/>
     * onChanged runs on the main thread, only when something changed here.
     */
    public static void pull(Context context, Runnable onChanged) {
        pull(context, onChanged, null);
    }

    /**
     * onDone always runs on the main thread at the end (also when not signed in or on error).
     */
    public static void pull(Context context, Runnable onChanged, Runnable onDone) {
        Handler handler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "pull: signed %s", isSigned());

        if (!isSigned()) {
            if (onDone != null) {
                handler.post(onDone);
            }
            return;
        }

        Context appContext = context.getApplicationContext();
        final boolean[] changed = {false};

        RxHelper.runAsyncUser(() -> {
            List<PlaylistInfo> infos = itemService().getPlaylistsInfo(ANY_VIDEO_ID);
            Log.d(TAG, "pull: %s playlists", infos != null ? infos.size() : -1);

            if (infos == null) {
                return;
            }

            List<Profile> profiles = getProfiles(appContext);
            List<String> remoteNames = new ArrayList<>();
            SharedPreferences.Editor editor = prefs(appContext).edit();

            for (PlaylistInfo info : infos) {
                String[] parsed = parse(info.getTitle());
                Log.d(TAG, "pull: playlist %s", info.getTitle());

                if (parsed == null) {
                    continue;
                }

                String name = parsed[0];
                String topics = parsed[1];
                remoteNames.add(name.toLowerCase());
                Profile profile = findProfile(profiles, name);

                if (profile == null) {
                    if (profiles.size() >= MAX_PROFILES) {
                        continue;
                    }

                    int id = 0;
                    for (Profile item : profiles) {
                        id = Math.max(id, item.id + 1);
                    }
                    profile = new Profile(id, name, COLORS[profiles.size() % COLORS.length]);
                    profiles.add(profile);
                    saveProfiles(appContext, profiles);
                    changed[0] = true;
                }

                String localTopics = prefs(appContext).getString(key(profile.id, KEY_TOPICS), "");

                if (!topics.equals(localTopics)) {
                    editor.putString(key(profile.id, KEY_TOPICS), topics);
                    changed[0] = true;
                }

                editor.putBoolean(key(profile.id, KEY_TOPICS_ASKED), true);
            }

            editor.commit();

            // Profiles created here before signing in (or offline) go to the account too
            for (Profile profile : profiles) {
                if (!remoteNames.contains(cleanName(profile.name).toLowerCase())
                        && !TextUtils.isEmpty(prefs(appContext).getString(key(profile.id, KEY_TOPICS), ""))) {
                    push(appContext, profile);
                }
            }
        }, error -> {
            Log.e(TAG, "pull error: %s", error.getMessage());
            if (onDone != null) {
                handler.post(onDone);
            }
        }, () -> {
            if (changed[0] && onChanged != null) {
                handler.post(onChanged);
            }
            if (onDone != null) {
                handler.post(onDone);
            }
        });
    }

    private static PlaylistInfo findPlaylist(List<PlaylistInfo> infos, String profileName) {
        if (infos == null) {
            return null;
        }

        for (PlaylistInfo info : infos) {
            String[] parsed = parse(info.getTitle());

            if (parsed != null && parsed[0].equalsIgnoreCase(cleanName(profileName))) {
                return info;
            }
        }

        return null;
    }

    private static Profile findProfile(List<Profile> profiles, String name) {
        for (Profile profile : profiles) {
            if (cleanName(profile.name).equalsIgnoreCase(name)) {
                return profile;
            }
        }

        return null;
    }

    /**
     * "BlueTube · Papi [trading,crypto]" -> {"Papi", "trading,crypto"}
     */
    private static String[] parse(String title) {
        if (title == null || !title.startsWith(PREFIX)) {
            return null;
        }

        String rest = title.substring(PREFIX.length());
        int open = rest.lastIndexOf(" [");

        if (open <= 0 || !rest.endsWith("]")) {
            return null;
        }

        return new String[] {rest.substring(0, open).trim(), rest.substring(open + 2, rest.length() - 1).trim()};
    }

    /**
     * No characters that break the request or the title format.
     */
    public static String cleanName(String name) {
        return name == null ? "" : name.replaceAll("[\"\\\\\\[\\]]", "").trim();
    }
}
