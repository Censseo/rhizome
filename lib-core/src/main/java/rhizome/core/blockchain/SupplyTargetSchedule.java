package rhizome.core.blockchain;

/**
 * The decaying supply target {@code S*(h)} (contracts/supply-target-schedule.md; 008-decaying-
 * supply-target): a value class computed from five pinned per-network constants, holding the
 * curve's monetary target at its pinned peak until a scheduled decay-start height and decaying it
 * geometrically once per epoch down to a pinned floor.
 *
 * <p>The target is a <b>pure function of height</b> — no clock, no chain state, no iteration
 * order — which is what keeps coinbase validation in the pre-PoW structural pass (no ledger read
 * is introduced) and makes reorg reversal structural (there is no target state to roll back). An
 * unscheduled profile ({@code decayStartHeight == 0}) is bit-for-bit indistinguishable from the
 * pre-feature implementation: {@link #targetAt} returns the peak everywhere, with no arithmetic
 * executed.
 *
 * <p><b>Sentinel polarity (read this before adding a sibling)</b>: {@code decayStartHeight == 0}
 * means <em>never scheduled</em> — the {@code powUpgradeHeight}/{@code emissionCurveHeight}
 * polarity, NOT the {@code boxActivationHeight}/{@code tokenActivationHeight} "0 = from genesis"
 * polarity. Both conventions live on {@code NetworkParameters} simultaneously; this class copies
 * the {@code powUpgradeHeight} one deliberately, and a height-gated rule on this class can never
 * treat {@code 0} as "active from the first block".
 *
 * <p><b>Generation at build time, fail-fast</b> (the {@code EmissionCurve} discipline): the
 * derived constants — {@link #epochsToFloor}, {@link #floorArrivalHeight},
 * {@link #perEpochReductionBound}, {@link #maxEvaluableSupply} — are computed once, here, by the
 * same bounded iteration {@link #targetAt} uses (so {@code epochsToFloor} is exact by construction
 * rather than a closed-form estimate that could disagree by one step), and every degenerate
 * configuration refuses at construction. A misconfigured profile must fail at node startup, never
 * mint wrong money mid-chain (FR-022). That discipline extends to the <b>inert</b> constants: at
 * the {@code startHeight == 0} sentinel the other four must all be {@code 0} too, so a profile
 * that states a decay but forgets its start height is refused rather than silently minting at the
 * peak forever (see {@link #build}).
 *
 * <p>The same build step materialises the decay recurrence into {@link #decayTable}, so
 * {@link #targetAt} is a bounds-checked array read rather than a per-call loop — see that field.
 */
public final class SupplyTargetSchedule {

    /**
     * Safety cap on the build-time {@code epochsToFloor} iteration. {@code num < den} guarantees
     * the decayed value strictly decreases every epoch, so the iteration always terminates — but
     * not always quickly enough for a boot path: a ratio absurdly close to 1 against a tiny step
     * could legally iterate for billions of epochs. Past this cap the calibration is refused at
     * construction (fail fast, the {@code EmissionCurve.build} discipline) with a message naming
     * the remedy. The shipped mainnet calibration iterates 555 times; a ratio of 0.999999 down to
     * a floor at half the peak iterates ~693 000 times — comfortably inside the cap — while
     * 0.9999999 (~6.93M epochs) also fits. Anything beyond 10M epochs to halve is not a
     * calibration a network can govern, and refusing it beats hanging node startup.
     */
    private static final long MAX_EPOCHS_TO_FLOOR = 10_000_000L;

    /**
     * How many decay epochs {@link #decayTable} materialises at most — 65 536 entries, 512 KB in
     * the worst case, allocated once per scheduled profile and never for an unscheduled one.
     * Sized to cover every calibration a network could plausibly govern (the shipped mainnet one
     * needs 556 entries, 4.4 KB; the decay fixtures need 8) with orders of magnitude to spare,
     * while refusing to turn {@link #MAX_EPOCHS_TO_FLOOR}'s legal-but-pathological ceiling into an
     * 80 MB allocation. Past the prefix {@link #targetAt} resumes the same recurrence from the
     * last tabulated value, so the cap costs correctness nothing — only the constant-time
     * guarantee, and only on a calibration no shipped profile approaches.
     */
    private static final int MAX_MEMOISED_EPOCHS = 65_536;

    /** {@link #MAX_MEMOISED_EPOCHS}, for the same-package test-fixtures bridge only. */
    static int maxMemoisedEpochs() {
        return MAX_MEMOISED_EPOCHS;
    }

    /** The table an unscheduled profile carries: empty, so the sentinel allocates nothing. */
    private static final long[] NO_DECAY = new long[0];

    /** The peak target {@code S*_peak} — always {@code > 0}; the value every unscheduled height sees. */
    private final long peak;
    /** Decay-start height {@code H_d}; {@code 0} = never (see the class javadoc for the polarity). */
    private final long startHeight;
    /** Epoch length {@code E} in blocks; {@code > 0} whenever scheduled. */
    private final long epochBlocks;
    /** Per-epoch ratio numerator; {@code 0 < num < den} whenever scheduled. */
    private final long num;
    /** Per-epoch ratio denominator; {@code 0 < num < den} whenever scheduled. */
    private final long den;
    /** The floor {@code S*_floor}; {@code 0 < floor < peak} whenever scheduled. */
    private final long floor;

    /**
     * Smallest {@code e >= 0} with {@code T(e) <= floor}, where {@code T(0) = peak} and
     * {@code T(k+1) = floor(T(k) * num / den)} — computed by the same iteration {@link #targetAt}
     * uses, so it is exact rather than a closed-form estimate (the continuous
     * {@code ln 2 / ln(den/num)} form disagrees with the iteration by one step at the shipped
     * calibration: 554 vs the exact 555; research.md §Decision 3 records the measurement).
     * {@code 0} on an unscheduled profile.
     */
    private final long epochsToFloor;

    /** {@code startHeight + epochsToFloor * epochBlocks}, checked; {@code 0} on an unscheduled profile. */
    private final long floorArrivalHeight;

    /**
     * {@code ceil(c * ln(den/num))} base units — the maximum amount one completed epoch can reduce
     * the scheduled subsidy by at any fixed supply (G5's published bound, asserted by sweep).
     * Computed by the SAME fixed-point logarithm the emission table is generated from
     * ({@code EmissionCurve.logRatioFloor}, floored one unit below a transcendental value, so
     * {@code +1} is the ceiling) — never a floating-point {@code Math.log}. {@code 0} on an
     * unscheduled profile.
     */
    private final long perEpochReductionBound;

    /**
     * {@code T(e)} for every completed-epoch count {@code e} in
     * {@code [0, min(epochsToFloor, MAX_MEMOISED_EPOCHS)]} — the decay recurrence materialised at
     * build time so {@link #targetAt} costs one array read instead of {@code e} multiply-divide
     * steps. {@code T(0) = peak}, and the entry at {@code epochsToFloor} (when tabulated) is the
     * first value at or below the floor, which {@link #targetAt} never reads: it early-returns
     * {@link #floor} there.
     *
     * <p>Not a cache: written once during construction, never mutated, never invalidated. It is a
     * different <em>encoding</em> of the same pure function of height, not a memo of chain state —
     * so it changes nothing about reorg reversal, which stays structural. Empty ({@link #NO_DECAY})
     * on an unscheduled profile, which therefore still allocates nothing and executes no
     * arithmetic (FR-004/G1).
     *
     * <p>The recurrence here and the one {@link #computeEpochsToFloor} counts with are the same
     * two operations in the same order; {@code TargetDecayVectorsTest} pins both against a
     * checked-in artifact of expected targets, so a divergence between them fails the build.
     */
    private final long[] decayTable;

    /**
     * The largest supply {@code EmissionCurve.raw(supply, targetAt(h))} can evaluate at any height
     * without the checked narrowing throwing; {@link Long#MAX_VALUE} on an unscheduled profile.
     * See {@link #maxEvaluableSupply()}.
     */
    private final long maxEvaluableSupply;

    private SupplyTargetSchedule(long peak, long startHeight, long epochBlocks,
            long num, long den, long floor, long epochsToFloor, long floorArrivalHeight,
            long perEpochReductionBound, long[] decayTable, long maxEvaluableSupply) {
        this.peak = peak;
        this.startHeight = startHeight;
        this.epochBlocks = epochBlocks;
        this.num = num;
        this.den = den;
        this.floor = floor;
        this.epochsToFloor = epochsToFloor;
        this.floorArrivalHeight = floorArrivalHeight;
        this.perEpochReductionBound = perEpochReductionBound;
        this.decayTable = decayTable;
        this.maxEvaluableSupply = maxEvaluableSupply;
    }

    /**
     * Validates every constraint of contracts/supply-target-schedule.md §1 and derives the
     * schedule's constants. Degenerate constants refuse here — a node that cannot state its own
     * monetary policy must not start, never fail mid-chain on the first decayed evaluation
     * (FR-022, the {@code EmissionCurve.build} discipline).
     *
     * <p>Constraints (§1): {@code peak > 0}; {@code startHeight >= 0}; and, whenever
     * {@code startHeight > 0} (i.e. the decay is actually scheduled): {@code epochBlocks > 0},
     * {@code 0 < num < den}, {@code 0 < floor < peak}, {@code peak * num} must fit a signed
     * 64-bit integer — that product is the largest intermediate {@link #targetAt}'s
     * multiply-then-divide can ever form, so proving it at construction proves evaluation can
     * never overflow (the {@code coefficient} must also be positive whenever scheduled, as the
     * reduction bound is derived from it) — and {@link #maxEvaluableSupply} must reach the peak,
     * so a calibration whose curve cannot be evaluated at its own target refuses here rather than
     * throwing mid-chain.
     *
     * <p><b>The sentinel is validated too</b>: at {@code startHeight == 0} the other four
     * constants must all be {@code 0}. They are inert there — but a profile that states
     * {@code epochBlocks}/{@code num}/{@code den}/{@code floor} and leaves the start height at its
     * default has almost certainly meant to schedule a decay, and the polarity makes that the easy
     * mistake to make (the class javadoc's sentinel note: {@code 0} means <em>never</em> here and
     * <em>from genesis</em> on the {@code boxActivationHeight} siblings). Silently discarding them
     * would mint at the peak forever with no diagnostic, which is precisely the mid-chain monetary
     * failure this method exists to prevent. Refusing costs a derived profile one explicit
     * {@code 0} per constant — which every shipped profile already states, since derived profiles
     * inherit silently (WI-9).
     *
     * @param peak the peak target {@code S*_peak}
     * @param startHeight decay-start height {@code H_d}; {@code 0} = never scheduled
     * @param epochBlocks epoch length {@code E} in blocks
     * @param num per-epoch ratio numerator
     * @param den per-epoch ratio denominator
     * @param floor the target floor {@code S*_floor}
     * @param coefficient the emission coefficient {@code c} (the same constant
     *            {@code NetworkParameters.emissionCoefficient} builds the curve's table from),
     *            used only to derive the per-epoch reduction bound
     * @throws IllegalArgumentException on any violated §1 constraint
     * @throws ArithmeticException if a derived constant overflows (checked arithmetic — it throws
     *             at construction, never wraps)
     */
    public static SupplyTargetSchedule build(long peak, long startHeight, long epochBlocks,
            long num, long den, long floor, long coefficient) {
        if (peak <= 0) {
            throw new IllegalArgumentException("peak must be > 0, was " + peak);
        }
        if (startHeight < 0) {
            throw new IllegalArgumentException("startHeight must be >= 0, was " + startHeight);
        }
        if (startHeight == 0) {
            // Unscheduled: the sentinel means "never". The remaining constants are inert, and are
            // required to SAY so -- see the javadoc: a stated decay with an unstated start height
            // is a misconfiguration, not an unscheduled profile.
            if (epochBlocks != 0 || num != 0 || den != 0 || floor != 0) {
                throw new IllegalArgumentException(
                    "startHeight == 0 means the decay is NEVER scheduled, so epochBlocks, num, "
                        + "den and floor must all be 0 -- was epochBlocks=" + epochBlocks
                        + ", ratio=" + num + "/" + den + ", floor=" + floor + ". Either set "
                        + "startHeight > 0 to schedule the decay these constants describe, or "
                        + "state them as 0 (note the polarity: 0 means NEVER here, unlike "
                        + "boxActivationHeight where 0 means from genesis)");
            }
            return new SupplyTargetSchedule(peak, 0, 0, 0, 0, 0, 0, 0, 0,
                NO_DECAY, Long.MAX_VALUE);
        }
        if (epochBlocks <= 0) {
            throw new IllegalArgumentException(
                "epochBlocks must be > 0 when a decay is scheduled, was " + epochBlocks);
        }
        if (num <= 0 || den <= num) {
            throw new IllegalArgumentException(
                "require 0 < num < den for a scheduled decay, was " + num + "/" + den);
        }
        if (floor <= 0 || floor >= peak) {
            throw new IllegalArgumentException(
                "require 0 < floor < peak for a scheduled decay, floor=" + floor
                    + ", peak=" + peak);
        }
        if (coefficient <= 0) {
            throw new IllegalArgumentException(
                "coefficient must be > 0 for a scheduled decay, was " + coefficient);
        }
        // Evaluation-time overflow proof: targetAt computes t * num for t <= peak, so
        // peak * num fitting a long is exactly the condition that no evaluation overflows.
        Math.multiplyExact(peak, num);

        long epochsToFloor = computeEpochsToFloor(peak, num, den, floor);
        long floorArrivalHeight = Math.addExact(startHeight,
            Math.multiplyExact(epochsToFloor, epochBlocks));
        long reductionBound = reductionBound(coefficient, num, den);
        long maxEvaluableSupply = maxEvaluableSupply(peak, floor);
        if (maxEvaluableSupply < peak) {
            throw new IllegalArgumentException(
                "the peak/floor ratio " + peak + "/" + floor + " leaves EmissionCurve.raw "
                    + "unevaluable at the target itself: the largest supply it can scale without "
                    + "overflowing a long is " + maxEvaluableSupply + ", below the peak " + peak
                    + ". Raise the floor or lower the peak");
        }
        return new SupplyTargetSchedule(peak, startHeight, epochBlocks, num, den, floor,
            epochsToFloor, floorArrivalHeight, reductionBound,
            decayTable(peak, num, den, epochsToFloor), maxEvaluableSupply);
    }

    /**
     * The decay recurrence materialised: {@code [T(0), T(1), ..., T(min(E_f, cap))]}, by the same
     * {@code t * num / den} step {@link #computeEpochsToFloor} counts with and {@link #targetAt}
     * falls back to. {@code peak * num} has already been proven to fit a long at this point, and
     * the sequence is non-increasing, so no entry can overflow.
     */
    private static long[] decayTable(long peak, long num, long den, long epochsToFloor) {
        long[] table = new long[(int) Math.min(epochsToFloor, MAX_MEMOISED_EPOCHS) + 1];
        table[0] = peak;
        for (int e = 1; e < table.length; e++) {
            table[e] = table[e - 1] * num / den;
        }
        return table;
    }

    /**
     * {@code floor(Long.MAX_VALUE * floor / peak)} — the largest supply the curve's scaled
     * argument {@code floor(supply * peak / liveTarget)} can narrow to a long at the SMALLEST live
     * target this schedule ever reaches, and therefore at every height. Computed in arbitrary
     * precision because the numerator overflows by construction.
     */
    private static long maxEvaluableSupply(long peak, long floor) {
        return java.math.BigInteger.valueOf(Long.MAX_VALUE)
            .multiply(java.math.BigInteger.valueOf(floor))
            .divide(java.math.BigInteger.valueOf(peak))
            .longValueExact(); // <= Long.MAX_VALUE since floor < peak
    }

    /**
     * The exact epochs-to-floor, by the same iteration {@link #targetAt} performs. Strictly
     * decreasing by construction ({@code num < den}), so the loop terminates; the
     * {@link #MAX_EPOCHS_TO_FLOOR} cap turns a pathological-but-legal calibration into a
     * construction-time refusal instead of a hung node boot.
     */
    private static long computeEpochsToFloor(long peak, long num, long den, long floor) {
        long t = peak;
        long epochs = 0;
        while (t > floor) {
            t = Math.multiplyExact(t, num) / den;
            epochs++;
            if (epochs > MAX_EPOCHS_TO_FLOOR) {
                throw new IllegalArgumentException(
                    "decay ratio " + num + "/" + den + " does not reach the floor " + floor
                        + " within " + MAX_EPOCHS_TO_FLOOR + " epochs -- not a calibratable "
                        + "schedule; raise the per-epoch step (larger den/num gap) or raise the "
                        + "floor");
            }
        }
        return epochs;
    }

    /**
     * {@code ceil(c * ln(den/num))} in integer-only arithmetic: the fixed-point floor of the
     * transcendental {@code c * ln(den/num)} is strictly below the true value, and a transcendental
     * is never an integer, so the ceiling is exactly {@code floor + 1}.
     */
    private static long reductionBound(long coefficient, long num, long den) {
        return Math.addExact(EmissionCurve.logRatioFloor(coefficient, den, num), 1);
    }

    /** The peak target {@code S*_peak}. */
    public long peak() {
        return peak;
    }

    /** The decay-start height {@code H_d}; {@code 0} = never (class javadoc states the polarity). */
    public long startHeight() {
        return startHeight;
    }

    /** The epoch length {@code E} in blocks; {@code 0} on an unscheduled profile. */
    public long epochBlocks() {
        return epochBlocks;
    }

    /** The per-epoch ratio numerator; {@code 0} on an unscheduled profile. */
    public long num() {
        return num;
    }

    /** The per-epoch ratio denominator; {@code 0} on an unscheduled profile. */
    public long den() {
        return den;
    }

    /** The target floor {@code S*_floor}; {@code 0} on an unscheduled profile. */
    public long floor() {
        return floor;
    }

    /** Whether this profile schedules a decay at all ({@code startHeight > 0}). */
    public boolean isScheduled() {
        return startHeight > 0;
    }

    /**
     * Exact epochs of decay from {@code startHeight} to the floor, computed at build time by the
     * same iteration {@link #targetAt} uses; {@code 0} on an unscheduled profile. This is also the
     * per-evaluation cost bound of {@link #targetAt} (G3), and {@code NetworkParametersTest}
     * asserts the shipped calibration's value against research.md §Decision 3.
     */
    public long epochsToFloor() {
        return epochsToFloor;
    }

    /**
     * The first height at which the target has reached the floor ({@code startHeight +
     * epochsToFloor * epochBlocks}); {@code 0} on an unscheduled profile. The target is exactly
     * the floor at and after this height, forever.
     */
    public long floorArrivalHeight() {
        return floorArrivalHeight;
    }

    /**
     * {@code ceil(c * ln(den/num))} base units — the published maximum amount one completed epoch
     * can reduce the scheduled subsidy by at any fixed supply (G5); {@code 0} on an unscheduled
     * profile. Mainnet: {@code 30}.
     */
    public long perEpochReductionBound() {
        return perEpochReductionBound;
    }

    /**
     * The largest supply {@code EmissionCurve.raw(supply, targetAt(h))} evaluates at any height
     * without throwing: {@code floor(Long.MAX_VALUE * floor / peak)}, i.e. {@code Long.MAX_VALUE}
     * divided by the schedule's total decay ratio. {@link Long#MAX_VALUE} on an unscheduled
     * profile, where the live target always equals the peak and the curve never scales its
     * argument at all.
     *
     * <p>Why it exists: the curve evaluates a live target below the peak by scaling its argument
     * to {@code floor(supply * peak / liveTarget)}, and that narrowing is <b>checked</b> — it
     * throws rather than wrapping (FR-023). So {@code raw} stopped being total over the whole
     * {@code long} domain the moment a decay could put the live target below the peak, and this is
     * the exact bound of what is left. Every consensus caller already treats an
     * {@code ArithmeticException} as a rejected block; publishing the bound is what lets an
     * observability caller — which does not catch it — reason about whether it can be reached.
     * Mainnet's {@code floor = peak/2} puts it at {@code Long.MAX_VALUE / 2} ≈ 4.6e18, roughly
     * 1.5 million times the peak target, so no supply a chain can commit approaches it.
     *
     * <p>{@link #build} refuses a calibration whose bound falls below the peak: a curve that
     * cannot be evaluated at its own target is a boot-time refusal, not a mid-chain surprise.
     */
    public long maxEvaluableSupply() {
        return maxEvaluableSupply;
    }

    /**
     * The decay-epoch index at {@code height}: {@code 0} on any height before the decay start
     * (including every height on a profile that never schedules one, so the index — and anything
     * keyed by it — is constant there), and {@code completedEpochs + 1} once decaying. The rule
     * lives HERE, beside {@link #targetAt}'s own {@code (height - startHeight) / epochBlocks},
     * so an observability cache keyed by the index cannot drift from the target it is caching
     * (the dashboard's browser-side copy mirrors this method deliberately — keep them in step).
     *
     * <p>Total over the whole {@code long} domain — unlike {@link #targetAt} it does not refuse a
     * negative height: this is an observability key, and a publication path must degrade rather
     * than throw. Any height below the start (negative included) is index {@code 0}.
     */
    public long epochIndexAt(long height) {
        if (!isScheduled() || height < startHeight) {
            return 0;
        }
        return (height - startHeight) / epochBlocks + 1;
    }

    /**
     * The supply target at {@code height} — total over the whole non-negative height domain,
     * non-increasing, strictly positive, and bounded at {@link #epochsToFloor} decay steps (G2,
     * G3). In practice it costs <b>none</b>: the recurrence is tabulated at build time
     * ({@link #decayTable}), so every height on every calibration a network could govern resolves
     * in one array read. The {@code epochsToFloor} bound still holds — it is now slack, not tight,
     * and only a calibration past {@link #MAX_MEMOISED_EPOCHS} epochs iterates at all.
     *
     * <p>Below {@code startHeight} (and everywhere on an unscheduled profile) this is exactly the
     * peak with <b>no arithmetic executed</b>, which is what makes an unscheduled profile
     * bit-for-bit indistinguishable from the pre-feature implementation. The first decayed value
     * appears at {@code startHeight + epochBlocks}: the height's completed-epoch count is
     * {@code (height - startHeight) / epochBlocks}, integer division.
     *
     * @throws IllegalArgumentException if {@code height < 0} (a caller bug — heights are
     *            non-negative everywhere in this codebase)
     */
    public long targetAt(long height) {
        return targetAt(height, null);
    }

    /**
     * The {@link #targetAt(long)} algorithm with an instrumentation seam: every decay
     * multiplication the evaluation performs is reported to {@code stepCounter} (null = silent).
     * Package-private on purpose — visible to this module's test fixtures source set (same
     * package, the {@code ChainEngineTestAccess} pattern) and to nothing else. {@code targetAt}
     * delegates here, so the counted path and the production path cannot diverge; the G3
     * iteration-bound proof asserts on these counts rather than on wall-clock time.
     *
     * <p>A tabulated read reports nothing, so on a fully memoised schedule — every shipped and
     * fixture profile — the count is {@code 0} at every height. That is the G3 bound met with room
     * to spare, not the instrumentation gone silent: the counter still fires once per step on the
     * post-cap fallback, which is the only path that iterates.
     *
     * @throws IllegalArgumentException if {@code height < 0}
     */
    long targetAt(long height, java.util.function.LongConsumer stepCounter) {
        if (height < 0) {
            throw new IllegalArgumentException("height must be non-negative, was " + height);
        }
        if (startHeight <= 0 || height < startHeight) {
            return peak; // exact; no arithmetic performed
        }
        long epochs = (height - startHeight) / epochBlocks; // completed epochs, floor division
        if (epochs >= epochsToFloor) {
            return floor;
        }
        if (epochs < decayTable.length) {
            // The whole schedule, for every calibration a network could govern: one array read,
            // no arithmetic, no steps reported. epochs < epochsToFloor holds above, so this entry
            // is strictly above the floor.
            return decayTable[(int) epochs];
        }
        // Past the memoised prefix -- reachable only on a calibration whose epochsToFloor exceeds
        // MAX_MEMOISED_EPOCHS. Resume the identical recurrence from the last tabulated value
        // rather than from the peak, so the cost is (epochs - cap) steps, never `epochs`.
        long t = decayTable[decayTable.length - 1];
        for (long i = decayTable.length - 1; i < epochs; i++) {
            // peak * num fits a long (proven at build), so this multiply cannot overflow.
            t = t * num / den;
            if (stepCounter != null) {
                stepCounter.accept(i);
            }
            if (t <= floor) {
                return floor;
            }
        }
        return t;
    }

    /**
     * The whole schedule in human-readable form, constants included — for boot logs and refusal
     * messages. Values only, no secret, no path.
     */
    @Override
    public String toString() {
        if (!isScheduled()) {
            return "SupplyTargetSchedule[unscheduled, peak=" + peak + "]";
        }
        return "SupplyTargetSchedule[peak=" + peak + ", startHeight=" + startHeight
            + ", epochBlocks=" + epochBlocks + ", ratio=" + num + "/" + den
            + ", floor=" + floor + ", epochsToFloor=" + epochsToFloor
            + ", floorArrivalHeight=" + floorArrivalHeight
            + ", perEpochReductionBound=" + perEpochReductionBound + "]";
    }
}
