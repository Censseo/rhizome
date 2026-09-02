package rhizome.core.blockchain;

import java.util.function.LongConsumer;

/**
 * Test access to {@link SupplyTargetSchedule}'s package-private instrumentation seam — the G3
 * iteration-bound proof counts decay multiplications through here instead of measuring wall-clock
 * time (FR-010, SC-014). Same-package bridge, the {@link ChainEngineTestAccess} pattern: main
 * stays free of public test surface, tests stay free of reflection.
 */
public final class SupplyTargetScheduleTestAccess {

    private SupplyTargetScheduleTestAccess() {
    }

    /** Runs {@code targetAt}, reporting each decay multiplication to {@code stepCounter}. */
    public static long targetAt(SupplyTargetSchedule schedule, long height,
            LongConsumer stepCounter) {
        return schedule.targetAt(height, stepCounter);
    }

    /**
     * The build-time memo's epoch cap — the boundary between the tabulated constant-time path and
     * the iterating fallback. Exposed so the cost proofs can state which side of it a calibration
     * sits on without hard-coding the constant a second time.
     */
    public static int maxMemoisedEpochs() {
        return SupplyTargetSchedule.maxMemoisedEpochs();
    }
}
