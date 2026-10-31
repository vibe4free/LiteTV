package com.cabletv.player.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cabletv.player.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The draggable time-shift (时移) seek bar shown over live playback and catchup (回看).
 * The track runs from the start of a fixed window ({@link #setWindow}) to its end; dragging
 * the thumb moves the playhead, and releasing commits the target time. The bar itself knows
 * nothing about catchup mechanics: it only maps a millisecond window to a seek bar and reports
 * a committed target through {@link OnSeekListener}. The "回直播" button jumps the playhead
 * back to the live edge.
 */
public class TimeShiftBarComponent extends FrameLayout {
    private static final int BAR_HEIGHT_DP = 92;

    private final TextView mChannelLabel;
    private final TextView mBackToLive;
    private final TextView mWindowLabel;
    private final TextView mTargetLabel;
    private final TextView mRateLabel;
    private final SeekBar mSeekBar;
    private OnSeekListener mSeekListener;
    private static final long AUTO_HIDE_MS = 6_000;
    private final Runnable mAutoHideRunnable = this::hideSmooth;

    private long mWindowStartMs = 0;
    private long mWindowEndMs = 0;
    private long mPositionMs = 0;
    /** True while the thumb is being dragged; live ticking pauses so the thumb stays put. */
    private boolean mDragging = false;

    public interface OnSeekListener {
        /** The user released the thumb at this absolute epoch time; seek the playhead there. */
        void onSeekTo(long targetMs);

        /** The user asked to jump straight to the live edge (返回直播). */
        void onBackToLive();
    }

    public TimeShiftBarComponent(@NonNull Context context) {
        super(context);
        setBackgroundResource(R.drawable.gradient_playback_info_bg);
        setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8));
        setVisibility(GONE);
        // The whole strip eats its own touches so they never reach the activity gesture layer.
        setClickable(true);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        addView(header, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        mChannelLabel = new TextView(context);
        mChannelLabel.setTextColor(0xFFFFFFFF);
        mChannelLabel.setTextSize(13);
        mChannelLabel.setMaxLines(1);
        header.addView(mChannelLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        mBackToLive = new TextView(context);
        mBackToLive.setText(R.string.back_to_live);
        mBackToLive.setTextColor(0xFFFF9966);
        mBackToLive.setTextSize(12);
        mBackToLive.setPadding(dp2px(8), 0, dp2px(2), 0);
        mBackToLive.setOnClickListener(v -> {
            if (mSeekListener != null) {
                mSeekListener.onBackToLive();
            }
        });
        header.addView(mBackToLive, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mRateLabel = new TextView(context);
        mRateLabel.setTextColor(0xFFFF9966);
        mRateLabel.setTextSize(12);
        mRateLabel.setGravity(Gravity.CENTER);
        mRateLabel.setVisibility(GONE);
        FrameLayout.LayoutParams rateLp = new FrameLayout.LayoutParams(
                dp2px(56), dp2px(22), Gravity.CENTER_VERTICAL | Gravity.END);
        addView(mRateLabel, rateLp);

        mSeekBar = new SeekBar(context);
        mSeekBar.setMax(1000);
        mSeekBar.setProgress(0);
        addView(mSeekBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp2px(34),
                Gravity.CENTER_VERTICAL | Gravity.TOP));

        mWindowLabel = new TextView(context);
        mWindowLabel.setTextColor(0xFFB0B8C4);
        mWindowLabel.setTextSize(11);
        FrameLayout.LayoutParams winLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        winLp.gravity = Gravity.BOTTOM | Gravity.START;
        addView(mWindowLabel, winLp);

        mTargetLabel = new TextView(context);
        mTargetLabel.setTextColor(0xFFFF9966);
        mTargetLabel.setTextSize(11);
        FrameLayout.LayoutParams tgtLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tgtLp.gravity = Gravity.BOTTOM | Gravity.END;
        addView(mTargetLabel, tgtLp);

        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mPositionMs = windowPosition(progress);
                    updateLabels();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mDragging = true;
                cancelAutoHide();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mDragging = false;
                commitProgress(seekBar.getProgress());
                scheduleAutoHide();
            }
        });
    }

    /** Commits a seek bar position: updates labels and reports the absolute target. */
    void commitProgress(int progress) {
        mPositionMs = windowPosition(progress);
        updateLabels();
        if (mSeekListener != null) {
            mSeekListener.onSeekTo(mPositionMs);
        }
    }

    /**
     * Keeps the strip on screen while the viewer is seeking with the remote.
     *
     * <p>It normally fades away after six seconds; during a seek the rate indicator has to stay
     * visible, or the viewer is told nothing about what the key is doing.
     */
    public void showWhileSeeking() {
        removeCallbacks(mAutoHideRunnable);
        clearAnimation();
        // The fade-out leaves the alpha at zero, so the bar would stay invisible if only the
        // visibility were restored.
        setAlpha(1f);
        setVisibility(VISIBLE);
    }

    /**
     * Shows the acceleration rate while the remote is held down (2x, 3x, 4x), the way the
     * Apple TV remote reports the speed it is rewinding at.
     */
    public void showSeekRate(int rate) {
        if (mRateLabel == null) {
            return;
        }
        mRateLabel.setText(rate + "x");
        mRateLabel.setVisibility(VISIBLE);
    }

    /**
     * Shows the rate the key is seeking at and how far it has travelled.
     *
     * <p>Both belong on screen at once: the rate says how fast the playhead moves, the distance
     * says where it is going.
     */
    public void showSeekPreview(int rate, long deltaMs) {
        if (mRateLabel == null) {
            return;
        }
        long seconds = Math.abs(deltaMs) / 1000;
        mRateLabel.setText(rate + "x · " + (deltaMs < 0 ? "-" : "+") + seconds + "s");
        mRateLabel.setVisibility(VISIBLE);
    }

    /** Hides the rate indicator when the key is released. */
    public void hideSeekRate() {
        if (mRateLabel != null) {
            mRateLabel.setVisibility(GONE);
        }
    }

    /** The HH:mm label currently shown for the playhead (used by tests). */
    String findShownPositionLabel() {
        return mTargetLabel.getText().toString();
    }

    /** @return layout params that anchor this bar just above the playback info strip. */
    public FrameLayout.LayoutParams createBottomLayoutParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp2px(BAR_HEIGHT_DP));
        lp.gravity = Gravity.BOTTOM;
        lp.bottomMargin = dp2px(100); // PlaybackInfoComponent is 100dp tall
        return lp;
    }

    private long windowPosition(int progress) {
        if (mWindowEndMs <= mWindowStartMs) {
            return mWindowStartMs;
        }
        return mWindowStartMs + (mWindowEndMs - mWindowStartMs) * progress / 1000L;
    }

    public void setOnSeekListener(@Nullable OnSeekListener listener) {
        mSeekListener = listener;
    }

    /** Set the draggable window (in epoch millis) and where the playhead currently sits. */
    public void setWindow(long windowStartMs, long windowEndMs, long positionMs) {
        mWindowStartMs = windowStartMs;
        mWindowEndMs = Math.max(windowStartMs + 1000, windowEndMs);
        mPositionMs = Math.max(windowStartMs, Math.min(mWindowEndMs, positionMs));
        mSeekBar.setProgress(progressFor(mPositionMs));
        updateLabels();
    }

    public void setChannelLabel(String label) {
        mChannelLabel.setText(label);
    }

    public void setBackToLiveVisible(boolean visible) {
        mBackToLive.setVisibility(visible ? VISIBLE : GONE);
    }

    private int progressFor(long positionMs) {
        long span = mWindowEndMs - mWindowStartMs;
        if (span <= 0) {
            return 0;
        }
        long rel = Math.max(0, Math.min(span, positionMs - mWindowStartMs));
        return (int) Math.round(rel * 1000.0 / span);
    }

    private void updateLabels() {
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        mTargetLabel.setText(getContext().getString(R.string.timeshift_position,
                timeFmt.format(new Date(mPositionMs))));
        mWindowLabel.setText(getContext().getString(R.string.timeshift_window,
                timeFmt.format(new Date(mWindowStartMs)),
                timeFmt.format(new Date(mWindowEndMs))));
    }

    /** Keep the thumb advancing with real time while watching live (only when idle). */
    public void tickIfIdle() {
        if (mDragging || getVisibility() != VISIBLE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > mWindowEndMs) {
            return; // window ended; don't push the thumb past it
        }
        mPositionMs = Math.max(mWindowStartMs, now);
        mSeekBar.setProgress(progressFor(mPositionMs));
        updateLabels();
    }

    public void show() {
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
        postDelayed(mAutoHideRunnable, AUTO_HIDE_MS);
    }

    private void cancelAutoHide() {
        removeCallbacks(mAutoHideRunnable);
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }
}