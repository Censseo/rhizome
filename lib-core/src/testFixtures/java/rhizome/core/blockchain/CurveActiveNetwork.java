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

    /** Curve active from height 1 on, over the test-scale {@code (S*, c, N)} triple below. */
    public static NetworkParameters curveActiveTestnet() {
        return NetworkParameters.testnet().toBuilder()
            .supplyTarget(1_000_000L)
            .emissionCoefficient(10_000L)
            .emissionTableSteps(16)
            .emissionCurveHeight(1)
            .build();
    }
}
