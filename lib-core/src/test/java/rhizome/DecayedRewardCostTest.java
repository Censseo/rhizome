package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.EmissionCurve;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyTargetSchedule;

/**
 * 008-decaying-supply-target, User Story 4 (T035-T038): what the decay costs a miner is bounded,
 * monotone and published as a number. Four proofs over the real dispatch:
 *
 * <ul>
 *   <li><b>G4 / T035</b> — the scheduled base reward is {@code >= R_min > 0} at every
 *       curve-active height and every supply, including past {@code floorArrivalHeight}.</li>
 *   <li><b>G5 / SC-013 / T036</b> — at a fixed supply, consecutive epochs are non-increasing and
 *       differ by at most the published {@code perEpochReductionBound} (measured worst case at
 *       the mainnet calibration: exactly 30, at {@code S_0}).</li>
 *   <li><b>T037</b> — below the decay-start height the reward is bit-for-bit the pre-feature
 *       value for the same supply (SC-001/FR-015).</li>
 *   <li><b>FR-016 / T038</b> — uncle and nephew rewards still derive from the SAME floored base
 *       under a decayed target, with no second clamp site.</li>
 * </ul>
 */
class DecayedRewardCostTest {

    /** Mainnet carrying the published decay calibration (identical helper to the simulation). */
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

    /** Dense height coverage: activation through past floor arrival, plus the sentinel height. */
    private static long[] sweepHeights(NetworkParameters params) {
        SupplyTargetSchedule s = params.supplyTargetSchedule();
        long start = Math.max(1, params.emissionCurveHeight());
        java.util.TreeSet<Long> heights = new java.util.TreeSet<>();
        for (long h = start; h <= 40 && h <= s.floorArrivalHeight() + 1; h++) {
            heights.add(h); // the fixture-scale early regime, every height
        }
        if (s.isScheduled()) {
            for (long e = 0; e <= s.epochsToFloor(); e++) {
                heights.add(s.startHeight() + e * s.epochBlocks()); // every epoch boundary
                heights.add(Math.max(start, s.startHeight() + e * s.epochBlocks() - 1));
            }
            heights.add(s.floorArrivalHeight() - 1);
            heights.add(s.floorArrivalHeight());
            heights.add(s.floorArrivalHeight() + 1);
            heights.add(s.floorArrivalHeight() * 2);
            heights.add(Long.MAX_VALUE / 2);
        }
        long[] out = new long[heights.size()];
        int i = 0;
        for (long h : heights) {
            out[i++] = h;
        }
        return out;
    }

    /** Dense supply coverage: domain edges, target fractions, the target itself, the mirror. */
    private static long[] sweepSupplies(NetworkParameters params) {
        SupplyTargetSchedule s = params.supplyTargetSchedule();
        java.util.TreeSet<Long> supplies = new java.util.TreeSet<>();
        supplies.add(0L);
        supplies.add(1L);
        if (params.genesisSupply() > 0) {
            supplies.add(params.genesisSupply());
        }
        for (long frac : new long[] {1, 2, 4, 9, 10}) {
            supplies.add(s.peak() * frac / 10);
        }
        supplies.add(s.peak() - 1);
        supplies.add(s.peak());
        supplies.add(s.peak() + 1);
        supplies.add(s.peak() * 15 / 10);
        supplies.add(s.peak() * 19 / 10);
        supplies.add(Long.MAX_VALUE / 4);
        long[] out = new long[supplies.size()];
        int i = 0;
        for (long v : supplies) {
            out[i++] = v;
        }
        return out;
    }

    @Test
    void theScheduledBaseRewardStaysAtOrAboveTheRevenueFloorEverywhere() {
        // G4/SC-002, on BOTH the decay-active fixture and the calibrated mainnet twin: a dense
        // sweep of curve-active heights (including past floorArrivalHeight) and supplies.
        for (NetworkParameters params : new NetworkParameters[] {
                CurveActiveNetwork.decayActiveTestnet(), calibratedMainnet()}) {
            long floor = params.minerRevenueFloor();
            assertTrue(floor > 0, "R_min must be strictly positive for the invariant to bite");
            for (long height : sweepHeights(params)) {
                if (!params.emissionCurveActiveAt(height)) {
                    continue;
                }
                for (long supply : sweepSupplies(params)) {
                    long reward = params.miningReward(height, supply);
                    assertTrue(reward >= floor,
                        params.networkName() + ": reward " + reward + " fell below the floor "
                            + floor + " at height " + height + ", supply " + supply);
                }
            }
        }
    }

    @Test
    void consecutiveEpochsReduceTheRewardByAtMostThePublishedBound() {
        // G5/SC-013. At a fixed supply the scheduled base reward is non-increasing across
        // elapsed epochs on EVERY calibration. The exact published bound (measured worst case:
        // exactly 30, at S_0) is a record of the SHIPPED mainnet calibration, where the scaled
        // argument stays inside one interpolation segment per epoch step; asserted there in
        // full. The test-scale fixture's coarse N=16 table quantizes the mirror-branch argument
        // far more coarsely, so it carries the provable general bound instead:
        // per-epoch reduction <= D + (largest adjacent table drop) + rounding.
        NetworkParameters mainnet = calibratedMainnet();
        SupplyTargetSchedule s = mainnet.supplyTargetSchedule();
        long bound = s.perEpochReductionBound();
        for (long supply : new long[] {
                mainnet.genesisSupply(), s.peak() / 2, s.peak() * 9 / 10, s.peak(),
                s.peak() * 15 / 10, s.peak() * 19 / 10, Long.MAX_VALUE / 4}) {
            long previous = mainnet.miningReward(
                Math.max(1, mainnet.emissionCurveHeight()), supply);
            for (long e = 1; e <= s.epochsToFloor(); e++) {
                long height = s.startHeight() + e * s.epochBlocks();
                long reward = mainnet.miningReward(height, supply);
                assertTrue(reward <= previous,
                    "supply " + supply + ": reward rose across epoch " + e + " (" + previous
                        + " -> " + reward + ")");
                assertTrue(previous - reward <= bound,
                    "supply " + supply + ": epoch " + e + " reduced the reward by "
                        + (previous - reward) + ", over the published bound " + bound);
                previous = reward;
            }
        }

        NetworkParameters fixture = CurveActiveNetwork.decayActiveTestnet();
        SupplyTargetSchedule fs = fixture.supplyTargetSchedule();
        EmissionCurve fixtureCurve = EmissionCurve.build(fixture.supplyTarget(),
            fixture.emissionCoefficient(), fixture.emissionTableSteps());
        long fixtureBound = fs.perEpochReductionBound()
            + (fixtureCurve.tableValue(1) - fixtureCurve.tableValue(2)) + 2;
        for (long supply : new long[] {1L, fixture.genesisSupply() > 0 ? fixture.genesisSupply()
                : 1L, fs.peak() / 2, fs.peak() * 9 / 10, fs.peak(), fs.peak() * 15 / 10,
                Long.MAX_VALUE / 4}) {
            long previous = fixture.miningReward(
                Math.max(1, fixture.emissionCurveHeight()), supply);
            for (long e = 1; e <= fs.epochsToFloor(); e++) {
                long height = fs.startHeight() + e * fs.epochBlocks();
                long reward = fixture.miningReward(height, supply);
                assertTrue(reward <= previous,
                    "fixture supply " + supply + ": reward rose across epoch " + e);
                assertTrue(previous - reward <= fixtureBound,
                    "fixture supply " + supply + ": epoch " + e + " reduced the reward by "
                        + (previous - reward) + ", over the general bound " + fixtureBound
                        + " (D + largest table drop + rounding)");
                previous = reward;
            }
        }
    }

    @Test
    void belowTheDecayStartTheRewardIsBitForBitThePreFeatureValue() {
        // FR-015/SC-001: the pre-feature value is the peak-curve evaluation at the same supply,
        // floored at R_min — recomputed here against an independent table built from the
        // published triple, never by calling today's dispatch with the peak.
        for (NetworkParameters params : new NetworkParameters[] {
                CurveActiveNetwork.decayActiveTestnet(), calibratedMainnet()}) {
            SupplyTargetSchedule s = params.supplyTargetSchedule();
            EmissionCurve peakCurve = EmissionCurve.build(params.supplyTarget(),
                params.emissionCoefficient(), params.emissionTableSteps());
            for (long height : sweepHeights(params)) {
                if (s.isScheduled() && height >= s.startHeight()) {
                    continue; // only the pre-decay era
                }
                if (!params.emissionCurveActiveAt(height)) {
                    continue;
                }
                for (long supply : sweepSupplies(params)) {
                    long preFeature = Math.max(params.minerRevenueFloor(),
                        peakCurve.raw(supply, params.supplyTarget()));
                    assertEquals(preFeature, params.miningReward(height, supply),
                        params.networkName() + ": below the decay start, the reward at height "
                            + height + ", supply " + supply + " must be bit-for-bit the "
                            + "pre-feature value");
                }
            }
        }
    }

    @Test
    void uncleAndNephewRewardsDeriveFromTheSameFlooredBaseUnderADecayedTarget() {
        // FR-016: one clamp site. Uncle and nephew rewards are exact rational multiples of the
        // dispatched, floored base — including the decayed regime where the raw value is
        // negative and the floor is what the base becomes.
        NetworkParameters params = CurveActiveNetwork.decayActiveTestnet();
        SupplyTargetSchedule s = params.supplyTargetSchedule();
        EmissionCurve peakCurve = EmissionCurve.build(params.supplyTarget(),
            params.emissionCoefficient(), params.emissionTableSteps());
        for (long height : sweepHeights(params)) {
            if (!params.emissionCurveActiveAt(height)) {
                continue;
            }
            for (long supply : sweepSupplies(params)) {
                long base = params.miningReward(height, supply);
                long independentBase = Math.max(params.minerRevenueFloor(),
                    peakCurve.raw(supply, params.supplyTargetAt(height)));
                assertEquals(independentBase, base,
                    "the dispatched base must be max(R_min, raw against the live target)");
                assertEquals(Math.multiplyExact(base, params.uncleRewardNum())
                        / params.uncleRewardDen(),
                    params.uncleReward(height, supply),
                    "uncle reward must derive from the same floored base at height " + height);
                assertEquals(base / params.nephewRewardDivisor(),
                    params.nephewReward(height, supply),
                    "nephew reward must derive from the same floored base at height " + height);
            }
        }
        // The decayed-specific shape: supply above a decayed target drives raw negative, so the
        // FLOOR is the base the uncle/nephew terms derive from — the 005 property survives the
        // decay, with no second clamp site to bypass.
        long decayedHeight = s.startHeight() + 3 * s.epochBlocks();
        long aboveTargetSupply = params.supplyTargetAt(decayedHeight) * 2;
        assertTrue(peakCurve.raw(aboveTargetSupply, params.supplyTargetAt(decayedHeight)) < 0,
            "fixture sanity: the raw value is negative here");
        assertEquals(params.minerRevenueFloor(), params.miningReward(decayedHeight,
            aboveTargetSupply));
        assertEquals(params.minerRevenueFloor() / 2, params.uncleReward(decayedHeight,
            aboveTargetSupply));
        assertEquals(params.minerRevenueFloor() / params.nephewRewardDivisor(),
            params.nephewReward(decayedHeight, aboveTargetSupply));
    }
}
