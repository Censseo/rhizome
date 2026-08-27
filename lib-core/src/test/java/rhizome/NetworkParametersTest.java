package rhizome;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.DifficultyAdjustment;
import rhizome.core.blockchain.EmissionCurve;
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
        //
        // Termination (research.md Decision 6 item 2, rewritten for the floor): the reward NEVER
        // reaches 0 under the floor -- once raw(S) drops below R_min the clamp holds it at exactly
        // R_min forever, so the recursion terminates on `reward == R_min` sustained, never on
        // `reward > 0` failing (that loop would never exit -- a suite-wide hang, not a test
        // failure). A hard iteration cap remains as a belt-and-braces hang guard.
        long floor = p.minerRevenueFloor();
        long supply = s0;
        long height = activationHeight;
        long reward = p.miningReward(height, supply);
        long previousReward = Long.MAX_VALUE;
        long supplyAtTau = -1;
        long lastSupplyAboveFloor = s0;
        int iterations = 0;
        while (reward > floor) {
            if (++iterations > 5_000_000) {
                fail("calibration recursion did not terminate at the floor -- hang guard");
            }
            // Hard invariant, asserted every iteration: reward is monotone non-increasing across
            // the entire run. The curve must never re-inflate as supply climbs toward S* --
            // WHITEPAPER.md determinism/no-surprise-inflation property for the emission rule.
            assertTrue(reward <= previousReward,
                "reward must be monotone non-increasing across the run: " + reward
                    + " > previous " + previousReward + " at supply=" + supply);
            previousReward = reward;
            lastSupplyAboveFloor = supply;

            long k = Math.max(1, supply / (1000 * c));
            supply += k * reward;
            height += k;
            reward = p.miningReward(height, supply);

            if (supplyAtTau < 0 && height >= tauBlocks) {
                supplyAtTau = supply;
            }
        }
        // The loop exited with reward == R_min (never 0 under the floor); the coarse stride above
        // BRACKETED the exact supply at which raw(S) first drops below R_min -- the floored
        // crossover -- but overshot it.
        long firstFloorSupply = supply; // miningReward(height, firstFloorSupply) == R_min

        // Supply at tau must have moved a substantial majority of the way from S0 toward S* --
        // a generous, empirically-verified band (measured ~71.7% at the shipped calibration; the
        // continuous-model estimate for this shape is 1 - e^-1 ~= 63.2%), not a tight pin: a
        // logarithmic decay never actually reaches S* in finitely many blocks.
        assertTrue(supplyAtTau > 0, "tau was never reached before the curve terminated");
        double fractionOfGapCovered = (double) (supplyAtTau - s0) / (sStar - s0);
        assertTrue(fractionOfGapCovered > 0.5 && fractionOfGapCovered < 0.95,
            "supply at tau should have covered a substantial majority of the S0->S* gap, was "
                + (fractionOfGapCovered * 100) + "%");

        // Floored crossover: the coarse stride above lands a single stride past the point where
        // the reward first clamps to exactly R_min (near S*, k grows to ~S/(1000c) while the
        // reward has shrunk to a few hundred base units, so one stride can leap tens of millions
        // of base units). Refine with a binary search on the same monotone function to land on
        // the EXACT smallest supply at which the floored reward is exactly R_min -- still running
        // the real curve, never hand-derived.
        long lo = lastSupplyAboveFloor; // miningReward(height, lo) > floor
        long hi = firstFloorSupply;     // miningReward(height, hi) == floor
        while (hi - lo > 1) {
            long mid = lo + (hi - lo) / 2;
            if (p.miningReward(height, mid) > floor) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        // Pinned EXACT floored crossover at the shipped mainnet calibration (S*=2_997_924_580_000,
        // c=23_750, N=256, R_min=800) -- a REGRESSION GUARD, not a derived constant. The
        // continuous estimate ln(S*/S) = R_min/c gives S ≈ 0.9669 × S* (research.md Decision 3);
        // this is the exact value the integer stepped table interpolates to. If a future change
        // to any curve constant or the floor shifts where raw crosses R_min, this assertion is
        // what catches it: update it deliberately, alongside the constants, should that ever
        // happen.
        assertEquals(2_898_445_750_238L, hi,
            "pinned floored crossover drifted -- update deliberately if the curve constants changed");

        // The RAW curve's terminal supply -- the first supply at which floor(c * ln(S*/S))
        // truncates to exactly 0 -- is no longer a consensus quantity under the floor (the MINTED
        // reward never reaches 0), but WHITEPAPER §5.3 still publishes the exact constant as
        // exhaustively verified. Keep it pinned here, against the raw curve, rather than letting
        // the floor silently retire the only guard the published number ever had. Same binary
        // search over the same monotone function, one branch further along.
        EmissionCurve rawCurve = EmissionCurve.build(sStar, c, p.emissionTableSteps());
        long rawLo = hi;     // raw(rawLo) > 0 (it is just under R_min, not yet truncated)
        long rawHi = sStar;  // raw(sStar) == 0 exactly (ln(S*/S*) == 0)
        assertTrue(rawCurve.raw(rawLo) > 0 && rawCurve.raw(rawHi) == 0,
            "the terminal-supply search must genuinely bracket the truncation point");
        while (rawHi - rawLo > 1) {
            long mid = rawLo + (rawHi - rawLo) / 2;
            if (rawCurve.raw(mid) > 0) {
                rawLo = mid;
            } else {
                rawHi = mid;
            }
        }
        assertEquals(2_997_797_290_244L, rawHi,
            "pinned terminal supply of the RAW curve drifted -- update deliberately, alongside "
                + "WHITEPAPER §5.3's calibration record, if the curve constants changed");

        // Post-target tail (research.md Decisions 3 & 5; contracts/miner-revenue-floor.md §5):
        // from the floored crossover onward the schedule is flat R_min, so supply crosses S* and
        // grows at exactly R_min per block forever (until feature 08's burn counterbalances it).
        // The reward is constant over the whole [crossover, ∞) span, so a stride of k blocks is
        // EXACT -- no approximation.
        long heightAtCrossover = height;
        long blocksToTarget = (sStar - hi) / floor;
        long heightPastTarget = heightAtCrossover + blocksToTarget + 1_000L; // clearly past S*
        long supplyPastTarget = hi + (heightPastTarget - heightAtCrossover) * floor;
        assertEquals(floor, p.miningReward(heightPastTarget, supplyPastTarget),
            "reward must be exactly R_min past the target at supply=" + supplyPastTarget);
        // Per-block growth is exactly R_min -- literal, no striding.
        long s = supplyPastTarget;
        long h = heightPastTarget;
        for (int i = 0; i < 10_000; i++) {
            long r = p.miningReward(h, s);
            assertEquals(floor, r, "reward must be exactly R_min per block past S* at supply=" + s);
            s += r;
            h++;
        }

        // R_min sits in the stated fraction band of R₀ (research.md Decision 3: 800 ≈ R₀/32.6 at
        // the provisional calibration). Decision 3 CONSIDERED [R₀/256 ≈ 102, R₀/16 ≈ 1 630] and
        // rejected both ends (R₀/256 is a symbolic budget, R₀/16 over-taxes holders); what it
        // SHIPPED sits in the middle, so the assertion pins the tighter [R₀/64, R₀/16] band the
        // shipped value actually lives in -- deliberately narrower than the range surveyed, or a
        // drift back toward either rejected end would pass. Checked on the pinned mainnet profile,
        // where R₀ is defined.
        double floorFractionOfR0 = (double) floor / launchReward;
        assertTrue(floorFractionOfR0 > 1.0 / 64.0 && floorFractionOfR0 < 1.0 / 16.0,
            "R_min/R₀ = " + floorFractionOfR0 + " outside the shipped [1/64, 1/16] band "
                + "(research.md Decision 3 surveyed [1/256, 1/16] and rejected both ends)");

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
    private static final long CURVE_MINER_REVENUE_FLOOR = 800L;

    /** Testnet base (unpinned genesisSupply) with small curve constants and a chosen activation height. */
    private static NetworkParameters curveParams(long activationHeight) {
        return NetworkParameters.testnet().toBuilder()
            .supplyTarget(CURVE_SUPPLY_TARGET)
            .emissionCoefficient(CURVE_EMISSION_COEFFICIENT)
            .emissionTableSteps(CURVE_EMISSION_TABLE_STEPS)
            .minerRevenueFloor(CURVE_MINER_REVENUE_FLOOR)
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
    void theConsensusRewardFloorsTheNegativeBranchAtRMin() {
        // Locks contracts/miner-revenue-floor.md §2's single clamp site (research.md Decision 6
        // item 1): at the NetworkParameters dispatch level, a parentSupply at/above supplyTarget
        // (raw curve value negative or zero) must never mint below R_min -- never zero, never
        // negative. EmissionCurveTest separately allows raw() itself to return negative -- that
        // clamp belongs here, at the one consensus-facing call site.
        NetworkParameters active = curveParams(1);
        long floor = active.minerRevenueFloor();
        long parentSupply = CURVE_SUPPLY_TARGET * 2; // strictly above S* -> raw curve value < 0

        // Exact equality at both heights; the ">= floor" form the invariant is stated in is
        // subsumed by it, so it is not asserted twice.
        assertEquals(floor, active.miningReward(1, parentSupply));
        assertEquals(floor, active.miningReward(50, parentSupply));

        // raw() itself stays signed (observability for feature 08): the table value above S* is a
        // negative number -- the floor lives at the clamp site, not inside the curve.
        EmissionCurve curve = EmissionCurve.build(
            CURVE_SUPPLY_TARGET, CURVE_EMISSION_COEFFICIENT, CURVE_EMISSION_TABLE_STEPS);
        assertTrue(curve.raw(parentSupply) < 0,
            "raw must stay signed (negative) above S*, was " + curve.raw(parentSupply));
        assertTrue(curve.raw(CURVE_SUPPLY_TARGET * 10) < 0);
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

    @Test
    void minerRevenueNeverFallsBelowTheFloor() {
        // Invariant-locking (constitution §Invariant-Locking Tests; research.md Decision 6 item 3):
        // on a curve-active profile, the scheduled base reward is >= R_min across the whole reachable
        // domain -- every table-step boundary, the crossover band where raw(S) first drops below
        // R_min (S ≈ 0.9669 × S* at the shipped calibration), S* itself, and the deep ratio-mirror
        // branch above it -- and equals R_min exactly where raw(S) < R_min (contracts/
        // miner-revenue-floor.md §2).
        long activationHeight = 1;
        NetworkParameters p = NetworkParameters.cleanMainnet().toBuilder()
            .emissionCurveHeight(activationHeight)
            .build();
        long floor = p.minerRevenueFloor();
        long sStar = p.supplyTarget();
        long stepWidth = sStar / p.emissionTableSteps();
        // raw() stays signed and is not exposed through NetworkParameters; rebuild the same table
        // (pure function of the published triple -- DI-11) so the test can read the raw value.
        EmissionCurve curve = EmissionCurve.build(sStar, p.emissionCoefficient(), p.emissionTableSteps());

        // Every table-step boundary, a unit either side (boundary effects), and segment midpoints.
        for (int i = 1; i <= p.emissionTableSteps(); i++) {
            long s = Math.multiplyExact((long) i, stepWidth);
            assertFlooredReward(p, curve, activationHeight, floor, s);
            assertFlooredReward(p, curve, activationHeight, floor, s - 1);
            assertFlooredReward(p, curve, activationHeight, floor, s + 1);
            assertFlooredReward(p, curve, activationHeight, floor, s - stepWidth / 2);
        }

        // The crossover band: raw(S) crosses R_min near S ≈ S*/exp(R_min/c) (0.9669 × S* at the
        // shipped calibration). Probe a window wide enough to bracket the crossing on both sides.
        long crossoverEstimate = (long) (sStar / Math.exp((double) floor / p.emissionCoefficient()));
        for (long delta = -200_000; delta <= 200_000; delta += 50) {
            assertFlooredReward(p, curve, activationHeight, floor,
                Math.addExact(crossoverEstimate, delta));
        }

        // S* itself (raw exactly 0) and just above it.
        assertFlooredReward(p, curve, activationHeight, floor, sStar);
        assertFlooredReward(p, curve, activationHeight, floor, sStar + 1);
        assertFlooredReward(p, curve, activationHeight, floor, sStar + 1_000);

        // Deep ratio-mirror branch: supplies far above S* mirror back toward the table head, where
        // raw is a large negative value -- the floor must hold there too, including wire-legal
        // extremes (SI-5: checked arithmetic fails loud rather than wrapping).
        for (long k : new long[] {2L, 3L, 10L, 100L}) {
            assertFlooredReward(p, curve, activationHeight, floor, Math.multiplyExact(sStar, k));
        }
        assertFlooredReward(p, curve, activationHeight, floor, Long.MAX_VALUE / 2);
    }

    /** Asserts the floor invariant at a single supply: reward >= R_min and reward == max(R_min, raw). */
    private static void assertFlooredReward(NetworkParameters p, EmissionCurve curve,
            long height, long floor, long supply) {
        long raw = curve.raw(supply);
        long reward = p.miningReward(height, supply);
        assertTrue(reward >= floor,
            "reward " + reward + " fell below the floor " + floor + " at supply=" + supply
                + " (raw=" + raw + ")");
        assertEquals(Math.max(floor, raw), reward,
            "reward at supply=" + supply + " must be exactly max(R_min, raw)=" + Math.max(floor, raw)
                + " (raw=" + raw + ")");
    }

    @Test
    void aNonPositiveMinerRevenueFloorRefusesToBuild() {
        // The floor is a consensus constant whose job is to guarantee a strictly positive subsidy;
        // a non-positive floor silently restores the zero-clamp cliff (research.md Decision 2) -- a
        // misconfiguration must fail fast at build time, not mid-chain.
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().minerRevenueFloor(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> NetworkParameters.testnet().toBuilder().minerRevenueFloor(-1).build());
        // A positive floor is accepted.
        assertDoesNotThrow(() -> NetworkParameters.testnet().toBuilder().minerRevenueFloor(1).build());
    }
}
