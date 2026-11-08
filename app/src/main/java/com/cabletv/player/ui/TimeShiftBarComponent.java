package com.cabletv.player.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The unified playback & time-shift (时移/回看) HUD shown over live playback and catchup.
 *
 * <p>Combines channel information, channel logo, current programme title, scrubbable single-programme timeline,
 * start/end time markers, fixed behind-live difference indicator, seek rate, and the live edge shortcut.
 */
public class TimeShiftBarComponent extends FrameLayout {

    public static final int BAR_HEIGHT_DP = 126;

    private final TextView mChannelNumView;
    private final ImageView mLogoView;
    private final TextView mChannelLabel;
    private final TextView mSourceBadge;
    private final TextView mLiveBadge;
    private final TextView mBackToLive;

    private final TextView mProgramTitle;
    private final TextView mNextProgramView;

    private final ProgramTimelineView mTimeline;

    private final TextView mStartTimeLabel;
    private final TextView mEndTimeLabel;
    private final TextView mTargetLabel;
    private final TextView mRateLabel;
    private final TextView mBehindLiveLabel;

    private OnSeekListener mSeekListener;
    private static final long AUTO_HIDE_MS = 6_000;
    private final Runnable mAutoHideRunnable = this::onAutoHide;
    private boolean mAutoHidePending;
    private boolean mRateVisible;

    private long mWindowStartMs = 0;
    private long mWindowEndMs = 0;
    private long mPositionMs = 0;
    private long mLivePointMs = 0;
    private long mFixedBehindMs = 0;
    private boolean mLiveMode = true;
    private boolean mDragging = false;

    public interface OnSeekListener {
        /** The user released the thumb at this absolute epoch time; seek the playhead there. */
        void onSeekTo(long targetMs);

        /** The user asked to jump straight to the live edge (返回直播). */
        void onBackToLive();
    }

    public TimeShiftBarComponent(@NonNull Context context) {
        super(context);
        applyOpacity();
        setPadding(dp2px(20), dp2px(12), dp2px(20), dp2px(10));
        setVisibility(GONE);
        setClickable(true);

        LinearLayout rootContainer = new LinearLayout(context);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        rootContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(rootContainer);

        // ================= ROW 1: Channel Info & Live/Catchup Controls =================
        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2px(28));
        rootContainer.addView(row1, r1Lp);

        mChannelNumView = new TextView(context);
        mChannelNumView.setTextColor(0xFF00E5FF);
        mChannelNumView.setTextSize(11);
        mChannelNumView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mChannelNumView.setGravity(Gravity.CENTER);
        mChannelNumView.setBackgroundResource(R.drawable.bg_channel_num_pill);
        mChannelNumView.setPadding(dp2px(6), dp2px(2), dp2px(6), dp2px(2));
        LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp2px(20));
        numLp.rightMargin = dp2px(8);
        row1.addView(mChannelNumView, numLp);

        mLogoView = new ImageView(context);
        mLogoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mLogoView.setBackgroundResource(R.drawable.bg_channel_logo_holder);
        mLogoView.setPadding(dp2px(3), dp2px(1), dp2px(3), dp2px(1));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                dp2px(38), dp2px(22));
        logoLp.rightMargin = dp2px(10);
        mLogoView.setLayoutParams(logoLp);
        mLogoView.setVisibility(GONE);
        row1.addView(mLogoView);

        mChannelLabel = new TextView(context);
        mChannelLabel.setTextColor(0xFFFFFFFF);
        mChannelLabel.setTextSize(16);
        mChannelLabel.setTypeface(Typeface.DEFAULT_BOLD);
        mChannelLabel.setMaxLines(1);
        mChannelLabel.setEllipsize(TextUtils.TruncateAt.END);
        row1.addView(mChannelLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        mSourceBadge = new TextView(context);
        mSourceBadge.setTextColor(0xFF94A3B8);
        mSourceBadge.setTextSize(11);
        mSourceBadge.setBackgroundResource(R.drawable.bg_date_capsule);
        mSourceBadge.setPadding(dp2px(6), dp2px(2), dp2px(6), dp2px(2));
        mSourceBadge.setVisibility(GONE);
        LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srcLp.rightMargin = dp2px(12);
        row1.addView(mSourceBadge, srcLp);

        mLiveBadge = new TextView(context);
        mLiveBadge.setTextColor(0xFF00E676);
        mLiveBadge.setTextSize(12);
        mLiveBadge.setTypeface(Typeface.DEFAULT_BOLD);
        mLiveBadge.setText(R.string.live_indicator);
        mLiveBadge.setVisibility(VISIBLE);
        row1.addView(mLiveBadge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mBackToLive = new TextView(context);
        mBackToLive.setText(R.string.back_to_live);
        mBackToLive.setTextColor(0xFFFFFFFF);
        mBackToLive.setTextSize(12);
        mBackToLive.setTypeface(Typeface.DEFAULT_BOLD);
        mBackToLive.setBackgroundResource(R.drawable.apple_pill_button);
        mBackToLive.setPadding(dp2px(12), dp2px(4), dp2px(12), dp2px(4));
        mBackToLive.setVisibility(GONE);
        mBackToLive.setFocusable(true);
        mBackToLive.setOnClickListener(v -> {
            if (mSeekListener != null) {
                mSeekListener.onBackToLive();
            }
        });
        row1.addView(mBackToLive, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ================= ROW 2: Program Names =================
        LinearLayout row2 = new LinearLayout(context);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2px(20));
        r2Lp.topMargin = dp2px(4);
        rootContainer.addView(row2, r2Lp);

        mProgramTitle = new TextView(context);
        mProgramTitle.setTextColor(0xFFE2E8F0);
        mProgramTitle.setTextSize(14);
        mProgramTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mProgramTitle.setMaxLines(1);
        mProgramTitle.setEllipsize(TextUtils.TruncateAt.END);
        row2.addView(mProgramTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        mNextProgramView = new TextView(context);
        mNextProgramView.setTextColor(0xFF64748B);
        mNextProgramView.setTextSize(12);
        mNextProgramView.setMaxLines(1);
        mNextProgramView.setEllipsize(TextUtils.TruncateAt.END);
        row2.addView(mNextProgramView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ================= ROW 3: Single Program Timeline =================
        mTimeline = new ProgramTimelineView(context);
        mTimeline.setOnScrubListener(new ProgramTimelineView.OnScrubListener() {
            @Override
            public void onScrubStart() {
                mDragging = true;
                cancelAutoHide();
            }

            @Override
            public void onScrubTo(long timeMs) {
                mPositionMs = timeMs;
                if (!mLiveMode) {
                    long now = mLivePointMs > 0 ? mLivePointMs : System.currentTimeMillis();
                    mFixedBehindMs = Math.max(0, now - mPositionMs);
                }
                updateLabels();
            }

            @Override
            public void onScrubCommit(long timeMs) {
                mDragging = false;
                commitPosition(timeMs);
                scheduleAutoHide();
            }
        });
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2px(22));
        tlLp.topMargin = dp2px(4);
        rootContainer.addView(mTimeline, tlLp);

        // ================= ROW 4: Time Labels & Status Row =================
        FrameLayout row4 = new FrameLayout(context);
        LinearLayout.LayoutParams r4Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2px(18));
        r4Lp.topMargin = dp2px(2);
        rootContainer.addView(row4, r4Lp);

        mStartTimeLabel = new TextView(context);
        mStartTimeLabel.setTextColor(0xFF64748B);
        mStartTimeLabel.setTextSize(11);
        mStartTimeLabel.setTypeface(Typeface.MONOSPACE);
        FrameLayout.LayoutParams startLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        row4.addView(mStartTimeLabel, startLp);

        LinearLayout centerGroup = new LinearLayout(context);
        centerGroup.setOrientation(LinearLayout.HORIZONTAL);
        centerGroup.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        row4.addView(centerGroup, centerLp);

        mTargetLabel = new TextView(context);
        mTargetLabel.setTextColor(0xFF00E5FF);
        mTargetLabel.setTextSize(11);
        mTargetLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTargetLabel.setPadding(0, 0, dp2px(8), 0);
        centerGroup.addView(mTargetLabel);

        mBehindLiveLabel = new TextView(context);
        mBehindLiveLabel.setTextColor(0xFFFF7A00);
        mBehindLiveLabel.setTextSize(11);
        mBehindLiveLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mBehindLiveLabel.setBackgroundResource(R.drawable.bg_hud_behind_live);
        mBehindLiveLabel.setPadding(dp2px(8), dp2px(2), dp2px(8), dp2px(2));
        mBehindLiveLabel.setVisibility(GONE);
        centerGroup.addView(mBehindLiveLabel);

        mRateLabel = new TextView(context);
        mRateLabel.setTextColor(0xFF00E5FF);
        mRateLabel.setTextSize(12);
        mRateLabel.setTypeface(Typeface.DEFAULT_BOLD);
        mRateLabel.setVisibility(GONE);
        centerGroup.addView(mRateLabel);

        mEndTimeLabel = new TextView(context);
        mEndTimeLabel.setTextColor(0xFF64748B);
        mEndTimeLabel.setTextSize(11);
        mEndTimeLabel.setTypeface(Typeface.MONOSPACE);
        FrameLayout.LayoutParams endLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        row4.addView(mEndTimeLabel, endLp);
    }

    public void setChannelInfo(String name, int channelNumber, int urlIndex, int urlCount) {
        setChannelInfo(name, channelNumber, urlIndex, urlCount, null);
    }

    public void setChannelInfo(String name, int channelNumber, int urlIndex, int urlCount, @Nullable String logoUrl) {
        if (mChannelLabel != null) {
            mChannelLabel.setText(name);
        }
        if (mChannelNumView != null) {
            if (channelNumber > 0) {
                mChannelNumView.setText(String.format(Locale.getDefault(), "%02d", channelNumber));
                mChannelNumView.setVisibility(VISIBLE);
            } else {
                mChannelNumView.setVisibility(GONE);
            }
        }
        if (mSourceBadge != null) {
            if (urlCount > 1) {
                mSourceBadge.setText(getContext().getString(R.string.source_switched, "", urlIndex, urlCount).trim());
                mSourceBadge.setVisibility(VISIBLE);
            } else {
                mSourceBadge.setVisibility(GONE);
            }
        }
        if (mLogoView != null) {
            if (logoUrl != null && !logoUrl.trim().isEmpty()) {
                mLogoView.setVisibility(VISIBLE);
                try {
                    Glide.with(getContext())
                            .load(logoUrl.trim())
                            .listener(new RequestListener<Drawable>() {
                                @Override
                                public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                                    mLogoView.post(() -> mLogoView.setVisibility(GONE));
                                    return false;
                                }

                                @Override
                                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                                    mLogoView.setVisibility(VISIBLE);
                                    return false;
                                }
                            })
                            .into(mLogoView);
                } catch (Exception e) {
                    mLogoView.setImageDrawable(null);
                    mLogoView.setVisibility(GONE);
                }
            } else {
                mLogoView.setImageDrawable(null);
                mLogoView.setVisibility(GONE);
            }
        }
    }

    public void setProgramInfo(String currentProgram, String nextProgram) {
        if (mProgramTitle != null) {
            if (currentProgram != null && !currentProgram.isEmpty()) {
                mProgramTitle.setText(getContext().getString(R.string.playing_now, currentProgram));
            } else {
                mProgramTitle.setText(R.string.no_epg_data);
            }
        }
        if (mNextProgramView != null) {
            if (nextProgram != null && !nextProgram.isEmpty()) {
                mNextProgramView.setText(getContext().getString(R.string.next_program, nextProgram));
                mNextProgramView.setVisibility(VISIBLE);
            } else {
                mNextProgramView.setText("");
                mNextProgramView.setVisibility(GONE);
            }
        }
    }

    public void showWhileSeeking() {
        cancelAutoHide();
        clearAnimation();
        setAlpha(1f);
        setVisibility(VISIBLE);
    }

    public void endSeeking() {
        hideSeekRate();
        scheduleAutoHide();
    }

    public void showSeekRate(int rate) {
        if (mRateLabel == null) {
            return;
        }
        mRateVisible = true;
        mBehindLiveLabel.setVisibility(GONE);
        mRateLabel.setText(rate + "x");
        mRateLabel.setVisibility(VISIBLE);
    }

    public void showSeekPreview(int rate, long deltaMs) {
        if (mRateLabel == null) {
            return;
        }
        mRateVisible = true;
        mBehindLiveLabel.setVisibility(GONE);
        long seconds = Math.abs(deltaMs) / 1000;
        mRateLabel.setText(rate + "x · " + (deltaMs < 0 ? "-" : "+") + seconds + "s");
        mRateLabel.setVisibility(VISIBLE);
    }

    public void hideSeekRate() {
        mRateVisible = false;
        if (mRateLabel != null) {
            mRateLabel.setVisibility(GONE);
        }
        updateBehindLive();
    }

    ProgramTimelineView findTimeline() {
        return mTimeline;
    }

    String findShownPositionLabel() {
        return mTargetLabel.getText().toString();
    }

    long findShownPositionMs() {
        return mPositionMs;
    }

    long findBehindLiveMs() {
        if (mFixedBehindMs > 0) {
            return mFixedBehindMs;
        }
        return mLivePointMs > 0 ? mLivePointMs - mPositionMs : 0;
    }

    boolean hasPendingAutoHide() {
        return mAutoHidePending;
    }

    public FrameLayout.LayoutParams createBottomLayoutParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp2px(BAR_HEIGHT_DP));
        lp.gravity = Gravity.BOTTOM;
        lp.setMargins(dp2px(32), 0, dp2px(32), dp2px(24));
        return lp;
    }

    private static final int INFO_BAR_HEIGHT_DP = 100;
    private static final int COMPACT_MARGIN_DP = 8;
    private static final int MIN_GAP_DP = 24;

    static int bottomMarginDp(int screenHeightDp) {
        int needed = 88 + INFO_BAR_HEIGHT_DP + MIN_GAP_DP;
        return screenHeightDp >= needed ? INFO_BAR_HEIGHT_DP : COMPACT_MARGIN_DP;
    }

    public void setOnSeekListener(@Nullable OnSeekListener listener) {
        mSeekListener = listener;
    }

    public void setWindow(long windowStartMs, long windowEndMs, long positionMs) {
        mWindowStartMs = windowStartMs;
        mWindowEndMs = Math.max(windowStartMs + 1000, windowEndMs);
        mPositionMs = Math.max(windowStartMs, Math.min(mWindowEndMs, positionMs));
        mTimeline.setWindow(mWindowStartMs, mWindowEndMs, mPositionMs);
        if (!mLiveMode) {
            long now = mLivePointMs > 0 ? mLivePointMs : System.currentTimeMillis();
            mFixedBehindMs = Math.max(0, now - mPositionMs);
        } else {
            mFixedBehindMs = 0;
        }
        updateLabels();
    }

    public long getWindowStart() {
        return mWindowStartMs;
    }

    public long getWindowEnd() {
        return mWindowEndMs;
    }

    public boolean isPastLastProgramme() {
        return mTimeline.isPastLastSegment();
    }

    public void setPrograms(List<ProgramTimelineView.Segment> segments) {
        mTimeline.setSegments(segments);
        updateProgramTitle();
    }

    public void setLiveEdgeVisible(boolean visible) {
        mTimeline.setLiveEdgeVisible(visible);
    }

    private void updateProgramTitle() {
        String title = null;
        for (ProgramTimelineView.Segment segment : mTimeline.getSegments()) {
            if (mPositionMs >= segment.startTime && mPositionMs <= segment.endTime) {
                title = segment.title;
                break;
            }
        }
        if (title != null && !title.isEmpty()) {
            mProgramTitle.setText(getContext().getString(R.string.playing_now, title));
        }
    }

    static String formatBehind(long behindMs) {
        if (behindMs <= 0) {
            return "0:00";
        }
        long total = (behindMs + 500) / 1000;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    void commitPosition(long timeMs) {
        mPositionMs = Math.max(mWindowStartMs, Math.min(mWindowEndMs, timeMs));
        if (!mLiveMode) {
            long now = mLivePointMs > 0 ? mLivePointMs : System.currentTimeMillis();
            mFixedBehindMs = Math.max(0, now - mPositionMs);
        }
        updateLabels();
        if (mSeekListener != null) {
            mSeekListener.onSeekTo(mPositionMs);
        }
    }

    public void setChannelLabel(String label) {
        mChannelLabel.setText(label);
    }

    public void setBackToLiveVisible(boolean visible) {
        mBackToLive.setVisibility(visible ? VISIBLE : GONE);
        mLiveBadge.setVisibility(visible ? GONE : VISIBLE);
    }

    private void updateLabels() {
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        mTargetLabel.setText(timeFmt.format(new Date(mPositionMs)));
        mStartTimeLabel.setText(timeFmt.format(new Date(mWindowStartMs)));
        mEndTimeLabel.setText(timeFmt.format(new Date(mWindowEndMs)));
        mTimeline.setPosition(mPositionMs);
        updateBehindLive();
    }

    private void updateBehindLive() {
        if (mRateVisible) {
            mBehindLiveLabel.setVisibility(GONE);
            return;
        }
        long behind = mFixedBehindMs;
        if (behind <= 0 && !mLiveMode) {
            long now = mLivePointMs > 0 ? mLivePointMs : System.currentTimeMillis();
            behind = Math.max(0, now - mPositionMs);
        }
        if (!mLiveMode && behind > 2000) {
            mBehindLiveLabel.setVisibility(VISIBLE);
            mBehindLiveLabel.setText(getContext().getString(R.string.timeshift_behind_live,
                    formatBehind(behind)));
        } else {
            mBehindLiveLabel.setVisibility(GONE);
        }
    }

    public void setPlaybackPosition(long positionMs) {
        if (mDragging) {
            return;
        }
        long clamped = Math.max(mWindowStartMs, Math.min(mWindowEndMs, positionMs));
        if (clamped == mPositionMs) {
            return;
        }
        mPositionMs = clamped;
        mTimeline.setPosition(mPositionMs);
        updateLabels();
    }

    public void tickIfIdle() {
        tickIfIdle(System.currentTimeMillis());
    }

    void tickIfIdle(long now) {
        if (mDragging || getVisibility() != VISIBLE) {
            return;
        }
        mLivePointMs = now;
        if (mLiveMode) {
            mWindowEndMs = Math.max(mWindowEndMs, now);
            mPositionMs = now;
            mTimeline.setWindow(mWindowStartMs, mWindowEndMs, mPositionMs);
        }
        updateLabels();
    }

    public void setLiveMode(boolean liveMode, long livePointMs) {
        mLiveMode = liveMode;
        mLivePointMs = livePointMs;
        if (!liveMode) {
            mFixedBehindMs = Math.max(0, livePointMs - mPositionMs);
        } else {
            mFixedBehindMs = 0;
        }
        mTimeline.setLiveEdgeVisible(liveMode);
        mLiveBadge.setVisibility(liveMode ? VISIBLE : GONE);
        mBackToLive.setVisibility(!liveMode ? VISIBLE : GONE);
        updateBehindLive();
    }

    /**
     * Updates the background gradient opacity according to user preference in AppConfig.
     * Follows the same alpha scale as the channel guide:
     * - TRANSLUCENT: 0xB3 (top 70%) / 0xC0 (bottom 75%)
     * - SEMI_OPAQUE: 0xE0 (top 88%) / 0xE6 (bottom 90%)
     * - OPAQUE:      0xFF (top 100%) / 0xFF (bottom 100%)
     */
    public void applyOpacity() {
        int level = AppConfig.getSidebarOpacity();
        int startColor;
        int endColor;
        int strokeColor;
        switch (level) {
            case AppConfig.SIDEBAR_OPAQUE:
                startColor = 0xFF141C28;
                endColor = 0xFF0A0E17;
                strokeColor = 0x33FFFFFF;
                break;
            case AppConfig.SIDEBAR_SEMI_OPAQUE:
                // Enhanced semi-opaque: deep obsidian tint (~88%-90% opacity)
                startColor = 0xE0141C28;
                endColor = 0xE60A0E17;
                strokeColor = 0x2CFFFFFF;
                break;
            default: // SIDEBAR_TRANSLUCENT
                // Enhanced translucent: crisp contrast (~70%-75% opacity)
                startColor = 0xB3141C28;
                endColor = 0xC00A0E17;
                strokeColor = 0x22FFFFFF;
                break;
        }
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { startColor, endColor });
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp2px(20));
        gd.setStroke(dp2px(1), strokeColor);
        setBackground(gd);
    }

    public void show() {
        applyOpacity();
        removeCallbacks(mAutoHideRunnable);
        clearAnimation();
        setAlpha(1f);
        setVisibility(VISIBLE);
        scheduleAutoHide();
    }

    public void hideSmooth() {
        if (getVisibility() != VISIBLE) {
            return;
        }
        AlphaAnimation fade = new AlphaAnimation(1.0f, 0.0f);
        fade.setDuration(300);
        fade.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) {}
            @Override public void onAnimationRepeat(Animation animation) {}
            @Override public void onAnimationEnd(Animation animation) {
                setVisibility(GONE);
            }
        });
        startAnimation(fade);
    }

    public void hide() {
        removeCallbacks(mAutoHideRunnable);
        setVisibility(GONE);
    }

    private void scheduleAutoHide() {
        cancelAutoHide();
        mAutoHidePending = true;
        postDelayed(mAutoHideRunnable, AUTO_HIDE_MS);
    }

    private void cancelAutoHide() {
        mAutoHidePending = false;
        removeCallbacks(mAutoHideRunnable);
    }

    private void onAutoHide() {
        mAutoHidePending = false;
        hideSmooth();
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }
}
