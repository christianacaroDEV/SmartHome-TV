package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.misc.together.TogetherManager;
import com.liskovsoft.smartyoutubetv2.common.misc.together.TogetherManager.Message;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.File;
import java.util.List;

/**
 * "Watch together" on the phone: the room dialog (create / join / share / leave) and the panel under the video
 * (who is in, last messages, text box, voice note button, reactions).
 */
public final class MobileTogether {
    private static final int ACCENT = 0xFF2F80FF;
    private static final int TEXT_SECONDARY = 0xFF9FB3D9;
    private static final int CARD = 0xFF13224A;
    private static final int REQUEST_MIC = 4711;
    private static final String[] REACTIONS = {"❤️", "😂", "😮", "👏"};

    private MobileTogether() {
    }

    // ---------------------------------------------------------------------------------------------
    // Room dialog
    // ---------------------------------------------------------------------------------------------

    public static void showDialog(Activity activity) {
        TogetherManager manager = TogetherManager.instance(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 22), dp(activity, 18), dp(activity, 22), dp(activity, 12));
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(box).create();

        TextView title = text(activity, 20, Color.WHITE, true);
        title.setText(R.string.together_title);
        box.addView(title);

        if (manager.isActive()) {
            TextView info = text(activity, 14, TEXT_SECONDARY, false);
            info.setText(manager.getMembers() > 1 ? R.string.together_title : R.string.together_created);
            info.setPadding(0, dp(activity, 10), 0, 0);
            box.addView(info);

            TextView code = text(activity, 34, Color.WHITE, true);
            code.setText(formatCode(manager.getCode()));
            if (Build.VERSION.SDK_INT >= 21) { code.setLetterSpacing(0.12f); }
            code.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
            box.addView(code);

            TextView members = text(activity, 13, TEXT_SECONDARY, false);
            members.setText(manager.getMembers() > 1
                    ? activity.getString(R.string.together_in_room, formatCode(manager.getCode()), manager.getMembers())
                    : activity.getString(R.string.together_waiting));
            box.addView(members);

            box.addView(button(activity, R.string.together_share, true, v -> share(activity, manager.getCode())));
            // Back to the video (the code stays in the "Watching together" bar: tap it to share again)
            box.addView(button(activity, R.string.together_keep_watching, false, v -> dialog.dismiss()));
            box.addView(button(activity, R.string.together_leave, false, v -> {
                manager.leave();
                dialog.dismiss();
            }));

            // Someone joined: the window goes away by itself
            int membersAtOpen = manager.getMembers();
            TogetherManager.UiListener closer = new TogetherManager.UiListener() {
                @Override
                public void onRoomChanged() {
                    if (manager.getMembers() > Math.max(1, membersAtOpen) && dialog.isShowing()) {
                        dialog.dismiss();
                    }
                }

                @Override
                public void onMessage(Message message) {
                    // NOP
                }
            };
            manager.addUiListener(closer);
            dialog.setOnDismissListener(d -> manager.removeUiListener(closer));
        } else {
            TextView hint = text(activity, 14, TEXT_SECONDARY, false);
            hint.setText(R.string.together_create_hint);
            hint.setPadding(0, dp(activity, 8), 0, dp(activity, 4));
            box.addView(hint);

            box.addView(button(activity, R.string.together_create, true, v -> {
                v.setEnabled(false);
                manager.create(success -> {
                    if (success) {
                        dialog.dismiss();
                        showDialog(activity); // now with the code to share
                    } else {
                        v.setEnabled(true);
                        Toast.makeText(activity, R.string.together_create_failed, Toast.LENGTH_LONG).show();
                    }
                });
            }));

            EditText code = new EditText(activity);
            code.setHint(R.string.together_code_hint);
            code.setInputType(InputType.TYPE_CLASS_NUMBER);
            code.setTextColor(Color.WHITE);
            code.setHintTextColor(TEXT_SECONDARY);
            code.setTextSize(20);
            if (Build.VERSION.SDK_INT >= 21) { code.setLetterSpacing(0.1f); }
            code.setImeOptions(EditorInfo.IME_ACTION_GO);
            LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            codeParams.setMargins(0, dp(activity, 18), 0, 0);
            box.addView(code, codeParams);

            View join = button(activity, R.string.together_join, false, null);
            join.setOnClickListener(v -> {
                v.setEnabled(false);
                manager.join(code.getText().toString(), success -> {
                    v.setEnabled(true);
                    if (success) {
                        dialog.dismiss();
                    } else {
                        Toast.makeText(activity, R.string.together_join_failed, Toast.LENGTH_LONG).show();
                    }
                });
            });
            code.setOnEditorActionListener((v, actionId, event) -> {
                join.performClick();
                return true;
            });
            box.addView(join);
        }

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF0F1C3A);
        background.setCornerRadius(dp(activity, 18));

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(background);
        }
    }

    private static void share(Context context, String code) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, context.getString(R.string.together_share_text, formatCode(code)));
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.together_share)));
    }

    public static String formatCode(String code) {
        return code != null && code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : String.valueOf(code);
    }

    // ---------------------------------------------------------------------------------------------
    // Panel under the video
    // ---------------------------------------------------------------------------------------------

    /**
     * Under the video, only while in a room: who is in, the last 2 messages, new messages count, "Open chat".
     */
    public static class Panel extends LinearLayout implements TogetherManager.UiListener {
        private static final int VISIBLE_MESSAGES = 2;
        private final TogetherManager mManager;
        private final TextView mHeader;
        private final TextView mOpen;
        private final LinearLayout mMessages;

        public Panel(Context context) {
            super(context);
            mManager = TogetherManager.instance(context);
            setOrientation(VERTICAL);
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
            GradientDrawable background = new GradientDrawable();
            background.setColor(CARD);
            background.setCornerRadius(dp(context, 14));
            setBackground(background);
            setOnClickListener(v -> openChat());

            LinearLayout top = new LinearLayout(context);
            top.setGravity(Gravity.CENTER_VERTICAL);
            mHeader = text(context, 13, Color.WHITE, true);
            mHeader.setOnClickListener(v -> {
                if (getContext() instanceof Activity) {
                    showDialog((Activity) getContext()); // code, share, leave
                }
            });
            top.addView(mHeader, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mOpen = text(context, 13, 0xFF7FB0FF, false);
            mOpen.setPadding(dp(context, 10), dp(context, 4), 0, dp(context, 4));
            mOpen.setOnClickListener(v -> openChat());
            top.addView(mOpen);
            addView(top);

            mMessages = new LinearLayout(context);
            mMessages.setOrientation(VERTICAL);
            mMessages.setPadding(0, dp(context, 6), 0, 0);
            addView(mMessages);

            refresh();
        }

        private void openChat() {
            if (getContext() instanceof Activity) {
                ChatSheet.show((Activity) getContext());
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mManager.addUiListener(this);
            refresh();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mManager.removeUiListener(this);
        }

        @Override
        public void onRoomChanged() {
            refresh();
        }

        @Override
        public void onMessage(Message message) {
            refresh();

            if (message.isNew && !message.mine && !ChatSheet.isShowing()) {
                Toast.makeText(getContext(), mManager.describe(message), Toast.LENGTH_SHORT).show();
            }
        }

        private void refresh() {
            boolean active = mManager.isActive();
            setVisibility(active ? VISIBLE : GONE);

            if (!active) {
                return;
            }

            int members = Math.max(1, mManager.getMembers());
            mHeader.setText("👥  " + getContext().getString(R.string.together_in_room, formatCode(mManager.getCode()), members));
            int unread = mManager.getUnread();
            mOpen.setText(unread > 0
                    ? getContext().getString(R.string.together_open_chat_unread, unread)
                    : getContext().getString(R.string.together_open_chat));

            mMessages.removeAllViews();
            List<Message> history = mManager.getHistory();

            for (int i = Math.max(0, history.size() - VISIBLE_MESSAGES); i < history.size(); i++) {
                Message message = history.get(i);
                TextView line = text(getContext(), 13, message.mine ? TEXT_SECONDARY : Color.WHITE, false);
                line.setText(mManager.describe(message));
                line.setMaxLines(1);
                line.setEllipsize(TextUtils.TruncateAt.END);
                line.setPadding(0, dp(getContext(), 2), 0, dp(getContext(), 2));
                mMessages.addView(line);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Whole chat
    // ---------------------------------------------------------------------------------------------

    /**
     * The whole conversation from the bottom (the video stays above): bubbles, voice notes with play/pause,
     * "new messages" when reading older ones, reactions, text box and hold-to-talk.
     */
    public static class ChatSheet implements TogetherManager.UiListener {
        private static ChatSheet sShowing;
        private final Activity mActivity;
        private final TogetherManager mManager;
        private final android.app.Dialog mDialog;
        private final androidx.recyclerview.widget.RecyclerView mList;
        private final androidx.recyclerview.widget.LinearLayoutManager mLayout;
        private final Adapter mAdapter = new Adapter();
        private final TextView mNewMessages;
        private final TextView mTitle;
        private final EditText mInput;
        private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable mVoiceTicker = new Runnable() {
            @Override
            public void run() {
                mAdapter.notifyVoiceProgress();
                mHandler.postDelayed(this, 250);
            }
        };
        private int mShownCount;
        private MediaRecorder mRecorder;
        private File mRecording;
        private long mRecordStart;

        public static boolean isShowing() {
            return sShowing != null;
        }

        public static void show(Activity activity) {
            if (sShowing == null && TogetherManager.instance(activity).isActive()) {
                new ChatSheet(activity);
            }
        }

        private ChatSheet(Activity activity) {
            mActivity = activity;
            mManager = TogetherManager.instance(activity);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xFF0A1530);
            float radius = dp(activity, 18);
            background.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
            root.setBackground(background);

            // Title row: "Chat · room 984 045" | close
            LinearLayout top = new LinearLayout(activity);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 6), dp(activity, 6));
            mTitle = text(activity, 15, Color.WHITE, true);
            top.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton close = iconButton(activity, R.drawable.ic_m_close);
            close.setContentDescription(activity.getString(R.string.together_keep_watching));
            top.addView(close, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
            root.addView(top);

            // Messages (scrolls) + "new messages" button over them
            android.widget.FrameLayout messagesBox = new android.widget.FrameLayout(activity);
            mList = new androidx.recyclerview.widget.RecyclerView(activity);
            mLayout = new androidx.recyclerview.widget.LinearLayoutManager(activity);
            mLayout.setStackFromEnd(true);
            mList.setLayoutManager(mLayout);
            mList.setAdapter(mAdapter);
            mList.setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4));
            mList.setClipToPadding(false);
            messagesBox.addView(mList, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            mNewMessages = text(activity, 13, Color.WHITE, true);
            mNewMessages.setPadding(dp(activity, 14), dp(activity, 6), dp(activity, 14), dp(activity, 6));
            mNewMessages.setBackground(pill(activity, ACCENT));
            mNewMessages.setVisibility(View.GONE);
            mNewMessages.setOnClickListener(v -> scrollToEnd(true));
            android.widget.FrameLayout.LayoutParams newParams = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            newParams.bottomMargin = dp(activity, 8);
            messagesBox.addView(mNewMessages, newParams);
            root.addView(messagesBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            mList.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                    if (isAtEnd()) {
                        mNewMessages.setVisibility(View.GONE);
                    }
                }
            });

            // Reactions
            LinearLayout reactions = new LinearLayout(activity);
            reactions.setPadding(dp(activity, 8), dp(activity, 2), dp(activity, 8), 0);
            for (String reaction : REACTIONS) {
                TextView chip = text(activity, 20, Color.WHITE, false);
                chip.setText(reaction);
                chip.setGravity(Gravity.CENTER);
                chip.setOnClickListener(v -> mManager.sendText(reaction));
                reactions.addView(chip, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 40)));
            }
            root.addView(reactions);

            // Text box | send | hold-to-talk
            LinearLayout inputRow = new LinearLayout(activity);
            inputRow.setGravity(Gravity.CENTER_VERTICAL);
            inputRow.setPadding(dp(activity, 12), 0, dp(activity, 6), dp(activity, 8));
            mInput = new EditText(activity);
            mInput.setHint(R.string.together_message_hint);
            mInput.setTextColor(Color.WHITE);
            mInput.setHintTextColor(TEXT_SECONDARY);
            mInput.setTextSize(15);
            mInput.setSingleLine(true);
            mInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
            mInput.setOnEditorActionListener((v, actionId, event) -> {
                sendText();
                return true;
            });
            inputRow.addView(mInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton send = iconButton(activity, R.drawable.ic_m_play);
            send.setContentDescription(activity.getString(R.string.together_message_hint));
            send.setOnClickListener(v -> sendText());
            inputRow.addView(send, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
            ImageButton mic = iconButton(activity, R.drawable.ic_m_mic);
            mic.setContentDescription(activity.getString(R.string.together_hold_to_talk));
            mic.setOnTouchListener(this::onMicTouch);
            inputRow.addView(mic, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
            root.addView(inputRow);

            // Above the system navigation buttons (the window goes edge to edge)
            int baseBottom = root.getPaddingBottom();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), baseBottom + insets.getSystemWindowInsetBottom());
                return insets;
            });

            mDialog = new android.app.Dialog(activity);
            mDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            mDialog.setContentView(root);
            close.setOnClickListener(v -> mDialog.dismiss());
            mDialog.setOnDismissListener(d -> onClosed());

            sShowing = this;
            mManager.addUiListener(this);
            mManager.setChatOpen(true);
            mManager.loadWholeConversation();
            refresh(true);
            mDialog.show();

            android.view.Window window = mDialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                int height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.68f);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
                window.setGravity(Gravity.BOTTOM);
                window.setDimAmount(0.25f); // the video stays visible above
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                window.getDecorView().setPadding(0, 0, 0, 0);
                int navigation = navigationBarHeight(activity);
                root.setPadding(0, 0, 0, navigation); // keyboard/insets update it later
            }

            mHandler.post(mVoiceTicker);
        }

        private void onClosed() {
            sShowing = null;
            mHandler.removeCallbacks(mVoiceTicker);
            stopRecording(false);
            mManager.removeUiListener(this);
            mManager.setChatOpen(false);
        }

        @Override
        public void onRoomChanged() {
            if (!mManager.isActive()) {
                mDialog.dismiss();
                return;
            }

            refresh(false);
        }

        @Override
        public void onMessage(Message message) {
            refresh(false);
        }

        private void refresh(boolean first) {
            mTitle.setText("👥  " + mActivity.getString(R.string.together_chat_title, formatCode(mManager.getCode())));
            boolean wasAtEnd = first || isAtEnd();
            List<Message> history = mManager.getHistory();
            int added = history.size() - mShownCount;
            boolean lastIsMine = !history.isEmpty() && history.get(history.size() - 1).mine;
            mAdapter.setItems(history);
            mShownCount = history.size();

            if (wasAtEnd || lastIsMine) {
                scrollToEnd(false);
            } else if (added > 0) {
                // Reading older messages: don't jump, offer to go down
                int pending = added + (mNewMessages.getVisibility() == View.VISIBLE ? parseCount() : 0);
                mNewMessages.setTag(pending);
                mNewMessages.setText(mActivity.getString(R.string.together_new_messages, pending));
                mNewMessages.setVisibility(View.VISIBLE);
            }
        }

        private int parseCount() {
            Object tag = mNewMessages.getTag();
            return tag instanceof Integer ? (Integer) tag : 0;
        }

        private boolean isAtEnd() {
            int count = mAdapter.getItemCount();
            return count == 0 || mLayout.findLastVisibleItemPosition() >= count - 2;
        }

        private void scrollToEnd(boolean smooth) {
            mNewMessages.setVisibility(View.GONE);
            int last = mAdapter.getItemCount() - 1;
            if (last >= 0) {
                if (smooth) {
                    mList.smoothScrollToPosition(last);
                } else {
                    mList.scrollToPosition(last);
                }
            }
        }

        private void sendText() {
            String text = mInput.getText().toString();

            if (!TextUtils.isEmpty(text.trim())) {
                mManager.sendText(text);
                mInput.setText("");
            }
        }

        // -----------------------------------------------------------------------------------------
        // Voice notes: hold the mic, talk, release
        // -----------------------------------------------------------------------------------------

        private boolean onMicTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!hasMicPermission()) {
                        requestMicPermission();
                        return true;
                    }
                    view.setPressed(true);
                    startRecording();
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    stopRecording(true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    stopRecording(false);
                    return true;
            }

            return true;
        }

        private boolean hasMicPermission() {
            return Build.VERSION.SDK_INT < 23
                    || mActivity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        private void requestMicPermission() {
            Toast.makeText(mActivity, R.string.together_mic_permission, Toast.LENGTH_LONG).show();

            if (Build.VERSION.SDK_INT >= 23) {
                mActivity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            }
        }

        @SuppressWarnings("deprecation")
        private void startRecording() {
            stopRecording(false);
            mManager.stopVoice(); // don't record the note being played

            try {
                mRecording = new File(mActivity.getCacheDir(), "together_record.m4a");
                mRecorder = new MediaRecorder();
                mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mRecorder.setAudioChannels(1);
                mRecorder.setAudioSamplingRate(16_000);
                mRecorder.setAudioEncodingBitRate(24_000); // ~3 KB per second: small enough for the database
                mRecorder.setMaxDuration(60_000);
                mRecorder.setOutputFile(mRecording.getAbsolutePath());
                mRecorder.prepare();
                mRecorder.start();
                mRecordStart = System.currentTimeMillis();
                Toast.makeText(mActivity, R.string.together_recording, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                stopRecording(false);
            }
        }

        private void stopRecording(boolean send) {
            if (mRecorder == null) {
                return;
            }

            long duration = System.currentTimeMillis() - mRecordStart;

            try {
                mRecorder.stop();
            } catch (RuntimeException e) {
                send = false; // too short: nothing recorded
            }

            mRecorder.release();
            mRecorder = null;

            if (send && duration >= 700) {
                mManager.sendVoice(mRecording, duration, success -> {
                    if (!success) {
                        Toast.makeText(mActivity, R.string.together_connection_error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        // -----------------------------------------------------------------------------------------
        // Bubbles
        // -----------------------------------------------------------------------------------------

        private class Adapter extends androidx.recyclerview.widget.RecyclerView.Adapter<BubbleHolder> {
            private List<Message> mItems = new java.util.ArrayList<>();

            void setItems(List<Message> items) {
                mItems = items;
                notifyDataSetChanged();
            }

            void notifyVoiceProgress() {
                for (int i = 0; i < mList.getChildCount(); i++) {
                    androidx.recyclerview.widget.RecyclerView.ViewHolder holder = mList.getChildViewHolder(mList.getChildAt(i));
                    if (holder instanceof BubbleHolder) {
                        ((BubbleHolder) holder).updateVoice();
                    }
                }
            }

            @androidx.annotation.NonNull
            @Override
            public BubbleHolder onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int viewType) {
                return new BubbleHolder(parent.getContext());
            }

            @Override
            public void onBindViewHolder(@androidx.annotation.NonNull BubbleHolder holder, int position) {
                holder.bind(mItems.get(position));
            }

            @Override
            public int getItemCount() {
                return mItems.size();
            }
        }

        private class BubbleHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            private final LinearLayout mBubble;
            private final TextView mName;
            private final TextView mText;
            private final LinearLayout mVoice;
            private final ImageButton mPlay;
            private final android.widget.ProgressBar mProgress;
            private final TextView mDuration;
            private final TextView mTime;
            private Message mMessage;

            BubbleHolder(Context context) {
                super(new LinearLayout(context));
                LinearLayout row = (LinearLayout) itemView;
                row.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setPadding(0, dp(context, 3), 0, dp(context, 3));

                mBubble = new LinearLayout(context);
                mBubble.setOrientation(LinearLayout.VERTICAL);
                mBubble.setPadding(dp(context, 11), dp(context, 7), dp(context, 11), dp(context, 6));
                row.addView(mBubble, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                mName = text(context, 11, TEXT_SECONDARY, true);
                mBubble.addView(mName);
                mText = text(context, 15, Color.WHITE, false);
                mText.setMaxWidth(Math.round(context.getResources().getDisplayMetrics().widthPixels * 0.72f));
                mBubble.addView(mText);

                mVoice = new LinearLayout(context);
                mVoice.setGravity(Gravity.CENTER_VERTICAL);
                mPlay = iconButton(context, R.drawable.ic_m_play);
                mPlay.setPadding(dp(context, 7), dp(context, 7), dp(context, 7), dp(context, 7));
                mVoice.addView(mPlay, new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)));
                mProgress = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
                mProgress.setMax(1000);
                LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(context, 120), dp(context, 6));
                progressParams.setMargins(dp(context, 4), 0, dp(context, 8), 0);
                mVoice.addView(mProgress, progressParams);
                mDuration = text(context, 12, Color.WHITE, false);
                mVoice.addView(mDuration);
                mBubble.addView(mVoice);

                mTime = text(context, 10, TEXT_SECONDARY, false);
                mTime.setGravity(Gravity.END);
                mBubble.addView(mTime, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            void bind(Message message) {
                mMessage = message;
                Context context = itemView.getContext();
                LinearLayout row = (LinearLayout) itemView;
                row.setGravity(message.mine ? Gravity.END : Gravity.START);

                boolean isReaction = "text".equals(message.type) && message.text != null && message.text.length() <= 4
                        && !message.text.matches(".*[\\p{L}\\p{N}].*");
                float r = dp(context, 14);
                float small = dp(context, 4);
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(isReaction ? Color.TRANSPARENT : message.mine ? ACCENT : 0xFF1B2A4F);
                shape.setCornerRadii(message.mine
                        ? new float[] {r, r, r, r, small, small, r, r}
                        : new float[] {r, r, r, r, r, r, small, small});
                mBubble.setBackground(shape);

                mName.setText(message.mine ? context.getString(R.string.together_you) : message.name);
                mName.setTextColor(message.mine ? 0xFFDCE6FA : TEXT_SECONDARY);
                mTime.setText(message.ts > 0 ? new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(message.ts)) : "");
                mTime.setTextColor(message.mine ? 0xFFDCE6FA : TEXT_SECONDARY);

                boolean voice = "voice".equals(message.type);
                mText.setVisibility(voice ? View.GONE : View.VISIBLE);
                mVoice.setVisibility(voice ? View.VISIBLE : View.GONE);
                mText.setTextSize(isReaction ? 28 : 15);
                mText.setText(message.text);

                if (voice) {
                    mPlay.setOnClickListener(v -> {
                        mManager.toggleVoice(message);
                        updateVoice();
                    });
                    updateVoice();
                }
            }

            void updateVoice() {
                if (mMessage == null || !"voice".equals(mMessage.type)) {
                    return;
                }

                boolean playing = mManager.isVoicePlaying(mMessage);
                int position = mManager.getVoicePosition(mMessage);
                long duration = Math.max(1, mMessage.durationMs);
                mPlay.setImageResource(playing ? R.drawable.ic_m_pause : R.drawable.ic_m_play);
                mProgress.setProgress(position >= 0 ? (int) Math.min(1000, position * 1000L / duration) : 0);
                mDuration.setText(position >= 0
                        ? formatSeconds(position) + " / " + formatSeconds(duration)
                        : formatSeconds(duration));
            }
        }

        private static int navigationBarHeight(Context context) {
            int id = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            return id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
        }

        private static String formatSeconds(long ms) {
            long seconds = Math.max(0, Math.round(ms / 1000f));
            return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
        }
    }

    private static GradientDrawable pill(Context context, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, 16));
        return shape;
    }

    // ---------------------------------------------------------------------------------------------
    // Small views
    // ---------------------------------------------------------------------------------------------

    private static View button(Context context, int textResId, boolean primary, View.OnClickListener listener) {
        TextView button = text(context, 15, Color.WHITE, true);
        button.setText(textResId);
        button.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 22));
        if (primary) {
            shape.setColor(ACCENT);
        } else {
            shape.setColor(Color.TRANSPARENT);
            shape.setStroke(dp(context, 1), 0xFF3A4E80);
        }
        button.setBackground(shape);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 44));
        params.setMargins(0, dp(context, 12), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private static ImageButton iconButton(Context context, int iconResId) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(iconResId);
        button.setBackground(null);
        button.setPadding(dp(context, 9), dp(context, 9), dp(context, 9), dp(context, 9));
        button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        return button;
    }

    private static TextView text(Context context, int sizeSp, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        if (bold) {
            text.setTypeface(null, Typeface.BOLD);
        }
        return text;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
