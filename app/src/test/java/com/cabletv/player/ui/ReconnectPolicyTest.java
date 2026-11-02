package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The reconnect schedule: short waits first, then longer ones, then give up and say so.
 */
public class ReconnectPolicyTest {

    @Test
    public void waitsGrowWithEachFailure() {
        assertEquals(1_000L, ReconnectPolicy.delayFor(0));
        assertEquals(2_000L, ReconnectPolicy.delayFor(1));
        assertEquals(4_000L, ReconnectPolicy.delayFor(2));
        assertEquals(8_000L, ReconnectPolicy.delayFor(3));
        assertEquals(15_000L, ReconnectPolicy.delayFor(4));
    }

    @Test
    public void theWaitsNeverShrink() {
        long previous = 0;
        for (int attempt = 0; attempt < ReconnectPolicy.maxAttempts(); attempt++) {
            long delay = ReconnectPolicy.delayFor(attempt);
            assertTrue("attempt " + attempt + " waits less than the one before",
                    delay >= previous);
            previous = delay;
        }
    }

    @Test
    public void theAttemptsRunOutAfterFive() {
        assertEquals(5, ReconnectPolicy.maxAttempts());
        assertTrue(ReconnectPolicy.canRetry(4));
        assertFalse("a sixth attempt is not scheduled", ReconnectPolicy.canRetry(5));
        assertEquals("no delay is offered once the schedule is spent",
                -1L, ReconnectPolicy.delayFor(5));
    }

    @Test
    public void nonsenseAttemptNumbersAreTreatedAsSpent() {
        assertFalse(ReconnectPolicy.canRetry(-1));
        assertEquals(-1L, ReconnectPolicy.delayFor(-1));
    }

    @Test
    public void theNumberShownToTheViewerStartsAtOne() {
        assertEquals(1, ReconnectPolicy.attemptNumber(0));
        assertEquals(5, ReconnectPolicy.attemptNumber(4));
    }

    /** Half a minute of patience: long enough for an upstream hiccup, short enough to notice. */
    @Test
    public void theWholeScheduleFitsInAboutHalfAMinute() {
        assertEquals(30_000L, ReconnectPolicy.totalWaitMs());
    }
}
