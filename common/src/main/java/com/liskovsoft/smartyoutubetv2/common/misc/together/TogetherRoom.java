package com.liskovsoft.smartyoutubetv2.common.misc.together;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;

/**
 * "Watch together" room on a Firebase Realtime Database, through its REST API (no Firebase SDK needed):
 * <pre>
 * /rooms/{code}/state     {videoId, title, positionMs, playing, ts (server time), by, name}
 * /rooms/{code}/messages  {pushId: {by, name, type: text|voice, text, audio (base64 m4a), ts}}
 * /rooms/{code}/members   {deviceId: {name, ts}}
 * </pre>
 * Changes arrive through Server-Sent Events streams (one per node). All the network work runs on own threads;
 * the listener is called on those threads.
 */
public class TogetherRoom {
    private static final String TAG = TogetherRoom.class.getSimpleName();
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 90_000; // Firebase sends a keep-alive every ~30s

    public interface Listener {
        void onState(JSONObject state);
        /** isNew = arrived after joining (old ones are the chat history) */
        void onMessage(String key, JSONObject message, boolean isNew);
        void onMembers(int count);
        void onConnectionError(String error);
    }

    private final String mDbUrl;
    private final String mCode;
    private final Listener mListener;
    private volatile boolean mRunning;
    private final JSONObject mState = new JSONObject();
    private final JSONObject mMembers = new JSONObject();
    private volatile long mServerOffsetMs;
    private volatile boolean mMessagesLoaded;

    public TogetherRoom(String dbUrl, String code, Listener listener) {
        mDbUrl = dbUrl.endsWith("/") ? dbUrl.substring(0, dbUrl.length() - 1) : dbUrl;
        mCode = code;
        mListener = listener;
    }

    public String getCode() {
        return mCode;
    }

    /**
     * Server time (ms) estimated from the last write: used to know how far a playing video went since a change.
     */
    public long serverNow() {
        return System.currentTimeMillis() + mServerOffsetMs;
    }

    public void start() {
        mRunning = true;
        stream("state", null);
        stream("members", null);
        stream("messages", "orderBy=%22%24key%22&limitToLast=30");
    }

    public void stop() {
        mRunning = false;
    }

    // ---------------------------------------------------------------------------------------------
    // Writes (blocking: call them from a background thread)
    // ---------------------------------------------------------------------------------------------

    /**
     * Is there a room with this code? (any node under it)
     */
    public static boolean exists(String dbUrl, String code) {
        String base = dbUrl.endsWith("/") ? dbUrl.substring(0, dbUrl.length() - 1) : dbUrl;
        String result = request("GET", base + "/rooms/" + code + ".json?shallow=true", null);
        return result != null && !"null".equals(result.trim());
    }

    /**
     * The whole conversation of the room (key order = time order). null on error.
     */
    public JSONObject getAllMessages() {
        String result = request("GET", url("messages"), null);

        if (result == null) {
            return null;
        }

        try {
            return "null".equals(result.trim()) ? new JSONObject() : new JSONObject(result);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * A room nobody has been in for a long time (the app was closed without leaving): deleted with everything in it.
     */
    public static void deleteIfAbandoned(String dbUrl, String code, long maxIdleMs) {
        String base = dbUrl.endsWith("/") ? dbUrl.substring(0, dbUrl.length() - 1) : dbUrl;
        String members = request("GET", base + "/rooms/" + code + "/members.json", null);

        if (members == null) {
            return; // no connection: try another day
        }

        long newest = 0;

        try {
            if (!"null".equals(members.trim())) {
                JSONObject all = new JSONObject(members);
                for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
                    JSONObject member = all.optJSONObject(keys.next());
                    if (member != null) {
                        newest = Math.max(newest, member.optLong("ts"));
                    }
                }
            }
        } catch (JSONException e) {
            return;
        }

        if (newest == 0 || System.currentTimeMillis() - newest > maxIdleMs) {
            request("DELETE", base + "/rooms/" + code + ".json", null);
        }
    }

    public boolean putState(JSONObject state) {
        try {
            state.put("ts", serverTimestamp());
        } catch (JSONException e) {
            return false;
        }

        long sentAt = System.currentTimeMillis();
        String result = request("PUT", url("state"), state.toString());

        if (result == null) {
            return false;
        }

        try {
            // The answer has the resolved server time: our clock vs the server one
            long serverTs = new JSONObject(result).optLong("ts", 0);
            if (serverTs > 0) {
                long localMid = (sentAt + System.currentTimeMillis()) / 2;
                mServerOffsetMs = serverTs - localMid;
            }
        } catch (JSONException e) {
            // NOP
        }

        return true;
    }

    public boolean sendMessage(JSONObject message) {
        try {
            message.put("ts", serverTimestamp());
        } catch (JSONException e) {
            return false;
        }

        return request("POST", url("messages"), message.toString()) != null;
    }

    public boolean putMember(String deviceId, String name) {
        try {
            JSONObject member = new JSONObject().put("name", name).put("ts", serverTimestamp());
            return request("PUT", url("members/" + deviceId), member.toString()) != null;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * Leaves the room; the last one out deletes it (chat and voice notes included).
     */
    public void leave(String deviceId) {
        request("DELETE", url("members/" + deviceId), null);
        String members = request("GET", url("members") + "?shallow=true", null);

        if (members == null || "null".equals(members.trim())) {
            request("DELETE", mDbUrl + "/rooms/" + mCode + ".json", null);
        }
    }

    private static JSONObject serverTimestamp() throws JSONException {
        return new JSONObject().put(".sv", "timestamp");
    }

    private String url(String node) {
        return mDbUrl + "/rooms/" + mCode + "/" + node + ".json";
    }

    private static String request(String method, String url, String body) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(CONNECT_TIMEOUT_MS);

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream out = connection.getOutputStream();
                out.write(body.getBytes("UTF-8"));
                out.close();
            }

            int code = connection.getResponseCode();

            if (code < 200 || code >= 300) {
                Log.e(TAG, "%s %s: %s", method, url, code);
                return null;
            }

            return readAll(connection.getInputStream());
        } catch (Exception e) {
            Log.e(TAG, "%s %s: %s", method, url, e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }

        in.close();
        return out.toString("UTF-8");
    }

    // ---------------------------------------------------------------------------------------------
    // Streams
    // ---------------------------------------------------------------------------------------------

    private void stream(String node, String query) {
        Thread thread = new Thread(() -> {
            int failures = 0;

            while (mRunning) {
                boolean ok = streamOnce(node, query);

                if (!mRunning) {
                    break;
                }

                failures = ok ? 0 : failures + 1;

                if (failures == 3) {
                    mListener.onConnectionError(node);
                }

                try {
                    Thread.sleep(Math.min(30_000, 1_000L * (1 << Math.min(failures, 5))));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "together-" + node);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * One SSE connection until it drops. true = it was connected (a reconnection can be tried right away).
     */
    private boolean streamOnce(String node, String query) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(url(node) + (query != null ? "?" + query : "")).openConnection();
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            if (connection.getResponseCode() != 200) {
                Log.e(TAG, "Stream %s: %s", node, connection.getResponseCode());
                return false;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            String event = null;
            String line;

            while (mRunning && (line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:") && event != null) {
                    onEvent(node, event, line.substring(5).trim());
                    event = null;
                }
            }

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Stream %s: %s", node, e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void onEvent(String node, String event, String data) {
        if (!"put".equals(event) && !"patch".equals(event)) {
            return; // keep-alive, cancel, auth_revoked
        }

        try {
            JSONObject json = new JSONObject(data);
            String path = json.optString("path", "/");
            Object value = json.opt("data");

            switch (node) {
                case "state":
                    synchronized (mState) {
                        apply(mState, path, value, "put".equals(event));
                        mListener.onState(new JSONObject(mState.toString()));
                    }
                    break;
                case "members":
                    synchronized (mMembers) {
                        apply(mMembers, path, value, "put".equals(event));
                        mListener.onMembers(mMembers.length());
                    }
                    break;
                case "messages":
                    onMessagesEvent(path, value);
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Bad event: %s", e.getMessage());
        }
    }

    private void onMessagesEvent(String path, Object value) {
        if ("/".equals(path)) {
            // First answer: the history (last messages)
            if (value instanceof JSONObject) {
                JSONObject all = (JSONObject) value;
                for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    JSONObject message = all.optJSONObject(key);
                    if (message != null) {
                        mListener.onMessage(key, message, mMessagesLoaded);
                    }
                }
            }
            mMessagesLoaded = true;
        } else if (value instanceof JSONObject) {
            String key = path.substring(1);
            if (!key.contains("/")) {
                mListener.onMessage(key, (JSONObject) value, true);
            }
        }
    }

    /**
     * Applies a Firebase put/patch at a path ("/", "/playing", "/abc") to the local copy of the node.
     */
    private static void apply(JSONObject target, String path, Object value, boolean isPut) throws JSONException {
        if ("/".equals(path)) {
            if (isPut) {
                clear(target);
            }

            if (value instanceof JSONObject) {
                JSONObject source = (JSONObject) value;
                for (Iterator<String> keys = source.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    target.put(key, source.get(key));
                }
            }
            return;
        }

        String key = path.substring(1);

        if (key.contains("/")) {
            key = key.substring(0, key.indexOf('/')); // nested change: take the child as changed (rare)
        }

        if (value == null || value == JSONObject.NULL) {
            target.remove(key);
        } else {
            target.put(key, value);
        }
    }

    private static void clear(JSONObject object) {
        Iterator<String> keys = object.keys();
        java.util.List<String> names = new java.util.ArrayList<>();
        while (keys.hasNext()) {
            names.add(keys.next());
        }
        for (String name : names) {
            object.remove(name);
        }
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }
}
