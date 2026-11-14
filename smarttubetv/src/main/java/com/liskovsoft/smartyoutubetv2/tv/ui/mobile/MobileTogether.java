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
    private static final String[] REACTIONS = {"❤️", "😂", "😮", "👏", "🔥", "😢", "👍", "🙏"};
    private static final int RECORDING = 0xFFE5484D;

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
                    ? manager.describeRoom(activity)
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
            // One thing at a time: first the choice, then only what that choice needs
            TextView hint = text(activity, 14, TEXT_SECONDARY, false);
            hint.setText(R.string.together_choose);
            hint.setPadding(0, dp(activity, 8), 0, dp(activity, 4));
            box.addView(hint);

            LinearLayout choice = new LinearLayout(activity);
            choice.setOrientation(LinearLayout.VERTICAL);
            box.addView(choice);

            LinearLayout joinBox = new LinearLayout(activity);
            joinBox.setOrientation(LinearLayout.VERTICAL);
            joinBox.setVisibility(View.GONE);
            box.addView(joinBox);

            // 1) Create: one tap
            choice.addView(option(activity, "➕", R.string.together_create, R.string.together_create_desc, v -> {
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

            // 2) Join: shows the code field (and only it)
            EditText code = new EditText(activity);
            choice.addView(option(activity, "🔑", R.string.together_join_room, R.string.together_join_desc, v -> {
                choice.setVisibility(View.GONE);
                hint.setText(R.string.together_code_hint);
                joinBox.setVisibility(View.VISIBLE);
                code.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    code.postDelayed(() -> imm.showSoftInput(code, 0), 150);
                }
            }));

            code.setHint("000 000");
            code.setInputType(InputType.TYPE_CLASS_NUMBER);
            code.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(6)});
            code.setTextColor(Color.WHITE);
            code.setHintTextColor(TEXT_SECONDARY);
            code.setTextSize(26);
            code.setGravity(Gravity.CENTER);
            if (Build.VERSION.SDK_INT >= 21) { code.setLetterSpacing(0.25f); }
            code.setImeOptions(EditorInfo.IME_ACTION_GO);
            LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            codeParams.setMargins(0, dp(activity, 12), 0, 0);
            joinBox.addView(code, codeParams);

            View join = button(activity, R.string.together_join, true, null);
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
            joinBox.addView(join);

            joinBox.addView(button(activity, R.string.together_back, false, v -> {
                joinBox.setVisibility(View.GONE);
                hint.setText(R.string.together_choose);
                choice.setVisibility(View.VISIBLE);
            }));
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

            mHeader.setText("👥  " + mManager.describeRoom(getContext()));
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
        private final TextView mSubtitle;
        private final TextView mPlayAll;
        private final LinearLayout mEmojiBar;
        private final ImageButton mAction;
        private final EditText mInput;
        private boolean mCancelled;
        private float mTouchStartX;
        private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable mVoiceTicker = new Runnable() {
            @Override
            public void run() {
                mAdapter.notifyVoiceProgress();
                updateRecordingHint();
                updatePlayAll();
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

            // Title: "Room 984 045" + who is in | share | close
            LinearLayout top = new LinearLayout(activity);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 6), dp(activity, 4));
            LinearLayout titles = new LinearLayout(activity);
            titles.setOrientation(LinearLayout.VERTICAL);
            mTitle = text(activity, 16, Color.WHITE, true);
            titles.addView(mTitle);
            mSubtitle = text(activity, 12, TEXT_SECONDARY, false);
            mSubtitle.setMaxLines(1);
            mSubtitle.setEllipsize(TextUtils.TruncateAt.END);
            titles.addView(mSubtitle);
            top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton share = iconButton(activity, R.drawable.ic_m_share);
            share.setContentDescription(activity.getString(R.string.together_share));
            share.setOnClickListener(v -> share(activity, mManager.getCode()));
            top.addView(share, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
            ImageButton close = iconButton(activity, R.drawable.ic_m_close);
            close.setContentDescription(activity.getString(R.string.together_keep_watching));
            top.addView(close, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
            root.addView(top);

            // Everybody's video: pause/play | -10 s | sync
            android.widget.HorizontalScrollView controlsScroll = new android.widget.HorizontalScrollView(activity);
            controlsScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout controls = new LinearLayout(activity);
            controls.setPadding(dp(activity, 12), dp(activity, 2), dp(activity, 12), dp(activity, 8));
            mPlayAll = chip(activity, activity.getString(R.string.together_pause_all), R.drawable.ic_m_pause);
            mPlayAll.setOnClickListener(v -> control(TogetherManager.ACTION_TOGGLE_PLAY));
            controls.addView(mPlayAll);
            TextView rewind = chip(activity, "10 s", R.drawable.ic_m_replay);
            rewind.setOnClickListener(v -> control(TogetherManager.ACTION_REWIND));
            controls.addView(rewind);
            TextView sync = chip(activity, activity.getString(R.string.together_sync), R.drawable.ic_m_sync);
            sync.setOnClickListener(v -> Toast.makeText(mActivity, mManager.syncNow()
                    ? R.string.together_synced : R.string.together_nothing_to_sync, Toast.LENGTH_SHORT).show());
            controls.addView(sync);
            controlsScroll.addView(controls);
            root.addView(controlsScroll);

            View divider = new View(activity);
            divider.setBackgroundColor(0xFF1B2A4F);
            root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(activity, 1) / 2)));

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

            // Quick phrases (one tap = sent)
            android.widget.HorizontalScrollView phrasesScroll = new android.widget.HorizontalScrollView(activity);
            phrasesScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout phrases = new LinearLayout(activity);
            phrases.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 2));
            for (int resId : new int[] {R.string.together_quick_1, R.string.together_quick_2, R.string.together_quick_3}) {
                String phrase = activity.getString(resId);
                TextView chip = chip(activity, phrase, 0);
                chip.setOnClickListener(v -> mManager.sendText(phrase));
                phrases.addView(chip);
            }
            phrasesScroll.addView(phrases);
            root.addView(phrasesScroll);

            // Emojis, opened with the smiley button
            mEmojiBar = new LinearLayout(activity);
            mEmojiBar.setGravity(Gravity.CENTER_HORIZONTAL);
            mEmojiBar.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), 0);
            mEmojiBar.setVisibility(View.GONE);
            for (String reaction : REACTIONS) {
                TextView emoji = text(activity, 24, Color.WHITE, false);
                emoji.setText(reaction);
                emoji.setGravity(Gravity.CENTER);
                emoji.setOnClickListener(v -> {
                    mManager.sendText(reaction);
                    mEmojiBar.setVisibility(View.GONE);
                });
                mEmojiBar.addView(emoji, new LinearLayout.LayoutParams(0, dp(activity, 46), 1f));
            }
            root.addView(mEmojiBar);

            // Smiley | text box | one button: mic (hold to talk) while empty, send once something is typed
            LinearLayout inputRow = new LinearLayout(activity);
            inputRow.setGravity(Gravity.CENTER_VERTICAL);
            inputRow.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 8));
            TextView smiley = text(activity, 22, Color.WHITE, false);
            smiley.setText("🙂");
            smiley.setGravity(Gravity.CENTER);
            smiley.setContentDescription(activity.getString(R.string.together_emojis));
            smiley.setBackground(circle(CARD));
            smiley.setOnClickListener(v -> mEmojiBar.setVisibility(mEmojiBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            inputRow.addView(smiley, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
            mInput = new EditText(activity);
            mInput.setHint(R.string.together_message_hint);
            mInput.setTextColor(Color.WHITE);
            mInput.setHintTextColor(TEXT_SECONDARY);
            mInput.setTextSize(15);
            mInput.setSingleLine(true);
            mInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
            mInput.setBackground(pill(activity, CARD));
            mInput.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10));
            mInput.setOnEditorActionListener((v, actionId, event) -> {
                sendText();
                return true;
            });
            LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            inputParams.setMargins(dp(activity, 8), 0, dp(activity, 8), 0);
            inputRow.addView(mInput, inputParams);
            mAction = iconButton(activity, R.drawable.ic_m_mic);
            mAction.setPadding(dp(activity, 11), dp(activity, 11), dp(activity, 11), dp(activity, 11));
            mAction.setBackground(circle(ACCENT));
            mAction.setOnTouchListener(this::onActionTouch);
            inputRow.addView(mAction, new LinearLayout.LayoutParams(dp(activity, 46), dp(activity, 46)));
            root.addView(inputRow);
            mInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    updateActionButton();
                }
            });
            updateActionButton();

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
            mTitle.setText(mActivity.getString(R.string.together_room_title, formatCode(mManager.getCode())));
            String others = mManager.getOtherNames();
            mSubtitle.setText(others.isEmpty()
                    ? mActivity.getString(R.string.together_waiting)
                    : mActivity.getString(R.string.together_with, others) + "  ·  "
                        + mActivity.getResources().getQuantityString(R.plurals.together_people, mManager.getMembers(), mManager.getMembers()));
            updatePlayAll();
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

        private void control(int action) {
            if (!mManager.control(action)) {
                Toast.makeText(mActivity, R.string.together_open_video_first, Toast.LENGTH_SHORT).show();
            }
            updatePlayAll();
        }

        private void updatePlayAll() {
            Boolean playing = mManager.isPlaying();
            boolean paused = playing != null && !playing;
            String label = mActivity.getString(paused ? R.string.together_play_all : R.string.together_pause_all);

            if (!label.contentEquals(mPlayAll.getText())) {
                mPlayAll.setText(label);
                setChipIcon(mPlayAll, paused ? R.drawable.ic_m_play : R.drawable.ic_m_pause);
            }
        }

        private boolean hasText() {
            return !TextUtils.isEmpty(mInput.getText().toString().trim());
        }

        private void updateActionButton() {
            boolean text = hasText();
            mAction.setImageResource(text ? R.drawable.ic_m_send : R.drawable.ic_m_mic);
            mAction.setContentDescription(mActivity.getString(text ? R.string.together_send : R.string.together_hold_to_talk));
        }

        // -----------------------------------------------------------------------------------------
        // Voice notes: hold the mic, talk, release (slide left to cancel)
        // -----------------------------------------------------------------------------------------

        private boolean onActionTouch(View view, MotionEvent event) {
            if (hasText() && mRecorder == null) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    sendText();
                }
                return true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!hasMicPermission()) {
                        requestMicPermission();
                        return true;
                    }
                    mTouchStartX = event.getRawX();
                    mCancelled = false;
                    startRecording();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mRecorder != null && mTouchStartX - event.getRawX() > dp(mActivity, 90)) {
                        mCancelled = true;
                        stopRecording(false);
                        Toast.makeText(mActivity, R.string.together_recording_cancelled, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    stopRecording(!mCancelled);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopRecording(false);
                    return true;
            }

            return true;
        }

        /**
         * While recording, the text box shows the time and how to cancel; the button turns red.
         */
        private void updateRecordingHint() {
            if (mRecorder != null) {
                mInput.setHint(mActivity.getString(R.string.together_recording_live,
                        formatSeconds(System.currentTimeMillis() - mRecordStart)));
                mInput.setHintTextColor(0xFFFF9EA1);
                mAction.setBackground(circle(RECORDING));
            }
        }

        private void resetRecordingHint() {
            mInput.setHint(R.string.together_message_hint);
            mInput.setHintTextColor(TEXT_SECONDARY);
            mAction.setBackground(circle(ACCENT));
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
                updateRecordingHint();
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
            resetRecordingHint();

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
            private final WaveView mProgress;
            private final TextView mSystem;
            private final TextView mDuration;
            private final TextView mTime;
            private Message mMessage;

            BubbleHolder(Context context) {
                super(new LinearLayout(context));
                LinearLayout row = (LinearLayout) itemView;
                row.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setPadding(0, dp(context, 3), 0, dp(context, 3));

                mSystem = text(context, 12, TEXT_SECONDARY, false);
                mSystem.setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 4));
                mSystem.setBackground(pill(context, CARD));
                row.addView(mSystem, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
                mProgress = new WaveView(context);
                LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(context, 120), dp(context, 26));
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
                boolean system = "system".equals(message.type);
                mSystem.setVisibility(system ? View.VISIBLE : View.GONE);
                mBubble.setVisibility(system ? View.GONE : View.VISIBLE);

                if (system) {
                    row.setGravity(Gravity.CENTER_HORIZONTAL);
                    String time = message.ts > 0 ? new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(message.ts)) : "";
                    mSystem.setText(time.isEmpty() ? message.text : message.text + "  ·  " + time);
                    return;
                }

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
                    mProgress.setSeed(message.key != null ? message.key.hashCode() : 0);
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
                mProgress.setProgress(position >= 0 ? Math.min(1f, position / (float) duration) : 0f);
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

    /**
     * Voice note "waveform": bars (always the same for a note) that light up while it plays.
     */
    private static class WaveView extends View {
        private static final int BARS = 24;
        private final android.graphics.Paint mPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float[] mHeights = new float[BARS];
        private float mProgress;

        WaveView(Context context) {
            super(context);
            setSeed(0);
        }

        void setSeed(int seed) {
            java.util.Random random = new java.util.Random(seed);
            for (int i = 0; i < BARS; i++) {
                mHeights[i] = 0.25f + random.nextFloat() * 0.75f;
            }
            invalidate();
        }

        void setProgress(float progress) {
            if (progress != mProgress) {
                mProgress = progress;
                invalidate();
            }
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float step = getWidth() / (float) BARS;
            float bar = step * 0.55f;
            float middle = getHeight() / 2f;

            for (int i = 0; i < BARS; i++) {
                mPaint.setColor((i + 0.5f) / BARS <= mProgress ? Color.WHITE : 0x80FFFFFF);
                float half = Math.max(bar / 2, mHeights[i] * getHeight() / 2f);
                float left = i * step + (step - bar) / 2;
                canvas.drawRoundRect(new android.graphics.RectF(left, middle - half, left + bar, middle + half), bar / 2, bar / 2, mPaint);
            }
        }
    }

    /**
     * A rounded chip with an optional icon on the left (chat controls and quick phrases).
     */
    private static TextView chip(Context context, String label, int iconResId) {
        TextView chip = text(context, 13, Color.WHITE, false);
        chip.setText(label);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setSingleLine(true);
        chip.setPadding(dp(context, 12), 0, dp(context, 14), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD);
        shape.setStroke(dp(context, 1), 0xFF3A4E80);
        shape.setCornerRadius(dp(context, 18));
        chip.setBackground(shape);
        setChipIcon(chip, iconResId);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36));
        params.setMargins(0, 0, dp(context, 8), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private static void setChipIcon(TextView chip, int iconResId) {
        Context context = chip.getContext();

        if (iconResId == 0) {
            chip.setCompoundDrawables(null, null, null, null);
            return;
        }

        android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(context, iconResId);
        if (icon != null) {
            icon = icon.mutate();
            icon.setBounds(0, 0, dp(context, 18), dp(context, 18));
        }
        chip.setCompoundDrawables(icon, null, null, null);
        chip.setCompoundDrawablePadding(dp(context, 6));
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
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

    /**
     * A big choice row: icon | title + one line of help.
     */
    private static View option(Context context, String icon, int titleResId, int descResId, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 14));
        shape.setColor(CARD);
        row.setBackground(shape);
        row.setOnClickListener(listener);

        TextView iconView = text(context, 24, Color.WHITE, false);
        iconView.setText(icon);
        row.addView(iconView);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(context, 14), 0, 0, 0);
        TextView title = text(context, 16, Color.WHITE, true);
        title.setText(titleResId);
        texts.addView(title);
        TextView desc = text(context, 13, TEXT_SECONDARY, false);
        desc.setText(descResId);
        texts.addView(desc);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(context, 10), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

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
