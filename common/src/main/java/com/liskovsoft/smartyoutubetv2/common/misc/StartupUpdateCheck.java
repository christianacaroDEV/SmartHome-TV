package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * BlueTube: as soon as the app opens (TV and phone) it reads the small update manifest and, if there is a newer
 * version, asks right away "Update now / Later". The APK (~30 MB) is only downloaded after "Update now".
 * Once per app start; "Later" asks again on the next start.
 */
public class StartupUpdateCheck {
    private static final long FOCUS_RETRY_MS = 1_500;
    private static final long MAX_WAIT_MS = 90_000;
    private static boolean sChecked;

    public static void checkOnce(Activity activity) {
        if (sChecked || activity == null) {
            return;
        }

        String[] urls = activity.getResources().getStringArray(R.array.update_urls);
        if (urls.length == 0) {
            return; // flavor without updates (F-Droid)
        }

        sChecked = true;
        int currentCode = getVersionCode(activity);
        WeakReference<Activity> ref = new WeakReference<>(activity);
        Handler main = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            Update update = null;
            for (String url : urls) {
                update = fetch(url);
                if (update != null) {
                    break;
                }
            }

            if (update == null) {
                sChecked = false; // no internet yet (e.g. TV just turned on): try again next time the screen opens
            }

            if (update != null && update.versionCode > currentCode) {
                Update found = update;
                main.post(() -> showWhenReady(ref, found, main, System.currentTimeMillis()));
            }
        }, "BlueTubeUpdateCheck").start();
    }

    /**
     * Waits until the main screen is really in front (not behind the profile picker or the splash).
     */
    private static void showWhenReady(WeakReference<Activity> ref, Update update, Handler main, long startMs) {
        Activity activity = ref.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }

        if (!activity.hasWindowFocus()) {
            if (System.currentTimeMillis() - startMs < MAX_WAIT_MS) {
                main.postDelayed(() -> showWhenReady(ref, update, main, startMs), FOCUS_RETRY_MS);
            }
            return;
        }

        showCard(activity, update);
    }

    private static final int CARD = 0xFF13204A;
    private static final int TEXT_SECONDARY = 0xFF9FB4E0;
    private static final int BLUE = 0xFF2F7BFF;

    /**
     * BlueTube card: app icon, version, "What's new" with ticks, and big buttons.
     * Phone: anchored at the bottom, full width. TV: centered, buttons side by side with a visible focus ring.
     */
    private static void showCard(Activity activity, Update update) {
        boolean tv = !DeviceMode.isMobile(activity);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 22), dp(activity, 22), dp(activity, 22), dp(activity, tv ? 22 : 12));
        card.setBackground(shape(activity, CARD, 24, 0, 0));

        // Header: icon + "A new update is available" (no version, no list of changes: just that there is one)
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(activity.getApplicationInfo().icon);
        header.addView(icon, new LinearLayout.LayoutParams(dp(activity, tv ? 60 : 48), dp(activity, tv ? 60 : 48)));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(activity, 14), 0, 0, 0);
        titles.addView(text(activity, activity.getString(R.string.bluetube_update_title), tv ? 22 : 19, Color.WHITE, true));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.setPadding(0, 0, 0, dp(activity, 20));
        card.addView(header);

        // Buttons
        TextView now = button(activity, R.string.bluetube_update_now, true, tv);
        TextView later = button(activity, R.string.bluetube_update_later, false, tv);
        now.setOnClickListener(v -> {
            dialog.dismiss();
            // If the install is cancelled (e.g. the first time Android asks for the "unknown apps" permission),
            // ask again when the user is back in the app
            sChecked = false;
            AppUpdatePresenter.instance(activity).downloadAndInstall();
        });
        later.setOnClickListener(v -> dialog.dismiss());

        if (tv) {
            LinearLayout buttons = new LinearLayout(activity);
            buttons.addView(now);
            LinearLayout.LayoutParams laterParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            laterParams.leftMargin = dp(activity, 14);
            buttons.addView(later, laterParams);
            card.addView(buttons);
        } else {
            card.addView(now, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(later, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            WindowManager.LayoutParams params = window.getAttributes();
            if (tv) {
                params.width = Math.min(dp(activity, 640), Math.round(width * 0.6f));
                params.gravity = Gravity.CENTER;
            } else {
                params.width = Math.min(width - dp(activity, 20), dp(activity, 480));
                params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                // above the navigation bar (the dialog window may be drawn behind it)
                int navId = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
                params.y = dp(activity, 12) + (navId > 0 ? activity.getResources().getDimensionPixelSize(navId) : 0);
            }
            window.setAttributes(params);
        }
        dialog.show();
        now.requestFocus(); // TV remote: OK = update
    }

    private static TextView text(Activity activity, String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static TextView button(Activity activity, int labelResId, boolean primary, boolean tv) {
        TextView button = text(activity, activity.getString(labelResId), tv ? 17 : 16, primary ? Color.WHITE : TEXT_SECONDARY, primary);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(activity, 26), dp(activity, 12), dp(activity, 26), dp(activity, 12));
        button.setFocusable(true);
        button.setClickable(true);
        // Focus ring (TV remote) and pressed state
        StateListDrawable states = new StateListDrawable();
        int base = primary ? BLUE : Color.TRANSPARENT;
        states.addState(new int[] {android.R.attr.state_focused}, shape(activity, primary ? BLUE : 0x33FFFFFF, 26, 3, Color.WHITE));
        states.addState(new int[] {android.R.attr.state_pressed}, shape(activity, primary ? 0xFF2566D6 : 0x22FFFFFF, 26, 0, 0));
        states.addState(new int[] {}, shape(activity, base, 26, 0, 0));
        button.setBackground(states);
        return button;
    }

    private static GradientDrawable shape(Activity activity, int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(activity, radiusDp));
        if (strokeDp > 0) {
            shape.setStroke(dp(activity, strokeDp), strokeColor);
        }
        return shape;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int getVersionCode(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE; // unknown: never nag
        }
    }

    /**
     * Manifest: {"package": {...}, "32.72": {"versionCode": 2474, "changelog": ["...", ...]}}
     */
    private static Update fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() != 200) {
                return null;
            }

            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();

            JSONObject json = new JSONObject(out.toString("UTF-8"));
            Update best = null;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject version = json.optJSONObject(key);
                if (version == null || !version.has("versionCode")) {
                    continue;
                }

                int code = version.optInt("versionCode");
                if (best == null || code > best.versionCode) {
                    best = new Update();
                    best.versionName = key;
                    best.versionCode = code;
                    JSONArray changelog = version.optJSONArray("changelog");
                    for (int i = 0; changelog != null && i < changelog.length(); i++) {
                        best.changes.add(changelog.optString(i));
                    }
                }
            }

            return best;
        } catch (Exception e) {
            return null; // no internet etc.: silently try on the next start
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static class Update {
        String versionName;
        int versionCode;
        final List<String> changes = new ArrayList<>();
    }
}
