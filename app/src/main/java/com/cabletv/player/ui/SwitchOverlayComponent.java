package com.cabletv.player.ui;

import android.content.Context;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.cabletv.player.model.Channel;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;

public class SwitchOverlayComponent extends FrameLayout implements IControlComponent {
    private final ImageView mLogoView;
    private final TextView mChannelNameView;
    private final LinearLayout mOverlay;
    private boolean mIsVisible = false;

    public SwitchOverlayComponent(@NonNull Context context) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Center overlay with logo and channel name
        mOverlay = new LinearLayout(context);
        mOverlay.setOrientation(LinearLayout.VERTICAL);
        mOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                dp2px(200),
                dp2px(200),
                android.view.Gravity.CENTER));
        mOverlay.setBackgroundColor(0xB3000000);
        mOverlay.setAlpha(0.8f);

        // Logo
        mLogoView = new ImageView(context);
        mLogoView.setLayoutParams(new LinearLayout.LayoutParams(
                dp2px(120),
                dp2px(120)));
        mLogoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mOverlay.addView(mLogoView);

        // Channel name
        mChannelNameView = new TextView(context);
        mChannelNameView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(60)));
        mChannelNameView.setTextColor(0xFFFFFFFF);
        mChannelNameView.setTextSize(16);
        mChannelNameView.setGravity(android.view.Gravity.CENTER);
        mChannelNameView.setMaxLines(2);
        mOverlay.addView(mChannelNameView);

        addView(mOverlay);
        setVisibility(GONE);
    }

    public void showChannelSwitch(Channel channel) {
        if (channel == null) {
            return;
        }

        mChannelNameView.setText(channel.name);
        if (channel.logo != null && !channel.logo.isEmpty()) {
            Glide.with(getContext())
                    .load(channel.logo)
                    .centerInside()
                    .into(mLogoView);
        } else {
            mLogoView.setImageDrawable(null);
        }

        setVisibility(VISIBLE);
        setAlpha(1.0f);

        // Auto fade out after 2 seconds
        postDelayed(this::fadeOut, 2000);
    }

    private void fadeOut() {
        if (getVisibility() != VISIBLE) {
            return;
        }

        AlphaAnimation fadeOut = new AlphaAnimation(1.0f, 0.0f);
        fadeOut.setDuration(500);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                setVisibility(GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });
        startAnimation(fadeOut);
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        // No-op for overlay
    }

    @Nullable
    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        mIsVisible = isVisible;
        if (!isVisible) {
            setVisibility(GONE);
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
        // Not used
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        // Not used
    }

    @Override
    public void setProgress(int duration, int position) {
        // Not used
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
        // Not used
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }
}
