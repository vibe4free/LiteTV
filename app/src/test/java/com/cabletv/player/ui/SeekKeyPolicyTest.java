package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins down the rules behind the LEFT and RIGHT keys while rewinding.
 *
 * <p>The behaviour follows the Apple TV remote: a press moves ten seconds, holding the key
 * accelerates through 2x, 3x and 4x.
 */
public class SeekKeyPolicyTest {

    @Test
    public void singlePressMovesTenSeconds() {
        assertEquals(10_000L, SeekKeyPolicy.STEP_MS);
    }

    @Test
    public void theSpeedLadderIsTwoThreeFour() {
        assertEquals(3, SeekKeyPolicy.RATES.length);
        assertEquals(2, SeekKeyPolicy.RATES[0]);
        assertEquals(3, SeekKeyPolicy.RATES[1]);
        assertEquals(4, SeekKeyPolicy.RATES[2]);
    }

    @Test
    public void theLadderClimbsAndWrapsAround() {
        // 2x moves on to 3x, 3x to 4x.
        assertEquals(1, SeekKeyPolicy.nextRateIndex(0));
        assertEquals(2, SeekKeyPolicy.nextRateIndex(1));
        // After 4x the ladder starts over at 2x.
        assertEquals(0, SeekKeyPolicy.nextRateIndex(2));
    }

    @Test
    public void rateIsReadFromTheLadder() {
        assertEquals(2, SeekKeyPolicy.rate(0));
        assertEquals(4, SeekKeyPolicy.rate(2));
        // An index beyond the ladder wraps instead of failing.
        assertEquals(2, SeekKeyPolicy.rate(3));
    }

    @Test
    public void distanceGrowsWithTimeAndSpeed() {
        assertEquals(3000L, SeekKeyPolicy.distance(1000L, 3, 1));
        assertEquals(-3000L, SeekKeyPolicy.distance(1000L, 3, -1));
        // A negative elapsed time cannot travel backwards in time.
        assertEquals(0L, SeekKeyPolicy.distance(-500L, 3, 1));
    }

    @Test
    public void theKeysMeanSeekWhileTheBarIsOpenOrACatchupRuns() {
        assertTrue(SeekKeyPolicy.applies(false, true));
        assertTrue(SeekKeyPolicy.applies(true, false));
        assertTrue(SeekKeyPolicy.applies(true, true));
        // Otherwise the keys keep their plain meaning: list columns and day paging.
        assertFalse(SeekKeyPolicy.applies(false, false));
    }

    @Test
    public void oneSpeedIsHeldForAWhileBeforeTheNextOne() {
        assertEquals(1500L, SeekKeyPolicy.RATE_STEP_MS);
    }
}
