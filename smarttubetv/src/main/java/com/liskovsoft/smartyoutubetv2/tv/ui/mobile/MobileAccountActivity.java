package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AboutSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MobileSettings;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;

/**
 * "Me" page of the mobile mode (like the YouTube 'You' page): profile card, account switching,
 * sign out, settings and the background audio switch.
 */
public class MobileAccountActivity extends MotherActivity {
    private LinearLayout mContent;
    private TextView mTitle;
    private ImageButton mCloseButton;
    private boolean mAccountsExpanded;
    private boolean mShowingSettings;
    private List<Account> mAccounts;

    @Override
    protected boolean showsMobileBackButton() {
        return false; // it has its own close/back button
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bluetube_background);

        // Top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        mCloseButton = new ImageButton(this);
        mCloseButton.setBackground(null);
        mCloseButton.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        mCloseButton.setPadding(dp(14), dp(14), dp(14), dp(14));
        mCloseButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mCloseButton.setOnClickListener(v -> onBackPressed());
        bar.addView(mCloseButton);

        mTitle = new TextView(this);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(20);
        mTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(mTitle);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mContent, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        setContentView(root);
        showMain();

        MediaServiceManager.instance().loadAccounts(accounts -> runOnUiThread(() -> {
            mAccounts = accounts;
            if (!mShowingSettings) {
                showMain();
            }
        }));
    }

    @Override
    public void onBackPressed() {
        if (mShowingSettings) {
            mShowingSettings = false;
            showMain();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------------------------------------

    private void showMain() {
        mShowingSettings = false;
        mTitle.setText(R.string.mobile_me);
        mCloseButton.setImageResource(R.drawable.ic_nav_close);
        mContent.removeAllViews();

        Account current = MediaServiceManager.instance().getSelectedAccount();

        mContent.addView(createProfileCard(current));

        if (mAccountsExpanded) {
            mContent.addView(createAccountsPanel());
        }

        mContent.addView(createDivider());
        mContent.addView(createRow(R.drawable.ic_m_settings, getString(R.string.header_settings), null, v -> showSettings()));
        mContent.addView(createSwitchRow(R.drawable.ic_m_headphones, getString(R.string.mobile_background_audio),
                getString(R.string.mobile_background_audio_desc), MobileSettings.isBackgroundAudioEnabled(this),
                (button, checked) -> MobileSettings.setBackgroundAudioEnabled(this, checked)));
        mContent.addView(createRow(R.drawable.ic_m_headphones, getString(R.string.mobile_audio_only),
                getResources().getStringArray(R.array.mobile_audio_only_modes)[MobileSettings.getAudioOnlyMode(this)],
                v -> new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.mobile_audio_only)
                        .setSingleChoiceItems(R.array.mobile_audio_only_modes, MobileSettings.getAudioOnlyMode(this), (dialog, which) -> {
                            MobileSettings.setAudioOnlyMode(this, which);
                            dialog.dismiss();
                            recreate();
                        })
                        .show()));
        mContent.addView(createRow(R.drawable.ic_m_info, getString(R.string.settings_about), null,
                v -> AboutSettingsPresenter.instance(this).show()));
        mContent.addView(createRow(R.drawable.ic_m_update, getString(R.string.check_for_updates), null,
                v -> MobileSettings.checkForUpdates(this)));
    }

    private void showSettings() {
        mShowingSettings = true;
        mTitle.setText(R.string.header_settings);
        mCloseButton.setImageResource(R.drawable.ic_nav_back);
        mContent.removeAllViews();

        List<SettingsItem> items = AppDataSourceManager.instance().getSettingItems(this);

        for (SettingsItem item : items) {
            Integer icon = item.imageResId > 0 ? item.imageResId : null;
            mContent.addView(createRow(icon != null ? icon : 0, item.title, null, v -> onSettingsItemClicked(item)));
        }
    }

    private void onSettingsItemClicked(SettingsItem item) {
        String password = GeneralData.instance(this).getSettingsPassword();

        if (password == null) {
            item.onClick.run();
            return;
        }

        SimpleEditDialog.OnChange onChange = newValue -> {
            if (Utils.passwordMatch(password, newValue)) {
                item.onClick.run();
                return true;
            }
            return false;
        };

        if (password.matches("\\d+")) {
            SimpleEditDialog.showPin(this, getString(R.string.enter_settings_password), null, onChange);
        } else {
            SimpleEditDialog.showPassword(this, getString(R.string.enter_settings_password), null, onChange);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Profile and accounts
    // ---------------------------------------------------------------------------------------------

    private View createProfileCard(Account account) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(12), dp(12));

        ImageView avatar = new ImageView(this);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        loadAvatar(avatar, account != null ? account.getAvatarImageUrl() : null);
        card.addView(avatar);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.setMargins(dp(14), 0, dp(8), 0);
        texts.setLayoutParams(textsParams);

        TextView name = new TextView(this);
        name.setTextColor(Color.WHITE);
        name.setTextSize(18);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(account != null ? account.getName() : getString(R.string.dialog_account_none));
        texts.addView(name);

        if (account != null && !TextUtils.isEmpty(account.getEmail())) {
            TextView email = new TextView(this);
            email.setTextColor(Color.parseColor("#9FB3D9"));
            email.setTextSize(13);
            email.setMaxLines(1);
            email.setEllipsize(TextUtils.TruncateAt.END);
            email.setText(account.getEmail());
            texts.addView(email);
        }

        card.addView(texts);

        ImageView chevron = new ImageView(this);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        chevron.setImageResource(R.drawable.ic_m_expand_more);
        chevron.setRotation(mAccountsExpanded ? 180 : 0);
        card.addView(chevron);

        card.setOnClickListener(v -> {
            mAccountsExpanded = !mAccountsExpanded;
            showMain();
        });

        return card;
    }

    private View createAccountsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0x14FFFFFF);

        if (mAccounts != null) {
            for (Account account : mAccounts) {
                LinearLayout row = createRowContainer();
                ImageView avatar = new ImageView(this);
                avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
                loadAvatar(avatar, account.getAvatarImageUrl());
                row.addView(avatar);
                row.addView(createRowText(account.getName() + (TextUtils.isEmpty(account.getEmail()) ? "" : "\n" + account.getEmail()), account.isSelected()));

                if (account.isSelected()) {
                    TextView check = new TextView(this);
                    check.setText("✓");
                    check.setTextColor(Color.parseColor("#38E1FF"));
                    check.setTextSize(20);
                    row.addView(check);
                }

                row.setOnClickListener(v -> {
                    AccountSelectionPresenter.instance(this).selectAccount(account);
                    finish();
                });
                panel.addView(row);
            }
        }

        panel.addView(createRow(R.drawable.ic_m_add, getString(R.string.dialog_add_account), null,
                v -> YTSignInPresenter.instance(this).start()));

        Account selected = MediaServiceManager.instance().getSelectedAccount();

        if (selected != null) {
            panel.addView(createRow(R.drawable.ic_m_logout, getString(R.string.mobile_sign_out), null,
                    v -> AppDialogUtil.showConfirmationDialog(this, getString(R.string.mobile_sign_out) + "?", () -> {
                        YouTubeServiceManager.instance().getSignInService().removeAccount(selected);
                        finish();
                    })));
        }

        return panel;
    }

    private void loadAvatar(ImageView view, String url) {
        if (TextUtils.isEmpty(url) || isDestroyed()) {
            view.setImageResource(R.drawable.ic_m_account);
            return;
        }

        Glide.with(this).load(url).apply(ViewUtil.glideOptions()).circleCrop().into(view);
    }

    // ---------------------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------------------

    private LinearLayout createRowContainer() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(20), dp(8), dp(16), dp(8));
        return row;
    }

    private TextView createRowText(String text, boolean bold) {
        TextView view = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(18), 0, dp(8), 0);
        view.setLayoutParams(params);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        view.setText(text);
        return view;
    }

    private View createRow(int iconResId, String title, String subtitle, View.OnClickListener listener) {
        LinearLayout row = createRowContainer();

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        if (iconResId > 0) {
            icon.setImageResource(iconResId);
            icon.setColorFilter(Color.parseColor("#B8C7E6"));
        }
        row.addView(icon);
        if (subtitle == null) {
            row.addView(createRowText(title, false));
        } else {
            // title + the current choice under it
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textsParams.setMargins(dp(18), 0, dp(8), 0);
            texts.setLayoutParams(textsParams);
            TextView titleView = new TextView(this);
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(16);
            titleView.setText(title);
            texts.addView(titleView);
            TextView subtitleView = new TextView(this);
            subtitleView.setTextColor(Color.parseColor("#9FB3D9"));
            subtitleView.setTextSize(13);
            subtitleView.setText(subtitle);
            texts.addView(subtitleView);
            row.addView(texts);
        }
        row.setOnClickListener(listener);

        return row;
    }

    private View createSwitchRow(int iconResId, String title, String subtitle, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = createRowContainer();

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        icon.setImageResource(iconResId);
        icon.setColorFilter(Color.parseColor("#B8C7E6"));
        row.addView(icon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.setMargins(dp(18), 0, dp(8), 0);
        texts.setLayoutParams(textsParams);

        TextView titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setText(title);
        texts.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setTextColor(Color.parseColor("#9FB3D9"));
        subtitleView.setTextSize(12);
        subtitleView.setText(subtitle);
        texts.addView(subtitleView);
        row.addView(texts);

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle);

        row.setOnClickListener(v -> toggle.toggle());

        return row;
    }

    private View createDivider() {
        View divider = new View(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(0, dp(8), 0, dp(8));
        divider.setLayoutParams(params);
        divider.setBackgroundColor(0x1FFFFFFF);
        return divider;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
