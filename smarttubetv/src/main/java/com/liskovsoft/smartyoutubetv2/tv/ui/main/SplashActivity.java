package com.liskovsoft.smartyoutubetv2.tv.ui.main;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

import java.util.Calendar;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SplashPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SplashView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

public class SplashActivity extends MotherActivity implements SplashView {
    private static final String TAG = SplashActivity.class.getSimpleName();

    @Override
    protected boolean showsMobileBackButton() {
        return false; // root screen
    }

    /**
     * Branded launch screen is shown only on a cold start (once per app process).
     */
    private static boolean sShowBrandedSplash = true;
    private static final long SPLASH_DURATION_MS = 2_400;
    private static final String TAGLINE_PREFS = "bluetube_defaults";
    private static final String KEY_TAGLINE_INDEX = "splash_tagline_index";
    private static final int BAR_WIDTH_DP = 280;
    private static final int BAR_FILL_WIDTH_DP = 120;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Animator> mAnimators = new ArrayList<>();
    private final Runnable mStartApp = this::startApp;
    private Intent mNewIntent;
    private SplashPresenter mPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mNewIntent = getIntent();

        mPresenter = SplashPresenter.instance(this);
        mPresenter.setView(this);

        if (sShowBrandedSplash) {
            sShowBrandedSplash = false;
            setContentView(R.layout.activity_splash);
            startAnimations();
            // Let the user see the logo while the app is being initialized
            mHandler.postDelayed(mStartApp, SPLASH_DURATION_MS);
        } else {
            startApp();
        }

        //finish();
    }

    private void startApp() {
        if (mPresenter != null && !isFinishing()) {
            mPresenter.onViewInitialized();
        }
    }

    private void startAnimations() {
        View glow = findViewById(R.id.splash_glow);
        View logo = findViewById(R.id.splash_logo);
        View title = findViewById(R.id.splash_title);
        View tagline = findViewById(R.id.splash_tagline);
        View loading = findViewById(R.id.splash_loading);
        View bar = findViewById(R.id.splash_bar_fill);
        float density = getResources().getDisplayMetrics().density;

        // Logo: fade + scale in
        logo.setAlpha(0f);
        logo.setScaleX(0.8f);
        logo.setScaleY(0.8f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();

        // Title and tagline: staggered fade + slide up
        for (View v : new View[] {title, tagline, loading}) {
            v.setAlpha(0f);
            v.setTranslationY(20 * density);
        }
        title.animate().alpha(1f).translationY(0).setStartDelay(350).setDuration(600).start();
        tagline.animate().alpha(1f).translationY(0).setStartDelay(600).setDuration(400).start();
        typeTagline((TextView) tagline, nextTagline(), 650);
        loading.animate().alpha(1f).translationY(0).setStartDelay(800).setDuration(500).start();

        // Glow: slow "breathing"
        ObjectAnimator pulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.35f, 1f);
        pulse.setDuration(1_300);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.start();
        mAnimators.add(pulse);

        // Loading bar: a light sweeps across the track
        ObjectAnimator sweep = ObjectAnimator.ofFloat(bar, View.TRANSLATION_X,
                -BAR_FILL_WIDTH_DP * density, BAR_WIDTH_DP * density);
        sweep.setDuration(1_100);
        sweep.setRepeatCount(ObjectAnimator.INFINITE);
        sweep.setInterpolator(new LinearInterpolator());
        sweep.start();
        mAnimators.add(sweep);
    }

    /**
     * A different phrase on every launch (in order, the first launch shows the default one).
     * "@@greeting@@" becomes good morning/afternoon/evening depending on the time.
     */
    private String nextTagline() {
        String[] taglines = getResources().getStringArray(R.array.splash_taglines);

        if (taglines.length == 0) {
            return getString(R.string.splash_tagline);
        }

        SharedPreferences prefs = getSharedPreferences(TAGLINE_PREFS, MODE_PRIVATE);
        int index = prefs.getInt(KEY_TAGLINE_INDEX, 0) % taglines.length;
        prefs.edit().putInt(KEY_TAGLINE_INDEX, index + 1).apply();

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int greeting = hour >= 5 && hour < 12 ? R.string.splash_greeting_morning
                : hour >= 12 && hour < 19 ? R.string.splash_greeting_afternoon : R.string.splash_greeting_night;

        return taglines[index].replace("@@greeting@@", getString(greeting));
    }

    /**
     * Typewriter effect: the phrase is written letter by letter.
     */
    private void typeTagline(TextView view, String text, long startDelayMs) {
        // The whole phrase is always there (so the centered text doesn't move), the part not typed yet is transparent
        view.setText(typed(text, 0));
        long perChar = Math.max(18, Math.min(45, 1_300 / Math.max(1, text.length())));

        for (int i = 1; i <= text.length(); i++) {
            final int length = i;
            mHandler.postDelayed(() -> view.setText(typed(text, length)), startDelayMs + perChar * i);
        }
    }

    private static CharSequence typed(String text, int length) {
        SpannableString result = new SpannableString(text);
        if (length < text.length()) {
            result.setSpan(new ForegroundColorSpan(Color.TRANSPARENT), length, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        mNewIntent = intent;

        mPresenter.onViewInitialized();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null); // start + typewriter steps
        for (Animator animator : mAnimators) {
            animator.cancel();
        }
        mAnimators.clear();
        mPresenter.onViewDestroyed();
    }

    @Override
    public Intent getNewIntent() {
        return mNewIntent;
    }

    @Override
    public void finishView() {
        try {
            finish();
        } catch (NullPointerException e) {
            // NullPointerException: Attempt to invoke virtual method 'void com.android.server.wm.DisplayContent.moveStack(com.android.server.wm.TaskStack, boolean)'
            e.printStackTrace();
        }
    }
}
