package com.cabletv.player.ui;

/**
 * The rules behind the LEFT and RIGHT keys while the viewer is rewinding.
 *
 * <p>Kept apart from the activity so the numbers can be pinned down by tests: the step of a
 * single press, the ladder of speeds a held key climbs through, and when the keys mean "seek"
 * at all.
 *
 * <p>The behaviour follows the Apple TV remote: a press moves ten seconds, holding the key
 * accelerates through 2x, 3x and 4x.
 */
final class SeekKeyPolicy {

    /** How far a single press of LEFT or RIGHT moves the playhead. */
    static final long STEP_MS = 10_000L;

    /** The speeds a held key climbs through, in order. */
    static final int[] RATES = {2, 3, 4};

    /** How long one speed is held before the seek speeds up to the next one. */
    static final long RATE_STEP_MS = 1500L;

    /** How often the travelled distance is recounted while the key is held. */
    static final long TICK_MS = 100L;

    private SeekKeyPolicy() {
    }

    /**
     * Whether the horizontal keys currently mean "move the playhead".
     *
     * <p>Yes while the time-shift bar is on screen (the viewer is adjusting the playhead rather
     * than browsing the list) and while a catchup is running. Otherwise the keys keep their
     * plain meaning: switching columns in the list, paging the guide by a day.
     */
    static boolean applies(boolean catchupActive, boolean barVisible) {
        return catchupActive || barVisible;
    }

    /** The speed for a given step of the ladder, wrapping around at the end. */
    static int rate(int index) {
        return RATES[Math.floorMod(index, RATES.length)];
    }

    /** The speed after the current one; the ladder starts over after the last speed. */
    static int nextRateIndex(int index) {
        return (index + 1) % RATES.length;
    }

    /**
     * The distance travelled while a key was held.
     *
     * @param elapsedMs how long the key was held
     * @param rate      the speed it was seeking at
     * @param direction -1 for LEFT, 1 for RIGHT
     */
    static long distance(long elapsedMs, int rate, int direction) {
        return Math.max(0, elapsedMs) * rate * direction;
    }
}
