package com.liskovsoft.smartyoutubetv2.common.misc.together;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * "Send to TV": a phone with BlueTube sends the video it is watching to a TV with BlueTube, then works as its
 * remote. Through the same database as "Watch together":
 * <pre>
 * /rooms/{tvCode}/cast/info   {name, ts}             the TV says it is on (every 30 s)
 * /rooms/{tvCode}/cast/cmd    {action, videoId, title, positionMs, value, ts}   last order of the phone
 * /rooms/{tvCode}/cast/state  {open, videoId, title, positionMs, durationMs, playing, ts}   what the TV is playing
 * </pre>
 * The TV keeps a fixed 6-digit code; the phone remembers the TVs it was linked to.
 */
public final class BlueCast {
    private static final String TAG = BlueCast.class.getSimpleName();
    private static final String PREFS = "bluetube_cast";
    private static final String KEY_CODE = "tv_code";
    private static final String KEY_TVS = "tvs";
    private static final long INFO_MS = 30_000;
    /** A TV not heard of for this long is off (or BlueTube closed) */
    private static final long ONLINE_MS = 75_000;
    private static final long STATE_MS = 2_000;
    /** The TV reports its state this long after the last order */
    private static final long SESSION_MS = 4 * 60 * 60 * 1_000;
    @SuppressLint("StaticFieldLeak")
    private static BlueCast sInstance;
    private static PlayerHook sHook;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mReceiving;
    private long mLastCommandTs;
    private boolean mFirstCommand = true;
    private long mSessionUntil;
    private final Runnable mInfo = this::sendInfo;
    private final Runnable mState = this::sendState;

    /**
     * The TV player (CastController): plays what the phone sends and says where it is.
     */
    public interface PlayerHook {
        void castPlay(Context context, String videoId, String title, long positionMs);
        /** toggle / play / pause / seek (value = ms to move) / stop */
        void castControl(String action, long value);
        /** null if no video is open */
        JSONObject castState();
    }

    public interface Callback<T> {
        void onResult(T result);
    }

    public static class Tv {
        public String code;
        public String name;
        /** null = unknown yet */
        public Boolean online;
    }

    private BlueCast(Context context) {
        mContext = context.getApplicationContext();
    }

    public static BlueCast instance(Context context) {
        if (sInstance == null) {
            sInstance = new BlueCast(context);
        }

        return sInstance;
    }

    public static void setPlayerHook(PlayerHook hook) {
        sHook = hook;
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String dbUrl() {
        String url = mContext.getString(R.string.together_db_url);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String url(String code, String node) {
        // Under /rooms like a "Watch together" room (the database only allows that): TV codes start with 0, room
        // codes never do, so they can't meet
        return dbUrl() + "/rooms/" + code + "/cast/" + node + ".json";
    }

    public static String formatCode(String code) {
        return code != null && code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
    }

    // ---------------------------------------------------------------------------------------------
    // TV side
    // ---------------------------------------------------------------------------------------------

    /** The fixed code of this TV (created the first time) */
    public String getTvCode() {
        String code = prefs().getString(KEY_CODE, null);

        if (code == null || !code.startsWith("0")) {
            code = String.format(java.util.Locale.US, "0%05d", 1 + new SecureRandom().nextInt(99_999));
            prefs().edit().putString(KEY_CODE, code).apply();
        }

        return code;
    }

    /** TV: says it is on and waits for what the phones send */
    public void startReceiver() {
        if (mReceiving) {
            return;
        }

        mReceiving = true;
        String code = getTvCode();
        sendInfo();
        stream(code);
    }

    private static String tvName() {
        String model = Build.MODEL != null ? Build.MODEL.trim() : "";
        return model.isEmpty() ? "TV" : "TV " + model;
    }

    private void sendInfo() {
        if (!mReceiving) {
            return;
        }

        String code = getTvCode();
        runAsync(() -> {
            try {
                JSONObject info = new JSONObject().put("name", tvName()).put("ts", new JSONObject().put(".sv", "timestamp"));
                TogetherRoom.request("PUT", url(code, "info"), info.toString());
            } catch (JSONException e) {
                // NOP
            }
        });
        mHandler.removeCallbacks(mInfo);
        mHandler.postDelayed(mInfo, INFO_MS);
    }

    private void onCommand(JSONObject command) {
        long ts = command.optLong("ts", 0);
        boolean first = mFirstCommand;
        mFirstCommand = false;

        // The last order is kept in the database: when the TV starts, an old one isn't done again
        if (ts <= mLastCommandTs || (first && Math.abs(System.currentTimeMillis() - ts) > 60_000)) {
            mLastCommandTs = Math.max(mLastCommandTs, ts);
            return;
        }

        mLastCommandTs = ts;
        String action = command.optString("action");
        Log.d(TAG, "Command: %s", action);

        // The player controllers exist once the playback presenter does
        com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(mContext);

        if (sHook == null) {
            return;
        }

        if ("play".equals(action)) {
            String videoId = command.optString("videoId");
            if (!TextUtils.isEmpty(videoId)) {
                long sent = command.optLong("ts", System.currentTimeMillis());
                long late = command.optBoolean("playing", true) ? Math.max(0, Math.min(10_000, System.currentTimeMillis() - sent)) : 0;
                sHook.castPlay(mContext, videoId, command.optString("title"), command.optLong("positionMs") + late);
            }
        } else {
            sHook.castControl(action, command.optLong("value"));
        }

        mSessionUntil = System.currentTimeMillis() + SESSION_MS;
        mHandler.removeCallbacks(mState);
        mHandler.postDelayed(mState, 1_000);
    }

    /** While a phone controls it, the TV says what it plays and where (for the phone's remote) */
    private void sendState() {
        if (!mReceiving || System.currentTimeMillis() > mSessionUntil) {
            return;
        }

        JSONObject state = sHook != null ? sHook.castState() : null;
        String code = getTvCode();

        try {
            JSONObject body = state != null ? state : new JSONObject().put("open", false);
            body.put("ts", new JSONObject().put(".sv", "timestamp"));
            runAsync(() -> TogetherRoom.request("PUT", url(code, "state"), body.toString()));
        } catch (JSONException e) {
            // NOP
        }

        mHandler.postDelayed(mState, STATE_MS);
    }

    /** Server-sent events of the TV's order node (whole object each time) */
    private void stream(String code) {
        Thread thread = new Thread(() -> {
            int failures = 0;

            while (mReceiving) {
                HttpURLConnection connection = null;
                boolean ok = false;

                try {
                    connection = (HttpURLConnection) new URL(url(code, "cmd")).openConnection();
                    connection.setRequestProperty("Accept", "text/event-stream");
                    connection.setConnectTimeout(15_000);
                    connection.setReadTimeout(90_000);
                    connection.setInstanceFollowRedirects(true);

                    if (connection.getResponseCode() == 200) {
                        ok = true;
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                        String event = null;
                        String line;
                        JSONObject current = new JSONObject();

                        while (mReceiving && (line = reader.readLine()) != null) {
                            if (line.startsWith("event:")) {
                                event = line.substring(6).trim();
                            } else if (line.startsWith("data:") && ("put".equals(event) || "patch".equals(event))) {
                                JSONObject json = new JSONObject(line.substring(5).trim());
                                String path = json.optString("path", "/");
                                Object data = json.opt("data");
                                if ("/".equals(path)) {
                                    current = data instanceof JSONObject ? (JSONObject) data : new JSONObject();
                                } else if (data != null && data != JSONObject.NULL) {
                                    current.put(path.substring(1), data);
                                }
                                if (current.has("action") && current.has("ts")) {
                                    JSONObject command = new JSONObject(current.toString());
                                    mHandler.post(() -> onCommand(command));
                                }
                                event = null;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Stream: %s", e.getMessage());
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }

                failures = ok ? 0 : failures + 1;

                try {
                    Thread.sleep(Math.min(30_000, 1_000L * (1 << Math.min(failures, 5))));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "bluecast");
        thread.setDaemon(true);
        thread.start();
    }

    // ---------------------------------------------------------------------------------------------
    // Phone side
    // ---------------------------------------------------------------------------------------------

    /** The TVs this phone was linked to */
    public List<Tv> getTvs() {
        List<Tv> result = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(prefs().getString(KEY_TVS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) {
                    Tv tv = new Tv();
                    tv.code = json.optString("code");
                    tv.name = json.optString("name", "TV");
                    result.add(tv);
                }
            }
        } catch (JSONException e) {
            // NOP
        }

        return result;
    }

    private void saveTvs(List<Tv> tvs) {
        JSONArray array = new JSONArray();

        for (Tv tv : tvs) {
            try {
                array.put(new JSONObject().put("code", tv.code).put("name", tv.name));
            } catch (JSONException e) {
                // NOP
            }
        }

        prefs().edit().putString(KEY_TVS, array.toString()).apply();
    }

    /** Links a TV by its code (it must have BlueTube open once at least). Result: the TV or null */
    public void linkTv(String code, Callback<Tv> callback) {
        String clean = code != null ? code.replaceAll("[^0-9]", "") : "";

        if (clean.length() != 6) {
            callback.onResult(null);
            return;
        }

        runAsync(() -> {
            JSONObject info = getJson(url(clean, "info"));
            Tv tv = null;

            if (info != null && info.has("name")) {
                tv = new Tv();
                tv.code = clean;
                tv.name = info.optString("name", "TV");
                tv.online = isOnline(info);
                List<Tv> tvs = getTvs();
                for (int i = tvs.size() - 1; i >= 0; i--) {
                    if (tvs.get(i).code.equals(clean)) {
                        tvs.remove(i);
                    }
                }
                tvs.add(0, tv);
                saveTvs(tvs);
            }

            Tv result = tv;
            mHandler.post(() -> callback.onResult(result));
        });
    }

    public void unlinkTv(String code) {
        List<Tv> tvs = getTvs();

        for (int i = tvs.size() - 1; i >= 0; i--) {
            if (tvs.get(i).code.equals(code)) {
                tvs.remove(i);
            }
        }

        saveTvs(tvs);
    }

    /** Is the TV on with BlueTube open? */
    public void checkOnline(Tv tv, Callback<Tv> callback) {
        runAsync(() -> {
            JSONObject info = getJson(url(tv.code, "info"));
            tv.online = info != null && isOnline(info);
            if (info != null && info.has("name")) {
                tv.name = info.optString("name", tv.name);
            }
            mHandler.post(() -> callback.onResult(tv));
        });
    }

    private static boolean isOnline(JSONObject info) {
        long ts = info.optLong("ts", 0);
        return ts > 0 && Math.abs(System.currentTimeMillis() - ts) < ONLINE_MS;
    }

    /** Plays the video on the TV, from this position */
    public void sendPlay(String code, String videoId, String title, long positionMs, Callback<Boolean> callback) {
        try {
            send(code, new JSONObject().put("action", "play").put("videoId", videoId)
                    .put("title", title != null ? title : "").put("positionMs", positionMs).put("playing", true), callback);
        } catch (JSONException e) {
            callback.onResult(false);
        }
    }

    /** toggle / play / pause / seek (value ms) / stop */
    public void sendControl(String code, String action, long value) {
        try {
            send(code, new JSONObject().put("action", action).put("value", value), null);
        } catch (JSONException e) {
            // NOP
        }
    }

    private void send(String code, JSONObject command, Callback<Boolean> callback) {
        runAsync(() -> {
            boolean ok = false;
            try {
                command.put("ts", new JSONObject().put(".sv", "timestamp"));
                ok = TogetherRoom.request("PUT", url(code, "cmd"), command.toString()) != null;
            } catch (JSONException e) {
                // NOP
            }
            boolean result = ok;
            if (callback != null) {
                mHandler.post(() -> callback.onResult(result));
            }
        });
    }

    /** What the TV plays now (null if unknown) */
    public void getState(String code, Callback<JSONObject> callback) {
        runAsync(() -> {
            JSONObject state = getJson(url(code, "state"));
            mHandler.post(() -> callback.onResult(state));
        });
    }

    private static JSONObject getJson(String url) {
        String result = TogetherRoom.request("GET", url, null);

        if (result == null || "null".equals(result.trim())) {
            return null;
        }

        try {
            return new JSONObject(result);
        } catch (JSONException e) {
            return null;
        }
    }

    private static void runAsync(Runnable runnable) {
        Thread thread = new Thread(runnable, "bluecast-io");
        thread.setDaemon(true);
        thread.start();
    }
}
