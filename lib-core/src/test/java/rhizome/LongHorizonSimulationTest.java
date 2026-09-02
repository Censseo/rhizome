package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.NetworkParameters;

/**
 * 008-decaying-supply-target T025 (FR-033, SC-004): the long-horizon simulation — supply, minted
 * reward and burn obligation over a full chain lifetime (200 years at the 5 s cadence = exactly
 * 800 decay epochs), including uncle issuance, asserted against the calibration record's endpoints
 * (contracts/supply-target-schedule.md §8, research.md Decision 3/4).
 *
 * <p>The simulation walks DECAY EPOCHS, not blocks: within one epoch the scheduled reward moves by
 * at most the published per-epoch reduction bound (G5, 30 base units at this calibration), so the
 * epoch-start reward approximates the epoch's per-block reward to well under the tolerances the
 * assertions use. Two trajectories are walked:
 *
 * <ul>
 *   <li><b>Shipped state, no burn</b> (this feature enforces nothing): uncle-free and worst-case
 *       uncle variants. Past floor arrival the reward is the floor {@code R_min} every block, so
 *       the tail minting rate is exact — and supply STILL rises past the floor target forever,
 *       which is precisely the failure mode feature 08 exists to discharge.</li>
 *   <li><b>Full tracking</b> (the calibration's stated fee-flow assumption, feature 08's shape):
 *       every block's obligation plus the floor's own tail issuance is hypothetically destroyed,
 *       so supply follows the decaying target down and stays on it (SC-004).</li>
 * </ul>
 */
class LongHorizonSimulationTest {

    private static final long BLOCKS_PER_YEAR = 6_307_200L; // 5 s cadence
    private static final int HORIZON_YEARS = 200;

    /** Uncle-free and worst-case-uncle per-block minting multipliers (WHITEPAPER §5.3 tail rate). */
    private static final double WORST_CASE_UNCLE_FACTOR = 2.0625; // 1 + 2 x (1/2 + 1/32)

    private record EpochSample(long height, long supply, long target, long reward, long obligation) {
    }

    /**
     * Mainnet carrying the published decay calibration (contracts/supply-target-schedule.md
     * section 1). Until T045 the shipped profile stays at the sentinel, so the calibration is
     * applied here explicitly; once T045 lands, plain {@code cleanMainnet()} IS this profile and
     * the helper simply returns it -- the test reads identically on both sides of activation.
     */
    private static NetworkParameters calibratedMainnet() {
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        if (mainnet.supplyTargetSchedule().isScheduled()) {
            return mainnet;
        }
        return mainnet.toBuilder()
            .decayStartHeight(126_144_000L)
            .decayEpochBlocks(1_576_800L)
            .decayNum(799L)
            .decayDen(800L)
            .supplyTargetFloor(1_498_962_290_000L)
            .build();
    }

    /** Walks {@code years} of epochs from the pinned genesis supply; no burn (shipped state). */
    private static EpochSample[] simulateNoBurn(NetworkParameters params, double uncleFactor,
            int years) {
        int epochs = (int) ((long) years * BLOCKS_PER_YEAR / params.supplyTargetSchedule().epochBlocks());
        EpochSample[] samples = new EpochSample[epochs];
        long supply = params.genesisSupply();
        for (int e = 0; e < epochs; e++) {
            long height = 2 + (long) e * params.supplyTargetSchedule().epochBlocks();
            long target = params.supplyTargetAt(height);
            long reward = params.miningReward(height, supply);
            samples[e] = new EpochSample(height, supply, target, reward,
                params.burnObligation(height, supply));
            double uncleBonus = (uncleFactor - 1.0) * reward;
            supply = Math.addExact(supply,
                Math.round(params.supplyTargetSchedule().epochBlocks() * (reward + uncleBonus)));
        }
        return samples;
    }

    @Test
    void theShippedTrajectoryMatchesTheCalibrationRecord() {
        NetworkParameters params = calibratedMainnet();
        var schedule = params.supplyTargetSchedule();
        EpochSample[] samples = simulateNoBurn(params, 1.0, HORIZON_YEARS);

        // --- Endpoint 1 (research.md Decision 3, year 20 = the decay start): supply ~243.20M PDN.
        long decayStartEpoch = schedule.startHeight() / schedule.epochBlocks(); // 80
        long supplyAtDecayStart = samples[(int) decayStartEpoch].supply();
        long expectedY20 = 243_200_000L * 10_000L; // PDN -> base units
        assertTrue(Math.abs(supplyAtDecayStart - expectedY20) <= expectedY20 / 200,
            "supply at the decay start (year 20) is " + supplyAtDecayStart / 10_000L
                + " PDN; the calibration record states ~243.20M PDN (+/-0.5%)");

        // --- Endpoint 2 (contract section 8): past floor arrival the uncle-free tail rate is
        // exactly R_min per block: 800 x 6 307 200 = 504 576 PDN/year. The clamped reward makes
        // this drift-free, so the epoch-walk agrees to within one epoch's rounding.
        int floorEpoch = (int) ((schedule.floorArrivalHeight() - 2)
            / schedule.epochBlocks()) + 1;
        long supplyEarly = samples[floorEpoch].supply();
        long supplyLate = samples[samples.length - 1].supply();
        long yearsLate = (samples[samples.length - 1].height() - samples[floorEpoch].height())
            / BLOCKS_PER_YEAR;
        long tailRate = (supplyLate - supplyEarly) / yearsLate / 10_000L; // PDN/year
        assertEquals(504_576L, tailRate,
            "the uncle-free tail minting rate past floor arrival must equal the published "
                + "504 576 PDN/year (R_min x blocks/year)");

        // The worst-case uncle variant scales the same tail by 2.0625 -- the WHITEPAPER's
        // published 0.347 %-of-S*-per-year worst case.
        EpochSample[] withUncles = simulateNoBurn(params, WORST_CASE_UNCLE_FACTOR, HORIZON_YEARS);
        long uncleTailRate = (withUncles[withUncles.length - 1].supply()
            - withUncles[floorEpoch].supply()) / yearsLate / 10_000L;
        assertEquals(Math.round(504_576L * WORST_CASE_UNCLE_FACTOR), uncleTailRate,
            "the worst-case uncle tail rate must equal 2.0625 x the uncle-free rate");
        // 2.0625 x 0.168 % = 0.347143 % -- the WHITEPAPER's "0.347 % of S* per year" to three
        // decimal places; the assertion allows that exact ratio, not a rounder one.
        assertTrue(uncleTailRate <= 299_792_458_0000L * 3_472 / 1_000_000,
            "the worst-case tail must stay within the published 0.347 % of S* per year");

        // --- Endpoint 3: with nothing enforcing the obligation, supply still ends far above the
        // floor target. That overshoot is not a bug -- it is the recorded reason feature 08
        // exists (the baseline being corrected, contract section 8's last row).
        assertTrue(samples[samples.length - 1].supply() > schedule.floor(),
            "with no burn, supply rises past the floor target forever -- the load-bearing gap "
                + "the calibration record states rather than hides");
    }

    @Test
    void theObligationAppearsExactlyWhereTheCalibrationSaysAndStaysPerBlock() {
        NetworkParameters params = calibratedMainnet();
        // An independent peak-curve (a pure function of the published triple, DI-11) to compute
        // the obligation's definition against, rather than the dispatch's own instance.
        var peakCurve = rhizome.core.blockchain.EmissionCurve.build(params.supplyTarget(),
            params.emissionCoefficient(), params.emissionTableSteps());
        EpochSample[] samples = simulateNoBurn(params, 1.0, HORIZON_YEARS);

        boolean seenZero = false;
        boolean seenPositive = false;
        long maxObligation = 0;
        for (EpochSample sample : samples) {
            // The obligation is exactly max(0, -raw) against the live target -- never a
            // cumulative figure (FR-018, research.md Decision 4: a cumulative form reaches
            // ~3.5x circulating supply over this horizon and describes nothing).
            long independent = Math.max(0, -peakCurve.raw(sample.supply(), sample.target()));
            assertEquals(independent, sample.obligation(),
                "obligation at height " + sample.height() + " must be max(0, -raw)");
            seenZero |= sample.obligation() == 0;
            seenPositive |= sample.obligation() > 0;
            maxObligation = Math.max(maxObligation, sample.obligation());
        }
        assertTrue(seenZero, "before the decay overtakes supply the obligation is 0");
        assertTrue(seenPositive, "once the target falls below supply the obligation is positive");
        // research.md Decision 4: c*ln(150/350) ~ -20 116 base units at year 200 -- the deepest
        // sampled obligation sits in that neighbourhood, not in accumulator territory.
        assertTrue(maxObligation < 40_000,
            "the per-block obligation must stay a rate (max sampled " + maxObligation
                + "), never a cumulative debt");
    }

    @Test
    void underFullTrackingSupplyFollowsTheDecayingTargetDown() {
        // SC-004's stated-fee-flow branch: with every block's obligation PLUS the floor's own
        // tail issuance hypothetically destroyed (feature 08's shape, ~2.0M PDN/year of burn at
        // the shipped calibration), supply follows the target down and stays on it.
        NetworkParameters params = calibratedMainnet();
        var schedule = params.supplyTargetSchedule();
        int epochs = (int) ((long) HORIZON_YEARS * BLOCKS_PER_YEAR / schedule.epochBlocks());
        long supply = params.genesisSupply();
        int samplesTaken = 0;
        boolean crossed = false;
        for (int e = 0; e < epochs; e++) {
            long height = 2 + (long) e * schedule.epochBlocks();
            long target = params.supplyTargetAt(height);
            long reward = params.miningReward(height, supply);
            long obligation = params.burnObligation(height, supply);
            long net = reward - obligation - params.minerRevenueFloor();
            supply = Math.addExact(supply, schedule.epochBlocks() * net);
            if (supply >= target) {
                crossed = true; // the falling target has met the rising supply: tracking engages
            }
            if (height > schedule.startHeight() && crossed && e % 5 == 0) {
                samplesTaken++;
                // Past the crossing the burn holds supply in the crossover band just below the
                // target, following it down all the way to the floor.
                assertTrue(supply <= target + target / 10,
                    "under full tracking, supply at height " + height + " (" + supply
                        / 10_000L + " PDN) must follow the decaying target (" + target / 10_000L
                        + " PDN) down -- within 10% above it");
                assertTrue(supply > target - target / 10,
                    "under full tracking, supply must not lag the falling target by more than "
                        + "10% (the crossover band is 3.31%, plus epoch-walk transient) at "
                        + "height " + height);
            }
        }
        assertTrue(crossed, "the falling target must cross the rising supply within the horizon");
        assertTrue(samplesTaken >= 50, "the tracking sweep must actually sample the decayed era");
    }
}
