package rhizome.core.blockchain;

import rhizome.crypto.Crypto;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PowCosts;
import rhizome.crypto.SHA256Hash;

/**
 * Minimal proof-of-work solver: finds a nonce for a block-header hash under the
 * chain's PoW algorithm. Deterministic (counts up from zero), which makes tests
 * reproducible; a production miner would randomise its starting point.
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
        while (true) {
            SHA256Hash candidate = SHA256Hash.of(nonce.clone());
            if (Crypto.verifyHash(target, candidate, difficulty, usePufferfish, false, costs)) {
                return candidate;
            }
            for (int i = nonce.length - 1; i >= 0; i--) {
                if (++nonce[i] != 0) {
                    break;
                }
            }
        }
    }
}
