package rhizome.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Locks the PoW cost-parameter value type and the cache separation between cost
 * pairs: two different {@link PowCosts} over the same preimage must never be
 * served the same cached Pufferfish2 result (they are both live around a
 * scheduled cost upgrade).
 */
class PowCostsTest {

    @Test
    void defaultMatchesGenesisCosts() {
        assertEquals(0, PowCosts.DEFAULT.costT());
        assertEquals(8, PowCosts.DEFAULT.costM());
        assertEquals(PufferfishAlgorithm.COST_T, PowCosts.DEFAULT.costT());
        assertEquals(PufferfishAlgorithm.COST_M, PowCosts.DEFAULT.costM());
    }

    @Test
    void rejectsOutOfRangeCosts() {
        assertThrows(IllegalArgumentException.class, () -> new PowCosts(-1, 8));
        assertThrows(IllegalArgumentException.class, () -> new PowCosts(PowCosts.MAX_COST_T + 1, 8));
        assertThrows(IllegalArgumentException.class, () -> new PowCosts(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PowCosts(0, PowCosts.MAX_COST_M + 1));
        // Bounds are accepted.
        new PowCosts(0, PowCosts.MIN_COST_M);
        new PowCosts(3, PowCosts.MAX_COST_M);
        new PowCosts(PowCosts.MAX_COST_T, 8);
    }

    @Test
    void pufferfishEntryPointRejectsOverflowingCostT() {
        // Defense in depth behind the PowCosts constructor: (1 << 31) + 1 would silently
        // collapse the mixing loop instead of throwing.
        assertThrows(IllegalArgumentException.class,
            () -> Pufferfish2.newHash(new byte[8], PowCosts.MAX_COST_T + 1, 8));
        assertThrows(IllegalArgumentException.class,
            () -> Pufferfish2.newHash(new byte[8], -1, 8));
    }

    @Test
    void pufferfishEntryPointRejectsOutOfRangeCostM() {
        // Same defense in depth for the memory cost: 4 * 2^(costM+10) bytes of s-boxes must not
        // overflow the shift or OOM the JVM on one hash call.
        assertThrows(IllegalArgumentException.class,
            () -> Pufferfish2.newHash(new byte[8], 0, PowCosts.MIN_COST_M - 1));
        assertThrows(IllegalArgumentException.class,
            () -> Pufferfish2.newHash(new byte[8], 0, PowCosts.MAX_COST_M + 1));
    }

    @Test
    void differentCostsGiveDifferentOutputs() {
        byte[] input = new byte[64];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) i;
        }
        PowCosts upgraded = new PowCosts(0, 10);
        assertNotEquals(
            Crypto.PUFFERFISH(input, false, PowCosts.DEFAULT),
            Crypto.PUFFERFISH(input, false, upgraded));
    }

    @Test
    void cacheKeepsCostPairsSeparate() {
        // Same input, two cost pairs, cache ON: each lookup must return the result for
        // ITS costs (equal to the uncached computation), not whichever was cached first.
        byte[] input = new byte[64];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (255 - i);
        }
        PowCosts upgraded = new PowCosts(0, 10);

        SHA256Hash expectedDefault = Crypto.PUFFERFISH(input, false, PowCosts.DEFAULT);
        SHA256Hash expectedUpgraded = Crypto.PUFFERFISH(input, false, upgraded);
        assertNotEquals(expectedDefault, expectedUpgraded);

        // Populate the cache in both orders and re-read.
        assertEquals(expectedDefault, Crypto.PUFFERFISH(input, true, PowCosts.DEFAULT));
        assertEquals(expectedUpgraded, Crypto.PUFFERFISH(input, true, upgraded));
        assertEquals(expectedDefault, Crypto.PUFFERFISH(input, true, PowCosts.DEFAULT));
        assertEquals(expectedUpgraded, Crypto.PUFFERFISH(input, true, upgraded));
    }

    @Test
    void legacyOverloadsUseDefaultCosts() {
        byte[] input = new byte[64];
        assertEquals(
            Crypto.PUFFERFISH(input, false, PowCosts.DEFAULT),
            Crypto.PUFFERFISH(input, false));
    }
}
