package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.DifficultyAdjustment;
import rhizome.core.blockchain.NetworkParameters;

/**
 * Pins the arithmetic behind {@link NetworkParameters#staging()}'s difficulty floor to the
 * hashrate measurement its javadoc cites. This is deliberately pure arithmetic — no mining, no
 * {@code ChainEngine} — so it runs in milliseconds; its whole purpose is to be a build-breaking
 * tripwire: if a later change lowers {@code staging()}'s {@code minDifficulty} margin, raises
 * {@code desiredBlockTimeSec}, or otherwise erodes the headroom the campaign relies on, the build
 * fails here instead of silently reproducing "campaign 7" — a past local campaign whose floor was
 * pinned above the network's real available hashrate, so the retarget loop could never climb away
 * from it and blocks landed every ~20 minutes instead of ~5 seconds.
 */
class StagingCalibrationTest {

    /**
     * Pufferfish2 genesis-cost (cost_t=0, cost_m=8) single-thread hashrate, measured 2026-09-06 on
     * a 16-core development box: 18.331 ms/hash =~ 54.6 H/s (see
     * {@link NetworkParameters#staging()}'s javadoc, and {@code Pufferfish2Benchmark}). Mining is
     * single-threaded PER NODE PROCESS ({@code BlockProducer} runs {@code Miner.mineNonce} on one
     * dedicated thread), so a node's own hashrate is ~54.6 H/s regardless of its host's core
     * count — 16 threads together on the same box only reached 2.548 ms/hash aggregate =~ 392.4
     * H/s (a ~7.2x speedup over 16x, i.e. ~45% efficiency; Pufferfish2 is memory-hard, so it does
     * not parallelize linearly). Only the number of separate miner PROCESSES (K), spread across
     * however many campaign VMs, raises the network's aggregate hashrate — this constant is a
     * per-process rate, not a per-core one.
     */
    private static final double PER_THREAD_HASH_RATE = 54.6;

    /** Smallest supported topology: one VM running one miner process. */
    private static final int MIN_MINER_PROCESSES = 1;

    /**
     * Largest supported topology staging()'s javadoc analyzes: 4 VMs x 8 miner processes each
     * (K=32).
     */
    private static final int MAX_MINER_PROCESSES = 32;

    /**
     * A representative mid-range topology (one VM running several miner processes) used by the
     * retarget simulations below, so they exercise a duration staging()'s floor is meant to climb
     * away from without either drowning in MAX_STEP_BITS-bounded steps or picking an edge value.
     */
    private static final int REPRESENTATIVE_MINER_PROCESSES = 8;

    /**
     * The equilibrium difficulty d* = log2(H_total * desiredBlockTimeSec) must sit strictly
     * between staging()'s minDifficulty and maxDifficulty for every supported topology from a
     * single miner process (K=1) through the campaign's largest assumed deployment (K=32) — i.e.
     * the retarget loop has room to move in BOTH directions everywhere in that range, not just at
     * one assumed K. This is the calibration argument from staging()'s javadoc, re-derived rather
     * than merely restated: it fails the build the moment minDifficulty, maxDifficulty, or
     * desiredBlockTimeSec drift far enough to close the margin at either end.
     */
    @Test
    void equilibriumDifficultyHasHeadroomAcrossSupportedTopology() {
        NetworkParameters staging = NetworkParameters.staging();
        for (int k = MIN_MINER_PROCESSES; k <= MAX_MINER_PROCESSES; k++) {
            double hTotal = k * PER_THREAD_HASH_RATE;
            double equilibriumDifficulty = log2(hTotal * staging.desiredBlockTimeSec());
            assertTrue(equilibriumDifficulty > staging.minDifficulty(),
                "K=" + k + ": equilibrium difficulty " + equilibriumDifficulty
                    + " must sit above staging's floor " + staging.minDifficulty());
            assertTrue(equilibriumDifficulty < staging.maxDifficulty(),
                "K=" + k + ": equilibrium difficulty " + equilibriumDifficulty
                    + " must sit below staging's ceiling " + staging.maxDifficulty());
        }
    }

    /**
     * Simulates a full {@code difficultyLookback} window mined AT staging's floor
     * (minDifficulty=8) but at the REAL implied duration for a representative K=8 topology
     * (H_total =~ 8 x 54.6 =~ 436.8 H/s), using the repo's actual
     * {@link DifficultyAdjustment#nextDifficulty} — not a reimplementation of it. Difficulty 8
     * means each block needs 2^8=256 expected hashes; at H_total that takes 256/436.8 seconds, so
     * a window of {@code difficultyLookback} blocks takes that many times longer. That duration is
     * computed here, not hardcoded, so the assertion tracks whichever constants actually ship.
     * Asserting the computed next difficulty is STRICTLY GREATER than the floor is the point: the
     * retarget loop actually climbs off the floor instead of staying pinned there for the whole
     * campaign.
     */
    @Test
    void atFloorRetargetClimbsAwayGivenRealHashrate() {
        NetworkParameters staging = NetworkParameters.staging();
        long window = staging.difficultyLookback();
        double hTotal = REPRESENTATIVE_MINER_PROCESSES * PER_THREAD_HASH_RATE;
        long observedSeconds = observedWindowSeconds(staging.minDifficulty(), hTotal, window);

        int next = DifficultyAdjustment.nextDifficulty(staging, staging.minDifficulty(), window, observedSeconds);

        assertTrue(next > staging.minDifficulty(),
            "expected staging's floor of " + staging.minDifficulty()
                + " to climb under a real K=" + REPRESENTATIVE_MINER_PROCESSES
                + " hashrate, but next difficulty was " + next);
    }

    /**
     * The negative control that keeps the two tests above honest: repeats the exact same
     * window/duration computation as {@link #atFloorRetargetClimbsAwayGivenRealHashrate()} — same
     * K=8 hashrate, same difficultyLookback window — but starting from
     * {@link NetworkParameters#cleanMainnet()}'s floor of 16 instead of staging's 8. Difficulty 16
     * needs 2^16=65536 expected hashes per block, which the same K=8 hashrate takes far longer than
     * desiredBlockTimeSec to produce, so the retarget wants to fall — but mainnet's own floor
     * clamps it right back to 16. This is exactly the defect staging()'s lower floor exists to
     * avoid: shipping mainnet's floor onto the campaign would pin the retarget loop at its minimum
     * for the whole run, never adapting to the real available hashrate.
     */
    @Test
    void mainnetsOwnFloorWouldStayPinnedAtTheStagingCampaignsHashrate() {
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        long window = mainnet.difficultyLookback();
        double hTotal = REPRESENTATIVE_MINER_PROCESSES * PER_THREAD_HASH_RATE;
        long observedSeconds = observedWindowSeconds(mainnet.minDifficulty(), hTotal, window);

        int next = DifficultyAdjustment.nextDifficulty(mainnet, mainnet.minDifficulty(), window, observedSeconds);

        assertEquals(mainnet.minDifficulty(), next,
            "expected mainnet's floor of " + mainnet.minDifficulty()
                + " to stay pinned under the staging campaign's K=" + REPRESENTATIVE_MINER_PROCESSES
                + " hashrate, but next difficulty was " + next);
    }

    /**
     * Real (not hardcoded) wall-clock duration for a {@code windowBlocks}-block window mined at
     * {@code difficulty}, given aggregate hashrate {@code hashRate}: each block needs
     * 2^difficulty expected hashes, so it takes {@code 2^difficulty / hashRate} seconds, and the
     * window takes that many times {@code windowBlocks}.
     */
    private static long observedWindowSeconds(int difficulty, double hashRate, long windowBlocks) {
        double perBlockSeconds = Math.pow(2, difficulty) / hashRate;
        return Math.round(windowBlocks * perBlockSeconds);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
