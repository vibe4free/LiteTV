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
     * Whether the keys mean "seek", given what is on screen.
     *
     * <p>The list has priority: while it is open the horizontal keys belong to its columns
     * (switching column, paging the guide by a day), even during a replay. Letting a replay keep
     * the keys took them away from the list the viewer had just opened, which left the guide
     * impossible to navigate while rewinding.
     *
     * <p>Full screen they always seek: LEFT while watching live rewinds from this moment rather
     * than restarting the programme from its beginning.
     */
    static boolean appliesOnScreen(boolean listOpen) {
        return !listOpen;
    }

    /**
     * The speed after the current one.
     *
     * <p>It stops at the fastest rather than starting over: a key that is still held down must
     * keep rewinding at 4x. Cycling back to 2x mid-hold made the picture visibly slow down and
     * speed up again while the viewer was doing nothing.
     */
    static int nextRateIndex(int index) {
        return Math.min(index + 1, RATES.length - 1);
    }

    /**
     * How far a released hold moves the playhead.
     *
     * <p>Everything the hold accumulated, or a single step when the hold was too brief to add up
     * to one. The comparison has to follow the direction: taking the larger of the two values
     * with {@code Math.max} looks right for forward seeks and silently throws away a rewind,
     * which is why a held LEFT used to move ten seconds however long it was pressed.
     */
    static long holdDistance(long accumulatedMs, long stepMs, int direction) {
        if (Math.abs(accumulatedMs) < stepMs) {
            return direction * stepMs;
        }
        return accumulatedMs;
    }

    /**
     * The distance travelled while a key was held.
     *
     * <p>The rate multiplies the <em>step</em>, not the playback speed: at 4x the playhead moves
     * four ten-second steps every second, i.e. forty seconds of programme per second of holding.
     * Treating it as a playback speed instead (four seconds per second) made a held key crawl
     * and never matched the numbers shown on screen.
     *
     * @param elapsedMs how long the key was held
     * @param rate      the speed it was seeking at
     * @param direction -1 for LEFT, 1 for RIGHT
     */
    static long distance(long elapsedMs, int rate, int direction) {
        return Math.max(0, elapsedMs) * rate * STEP_MS / 1000L * direction;
    }
}
