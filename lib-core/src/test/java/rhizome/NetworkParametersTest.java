package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.PowAlgorithm;

class NetworkParametersTest {

    @Test
    void cleanMainnetUsesPufferfishFromGenesis() {
        NetworkParameters params = NetworkParameters.cleanMainnet();
        assertEquals(PowAlgorithm.PUFFERFISH2, params.powAlgorithm());
        assertEquals(1, params.chainId());
    }

    @Test
    void miningRewardIsIntegerAndDeterministic() {
        NetworkParameters params = NetworkParameters.cleanMainnet();
        long base = 50L * 10_000L;

        // First epoch: full reward.
        assertEquals(base, params.miningReward(0));
        assertEquals(base, params.miningReward(params.rewardEpochBlocks() - 1));

        // Second epoch: reward * 2 / 3 (integer).
        assertEquals(base * 2 / 3, params.miningReward(params.rewardEpochBlocks()));

        // Third epoch: (base * 2 / 3) * 2 / 3 — compounded with integer truncation.
        long expectedThird = (base * 2 / 3) * 2 / 3;
        assertEquals(expectedThird, params.miningReward(2 * params.rewardEpochBlocks()));

        // Deterministic across calls.
        assertEquals(params.miningReward(5_000_000), params.miningReward(5_000_000));
    }

    @Test
    void rewardDecaysOverTime() {
        NetworkParameters params = NetworkParameters.cleanMainnet();
        assertTrue(params.miningReward(10 * params.rewardEpochBlocks())
            < params.miningReward(0));
    }

    @Test
    void testnetDiffersFromMainnet() {
        assertNotEquals(NetworkParameters.cleanMainnet().chainId(),
            NetworkParameters.testnet().chainId());
    }
}
