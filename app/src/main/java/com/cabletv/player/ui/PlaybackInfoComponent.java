package com.cabletv.player.ui;

import android.content.Context;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cabletv.player.R;
import com.cabletv.player.epg.EpgManager;
import com.cabletv.player.model.Channel;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;

public class PlaybackInfoComponent extends FrameLayout implements IControlComponent {
    private LinearLayout mInfoContainer;
    private TextView mChannelNameView;
    private TextView mCurrentProgramView;
    private ProgressBar mProgressBar;
    private TextView mNextProgramView;
    private EpgManager mEpgManager;
    private Channel mCurrentChannel;
    private int mAutoHideDelay = 3000;

    public PlaybackInfoComponent(@NonNull Context context) {
        super(context);
        initView(context);
    }

    private void initView(Context context) {
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp2px(100)));
        setBackgroundResource(R.drawable.gradient_playback_info_bg);

        mInfoContainer = new LinearLayout(context);
        mInfoContainer.setOrientation(LinearLayout.VERTICAL);
        mInfoContainer.setLayoutParams(new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        mInfoContainer.setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8));

        // Channel name and icon row
        LinearLayout channelRow = new LinearLayout(context);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(30)));

        mChannelNameView = new TextView(context);
        mChannelNameView.setTextColor(0xFFFFFFFF);
        mChannelNameView.setTextSize(16);
        mChannelNameView.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));
        channelRow.addView(mChannelNameView);

        mInfoContainer.addView(channelRow);

        // Current program info row
        LinearLayout programRow = new LinearLayout(context);
        programRow.setOrientation(LinearLayout.VERTICAL);
        programRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mCurrentProgramView = new TextView(context);
        mCurrentProgramView.setTextColor(0xFFFFFFFF);
        mCurrentProgramView.setTextSize(13);
        mCurrentProgramView.setMaxLines(1);
        mCurrentProgramView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        programRow.addView(mCurrentProgramView);

        mProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(4)));
        mProgressBar.setProgressDrawable(context.getDrawable(R.drawable.progress_bar_accent));
        programRow.addView(mProgressBar);

        mNextProgramView = new TextView(context);
        mNextProgramView.setTextColor(0xFF999999);
        mNextProgramView.setTextSize(12);
        mNextProgramView.setMaxLines(1);
        mNextProgramView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        programRow.addView(mNextProgramView);

        mInfoContainer.addView(programRow);
        addView(mInfoContainer);

        setVisibility(GONE);
    }

    public void setEpgManager(EpgManager epgManager) {
        mEpgManager = epgManager;
    }

    public void showChannelInfo(Channel channel) {
        mCurrentChannel = channel;
        if (mChannelNameView != null) {
            mChannelNameView.setText(channel.name);
        }
        updateProgramInfo();
        showWithAutoHide();
    }

    private void updateProgramInfo() {
        if (mCurrentChannel == null || mEpgManager == null) {
            if (mCurrentProgramView != null) {
                mCurrentProgramView.setText("No EPG data");
            }
            if (mNextProgramView != null) {
                mNextProgramView.setText("");
            }
            if (mProgressBar != null) {
                mProgressBar.setProgress(0);
                mProgressBar.setMax(100);
            }
            return;
        }

        EpgManager.Program currentProgram = mEpgManager.getCurrentProgram(mCurrentChannel);
        if (currentProgram != null && mCurrentProgramView != null) {
            mCurrentProgramView.setText(currentProgram.title);

            // Update progress bar based on program duration
            if (mProgressBar != null && currentProgram.startTime > 0 && currentProgram.endTime > 0) {
                long duration = currentProgram.endTime - currentProgram.startTime;
                long elapsed = System.currentTimeMillis() - currentProgram.startTime;

                if (duration > 0) {
                    int progress = (int) ((elapsed * 100) / duration);
                    mProgressBar.setMax(100);
                    mProgressBar.setProgress(Math.max(0, Math.min(100, progress)));
                } else {
                    mProgressBar.setProgress(0);
                }
            }
        } else if (mCurrentProgramView != null) {
            mCurrentProgramView.setText("No EPG data");
            if (mProgressBar != null) {
                mProgressBar.setProgress(0);
            }
        }

        String nextProgram = mEpgManager.getNextProgramInfo(mCurrentChannel);
        if (mNextProgramView != null) {
            mNextProgramView.setText(nextProgram != null ? "Next: " + nextProgram : "");
        }
    }

    private void showWithAutoHide() {
        setVisibility(VISIBLE);
        setAlpha(0.0f);
        animate()
                .alpha(1.0f)
                .setDuration(300)
                .start();
        removeCallbacks(this::hide);
        postDelayed(this::hide, mAutoHideDelay);
    }

    private void hide() {
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
    }

    @Nullable
    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        if (isVisible) {
            showWithAutoHide();
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
    }

    @Override
    public void setProgress(int duration, int position) {
        if (mProgressBar != null && duration > 0) {
            mProgressBar.setMax(duration);
            mProgressBar.setProgress(position);
        }
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }
}
