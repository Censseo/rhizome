package rhizome.crypto;

/**
 * Pandanite proof-of-work uses Pufferfish2 with cost parameters {@code cost_t=0}
 * and {@code cost_m=8} and an all-zero salt, which makes it deterministic and
 * therefore verifiable.
 *
 * {@link #compute(byte[])} returns the encoded {@code "$PF2$..."} buffer; the
 * proof-of-work then takes a final SHA-256 over it (see {@link Crypto#PUFFERFISH}).
 */
public final class PufferfishAlgorithm {

    /** Pandanite's fixed Pufferfish2 time cost. */
    public static final int COST_T = 0;
    /** Pandanite's fixed Pufferfish2 memory cost. */
    public static final int COST_M = 8;

    private PufferfishAlgorithm() {}

    /** Computes Pufferfish2 under the genesis costs ({@link PowCosts#DEFAULT}). */
    public static byte[] compute(byte[] input) {
        return compute(input, PowCosts.DEFAULT);
    }

    /** Computes Pufferfish2 under the given consensus cost parameters. */
    public static byte[] compute(byte[] input, PowCosts costs) {
        return Pufferfish2.newHash(input, costs.costT(), costs.costM());
    }
}
