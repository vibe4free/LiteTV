package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cabletv.player.epg.EpgManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The left edge of a replay window.
 *
 * <p>It used to be the start of the programme on air, which made rewinding stop dead at the
 * programme boundary: the viewer stepping back had no way to reach what aired before it.
 */
public class CatchupWindowStartTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long MAX_REWIND = 8 * HOUR;

    private static EpgManager.Program program(String title, long startMinute, long endMinute) {
        return new EpgManager.Program(title, startMinute * MINUTE, endMinute * MINUTE);
    }

    @Test
    public void theWindowReachesBackPastTheProgrammeOnAir() {
        long now = 600 * MINUTE;         // 10:00
        long target = 595 * MINUTE;      // 09:55, inside the programme that started at 09:50
        List<EpgManager.Program> programs = Arrays.asList(
                program("Morning news", 540, 590),   // 09:00-09:50, the programme before
                program("Film", 590, 640));          // 09:50-10:40, on air

        long start = MainActivity.catchupWindowStart(programs, target, MAX_REWIND);

        assertEquals("the window has to hold the programme before this one",
                540 * MINUTE, start);
    }

    @Test
    public void theWindowStopsAtTheOldestProgrammeTheGuideStillHolds() {
        long now = 600 * MINUTE;
        List<EpgManager.Program> programs = Arrays.asList(
                program("A", 500, 540),
                program("B", 540, 590),
                program("C", 590, 640));

        long start = MainActivity.catchupWindowStart(programs, 595 * MINUTE, MAX_REWIND);

        assertEquals(500 * MINUTE, start);
    }

    @Test
    public void theWindowNeverReachesBeyondTheLimit() {
        long now = 600 * MINUTE;
        List<EpgManager.Program> programs = Arrays.asList(
                program("Very old", 10, 100),        // far outside the limit
                program("Recent", 540, 590));

        long start = MainActivity.catchupWindowStart(programs, 595 * MINUTE, MAX_REWIND);

        // 10:00 minus 8 hours is 02:00; the ancient programme is ignored, and the recent one
        // (09:00) is inside the limit so it wins.
        assertEquals(540 * MINUTE, start);
        assertTrue("nothing older than the limit may be offered", start >= now - MAX_REWIND);
    }

    @Test
    public void programmesEntirelyBeforeTheLimitAreIgnored() {
        long now = 600 * MINUTE;
        List<EpgManager.Program> programs = new ArrayList<>();
        programs.add(program("Yesterday", 0, 60));    // ends 7.9h before the playhead

        long start = MainActivity.catchupWindowStart(programs, 595 * MINUTE, MAX_REWIND);

        assertEquals("no guide data inside the window, so fall back to a fixed lookback",
                595 * MINUTE - 2 * HOUR, start);
    }

    @Test
    public void withoutAGuideTheWindowIsAFixedLookback() {
        long now = 600 * MINUTE;
        long target = 590 * MINUTE;

        long start = MainActivity.catchupWindowStart(null, target, MAX_REWIND);

        assertEquals(target - 2 * HOUR, start);
    }

    /**
     * Picking a programme from the guide is an explicit request for that moment. A window that
     * starts after it leaves the thumb pinned to the left edge and the strip naming the wrong
     * programme -- and the replay then plays something the bar does not describe.
     */
    @Test
    public void theWindowNeverStartsAfterThePlayhead() {
        long now = 600 * MINUTE;                 // 10:00
        long target = 120 * MINUTE;              // 02:00, far outside the three hour limit
        long maxRewind = 3 * HOUR;
        List<EpgManager.Program> programs = Arrays.asList(
                program("Late night", 100, 130),
                program("Morning", 540, 590));

        long start = MainActivity.catchupWindowStart(programs, target, maxRewind);

        assertTrue("the window must contain the chosen moment: start=" + start,
                start <= target);
    }

    @Test
    public void anExplicitlyChosenOldProgrammeKeepsItsOwnNeighbour() {
        long now = 600 * MINUTE;
        long target = 120 * MINUTE;
        List<EpgManager.Program> programs = Arrays.asList(
                program("Before it", 60, 120),
                program("Chosen", 120, 150));

        long start = MainActivity.catchupWindowStart(programs, target, 3 * HOUR);

        assertEquals("the programme before the chosen one is still reachable",
                60 * MINUTE, start);
    }

    @Test
    public void thePlayheadItSelfIsNeverInsideTheWindowAfterIt() {
        long now = 600 * MINUTE;
        long target = 300 * MINUTE;   // deep in the past

        long start = MainActivity.catchupWindowStart(null, target, MAX_REWIND);

        assertTrue("the window must not start after the playhead", start <= target);
    }
}
