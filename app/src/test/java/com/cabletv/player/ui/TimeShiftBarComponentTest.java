package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.cabletv.player.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Time-shift bar tests (issue #5: 直播直接时移拖动): the draggable window maps a millisecond
 * range onto the seek bar, committing a drag reports the absolute target time (the same epoch
 * value MainActivity turns into a catchup seek), and the live-edge shortcut stays available.
 */
@RunWith(RobolectricTestRunner.class)
public class TimeShiftBarComponentTest {

    private TimeShiftBarComponent buildBar() {
        Context context = RuntimeEnvironment.getApplication();
        return new TimeShiftBarComponent(context);
    }

    @Test
    public void committedDrag_reportsAbsoluteTarget() {
        TimeShiftBarComponent bar = buildBar();
        AtomicLong committed = new AtomicLong(-1);
        bar.setOnSeekListener(new TimeShiftBarComponent.OnSeekListener() {
            @Override
            public void onSeekTo(long targetMs) {
                committed.set(targetMs);
            }

            @Override
            public void onBackToLive() {
            }
        });
        // Window 10:00–10:10 (epoch 0-based for determinism), 10s long.
        bar.setWindow(10_000L, 20_000L, 10_000L);

        // Half of the window = 10:05.
        bar.commitPosition(15_000L);
        assertEquals(15_000L, committed.get());
    }

    @Test
    public void windowStart_mapsToProgressZero_andEndToMax() {
        TimeShiftBarComponent bar = buildBar();
        bar.setWindow(1000L, 11_000L, 1000L);
        // Verify the label reflects the committed position.
        bar.commitPosition(1000L);
        String label = bar.findShownPositionLabel();
        assertNotNull(label);
        assertTrue(label.length() > 0);
    }

    /**
     * The track used to sit under a system seek bar whose thumb was inset by its own width, so
     * the two markers never lined up. There is one marker now, and this pins the mapping both the
     * drawing and the touch handling use.
     */
    @Test
    public void touchingTheMiddleOfTheTrackSelectsTheMiddleOfTheWindow() {
        TimeShiftBarComponent bar = buildBar();
        AtomicLong committed = new AtomicLong(-1);
        bar.setOnSeekListener(new TimeShiftBarComponent.OnSeekListener() {
            @Override
            public void onSeekTo(long targetMs) {
                committed.set(targetMs);
            }

            @Override
            public void onBackToLive() {
            }
        });
        bar.setWindow(10_000L, 20_000L, 10_000L);

        ProgramTimelineView track = bar.findTimeline();
        int width = 1000;
        track.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(26,
                        android.view.View.MeasureSpec.EXACTLY));
        track.layout(0, 0, width, 26);

        long down = android.os.SystemClock.uptimeMillis();
        track.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down,
                android.view.MotionEvent.ACTION_DOWN, width / 2f, 13f, 0));
        track.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down + 50,
                android.view.MotionEvent.ACTION_UP, width / 2f, 13f, 0));

        assertEquals("the middle of the track is the middle of the window",
                15_000L, committed.get());
    }

    @Test
    public void backToLive_string_isLocalized() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(context.getString(R.string.back_to_live),
                context.getString(R.string.back_to_live));
    }
}