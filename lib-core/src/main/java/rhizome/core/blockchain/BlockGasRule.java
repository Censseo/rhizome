package rhizome.core.blockchain;

/**
 * The block-wide declared-gas ceiling, as one expression shared by the executor (validation) and
 * the mempool (selection). The two used to disagree: only validation enforced it, so a pool of
 * max-gas calls made every honest miner assemble a block every node rejected, forever.
 */
public final class BlockGasRule {

    /** Returned by {@link #add} when the new total would exceed the cap (or overflow). */
    public static final long EXCEEDED = -1L;

    private BlockGasRule() {
    }

    /**
     * The block's declared-gas total after adding {@code gasLimit} to {@code blockGas}, or
     * {@link #EXCEEDED}. A cap of 0 disables the rule.
     */
    public static long add(NetworkParameters params, long blockGas, long gasLimit) {
        if (params.maxBlockGas() <= 0) {
            return 0;
        }
        try {
            long total = Math.addExact(blockGas, gasLimit);
            return total > params.maxBlockGas() ? EXCEEDED : total;
        } catch (ArithmeticException overflow) {
            return EXCEEDED; // an unbounded-gasLimit sum can only be over the cap
        }
    }
}
