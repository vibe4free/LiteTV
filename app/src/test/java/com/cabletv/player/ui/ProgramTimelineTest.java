package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

/**
 * The time-shift track: how the guide's programmes are turned into pieces of the track, and how
 * the distance to the live edge is written down.
 */
@RunWith(RobolectricTestRunner.class)
public class ProgramTimelineTest {

    private static final long HOUR = 3_600_000L;

    @Test
    public void theDistanceToTheLiveEdgeIsWrittenAsMinutesAndSeconds() {
        assertEquals("0:00", TimeShiftBarComponent.formatBehind(0));
        assertEquals("0:00", TimeShiftBarComponent.formatBehind(-500));
        assertEquals("0:30", TimeShiftBarComponent.formatBehind(30_000));
        assertEquals("2:05", TimeShiftBarComponent.formatBehind(125_000));
        assertEquals("59:59", TimeShiftBarComponent.formatBehind(59 * 60_000 + 59_000));
    }

    @Test
    public void theDistanceGainsAnHourPartWhenItIsThatLong() {
        assertEquals("1:00:00", TimeShiftBarComponent.formatBehind(HOUR));
        assertEquals("2:03:04", TimeShiftBarComponent.formatBehind(2 * HOUR + 3 * 60_000 + 4_000));
    }

    @Test
    public void aSegmentKnowsWhenItStartsAndEnds() {
        ProgramTimelineView.Segment segment =
                new ProgramTimelineView.Segment("News", 1_000L, 2_000L);

        assertEquals("News", segment.title);
        assertEquals(1_000L, segment.startTime);
        assertEquals(2_000L, segment.endTime);
    }

    @Test
    public void theTimelineKeepsTheProgrammesItWasGiven() {
        ProgramTimelineView view = new ProgramTimelineView(
                org.robolectric.RuntimeEnvironment.getApplication());
        List<ProgramTimelineView.Segment> segments = Arrays.asList(
                new ProgramTimelineView.Segment("One", 0L, 1000L),
                new ProgramTimelineView.Segment("Two", 1000L, 2000L));

        view.setSegments(segments);

        assertEquals(2, view.getSegments().size());
        assertEquals("One", view.getSegments().get(0).title);
        assertEquals("Two", view.getSegments().get(1).title);
    }

    private static TimeShiftBarComponent newBar() {
        return new TimeShiftBarComponent(org.robolectric.RuntimeEnvironment.getApplication());
    }

    /**
     * The ticker used to drag the playhead back to "now" every second, including during a
     * catchup. Every seek was undone a second later, which looked exactly like the remote key
     * doing nothing at all.
     */
    @Test
    public void aReplayDoesNotFollowTheClock() {
        TimeShiftBarComponent bar = newBar();
        long start = 1_700_000_000_000L;
        bar.setWindow(start - 3_600_000L, start + 3_600_000L, start - 600_000L);
        bar.setLiveMode(false, start);
        bar.show();

        bar.tickIfIdle(start + 3_000L);

        assertEquals("the playhead must stay where the viewer put it",
                start - 600_000L, bar.findShownPositionMs());
    }

    @Test
    /**
     * The window live playback builds ends at "now". The thumb used to freeze on that instant and
     * never move again, while the distance-to-live reading kept counting up: the bar claimed the
     * viewer was falling behind the broadcast while they were watching it.
     */
    public void watchingLiveKeepsThePlayheadOnTheClock() {
        TimeShiftBarComponent bar = newBar();
        long start = 1_700_000_000_000L;
        bar.setWindow(start - 3_600_000L, start, start);   // no spare tail: this is what live does
        bar.setLiveMode(true, start);
        bar.show();

        // Three seconds later the thumb must have moved three seconds, not stayed put.
        bar.tickIfIdle(start + 3_000L);

        assertEquals("the thumb follows the broadcast",
                start + 3_000L, bar.findShownPositionMs());
        assertEquals("nothing is being missed while watching live",
                0L, bar.findBehindLiveMs());
    }

    /**
     * The distance to live has to be measured from "now". The window carries a long catchup
     * tail, so measuring from its end said "8 hours behind live" while sitting at the live edge.
     */
    @Test
    public void theDistanceToLiveIsMeasuredFromNow() {
        TimeShiftBarComponent bar = newBar();
        long now = 1_700_000_000_000L;
        bar.setLiveMode(false, now);
        // A window whose end is eight hours of catchup tail away from "now".
        bar.setWindow(now - 600_000L, now + 8 * 3_600_000L, now - 600_000L);

        assertEquals(600_000L, bar.findBehindLiveMs());
    }

    /** The point of the whole thing: ending a seek starts the six second countdown again. */
    @Test
    public void endingASeekLetsTheStripFadeAwayAgain() {
        TimeShiftBarComponent bar = newBar();
        bar.showWhileSeeking();

        bar.endSeeking();

        assertTrue("the strip must count down to hiding after a seek",
                bar.hasPendingAutoHide());
    }

    /**
     * Live playback keeps moving while the bar is open. When it crosses into a programme the
     * track was never told about, the programme name used to vanish and the highlight with it --
     * this is the signal the player uses to ask for a fresh excerpt.
     */
    @Test
    public void theTrackReportsWhenThePlayheadLeavesTheGuideItWasGiven() {
        TimeShiftBarComponent bar = newBar();
        long start = 1_700_000_000_000L;
        long hour = 3_600_000L;
        bar.setWindow(start - hour, start, start);
        bar.setLiveMode(true, start);
        bar.setPrograms(Arrays.asList(
                new ProgramTimelineView.Segment("First", start - hour, start),
                new ProgramTimelineView.Segment("Second", start, start + hour)));
        bar.show();

        bar.tickIfIdle(start - 1000L);
        assertFalse("still inside the excerpt", bar.isPastLastProgramme());

        bar.tickIfIdle(start + 2 * hour);
        assertTrue("two hours on, the excerpt is stale", bar.isPastLastProgramme());
    }

    @Test
    public void anEmptyGuideLeavesTheTrackEmpty() {
        ProgramTimelineView view = new ProgramTimelineView(
                org.robolectric.RuntimeEnvironment.getApplication());

        view.setSegments(null);

        assertTrue("without a guide the track stays empty",
                view.getSegments().isEmpty());
    }
}
