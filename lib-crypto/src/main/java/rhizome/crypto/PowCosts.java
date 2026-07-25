package rhizome.crypto;

/**
 * Pufferfish2 proof-of-work cost parameters ({@code cost_t}, {@code cost_m}).
 *
 * <p>The costs are a <b>consensus parameter</b>: two different cost pairs over the same
 * preimage produce different PoW outputs, so every verification path must use the costs
 * in force at the block's height (see {@code NetworkParameters#powCostsAt}). The genesis
 * values remain {@link #DEFAULT} ({@code cost_t=0}, {@code cost_m=8}) so the existing chain
 * re-verifies bit-for-bit; raising them is an opt-in, height-scheduled network upgrade —
 * the memory-hardness upgrade path.
 *
 * <p>Memory use grows as {@code 2^(cost_m + 10)} bytes (four s-boxes of
 * {@code 2^(cost_m+5)} 64-bit entries each), so {@code cost_m} is capped: an unbounded
 * value from misconfigured parameters would OOM every validator.
 *
 * @param costT time cost (number of extra mixing rounds is {@code (1 << costT) + 1})
 * @param costM memory cost (log2 of the s-box size, minus 5)
 */
public record PowCosts(int costT, int costM) {

    /** Smallest accepted memory cost. */
    public static final int MIN_COST_M = 1;
    /** Largest accepted memory cost (~1 GiB of s-boxes) — bounds validator memory. */
    public static final int MAX_COST_M = 20;

    /** The costs in force since genesis: {@code cost_t=0}, {@code cost_m=8}. */
    public static final PowCosts DEFAULT = new PowCosts(PufferfishAlgorithm.COST_T, PufferfishAlgorithm.COST_M);

    public PowCosts {
        if (costT < 0) {
            throw new IllegalArgumentException("costT must be non-negative: " + costT);
        }
        if (costM < MIN_COST_M || costM > MAX_COST_M) {
            throw new IllegalArgumentException(
                "costM out of range [" + MIN_COST_M + ", " + MAX_COST_M + "]: " + costM);
        }
    }
}
