package rhizome.core.blockchain;

import java.math.BigInteger;

/**
 * Precomputed proof-of-work weights: {@code 2^difficulty} for every valid difficulty
 * ({@code [0, 255]}, bounded at header decode). The cumulative-work sums recompute this constant
 * per block on the O(height) boot rebuild, the O(depth) reorg gates and every add/pop; caching the
 * 256 possible values removes that repeated {@link BigInteger#pow} allocation (audit P9).
 *
 * <p>The returned value is byte-for-byte the same {@code BigInteger} {@code BigInteger.TWO.pow(d)}
 * produced, so no consensus quantity changes. BigInteger is immutable, so a shared instance is safe.
 */
final class BlockWork {

    private static final BigInteger[] TABLE = new BigInteger[256];

    static {
        for (int d = 0; d < TABLE.length; d++) {
            TABLE[d] = BigInteger.ONE.shiftLeft(d); // == 2^d
        }
    }

    private BlockWork() {}

    /** {@code 2^difficulty} as a shared immutable BigInteger; falls back to pow if out of the cached range. */
    static BigInteger of(int difficulty) {
        if (difficulty >= 0 && difficulty < TABLE.length) {
            return TABLE[difficulty];
        }
        return BigInteger.TWO.pow(difficulty);
    }
}
