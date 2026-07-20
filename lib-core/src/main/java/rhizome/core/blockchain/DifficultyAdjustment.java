package rhizome.core.blockchain;

/**
 * Difficulty retargeting for the clean chain.
 *
 * <p>Design constraints, learned from Pandanite's C++ flaws:
 * <ul>
 *   <li><b>Pure and deterministic</b> — a function of the observed window only,
 *       never of mutable engine state. Pandanite kept a stored difficulty that
 *       went stale after {@code popBlock}, which forced a hardcoded exception
 *       for blocks 536100–536200.</li>
 *   <li><b>Integer-only</b> — no floating point anywhere near consensus.</li>
 *   <li><b>Bounded step</b> — at most {@value #MAX_STEP_BITS} bits per retarget,
 *       so timestamp manipulation cannot swing difficulty wildly (Pandanite's
 *       doubling search had an inconsistent cap and {@code abs()} on raw
 *       int32 deltas).</li>
 * </ul>
 *
 * <p>Difficulty is a number of required leading zero bits: +1 bit doubles the
 * expected work. The retarget compares the observed duration of the last
 * {@code difficultyLookback} blocks against the desired duration and shifts by
 * whole bits (each bit is a 2x correction), clamped to the network bounds.
 */
public final class DifficultyAdjustment {

    /** Maximum difficulty change (in bits) per retarget. */
    public static final int MAX_STEP_BITS = 4;

    private DifficultyAdjustment() {}

    /**
     * Returns the difficulty for the next window.
     *
     * @param params           network parameters (desired block time, bounds)
     * @param currentDifficulty difficulty of the closing window
     * @param windowBlocks     number of blocks observed (normally
     *                         {@code params.difficultyLookback()})
     * @param observedSeconds  time the window actually took, from block
     *                         timestamps (clamped to at least 1)
     */
    public static int nextDifficulty(NetworkParameters params, int currentDifficulty,
                                     long windowBlocks, long observedSeconds) {
        if (windowBlocks <= 0) {
            throw new IllegalArgumentException("windowBlocks must be positive");
        }
        long desired = windowBlocks * params.desiredBlockTimeSec();
        long observed = Math.max(1, observedSeconds);

        int next = currentDifficulty;
        int step = 0;

        // Too fast: each halving of observed vs desired earns +1 bit.
        while (step < MAX_STEP_BITS && observed * 2 <= desired) {
            next++;
            observed *= 2;
            step++;
        }
        // Too slow: each doubling of observed vs desired costs -1 bit.
        while (step < MAX_STEP_BITS && observed >= desired * 2) {
            next--;
            desired *= 2;
            step++;
        }

        return Math.clamp(next, params.minDifficulty(), params.maxDifficulty());
    }

    /** True when {@code height} closes a retarget window. */
    public static boolean isRetargetHeight(NetworkParameters params, long height) {
        return height > 0 && height % params.difficultyLookback() == 0;
    }
}
