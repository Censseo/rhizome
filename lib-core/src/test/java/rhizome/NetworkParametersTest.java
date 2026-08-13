package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.DifficultyAdjustment;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;

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
        long base = params.miningReward(0); // derived, not hard-coded to a magnitude

        // First epoch: full reward.
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
    void emissionScheduleIsCalibratedForTheBlockCadence() {
        // Guards against the "reward curve is broken at fast blocks" regression: the
        // decay epoch is denominated in blocks, so its REAL-TIME length must be
        // recomputed from the block time. Whatever the cadence, the epoch must span
        // years, not days, and the subsidy must last decades, not months.
        NetworkParameters p = NetworkParameters.cleanMainnet();
        long blockTime = p.desiredBlockTimeSec();
        double epochYears = p.rewardEpochBlocks() * blockTime / 86_400.0 / 365.25;
        assertTrue(epochYears > 1.0 && epochYears < 5.0,
            "decay epoch should span ~1.9 years in real time, was " + epochYears);

        // Total issuance (geometric series with x2/3 decay, integer-truncated) must
        // land near the intended ~100M PDN, not balloon or collapse.
        long totalBase = 0;
        long reward = p.miningReward(0);
        long h = 0;
        while (reward > 0) {
            totalBase += reward * p.rewardEpochBlocks();
            h += p.rewardEpochBlocks();
            reward = p.miningReward(h);
        }
        long totalPdn = totalBase / p.decimalScaleFactor();
        assertTrue(totalPdn > 80_000_000L && totalPdn < 120_000_000L,
            "total issuance should be ~100M PDN, was " + totalPdn);

        // The subsidy tail must survive well beyond a year at the configured cadence.
        long blocksPerYear = 365L * 86_400L / blockTime;
        assertTrue(p.miningReward(blocksPerYear) > 0, "reward must not dry up within a year");
    }

    @Test
    void testnetDiffersFromMainnet() {
        assertNotEquals(NetworkParameters.cleanMainnet().chainId(),
            NetworkParameters.testnet().chainId());
    }

    @Test
    void devnetHasItsOwnChainIdAndCheapPow() {
        NetworkParameters devnet = NetworkParameters.devnet();
        assertEquals(3, devnet.chainId());
        assertNotEquals(NetworkParameters.testnet().chainId(), devnet.chainId());
        assertNotEquals(NetworkParameters.cleanMainnet().chainId(), devnet.chainId());
        assertEquals(PowAlgorithm.SHA256, devnet.powAlgorithm());
    }

    @Test
    void devnetRetargetsOnTheCadenceItIsPacedAt() {
        NetworkParameters devnet = NetworkParameters.devnet();

        // The whole reason devnet exists: a producer paced at 5 s must not be seen as "too fast"
        // by the retarget. On testnet (90 s target) that same cadence is 18x fast and difficulty
        // runs away; here observed == desired, so difficulty holds.
        assertEquals(5, devnet.desiredBlockTimeSec());

        long window = devnet.difficultyLookback();
        long observedAt5s = window * 5L;
        assertEquals(devnet.minDifficulty(),
            DifficultyAdjustment.nextDifficulty(devnet, devnet.minDifficulty(), window, observedAt5s));

        // Contrast: the same 5 s cadence against testnet's 90 s target climbs.
        NetworkParameters testnet = NetworkParameters.testnet();
        assertTrue(DifficultyAdjustment.nextDifficulty(
                testnet, testnet.minDifficulty(), testnet.difficultyLookback(),
                testnet.difficultyLookback() * 5L) > testnet.minDifficulty(),
            "testnet is expected to ramp difficulty at 5 s — that is why devnet exists");
    }

    @Test
    void devnetDifficultyStaysCheapEnoughToKeepMining() {
        NetworkParameters devnet = NetworkParameters.devnet();

        // Even a runaway retarget is clamped to work a single core can still do.
        assertTrue(devnet.maxDifficulty() <= 24,
            "devnet must stay minable on one core, was " + devnet.maxDifficulty());

        int difficulty = devnet.minDifficulty();
        for (int i = 0; i < 50; i++) {
            // Blocks arriving instantly (observed clamped to 1 s) is the worst case for the ramp.
            difficulty = DifficultyAdjustment.nextDifficulty(devnet, difficulty,
                devnet.difficultyLookback(), 0);
        }
        assertEquals(devnet.maxDifficulty(), difficulty);
    }

    @Test
    void rejectsNegativeActivationHeights() {
        // A negative activation height would invert the box/token predicates (every height
        // reads "active", including the Long.MAX_VALUE sentinel edge in the mempool gate).
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().boxActivationHeight(-1).build());
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().tokenActivationHeight(-1).build());
        // Bounds are accepted: 0 = from genesis, positive = gated.
        NetworkParameters.testnet().toBuilder().boxActivationHeight(0).build();
        NetworkParameters.testnet().toBuilder().tokenActivationHeight(100).build();
    }

    @Test
    void activationPredicatesCarryTheBoundary() {
        // The predicates are the single expression of where each domain activates: the executor
        // judges the block's own height, the mempool the next block. Both sides must land on the
        // same boundary — exactly at the activation height and one below it.
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .boxActivationHeight(100).tokenActivationHeight(200).build();
        assertFalse(params.boxActiveAt(99));
        assertTrue(params.boxActiveAt(100));
        assertFalse(params.tokenActiveAt(199));
        assertTrue(params.tokenActiveAt(200));

        // Next-block judgement: confirmed 98 means next block 99 < 100 → still gated;
        // confirmed 99 means next block 100 == activation → admitted.
        assertFalse(params.boxActiveForNextBlock(98));
        assertTrue(params.boxActiveForNextBlock(99));
        assertFalse(params.tokenActiveForNextBlock(198));
        assertTrue(params.tokenActiveForNextBlock(199));

        // The Long.MAX_VALUE sentinel is past every activation: both judgements admit.
        assertTrue(params.boxActiveForNextBlock(Long.MAX_VALUE));
        assertTrue(params.tokenActiveForNextBlock(Long.MAX_VALUE));
        assertTrue(params.boxActiveAt(Long.MAX_VALUE));
        assertTrue(params.tokenActiveAt(Long.MAX_VALUE));
    }
}
