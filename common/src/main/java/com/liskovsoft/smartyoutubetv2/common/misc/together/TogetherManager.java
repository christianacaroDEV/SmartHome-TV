package com.liskovsoft.smartyoutubetv2.common.misc.together;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.misc.BlueTubeSync;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "Watch together": one room shared by phones and TVs. Keeps the same video, position and play/pause on every
 * member, plus a chat (text, reactions) and voice notes.<br/>
 * The player side is {@link TogetherController}; the screens register a {@link UiListener}.
 */
public final class TogetherManager implements TogetherRoom.Listener {
    private static final String TAG = TogetherManager.class.getSimpleName();
    private static final String KEY_DEVICE_ID = "together_device_id";
    private static final String KEY_ROOM = "together_room";
    private static final String KEY_ROOM_TIME = "together_room_time";
    /** A room is remembered this long (the app may be closed by Android and opened again) */
    private static final long ROOM_MEMORY_MS = 12 * 60 * 60 * 1_000;
    private static final long HEARTBEAT_MS = 60_000;
    private static final int MAX_HISTORY = 60;
    @SuppressLint("StaticFieldLeak")
    private static TogetherManager sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<UiListener> mUiListeners = new CopyOnWriteArrayList<>();
    private final List<Message> mHistory = new ArrayList<>();
    private final String mDeviceId;
    /** When the app (this manager) started: the splash screen must be done before the room video is opened */
    private final long mStartedAt = android.os.SystemClock.uptimeMillis();
    private static final long STARTUP_MS = 6_000;
    private TogetherRoom mRoom;
    private PlayerSide mPlayerSide;
    private int mMembers;
    /** deviceId -> name of everybody in the room (this device included) */
    private final java.util.Map<String, String> mMemberNames = new java.util.LinkedHashMap<>();
    private MediaPlayer mChimePlayer;
    private JSONObject mPendingState;
    /** The latest state of the room (also our own), for "Sync" */
    private JSONObject mLastState;
    /** The first state after (re)joining is applied even if this device wrote it (e.g. the app was restarted) */
    private boolean mFirstState;
    private MediaPlayer mVoicePlayer;
    private String mVoiceKey;
    private boolean mChatOpen;
    private int mUnread;
    private final Runnable mHeartbeat = this::heartbeat;

    public interface UiListener {
        /** Joined, left or the people in the room changed */
        void onRoomChanged();
        void onMessage(Message message);
    }

    /**
     * The player (TogetherController): applies what others do and lowers the volume during voice notes.
     */
    public interface PlayerSide {
        void applyRemoteState(String videoId, String title, long positionMs, boolean playing);
        void setDucked(boolean ducked);
        /** Current video/position, to fill a new room */
        JSONObject getLocalState();
        /** The room was just created here: wait (paused) for the others */
        void pauseForRoom();
        /** true/false = playing or paused, null = no video open */
        Boolean isPlaying();
        /** {@link #ACTION_TOGGLE_PLAY} or {@link #ACTION_REWIND}: done here and sent to everybody. false = no video open */
        boolean control(int action);
    }

    public static final int ACTION_TOGGLE_PLAY = 0;
    public static final int ACTION_REWIND = 1;
    /** Back this much with the "-10 s" button */
    public static final long REWIND_MS = 10_000;

    public interface Callback {
        void onResult(boolean success);
    }

    public static class Message {
        public String key;
        public String by;
        public String name;
        public String type; // text | voice
        public String text;
        public String audio;
        public long durationMs;
        public long ts;
        public boolean mine;
        public boolean isNew;
    }

    private TogetherManager(Context context) {
        // Wrapped with the language chosen in the app (else the toasts follow the system language)
        mContext = com.liskovsoft.sharedutils.locale.LocaleContextWrapper.wrap(context.getApplicationContext(),
                com.liskovsoft.sharedutils.locale.LocaleUpdater.getSavedLocale(context.getApplicationContext()));
        SharedPreferences prefs = BlueTubeSync.prefs(mContext);
        String id = prefs.getString(KEY_DEVICE_ID, null);

        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }

        mDeviceId = id;

        // Back in the room after the app was restarted (Android closed it, the TV went to sleep...)
        String savedRoom = prefs.getString(KEY_ROOM, null);
        long savedTime = prefs.getLong(KEY_ROOM_TIME, 0);

        if (savedRoom != null && System.currentTimeMillis() - savedTime < ROOM_MEMORY_MS) {
            mHandler.postDelayed(() -> {
                if (mRoom == null) {
                    join(savedRoom, success -> {
                        if (!success) {
                            forgetRoom();
                        }
                    });
                }
            }, 1_500);
        } else if (savedRoom != null) {
            // Not used for hours: if nobody is there any more, it goes away with its chat and voice notes
            forgetRoom();
            runAsync(() -> TogetherRoom.deleteIfAbandoned(dbUrl(), savedRoom, ROOM_MEMORY_MS));
        }
    }

    private void rememberRoom(String code) {
        BlueTubeSync.prefs(mContext).edit().putString(KEY_ROOM, code).putLong(KEY_ROOM_TIME, System.currentTimeMillis()).apply();
    }

    private void forgetRoom() {
        BlueTubeSync.prefs(mContext).edit().remove(KEY_ROOM).remove(KEY_ROOM_TIME).apply();
    }

    public static TogetherManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new TogetherManager(context);
        }

        return sInstance;
    }

    // ---------------------------------------------------------------------------------------------
    // Room
    // ---------------------------------------------------------------------------------------------

    private String dbUrl() {
        return mContext.getString(R.string.together_db_url);
    }

    public boolean isActive() {
        return mRoom != null;
    }

    public String getCode() {
        return mRoom != null ? mRoom.getCode() : null;
    }

    public int getMembers() {
        return mMembers;
    }

    /**
     * Names of the other people in the room ("Ana, Luis"), empty if nobody else is there.
     */
    public String getOtherNames() {
        StringBuilder names = new StringBuilder();

        for (java.util.Map.Entry<String, String> member : mMemberNames.entrySet()) {
            if (!mDeviceId.equals(member.getKey())) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(member.getValue());
            }
        }

        return names.toString();
    }

    /**
     * "Viendo juntos · código 123 456 · 2 personas" plus, on a second line, who the others are.
     */
    public String describeRoom(Context context) {
        String code = getCode();

        if (code == null) {
            return "";
        }

        String info = context.getString(R.string.together_in_room, code.substring(0, 3) + " " + code.substring(3), Math.max(1, mMembers));
        String others = getOtherNames();
        return others.isEmpty() ? info : info + "\n" + context.getString(R.string.together_with, others);
    }

    public List<Message> getHistory() {
        synchronized (mHistory) {
            return new ArrayList<>(mHistory);
        }
    }

    /**
     * A new room with a free 6-digit code, starting with what this device is playing.
     */
    public void create(Callback callback) {
        // The player is read here, on its own (main) thread
        JSONObject localState = mPlayerSide != null ? mPlayerSide.getLocalState() : null;

        runAsync(() -> {
            SecureRandom random = new SecureRandom();
            String code = null;

            for (int i = 0; i < 5; i++) {
                String candidate = String.valueOf(100_000 + random.nextInt(900_000));
                if (!TogetherRoom.exists(dbUrl(), candidate)) {
                    code = candidate;
                    break;
                }
            }

            if (code == null) {
                post(() -> callback.onResult(false));
                return;
            }

            TogetherRoom room = new TogetherRoom(dbUrl(), code, this);
            boolean ok = room.putMember(mDeviceId, myName());

            if (ok) {
                JSONObject state = localState;
                if (state != null) {
                    putStamp(state);
                    try {
                        state.put("playing", false); // waits for the others: everybody starts at the same time
                    } catch (JSONException e) {
                        // NOP
                    }
                    room.putState(state);
                }
            }

            post(() -> {
                if (ok) {
                    startRoom(room);
                    if (mPlayerSide != null) {
                        mPlayerSide.pauseForRoom();
                    }
                }
                callback.onResult(ok);
            });
        });
    }

    public void join(String code, Callback callback) {
        String clean = code != null ? code.replaceAll("[^0-9]", "") : "";

        if (clean.length() != 6) {
            callback.onResult(false);
            return;
        }

        runAsync(() -> {
            boolean exists = TogetherRoom.exists(dbUrl(), clean);
            TogetherRoom room = new TogetherRoom(dbUrl(), clean, this);
            boolean ok = exists && room.putMember(mDeviceId, myName());

            post(() -> {
                if (ok) {
                    startRoom(room);
                }
                callback.onResult(ok);
            });
        });
    }

    public void leave() {
        TogetherRoom room = mRoom;

        if (room == null) {
            return;
        }

        mRoom = null;
        mMembers = 0;
        mMemberNames.clear();
        mLastState = null;
        forgetRoom();
        mPendingState = null;
        mHandler.removeCallbacks(mHeartbeat);
        room.stop();
        synchronized (mHistory) {
            mHistory.clear();
        }
        stopVoice();
        mUnread = 0;
        runAsync(() -> room.leave(mDeviceId));
        notifyRoomChanged();
    }

    private void startRoom(TogetherRoom room) {
        if (mRoom != null) {
            leave();
        }

        mRoom = room;
        mFirstState = true;
        rememberRoom(room.getCode());
        room.start();
        mHandler.postDelayed(mHeartbeat, HEARTBEAT_MS);
        notifyRoomChanged();
    }

    private void heartbeat() {
        TogetherRoom room = mRoom;

        Log.d(TAG, "Heartbeat room=%s", room != null);

        if (room != null) {
            runAsync(() -> room.putMember(mDeviceId, myName()));
            mHandler.postDelayed(mHeartbeat, HEARTBEAT_MS);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Player
    // ---------------------------------------------------------------------------------------------

    public void setPlayerSide(PlayerSide playerSide) {
        mPlayerSide = playerSide;

        // Joined from the menu (no player open yet): play what the room is playing
        if (playerSide != null && mPendingState != null) {
            JSONObject state = mPendingState;
            mPendingState = null;
            applyState(state);
        }
    }

    /**
     * This device played, paused, seeked or changed the video: everybody else follows.
     */
    public void publishState(String videoId, String title, long positionMs, boolean playing) {
        TogetherRoom room = mRoom;

        if (room == null || videoId == null) {
            return;
        }

        try {
            JSONObject state = new JSONObject()
                    .put("videoId", videoId)
                    .put("title", title != null ? title : "")
                    .put("positionMs", positionMs)
                    .put("playing", playing);
            putStamp(state);
            runAsync(() -> room.putState(state));
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    private void putStamp(JSONObject state) {
        try {
            state.put("by", mDeviceId).put("name", myName());
        } catch (JSONException e) {
            // NOP
        }
    }

    @Override
    public void onState(JSONObject state) {
        post(() -> {
            if (mRoom == null) {
                return;
            }

            mLastState = state;
            boolean first = mFirstState;
            mFirstState = false;

            if (!first && mDeviceId.equals(state.optString("by"))) {
                return; // our own change coming back
            }

            if (mPlayerSide == null) {
                mPendingState = state;
                openRoomVideo(state);
                return;
            }

            applyState(state);
        });
    }

    private void applyState(JSONObject state) {
        String videoId = state.optString("videoId", null);

        if (TextUtils.isEmpty(videoId) || mPlayerSide == null || mRoom == null) {
            return;
        }

        boolean playing = state.optBoolean("playing");
        long position = state.optLong("positionMs");
        long ts = state.optLong("ts", 0);

        if (playing && ts > 0) {
            // The time that passed since the other person pressed play/seek
            position += Math.max(0, Math.min(60_000, mRoom.serverNow() - ts));
        }

        mPlayerSide.applyRemoteState(videoId, state.optString("title"), position, playing);
    }

    // ---------------------------------------------------------------------------------------------
    // Controls for everybody (chat buttons)
    // ---------------------------------------------------------------------------------------------

    /**
     * true/false = the video is playing/paused here, null = no video open.
     */
    public Boolean isPlaying() {
        return mPlayerSide != null ? mPlayerSide.isPlaying() : null;
    }

    /**
     * Pause/play or go back 10 s, here and for everybody. false = there is no video open.
     */
    public boolean control(int action) {
        return mRoom != null && mPlayerSide != null && mPlayerSide.control(action);
    }

    /**
     * Jump to where the room is (same video, position and play state as the others). false = nothing to sync yet.
     */
    public boolean syncNow() {
        JSONObject state = mLastState;

        if (mRoom == null || state == null || TextUtils.isEmpty(state.optString("videoId", null))) {
            return false;
        }

        if (mPlayerSide == null) {
            openRoomVideo(state);
        } else {
            applyState(state);
        }

        return true;
    }

    private void openRoomVideo(JSONObject state) {
        long wait = mStartedAt + STARTUP_MS - android.os.SystemClock.uptimeMillis();

        if (wait > 0) {
            // App just opened: the splash would open the main screen over the video
            mHandler.postDelayed(() -> {
                if (mRoom != null && mPlayerSide == null && mPendingState == state) {
                    openRoomVideo(state);
                }
            }, wait);
            return;
        }

        String videoId = state.optString("videoId", null);

        if (!TextUtils.isEmpty(videoId)) {
            com.liskovsoft.smartyoutubetv2.common.app.models.data.Video video =
                    com.liskovsoft.smartyoutubetv2.common.app.models.data.Video.from(videoId);
            video.title = state.optString("title");
            com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(mContext).openVideo(video);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Chat
    // ---------------------------------------------------------------------------------------------

    public void sendText(String text) {
        TogetherRoom room = mRoom;

        if (room == null || TextUtils.isEmpty(text != null ? text.trim() : null)) {
            return;
        }

        try {
            JSONObject message = new JSONObject().put("type", "text").put("text", text.trim());
            putStamp(message);
            runAsync(() -> room.sendMessage(message));
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * Voice note: a small m4a (AAC) file, sent as base64 inside the message.
     */
    public void sendVoice(File audioFile, long durationMs, Callback callback) {
        TogetherRoom room = mRoom;

        if (room == null || audioFile == null || !audioFile.exists()) {
            callback.onResult(false);
            return;
        }

        runAsync(() -> {
            boolean ok = false;

            try {
                byte[] bytes = readFile(audioFile);
                JSONObject message = new JSONObject().put("type", "voice")
                        .put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("durationMs", durationMs);
                putStamp(message);
                ok = room.sendMessage(message);
            } catch (Exception e) {
                Log.e(TAG, "sendVoice: %s", e.getMessage());
            }

            boolean result = ok;
            post(() -> callback.onResult(result));
        });
    }

    @Override
    public void onMessage(String key, JSONObject json, boolean isNew) {
        Log.d(TAG, "Message %s new=%s room=%s ui=%s", key, isNew, mRoom != null, mUiListeners.size());
        Message message = toMessage(key, json, isNew);

        post(() -> {
            if (mRoom == null) {
                return;
            }

            synchronized (mHistory) {
                for (Message old : mHistory) {
                    if (old.key.equals(key)) {
                        return;
                    }
                }
                insertSorted(message);

                if (isNew && !message.mine && !mChatOpen) {
                    mUnread++;
                }
            }

            if (isNew && !message.mine && "voice".equals(message.type)) {
                playVoice(message);
            }

            if (mUiListeners.isEmpty()) {
                // No chat on screen (e.g. TV): a short notice
                if (isNew && !message.mine) {
                    MessageHelpers.showMessage(mContext, describe(message), true);
                }
            } else {
                for (UiListener listener : mUiListeners) {
                    listener.onMessage(message);
                }
            }
        });
    }

    public String describe(Message message) {
        if ("system".equals(message.type)) {
            return "• " + message.text;
        }

        String text = "voice".equals(message.type) ? "🎙 " + mContext.getString(R.string.together_voice_note) : message.text;
        return message.name + ": " + text;
    }

    /**
     * Tap on a voice note: plays it, or pauses/resumes it if it is the one playing.
     */
    public void toggleVoice(Message message) {
        if (message != null && message.key.equals(mVoiceKey) && mVoicePlayer != null) {
            try {
                if (mVoicePlayer.isPlaying()) {
                    mVoicePlayer.pause();
                    if (mPlayerSide != null) {
                        mPlayerSide.setDucked(false);
                    }
                } else {
                    if (mPlayerSide != null) {
                        mPlayerSide.setDucked(true);
                    }
                    mVoicePlayer.start();
                }
            } catch (IllegalStateException e) {
                stopVoice();
            }
            notifyVoiceChanged();
            return;
        }

        playVoice(message);
    }

    public boolean isVoicePlaying(Message message) {
        try {
            return message != null && message.key.equals(mVoiceKey) && mVoicePlayer != null && mVoicePlayer.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Position (ms) of the voice note being played or paused, -1 if it isn't this one.
     */
    public int getVoicePosition(Message message) {
        try {
            return message != null && message.key.equals(mVoiceKey) && mVoicePlayer != null ? mVoicePlayer.getCurrentPosition() : -1;
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    public void playVoice(Message message) {
        if (message == null || message.audio == null) {
            return;
        }

        try {
            stopVoice();
            File file = new File(mContext.getCacheDir(), "together_voice.m4a");
            FileOutputStream out = new FileOutputStream(file);
            out.write(Base64.decode(message.audio, Base64.DEFAULT));
            out.close();

            mVoicePlayer = new MediaPlayer();
            mVoicePlayer.setDataSource(file.getAbsolutePath());
            mVoicePlayer.setOnCompletionListener(mp -> stopVoice());
            mVoicePlayer.prepare();
            if (mPlayerSide != null) {
                mPlayerSide.setDucked(true); // the video goes quiet while the note plays
            }
            mVoiceKey = message.key;
            mVoicePlayer.start();
            notifyVoiceChanged();
        } catch (Exception e) {
            Log.e(TAG, "playVoice: %s", e.getMessage());
            stopVoice();
        }
    }

    public void stopVoice() {
        mVoiceKey = null;

        if (mVoicePlayer != null) {
            try {
                mVoicePlayer.release();
            } catch (Exception e) {
                // NOP
            }
            mVoicePlayer = null;
        }

        if (mPlayerSide != null) {
            mPlayerSide.setDucked(false);
        }

        notifyVoiceChanged();
    }

    private void notifyVoiceChanged() {
        for (UiListener listener : mUiListeners) {
            listener.onRoomChanged();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Whole conversation, unread
    // ---------------------------------------------------------------------------------------------

    private void insertSorted(Message message) {
        int index = mHistory.size();

        while (index > 0 && mHistory.get(index - 1).key.compareTo(message.key) > 0) {
            index--;
        }

        mHistory.add(index, message);
    }

    /**
     * The chat was opened: everything said in the room (also before joining), then the screen is told.
     */
    public void loadWholeConversation() {
        TogetherRoom room = mRoom;

        if (room == null) {
            return;
        }

        runAsync(() -> {
            JSONObject all = room.getAllMessages();

            if (all == null) {
                return;
            }

            for (java.util.Iterator<String> keys = all.keys(); keys.hasNext(); ) {
                String key = keys.next();
                JSONObject json = all.optJSONObject(key);
                if (json != null) {
                    Message message = toMessage(key, json, false);
                    post(() -> {
                        if (mRoom != room) {
                            return;
                        }
                        synchronized (mHistory) {
                            for (Message old : mHistory) {
                                if (old.key.equals(key)) {
                                    return;
                                }
                            }
                            insertSorted(message);
                        }
                    });
                }
            }

            post(this::notifyVoiceChanged);
        });
    }

    public void setChatOpen(boolean open) {
        mChatOpen = open;

        if (open) {
            mUnread = 0;
        }

        notifyVoiceChanged();
    }

    public int getUnread() {
        return mUnread;
    }

    private Message toMessage(String key, JSONObject json, boolean isNew) {
        Message message = new Message();
        message.key = key;
        message.by = json.optString("by");
        message.name = json.optString("name");
        message.type = json.optString("type", "text");
        message.text = json.optString("text");
        message.audio = json.optString("audio", null);
        message.durationMs = json.optLong("durationMs");
        message.ts = json.optLong("ts");
        message.mine = mDeviceId.equals(message.by);
        message.isNew = isNew;
        return message;
    }

    // ---------------------------------------------------------------------------------------------
    // TogetherRoom.Listener (network threads)
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onMembers(JSONObject members) {
        java.util.Map<String, String> now = new java.util.LinkedHashMap<>();

        for (java.util.Iterator<String> keys = members.keys(); keys.hasNext(); ) {
            String id = keys.next();
            JSONObject member = members.optJSONObject(id);
            String name = member != null ? member.optString("name") : "";
            now.put(id, TextUtils.isEmpty(name) ? "BlueTube" : name);
        }

        post(() -> {
            if (mRoom == null) {
                return;
            }

            boolean firstList = mMemberNames.isEmpty();
            List<String> joined = new ArrayList<>();
            List<String> left = new ArrayList<>();

            for (java.util.Map.Entry<String, String> member : now.entrySet()) {
                if (!mDeviceId.equals(member.getKey()) && !mMemberNames.containsKey(member.getKey())) {
                    joined.add(member.getValue());
                }
            }

            for (java.util.Map.Entry<String, String> member : mMemberNames.entrySet()) {
                if (!mDeviceId.equals(member.getKey()) && !now.containsKey(member.getKey())) {
                    left.add(member.getValue());
                }
            }

            boolean changed = !mMemberNames.equals(now);
            mMemberNames.clear();
            mMemberNames.putAll(now);
            mMembers = now.size();

            if (changed) {
                com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter.instance(mContext).refreshIfTogetherShown();
            }

            if (firstList) {
                if (!joined.isEmpty()) {
                    addSystemMessage(mContext.getString(R.string.together_with, TextUtils.join(", ", joined)));
                }
            } else {
                for (String name : joined) {
                    addSystemMessage(mContext.getString(R.string.together_system_joined, name));
                }
            }

            for (String name : left) {
                addSystemMessage(mContext.getString(R.string.together_system_left, name));
            }

            if (firstList) {
                // Just came in: who is already here
                if (!joined.isEmpty()) {
                    MessageHelpers.showMessage(mContext, mContext.getString(R.string.together_with, TextUtils.join(", ", joined)), true);
                }
            } else if (!joined.isEmpty()) {
                playChime(R.raw.together_join);
                MessageHelpers.showMessage(mContext, mContext.getString(R.string.together_someone_joined, TextUtils.join(", ", joined)), true);
            } else if (!left.isEmpty()) {
                playChime(R.raw.together_leave);
                MessageHelpers.showMessage(mContext, mContext.getString(R.string.together_someone_left, TextUtils.join(", ", left)), true);
            }

            notifyRoomChanged();
        });
    }

    /**
     * A line in the chat of this device only ("Ana joined"), in time order with the real messages.
     */
    private void addSystemMessage(String text) {
        TogetherRoom room = mRoom;

        if (room == null) {
            return;
        }

        Message message = new Message();
        long now = room.serverNow();
        message.key = pushKey(now);
        message.by = "";
        message.name = "";
        message.type = "system";
        message.text = text;
        message.ts = now;

        synchronized (mHistory) {
            insertSorted(message);
        }

        for (UiListener listener : mUiListeners) {
            listener.onMessage(message);
        }
    }

    /**
     * A key that sorts like the Firebase push ids of the same moment (they start with the time).
     */
    private static String pushKey(long timeMs) {
        String chars = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz";
        char[] key = new char[20];
        for (int i = 7; i >= 0; i--) {
            key[i] = chars.charAt((int) (timeMs % 64));
            timeMs /= 64;
        }
        java.util.Random random = new java.util.Random();
        for (int i = 8; i < 20; i++) {
            key[i] = chars.charAt(random.nextInt(64));
        }
        return new String(key);
    }

    /**
     * A short sound when somebody comes in or leaves (not over a voice note).
     */
    private void playChime(int resId) {
        if (mVoicePlayer != null) {
            return;
        }

        try {
            if (mChimePlayer != null) {
                mChimePlayer.release();
            }
            mChimePlayer = MediaPlayer.create(mContext, resId);
            if (mChimePlayer != null) {
                mChimePlayer.setOnCompletionListener(mp -> {
                    mp.release();
                    if (mChimePlayer == mp) {
                        mChimePlayer = null;
                    }
                });
                mChimePlayer.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "playChime: %s", e.getMessage());
        }
    }

    @Override
    public void onConnectionError(String error) {
        post(() -> {
            if (mRoom != null) {
                MessageHelpers.showMessage(mContext, R.string.together_connection_error);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    public void addUiListener(UiListener listener) {
        if (!mUiListeners.contains(listener)) {
            mUiListeners.add(listener);
        }
    }

    public void removeUiListener(UiListener listener) {
        mUiListeners.remove(listener);
    }

    private void notifyRoomChanged() {
        for (UiListener listener : mUiListeners) {
            listener.onRoomChanged();
        }
    }

    /**
     * The YouTube account name, else the family profile, else "BlueTube".
     */
    public String myName() {
        try {
            Account account = YouTubeSignInService.instance().getSelectedAccount();
            if (account != null && !TextUtils.isEmpty(account.getName())) {
                return account.getName().split(" ")[0];
            }
        } catch (RuntimeException e) {
            // NOP
        }

        String profile = BlueTubeSync.getCurrentProfile(mContext).name;
        return !TextUtils.isEmpty(profile) ? profile : "BlueTube";
    }

    private static byte[] readFile(File file) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toByteArray();
    }

    private void post(Runnable runnable) {
        mHandler.post(runnable);
    }

    private static void runAsync(Runnable runnable) {
        Thread thread = new Thread(runnable, "together-io");
        thread.setDaemon(true);
        thread.start();
    }
}
