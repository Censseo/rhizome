package rhizome;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

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
        // Rewritten for the supply-driven logarithmic curve (004-integer-log-curve; research.md
        // Decisions 6-7). The OLD version of this test (still reproducible by reverting this
        // method) asserted the LEGACY x2/3-decay geometric schedule's own calibration -- an
        // epoch-years band, a geometric-series total-issuance band, a one-year subsidy-survival
        // check -- entirely through the one-arg miningReward(height). That one-arg form is
        // untouched by this feature (mainnet ships emissionCurveHeight == 0, so the curve is
        // inactive), which is exactly why the old test still PASSES unmodified on this code: it
        // never reaches the curve at all. As a calibration proof for the curve it asserts
        // nothing, since the curve only exists behind the two-arg miningReward(height,
        // parentSupply) dispatch. This rewrite pins the CURVE's calibration instead.
        NetworkParameters geometric = NetworkParameters.cleanMainnet();

        // Mainnet ships emissionCurveHeight == 0 (feature 05 schedules the real activation
        // height); build a curve-ACTIVATED variant of the real mainnet constants (S*, c, N
        // unchanged) so this test can actually exercise the curve, independent of when the real
        // chain eventually turns it on -- this test proves the CONSTANTS, not the schedule.
        long activationHeight = 1;
        NetworkParameters p = geometric.toBuilder().emissionCurveHeight(activationHeight).build();

        // Cadence-relative guard, retained from the old test: tau (the curve's ~20-year decay
        // target, research.md Decision 6) is denominated in BLOCKS, so its real-time length must
        // be recomputed from desiredBlockTimeSec -- never hard-coded to 126_230_400 -- so a
        // future cadence change still forces this calibration to be revisited.
        long blockTime = p.desiredBlockTimeSec();
        double secondsPerYear = 365.25 * 86_400.0;
        long tauBlocks = (long) (20.0 * secondsPerYear / blockTime);

        long s0 = p.genesisSupply();
        long sStar = p.supplyTarget();
        long c = p.emissionCoefficient();

        // Launch emission must stay within 10% of today's geometric baseline (SC-004): the curve
        // must not surprise-diverge the launch reward even though its long-run shape is entirely
        // different.
        long launchReward = p.miningReward(activationHeight, s0);
        long geometricLaunchReward = geometric.miningReward(0);
        double launchPctDiff =
            100.0 * Math.abs(geometricLaunchReward - launchReward) / geometricLaunchReward;
        assertTrue(launchPctDiff < 10.0,
            "launch emission " + launchReward + " diverges " + launchPctDiff
                + "% from the geometric baseline " + geometricLaunchReward
                + ", exceeds the 10% band (SC-004)");

        // Strided supply-recursion simulation (research.md Decision 7): 126M blocks would be too
        // slow for the routine suite one block at a time, so advance k*f(S) per stride, with
        // k = max(1, S/(1000*c)) keeping each stride's relative supply change <= 0.1%. This
        // collapses the run to a few thousand iterations.
        long supply = s0;
        long height = activationHeight;
        long reward = p.miningReward(height, supply);
        long previousReward = Long.MAX_VALUE;
        long supplyAtTau = -1;
        long lastPositiveSupply = s0;
        while (reward > 0) {
            // Hard invariant, asserted every iteration: reward is monotone non-increasing across
            // the entire run. The curve must never re-inflate as supply climbs toward S* --
            // WHITEPAPER.md determinism/no-surprise-inflation property for the emission rule.
            assertTrue(reward <= previousReward,
                "reward must be monotone non-increasing across the run: " + reward
                    + " > previous " + previousReward + " at supply=" + supply);
            previousReward = reward;
            lastPositiveSupply = supply;

            long k = Math.max(1, supply / (1000 * c));
            supply += k * reward;
            height += k;
            reward = p.miningReward(height, supply);

            if (supplyAtTau < 0 && height >= tauBlocks) {
                supplyAtTau = supply;
            }
        }
        long firstZeroSupply = supply; // miningReward(height, firstZeroSupply) == 0, by striding

        // Supply at tau must have moved a substantial majority of the way from S0 toward S* --
        // a generous, empirically-verified band (measured ~71.7% at the shipped calibration; the
        // continuous-model estimate for this shape is 1 - e^-1 ~= 63.2%), not a tight pin: a
        // logarithmic decay never actually reaches S* in finitely many blocks.
        assertTrue(supplyAtTau > 0, "tau was never reached before the curve terminated");
        double fractionOfGapCovered = (double) (supplyAtTau - s0) / (sStar - s0);
        assertTrue(fractionOfGapCovered > 0.5 && fractionOfGapCovered < 0.95,
            "supply at tau should have covered a substantial majority of the S0->S* gap, was "
                + (fractionOfGapCovered * 100) + "%");

        // Terminal supply: the coarse stride above BRACKETS the true boundary but overshoots it
        // -- near S*, k grows to ~S/(1000c) (hundreds of thousands of blocks) while the reward
        // itself has shrunk to a handful of base units, so a single stride can leap tens of
        // millions of base units past the point the reward first truncates to 0. Refine with a
        // binary search on the same monotone function to land on the EXACT smallest supply at
        // which the curve's reward is 0 -- still running the real curve, never hand-derived.
        long lo = lastPositiveSupply; // miningReward(height, lo) > 0
        long hi = firstZeroSupply;    // miningReward(height, hi) == 0
        while (hi - lo > 1) {
            long mid = lo + (hi - lo) / 2;
            if (p.miningReward(height, mid) > 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        // Pinned EXACT terminal supply at the shipped mainnet calibration (S*=2_997_924_580_000,
        // c=23_750, N=256) -- a REGRESSION GUARD, not a derived constant. If a future change to
        // any of those three curve constants shifts where the reward truncates to zero, this
        // assertion is what catches it: update it deliberately, alongside the constants, should
        // that ever happen. (Note: research.md Decision 6 estimates this gap from S* at "~126
        // 000 base units"; the value actually measured here, ~127.29M base units below S*, is
        // three orders of magnitude larger and matches the S*/c ~= 126_228_403 scale of tau_blocks
        // itself -- the documented estimate looks like a units slip in the write-up, not a bug
        // in the curve. This assertion pins what the code actually does, per T019.)
        assertEquals(2_997_797_290_244L, hi,
            "pinned terminal supply drifted -- update deliberately if the curve constants changed");

        // Literal per-block segment (research.md Decision 7): validates the stride method
        // against ground truth over a real, if partial, span -- 100,000 single-block steps, no
        // striding.
        int literalBlocks = 100_000;
        long literalSupply = s0;
        long literalHeight = activationHeight;
        for (int i = 0; i < literalBlocks; i++) {
            literalSupply += p.miningReward(literalHeight, literalSupply);
            literalHeight++;
        }

        long stridedSupply = stridedSupplyOverSpan(p, activationHeight, s0, literalHeight, c);
        double totalMinted = literalSupply - s0;
        double relativeDivergence = Math.abs(stridedSupply - literalSupply) / totalMinted;
        // The stride method is a deliberate approximation, not floating-point noise: applying a
        // stride's START-of-stride reward across k blocks over-counts by however much the true
        // (monotone non-increasing) reward would have fallen across that span. Decision 7 bounds
        // that at <= 0.1% relative change PER STRIDE; the measured divergence over this span is
        // ~0.046% (well inside, over only 3 strides), so 0.1% is the tightest defensible,
        // non-flaky bound to assert here -- not exact equality, which the striding approximation
        // does not promise.
        assertTrue(relativeDivergence < 0.001,
            "strided supply " + stridedSupply + " diverges " + (relativeDivergence * 100)
                + "% from the literal per-block supply " + literalSupply + " over " + literalBlocks
                + " blocks, exceeds the 0.1% per-stride design bound (research.md Decision 7)");
    }

    /**
     * The stride method (research.md Decision 7) restricted to {@code [startHeight,
     * endHeightExclusive)}, clipping the final stride to the boundary -- used to validate stride
     * fidelity against a literal per-block segment over the same span.
     */
    private static long stridedSupplyOverSpan(NetworkParameters p, long startHeight,
            long startSupply, long endHeightExclusive, long coefficient) {
        long supply = startSupply;
        long height = startHeight;
        while (height < endHeightExclusive) {
            long reward = p.miningReward(height, supply);
            if (reward <= 0) {
                break;
            }
            long k = Math.max(1, supply / (1000 * coefficient));
            if (height + k > endHeightExclusive) {
                k = endHeightExclusive - height;
            }
            supply += k * reward;
            height += k;
        }
        return supply;
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

    @Test
    void mainnetPinsItsGenesisSupplyAndShipsAnAllocationArtifact() {
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        assertEquals(1_000_000_000_000L, mainnet.genesisSupply());
        assertEquals(Optional.of("genesis/rhizome-mainnet.json"), mainnet.genesisSnapshotResource());
    }

    @Test
    void testnetAndDevnetLeaveTheGenesisSupplyUnpinned() {
        // Decision 7 (research.md): testnet()/devnet() derive from cleanMainnet().toBuilder(),
        // so a new field with a mainnet value must be EXPLICITLY reset in both or it silently
        // inherits the mainnet pin and resource.
        NetworkParameters testnet = NetworkParameters.testnet();
        NetworkParameters devnet = NetworkParameters.devnet();

        assertEquals(NetworkParameters.GENESIS_SUPPLY_UNPINNED, testnet.genesisSupply());
        assertEquals(NetworkParameters.GENESIS_SUPPLY_UNPINNED, devnet.genesisSupply());
        assertTrue(testnet.genesisSnapshotResource().isEmpty());
        assertTrue(devnet.genesisSnapshotResource().isEmpty());
    }

    // --- Supply-driven logarithmic emission curve (004-integer-log-curve) ---

    // Small, fast test-scale curve constants -- NOT the mainnet calibration (that lives in
    // cleanMainnet() and emissionScheduleIsCalibratedForTheBlockCadence). Only the sign/equality
    // properties below matter to these tests, not the concrete curve shape.
    private static final long CURVE_SUPPLY_TARGET = 1_000_000L;
    private static final long CURVE_EMISSION_COEFFICIENT = 10_000L;
    private static final int CURVE_EMISSION_TABLE_STEPS = 4;

    /** Testnet base (unpinned genesisSupply) with small curve constants and a chosen activation height. */
    private static NetworkParameters curveParams(long activationHeight) {
        return NetworkParameters.testnet().toBuilder()
            .supplyTarget(CURVE_SUPPLY_TARGET)
            .emissionCoefficient(CURVE_EMISSION_COEFFICIENT)
            .emissionTableSteps(CURVE_EMISSION_TABLE_STEPS)
            .emissionCurveHeight(activationHeight)
            .build();
    }

    @Test
    void degenerateCurveConstantsFailFastAtBuildTime() {
        // Non-positive supplyTarget must fail fast (data-model.md "Supply target S*": > 0).
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().supplyTarget(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().supplyTarget(-1).build());

        // Non-positive emissionCoefficient must fail fast (data-model.md "Curve coefficient c": > 0).
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().emissionCoefficient(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().emissionCoefficient(-1).build());

        // emissionTableSteps < 2 must fail fast (data-model.md "Table resolution N": >= 2).
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().emissionTableSteps(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().emissionTableSteps(1).build());
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().emissionTableSteps(-5).build());
        // The boundary itself is accepted.
        assertDoesNotThrow(
            () -> NetworkParameters.testnet().toBuilder().emissionTableSteps(2).build());

        // supplyTarget must be strictly above a pinned genesisSupply (data-model.md "Supply target
        // S*": "> genesisSupply when a pin exists"; contracts/emission-curve.md). Testnet is
        // unpinned by default (GENESIS_SUPPLY_UNPINNED), so pin genesisSupply explicitly here to
        // exercise the comparison.
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder()
                .genesisSupply(1_000_000L)
                .supplyTarget(1_000_000L) // equal: not strictly above
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder()
                .genesisSupply(1_000_000L)
                .supplyTarget(500_000L) // below
                .build());
        // Strictly above the pin is accepted.
        assertDoesNotThrow(() -> NetworkParameters.testnet().toBuilder()
            .genesisSupply(1_000_000L)
            .supplyTarget(1_000_001L)
            .build());
    }

    @Test
    void theCurveActivatesOnlyAtOrAboveItsHeight() {
        NetworkParameters active = curveParams(100);

        assertFalse(active.emissionCurveActiveAt(99));
        assertTrue(active.emissionCurveActiveAt(100));
        assertTrue(active.emissionCurveActiveAt(101));
        assertTrue(active.emissionCurveActiveAt(10_000L));

        // Next-block mirror: confirmed 98 -> next block 99 < 100 -> still inactive;
        // confirmed 99 -> next block 100 == activation height -> active. Same boundary shape as
        // activationPredicatesCarryTheBoundary above, but with emissionCurveHeight's inverted
        // ("0 = never") polarity.
        assertFalse(active.emissionCurveActiveForNextBlock(98));
        assertTrue(active.emissionCurveActiveForNextBlock(99));

        // The Long.MAX_VALUE sentinel (AccountView.confirmedHeight()'s "past every activation"
        // convention) must resolve true without the sentinel-safe subtraction overflowing.
        assertTrue(active.emissionCurveActiveForNextBlock(Long.MAX_VALUE));

        // emissionCurveHeight == 0 means NEVER (the powUpgradeHeight polarity), the inverse of
        // boxActivationHeight's "0 = from genesis". Never active at any height tried, including
        // 0 itself and the Long.MAX_VALUE edge on both predicates.
        NetworkParameters never = curveParams(0);
        assertFalse(never.emissionCurveActiveAt(0));
        assertFalse(never.emissionCurveActiveAt(1));
        assertFalse(never.emissionCurveActiveAt(10_000_000L));
        assertFalse(never.emissionCurveActiveAt(Long.MAX_VALUE));
        assertFalse(never.emissionCurveActiveForNextBlock(Long.MAX_VALUE));
    }

    @Test
    void belowActivationTheSupplyAwareRewardEqualsTheGeometricReward() {
        long[] parentSupplies = {0L, 1L, 1_000L, 500_000L, Long.MAX_VALUE / 2};

        // Case A: the curve is never active on this profile (emissionCurveHeight == 0) -- every
        // height reads geometric regardless of parentSupply.
        NetworkParameters never = curveParams(0);
        for (long height : new long[] {0L, 1L, 100L, 10_000L}) {
            for (long parentSupply : parentSupplies) {
                assertEquals(never.miningReward(height), never.miningReward(height, parentSupply),
                    "parentSupply must be ignored while the curve is inactive (height=" + height
                        + ", parentSupply=" + parentSupply + ")");
            }
        }

        // Case B: the curve activates at a later height -- below it, the two-arg form must still
        // equal the one-arg geometric reward, for any parentSupply.
        NetworkParameters activatesLater = curveParams(500);
        for (long height : new long[] {0L, 1L, 100L, 499L}) {
            for (long parentSupply : parentSupplies) {
                assertEquals(activatesLater.miningReward(height),
                    activatesLater.miningReward(height, parentSupply),
                    "below activation, the two-arg form must equal the one-arg geometric reward "
                        + "(height=" + height + ", parentSupply=" + parentSupply + ")");
            }
        }
    }

    @Test
    void theConsensusRewardClampsTheNegativeBranchToZero() {
        // Locks contracts/emission-curve.md §4's single clamp site: at the NetworkParameters
        // dispatch level, a parentSupply at/above supplyTarget (raw curve value negative or zero)
        // must never mint a negative reward. EmissionCurveTest separately allows raw() itself to
        // return negative -- that clamp belongs here, at the one consensus-facing call site.
        NetworkParameters active = curveParams(1);
        long parentSupply = CURVE_SUPPLY_TARGET * 2; // strictly above S* -> raw curve value < 0

        assertEquals(0L, active.miningReward(1, parentSupply));
        assertEquals(0L, active.miningReward(50, parentSupply));
    }

    @Test
    void shippedProfilesNeverActivateTheCurve() {
        // Decision 3 (research.md)/WI-9-style toBuilder() inheritance guard: mainnet ships
        // emissionCurveHeight == 0 (this feature only pins calibrated constants, feature 05
        // schedules the real height), and testnet()/devnet() derive from cleanMainnet().toBuilder()
        // without resetting it -- prove behaviourally, not just by reading the constant, that
        // neither silently inherits or introduces a non-zero curve height.
        long[] heights = {0L, 1L, 100L, 10_000_000L};
        for (long height : heights) {
            assertFalse(NetworkParameters.cleanMainnet().emissionCurveActiveAt(height),
                "cleanMainnet must never activate the curve in this feature, height=" + height);
            assertFalse(NetworkParameters.testnet().emissionCurveActiveAt(height),
                "testnet must never activate the curve in this feature, height=" + height);
            assertFalse(NetworkParameters.devnet().emissionCurveActiveAt(height),
                "devnet must never activate the curve in this feature, height=" + height);
        }
    }
}
