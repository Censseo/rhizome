package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockHeader;
import rhizome.crypto.Crypto;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PowCosts;
import rhizome.crypto.SHA256Hash;

/**
 * Pins the shared PoW contract between {@link Miner} and {@link Crypto} (nit: the miner loop
 * duplicates the preimage layout {@code target‖nonce} and the Pufferfish/SHA-256 choice next to
 * {@code Crypto.concatHashes}, so the two could drift). Every nonce the mining loop yields —
 * under BOTH algorithms and from several starting points — must pass the very verification a
 * node runs on a header ({@code verifyHash} via {@link BlockHeader#verifyNonce}).
 */
class MinerTest {

    /** Low difficulty so the test mines quickly under both algorithms. */
    private static final int DIFFICULTY = 6;

    @Test
    void minedNoncesVerifyUnderSha256() {
        for (int round = 0; round < 3; round++) {
            SHA256Hash target = SHA256Hash.random();
            SHA256Hash nonce = Miner.mineNonce(target, DIFFICULTY, PowAlgorithm.SHA256);
            // The same call verifyNonce makes for a SHA256 network: concatHashes without cache.
            assertTrue(Crypto.verifyHash(target, nonce, DIFFICULTY, false, false),
                "a mined SHA256 nonce must pass verifyHash");
        }
    }

    @Test
    void minedNoncesVerifyUnderPufferfish2() {
        for (int round = 0; round < 2; round++) {
            SHA256Hash target = SHA256Hash.random();
            SHA256Hash nonce = Miner.mineNonce(target, DIFFICULTY, PowAlgorithm.PUFFERFISH2, PowCosts.DEFAULT);
            assertTrue(Crypto.verifyHash(target, nonce, DIFFICULTY, true, false, PowCosts.DEFAULT),
                "a mined Pufferfish2 nonce must pass verifyHash under the same costs");
        }
    }

    @Test
    void minedNoncesVerifyThroughTheHeaderPath() {
        // The full header-level predicate a consensus node runs: BlockHeader.verifyNonce folds
        // hash() + verifyHash together. A header carrying the mined nonce must validate.
        for (PowAlgorithm algorithm : new PowAlgorithm[] {PowAlgorithm.SHA256, PowAlgorithm.PUFFERFISH2}) {
            var header = new BlockHeader(2, 1_000L, DIFFICULTY, 1,
                SHA256Hash.random(), SHA256Hash.random(), SHA256Hash.empty(), SHA256Hash.empty(),
                0, java.util.List.of());
            SHA256Hash nonce = Miner.mineNonce(header.hash(), DIFFICULTY, algorithm, PowCosts.DEFAULT);
            var mined = new BlockHeader(2, 1_000L, DIFFICULTY, 1,
                header.lastBlockHash(), header.merkleRoot(), nonce, SHA256Hash.empty(),
                0, java.util.List.of());
            assertTrue(mined.verifyNonce(algorithm, PowCosts.DEFAULT),
                "header with a mined nonce must verify under " + algorithm);
        }
    }

    @Test
    void minerAndVerifierAgreeOnThePreimageLayout() {
        // Direct layout check: rebuild the miner's preimage by hand (target‖nonce) and confirm
        // the verifier's concatHashes produces the identical digest — any drift in field order
        // or length on either side breaks this equality.
        SHA256Hash target = SHA256Hash.random();
        SHA256Hash nonce = Miner.mineNonce(target, DIFFICULTY, PowAlgorithm.SHA256);
        byte[] preimage = new byte[64];
        System.arraycopy(target.raw(), 0, preimage, 0, 32);
        System.arraycopy(nonce.raw(), 0, preimage, 32, 32);
        assertTrue(Crypto.checkLeadingZeroBits(Crypto.SHA256(preimage), DIFFICULTY));
        assertTrue(Crypto.checkLeadingZeroBits(
            Crypto.concatHashes(target, nonce, false, false), DIFFICULTY));
        // Sanity: the verifier must agree with the manual preimage byte-for-byte.
        org.junit.jupiter.api.Assertions.assertEquals(
            Crypto.SHA256(preimage), Crypto.concatHashes(target, nonce, false, false));
    }

    @Test
    void zeroTargetStillMinesAndVerifies() {
        // Edge case: an all-zero target is a legal hash value — the loop must still terminate
        // and the nonce must verify.
        SHA256Hash nonce = Miner.mineNonce(SHA256Hash.empty(), DIFFICULTY, PowAlgorithm.SHA256);
        assertTrue(Crypto.verifyHash(SHA256Hash.empty(), nonce, DIFFICULTY, false, false));
    }
}
