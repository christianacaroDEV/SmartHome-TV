package com.liskovsoft.smartyoutubetv2.tv.ui.widgets;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small clock (24 h) in the top right corner of the full screen video, plus the battery level on devices
 * with a battery (phones/tablets). Doesn't take touches or focus.
 */
public final class ClockOverlay {
    private static final long UPDATE_MS = 15_000;
    private final Activity mActivity;
    private final boolean mShowBattery;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat mFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private TextView mView;
    private final Runnable mUpdate = new Runnable() {
        @Override
        public void run() {
            update();
            mHandler.postDelayed(this, UPDATE_MS);
        }
    };

    public ClockOverlay(Activity activity, boolean showBattery) {
        mActivity = activity;
        mShowBattery = showBattery;
    }

    public void show() {
        if (mView == null) {
            ViewGroup root = mActivity.findViewById(android.R.id.content);

            if (root == null) {
                return;
            }

            float density = mActivity.getResources().getDisplayMetrics().density;
            mView = new TextView(mActivity);
            mView.setTextColor(0xE6FFFFFF);
            mView.setTextSize(15);
            mView.setShadowLayer(4 * density, 0, 0, Color.BLACK);
            mView.setPadding(Math.round(10 * density), Math.round(4 * density), Math.round(10 * density), Math.round(4 * density));
            mView.setBackgroundColor(0x40000000);
            mView.setFocusable(false);
            mView.setClickable(false);
            mView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            int margin = Math.round(14 * density);
            params.setMargins(margin, margin, margin, margin);
            root.addView(mView, params);
        }

        // Above everything (the phone watch page lives in the same window, with its own elevation)
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            mView.setTranslationZ(1000 * mActivity.getResources().getDisplayMetrics().density);
        }
        mView.bringToFront();
        mView.setVisibility(View.VISIBLE);
        mView.post(() -> {
            if (mView != null) {
                mView.bringToFront();
            }
        });
        mHandler.removeCallbacks(mUpdate);
        mUpdate.run();
    }

    public void hide() {
        mHandler.removeCallbacks(mUpdate);

        if (mView != null) {
            mView.setVisibility(View.GONE);
        }
    }

    /**
     * "🔋 89%" / "⚡ 100%", or null on devices without a battery (TVs).
     */
    public static String batteryText(android.content.Context context) {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        if (battery == null || !battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
            return null;
        }

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;

        return level >= 0 && scale > 0 ? (charging ? "⚡ " : "🔋 ") + Math.round(level * 100f / scale) + "%" : null;
    }

    private void update() {
        if (mView == null) {
            return;
        }

        String text = mFormat.format(new Date());

        if (mShowBattery) {
            Intent battery = mActivity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

            if (battery != null && battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;

                if (level >= 0 && scale > 0) {
                    text += "   " + (charging ? "⚡" : "🔋") + " " + Math.round(level * 100f / scale) + "%";
                }
            }
        }

        mView.setText(text);
    }
}
