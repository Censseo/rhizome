package rhizome.core.blockchain;

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
        boolean usePufferfish = algorithm == PowAlgorithm.PUFFERFISH2;
        byte[] nonce = new byte[SHA256Hash.SIZE];
        new java.security.SecureRandom().nextBytes(nonce);
        // verifyHash copies the candidate bytes synchronously, so a single wrapper over the
        // mutable buffer avoids one allocation per attempt; clone only the winning nonce.
        SHA256Hash candidate = SHA256Hash.of(nonce);
        while (true) {
            if (Crypto.verifyHash(target, candidate, difficulty, usePufferfish, false, costs)) {
                return SHA256Hash.of(nonce.clone());
            }
            for (int i = nonce.length - 1; i >= 0; i--) {
                if (++nonce[i] != 0) {
                    break;
                }
            }
        }
    }
}
