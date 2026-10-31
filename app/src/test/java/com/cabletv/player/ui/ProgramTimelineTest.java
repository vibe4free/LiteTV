package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void anEmptyGuideLeavesTheTrackEmpty() {
        ProgramTimelineView view = new ProgramTimelineView(
                org.robolectric.RuntimeEnvironment.getApplication());

        view.setSegments(null);

        assertTrue("without a guide the track stays empty",
                view.getSegments().isEmpty());
    }
}
