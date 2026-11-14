package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * BlueTube "Live TV": free official TV streams (HLS) and the live stream of official YouTube channels,
 * shown as channels with a number, grouped by category, with "My channels" (favorites) and zapping in the player.<br/>
 * The list ships with the app (res/raw/live_tv_channels.json) and is refreshed from the BlueTube release, so dead
 * streams can be replaced without an app update.
 */
public final class LiveTv {
    private static final String TAG = LiveTv.class.getSimpleName();
    public static final int SECTION_ID = 97;
    private static final String ID_PREFIX = "bluetube-tv-";
    private static final String REMOTE_URL = "https://github.com/christianacaroDEV/SmartHome-TV/releases/download/latest/live_tv_channels.json";
    private static final String CACHE_FILE = "live_tv_channels.json";
    private static final String PREFS = "bluetube_live_tv";
    private static final String KEY_FAVORITES = "favorites";
    private static final long REMOTE_REFRESH_MS = 6 * 60 * 60 * 1_000L;
    private static final long YOUTUBE_CACHE_MS = 15 * 60 * 1_000L;
    private static final int TIMEOUT_MS = 6_000;
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Pattern CANONICAL = Pattern.compile("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([\\w-]{11})\"");
    private static final Pattern TITLE = Pattern.compile("<meta name=\"title\" content=\"([^\"]*)\"");

    public static final class Channel {
        public final int number;
        public final String id;
        public final String name;
        public final String category;
        public final List<String> hls;
        @Nullable public final String youtubeHandle;
        @Nullable public final String card;
        @Nullable public final String logo;

        Channel(JSONObject json) {
            number = json.optInt("n");
            id = json.optString("id");
            name = json.optString("name");
            category = json.optString("cat");
            List<String> urls = new ArrayList<>();
            JSONArray array = json.optJSONArray("hls");
            for (int i = 0; array != null && i < array.length(); i++) {
                urls.add(array.optString(i));
            }
            hls = urls;
            youtubeHandle = emptyToNull(json.optString("yt"));
            card = emptyToNull(json.optString("card"));
            logo = emptyToNull(json.optString("logo"));
        }

        public boolean isYouTube() {
            return youtubeHandle != null;
        }
    }

    private static final class LiveInfo {
        final String videoId;
        final String title;
        final long timeMs;

        LiveInfo(String videoId, String title) {
            this.videoId = videoId;
            this.title = title;
            this.timeMs = System.currentTimeMillis();
        }
    }

    private static List<Channel> sChannels;
    private static long sRemoteCheckedMs;
    /** Video id (fake id for streams, live video id for YouTube) -> channel */
    private static final Map<String, Channel> sByVideoId = new ConcurrentHashMap<>();
    private static final Map<String, LiveInfo> sYouTubeLive = new ConcurrentHashMap<>();
    /** The channels in number order, as shown in the section (zapping order) */
    private static volatile List<Video> sZapping = Collections.emptyList();
    private static String sAttemptVideoId;
    private static int sAttempt;

    private LiveTv() {
    }

    public static boolean isStreamId(@Nullable String videoId) {
        return videoId != null && videoId.startsWith(ID_PREFIX);
    }

    /**
     * A channel of the TV that is played from its own stream (not from YouTube): no YouTube requests for it.
     */
    public static boolean isStream(@Nullable Video video) {
        return video != null && isStreamId(video.videoId);
    }

    public static boolean isChannel(@Nullable Video video) {
        return video != null && video.videoId != null && (isStreamId(video.videoId) || sByVideoId.containsKey(video.videoId));
    }

    @Nullable
    public static Channel find(Context context, @Nullable Video video) {
        if (video == null || video.videoId == null) {
            return null;
        }

        Channel channel = sByVideoId.get(video.videoId);

        if (channel == null && isStreamId(video.videoId)) {
            // E.g. restored after a crash or opened by "Watch together"
            String id = video.videoId.substring(ID_PREFIX.length());
            for (Channel item : getChannels(context)) {
                if (item.id.equals(id)) {
                    channel = item;
                    sByVideoId.put(video.videoId, item);
                    break;
                }
            }
        }

        return channel;
    }

    // Streams with several addresses: try the next one when the current fails

    @Nullable
    public static String getStreamUrl(Context context, Video video) {
        Channel channel = find(context, video);

        if (channel == null || channel.hls.isEmpty()) {
            return null;
        }

        if (!video.videoId.equals(sAttemptVideoId)) {
            sAttemptVideoId = video.videoId;
            sAttempt = 0;
        }

        return channel.hls.get(sAttempt % channel.hls.size());
    }

    /**
     * @return false when every address of the channel failed (the next open starts over)
     */
    public static boolean nextStreamUrl(Context context, Video video) {
        Channel channel = find(context, video);

        if (channel == null || !video.videoId.equals(sAttemptVideoId)) {
            return false;
        }

        sAttempt++;

        if (sAttempt >= channel.hls.size()) {
            sAttempt = 0;
            return false;
        }

        return true;
    }

    // Zapping

    @Nullable
    public static Video getNext(Context context, Video current) {
        return getNeighbor(context, current, 1);
    }

    @Nullable
    public static Video getPrevious(Context context, Video current) {
        return getNeighbor(context, current, -1);
    }

    private static Video getNeighbor(Context context, Video current, int step) {
        List<Video> channels = getZapping(context);
        int size = channels.size();

        if (size < 2 || current == null) {
            return null;
        }

        for (int i = 0; i < size; i++) {
            if (channels.get(i).videoId.equals(current.videoId)) {
                return Video.from(channels.get(((i + step) % size + size) % size));
            }
        }

        return Video.from(channels.get(0));
    }

    public static List<Video> getZapping(Context context) {
        if (sZapping.isEmpty()) {
            // Opened without the section (e.g. after a crash): the streams are enough to zap
            List<Video> result = new ArrayList<>();
            for (Channel channel : getChannels(context)) {
                if (!channel.isYouTube()) {
                    result.add(toVideo(context, channel, null));
                }
            }
            sZapping = result;
        }

        return sZapping;
    }

    // Favorites ("My channels")

    public static boolean isFavorite(Context context, Channel channel) {
        return getFavorites(context).contains(channel.id);
    }

    public static void toggleFavorite(Context context, Channel channel) {
        Set<String> favorites = new HashSet<>(getFavorites(context));

        if (!favorites.remove(channel.id)) {
            favorites.add(channel.id);
        }

        prefs(context).edit().putStringSet(KEY_FAVORITES, favorites).apply();
    }

    private static Set<String> getFavorites(Context context) {
        return prefs(context).getStringSet(KEY_FAVORITES, Collections.<String>emptySet());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Section rows

    /**
     * "My channels" first, then one row per category. YouTube channels appear only while they are live.
     */
    public static Observable<List<MediaGroup>> getRowsObserve(Context context) {
        // The screen's context: its resources follow the language chosen in the app
        return Observable.fromCallable(() -> createRows(context))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private static List<MediaGroup> createRows(Context context) {
        refreshRemoteList(context);
        List<Channel> channels = getChannels(context);
        resolveYouTube(channels);

        List<Video> available = new ArrayList<>();
        for (Channel channel : channels) {
            if (!channel.isYouTube()) {
                available.add(toVideo(context, channel, null));
            } else {
                LiveInfo info = sYouTubeLive.get(channel.youtubeHandle);
                if (info != null && info.videoId != null) {
                    available.add(toVideo(context, channel, info));
                }
            }
        }
        sZapping = available;

        List<MediaGroup> rows = new ArrayList<>();
        Set<String> favorites = getFavorites(context);

        List<MediaItem> mine = new ArrayList<>();
        for (Video video : available) {
            Channel channel = sByVideoId.get(video.videoId);
            if (channel != null && favorites.contains(channel.id)) {
                mine.add(SimpleMediaItem.from(video));
            }
        }
        if (!mine.isEmpty()) {
            rows.add(new Row(context.getString(R.string.live_tv_my_channels), mine));
        }

        String[] keys = context.getResources().getStringArray(R.array.live_tv_category_keys);
        String[] labels = context.getResources().getStringArray(R.array.live_tv_category_labels);
        for (int i = 0; i < keys.length && i < labels.length; i++) {
            List<MediaItem> items = new ArrayList<>();
            for (Video video : available) {
                Channel channel = sByVideoId.get(video.videoId);
                if (channel != null && keys[i].equals(channel.category)) {
                    items.add(SimpleMediaItem.from(video));
                }
            }
            if (!items.isEmpty()) {
                rows.add(new Row(labels[i], items));
            }
        }

        return rows;
    }

    private static Video toVideo(Context context, Channel channel, @Nullable LiveInfo info) {
        Video video = new Video();
        video.videoId = info != null ? info.videoId : ID_PREFIX + channel.id;
        video.title = channel.name;
        String what = info != null && info.title != null && !info.title.isEmpty() ? info.title : categoryLabel(context, channel.category);
        video.secondTitle = context.getString(R.string.live_tv_channel_number, channel.number) + " · " + what;
        video.author = channel.name;
        video.isLive = true;

        if (channel.card != null) {
            video.cardImageUrl = "android.resource://" + context.getPackageName() + "/drawable/" + channel.card;
        } else if (info != null) {
            video.cardImageUrl = "https://i.ytimg.com/vi/" + info.videoId + "/hqdefault_live.jpg";
        } else {
            video.cardImageUrl = channel.logo;
        }
        video.bgImageUrl = info != null ? "https://i.ytimg.com/vi/" + info.videoId + "/maxresdefault_live.jpg" : video.cardImageUrl;

        sByVideoId.put(video.videoId, channel);
        return video;
    }

    private static String categoryLabel(Context context, String category) {
        String[] keys = context.getResources().getStringArray(R.array.live_tv_category_keys);
        String[] labels = context.getResources().getStringArray(R.array.live_tv_category_labels);
        for (int i = 0; i < keys.length && i < labels.length; i++) {
            if (keys[i].equals(category)) {
                // Drop the emoji of the row title
                String label = labels[i];
                int space = label.indexOf(' ');
                return space > 0 && space <= 6 ? label.substring(space + 1) : label;
            }
        }
        return category;
    }

    // The list

    private static synchronized List<Channel> getChannels(Context context) {
        if (sChannels == null) {
            List<Channel> result = parse(readCache(context));
            if (result.isEmpty()) {
                result = parse(readRaw(context));
            }
            sChannels = result;
        }

        return sChannels;
    }

    private static void refreshRemoteList(Context context) {
        if (System.currentTimeMillis() - sRemoteCheckedMs < REMOTE_REFRESH_MS) {
            return;
        }

        String json = download(REMOTE_URL, null);
        sRemoteCheckedMs = System.currentTimeMillis();
        List<Channel> remote = parse(json);

        if (!remote.isEmpty()) {
            synchronized (LiveTv.class) {
                sChannels = remote;
            }
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), CACHE_FILE))) {
                out.write(json.getBytes("UTF-8"));
            } catch (Exception e) {
                Log.e(TAG, "Can't save the channel list: %s", e.getMessage());
            }
        } else {
            sRemoteCheckedMs -= REMOTE_REFRESH_MS - 10 * 60 * 1_000L; // offline or not published: try again in 10 minutes
        }
    }

    private static List<Channel> parse(@Nullable String json) {
        List<Channel> result = new ArrayList<>();

        if (json == null) {
            return result;
        }

        try {
            JSONArray array = new JSONObject(json).getJSONArray("channels");
            for (int i = 0; i < array.length(); i++) {
                Channel channel = new Channel(array.getJSONObject(i));
                if (!channel.id.isEmpty() && (!channel.hls.isEmpty() || channel.isYouTube())) {
                    result.add(channel);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Bad channel list: %s", e.getMessage());
            result.clear();
        }

        return result;
    }

    @Nullable
    private static String readCache(Context context) {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.exists()) {
            return null;
        }
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readAll(in, Integer.MAX_VALUE);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String readRaw(Context context) {
        try (InputStream in = context.getResources().openRawResource(R.raw.live_tv_channels)) {
            return readAll(in, Integer.MAX_VALUE);
        } catch (Exception e) {
            return null;
        }
    }

    // YouTube: the current live stream of each official channel (https://www.youtube.com/@handle/live)

    private static void resolveYouTube(List<Channel> channels) {
        List<Channel> todo = new ArrayList<>();
        for (Channel channel : channels) {
            if (channel.isYouTube()) {
                LiveInfo info = sYouTubeLive.get(channel.youtubeHandle);
                if (info == null || System.currentTimeMillis() - info.timeMs > YOUTUBE_CACHE_MS) {
                    todo.add(channel);
                }
            }
        }

        if (todo.isEmpty()) {
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(6, todo.size()));
        for (Channel channel : todo) {
            executor.execute(() -> {
                LiveInfo info = fetchYouTubeLive(channel.youtubeHandle);
                if (info != null) {
                    sYouTubeLive.put(channel.youtubeHandle, info);
                }
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Nullable
    private static LiveInfo fetchYouTubeLive(String handle) {
        String page = download("https://www.youtube.com/@" + handle + "/live", "\"isLiveNow\":");

        if (page == null) {
            return null; // network error: keep the previous result
        }

        Matcher id = CANONICAL.matcher(page);
        if (!id.find() || !page.contains("\"isLiveNow\":true")) {
            return new LiveInfo(null, null); // not live now
        }

        Matcher title = TITLE.matcher(page);
        return new LiveInfo(id.group(1), title.find() ? unescape(title.group(1)) : null);
    }

    private static String unescape(String text) {
        return text.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    /**
     * @param stopAfter stop reading once this text has been seen (a YouTube page is large)
     */
    @Nullable
    private static String download(String url, @Nullable String stopAfter) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", BROWSER_UA);
            connection.setRequestProperty("Accept-Language", "es-419,es;q=0.9");
            connection.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1");
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return stopAfter != null ? readUntil(in, stopAfter) : readAll(in, 2_000_000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Download error %s: %s", url, e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream in, int limit) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1 && out.size() < limit) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    private static String readUntil(InputStream in, String marker) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        int checked = 0;
        while ((read = in.read(buffer)) != -1 && out.size() < 3_000_000) {
            out.write(buffer, 0, read);
            if (out.size() - checked > 64 * 1024) {
                String text = out.toString("UTF-8");
                int index = text.indexOf(marker);
                if (index != -1 && text.length() > index + 64) {
                    return text;
                }
                checked = out.size();
            }
        }
        return out.toString("UTF-8");
    }

    @Nullable
    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private static final class Row implements MediaGroup {
        private final String mTitle;
        private final List<MediaItem> mItems;

        Row(String title, List<MediaItem> items) {
            mTitle = title;
            mItems = items;
        }

        @Override public int getType() { return MediaGroup.TYPE_UNDEFINED; }
        @Override public List<MediaItem> getMediaItems() { return mItems; }
        @Override public String getTitle() { return mTitle; }
        @Override public String getChannelId() { return null; }
        @Override public String getParams() { return null; }
        @Override public String getReloadPageKey() { return null; }
        @Override public String getNextPageKey() { return null; }
        @Override public String getChannelUrl() { return null; }
        @Override public boolean isEmpty() { return mItems.isEmpty(); }
    }
}
