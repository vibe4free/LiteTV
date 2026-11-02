package com.cabletv.player.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cabletv.player.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
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
    /**
     * Three rows: the channel and the way back to live, the track, and the programme under the
     * playhead. It sits above the playback strip, so every row it takes is picture the viewer
     * loses -- which is why the window endpoints were dropped from the layout.
     */
    private static final int BAR_HEIGHT_DP = 88;
    private static final int ROW_HEIGHT_DP = 18;
    /** Where the track sits; the seek bar's thumb is lined up on the same centre. */
    private static final int TIMELINE_TOP_DP = 14;
    /** The track's touch area; the drawn bar is thinner and centred inside it. */
    private static final int TIMELINE_HEIGHT_DP = 26;

    private final TextView mChannelLabel;
    private final TextView mBackToLive;
    private final TextView mTargetLabel;
    private final TextView mRateLabel;
    private final TextView mProgramTitle;
    private final TextView mBehindLiveLabel;
    private final ProgramTimelineView mTimeline;
    private OnSeekListener mSeekListener;
    private static final long AUTO_HIDE_MS = 6_000;
    private final Runnable mAutoHideRunnable = this::onAutoHide;
    /**
     * Whether a hide is waiting to happen. Tracked explicitly: a view that is not attached to a
     * window queues its posts instead of handing them to a handler, so there is nothing to ask.
     */
    private boolean mAutoHidePending;
    /** True while the rate/distance reading occupies the corner the distance to live uses. */
    private boolean mRateVisible;

    private long mWindowStartMs = 0;
    private long mWindowEndMs = 0;
    private long mPositionMs = 0;
    /** Where "now" is; the distance to live is measured from here, not from the window end. */
    private long mLivePointMs = 0;
    /**
     * True while the playhead follows the broadcast. In catchup it must not: the ticker would
     * drag the thumb back to "now" one second after every seek, which looks exactly like the
     * remote key doing nothing.
     */
    private boolean mLiveMode = true;
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
        addView(header, barRowParams(ROW_HEIGHT_DP, 0));

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

        // Where the playhead is, in the first row: the programme row below is kept for the one
        // thing the viewer is looking for there, the name of what is playing.
        mTargetLabel = new TextView(context);
        mTargetLabel.setTextColor(0xFFFF9966);
        mTargetLabel.setTextSize(11);
        mTargetLabel.setPadding(0, 0, dp2px(8), 0);
        header.addView(mTargetLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        header.addView(mBackToLive, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mRateLabel = new TextView(context);
        mRateLabel.setTextColor(0xFFFF9966);
        mRateLabel.setTextSize(12);
        mRateLabel.setGravity(Gravity.END);
        mRateLabel.setMaxLines(1);
        mRateLabel.setVisibility(GONE);
        // Same corner as the distance to live, which it replaces while a key is held: two
        // readings on top of each other were both unreadable, and a fixed 56dp box cut the
        // longer "4x · -133s" off.
        FrameLayout.LayoutParams rateLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp2px(ROW_HEIGHT_DP),
                Gravity.END | Gravity.TOP);
        rateLp.topMargin = dp2px(TIMELINE_TOP_DP + TIMELINE_HEIGHT_DP + 2);
        addView(mRateLabel, rateLp);

        // The timeline is both the picture of the guide and the control: one track, one marker.
        // It used to be drawn under a system seek bar, which put a second thumb on a different
        // scale -- the two markers never lined up.
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
                updateLabels();
            }

            @Override
            public void onScrubCommit(long timeMs) {
                mDragging = false;
                commitPosition(timeMs);
                scheduleAutoHide();
            }
        });
        addView(mTimeline, barRowParams(TIMELINE_HEIGHT_DP, TIMELINE_TOP_DP));

        // The programme that is playing, named under the track.
        mProgramTitle = new TextView(context);
        mProgramTitle.setTextColor(0xFFFFFFFF);
        mProgramTitle.setTextSize(13);
        mProgramTitle.setMaxLines(1);
        mProgramTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp2px(ROW_HEIGHT_DP),
                Gravity.START | Gravity.TOP);
        titleLp.topMargin = dp2px(TIMELINE_TOP_DP + TIMELINE_HEIGHT_DP + 2);
        titleLp.rightMargin = dp2px(96);
        addView(mProgramTitle, titleLp);

        // How far behind the live edge the playhead is.
        mBehindLiveLabel = new TextView(context);
        mBehindLiveLabel.setTextColor(0xFF94A3B8);
        mBehindLiveLabel.setTextSize(11);
        // Right-aligned in the programme row, opposite the programme name.
        FrameLayout.LayoutParams behindLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp2px(ROW_HEIGHT_DP),
                Gravity.END | Gravity.TOP);
        behindLp.topMargin = dp2px(TIMELINE_TOP_DP + TIMELINE_HEIGHT_DP + 2);
        addView(mBehindLiveLabel, behindLp);

    }

    /**
     * Keeps the strip on screen while the viewer is seeking with the remote.
     *
     * <p>It normally fades away after six seconds; during a seek the rate indicator has to stay
     * visible, or the viewer is told nothing about what the key is doing.
     */
    public void showWhileSeeking() {
        cancelAutoHide();
        clearAnimation();
        // The fade-out leaves the alpha at zero, so the bar would stay invisible if only the
        // visibility were restored.
        setAlpha(1f);
        setVisibility(VISIBLE);
    }

    /**
     * Ends a seek: the rate indicator goes away and the strip starts counting down to hiding
     * again. Without this it stayed on screen for the rest of the programme, because
     * {@link #showWhileSeeking()} had cancelled the only thing that would ever hide it.
     */
    public void endSeeking() {
        hideSeekRate();
        scheduleAutoHide();
    }

    /**
     * Shows the acceleration rate while the remote is held down (2x, 3x, 4x), the way the
     * Apple TV remote reports the speed it is rewinding at.
     */
    public void showSeekRate(int rate) {
        if (mRateLabel == null) {
            return;
        }
        mRateVisible = true;
        mBehindLiveLabel.setVisibility(GONE);
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
        mRateVisible = true;
        mBehindLiveLabel.setVisibility(GONE);
        long seconds = Math.abs(deltaMs) / 1000;
        mRateLabel.setText(rate + "x · " + (deltaMs < 0 ? "-" : "+") + seconds + "s");
        mRateLabel.setVisibility(VISIBLE);
    }

    /** Hides the rate indicator when the key is released. */
    public void hideSeekRate() {
        mRateVisible = false;
        if (mRateLabel != null) {
            mRateLabel.setVisibility(GONE);
        }
        updateBehindLive();
    }

    /** The drawn track, so tests can drive its touch handling (used by tests). */
    ProgramTimelineView findTimeline() {
        return mTimeline;
    }

    /** The HH:mm label currently shown for the playhead (used by tests). */
    String findShownPositionLabel() {
        return mTargetLabel.getText().toString();
    }

    /** The playhead in epoch millis (used by tests). */
    long findShownPositionMs() {
        return mPositionMs;
    }

    /** How far behind live the strip currently reports (used by tests). */
    long findBehindLiveMs() {
        return mLivePointMs > 0 ? mLivePointMs - mPositionMs : 0;
    }

    /** Whether a hide is scheduled (used by tests). */
    boolean hasPendingAutoHide() {
        return mAutoHidePending;
    }

    /**
     * @return layout params that anchor this bar just above the playback info strip, or flat
     *         against the bottom edge on a screen too short for both.
     */
    public FrameLayout.LayoutParams createBottomLayoutParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp2px(BAR_HEIGHT_DP));
        lp.gravity = Gravity.BOTTOM;
        lp.bottomMargin = dp2px(bottomMarginDp(screenHeightDp()));
        return lp;
    }

    /** How much room the strip above the playback info strip needs, in dp. */
    private static final int INFO_BAR_HEIGHT_DP = 100;
    /** Space left under the bar when it has to sit on top of the info strip. */
    private static final int COMPACT_MARGIN_DP = 8;
    /** A little air so the two strips never touch. */
    private static final int MIN_GAP_DP = 24;

    private int screenHeightDp() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        return m.heightPixels == 0 ? 0 : Math.round(m.heightPixels / m.density);
    }

    /**
     * Where the bar sits from the bottom edge.
     *
     * <p>Normally it clears the info strip. On a short screen (a phone held sideways) the two
     * strips together are taller than the display, and the old fixed 100dp margin pushed the
     * whole bar off the top: the viewer tapped the picture and no progress control appeared.
     * There the bar wins and covers the info strip -- moving the playhead matters more.
     */
    static int bottomMarginDp(int screenHeightDp) {
        int needed = BAR_HEIGHT_DP + INFO_BAR_HEIGHT_DP + MIN_GAP_DP;
        return screenHeightDp >= needed ? INFO_BAR_HEIGHT_DP : COMPACT_MARGIN_DP;
    }

    public void setOnSeekListener(@Nullable OnSeekListener listener) {
        mSeekListener = listener;
    }

    /** Set the draggable window (in epoch millis) and where the playhead currently sits. */
    public void setWindow(long windowStartMs, long windowEndMs, long positionMs) {
        mWindowStartMs = windowStartMs;
        mWindowEndMs = Math.max(windowStartMs + 1000, windowEndMs);
        mPositionMs = Math.max(windowStartMs, Math.min(mWindowEndMs, positionMs));
        mTimeline.setWindow(mWindowStartMs, mWindowEndMs, mPositionMs);
        updateLabels();
    }

    /** Whether the playhead has moved past the last programme the track knows about. */
    public boolean isPastLastProgramme() {
        return mTimeline.isPastLastSegment();
    }

    /**
     * The programmes the timeline draws, taken from the guide.
     *
     * <p>Without a guide the track stays empty; the bar keeps working exactly as before.
     */
    public void setPrograms(List<ProgramTimelineView.Segment> segments) {
        mTimeline.setSegments(segments);
        updateProgramTitle();
    }

    /** Marks the right end of the track as the live edge. */
    public void setLiveEdgeVisible(boolean visible) {
        mTimeline.setLiveEdgeVisible(visible);
    }

    /** The programme under the playhead, named above the track. */
    private void updateProgramTitle() {
        String title = null;
        for (ProgramTimelineView.Segment segment : mTimeline.getSegments()) {
            // Inclusive at the end: while watching live the playhead sits exactly on the window
            // end, and an exclusive comparison left the programme unnamed.
            if (mPositionMs >= segment.startTime && mPositionMs <= segment.endTime) {
                title = segment.title;
                break;
            }
        }
        if (title == null || title.isEmpty()) {
            mProgramTitle.setVisibility(GONE);
            return;
        }
        mProgramTitle.setVisibility(VISIBLE);
        mProgramTitle.setText(title);
    }

    /** How far behind the live edge the playhead is, in the compact form "1:23:45". */
    static String formatBehind(long behindMs) {
        if (behindMs <= 0) {
            return "0:00";
        }
        long total = behindMs / 1000;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    /**
     * Commits a playhead position: puts the marker there and reports it.
     *
     * <p>The single way a position is committed, whether the viewer dragged the track or a test
     * asks for one, so both follow the same rules.
     */
    void commitPosition(long timeMs) {
        mPositionMs = Math.max(mWindowStartMs, Math.min(mWindowEndMs, timeMs));
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
    }

    private void updateLabels() {
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        mTargetLabel.setText(getContext().getString(R.string.timeshift_position,
                timeFmt.format(new Date(mPositionMs))));
        mTimeline.setPosition(mPositionMs);
        updateProgramTitle();
        // The distance to live is measured from "now". The window end carries a long catchup
        // tail, so using it said "8 hours behind live" while the playhead sat at the live edge.
        updateBehindLive();
    }

    /** The distance to live, unless a held key is using that corner for its own reading. */
    private void updateBehindLive() {
        if (mRateVisible) {
            mBehindLiveLabel.setVisibility(GONE);
            return;
        }
        long behind = mLivePointMs > 0 ? mLivePointMs - mPositionMs : 0;
        // Under a couple of seconds there is nothing to report: while watching live the figure
        // would just flicker between 0 and 1.
        if (behind > 2000) {
            mBehindLiveLabel.setVisibility(VISIBLE);
            mBehindLiveLabel.setText(getContext().getString(R.string.timeshift_behind_live,
                    formatBehind(behind)));
        } else {
            mBehindLiveLabel.setVisibility(GONE);
        }
    }

    /**
     * Keeps the thumb advancing with real time, and only that.
     *
     * <p>While a catchup is running the playhead belongs to the viewer, so the ticker leaves it
     * alone. It still refreshes the distance to live, which does keep growing.
     */
    public void tickIfIdle() {
        tickIfIdle(System.currentTimeMillis());
    }

    /** The tick, with the clock handed in so tests can let time pass deterministically. */
    void tickIfIdle(long now) {
        if (mDragging || getVisibility() != VISIBLE) {
            return;
        }
        mLivePointMs = now;
        if (mLiveMode) {
            // Live playback hands the bar a window ending at "now", so the end has to move with
            // the clock. Holding it still froze the thumb on the moment the bar was opened, while
            // the distance to live kept growing -- the two readings contradicted each other.
            mWindowEndMs = Math.max(mWindowEndMs, now);
            mPositionMs = now;
            mTimeline.setWindow(mWindowStartMs, mWindowEndMs, mPositionMs);
        }
        updateLabels();
    }

    /**
     * Tells the strip whether the playhead follows the broadcast or a replay.
     *
     * @param liveMode true while watching live, false during a catchup
     * @param livePointMs where "now" is, used for the distance to live
     */
    public void setLiveMode(boolean liveMode, long livePointMs) {
        mLiveMode = liveMode;
        mLivePointMs = livePointMs;
        mTimeline.setLiveEdgeVisible(liveMode);
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

    /** A row of the strip: full width, fixed height, placed down from the top edge. */
    private FrameLayout.LayoutParams barRowParams(int heightDp, int topMarginDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp2px(heightDp));
        lp.gravity = Gravity.TOP;
        lp.topMargin = dp2px(topMarginDp);
        return lp;
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }
}