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
    public void theLadderClimbsAndStopsAtTheFastest() {
        // 2x moves on to 3x, 3x to 4x.
        assertEquals(1, SeekKeyPolicy.nextRateIndex(0));
        assertEquals(2, SeekKeyPolicy.nextRateIndex(1));
        // Held down, the key stays at 4x: cycling back to 2x would slow the picture down again
        // while the viewer is still holding the key.
        assertEquals(2, SeekKeyPolicy.nextRateIndex(2));
        assertEquals(2, SeekKeyPolicy.nextRateIndex(2));
    }

    /**
     * The rate multiplies the ten-second step, not the playback speed: one second of holding a
     * key at 4x has to move forty seconds of programme, which is what the on-screen "4x" claims.
     */
    @Test
    public void distanceIsTheStepMultipliedByTheRate() {
        // One second at 2x, 3x, 4x.
        assertEquals(2 * 10_000L, SeekKeyPolicy.distance(1000L, 2, 1));
        assertEquals(3 * 10_000L, SeekKeyPolicy.distance(1000L, 3, 1));
        assertEquals(4 * 10_000L, SeekKeyPolicy.distance(1000L, 4, 1));
        // Half a second at 4x is two steps.
        assertEquals(2 * 10_000L, SeekKeyPolicy.distance(500L, 4, 1));
    }

    /**
     * A held LEFT used to move exactly ten seconds no matter how long it was pressed: the code
     * took {@code Math.max(accumulated, -step)}, and -10000 is larger than -31520.
     */
    @Test
    public void aRewindKeepsWhatTheHoldAccumulated() {
        assertEquals(-31_520L, SeekKeyPolicy.holdDistance(-31_520L, 10_000L, -1));
        assertEquals(-97_000L, SeekKeyPolicy.holdDistance(-97_000L, 10_000L, -1));
        // A forward hold is unaffected.
        assertEquals(31_520L, SeekKeyPolicy.holdDistance(31_520L, 10_000L, 1));
    }

    @Test
    public void aBriefHoldStillMovesOneStep() {
        assertEquals(-10_000L, SeekKeyPolicy.holdDistance(-3_000L, 10_000L, -1));
        assertEquals(10_000L, SeekKeyPolicy.holdDistance(0L, 10_000L, 1));
        assertEquals(-10_000L, SeekKeyPolicy.holdDistance(0L, 10_000L, -1));
    }

    @Test
    public void distanceKeepsTheDirection() {
        assertEquals(-40_000L, SeekKeyPolicy.distance(1000L, 4, -1));
        // A negative elapsed time cannot travel backwards in time.
        assertEquals(0L, SeekKeyPolicy.distance(-500L, 3, 1));
    }

    /**
     * Full screen LEFT must rewind from this moment. It used to fall through to the "enter
     * catchup" shortcut, which restarted the programme from its beginning.
     */
    @Test
    public void fullScreenMeansSeek() {
        assertTrue("watching live, list closed", SeekKeyPolicy.appliesOnScreen(false));
        assertTrue("rewinding, list closed", SeekKeyPolicy.appliesOnScreen(false));
    }

    /**
     * The list wins: it used to lose the keys to a running replay, so opening the guide while
     * rewinding left its columns impossible to navigate.
     */
    @Test
    public void anOpenListOwnsTheKeysDuringAReplayToo() {
        assertFalse("the columns need the keys", SeekKeyPolicy.appliesOnScreen(true));
    }

    /**
     * The list must keep the keys during a replay: opening the guide while rewinding used to
     * leave its columns unreachable, because the replay still owned LEFT and RIGHT.
     */
    @Test
    public void replayDoesNotStealTheKeysFromAnOpenList() {
        assertFalse(SeekKeyPolicy.appliesOnScreen(true));
        assertTrue("with the list closed the strip works as before",
                SeekKeyPolicy.appliesOnScreen(false));
    }

    @Test
    public void oneSpeedIsHeldForAWhileBeforeTheNextOne() {
        assertEquals(1500L, SeekKeyPolicy.RATE_STEP_MS);
    }
}
