package com.cabletv.player.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cabletv.player.epg.EpgManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The time-shift track, drawn as a row of programme blocks.
 *
 * <p>The plain seek bar only says "somewhere in this window"; on a television that is not
 * enough to answer the question the viewer is actually asking, which is "what am I about to
 * watch". So the track is drawn with one block per programme from the guide, the block under
 * the playhead picked out in the accent colour, and the live edge marked at the right.
 *
 * <p>The drawing is kept to a single accent colour on a dark ground: a rainbow of programmes
 * reads as noise from three metres away, and the one thing that has to stand out is where the
 * playhead is.
 */
public class ProgramTimelineView extends View {

    /** One programme as the timeline needs it. */
    public static class Segment {
        public final String title;
        public final long startTime;
        public final long endTime;

        public Segment(String title, long startTime, long endTime) {
            this.title = title;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    private static final int TRACK_HEIGHT_DP = 8;
    private static final int PLAYHEAD_WIDTH_DP = 3;
    private static final float SEGMENT_GAP_DP = 1f;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSegmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCurrentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlayheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    /** Told while the viewer drags along the track. */
    public interface OnScrubListener {
        /** A finger went down on the track. */
        void onScrubStart();

        /** The finger is at this moment; show it, but do not chase the stream yet. */
        void onScrubTo(long timeMs);

        /** The finger lifted here: this is the position to play from. */
        void onScrubCommit(long timeMs);
    }

    private final List<Segment> mSegments = new ArrayList<>();
    private OnScrubListener mScrubListener;
    /** True between the finger going down and coming back up. */
    private boolean mScrubbing;
    private long mWindowStartMs;
    private long mWindowEndMs;
    private long mPositionMs;
    private boolean mLiveEdgeVisible;

    public ProgramTimelineView(@NonNull Context context) {
        this(context, null);
    }

    public ProgramTimelineView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mTrackPaint.setColor(Color.parseColor("#1E293B"));
        mSegmentPaint.setColor(Color.parseColor("#263449"));
        mCurrentPaint.setColor(Color.parseColor("#FF6B35"));
        mPlayheadPaint.setColor(Color.WHITE);
        mLivePaint.setColor(Color.parseColor("#FF9966"));
        setClickable(true);
    }

    /** The window the track covers, and where the playhead sits inside it. */
    public void setWindow(long windowStartMs, long windowEndMs, long positionMs) {
        mWindowStartMs = windowStartMs;
        mWindowEndMs = Math.max(windowStartMs + 1000, windowEndMs);
        mPositionMs = positionMs;
        invalidate();
    }

    /**
     * Whether the playhead has run past the last programme the track knows about.
     *
     * <p>Live playback keeps moving while the bar is open, so it eventually crosses into a
     * programme that was not in the guide excerpt used to build the track. That is the signal to
     * ask for a fresh excerpt.
     */
    public boolean isPastLastSegment() {
        if (mSegments.isEmpty()) {
            return false;
        }
        Segment last = mSegments.get(mSegments.size() - 1);
        return mPositionMs > last.endTime;
    }

    /** The programmes currently drawn; the bar reads them to name the one being watched. */
    public List<Segment> getSegments() {
        return mSegments;
    }

    /** The programmes to draw, in the order they are shown on the guide. */
    public void setSegments(List<Segment> segments) {
        mSegments.clear();
        if (segments != null) {
            mSegments.addAll(segments);
        }
        invalidate();
    }

    /** Whether the right end of the track is the live edge. */
    public void setLiveEdgeVisible(boolean visible) {
        mLiveEdgeVisible = visible;
        invalidate();
    }

    /** Moves the playhead without redrawing the whole window. */
    public void setPosition(long positionMs) {
        mPositionMs = positionMs;
        invalidate();
    }

    public void setOnScrubListener(OnScrubListener listener) {
        mScrubListener = listener;
    }

    /**
     * The track is the control.
     *
     * <p>It used to sit under a system seek bar, which drew its own thumb on its own scale: the
     * two markers never lined up because the platform thumb is inset by its own width. Here the
     * marker the viewer sees is the only marker there is, and the touch maths uses the same
     * mapping as the drawing.
     */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (mWindowEndMs <= mWindowStartMs || getWidth() <= 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mScrubbing = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (mScrubListener != null) {
                    mScrubListener.onScrubStart();
                }
                moveToX(event.getX());
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (!mScrubbing) {
                    return false;
                }
                moveToX(event.getX());
                return true;
            case android.view.MotionEvent.ACTION_UP:
                if (!mScrubbing) {
                    return false;
                }
                mScrubbing = false;
                moveToX(event.getX());
                if (mScrubListener != null) {
                    mScrubListener.onScrubCommit(mPositionMs);
                }
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                if (!mScrubbing) {
                    return false;
                }
                mScrubbing = false;
                if (mScrubListener != null) {
                    mScrubListener.onScrubCommit(mPositionMs);
                }
                return true;
            default:
                return false;
        }
    }

    /** Maps a touch to a moment and reports it; the drawing uses the very same mapping. */
    private void moveToX(float x) {
        long span = mWindowEndMs - mWindowStartMs;
        float clamped = Math.max(0f, Math.min(getWidth(), x));
        mPositionMs = Math.max(mWindowStartMs,
                Math.min(mWindowEndMs, mWindowStartMs + (long) (clamped / getWidth() * span)));
        invalidate();
        if (mScrubListener != null) {
            mScrubListener.onScrubTo(mPositionMs);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(dp2px(TRACK_HEIGHT_DP * 3), widthMeasureSpec),
                resolveSize(dp2px(TRACK_HEIGHT_DP * 3), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float trackHeight = dp2px(TRACK_HEIGHT_DP);
        float top = (height - trackHeight) / 2f;
        float gap = dp2px(SEGMENT_GAP_DP);

        // The empty track: what the viewer sees when the guide has nothing to say.
        mRect.set(0, top, width, top + trackHeight);
        canvas.drawRoundRect(mRect, trackHeight / 2f, trackHeight / 2f, mTrackPaint);

        long span = mWindowEndMs - mWindowStartMs;
        if (span <= 0) {
            return;
        }

        // The programmes, each in its own piece of the track.
        for (Segment segment : mSegments) {
            float start = xFor(segment.startTime, span, width);
            float end = xFor(segment.endTime, span, width);
            if (end <= start) {
                // Too short to draw: give it a sliver so it is not lost.
                end = start + 1f;
            }
            if (start > width) {
                break;
            }
            if (end < 0) {
                continue;
            }
            mRect.set(Math.max(0, start), top, Math.min(width, end - gap),
                    top + trackHeight);
            if (mRect.right <= mRect.left) {
                continue;
            }
            // Inclusive at the end: live playback parks the playhead on the window end.
            boolean current = mPositionMs >= segment.startTime && mPositionMs <= segment.endTime;
            canvas.drawRoundRect(mRect, trackHeight / 2f, trackHeight / 2f,
                    current ? mCurrentPaint : mSegmentPaint);
        }

        // The live edge: a bright line at the end of the track, so it is clear where "now" is.
        if (mLiveEdgeVisible) {
            float x = width - dp2px(1.5f);
            mRect.set(x, top - dp2px(3), x + dp2px(3), top + trackHeight + dp2px(3));
            canvas.drawRect(mRect, mLivePaint);
        }

        // The playhead.
        float headX = xFor(mPositionMs, span, width);
        float headWidth = dp2px(PLAYHEAD_WIDTH_DP);
        mRect.set(headX - headWidth / 2f, top - dp2px(4),
                headX + headWidth / 2f, top + trackHeight + dp2px(4));
        canvas.drawRoundRect(mRect, headWidth / 2f, headWidth / 2f, mPlayheadPaint);
    }

    private float xFor(long timeMs, long span, int width) {
        double rel = (double) (timeMs - mWindowStartMs) / span;
        return (float) (rel * width);
    }

    private int dp2px(float dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
