package rhizome;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.EmissionCurve;

/**
 * Locks the domain contract of {@link EmissionCurve} (contracts/emission-curve.md §§2-3,
 * research.md Decisions 1-2): the stepped table generated at construction time from
 * {@code (supplyTarget, coefficient, steps)}, the O(1) evaluation with exact linear
 * interpolation between step boundaries (floor division exact toward negative infinity), the
 * monotone below-floor extension, and the ratio-mirrored negative branch above the supply
 * target. Any generator or evaluator that violates monotonicity, degenerates at a step boundary,
 * throws instead of returning a total function, or diverges between two builds of the same
 * constants would silently corrupt every downstream reward computation (four call sites, per
 * research.md).
 *
 * <p><b>Test-scale constants</b>: deliberately small and fast to sweep — NOT the mainnet
 * calibration (that lives in {@code NetworkParametersTest}/{@code cleanMainnet()}). {@code
 * SUPPLY_TARGET} is chosen so it is <em>not</em> evenly divisible by {@code STEPS}
 * ({@code 1_000_003 / 64} has a remainder of {@code 3}), mirroring the real mainnet
 * calibration (2 997 924 580 000 / 256 is likewise inexact) so the last table position
 * {@code S_STEPS} sits strictly below {@code SUPPLY_TARGET} — the same shape the shipped
 * constants have, and the shape that keeps the "exact table value at every step boundary"
 * assertion (test 4) clear of the "negative branch at/above the supply target" rule (test 3):
 * the two would otherwise disagree about how to evaluate the single point {@code S = S_STEPS =
 * SUPPLY_TARGET}.
 */
class EmissionCurveTest {

    // (0, S*+overshoot] sweep is over a coordinate space of ~2M values at these constants —
    // fast for a plain long-arithmetic loop with no I/O.
    private static final long SUPPLY_TARGET = 1_000_003L;
    private static final long COEFFICIENT = 1_000L;
    private static final int STEPS = 64;

    /** {@code S_i = i * floor(S* / N)} — contracts/emission-curve.md §2, reproduced here so the
     * test does not depend on {@link EmissionCurve} exposing its internal step positions. */
    private static long stepPosition(int i) {
        return i * (SUPPLY_TARGET / STEPS);
    }

    @Test
    void theCurveIsMonotoneNonIncreasingAcrossTheWholeDomain() {
        EmissionCurve curve = EmissionCurve.build(SUPPLY_TARGET, COEFFICIENT, STEPS);
        long overshoot = SUPPLY_TARGET; // sweep on into the negative branch too
        long stride = 37; // prime stride: thorough without walking every one of ~2M values

        long previousSupply = 1;
        long previousRaw = curve.raw(previousSupply, SUPPLY_TARGET);
        for (long supply = 1 + stride; supply <= SUPPLY_TARGET + overshoot; supply += stride) {
            long raw = curve.raw(supply, SUPPLY_TARGET);
            assertTrue(previousRaw >= raw,
                "raw() must be monotone non-increasing: raw(" + previousSupply + ")=" + previousRaw
                    + " < raw(" + supply + ")=" + raw);
            previousSupply = supply;
            previousRaw = raw;
        }
    }

    @Test
    void theCurveTruncatesToZeroAtAPinnedTerminalSupply() {
        EmissionCurve curve = EmissionCurve.build(SUPPLY_TARGET, COEFFICIENT, STEPS);

        // Scan upward from the domain floor for the first supply at which the reward has
        // truncated to exactly zero. Below the supply target the curve is strictly within the
        // table/interpolation region (never the negative branch), so this crossing is
        // unambiguous.
        long terminalSupply = -1;
        for (long supply = stepPosition(1); supply < SUPPLY_TARGET; supply++) {
            if (curve.raw(supply, SUPPLY_TARGET) == 0) {
                terminalSupply = supply;
                break;
            }
        }

        assertTrue(terminalSupply >= 0, "expected a supply at which raw() truncates to zero "
            + "somewhere below the supply target");
        // The pin is exact: the base unit immediately below still pays something, and the
        // terminal point itself is exactly zero — not "close to zero", not negative.
        assertTrue(curve.raw(terminalSupply - 1, SUPPLY_TARGET) > 0,
            "the base unit immediately below the terminal supply must still pay a positive reward");
        assertEquals(0, curve.raw(terminalSupply, SUPPLY_TARGET));
        // Deterministic: re-evaluating the same point again yields the identical pin.
        assertEquals(0, curve.raw(terminalSupply, SUPPLY_TARGET));
    }

    @Test
    void theNegativeBranchIsNegativeMonotoneAndTotal() {
        EmissionCurve curve = EmissionCurve.build(SUPPLY_TARGET, COEFFICIENT, STEPS);

        // Strictly above the supply target only — S == S* itself sits in the ambiguous single
        // point this test file deliberately avoids (see class Javadoc).
        long[] supplies = {
            SUPPLY_TARGET + 1,
            SUPPLY_TARGET + 1_000,
            SUPPLY_TARGET * 1_000,
            SUPPLY_TARGET * 1_000_000,
            Long.MAX_VALUE / 2,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE,
        };

        long previous = Long.MAX_VALUE; // no supply can produce a raw() above this as a starting bound
        for (long supply : supplies) {
            long raw = assertDoesNotThrow(() -> curve.raw(supply, SUPPLY_TARGET),
                "raw() must be total across the whole long domain, including near Long.MAX_VALUE");
            assertTrue(raw < 0, "raw(" + supply + ") must be strictly negative above the supply "
                + "target, was " + raw);
            assertTrue(raw <= previous, "the negative branch must stay monotone: raw(" + supply
                + ")=" + raw + " should be <= the previous (smaller-supply) value " + previous);
            previous = raw;
        }
    }

    @Test
    void stepBoundariesEvaluateToTheTableValuesExactly() {
        EmissionCurve curve = EmissionCurve.build(SUPPLY_TARGET, COEFFICIENT, STEPS);

        for (int i = 1; i <= STEPS; i++) {
            long supplyAtStep = stepPosition(i);
            assertEquals(curve.tableValue(i), curve.raw(supplyAtStep, SUPPLY_TARGET),
                "raw() at exactly S_" + i + " (" + supplyAtStep + ") must equal the generated "
                    + "table entry, with no interpolation error at the boundary itself");
        }
    }

    @Test
    void belowTheDomainFloorTheFirstStepValueHolds() {
        EmissionCurve curve = EmissionCurve.build(SUPPLY_TARGET, COEFFICIENT, STEPS);
        long firstStepValue = curve.tableValue(1);
        long domainFloor = stepPosition(1);

        // Including exactly 0 — an empty-genesis chain must still evaluate, not throw.
        long[] belowFloorSupplies = {0, 1, domainFloor / 2, domainFloor - 1};
        for (long supply : belowFloorSupplies) {
            assertEquals(firstStepValue, curve.raw(supply, SUPPLY_TARGET),
                "below the domain floor, raw(" + supply + ") must hold the first step's value "
                    + "(the monotone below-floor extension)");
        }
    }

    @Test
    void generationIsDeterministicAcrossConstructions() {
        EmissionCurve first = EmissionCurve.build(SUPPLY_TARGET, COEFFICIENT, STEPS);
        EmissionCurve second = EmissionCurve.build(SUPPLY_TARGET, COEFFICIENT, STEPS);

        for (int i = 1; i <= STEPS; i++) {
            assertEquals(first.tableValue(i), second.tableValue(i),
                "table entry " + i + " must be bit-identical across independent constructions "
                    + "from the same (S*, c, N)");
        }

        // A sample of raw() evaluations spanning below-floor, interpolated, and negative-branch
        // regions must also agree exactly.
        long[] sampleSupplies = {
            0,
            stepPosition(1) - 1,
            stepPosition(1),
            stepPosition(STEPS / 2),
            stepPosition(STEPS / 2) + stepPosition(1) / 2,
            stepPosition(STEPS),
            SUPPLY_TARGET,
            SUPPLY_TARGET + 1,
            SUPPLY_TARGET * 1_000,
            Long.MAX_VALUE,
        };
        for (long supply : sampleSupplies) {
            assertEquals(first.raw(supply, SUPPLY_TARGET), second.raw(supply, SUPPLY_TARGET),
                "raw(" + supply + ") must be bit-identical across independent constructions");
        }
    }

    // Real shipped mainnet calibration (NetworkParameters.cleanMainnet()) -- deliberately NOT
    // this file's test-scale constants above. research.md Decision 6's "Reorg continuity
    // (FR-014)" figure (~0.16 base units) is an empirical property of THIS calibration, not a
    // general property of any (S*, c, N) triple, so it must be checked against the real numbers.
    private static final long MAINNET_SUPPLY_TARGET = 2_997_924_580_000L;
    private static final long MAINNET_COEFFICIENT = 23_750L;
    private static final int MAINNET_STEPS = 256;
    private static final long MAINNET_GENESIS_SUPPLY = 1_000_000_000_000L; // S0

    /**
     * FR-014/SC-005: a reorg up to {@code maxReorgDepth} (120 blocks) deep lets two competing
     * chain tips commit different circulating supplies at the same settling height. Locks that
     * the curve reward itself cannot swing by more than one base unit between any two supplies
     * that far apart at the shipped calibration -- a deep reorg never produces a large,
     * consensus-destabilizing reward discontinuity.
     */
    @Test
    void theRewardDiscontinuityAcrossAMaxDepthReorgStaysBelowOneBaseUnit() {
        EmissionCurve mainnetCurve =
            EmissionCurve.build(MAINNET_SUPPLY_TARGET, MAINNET_COEFFICIENT, MAINNET_STEPS);

        // R0: the launch/peak reward at S0. |df/dS| = c/S is maximal at the LOWEST reachable
        // supply and decreases monotonically as supply rises, so S0 is where a fixed-size supply
        // divergence should move the reward the most across [S0, S*] -- measured live against the
        // real curve, not hardcoded from research.md's rounded figure, so a calibration change
        // (S*, c or N) is caught here too, not just approximated.
        long r0 = mainnetCurve.raw(MAINNET_GENESIS_SUPPLY, MAINNET_SUPPLY_TARGET);

        // research.md Decision 6: worst-case 120-block supply divergence between two competing
        // tips, conservatively including full-difficulty uncle/nephew issuance on top of the base
        // reward (~2.1x) at every one of the 120 blocks -- ~6.6e6 base units at this calibration.
        long deltaS = Math.round(120.0 * 2.1 * r0);
        assertTrue(deltaS > 0 && deltaS < MAINNET_GENESIS_SUPPLY,
            "sanity: deltaS must be positive and well below S0, or the sweep below isn't testing "
                + "what it claims to");

        // Anchors sweep [S0, S_(steps-1)]: one whole table step short of S* (a margin of exactly
        // stepWidth, ~1.17e10 base units). The final segment [S_(steps-1), S*) interpolates down
        // into the truncation-to-zero band -- a qualitatively different regime (reward already
        // at/near zero) that the calibration record's continuity claim is not about (see class
        // Javadoc / research.md Decision 6's terminal-supply paragraph). Even at the top of this
        // sweep, anchor + deltaS lands only a sliver into that final segment, nowhere near the
        // truncation crossing, so the sweep stays entirely in the "normal operating" regime.
        long stepWidth = MAINNET_SUPPLY_TARGET / MAINNET_STEPS;
        long upperBound = (MAINNET_STEPS - 1L) * stepWidth;

        // Large stride: thorough across the ~2e12-wide reachable domain without an intractable
        // per-unit walk (mirrors theCurveIsMonotoneNonIncreasingAcrossTheWholeDomain's approach,
        // scaled to mainnet's domain width). ~50,000 anchors, each checked both directions.
        long stride = Math.max(1, (upperBound - MAINNET_GENESIS_SUPPLY) / 50_000);

        // NOTE on the bound actually asserted: research.md's continuous-derivative estimate at S0
        // (c/S0 * deltaS ~= 0.16 base units) predicts a difference of exactly 0 once floored, and
        // that DOES hold at S0 itself. But swept across the domain, the discrete table's own
        // published interpolation-error budget (~0.41 base units, Decision 6) means two
        // evaluations deltaS apart can land in different interpolation segments and round to
        // exactly 1 base unit apart -- confirmed here, not hidden: a strict "< 1" (i.e. always
        // exactly 0) does NOT hold across this sweep, but "<= 1" (never 2 or more -- never a
        // *large* jump) does, which is what FR-014 actually guards against.
        for (long anchor = MAINNET_GENESIS_SUPPLY; anchor <= upperBound; anchor += stride) {
            long base = mainnetCurve.raw(anchor, MAINNET_SUPPLY_TARGET);

            long up = mainnetCurve.raw(anchor + deltaS, MAINNET_SUPPLY_TARGET);
            assertTrue(Math.abs(base - up) <= 1,
                "reward discontinuity at anchor=" + anchor + ", anchor+deltaS=" + (anchor + deltaS)
                    + ": raw()=" + base + " vs " + up + " differ by more than 1 base unit");

            // anchor >= S0 > deltaS, so anchor - deltaS is always positive here -- no guard needed.
            long down = mainnetCurve.raw(anchor - deltaS, MAINNET_SUPPLY_TARGET);
            assertTrue(Math.abs(base - down) <= 1,
                "reward discontinuity at anchor=" + anchor + ", anchor-deltaS=" + (anchor - deltaS)
                    + ": raw()=" + base + " vs " + down + " differ by more than 1 base unit");
        }

        // At S0 exactly -- the theoretical worst point per the derivative argument above -- the
        // divergence is 0, matching research.md's ~0.16-base-unit (rounds to 0) estimate exactly.
        long baseAtS0 = mainnetCurve.raw(MAINNET_GENESIS_SUPPLY, MAINNET_SUPPLY_TARGET);
        assertEquals(baseAtS0, mainnetCurve.raw(MAINNET_GENESIS_SUPPLY + deltaS, MAINNET_SUPPLY_TARGET),
            "at S0 itself the 120-block-reorg divergence must round to exactly 0 base units");
        assertEquals(baseAtS0, mainnetCurve.raw(MAINNET_GENESIS_SUPPLY - deltaS, MAINNET_SUPPLY_TARGET),
            "at S0 itself the 120-block-reorg divergence must round to exactly 0 base units");
    }

    /**
     * A legal-but-pathological {@code (S*, c, N)} triple — table entries that individually fit a
     * {@code long}, but whose adjacent drop times the widest segment would overflow {@code
     * interpolate}'s {@code multiplyExact} at EVALUATION time — must fail at construction
     * instead: {@code raw()} is evaluated inside {@code Executor}/{@code BlockAssembler}, where
     * an escaping {@code ArithmeticException} would crash validation of an attacker's block
     * rather than reject it. At these constants table[1] = floor(4e18 * ln 2) ~= 2.77e18 still
     * fits, while |drop| * stepWidth ~= 2.77e18 * 5e17 overflows — so the throw below comes from
     * the construction-time guard, not the entry-fit check (whose message differs).
     */
    @Test
    void aPathologicallyWideConfigurationFailsAtConstructionRatherThanAtEvaluation() {
        ArithmeticException overflow = assertThrows(ArithmeticException.class,
            () -> EmissionCurve.build(1_000_000_000_000_000_000L, 4_000_000_000_000_000_000L, 2));
        assertTrue(!overflow.getMessage().contains("generated emission table entry"),
            "the failure must come from the interpolation-overflow guard, not the entry-fit check: "
                + overflow.getMessage());
    }

    /**
     * 008-decaying-supply-target, T016 (research.md Decision 1 — the measurement that justified
     * the design): evaluating the PEAK-generated table at the scaled argument
     * {@code floor(S x peak / T)} must agree with a table REGENERATED for {@code T} to within
     * <b>±1 base unit</b> at every point that stresses the two tables' truncation disagreement:
     * every step boundary of both tables (a unit either side), the target neighbourhood, the
     * negative branch, and a dense seeded-random sweep up to {@code 1.9 x peak}. The two rules
     * are two equally legitimate integer definitions differing by at most one base unit; this
     * test pins that the shipped one never disagrees with the regenerated reference by more.
     */
    @Test
    void theScaledArgumentEvaluationAgreesWithARegeneratedTableToWithinOneBaseUnit() {
        long peak = SUPPLY_TARGET; // 1_000_003 — deliberately not divisible by STEPS, like mainnet
        // Ten ratios went into the published measurement; these five span its range on this
        // test-scale peak (0.999 down to 0.111), including the exact halves research.md
        // measured as agreeing everywhere.
        long[] targets = {
            peak * 999 / 1000,
            peak * 9 / 10,
            peak / 2,
            peak * 5 / 9,
            peak / 9,
        };

        java.util.TreeSet<Long> points = new java.util.TreeSet<>();
        java.util.Random random = new java.util.Random(0x008L); // seeded: failures reproduce
        for (long target : targets) {
            EmissionCurve live = EmissionCurve.build(peak, COEFFICIENT, STEPS);
            EmissionCurve regenerated = EmissionCurve.build(target, COEFFICIENT, STEPS);

            // Every step boundary of BOTH tables, a unit either side (boundary effects).
            for (int i = 1; i <= STEPS; i++) {
                long peakStep = stepPosition(i);
                long targetStep = i * (target / STEPS);
                points.add(peakStep); points.add(peakStep - 1); points.add(peakStep + 1);
                points.add(peakStep - 2); points.add(peakStep + 2);
                if (targetStep > 0) {
                    points.add(targetStep); points.add(targetStep - 1); points.add(targetStep + 1);
                    points.add(targetStep - 2); points.add(targetStep + 2);
                }
            }
            // The target neighbourhood: where the scaled argument crosses 1.
            for (long d = -5; d <= 5; d++) {
                long near = target + d;
                if (near > 0) {
                    points.add(near);
                }
            }
            // The negative branch (within the measured 1.9x domain) and random supplies.
            points.add(target + 1); points.add(target + 1_000);
            points.add(peak + peak / 2); points.add(peak * 19 / 10);
            for (int k = 0; k < 2_000; k++) {
                points.add(random.nextLong(peak * 19 / 10) + 1);
            }
            // Below-floor extension both ways (0 included — an empty-genesis chain evaluates).
            points.add(0L); points.add(1L);

            long maxAbsDiff = 0;
            long argMax = -1;
            for (long supply : points) {
                long liveValue = live.raw(supply, target);
                long reference = regenerated.raw(supply, target);
                long absDiff = Math.abs(liveValue - reference);
                assertTrue(absDiff <= 1,
                    "raw(" + supply + ", " + target + ") = " + liveValue
                        + " disagrees with the table regenerated at the live target (" + reference
                        + ") by " + absDiff + " base units -- over the published +/-1 bound");
                if (absDiff > maxAbsDiff) {
                    maxAbsDiff = absDiff;
                    argMax = supply;
                }
            }
            assertTrue(maxAbsDiff <= 1,
                "target " + target + ": max disagreement " + maxAbsDiff + " at supply " + argMax);
        }
    }
}
