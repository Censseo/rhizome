package rhizome.core.blockchain;

/**
 * A {@link NetworkParameters} profile with the supply-driven emission curve active from height 1
 * (genesis itself carries no coinbase, so there is nothing to activate <em>at</em> height 0) —
 * the one place every story's chain-building test gets a curve-active profile from, so each
 * doesn't hand-roll its own small {@code (S*, c, N)} triple (FR-010).
 *
 * <p>Derives from {@link NetworkParameters#testnet()} (unpinned genesis supply, so an
 * empty-snapshot or funded-snapshot chain both boot cleanly under it) with small curve constants
 * chosen only for a fast, well-populated table — not calibrated to any real-world timescale, see
 * {@link NetworkParameters#cleanMainnet()} for the shipped calibration.
 */
public final class CurveActiveNetwork {

    private CurveActiveNetwork() {
    }

    /**
     * The test-scale peak target every curve-active fixture is built around. Public because the
     * decay fixture's floor is defined relative to it ({@code S* / 2}), so a profile applying
     * {@link #decaySchedule} must be built on this same peak for that relationship to hold —
     * {@code app-node}'s instant-mining {@code TestNetwork.CURVE_ACTIVE} states it from here for
     * exactly that reason.
     */
    public static final long TEST_SUPPLY_TARGET = 1_000_000L;

    /** Curve active from height 1 on, over the test-scale {@code (S*, c, N)} triple below. */
    public static NetworkParameters curveActiveTestnet() {
        return NetworkParameters.testnet().toBuilder()
            .supplyTarget(TEST_SUPPLY_TARGET)
            .emissionCoefficient(10_000L)
            .emissionTableSteps(16)
            .emissionCurveHeight(1)
            .build();
    }

    /**
     * Applies the shared test-scale decay schedule to {@code builder}: decay from height 10, one
     * epoch every 5 blocks, ratio {@code 9/10}, floor {@code TEST_SUPPLY_TARGET / 2}. The five
     * constants live HERE and nowhere else (008 T001, WI-8) — {@link #decayActiveTestnet()} and
     * {@code app-node}'s {@code E2ETargetDecayTest} both drive this method, so retuning the
     * fixture moves both instead of leaving one silently on the old calibration.
     *
     * <p>Takes a builder rather than returning a profile because the two callers need different
     * <b>bases</b>: the lib-core fixture decays {@link #curveActiveTestnet()} (real PoW), while the
     * E2E decays {@code TestNetwork.CURVE_ACTIVE} (instant mining, same {@code (S*, c, N)}
     * triple). Only the decay constants are shared; the base is the caller's.
     *
     * <p>Every constant is stated explicitly, even where the value would coincide with a default —
     * derived profiles inherit silently (WI-9), and an unscheduled base carries zeros that a
     * partial application would leave incoherent.
     *
     * @param builder a builder whose {@code supplyTarget} is {@link #TEST_SUPPLY_TARGET} (the floor
     *            below is that peak's half; against a different peak it would be degenerate and
     *            {@code SupplyTargetSchedule.build} would refuse it)
     * @return the same builder, for chaining
     */
    public static NetworkParameters.NetworkParametersBuilder decaySchedule(
            NetworkParameters.NetworkParametersBuilder builder) {
        return builder
            .decayStartHeight(10L)
            .decayEpochBlocks(5L)
            .decayNum(9L)
            .decayDen(10L)
            .supplyTargetFloor(TEST_SUPPLY_TARGET / 2);
    }

    /**
     * The decay-active sibling of {@link #curveActiveTestnet()}: the same test-scale curve, plus a
     * supply-target decay scheduled at test-scale heights — decay starts at height 10, one epoch
     * every 5 blocks, the target shrinking by {@code 9/10} per epoch down to a floor of
     * {@code S* / 2} — so the schedule's whole lifecycle (peak plateau, first decayed epoch, floor
     * arrival) fits inside the heights a test actually mines through (008 T001: every later phase
     * drives this one fixture instead of hand-rolling decay params, WI-8).
     *
     * <p>The floor is profile-scaled ({@code S* / 2}, mirroring the mainnet calibration's shape) —
     * NOT the inherited mainnet constant, which would be degenerate against this test-scale
     * {@code supplyTarget} (it exceeds the peak; construction refuses it). The constants themselves
     * live in {@link #decaySchedule}, which this method and the E2E suite both apply.
     */
    public static NetworkParameters decayActiveTestnet() {
        return decaySchedule(curveActiveTestnet().toBuilder()).build();
    }
}
