package com.liskovsoft.smartyoutubetv2.tv.ui.widgets;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.misc.DeviceMode;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * BlueTube "no internet" screen (phone and TV): icon, "You're offline", a hint and a big "Retry".
 * Covers the screen while there is no connection and goes away by itself (reloading the content) when it comes back.
 */
public class OfflineOverlay {
    private static final int BACKGROUND = 0xFF0A1226;
    private static final int CIRCLE = 0xFF13204A;
    private static final int TEXT_SECONDARY = 0xFF9FB4E0;
    private static final int BLUE = 0xFF2F7BFF;

    private final Activity mActivity;
    private final Runnable mOnBackOnline;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private FrameLayout mView;
    private TextView mRetry;
    private boolean mRegistered;
    private boolean mShown;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Give the new network a moment (DNS) before reloading
            mHandler.removeCallbacks(mCheck);
            mHandler.postDelayed(mCheck, 1_500);
        }
    };
    private final Runnable mCheck = this::check;
    private Gate mGate;

    /** E.g. a video is playing from the buffer: don't cover it */
    public interface Gate {
        boolean canShow();
    }

    public void setGate(Gate gate) {
        mGate = gate;
    }

    public OfflineOverlay(Activity activity, Runnable onBackOnline) {
        mActivity = activity;
        mOnBackOnline = onBackOnline;
    }

    /** Call from onResume */
    @SuppressWarnings("deprecation")
    public void start() {
        if (!mRegistered) {
            mActivity.registerReceiver(mReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            mRegistered = true;
        }
        check();
    }

    /** Call from onPause */
    public void stop() {
        mHandler.removeCallbacksAndMessages(null);
        if (mRegistered) {
            try {
                mActivity.unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException e) {
                // not registered
            }
            mRegistered = false;
        }
    }

    public boolean isShown() {
        return mShown;
    }

    private void check() {
        boolean online = isOnline(mActivity);

        if (!online && !mShown && (mGate == null || mGate.canShow())) {
            show();
        } else if (online && mShown) {
            hide();
            if (mOnBackOnline != null) {
                mOnBackOnline.run();
            }
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean isOnline(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
            return info != null && info.isConnected();
        } catch (RuntimeException e) {
            return true; // can't tell: don't block the app
        }
    }

    private void show() {
        if (mView == null) {
            mView = build();
        }

        ViewGroup root = mActivity.findViewById(android.R.id.content);
        if (mView.getParent() == null && root != null) {
            root.addView(mView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        mView.bringToFront();
        mView.setAlpha(0f);
        mView.animate().alpha(1f).setDuration(250).start();
        mShown = true;
        mRetry.requestFocus(); // TV remote: OK = retry
    }

    private void hide() {
        mShown = false;
        if (mView != null && mView.getParent() != null) {
            ((ViewGroup) mView.getParent()).removeView(mView);
        }
    }

    private FrameLayout build() {
        boolean tv = !DeviceMode.isMobile(mActivity);
        FrameLayout screen = new FrameLayout(mActivity);
        screen.setBackgroundColor(BACKGROUND);
        screen.setClickable(true); // nothing behind can be touched
        screen.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            screen.setElevation(dp(30));
        }

        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(tv ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), 0, dp(32), 0);

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_m_wifi_off);
        icon.setColorFilter(0xFF8FD8FF);
        int circle = dp(tv ? 110 : 88);
        int pad = circle / 4;
        icon.setPadding(pad, pad, pad, pad);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(CIRCLE);
        icon.setBackground(round);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(circle, circle);
        if (tv) {
            iconParams.rightMargin = dp(32);
        } else {
            iconParams.bottomMargin = dp(20);
        }
        box.addView(icon, iconParams);

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(tv ? Gravity.START : Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(mActivity);
        title.setText(R.string.offline_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(tv ? 26 : 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(tv ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        texts.addView(title, new LinearLayout.LayoutParams(tv ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(mActivity);
        message.setText(tv ? R.string.offline_message_tv : R.string.offline_message_mobile);
        message.setTextColor(TEXT_SECONDARY);
        message.setTextSize(tv ? 17 : 15);
        message.setGravity(tv ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        message.setLineSpacing(0, 1.2f);
        message.setMaxWidth(dp(tv ? 460 : 300));
        message.setPadding(0, dp(8), 0, dp(22));
        texts.addView(message, new LinearLayout.LayoutParams(dp(tv ? 460 : 300), ViewGroup.LayoutParams.WRAP_CONTENT));

        mRetry = new TextView(mActivity);
        mRetry.setText(R.string.offline_retry);
        mRetry.setTextColor(Color.WHITE);
        mRetry.setTextSize(tv ? 18 : 16);
        mRetry.setTypeface(Typeface.DEFAULT_BOLD);
        mRetry.setGravity(Gravity.CENTER);
        mRetry.setPadding(dp(34), dp(13), dp(34), dp(13));
        mRetry.setFocusable(true);
        mRetry.setClickable(true);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, pill(BLUE, true));
        states.addState(new int[] {android.R.attr.state_pressed}, pill(0xFF2566D6, false));
        states.addState(new int[] {}, pill(BLUE, false));
        mRetry.setBackground(states);
        mRetry.setOnClickListener(v -> {
            mRetry.setAlpha(0.6f);
            mRetry.setEnabled(false);
            mHandler.postDelayed(() -> {
                mRetry.setAlpha(1f);
                mRetry.setEnabled(true);
                check();
                if (mShown) { // still nothing: a little shake
                    mRetry.animate().translationX(dp(8)).setDuration(60).withEndAction(() ->
                            mRetry.animate().translationX(-dp(8)).setDuration(60).withEndAction(() ->
                                    mRetry.animate().translationX(0).setDuration(60).start()).start()).start();
                }
            }, 700);
        });
        texts.addView(mRetry, new LinearLayout.LayoutParams(tv ? ViewGroup.LayoutParams.WRAP_CONTENT : dp(240), ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(texts);
        screen.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        return screen;
    }

    private GradientDrawable pill(int color, boolean focusRing) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(28));
        if (focusRing) {
            shape.setStroke(dp(3), Color.WHITE);
        }
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
