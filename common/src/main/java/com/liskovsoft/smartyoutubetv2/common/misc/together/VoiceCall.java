package com.liskovsoft.smartyoutubetv2.common.misc.together;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "Watch together" voice call: the people of the room talk while watching. Audio goes directly between the
 * devices (WebRTC of the system WebView, so the app doesn't grow); the room database only introduces them:
 * <pre>
 * /rooms/{code}/call/members/{deviceId}  {name, mic, ts}
 * /rooms/{code}/call/signal/{toDeviceId}/{pushId}  {from, data (offer/answer/ice as json)}
 * </pre>
 * A TV without a microphone joins to listen only.
 */
@android.annotation.TargetApi(21) // everything that touches the WebView runs only when isSupported() (Android 5+)
public final class VoiceCall {
    private static final String TAG = VoiceCall.class.getSimpleName();
    private static final long HEARTBEAT_MS = 30_000;
    /** A member not seen for this long left without saying it (app closed) */
    private static final long STALE_MS = 100_000;
    private static final long UNDUCK_DELAY_MS = 1_200;
    private static final int MAX_RETRIES = 3;
    @SuppressLint("StaticFieldLeak")
    private static VoiceCall sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    /** Everybody in the call (this device too), by device id */
    private final Map<String, Member> mMembers = new LinkedHashMap<>();
    private final Set<String> mPeers = new HashSet<>();
    private final Map<String, Integer> mRetries = new HashMap<>();
    private final List<String> mQueuedJs = new ArrayList<>();
    private String mCode;
    private int mGeneration;
    private boolean mMembersLoaded;
    private boolean mInCall;
    private boolean mHasMic;
    private boolean mMicOn = true;
    private boolean mOthersMuted;
    private WebView mWebView;
    private boolean mPageReady;
    private int mSpeakingCount;
    private String mSessionId = "";
    private final Runnable mHeartbeat = this::heartbeat;
    private final Runnable mUnduck = () -> TogetherManager.instance(getContext()).duckForCall(false);

    public interface Listener {
        /** Somebody joined/left, talks or stops talking, mic changed... */
        void onCallChanged();
    }

    public static class Member {
        public String id;
        public String name;
        public boolean mic;
        public long ts;
        public boolean me;
        public boolean speaking;
        /** Session of this person in the call (a new one each time they join): older signals are ignored */
        public String sid;
        /** connected / connecting / failed (WebRTC connection with this person) */
        public String state;
    }

    private VoiceCall(Context context) {
        mContext = context.getApplicationContext();
    }

    public static VoiceCall instance(Context context) {
        if (sInstance == null) {
            sInstance = new VoiceCall(context);
        }

        return sInstance;
    }

    private Context getContext() {
        return mContext;
    }

    /** WebRTC in the WebView needs Android 5+ */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 21;
    }

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    public boolean isInCall() {
        return mInCall;
    }

    public boolean hasMic() {
        return mHasMic;
    }

    public boolean isMicOn() {
        return mHasMic && mMicOn;
    }

    public boolean isOthersMuted() {
        return mOthersMuted;
    }

    /** People in the call, this device first when it's in */
    public List<Member> getMembers() {
        List<Member> result = new ArrayList<>();

        for (Member member : mMembers.values()) {
            if (member.me) {
                result.add(0, member);
            } else {
                result.add(member);
            }
        }

        return result;
    }

    /** Names of the others in the call ("Ana, Luis"), empty if nobody */
    public String getOtherNames() {
        List<String> names = new ArrayList<>();

        for (Member member : mMembers.values()) {
            if (!member.me) {
                names.add(member.name);
            }
        }

        return TextUtils.join(", ", names);
    }

    /** Who is talking now ("Ana"), null if nobody */
    public String getSpeakingName() {
        for (Member member : mMembers.values()) {
            if (member.speaking && !member.me) {
                return member.name;
            }
        }

        return null;
    }

    public boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < 23
                || mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Has a microphone at all (most TVs don't) */
    public boolean hasMicHardware() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
    }

    public void addListener(Listener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Listener listener : mListeners) {
            listener.onCallChanged();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Room (called by TogetherManager)
    // ---------------------------------------------------------------------------------------------

    void onRoomStarted(String code) {
        if (!isSupported()) {
            return;
        }

        mCode = code;
        mGeneration++;
        mMembers.clear();
        mMembersLoaded = false;
        stream("call/members", mGeneration, this::onMembersEvent);
        notifyChanged();
    }

    void onRoomLeft() {
        leave();
        mCode = null;
        mGeneration++;
        mMembers.clear();
        notifyChanged();
    }

    // ---------------------------------------------------------------------------------------------
    // Join / leave / mic
    // ---------------------------------------------------------------------------------------------

    /**
     * Joins the call of the room. Without a microphone (or its permission) this device only listens.
     */
    public void join() {
        if (mCode == null || mInCall || !isSupported()) {
            return;
        }

        mInCall = true;
        mSessionId = Long.toString(System.nanoTime() & 0xFFFFFFFFL, 36) + Integer.toString((int) (Math.random() * 1_000_000), 36);
        mMicOn = true;
        mOthersMuted = false;
        mPeers.clear();
        mRetries.clear();
        // The video keeps playing while the call holds the audio
        com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController.setInAppCall(true);

        boolean withMic = hasMicHardware() && hasMicPermission();
        createWebView();
        runJs("setIce(" + JSONObject.quote(iceServers()) + ")");
        runJs("start(" + withMic + ")");
        notifyChanged();
    }

    public void leave() {
        if (!mInCall) {
            return;
        }

        mInCall = false;
        mHasMic = false;
        mHandler.removeCallbacks(mHeartbeat);
        mHandler.removeCallbacks(mUnduck);
        TogetherManager.instance(mContext).duckForCall(false);
        mSpeakingCount = 0;
        runJs("hangupAll()");
        mPeers.clear();
        mGeneration++; // stops the signal stream (the members one restarts below)

        String code = mCode;
        String me = myId();
        runAsync(() -> {
            request("DELETE", url(code, "call/members/" + me), null);
            request("DELETE", url(code, "call/signal/" + me), null);
        });

        // Keep listening to who is in the call (to show "Ana is in the call · Join")
        mMembers.remove(me);
        if (mCode != null) {
            mMembersLoaded = false;
            stream("call/members", mGeneration, this::onMembersEvent);
        }

        mHandler.postDelayed(this::destroyWebView, 500);
        com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController.setInAppCall(false);
        notifyChanged();
    }

    public void setMicOn(boolean on) {
        if (!mInCall || !mHasMic) {
            return;
        }

        mMicOn = on;
        runJs("setMic(" + on + ")");
        putMe();
        notifyChanged();
    }

    /** Silence the others (the video still sounds) */
    public void setOthersMuted(boolean muted) {
        if (!mInCall) {
            return;
        }

        mOthersMuted = muted;
        runJs("setOthersMuted(" + muted + ")");

        if (muted) {
            mHandler.removeCallbacks(mUnduck);
            TogetherManager.instance(mContext).duckForCall(false);
        }

        notifyChanged();
    }

    // ---------------------------------------------------------------------------------------------
    // WebView (WebRTC)
    // ---------------------------------------------------------------------------------------------

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @android.annotation.TargetApi(21) // join() only runs on Android 5+ (isSupported)
    private void createWebView() {
        destroyWebView();
        mPageReady = false;
        mQueuedJs.clear();

        try {
            mWebView = new WebView(mContext);
        } catch (Throwable e) { // no WebView installed/updating
            Log.e(TAG, "WebView: %s", e.getMessage());
            mWebView = null;
            MessageHelpers.showMessage(mContext, R.string.together_call_unsupported);
            mInCall = false;
            com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController.setInAppCall(false);
            return;
        }

        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        mWebView.addJavascriptInterface(new Bridge(), "Android");
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            @android.annotation.TargetApi(21)
            public void onPermissionRequest(PermissionRequest request) {
                // Only our own page asks, and only for the microphone
                request.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            }
        });
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                mPageReady = true;
                for (String js : mQueuedJs) {
                    view.evaluateJavascript(js, null);
                }
                mQueuedJs.clear();
            }
        });
        // A secure origin is needed for the microphone; nothing is loaded from it
        mWebView.loadDataWithBaseURL("https://bluetube.local/", readPage(), "text/html", "utf-8", null);
    }

    private void destroyWebView() {
        if (mWebView != null && !mInCall) {
            try {
                mWebView.destroy();
            } catch (Throwable e) {
                // NOP
            }
            mWebView = null;
            mPageReady = false;
        }
    }

    private void runJs(String js) {
        if (mWebView == null) {
            return;
        }

        if (mPageReady) {
            mWebView.evaluateJavascript(js, null);
        } else {
            mQueuedJs.add(js);
        }
    }

    private String readPage() {
        try {
            InputStream in = mContext.getResources().openRawResource(R.raw.together_call);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            return out.toString("UTF-8");
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** STUN to find the way between the devices (+ TURN relay if configured, for networks that block it) */
    private String iceServers() {
        JSONArray servers = new JSONArray();

        try {
            servers.put(new JSONObject().put("urls", new JSONArray()
                    .put("stun:stun.l.google.com:19302").put("stun:stun1.l.google.com:19302")));

            String turn = mContext.getString(R.string.together_turn_url);
            if (!TextUtils.isEmpty(turn)) {
                servers.put(new JSONObject().put("urls", turn)
                        .put("username", mContext.getString(R.string.together_turn_user))
                        .put("credential", mContext.getString(R.string.together_turn_password)));
            }
        } catch (JSONException e) {
            // NOP
        }

        return servers.toString();
    }

    /** Calls from the page (WebView thread): all handled on the main thread */
    private class Bridge {
        @JavascriptInterface
        public void onReady(boolean hasMic) {
            mHandler.post(() -> onPageReady(hasMic));
        }

        @JavascriptInterface
        public void send(String to, String data) {
            String code = mCode;
            String me = myId();
            String sid = mSessionId;
            Member member = mMembers.get(to);
            String toSid = member != null && member.sid != null ? member.sid : "";
            if (code == null) {
                return;
            }
            runAsync(() -> {
                try {
                    JSONObject message = new JSONObject().put("from", me).put("sid", sid).put("to_sid", toSid).put("data", data);
                    request("POST", url(code, "call/signal/" + to), message.toString());
                } catch (JSONException e) {
                    // NOP
                }
            });
        }

        @JavascriptInterface
        public void onLevel(String id, boolean speaking) {
            mHandler.post(() -> onSpeaking(id, speaking));
        }

        @JavascriptInterface
        public void onPeerState(String id, String state) {
            mHandler.post(() -> onPeerStateChanged(id, state));
        }

        @JavascriptInterface
        public void log(String text) {
            Log.d(TAG, "page: %s", text);
        }
    }

    private void onPageReady(boolean hasMic) {
        if (!mInCall) {
            return;
        }

        mHasMic = hasMic;

        if (!hasMic) {
            MessageHelpers.showMessage(mContext, R.string.together_call_listen_only);
        }

        putMe();
        // Old signals (an earlier session) are dropped before listening
        String code = mCode;
        String me = myId();
        int generation = mGeneration;
        runAsync(() -> {
            request("DELETE", url(code, "call/signal/" + me), null);
            mHandler.post(() -> {
                if (mInCall && generation == mGeneration) {
                    stream("call/signal/" + me, generation, this::onSignalEvent);
                }
            });
        });
        mHandler.removeCallbacks(mHeartbeat);
        mHandler.postDelayed(mHeartbeat, HEARTBEAT_MS);
        connectToMembers();
        notifyChanged();
    }

    private void onSpeaking(String id, boolean speaking) {
        Member member = mMembers.get(id);

        if (member == null || member.speaking == speaking) {
            return;
        }

        member.speaking = speaking;
        mSpeakingCount = Math.max(0, mSpeakingCount + (speaking ? 1 : -1));

        // The video goes lower while someone talks (back up a moment after they stop)
        if (!mOthersMuted) {
            if (mSpeakingCount > 0) {
                mHandler.removeCallbacks(mUnduck);
                TogetherManager.instance(mContext).duckForCall(true);
            } else {
                mHandler.removeCallbacks(mUnduck);
                mHandler.postDelayed(mUnduck, UNDUCK_DELAY_MS);
            }
        }

        notifyChanged();
    }

    private void onPeerStateChanged(String id, String state) {
        Member member = mMembers.get(id);
        if (member != null) {
            member.state = state;
        }

        Log.d(TAG, "Peer %s: %s", id, state);

        if (("failed".equals(state) || "disconnected".equals(state)) && mInCall && isCaller(id) && mMembers.containsKey(id)) {
            int retries = mRetries.containsKey(id) ? mRetries.get(id) : 0;
            if (retries < MAX_RETRIES) {
                mRetries.put(id, retries + 1);
                mHandler.postDelayed(() -> {
                    if (mInCall && mMembers.containsKey(id)) {
                        runJs("hangup(" + JSONObject.quote(id) + ")");
                        runJs("call(" + JSONObject.quote(id) + ")");
                    }
                }, 3_000);
            } else if (member != null) {
                MessageHelpers.showMessage(mContext, mContext.getString(R.string.together_call_failed, member.name));
            }
        } else if ("connected".equals(state) || "completed".equals(state)) {
            mRetries.remove(id);
        }

        notifyChanged();
    }

    // ---------------------------------------------------------------------------------------------
    // Database
    // ---------------------------------------------------------------------------------------------

    private void putMe() {
        String code = mCode;
        String me = myId();
        String name = TogetherManager.instance(mContext).myName();
        boolean mic = isMicOn();

        if (code == null || !mInCall) {
            return;
        }

        runAsync(() -> {
            try {
                JSONObject member = new JSONObject().put("name", name).put("mic", mic).put("sid", mSessionId)
                        .put("ts", new JSONObject().put(".sv", "timestamp"));
                request("PUT", url(code, "call/members/" + me), member.toString());
            } catch (JSONException e) {
                // NOP
            }
        });
    }

    private void heartbeat() {
        if (mInCall) {
            putMe();
            mHandler.postDelayed(mHeartbeat, HEARTBEAT_MS);
        }
    }

    /** Members node changed (main thread): whole list */
    private void onMembersEvent(JSONObject all) {
        String me = myId();
        long now = System.currentTimeMillis();
        Map<String, Member> fresh = new LinkedHashMap<>();

        for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
            String id = keys.next();
            JSONObject json = all.optJSONObject(id);
            if (json == null) {
                continue;
            }
            long ts = json.optLong("ts", 0);
            if (ts > 0 && now - ts > STALE_MS && !id.equals(me)) {
                continue; // left without saying it
            }
            Member old = mMembers.get(id);
            Member member = old != null ? old : new Member();
            member.id = id;
            member.name = json.optString("name", "BlueTube");
            member.mic = json.optBoolean("mic");
            member.ts = ts;
            member.me = id.equals(me);
            String sid = json.optString("sid", "");
            if (old != null && !member.me && old.sid != null && !old.sid.isEmpty() && !old.sid.equals(sid)) {
                // Joined again (new session): the old connection is useless
                mPeers.remove(id);
                runJs("hangup(" + JSONObject.quote(id) + ")");
            }
            member.sid = sid;
            fresh.put(id, member);
        }

        boolean firstLoad = !mMembersLoaded;
        mMembersLoaded = true;

        // Who came / went
        for (Member member : fresh.values()) {
            if (!member.me && !mMembers.containsKey(member.id) && !firstLoad) {
                MessageHelpers.showMessage(mContext, mContext.getString(R.string.together_call_joined, member.name));
            }
        }

        for (Member member : mMembers.values()) {
            if (!member.me && !fresh.containsKey(member.id)) {
                if (member.speaking) {
                    onSpeaking(member.id, false);
                }
                mPeers.remove(member.id);
                runJs("hangup(" + JSONObject.quote(member.id) + ")");
            }
        }

        mMembers.clear();
        mMembers.putAll(fresh);

        if (mInCall && mHasMicKnown()) {
            connectToMembers();
        }

        notifyChanged();
    }

    /** The page answered (mic or not): connections can start */
    private boolean mHasMicKnown() {
        return mPageReady;
    }

    /**
     * One connection per person: the one with the smaller id calls, the other answers (no double calls).
     */
    private void connectToMembers() {
        for (Member member : mMembers.values()) {
            if (member.me || mPeers.contains(member.id)) {
                continue;
            }

            mPeers.add(member.id);

            Log.d(TAG, "Peer %s added, caller=%s", member.id, isCaller(member.id));
            if (isCaller(member.id)) {
                runJs("call(" + JSONObject.quote(member.id) + ")");
            }
        }
    }

    private boolean isCaller(String otherId) {
        return myId().compareTo(otherId) < 0;
    }

    /** A signal for this device: given to the page, then removed from the database */
    private void onSignalEvent(JSONObject all) {
        String code = mCode;
        String me = myId();

        for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
            String key = keys.next();
            JSONObject message = all.optJSONObject(key);
            if (message == null) {
                continue;
            }
            String from = message.optString("from");
            String data = message.optString("data");
            String toSid = message.optString("to_sid", "");
            Member sender = mMembers.get(from);
            boolean old = (!toSid.isEmpty() && !toSid.equals(mSessionId))
                    || (sender != null && sender.sid != null && !sender.sid.isEmpty() && !sender.sid.equals(message.optString("sid", "")));
            if (old) {
                Log.d(TAG, "Old signal from %s ignored", from);
            } else if (!TextUtils.isEmpty(from) && !TextUtils.isEmpty(data)) {
                Log.d(TAG, "Signal from %s: %s", from, data.length() > 40 ? data.substring(0, 40) : data);
                mPeers.add(from);
                runJs("onSignal(" + JSONObject.quote(from) + "," + JSONObject.quote(data) + ")");
            }
            runAsync(() -> request("DELETE", url(code, "call/signal/" + me + "/" + key), null));
        }
    }

    private interface EventHandler {
        void onEvent(JSONObject data);
    }

    /**
     * Server-sent events of a node while the generation doesn't change. The handler gets (on the main thread):
     * for "call/members" the whole list each time, for signals only the new children.
     */
    private void stream(String node, int generation, EventHandler handler) {
        String code = mCode;
        boolean whole = node.equals("call/members");

        Thread thread = new Thread(() -> {
            JSONObject state = new JSONObject();
            int failures = 0;

            while (generation == mGeneration && code != null) {
                HttpURLConnection connection = null;
                boolean ok = false;

                try {
                    connection = (HttpURLConnection) new URL(url(code, node)).openConnection();
                    connection.setRequestProperty("Accept", "text/event-stream");
                    connection.setConnectTimeout(15_000);
                    connection.setReadTimeout(90_000);

                    if (connection.getResponseCode() == 200) {
                        ok = true;
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                        String event = null;
                        String line;

                        while (generation == mGeneration && (line = reader.readLine()) != null) {
                            if (line.startsWith("event:")) {
                                event = line.substring(6).trim();
                            } else if (line.startsWith("data:") && ("put".equals(event) || "patch".equals(event))) {
                                JSONObject changes = applyEvent(state, line.substring(5).trim(), "put".equals(event), whole);
                                if (changes != null && generation == mGeneration) {
                                    mHandler.post(() -> {
                                        if (generation == mGeneration) {
                                            handler.onEvent(changes);
                                        }
                                    });
                                }
                                event = null;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Stream %s: %s", node, e.getMessage());
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }

                failures = ok ? 0 : failures + 1;

                try {
                    Thread.sleep(Math.min(20_000, 1_000L * (1 << Math.min(failures, 4))));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "together-call");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Firebase put/patch -> the whole node (whole = true) or only the children that arrived (signals).
     */
    private static JSONObject applyEvent(JSONObject state, String data, boolean isPut, boolean whole) {
        try {
            JSONObject json = new JSONObject(data);
            String path = json.optString("path", "/");
            Object value = json.opt("data");
            JSONObject added = new JSONObject();

            if ("/".equals(path)) {
                if (isPut) {
                    List<String> names = new ArrayList<>();
                    for (Iterator<String> keys = state.keys(); keys.hasNext(); ) {
                        names.add(keys.next());
                    }
                    for (String name : names) {
                        state.remove(name);
                    }
                }
                if (value instanceof JSONObject) {
                    for (Iterator<String> keys = ((JSONObject) value).keys(); keys.hasNext(); ) {
                        String key = keys.next();
                        state.put(key, ((JSONObject) value).get(key));
                        added.put(key, ((JSONObject) value).get(key));
                    }
                }
            } else {
                String key = path.substring(1);
                if (key.contains("/")) {
                    key = key.substring(0, key.indexOf('/'));
                    if (value != null && value != JSONObject.NULL && state.optJSONObject(key) != null) {
                        String child = path.substring(path.indexOf('/', 1) + 1);
                        state.optJSONObject(key).put(child, value);
                        value = state.optJSONObject(key);
                    }
                }
                if (value == null || value == JSONObject.NULL) {
                    state.remove(key);
                } else {
                    state.put(key, value);
                    added.put(key, value);
                }
            }

            if (whole) {
                return new JSONObject(state.toString());
            }

            return added.length() > 0 ? added : null;
        } catch (JSONException e) {
            return null;
        }
    }

    private String myId() {
        return TogetherManager.instance(mContext).getDeviceId();
    }

    private String url(String code, String node) {
        String base = TogetherManager.instance(mContext).dbUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/rooms/" + code + "/" + node + ".json";
    }

    private static String request(String method, String url, String body) {
        return TogetherRoom.request(method, url, body);
    }

    private static void runAsync(Runnable runnable) {
        Thread thread = new Thread(runnable, "together-call-io");
        thread.setDaemon(true);
        thread.start();
    }
}
