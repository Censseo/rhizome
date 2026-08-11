package rhizome.core.blockchain;

import java.util.Arrays;
import java.util.function.LongFunction;

import rhizome.core.block.BlockHeader;

/**
 * The difficulty-retarget arithmetic, as pure functions over a header accessor.
 *
 * <p>{@link ChainEngine} and {@link HeaderChain} must retarget identically: the engine folds the
 * chain it mines, the header validator folds the chain a peer offers, and any disagreement means
 * the node rejects, at the first boundary, every chain it would itself produce — the failure
 * {@code HeaderChain} warns about as "PEER_INVALID at the first retarget". They previously kept
 * separate copies of the boundary rule, the median-of-3 bound and the window step, held together
 * by {@code MUST match ... exactly} comments and one test that compared the two implementations.
 *
 * <p>What is NOT shared, deliberately: the memoisation around the fold. The engine caches
 * {@code boundary -> difficulty} and invalidates on pop; the header validator caches
 * {@code boundary -> (difficulty, headerHash)} and self-invalidates when a reorg rewrites a
 * window. Those are different policies over the same arithmetic, so each keeps its own loop and
 * calls {@link #stepWindow} for the step. Sharing the arithmetic is what matters; sharing the
 * cache would force one policy onto both.
 *
 * <p>Every function takes the header accessor as a parameter, which is why this works at all:
 * {@code HeaderChain} was already written against {@code LongFunction<BlockHeader>} over a virtual
 * chain (trusted prefix + candidate suffix), and the engine passes its store's accessor.
 *
 * <p>⚠ Callers inside {@code ChainEngine} must pass {@code store::headerAt}, never
 * {@code this::headerAt}: the public engine accessor takes the engine lock, so routing a fold
 * through it would lock and unlock once per header read.
 */
final class Retarget {

    private Retarget() {
    }

    /**
     * Difficulty after the retarget window closing at {@code boundary}, given the difficulty in
     * force before it. Returns it unchanged when the window holds no completed interval.
     *
     * <p>The genesis interval is excluded from the first window: genesis carries an artificial
     * timestamp (conventionally 0), so measuring from it would read "epoch → first real block" as
     * one block interval and crater the first retarget (audit L2).
     */
    static int stepWindow(NetworkParameters params, LongFunction<BlockHeader> at,
                          int difficulty, long boundary) {
        long windowStart = boundary - params.difficultyLookback() + 1;
        long measureStart = Math.max(windowStart, GenesisBlock.GENESIS_ID + 1);
        long intervals = boundary - measureStart;
        if (intervals <= 0) {
            return difficulty; // not enough real blocks in this window yet
        }
        long observedMs = boundaryTimestamp(params, at, boundary)
            - boundaryTimestamp(params, at, measureStart);
        return DifficultyAdjustment.nextDifficulty(params, difficulty, intervals, observedMs / 1000);
    }

    /**
     * The timestamp a retarget reads at bound height {@code h}: the median-of-3 from
     * {@code consensusV2Height} on, the raw header timestamp below it.
     *
     * <p>The decision height is the BOUNDARY the retarget closes at, and both bounds of one window
     * use the same rule, so a chain never sees one bound on each side of the activation.
     */
    static long boundaryTimestamp(NetworkParameters params, LongFunction<BlockHeader> at, long h) {
        if (params.consensusV2(h)) {
            return medianTimestamp(at, h);
        }
        return at.apply(h).timestamp();
    }

    /**
     * Median of the (up to) 3 header timestamps ending at {@code h} inclusive, clamped at genesis.
     *
     * <p>Measuring a window from two raw timestamps let a miner inflate ONE boundary timestamp and
     * stretch the observed duration, dragging difficulty down at almost no hash cost (audit:
     * timewarp). Against a median-of-3 bound a single manipulated timestamp moves the bound by at
     * most the gap to its neighbour, so steering the retarget needs a sustained multi-block
     * manipulation. The upper-median convention (index {@code size / 2}, as in
     * {@link #medianTimePast}) also keeps the artificial genesis timestamp out of the first
     * window's start bound.
     */
    static long medianTimestamp(LongFunction<BlockHeader> at, long h) {
        long lo = Math.max(GenesisBlock.GENESIS_ID, h - 2);
        int size = (int) (h - lo + 1);
        long[] timestamps = new long[size];
        for (int i = 0; i < size; i++) {
            timestamps[i] = at.apply(h - i).timestamp();
        }
        Arrays.sort(timestamps);
        return timestamps[size / 2];
    }

    /**
     * Median timestamp of the last {@code medianTimeWindow} headers up to {@code tip} inclusive —
     * the floor a block's timestamp must exceed.
     *
     * <p>{@code ChainEngine} does NOT call this on its hot path: it maintains the same median over
     * an incrementally-updated primitive ring, because this scan would re-read the window on every
     * added block (audit P6). That divergence is deliberate and is guarded by
     * {@code MedianTimePastRingTest}, which asserts the ring against this function as the
     * reference. The engine does use it to rebuild the ring at boot.
     */
    static long medianTimePast(NetworkParameters params, LongFunction<BlockHeader> at, long tip) {
        int window = (int) Math.min(params.medianTimeWindow(), tip);
        // Primitive long[] rather than a boxed List<Long>: this runs once per candidate header over
        // a sync window of up to MAX_HEADER_WINDOW, and the per-header boxing plus comparator churn
        // added up measurably.
        long[] timestamps = new long[window];
        int i = 0;
        for (long h = tip - window + 1; h <= tip; h++) {
            timestamps[i++] = at.apply(h).timestamp();
        }
        Arrays.sort(timestamps);
        return timestamps[window / 2];
    }
}
