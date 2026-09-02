package rhizome;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.SupplyTargetSchedule;
import rhizome.core.blockchain.SupplyTargetScheduleTestAccess;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.blockchain.VoteableParams;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * Locks the decaying supply target's law (008-decaying-supply-target,
 * contracts/supply-target-schedule.md §§1-3; G2, G3, G7): {@code S*(h)} is total, non-increasing
 * and strictly positive over the whole non-negative height domain including {@code Long.MAX_VALUE};
 * it is exactly the peak below the decay-start height (so an unscheduled profile is
 * indistinguishable from the pre-feature implementation), the first decayed value appears at
 * {@code decayStartHeight + epochBlocks} (the one boundary reading that had to be pinned — both
 * "at the start height" and "one epoch later" were plausible), it is exactly the floor from
 * {@code floorArrivalHeight} on, and evaluation costs at most {@code epochsToFloor} decay steps —
 * counted by instrumentation, never by wall clock. Degenerate constants refuse construction.
 */
class SupplyTargetScheduleTest {

    /**
     * The one decay-active fixture every later phase drives (T001): peak 1 000 000, decay starts
     * at height 10, epoch 5 blocks, ratio 9/10, floor 500 000. Derived constants, measured by the
     * normative iteration and pinned here so a fixture change that silently moves them fails:
     * epochsToFloor = 7 (1 000 000 → 900 000 → 810 000 → 729 000 → 656 100 → 590 490 → 531 441
     * → 478 296 ≤ 500 000), floorArrivalHeight = 10 + 7×5 = 45, perEpochReductionBound =
     * ⌈10 000 × ln(10/9)⌉ = ⌈1053.6⌉ = 1054.
     */
    private static NetworkParameters decayActive() {
        return CurveActiveNetwork.decayActiveTestnet();
    }

    // ---- G2: the target's law over the whole height domain ----

    @Test
    void theTargetIsTotalNonIncreasingAndStrictlyPositiveAcrossTheWholeHeightDomain() {
        NetworkParameters params = decayActive();
        SupplyTargetSchedule schedule = params.supplyTargetSchedule();

        // Boundary-dense sweep across the schedule's whole lifecycle plus long strides beyond it,
        // ending at the sentinel height itself.
        List<Long> heights = new ArrayList<>();
        for (long h = 0; h <= schedule.floorArrivalHeight() + 3 * schedule.epochBlocks(); h++) {
            heights.add(h);
        }
        long stride = 7919L; // prime stride past the floor: monotonicity has to survive big jumps
        for (long h = schedule.floorArrivalHeight(); h > 0; h -= stride) {
            heights.add(h);
        }
        heights.add(Long.MAX_VALUE);

        long previous = Long.MAX_VALUE;
        for (long h : heights) {
            long target = schedule.targetAt(h);
            assertTrue(target > 0,
                "the target must be strictly positive at height " + h + ", was " + target);
            assertTrue(target <= previous,
                "the target must be non-increasing: target(" + (h - 1) + ")=" + previous
                    + " < target(" + h + ")=" + target);
            previous = target;
        }

        // Totality at the extremes explicitly: never throws, never wraps negative.
        assertTrue(schedule.targetAt(Long.MAX_VALUE) == schedule.floor(),
            "past the floor arrival the target is the floor, forever — including Long.MAX_VALUE");
    }

    @Test
    void belowTheDecayStartHeightTheTargetIsExactlyThePeakWithNoDecayApplied() {
        NetworkParameters params = decayActive();
        SupplyTargetSchedule schedule = params.supplyTargetSchedule();
        assertTrue(schedule.isScheduled(), "fixture sanity: the decay is scheduled");

        for (long h = 0; h < schedule.startHeight(); h++) {
            assertEquals(params.supplyTarget(), schedule.targetAt(h),
                "below the decay-start height the target is exactly the peak at height " + h);
        }
    }

    @Test
    void anUnscheduledProfileHoldsThePeakEveryWhereAndIsBitForBitThePreFeatureBehaviour() {
        // The shipped testnet profile never schedules a decay (0 = never, the powUpgradeHeight
        // polarity): its schedule must hold the peak at EVERY height with no arithmetic — the
        // structural form of "nothing mints differently" (FR-004/G1).
        NetworkParameters params = NetworkParameters.testnet();
        SupplyTargetSchedule schedule = params.supplyTargetSchedule();
        assertFalse(schedule.isScheduled(), "testnet never schedules the decay");
        assertEquals(0, schedule.startHeight());
        assertEquals(0, schedule.epochsToFloor());
        assertEquals(0, schedule.floorArrivalHeight());
        assertEquals(0, schedule.perEpochReductionBound());

        long[] heights = {0, 1, 1_000, Long.MAX_VALUE / 2, Long.MAX_VALUE};
        for (long h : heights) {
            assertEquals(params.supplyTarget(), schedule.targetAt(h),
                "an unscheduled profile's target is the peak at every height, including " + h);
        }
    }

    @Test
    void theTargetIsExactlyTheFloorFromTheFloorArrivalHeightOn() {
        SupplyTargetSchedule schedule = decayActive().supplyTargetSchedule();

        long arrival = schedule.floorArrivalHeight();
        assertEquals(45L, arrival, "fixture floor arrival pinned (10 + 7 x 5)");
        for (long h = arrival; h <= arrival + 3 * schedule.epochBlocks(); h++) {
            assertEquals(schedule.floor(), schedule.targetAt(h),
                "the target must be exactly the floor at height " + h);
        }
        assertEquals(schedule.floor(), schedule.targetAt(Long.MAX_VALUE));
        // And the epoch before arrival is still strictly above it: the floor arrives exactly
        // once, not gradually.
        assertTrue(schedule.targetAt(arrival - 1) > schedule.floor(),
            "the epoch before floor arrival must still be above the floor");
    }

    // ---- T013: the one boundary reading that had to be pinned ----

    @Test
    void theTargetAtExactlyTheDecayStartHeightIsThePeakAndTheFirstDecayedValueAppearsOneEpochLater() {
        SupplyTargetSchedule schedule = decayActive().supplyTargetSchedule();
        long start = schedule.startHeight();
        long epoch = schedule.epochBlocks();

        // Spec §Edge Cases: both "decayed at start" and "decayed one epoch after start" were
        // plausible readings of the schedule. This pins the latter: the height's COMPLETED
        // epochs are floor((h - start) / epoch), so the start height itself has zero completed
        // epochs and still pays the peak.
        assertEquals(schedule.peak(), schedule.targetAt(start),
            "at exactly decayStartHeight the target is the peak (zero completed epochs)");
        assertEquals(schedule.peak(), schedule.targetAt(start + epoch - 1),
            "every height of the first epoch still holds the peak");
        assertEquals(schedule.peak() * schedule.num() / schedule.den(),
            schedule.targetAt(start + epoch),
            "the first decayed value appears exactly at decayStartHeight + epochBlocks "
                + "(one completed epoch's decay, no floor clamp this early)");
    }

    // ---- T014 / G3: the iteration bound, by instrumentation ----

    @Test
    void evaluatingAnyHeightCostsAtMostEpochsToFloorDecaySteps() {
        SupplyTargetSchedule schedule = decayActive().supplyTargetSchedule();
        long bound = schedule.epochsToFloor();
        assertEquals(7L, bound, "fixture epochsToFloor pinned");

        // Every height the fixture's lifecycle covers, past the floor, and the sentinel height —
        // none may cost more than the published bound (FR-010, SC-014). Counted, not timed.
        List<Long> heights = new ArrayList<>();
        for (long h = 0; h <= schedule.floorArrivalHeight() + 5 * schedule.epochBlocks(); h++) {
            heights.add(h);
        }
        heights.add(Long.MAX_VALUE / 2);
        heights.add(Long.MAX_VALUE);
        for (long h : heights) {
            long[] steps = {0};
            SupplyTargetScheduleTestAccess.targetAt(schedule, h, s -> steps[0]++);
            assertTrue(steps[0] <= bound,
                "evaluation at height " + h + " cost " + steps[0] + " decay steps, over the "
                    + "published bound of " + bound);
        }
    }

    @Test
    void aFullyMemoisedScheduleCostsZeroDecayStepsAtEveryHeightAndStillYieldsTheExactTargets() {
        SupplyTargetSchedule schedule = decayActive().supplyTargetSchedule();
        long start = schedule.startHeight();
        long epoch = schedule.epochBlocks();
        long bound = schedule.epochsToFloor();
        assertTrue(bound <= SupplyTargetScheduleTestAccess.maxMemoisedEpochs(),
            "fixture sanity: E_f = " + bound + " sits inside the memoised prefix, so the "
                + "constant-time path is the one under test here");

        // The build-time table makes every evaluation an array read: ZERO decay steps at every
        // height — below the start (no arithmetic at all), across the decaying stretch (tabulated)
        // and at or past E_f (the early floor return). The G3 bound of E_f steps still holds; it
        // is now slack. What must NOT weaken with it is the value, so each height is checked
        // against the recurrence re-run independently right here.
        long expected = schedule.peak();
        for (long e = 0; e <= bound + 3; e++) {
            long h = start + e * epoch;
            long[] steps = {0};
            long target = SupplyTargetScheduleTestAccess.targetAt(schedule, h, s -> steps[0]++);
            assertEquals(0, steps[0],
                "evaluation at height " + h + " (completed epoch " + e + ") must cost zero decay "
                    + "steps from the memoised table, cost " + steps[0]);
            if (e >= bound) {
                assertEquals(schedule.floor(), target,
                    "at or past E_f the early return yields the floor");
                continue;
            }
            assertEquals(expected, target,
                "the tabulated target at completed epoch " + e + " must equal the recurrence "
                    + "re-run independently");
            assertTrue(target > schedule.floor(),
                "below E_f the target is still strictly above the floor at completed epoch " + e);
            expected = expected * schedule.num() / schedule.den();
        }

        long[] belowStart = {0};
        SupplyTargetScheduleTestAccess.targetAt(schedule, start - 1, s -> belowStart[0]++);
        assertEquals(0, belowStart[0], "below the decay start no arithmetic runs at all");
    }

    @Test
    void aScheduleTooLongToMemoiseFallsBackToTheIterationResumedFromTheLastTabulatedEpoch() {
        // The one path the shipped and fixture calibrations never take. A ratio of 99999/100000
        // down to half the peak needs ~69 310 epochs (ln 2 / ln(100000/99999)) — past the memo
        // cap, so heights beyond it iterate. The fallback must resume from the LAST TABULATED
        // value, not from the peak: that is what keeps the cost (epochs - cap) rather than epochs.
        int cap = SupplyTargetScheduleTestAccess.maxMemoisedEpochs();
        SupplyTargetSchedule schedule = SupplyTargetSchedule.build(
            1_000_000_000L, 1L, 1L, 99_999L, 100_000L, 500_000_000L, 10_000L);
        assertTrue(schedule.epochsToFloor() > cap,
            "fixture sanity: E_f = " + schedule.epochsToFloor() + " must exceed the memo cap "
                + cap + " for the fallback to be reachable at all");

        // Inside the prefix: still zero steps.
        long[] tabulated = {0};
        SupplyTargetScheduleTestAccess.targetAt(schedule, 1 + cap - 1, s -> tabulated[0]++);
        assertEquals(0, tabulated[0], "an epoch inside the prefix is still a plain array read");

        // Past it: exactly the epochs the prefix does not cover, never the full count.
        long epochsPast = 1_000L;
        long height = 1 + cap + epochsPast; // epochBlocks == 1, decay starts at 1
        long[] steps = {0};
        long target = SupplyTargetScheduleTestAccess.targetAt(schedule, height, s -> steps[0]++);
        assertEquals(epochsPast, steps[0],
            "the fallback must cost only the epochs past the memoised prefix (resumed from the "
                + "last tabulated value), cost " + steps[0]);
        assertTrue(target > schedule.floor() && target < schedule.peak(),
            "the resumed value must still be a genuine mid-decay target, was " + target);

        // And it must agree with the recurrence run from scratch — resuming must not drift.
        long fromScratch = schedule.peak();
        for (long e = 0; e < cap + epochsPast; e++) {
            fromScratch = fromScratch * schedule.num() / schedule.den();
        }
        assertEquals(fromScratch, target,
            "the resumed evaluation must equal the recurrence run from the peak");
    }

    // ---- T015 / G7: degenerate constants refuse construction; overflow never wraps ----

    @Test
    void constructionRefusesEveryDegenerateConstantOfContractSection1() {
        long peak = 1_000_000L, start = 10L, epoch = 5L, num = 9L, den = 10L, floor = 500_000L, c = 10_000L;

        // peak > 0
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(0, start, epoch, num, den, floor, c),
            "peak == 0 must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(-1, start, epoch, num, den, floor, c),
            "negative peak must refuse");
        // H_d >= 0
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, -1, epoch, num, den, floor, c),
            "negative decay-start height must refuse");
        // E > 0 when scheduled
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, 0, num, den, floor, c),
            "zero epoch length must refuse when scheduled");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, -5, num, den, floor, c),
            "negative epoch length must refuse when scheduled");
        // 0 < num < den when scheduled
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, epoch, 0, den, floor, c),
            "zero ratio numerator must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, epoch, -1, den, floor, c),
            "negative ratio numerator must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, epoch, num, num, floor, c),
            "ratio 1 (num == den) never decays and must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, epoch, den, num, floor, c),
            "inverted ratio must refuse");
        // 0 < floor < peak when scheduled
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, epoch, num, den, 0, c),
            "zero floor must refuse when scheduled");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, epoch, num, den, -1, c),
            "negative floor must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, epoch, num, den, peak, c),
            "floor at the peak never decays and must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, epoch, num, den, peak + 1, c),
            "floor above the peak must refuse");
        // coefficient > 0 when scheduled (the reduction bound derives from it)
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, start, epoch, num, den, floor, 0),
            "zero coefficient must refuse when scheduled");
        // S*_peak * num must fit a signed 64-bit integer: this is the proof that
        // targetAt's multiply-then-divide can never overflow mid-chain (FR-022).
        assertThrows(ArithmeticException.class,
            () -> SupplyTargetSchedule.build(Long.MAX_VALUE / 2, start, epoch, 3, 4,
                Long.MAX_VALUE / 4, c),
            "peak * num overflowing a long must refuse at construction");
    }

    @Test
    void anUnscheduledProfileCarryingDecayConstantsRefusesInsteadOfDiscardingThemSilently() {
        // The polarity trap: 0 means NEVER here, but "from genesis" on boxActivationHeight and
        // its siblings. An operator who states a full decay and leaves the start height at its
        // default has described a schedule the node would otherwise DISCARD — minting at the peak
        // forever with no diagnostic. Each constant alone must be enough to refuse.
        long peak = 1_000_000L, epoch = 5L, num = 9L, den = 10L, floor = 500_000L, c = 10_000L;

        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, 0, epoch, 0, 0, 0, c),
            "an epoch length stated without a start height must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, 0, 0, num, 0, 0, c),
            "a ratio numerator stated without a start height must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, 0, 0, 0, den, 0, c),
            "a ratio denominator stated without a start height must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, 0, 0, 0, 0, floor, c),
            "a floor stated without a start height must refuse");
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(peak, 0, epoch, num, den, floor, c),
            "the whole schedule stated without a start height must refuse — the exact "
                + "misconfiguration this guard exists for");

        // The genuinely unscheduled profile — every inert constant stated as 0 — still builds,
        // and is still the peak-everywhere, no-arithmetic schedule (FR-004/G1).
        SupplyTargetSchedule unscheduled = SupplyTargetSchedule.build(peak, 0, 0, 0, 0, 0, c);
        assertFalse(unscheduled.isScheduled());
        assertEquals(peak, unscheduled.targetAt(Long.MAX_VALUE));
    }

    @Test
    void everyShippedProfileStatesItsDecayConstantsCoherentlyRatherThanInheritingThem() {
        // The guard above only bites if the shipped profiles state their zeros — testnet and
        // devnet derive from cleanMainnet(), which DOES schedule a decay, so silent inheritance
        // would now refuse their construction outright. That they build at all is the assertion.
        for (NetworkParameters params : new NetworkParameters[] {
                NetworkParameters.cleanMainnet(), NetworkParameters.testnet(),
                NetworkParameters.devnet(), CurveActiveNetwork.curveActiveTestnet(),
                CurveActiveNetwork.decayActiveTestnet()}) {
            SupplyTargetSchedule schedule = params.supplyTargetSchedule();
            if (schedule.isScheduled()) {
                continue;
            }
            assertEquals(0, params.decayEpochBlocks(),
                params.networkName() + " is unscheduled and must state epochBlocks = 0");
            assertEquals(0, params.decayNum(),
                params.networkName() + " is unscheduled and must state num = 0");
            assertEquals(0, params.decayDen(),
                params.networkName() + " is unscheduled and must state den = 0");
            assertEquals(0, params.supplyTargetFloor(),
                params.networkName() + " is unscheduled and must state the floor as 0");
        }
    }

    @Test
    void theMaxEvaluableSupplyBoundsTheCurvesScaledArgumentAndIsProvenAtConstruction() {
        // FR-023's domain, published: raw() stopped being total over long the moment the live
        // target could fall below the peak, and this is exactly what is left.
        SupplyTargetSchedule unscheduled = SupplyTargetSchedule.build(1_000_000L, 0, 0, 0, 0, 0, 10_000L);
        assertEquals(Long.MAX_VALUE, unscheduled.maxEvaluableSupply(),
            "an unscheduled profile never scales its argument, so nothing is out of domain");

        SupplyTargetSchedule mainnet =
            NetworkParameters.cleanMainnet().supplyTargetSchedule();
        assertEquals(Long.MAX_VALUE / 2, mainnet.maxEvaluableSupply(),
            "mainnet's floor at half the peak halves the domain, and no more");
        assertTrue(mainnet.maxEvaluableSupply() > 1_000L * mainnet.peak(),
            "sanity: the bound sits orders of magnitude above any supply a chain can commit");

        // The bound is tight in both directions: at it the curve evaluates, past it it throws.
        SupplyTargetSchedule steep =
            SupplyTargetSchedule.build(1_000_000L, 1L, 1L, 1L, 2L, 3L, 10_000L);
        var curve = rhizome.core.blockchain.EmissionCurve.build(1_000_000L, 10_000L, 16);
        long deepestTarget = steep.targetAt(Long.MAX_VALUE);
        assertEquals(steep.floor(), deepestTarget, "sanity: the deepest target is the floor");
        assertDoesNotThrow(() -> curve.raw(steep.maxEvaluableSupply(), deepestTarget),
            "the published bound must itself be evaluable");
        assertThrows(ArithmeticException.class,
            () -> curve.raw(steep.maxEvaluableSupply() + 1, deepestTarget),
            "one base unit past the bound must throw — the bound is exact, not indicative");

        // A calibration whose curve cannot be evaluated at its own target refuses at build time
        // rather than throwing mid-chain (the FR-022 discipline extended to FR-023's domain).
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(Long.MAX_VALUE / 4, 1L, 1L, 1L, 2L, 1L, 10_000L),
            "a peak/floor ratio that puts the peak itself out of the curve's domain must refuse");
    }

    @Test
    void aRatioTooCloseToOneToReachTheFloorWithinTheBoundedEpochCapRefusesConstruction() {
        // peak 9e10 against den 1e8: each step decrements by ceil(t/den), so once t drops
        // below den the decrement is exactly 1 per step and reaching floor=1 takes ~1e8 epochs --
        // past MAX_EPOCHS_TO_FLOOR (1e7). peak * num ~= 9e18 still fits a long, so the refusal
        // comes from the epoch cap, not the overflow proof. The refusal is the documented
        // alternative to hanging node boot inside the exact-iteration search.
        assertThrows(IllegalArgumentException.class,
            () -> SupplyTargetSchedule.build(9_000_000_000L, 1L, 1L, 99_999_999L, 100_000_000L,
                1L, 10_000L),
            "a decay that cannot reach its floor within the bounded epoch count must refuse "
                + "at construction, not hang the boot");
    }

    @Test
    void theScaledEvaluationArgumentNarrowsCheckedAndOverflowsRatherThanWrapping() {
        // FR-023/G7: a legal-but-pathological floor (3 base units against a peak of 1M) sends
        // the scaled argument floor(S * peak / T) far past a long at extreme supplies. The
        // narrowing must THROW (checked), never wrap into a value that happens to look valid.
        SupplyTargetSchedule pathological =
            SupplyTargetSchedule.build(1_000_000L, 1L, 1L, 1L, 2L, 3L, 10_000L);
        var curve = rhizome.core.blockchain.EmissionCurve.build(1_000_000L, 10_000L, 16);
        long hugeSupply = Long.MAX_VALUE / 2 + 1000; // x2 clears Long.MAX_VALUE by construction
        long decayedTarget = pathological.targetAt(2); // first decayed height: peak/2, != peak
        assertThrows(ArithmeticException.class, () -> curve.raw(hugeSupply, decayedTarget),
            "the scaled argument must narrow checked -- overflow throws, it never wraps");
    }

    // ---- T018 (vote half) / FR-009: the decay is outside governance ----

    @Test
    void theDecayConstantsAreAbsentFromTheMinerVoteSurface() {
        // Structural: VoteableParams is the entire surface a miner vote can move. Its fields and
        // vote codes are pinned here so a future decay constant added to it fails this test.
        var voteFields = java.util.Arrays.stream(VoteableParams.class.getDeclaredFields())
            .map(java.lang.reflect.Field::getName)
            .filter(name -> !name.startsWith("this$") && !name.equals("$JR"))
            .toList();
        assertTrue(voteFields.containsAll(List.of("storageFeeFactor", "minValuePerByte")),
            "VoteableParams must still carry the two known votables");
        assertTrue(voteFields.stream().noneMatch(name ->
                name.toLowerCase().contains("decay") || name.toLowerCase().contains("supplytarget")
                    || name.toLowerCase().contains("floor")),
            "no decay/supply-target constant may become a VoteableParams field; found " + voteFields);

        var voteCodes = java.util.Arrays.stream(VoteableParams.class.getFields())
            .filter(f -> f.getType() == int.class)
            .collect(java.util.stream.Collectors.toMap(java.lang.reflect.Field::getName,
                f -> {
                    try {
                        return f.getInt(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }));
        assertEquals(java.util.Map.of("ABSTAIN", 0, "STORAGE_FEE_FACTOR", 1, "MIN_VALUE_PER_BYTE", 2),
            voteCodes, "the vote code space is closed: only the two box knobs are votable");

        // Behavioural: an epoch of extreme votes moves the votable knob and nothing else —
        // supplyTargetAt is bit-identical before and after the boundary, at heights on both
        // sides of the decay start.
        NetworkParameters params = decayActive().toBuilder()
            .genesisDifficulty(3).minDifficulty(3)
            .votingEpochLength(4)
            .build();
        AtomicLong clock = new AtomicLong(1_000_000L);
        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, params.chainId()))
            .clock(clock::get).build();
        PublicAddress miner = PublicAddress.random();

        long beforeStart = params.supplyTargetAt(5);
        long atBoundaryHeight = params.supplyTargetAt(16); // past the decay start, mid-decay
        for (int v = 0; v < 4; v++) {
            long height = engine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder().id((int) height)
                .timestamp(clock.addAndGet(1000)).difficulty(engine.difficulty())
                .lastBlockHash(engine.tipHash()).vote(VoteableParams.MIN_VALUE_PER_BYTE)
                .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
            long parentSupply = engine.headerAt(engine.height()).supply();
            b.addTransaction(Transaction.of(miner,
                new TransactionAmount(params.miningReward(height, parentSupply))));
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
            assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS, engine.addBlock(b));
        }
        // The boundary passed (a vote moved the votable knob); the decay did not move.
        assertTrue(engine.voteableParams()[1] != params.minValuePerByte(),
            "sanity: the voted epoch boundary genuinely fired");
        assertEquals(beforeStart, params.supplyTargetAt(5),
            "a vote may not move the target below the decay start");
        assertEquals(atBoundaryHeight, params.supplyTargetAt(16),
            "a vote may not move the decayed target");
        assertEquals(params.supplyTargetSchedule(), engine.params().supplyTargetSchedule(),
            "the schedule itself is immutable across the boundary");
    }
}
