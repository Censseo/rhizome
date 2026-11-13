package rhizome.core.blockchain;

import java.util.Optional;
import java.util.function.BooleanSupplier;

import rhizome.crypto.Crypto;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PowCosts;
import rhizome.crypto.SHA256Hash;

/**
 * Minimal proof-of-work solver: finds a nonce for a block-header hash under the
 * chain's PoW algorithm. Each call starts from a random nonce: counting up from
 * zero makes every miner walk the same sequence, so the fastest worker wins every
 * block at equal difficulty — centralising (audit B-1).
 */
public final class Miner {

    private Miner() {}

    public static SHA256Hash mineNonce(SHA256Hash target, int difficulty, PowAlgorithm algorithm) {
        return mineNonce(target, difficulty, algorithm, PowCosts.DEFAULT);
    }

    /**
     * Mines under the PoW cost parameters in force at the height being mined (see
     * {@code NetworkParameters#powCostsAt}): across a scheduled cost upgrade, a nonce
     * found under the wrong costs will not verify.
     */
    public static SHA256Hash mineNonce(SHA256Hash target, int difficulty, PowAlgorithm algorithm, PowCosts costs) {
        return mineNonce(target, difficulty, algorithm, costs, () -> false).orElseThrow();
    }

    /**
     * As above, but polls {@code stale} between attempts (every attempt under Pufferfish2, every
     * 256th under SHA-256) and returns empty the moment it holds: a candidate whose parent is no
     * longer the tip can only ever become an orphan, so grinding on it burns the whole interval
     * while the chain moves on without us (the old loop never returned until solved).
     */
    public static Optional<SHA256Hash> mineNonce(SHA256Hash target, int difficulty, PowAlgorithm algorithm,
                                                 PowCosts costs, BooleanSupplier stale) {
        boolean usePufferfish = algorithm == PowAlgorithm.PUFFERFISH2;
        byte[] nonce = new byte[SHA256Hash.SIZE];
        new java.security.SecureRandom().nextBytes(nonce);
        // Build the (target ‖ nonce) preimage once and refresh only the nonce half per attempt.
        // SHA256Hash now defensively copies on construction and on access (audit), so the old
        // single-wrapper-over-the-mutable-buffer trick would snapshot the nonce and never see
        // the increments; driving the raw hash functions off the byte buffer keeps the hot loop
        // allocation-lean without relying on aliasing.
        byte[] data = new byte[64];
        // raw(): the target half is arraycopy'd into the preimage once here, never retained.
        System.arraycopy(target.raw(), 0, data, 0, 32);
        int pollMask = usePufferfish ? 0 : 255;
        for (long attempt = 0; ; attempt++) {
            if ((attempt & pollMask) == 0 && stale.getAsBoolean()) {
                return Optional.empty();
            }
            System.arraycopy(nonce, 0, data, 32, 32);
            SHA256Hash fullHash = usePufferfish
                ? Crypto.PUFFERFISH(data, false, costs)
                : Crypto.SHA256(data);
            if (Crypto.checkLeadingZeroBits(fullHash, difficulty)) {
                return Optional.of(SHA256Hash.of(nonce));
            }
            for (int i = nonce.length - 1; i >= 0; i--) {
                if (++nonce[i] != 0) {
                    break;
                }
            }
        }
    }
}
