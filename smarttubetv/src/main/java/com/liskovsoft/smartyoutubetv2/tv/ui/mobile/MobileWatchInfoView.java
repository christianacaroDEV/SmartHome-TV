package com.liskovsoft.smartyoutubetv2.tv.ui.mobile;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Info block under the video of the mobile watch page, like YouTube:<br/>
 * title, views · date and "…more" | channel row (avatar, name, subscribers, Subscribe) |
 * actions (like/dislike, share, save) | expandable description.
 */
@SuppressLint("ViewConstructor")
public class MobileWatchInfoView extends LinearLayout {
    private static final int ACCENT = 0xFF2F80FF;
    private static final int TEXT_SECONDARY = 0xFF9FB3D9;
    private static final int CHIP_COLOR = 0x26FFFFFF;
    private static final int CARD_COLOR = 0x1AFFFFFF;
    private static final int BUTTON_ON = 1;

    private final MobilePlayerView mPlayer;
    private final TextView mTitle;
    private final TextView mMeta;
    private final ImageView mAvatar;
    private final TextView mChannelName;
    private final TextView mSubscribers;
    private final TextView mSubscribe;
    private final TextView mLike;
    private final ImageView mDislike;
    private final TextView mWatchLater;
    private final LinearLayout mDescriptionCard;
    private final TextView mDescription;
    private final TextView mDescriptionToggle;
    private Video mVideo;
    private boolean mExpanded;
    private String mLoadedAvatar;
    private String mLikeVideoKey;
    private boolean mLikedAtLoad;

    public MobileWatchInfoView(Context context, MobilePlayerView player) {
        super(context);
        mPlayer = player;
        setOrientation(VERTICAL);
        setPadding(0, dp(12), 0, dp(8));

        // Title + views · date
        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(VERTICAL);
        titleBox.setPadding(dp(14), 0, dp(14), 0);
        titleBox.setOnClickListener(v -> toggleDescription());
        mTitle = text(18, Color.WHITE, true);
        mTitle.setMaxLines(3);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleBox.addView(mTitle);
        mMeta = text(13, TEXT_SECONDARY, false);
        mMeta.setPadding(0, dp(6), 0, 0);
        titleBox.addView(mMeta);
        addView(titleBox);

        // Channel row
        LinearLayout channel = new LinearLayout(context);
        channel.setGravity(Gravity.CENTER_VERTICAL);
        channel.setPadding(dp(14), dp(14), dp(14), dp(6));
        channel.setOnClickListener(v -> press(R.id.action_channel));
        mAvatar = new ImageView(context);
        mAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mAvatar.setBackground(circle(0xFF16264A));
        channel.addView(mAvatar, new LayoutParams(dp(38), dp(38)));
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(VERTICAL);
        names.setPadding(dp(12), 0, dp(8), 0);
        mChannelName = text(15, Color.WHITE, true);
        mChannelName.setMaxLines(1);
        mChannelName.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(mChannelName);
        mSubscribers = text(12, TEXT_SECONDARY, false);
        mSubscribers.setMaxLines(1);
        names.addView(mSubscribers);
        channel.addView(names, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mSubscribe = text(14, Color.BLACK, true);
        mSubscribe.setGravity(Gravity.CENTER);
        mSubscribe.setPadding(dp(16), 0, dp(16), 0);
        mSubscribe.setOnClickListener(v -> onSubscribeClicked());
        channel.addView(mSubscribe, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        addView(channel);

        // Actions (scroll sideways on small screens)
        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(14), dp(8), dp(14), dp(8));
        actionsScroll.addView(actions);

        // Like | dislike: one pill split in two, like YouTube
        LinearLayout likePill = new LinearLayout(context);
        likePill.setGravity(Gravity.CENTER_VERTICAL);
        likePill.setBackground(pill(CHIP_COLOR));
        mLike = text(13, Color.WHITE, true);
        mLike.setGravity(Gravity.CENTER_VERTICAL);
        mLike.setCompoundDrawablePadding(dp(6));
        mLike.setPadding(dp(14), 0, dp(12), 0);
        mLike.setOnClickListener(v -> press(R.id.action_thumbs_up));
        likePill.addView(mLike, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        View separator = new View(context);
        separator.setBackgroundColor(0x40FFFFFF);
        likePill.addView(separator, new LayoutParams(1, dp(20)));
        mDislike = new ImageView(context);
        mDislike.setImageResource(R.drawable.ic_m_thumb_down);
        mDislike.setPadding(dp(12), dp(9), dp(14), dp(9));
        mDislike.setOnClickListener(v -> press(R.id.action_thumbs_down));
        likePill.addView(mDislike, new LayoutParams(dp(44), dp(36)));
        actions.addView(likePill);

        mWatchLater = (TextView) chip(R.drawable.ic_m_history, R.string.mobile_watch_later, v -> {
            MobileTopics.toggleWatchLater(getContext(), mVideo);
            bind(mVideo);
        });
        actions.addView(mWatchLater);
        // "Watch together": same video, chat and voice notes with someone else (another phone or a TV)
        actions.addView(chip(R.drawable.ic_m_together, R.string.together_title, v -> {
            if (getContext() instanceof android.app.Activity) {
                MobileTogether.showDialog((android.app.Activity) getContext());
            }
        }));
        actions.addView(chip(R.drawable.ic_m_share, R.string.mobile_watch_share, v -> share()));
        actions.addView(chip(R.drawable.ic_m_playlist_add, R.string.mobile_watch_save, v -> press(R.id.action_playlist_add)));
        addView(actionsScroll);

        // Room panel (only while watching together)
        MobileTogether.Panel together = new MobileTogether.Panel(context);
        LayoutParams togetherParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        togetherParams.setMargins(dp(12), dp(4), dp(12), dp(6));
        addView(together, togetherParams);

        // Description card
        mDescriptionCard = new LinearLayout(context);
        mDescriptionCard.setOrientation(VERTICAL);
        mDescriptionCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        mDescriptionCard.setBackground(rounded(CARD_COLOR, 12));
        mDescriptionCard.setOnClickListener(v -> toggleDescription());
        mDescription = text(14, 0xFFDDE6F7, false);
        mDescription.setLineSpacing(0, 1.15f);
        mDescription.setAutoLinkMask(Linkify.WEB_URLS);
        mDescription.setLinkTextColor(0xFF7FB6FF);
        mDescriptionCard.addView(mDescription);
        mDescriptionToggle = text(14, Color.WHITE, true);
        mDescriptionToggle.setPadding(0, dp(6), 0, 0);
        mDescriptionCard.addView(mDescriptionToggle);
        LayoutParams cardParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(dp(12), dp(6), dp(12), dp(8));
        addView(mDescriptionCard, cardParams);
    }

    public void bind(Video video) {
        if (video != mVideo) {
            mExpanded = false;
        }

        mVideo = video;

        if (video == null) {
            return;
        }

        mTitle.setText(video.getTitleFull() != null ? video.getTitleFull() : video.getTitle());

        String author = authorOf(video);
        mMeta.setText(viewsAndDate(video, author));
        mChannelName.setText(author);
        mSubscribers.setText(video.subscriberCount);
        mSubscribers.setVisibility(TextUtils.isEmpty(video.subscriberCount) ? GONE : VISIBLE);

        // Avatar (arrives with the video metadata)
        String avatar = mPlayer.getChannelIconUrl();
        if (avatar != null && !avatar.equals(mLoadedAvatar) && !isActivityDestroyed()) {
            mLoadedAvatar = avatar;
            Glide.with(getContext()).load(avatar).apply(ViewUtil.glideOptions()).circleCrop().into(mAvatar);
        } else if (avatar == null) {
            mLoadedAvatar = null;
            mAvatar.setImageResource(R.drawable.ic_m_account);
        }

        boolean subscribed = mPlayer.getButtonState(R.id.action_subscribe) == BUTTON_ON;
        mSubscribe.setText(subscribed ? R.string.mobile_watch_subscribed : R.string.mobile_watch_subscribe);
        mSubscribe.setTextColor(subscribed ? Color.WHITE : Color.BLACK);
        mSubscribe.setBackground(pill(subscribed ? CHIP_COLOR : Color.WHITE));

        boolean liked = mPlayer.getButtonState(R.id.action_thumbs_up) == BUTTON_ON;
        boolean disliked = mPlayer.getButtonState(R.id.action_thumbs_down) == BUTTON_ON;
        mLike.setText(likeText(video, liked));
        mLike.setCompoundDrawablesWithIntrinsicBounds(tinted(R.drawable.ic_m_thumb_up, liked), null, null, null);
        mDislike.setColorFilter(disliked ? ACCENT : Color.WHITE);

        boolean savedForLater = MobileTopics.isInWatchLater(getContext(), video);
        mWatchLater.setCompoundDrawablesWithIntrinsicBounds(tinted(R.drawable.ic_m_history, savedForLater), null, null, null);
        mWatchLater.setTextColor(savedForLater ? 0xFF7FB6FF : Color.WHITE);

        // Description: 3 lines, tap to see all of it
        boolean hasDescription = !TextUtils.isEmpty(video.description);
        mDescriptionCard.setVisibility(hasDescription ? VISIBLE : GONE);
        mDescription.setText(video.description);
        mDescription.setMaxLines(mExpanded ? Integer.MAX_VALUE : 3);
        mDescription.setEllipsize(mExpanded ? null : TextUtils.TruncateAt.END);
        mDescriptionToggle.setText(mExpanded ? R.string.mobile_watch_less : R.string.mobile_watch_more);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * "Author • 192 K views • 2 weeks ago" -> "192 K views · 2 weeks ago" (the author is in the channel row).
     */
    private static CharSequence viewsAndDate(Video video, String author) {
        CharSequence second = video.getSecondTitleFull() != null ? video.getSecondTitleFull() : video.getSecondTitle();

        if (second == null) {
            return null;
        }

        List<String> parts = new ArrayList<>();

        for (String part : second.toString().split("[•·]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(author) && !trimmed.startsWith("@")) {
                parts.add(trimmed);
            }
        }

        return TextUtils.join("  ·  ", parts);
    }

    /**
     * The channel name. Before the metadata arrives (e.g. a search result) only "Author • views • date" is known.
     */
    private static String authorOf(Video video) {
        String author = !TextUtils.isEmpty(video.author) ? video.author : video.getAuthor();

        if (author != null && (author.contains("•") || author.contains("·"))) {
            author = author.split("[•·]")[0].trim();
        }

        return author;
    }

    private void toggleDescription() {
        if (mVideo == null || TextUtils.isEmpty(mVideo.description)) {
            return;
        }

        mExpanded = !mExpanded;
        bind(mVideo);
    }

    /**
     * Like YouTube: unsubscribing asks first ("Unsubscribe from X?"), subscribing is immediate.
     */
    private void onSubscribeClicked() {
        if (mPlayer.getButtonState(R.id.action_subscribe) != BUTTON_ON) {
            press(R.id.action_subscribe);
            return;
        }

        String channel = mVideo != null ? authorOf(mVideo) : null;
        new android.app.AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getContext().getString(R.string.mobile_unsubscribe_confirm, channel != null ? channel : ""))
                .setNegativeButton(R.string.mobile_cancel, null)
                .setPositiveButton(R.string.mobile_unsubscribe, (dialog, which) -> press(R.id.action_subscribe))
                .show();
    }

    /**
     * The like count follows the button: +1 when the user likes, -1 when the like is removed
     * (only for exact counts like "804"; "6.1K" stays as it is, like on YouTube).
     */
    private String likeText(Video video, boolean liked) {
        if (TextUtils.isEmpty(video.likeCount)) {
            return getContext().getString(R.string.mobile_watch_like);
        }

        String key = video.videoId != null ? video.videoId : String.valueOf(video.hashCode());

        if (!key.equals(mLikeVideoKey)) {
            mLikeVideoKey = key;
            mLikedAtLoad = liked; // the state YouTube reported together with the count
        }

        String digits = video.likeCount.replaceAll("[.,\\s\\u00A0]", "");

        if (!digits.matches("\\d{1,9}")) {
            return video.likeCount;
        }

        long count = Long.parseLong(digits) + (liked == mLikedAtLoad ? 0 : liked ? 1 : -1);
        return java.text.NumberFormat.getIntegerInstance().format(Math.max(0, count));
    }

    private void press(int buttonId) {
        PlaybackPresenter.instance(getContext()).onButtonClicked(buttonId, mPlayer.getButtonState(buttonId));
    }

    private void share() {
        if (mVideo == null || mVideo.videoId == null) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, mVideo.getTitle());
        intent.putExtra(Intent.EXTRA_TEXT, mVideo.getTitle() + "\nhttps://youtu.be/" + mVideo.videoId);

        try {
            getContext().startActivity(Intent.createChooser(intent, getContext().getString(R.string.mobile_watch_share)));
        } catch (ActivityNotFoundException e) {
            press(R.id.action_share); // fallback: the app's own share dialog
        }
    }

    private boolean isActivityDestroyed() {
        return getContext() instanceof android.app.Activity && ((android.app.Activity) getContext()).isDestroyed();
    }

    private View chip(int iconResId, int labelResId, OnClickListener listener) {
        TextView chip = text(13, Color.WHITE, true);
        chip.setText(labelResId);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setCompoundDrawablesWithIntrinsicBounds(tinted(iconResId, false), null, null, null);
        chip.setCompoundDrawablePadding(dp(6));
        chip.setPadding(dp(14), 0, dp(16), 0);
        chip.setBackground(pill(CHIP_COLOR));
        chip.setOnClickListener(listener);
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        params.setMargins(dp(8), 0, 0, 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private android.graphics.drawable.Drawable tinted(int iconResId, boolean active) {
        android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(getContext(), iconResId);

        if (icon != null) {
            icon = icon.mutate();
            icon.setBounds(0, 0, dp(18), dp(18));
            icon.setColorFilter(active ? ACCENT : Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            return new ScaledDrawable(icon, dp(18)); // fixed size, whatever the intrinsic one is
        }

        return null;
    }

    private TextView text(int sizeSp, int color, boolean bold) {
        TextView text = new TextView(getContext());
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        if (bold) {
            text.setTypeface(null, Typeface.BOLD);
        }
        return text;
    }

    private GradientDrawable pill(int color) {
        return rounded(color, 18);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Drawable shown at a fixed square size (compound drawables use the intrinsic size otherwise).
     */
    private static class ScaledDrawable extends android.graphics.drawable.LayerDrawable {
        private final int mSize;

        ScaledDrawable(android.graphics.drawable.Drawable drawable, int size) {
            super(new android.graphics.drawable.Drawable[] {drawable});
            mSize = size;
        }

        @Override
        public int getIntrinsicWidth() {
            return mSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return mSize;
        }
    }
}
