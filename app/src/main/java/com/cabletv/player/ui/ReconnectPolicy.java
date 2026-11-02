package com.cabletv.player.ui;

/**
 * How long to wait before trying a dropped stream again.
 *
 * <p>Kept apart from the activity so the schedule can be stated plainly and checked by tests:
 * a live stream that dies usually comes back, but hammering a dead source keeps it dead, and
 * waiting forever leaves the viewer staring at a frozen picture. Five attempts spread over
 * about half a minute covers a short upstream hiccup without pretending to be patient enough for
 * an outage.
 */
final class ReconnectPolicy {

    /** Waits, in order; the length of this list is also the attempt limit. */
    static final long[] DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 15_000};

    private ReconnectPolicy() {
    }

    /** How many attempts are made before the player gives up and says so. */
    static int maxAttempts() {
        return DELAYS_MS.length;
    }

    /** Whether another attempt is allowed after {@code attemptsMade} failures. */
    static boolean canRetry(int attemptsMade) {
        return attemptsMade >= 0 && attemptsMade < DELAYS_MS.length;
    }

    /**
     * The wait before the next attempt.
     *
     * @param attemptsMade how many attempts have already failed (0 for the first retry)
     * @return the delay in milliseconds, or -1 when no attempt remains
     */
    static long delayFor(int attemptsMade) {
        return canRetry(attemptsMade) ? DELAYS_MS[attemptsMade] : -1L;
    }

    /** The 1-based number shown to the viewer ("reconnecting, attempt 3 of 5"). */
    static int attemptNumber(int attemptsMade) {
        return attemptsMade + 1;
    }

    /** Total time spent waiting when every attempt is used (useful for release notes). */
    static long totalWaitMs() {
        long total = 0;
        for (long delay : DELAYS_MS) {
            total += delay;
        }
        return total;
    }
}
