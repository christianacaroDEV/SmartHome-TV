package com.liskovsoft.smartyoutubetv2.tv.ui.widgets;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.misc.DeviceMode;
import com.liskovsoft.smartyoutubetv2.common.misc.together.VoiceCall;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * "Watch together" voice call pill over every screen (also the full screen video): who is in the call, who talks
 * (green ring), and on phones the buttons mic / mute the call / hang up. It can be dragged out of the way.
 * On a TV it only shows (no focus); the call is handled from the "Watch together" menu.
 */
@android.annotation.TargetApi(21) // installed only when VoiceCall.isSupported() (Android 5+)
public final class CallOverlay implements Application.ActivityLifecycleCallbacks, VoiceCall.Listener {
    private static final int GREEN = 0xFF5DCAA5;
    private static final int PILL = 0xE60A1530;
    private static final int RED = 0xFFE5484D;
    private static final int BUTTON = 0xFF13224A;
    private static final int[] AVATAR_COLORS = {0xFF7F77DD, 0xFF1D9E75, 0xFFD85A30, 0xFF378ADD, 0xFFD4537E};
    /** Where the user dragged the pill (kept between screens) */
    private static float sOffsetX;
    private static float sOffsetY;

    private Activity mActivity;
    private LinearLayout mPill;
    private LinearLayout mAvatars;
    private TextView mStatus;
    private ImageButton mMic;
    private ImageButton mSpeaker;

    public static void install(Application application) {
        if (VoiceCall.isSupported()) {
            application.registerActivityLifecycleCallbacks(new CallOverlay());
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        mActivity = activity;
        VoiceCall.instance(activity).addListener(this);
        onCallChanged();
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (activity == mActivity) {
            VoiceCall.instance(activity).removeListener(this);
            removePill();
            mActivity = null;
        }
    }

    @Override
    public void onCallChanged() {
        Activity activity = mActivity;

        if (activity == null) {
            return;
        }

        VoiceCall call = VoiceCall.instance(activity);

        if (!call.isInCall()) {
            removePill();
            return;
        }

        if (mPill == null || mPill.getParent() == null) {
            createPill(activity);
        }

        update(call);
    }

    private void createPill(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);

        if (!(content instanceof FrameLayout)) {
            return;
        }

        boolean tv = !DeviceMode.isMobile(activity);
        mPill = new LinearLayout(activity);
        mPill.setGravity(Gravity.CENTER_VERTICAL);
        mPill.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, tv ? 14 : 6), dp(activity, 6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(PILL);
        background.setCornerRadius(dp(activity, 28));
        mPill.setBackground(background);
        mPill.setFocusable(false);
        mPill.setElevation(dp(activity, 8));

        mAvatars = new LinearLayout(activity);
        mPill.addView(mAvatars);

        mStatus = new TextView(activity);
        mStatus.setTextColor(0xFFDCE6FA);
        mStatus.setTextSize(tv ? 15 : 13);
        mStatus.setSingleLine(true);
        mStatus.setEllipsize(TextUtils.TruncateAt.END);
        mStatus.setMaxWidth(dp(activity, tv ? 260 : 150));
        mStatus.setPadding(dp(activity, 8), 0, dp(activity, 6), 0);
        mPill.addView(mStatus);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        if (tv) {
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.setMargins(0, 0, dp(activity, 28), dp(activity, 28));
        } else {
            mMic = button(activity, R.drawable.ic_m_mic, BUTTON);
            mMic.setOnClickListener(v -> {
                VoiceCall call = VoiceCall.instance(v.getContext());
                call.setMicOn(!call.isMicOn());
            });
            mPill.addView(mMic);
            mSpeaker = button(activity, R.drawable.ic_m_volume, BUTTON);
            mSpeaker.setOnClickListener(v -> {
                VoiceCall call = VoiceCall.instance(v.getContext());
                call.setOthersMuted(!call.isOthersMuted());
            });
            mPill.addView(mSpeaker);
            ImageButton hangUp = button(activity, R.drawable.ic_m_call_end, RED);
            hangUp.setContentDescription(activity.getString(R.string.together_call_leave));
            hangUp.setOnClickListener(v -> VoiceCall.instance(v.getContext()).leave());
            mPill.addView(hangUp);

            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(activity, 40);
            enableDrag(mPill);
        }

        mPill.setTranslationX(sOffsetX);
        mPill.setTranslationY(sOffsetY);
        content.addView(mPill, params);
    }

    private void update(VoiceCall call) {
        Context context = mPill.getContext();
        mAvatars.removeAllViews();
        List<VoiceCall.Member> members = call.getMembers();
        int shown = 0;

        for (VoiceCall.Member member : members) {
            if (++shown > 4) {
                break;
            }
            TextView avatar = new TextView(context);
            String name = member.me ? context.getString(R.string.together_you) : member.name;
            avatar.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
            avatar.setTextColor(Color.WHITE);
            avatar.setTypeface(null, Typeface.BOLD);
            avatar.setTextSize(13);
            avatar.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(AVATAR_COLORS[Math.abs(member.id.hashCode()) % AVATAR_COLORS.length]);
            boolean talking = member.me ? false : member.speaking;
            circle.setStroke(dp(context, 3), talking ? GREEN : (member.mic || member.me ? 0x00000000 : 0xFF6B7AA0));
            avatar.setBackground(circle);
            avatar.setAlpha(member.me || "connected".equals(member.state) || "completed".equals(member.state) ? 1f : 0.55f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 34), dp(context, 34));
            params.setMargins(0, 0, dp(context, 4), 0);
            mAvatars.addView(avatar, params);
        }

        String speaking = call.getSpeakingName();
        String others = call.getOtherNames();
        mStatus.setText(speaking != null ? context.getString(R.string.together_call_speaking, speaking)
                : others.isEmpty() ? context.getString(R.string.together_waiting)
                : context.getString(R.string.together_call_in_call));

        if (mMic != null) {
            mMic.setVisibility(call.hasMic() ? View.VISIBLE : View.GONE);
            mMic.setImageResource(call.isMicOn() ? R.drawable.ic_m_mic : R.drawable.ic_m_mic_off);
            mMic.setContentDescription(context.getString(call.isMicOn() ? R.string.together_call_mute_mic : R.string.together_call_unmute_mic));
            ((GradientDrawable) mMic.getBackground()).setColor(call.isMicOn() ? BUTTON : RED);
        }

        if (mSpeaker != null) {
            mSpeaker.setImageResource(call.isOthersMuted() ? R.drawable.ic_m_volume_off : R.drawable.ic_m_volume);
            mSpeaker.setContentDescription(context.getString(call.isOthersMuted()
                    ? R.string.together_call_unmute_others : R.string.together_call_mute_others));
            ((GradientDrawable) mSpeaker.getBackground()).setColor(call.isOthersMuted() ? RED : BUTTON);
        }
    }

    private void removePill() {
        if (mPill != null && mPill.getParent() instanceof ViewGroup) {
            ((ViewGroup) mPill.getParent()).removeView(mPill);
        }
        mPill = null;
        mMic = null;
        mSpeaker = null;
    }

    /** Drag the pill anywhere (the buttons still take taps) */
    private static void enableDrag(View pill) {
        float[] start = new float[4];
        boolean[] dragging = new boolean[1];
        int slop = android.view.ViewConfiguration.get(pill.getContext()).getScaledTouchSlop();

        pill.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = event.getRawX();
                    start[1] = event.getRawY();
                    start[2] = v.getTranslationX();
                    start[3] = v.getTranslationY();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - start[0];
                    float dy = event.getRawY() - start[1];
                    if (dragging[0] || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        dragging[0] = true;
                        v.setTranslationX(start[2] + dx);
                        v.setTranslationY(start[3] + dy);
                        sOffsetX = v.getTranslationX();
                        sOffsetY = v.getTranslationY();
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private static ImageButton button(Context context, int iconResId, int color) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(iconResId);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        button.setBackground(circle);
        int padding = dp(context, 8);
        button.setPadding(padding, padding, padding, padding);
        button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 38), dp(context, 38));
        params.setMargins(dp(context, 4), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
