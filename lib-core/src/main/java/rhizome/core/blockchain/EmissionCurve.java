package rhizome.core.blockchain;

import java.math.BigInteger;

/**
 * The supply-targeted logarithmic emission curve (contracts/emission-curve.md sections 2-3;
 * research.md Decisions 1-2): a stepped table generated once, at construction time, from three
 * consensus constants -- {@code supplyTarget} ({@code S*}), {@code coefficient} ({@code c}) and
 * {@code steps} ({@code N}) -- and evaluated afterward in O(1) integer arithmetic, no
 * {@link BigInteger}, no floating point.
 *
 * <p><b>Generation</b> (build time, {@link #build}): for step {@code i} in {@code [1, N]},
 * position {@code S_i = i * floor(S* / N)} and value {@code table[i] = floor(c * ln(S* / S_i))}.
 * {@code ln} of the ratio is computed by the successive-squaring fixed-point algorithm: reduce
 * the ratio into {@code [1, 2)} counting doublings (the integer part of {@code log2}), extract
 * {@link #LOG2_FRAC_BITS} fractional bits of {@code log2} by repeated squaring with floor
 * truncation at every step, then convert {@code log2 -> ln} by multiplying the published
 * fixed-point constant {@link #LN2_Q62} (since {@code ln(x) = log2(x) * ln 2}). Every
 * intermediate is {@link BigInteger}; the final value is asserted to fit in a {@code long} by
 * construction ({@link BigInteger#longValueExact()} -- throws if a misconfigured profile ever
 * produces a table entry too large to mint).
 *
 * <p><b>Evaluation</b> ({@link #raw}): one implicit point beyond the table, {@code S_(N+1) = S*}
 * with {@code table[N+1] = 0} (exact -- {@code ln(S* / S*) = 0}), is what the last real segment
 * {@code [S_N, S*)} interpolates against -- this is what keeps the curve continuous and exactly
 * zero at {@code S*} even when {@code N} does not divide {@code S*} evenly (the mainnet case, and
 * this class's own test constants mirror it deliberately). Below the first step
 * ({@code S < S_1}, including {@code 0}) the curve holds {@code table[1]} (monotone extension --
 * unreachable on a well-formed chain, where supply never falls below the pinned genesis supply).
 * At or above {@code S*} the curve mirrors the same table:
 * {@code raw(S) = -interp(floor(S* * S* / S))}, with one correction the ratio-mirror identity
 * alone does not supply -- see the comment on {@link #raw}.
 */
public final class EmissionCurve {

    /**
     * Working precision (bits) of the fixed-point mantissa carried through the reduction and
     * squaring steps of {@link #logTableEntry}. One bit of headroom over {@link #LOG2_FRAC_BITS}
     * so the mantissa's own scale never coincides with the comparison threshold by construction.
     */
    private static final int MANTISSA_BITS = 64;

    /** Number of squaring iterations = number of fractional {@code log2} bits extracted. */
    private static final int LOG2_FRAC_BITS = 63;

    /** Fractional-bit width of {@link #LN2_Q62}. */
    private static final int LN2_FRAC_BITS = 62;

    /** {@code floor(ln 2 * 2^62)} -- the published fixed-point constant converting log2 to ln. */
    private static final long LN2_Q62 = 3_196_577_161_300_663_914L;

    private static final BigInteger LN2_Q62_BIG = BigInteger.valueOf(LN2_Q62);
    private static final BigInteger MANTISSA_OVERFLOW = BigInteger.ONE.shiftLeft(MANTISSA_BITS + 1);

    private final long supplyTarget;
    private final long coefficient;
    private final int steps;
    private final long stepWidth;

    /** 1-indexed; {@code table[0]} is unused so {@code table[i]} lines up with step {@code i}. */
    private final long[] table;

    private EmissionCurve(long supplyTarget, long coefficient, int steps, long stepWidth, long[] table) {
        this.supplyTarget = supplyTarget;
        this.coefficient = coefficient;
        this.steps = steps;
        this.stepWidth = stepWidth;
        this.table = table;
    }

    /**
     * Generates the stepped table from {@code (supplyTarget, coefficient, steps)}. Degenerate
     * constants fail fast rather than producing a curve that silently misbehaves at evaluation
     * time (the discipline {@code NetworkParameters}'s constructor uses for every consensus
     * constant).
     *
     * @throws IllegalArgumentException if {@code supplyTarget <= 0}, {@code coefficient <= 0},
     *             {@code steps < 2}, or {@code supplyTarget} is too small to give the first step
     *             a positive width
     * @throws ArithmeticException if a generated table entry does not fit in a {@code long}, or
     *             the constants are so extreme that {@link #interpolate}'s own
     *             {@code (vHi - vLo) * (point - sLo)} product could overflow at evaluation time
     */
    public static EmissionCurve build(long supplyTarget, long coefficient, int steps) {
        if (supplyTarget <= 0) {
            throw new IllegalArgumentException("supplyTarget must be > 0, was " + supplyTarget);
        }
        if (coefficient <= 0) {
            throw new IllegalArgumentException("coefficient must be > 0, was " + coefficient);
        }
        if (steps < 2) {
            throw new IllegalArgumentException("steps must be >= 2, was " + steps);
        }
        long stepWidth = supplyTarget / steps;
        if (stepWidth <= 0) {
            throw new IllegalArgumentException(
                "supplyTarget " + supplyTarget + " is too small for " + steps + " steps");
        }

        long[] table = new long[steps + 1];
        for (int i = 1; i <= steps; i++) {
            long s_i = Math.multiplyExact((long) i, stepWidth);
            table[i] = logTableEntry(supplyTarget, coefficient, s_i);
        }

        // Evaluation-time overflow guard: interpolate() computes (vHi - vLo) * (point - sLo),
        // and the second factor never exceeds the widest segment while the first is an adjacent
        // table drop, so probing every drop against the widest segment bounds every product
        // evaluation can ever form (conservatively: a drop is charged the widest segment even
        // where its own is narrower). A legal-but-pathological (S*, c, N) triple whose product
        // would not fit a long fails HERE, at construction, rather than escaping interpolate's
        // multiplyExact mid-validation inside Executor/BlockAssembler.
        long widestSegment = Math.max(stepWidth, supplyTarget - steps * stepWidth);
        for (int i = 1; i <= steps; i++) {
            long vHi = (i == steps) ? 0L : table[i + 1];
            Math.multiplyExact(vHi - table[i], widestSegment);
        }
        return new EmissionCurve(supplyTarget, coefficient, steps, stepWidth, table);
    }

    /**
     * {@code floor(coefficient * ln(numerator / denominator))} via the exact fixed-point algorithm
     * the generated table uses ({@code numerator > denominator > 0}, so the ratio is {@code > 1}
     * and {@link #logTableEntry}'s reduction never needs a negative doubling count). Package-private:
     * {@link SupplyTargetSchedule}'s per-epoch reduction bound (contracts/
     * supply-target-schedule.md section 2, {@code D = ceil(c * ln(den/num))}) is derived from this
     * so the bound and the table share ONE logarithm implementation -- two fixed-point ports would
     * be two things to keep bit-for-bit identical.
     */
    static long logRatioFloor(long coefficient, long numerator, long denominator) {
        return logTableEntry(numerator, coefficient, denominator);
    }

    /**
     * {@code floor(c * ln(S* / S_i))} via the successive-squaring fixed-point log (contracts/
     * emission-curve.md section 2). {@code S_i <= S*} always holds for a generated table
     * position, so the ratio is always {@code >= 1} and the reduction below never needs a
     * negative doubling count.
     */
    private static long logTableEntry(long supplyTarget, long coefficient, long s_i) {
        // floor(ratio * 2^MANTISSA_BITS); both operands positive, so BigInteger's
        // truncate-toward-zero division is exactly the floor the contract requires.
        BigInteger scaledRatio = BigInteger.valueOf(supplyTarget)
            .shiftLeft(MANTISSA_BITS)
            .divide(BigInteger.valueOf(s_i));

        // Counting doublings: scaledRatio sits in [2^(MANTISSA_BITS+wholeLog2),
        // 2^(MANTISSA_BITS+wholeLog2+1)), so its bit length pins wholeLog2 = floor(log2(ratio))
        // directly, without an explicit halving loop.
        int wholeLog2 = scaledRatio.bitLength() - 1 - MANTISSA_BITS;
        BigInteger mantissa = scaledRatio.shiftRight(wholeLog2);

        BigInteger frac = BigInteger.ZERO;
        for (int bit = 0; bit < LOG2_FRAC_BITS; bit++) {
            // Square, then floor back to MANTISSA_BITS scale -- the "truncation is floor at
            // every stated step" the contract calls for.
            mantissa = mantissa.multiply(mantissa).shiftRight(MANTISSA_BITS);
            frac = frac.shiftLeft(1);
            if (mantissa.compareTo(MANTISSA_OVERFLOW) >= 0) {
                frac = frac.add(BigInteger.ONE);
                mantissa = mantissa.shiftRight(1); // halve back into [1, 2)
            }
        }

        BigInteger log2Q = BigInteger.valueOf(wholeLog2).shiftLeft(LOG2_FRAC_BITS).add(frac);
        BigInteger scaledLn = log2Q.multiply(LN2_Q62_BIG);
        // Q(LOG2_FRAC_BITS) * Q(LN2_FRAC_BITS) -> Q(LOG2_FRAC_BITS + LN2_FRAC_BITS); shiftRight
        // on a non-negative BigInteger is an exact floor division back to an integer.
        BigInteger value = BigInteger.valueOf(coefficient)
            .multiply(scaledLn)
            .shiftRight(LOG2_FRAC_BITS + LN2_FRAC_BITS);

        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException(
                "generated emission table entry for s_i=" + s_i + " does not fit in a long: " + value);
        }
    }

    /**
     * The generated table entry at 1-indexed {@code step} in {@code [1, steps]} -- the exact
     * value {@link #raw} returns at supply {@code step * floor(supplyTarget/steps)}.
     *
     * @throws IllegalArgumentException if {@code step} is outside {@code [1, steps]}
     */
    public long tableValue(int step) {
        if (step < 1 || step > steps) {
            throw new IllegalArgumentException("step must be in [1, " + steps + "], was " + step);
        }
        return table[step];
    }

    /**
     * The raw (unclamped) curve value at {@code supply}, measured against the live supply target
     * {@code liveTarget} — total over the entire {@code long} domain.
     *
     * <p><b>Widened in place</b> (008-decaying-supply-target, project rule WI-12 — there is no
     * target-less overload): the table is generated once for the peak {@code supplyTarget} and is
     * never regenerated; a live target below the peak is applied by scaling the evaluation
     * argument to {@code floor(supply * supplyTarget / liveTarget)} (see below). When
     * {@code liveTarget == supplyTarget} — every shipped profile before its decay-start height,
     * and every profile that never schedules a decay — the evaluation short-circuits to the
     * original peak path unchanged, which makes "no behaviour change below the decay" structural
     * rather than merely tested (FR-004/G1).
     *
     * <p>Shape of the result, all as before the widening: {@code table[1]} below the first step,
     * table-interpolated between the first step and the target, and strictly negative
     * (ratio-mirrored on the same table) at and above the target — except exactly at the target,
     * which the mirror also resolves to exactly {@code 0}, agreeing with the positive side by
     * construction rather than by special case.
     *
     * <p><b>Domain</b>: unlike the peak-only form, this one is NOT total over {@code long}. The
     * scaled argument is narrowed <i>checked</i>, so the method is defined exactly for
     * {@code supply <= Long.MAX_VALUE * liveTarget / supplyTarget} and throws above it. Taken over
     * every target a schedule reaches, that bound is
     * {@code SupplyTargetSchedule.maxEvaluableSupply()} — published there, and proven at
     * construction to be at least the peak target. Every consensus caller
     * ({@code HeaderChain.checkSupply}, {@code ChainEngine.checkSupply} and
     * {@code checkEmissionScheduleConsistency}) already treats the throw as a rejected block; an
     * observability caller reaches it only with a supply no validated chain could commit.
     *
     * @throws IllegalArgumentException if {@code liveTarget <= 0} — a target is a strictly
     *             positive monetary quantity ({@code SupplyTargetSchedule.targetAt} can never
     *             produce one); passing a sentinel here is a caller bug, not a degenerate value
     * @throws ArithmeticException if {@code supply} exceeds the domain bound above — deliberate
     *             (FR-023): the narrowing must never wrap or clamp into a plausible-looking reward
     */
    public long raw(long supply, long liveTarget) {
        if (liveTarget <= 0) {
            throw new IllegalArgumentException("liveTarget must be > 0, was " + liveTarget);
        }
        if (liveTarget == supplyTarget) {
            return rawAtPeak(supply);
        }

        // Scaled argument floor(S * S* / T): the identity c*ln(T/S) == c*ln(S* / (S*S*/T))
        // means the peak table evaluated at the scaled argument measures distance from the live
        // target. The product genuinely overflows a long at mainnet scale (~3e25), so the
        // arbitrary-precision intermediate is mandatory, not defensive -- and the narrowing is
        // CHECKED: it throws rather than wrapping or clamping (FR-023), byte-for-byte the
        // discipline mirror() below already uses.
        long scaled = BigInteger.valueOf(supply)
            .multiply(BigInteger.valueOf(supplyTarget))
            .divide(BigInteger.valueOf(liveTarget))
            .longValueExact();
        return rawAtPeak(scaled);
    }

    /** The peak-target evaluation -- the entire pre-widening {@code raw}, unchanged. */
    private long rawAtPeak(long supply) {
        if (supply < supplyTarget) {
            return curveValue(supply);
        }

        long mirrored = mirror(supply);
        long value = curveValue(mirrored);
        if (supply == supplyTarget) {
            // ratio == 1 exactly: ln(1) == 0 is an exact integer, no truncation occurred.
            return Math.negateExact(value);
        }

        // For every supply strictly above the target, the mirrored point is strictly below it
        // (never exactly supplyTarget again), so `value` is the FLOOR of a magnitude the table
        // already truncated away sub-unit information for, at generation and/or interpolation
        // time. Negating that floor as-is (`-value`) would recreate the SAME "floors to zero"
        // stretch the positive side truncates to zero across (see the class javadoc) --
        // collapsing raw() to exactly 0 for a wide band immediately above supplyTarget, which
        // is not total-and-strictly-negative. What raw() actually wants is floor(-trueValue):
        // the CEILING of the truncated magnitude, i.e. `value` unchanged only if the true
        // magnitude happened to be an exact integer (the ratio == 1 case above, already
        // excluded here) and `value + 1` in every other, generic case -- irrational logarithms
        // essentially never land on an exact integer, so this applies uniformly.
        return Math.negateExact(Math.addExact(value, 1));
    }

    /** {@code table[1]} below the first step's position, table-interpolated from there on. */
    private long curveValue(long point) {
        if (point < stepWidth) {
            return table[1];
        }
        return interpolate(point);
    }

    /**
     * Linear interpolation between adjacent step positions, using the implicit
     * {@code (supplyTarget, 0)} point past the last generated step (contracts/
     * emission-curve.md section 3). {@code point} must be in {@code [stepWidth, supplyTarget]}.
     */
    private long interpolate(long point) {
        long idx = 1 + Math.floorDiv(point - stepWidth, stepWidth);
        if (idx > steps) {
            idx = steps;
        }
        long sLo = Math.multiplyExact(idx, stepWidth);
        long sHi = (idx == steps) ? supplyTarget : Math.multiplyExact(idx + 1, stepWidth);
        long vLo = table[(int) idx];
        long vHi = (idx == steps) ? 0L : table[(int) idx + 1];
        if (sHi == sLo) {
            // supplyTarget divides evenly by steps: the last segment has zero width and vLo is
            // already the exact (ratio == 1) value -- nothing to interpolate.
            return vLo;
        }

        long numerator = Math.multiplyExact(vHi - vLo, point - sLo);
        // The difference (vHi - vLo) is <= 0 on this non-increasing table, so numerator can be
        // negative; Java's `/` truncates toward zero, which would round the wrong way here --
        // floor toward negative infinity is what the contract specifies.
        return Math.addExact(vLo, Math.floorDiv(numerator, sHi - sLo));
    }

    /**
     * {@code floor(supplyTarget * supplyTarget / supply)}, the ratio-mirror argument. The
     * product can overflow a {@code long} ({@code supplyTarget} squared), so it is computed with
     * an arbitrary-precision intermediate rather than risking a silent wrap; the result is
     * always in {@code [0, supplyTarget]} for {@code supply >= supplyTarget > 0}, so it fits a
     * {@code long} by construction.
     */
    private long mirror(long supply) {
        return BigInteger.valueOf(supplyTarget)
            .multiply(BigInteger.valueOf(supplyTarget))
            .divide(BigInteger.valueOf(supply))
            .longValueExact();
    }
}
