package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/**
 * "Add account" for phones and tablets: the steps, a big activation code, a button that opens the activation
 * page on this same phone (code already filled in) and a QR code for another device. It closes by itself once
 * the account is confirmed (same {@link SignInPresenter} as the TV screen).
 */
public class MobileSignInActivity extends MotherActivity implements SignInView {
    private static final int ACCENT = 0xFF2F80FF;
    private static final int CARD_COLOR = 0xFF16264A;
    private static final int TEXT_SECONDARY = 0xFF9FB3D9;

    private SignInPresenter mPresenter;
    private TextView mCode;
    private TextView mOpenButton;
    private TextView mCopyButton;
    private ImageView mQrCode;
    private TextView mStatus;
    private ProgressBar mStatusProgress;
    private String mUserCode;
    private String mFullSignInUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(createContent());

        mPresenter = SignInPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
    }

    @Override
    public void showCode(String userCode, String signInUrl) {
        showCode(userCode, signInUrl, null);
    }

    @Override
    public void showCode(String userCode, String signInUrl, String fullSignInUrl) {
        if (isFinishing() || TextUtils.isEmpty(userCode)) {
            return;
        }

        if (TextUtils.isEmpty(signInUrl)) {
            // An error message instead of a code
            mCode.setText(null);
            mStatusProgress.setVisibility(View.GONE);
            mStatus.setText(userCode);
            return;
        }

        mUserCode = userCode;
        mFullSignInUrl = fullSignInUrl != null ? fullSignInUrl : signInUrl;

        mCode.setText(userCode);
        mOpenButton.setEnabled(true);
        mOpenButton.setAlpha(1f);
        mCopyButton.setVisibility(View.VISIBLE);
        mStatus.setText(R.string.mobile_signin_waiting);

        if (!isDestroyed()) {
            Glide.with(this)
                    .load(Utils.toQrCodeLink(mFullSignInUrl))
                    .apply(ViewUtil.glideOptions())
                    .into(mQrCode);
        }
    }

    @Override
    public void close() {
        finish();
    }

    // ---------------------------------------------------------------------------------------------

    private View createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.bluetube_background);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(64), dp(24), dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Content column: never wider than 480dp (tablets, landscape)
        LinearLayout column = new LinearLayout(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = dp(480);
                if (MeasureSpec.getSize(widthMeasureSpec) > max) {
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        page.addView(column, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.splash_logo);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(logo, logoParams);

        TextView title = text(getString(R.string.mobile_signin_title), 24, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(16), 0, dp(6));
        column.addView(title);

        TextView subtitle = text(getString(R.string.mobile_signin_subtitle), 14, TEXT_SECONDARY, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(24));
        column.addView(subtitle);

        // Steps
        column.addView(step(1, getString(R.string.mobile_signin_step1)));
        column.addView(step(2, getString(R.string.mobile_signin_step2)));
        column.addView(step(3, getString(R.string.mobile_signin_step3)));

        // Code card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(12));
        card.setBackground(rounded(CARD_COLOR, 16));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(20), 0, 0);
        column.addView(card, cardParams);

        card.addView(text(getString(R.string.mobile_signin_code_label), 13, TEXT_SECONDARY, false));
        mCode = text(getString(R.string.mobile_signin_loading), 30, Color.WHITE, true);
        mCode.setGravity(Gravity.CENTER);
        mCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            mCode.setLetterSpacing(0.08f);
        }
        mCode.setTextIsSelectable(true);
        mCode.setPadding(0, dp(6), 0, dp(6));
        card.addView(mCode);
        mCopyButton = text(getString(R.string.mobile_signin_copy), 14, ACCENT, true);
        mCopyButton.setPadding(dp(16), dp(8), dp(16), dp(8));
        mCopyButton.setVisibility(View.GONE);
        mCopyButton.setOnClickListener(v -> copyCode());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(mCopyButton, copyParams);

        // Main action: open the activation page on this phone, the code is already in the link
        mOpenButton = text(getString(R.string.mobile_signin_open), 16, Color.WHITE, true);
        mOpenButton.setGravity(Gravity.CENTER);
        mOpenButton.setBackground(rounded(ACCENT, 26));
        mOpenButton.setEnabled(false);
        mOpenButton.setAlpha(0.5f);
        mOpenButton.setOnClickListener(v -> {
            if (mFullSignInUrl != null) {
                Utils.openLinkExt(this, mFullSignInUrl);
            }
        });
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        openParams.setMargins(0, dp(20), 0, 0);
        column.addView(mOpenButton, openParams);

        // Status
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, 0);
        mStatusProgress = new ProgressBar(this);
        status.addView(mStatusProgress, new LinearLayout.LayoutParams(dp(18), dp(18)));
        mStatus = text(getString(R.string.mobile_signin_loading), 14, TEXT_SECONDARY, false);
        mStatus.setPadding(dp(10), 0, 0, 0);
        status.addView(mStatus);
        column.addView(status);

        // QR for another device
        TextView qrLabel = text(getString(R.string.mobile_signin_qr), 13, TEXT_SECONDARY, false);
        qrLabel.setGravity(Gravity.CENTER);
        qrLabel.setPadding(0, dp(28), 0, dp(10));
        column.addView(qrLabel);
        mQrCode = new ImageView(this);
        mQrCode.setImageResource(R.drawable.activate_account_qrcode);
        mQrCode.setBackground(rounded(Color.WHITE, 12));
        mQrCode.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(170), dp(170));
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(mQrCode, qrParams);

        return scroll;
    }

    private void copyCode() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        if (clipboard != null && mUserCode != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.mobile_signin_code_label), mUserCode));
            MessageHelpers.showMessage(this, R.string.mobile_signin_copied);
        }
    }

    private View step(int number, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView circle = text(String.valueOf(number), 14, Color.WHITE, true);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(ACCENT);
        circle.setBackground(shape);
        row.addView(circle, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView text = text(label, 15, Color.WHITE, false);
        text.setPadding(dp(14), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        if (bold) {
            text.setTypeface(null, Typeface.BOLD);
        }
        return text;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
