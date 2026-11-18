package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.together.BlueCast;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * "Send to TV" on the phone: choose (or link with its code) a TV with BlueTube, send the video there, then the
 * phone is the remote: play/pause, -10 s / +10 s, stop, or watch it here again from where the TV is.
 */
public final class MobileCast {
    private static final int ACCENT = 0xFF2F80FF;
    private static final int CARD = 0xFF13224A;
    private static final int TEXT_SECONDARY = 0xFF9FB3D9;
    private static final int GREEN = 0xFF5DCAA5;
    private static final int RED = 0xFFE5484D;
    /** The TV being controlled now (null = not sending) */
    private static BlueCast.Tv sCasting;

    private MobileCast() {
    }

    public interface LocalPlayer {
        long getPositionMs();
        void pause();
        /** Back on the phone, from this second */
        void resumeAt(long positionMs);
    }

    public static boolean isCasting() {
        return sCasting != null;
    }

    /**
     * The "Send to TV" button: the remote if already sending, else the list of TVs.
     */
    public static void show(Activity activity, Video video, LocalPlayer player) {
        if (sCasting != null) {
            showRemote(activity, sCasting, player);
        } else {
            showTvs(activity, video, player);
        }
    }

    private static void showTvs(Activity activity, Video video, LocalPlayer player) {
        BlueCast cast = BlueCast.instance(activity);
        LinearLayout box = box(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(box).create();

        box.addView(text(activity, activity.getString(R.string.cast_title), 20, Color.WHITE, true));
        TextView how = text(activity, activity.getString(R.string.cast_how), 13, TEXT_SECONDARY, false);
        how.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        box.addView(how);

        List<BlueCast.Tv> tvs = cast.getTvs();
        for (BlueCast.Tv tv : tvs) {
            LinearLayout row = row(activity);
            TextView name = text(activity, "📺  " + tv.name, 16, Color.WHITE, true);
            TextView status = text(activity, activity.getString(R.string.cast_checking), 12, TEXT_SECONDARY, false);
            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(name);
            texts.addView(status);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView remove = text(activity, "✕", 18, TEXT_SECONDARY, false);
            remove.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 4), dp(activity, 6));
            remove.setContentDescription(activity.getString(R.string.cast_remove));
            remove.setOnClickListener(v -> {
                cast.unlinkTv(tv.code);
                dialog.dismiss();
                showTvs(activity, video, player);
            });
            row.addView(remove);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                sendTo(activity, tv, video, player);
            });
            box.addView(row);

            cast.checkOnline(tv, checked -> {
                boolean online = Boolean.TRUE.equals(checked.online);
                name.setText("📺  " + checked.name);
                status.setText(online ? activity.getString(R.string.cast_online) : activity.getString(R.string.cast_offline));
                status.setTextColor(online ? GREEN : TEXT_SECONDARY);
            });
        }

        // Link a new TV with the code it shows
        TextView linkTitle = text(activity, activity.getString(R.string.cast_link_tv), 15, Color.WHITE, true);
        linkTitle.setPadding(0, dp(activity, 16), 0, dp(activity, 6));
        box.addView(linkTitle);
        LinearLayout linkRow = new LinearLayout(activity);
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText code = new EditText(activity);
        code.setHint(R.string.cast_code_hint);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextColor(Color.WHITE);
        code.setHintTextColor(TEXT_SECONDARY);
        code.setTextSize(18);
        code.setBackground(pill(activity, CARD));
        code.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10));
        linkRow.addView(code, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView link = text(activity, activity.getString(R.string.cast_link), 15, Color.WHITE, true);
        link.setGravity(Gravity.CENTER);
        link.setBackground(pill(activity, ACCENT));
        link.setPadding(dp(activity, 18), 0, dp(activity, 18), 0);
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 44));
        linkParams.setMargins(dp(activity, 8), 0, 0, 0);
        linkRow.addView(link, linkParams);
        box.addView(linkRow);
        link.setOnClickListener(v -> {
            link.setEnabled(false);
            cast.linkTv(code.getText().toString(), tv -> {
                link.setEnabled(true);
                if (tv == null) {
                    Toast.makeText(activity, R.string.cast_link_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                dialog.dismiss();
                sendTo(activity, tv, video, player);
            });
        });

        showDialog(dialog);
    }

    private static void sendTo(Activity activity, BlueCast.Tv tv, Video video, LocalPlayer player) {
        if (video == null || TextUtils.isEmpty(video.videoId)) {
            return;
        }

        long position = player.getPositionMs();
        BlueCast.instance(activity).sendPlay(tv.code, video.videoId, video.getTitle(), position, ok -> {
            if (!ok) {
                Toast.makeText(activity, R.string.together_connection_error, Toast.LENGTH_LONG).show();
                return;
            }
            sCasting = tv;
            player.pause(); // the TV plays it now
            Toast.makeText(activity, activity.getString(R.string.cast_sent, tv.name), Toast.LENGTH_SHORT).show();
            showRemote(activity, tv, player);
        });
    }

    /**
     * The phone as the TV's remote, with what the TV is playing and where.
     */
    private static void showRemote(Activity activity, BlueCast.Tv tv, LocalPlayer player) {
        BlueCast cast = BlueCast.instance(activity);
        LinearLayout box = box(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(box).create();
        Handler handler = new Handler(Looper.getMainLooper());
        long[] lastPosition = {-1};

        box.addView(text(activity, activity.getString(R.string.cast_casting_on, tv.name), 18, Color.WHITE, true));
        TextView title = text(activity, activity.getString(R.string.cast_waiting), 14, TEXT_SECONDARY, false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(activity, 8), 0, dp(activity, 2));
        box.addView(title);
        TextView time = text(activity, "", 28, Color.WHITE, true);
        time.setGravity(Gravity.CENTER);
        time.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
        box.addView(time);

        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        TextView back = roundButton(activity, "⏪", CARD);
        TextView toggle = roundButton(activity, "⏯", ACCENT);
        TextView forward = roundButton(activity, "⏩", CARD);
        back.setContentDescription("-10 s");
        forward.setContentDescription("+10 s");
        back.setOnClickListener(v -> cast.sendControl(tv.code, "seek", -10_000));
        toggle.setOnClickListener(v -> cast.sendControl(tv.code, "toggle", 0));
        forward.setOnClickListener(v -> cast.sendControl(tv.code, "seek", 10_000));
        controls.addView(back);
        controls.addView(toggle);
        controls.addView(forward);
        box.addView(controls);

        View watchHere = MobileCastButtons.button(activity, R.string.cast_watch_here, true, v -> {
            cast.sendControl(tv.code, "stop", 0);
            sCasting = null;
            dialog.dismiss();
            player.resumeAt(lastPosition[0] >= 0 ? lastPosition[0] : player.getPositionMs());
        });
        box.addView(watchHere);
        View stop = MobileCastButtons.button(activity, R.string.cast_stop, false, v -> {
            cast.sendControl(tv.code, "stop", 0);
            sCasting = null;
            dialog.dismiss();
        });
        box.addView(stop);
        View hide = MobileCastButtons.button(activity, R.string.together_keep_watching, false, v -> dialog.dismiss());
        box.addView(hide);

        // What the TV plays, every 2 s while this is open
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                cast.getState(tv.code, state -> {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    if (state != null && state.optBoolean("open")) {
                        long position = state.optLong("positionMs");
                        long duration = state.optLong("durationMs");
                        lastPosition[0] = position;
                        title.setText(state.optString("title"));
                        time.setText(format(position) + (duration > 0 ? "  /  " + format(duration) : ""));
                        toggle.setText(state.optBoolean("playing") ? "⏸" : "▶");
                    }
                });
                if (dialog.isShowing()) {
                    handler.postDelayed(this, 2_000);
                }
            }
        };

        showDialog(dialog);
        handler.post(poll);
    }

    // ---------------------------------------------------------------------------------------------

    private static void showDialog(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
    }

    private static String format(long ms) {
        long seconds = Math.max(0, ms / 1000);
        long hours = seconds / 3600;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static LinearLayout box(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(context, 22), dp(context, 18), dp(context, 22), dp(context, 16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF0F1C3A);
        background.setCornerRadius(dp(context, 20));
        box.setBackground(background);
        return box;
    }

    private static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 14), dp(context, 12), dp(context, 10), dp(context, 12));
        row.setBackground(pill(context, CARD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(context, 8), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

    private static TextView roundButton(Context context, String label, int color) {
        TextView button = text(context, label, 22, Color.WHITE, false);
        button.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        button.setBackground(circle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 60), dp(context, 60));
        params.setMargins(dp(context, 10), 0, dp(context, 10), 0);
        button.setLayoutParams(params);
        return button;
    }

    static TextView text(Context context, String value, int sizeSp, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        if (bold) {
            text.setTypeface(null, Typeface.BOLD);
        }
        return text;
    }

    static GradientDrawable pill(Context context, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, 16));
        return shape;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Wide buttons of the remote (same look as the "Watch together" window) */
    static final class MobileCastButtons {
        static View button(Context context, int textResId, boolean primary, View.OnClickListener listener) {
            TextView button = text(context, context.getString(textResId), 15, Color.WHITE, true);
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
            params.setMargins(0, dp(context, 10), 0, 0);
            button.setLayoutParams(params);
            return button;
        }
    }
}
