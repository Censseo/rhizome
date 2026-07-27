package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
}
