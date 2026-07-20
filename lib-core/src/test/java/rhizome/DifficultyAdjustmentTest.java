package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.DifficultyAdjustment;
import rhizome.core.blockchain.NetworkParameters;

class DifficultyAdjustmentTest {

    private final NetworkParameters params = NetworkParameters.cleanMainnet();
    private final long window = params.difficultyLookback();
    private final long desired = window * params.desiredBlockTimeSec();

    @Test
    void onTargetKeepsDifficulty() {
        assertEquals(20, DifficultyAdjustment.nextDifficulty(params, 20, window, desired));
    }

    @Test
    void fasterBlocksRaiseDifficultyByWholeBits() {
        assertEquals(21, DifficultyAdjustment.nextDifficulty(params, 20, window, desired / 2));
        assertEquals(22, DifficultyAdjustment.nextDifficulty(params, 20, window, desired / 4));
    }

    @Test
    void slowerBlocksLowerDifficultyByWholeBits() {
        assertEquals(19, DifficultyAdjustment.nextDifficulty(params, 20, window, desired * 2));
        assertEquals(18, DifficultyAdjustment.nextDifficulty(params, 20, window, desired * 4));
    }

    @Test
    void stepIsBoundedAgainstTimestampManipulation() {
        // 1000x too fast still only moves MAX_STEP_BITS bits.
        assertEquals(20 + DifficultyAdjustment.MAX_STEP_BITS,
            DifficultyAdjustment.nextDifficulty(params, 20, window, Math.max(1, desired / 1000)));
        assertEquals(20 - DifficultyAdjustment.MAX_STEP_BITS,
            DifficultyAdjustment.nextDifficulty(params, 20, window, desired * 1000));
    }

    @Test
    void clampedToNetworkBounds() {
        assertEquals(params.minDifficulty(),
            DifficultyAdjustment.nextDifficulty(params, params.minDifficulty(), window, desired * 1000));
        assertEquals(params.maxDifficulty(),
            DifficultyAdjustment.nextDifficulty(params, params.maxDifficulty(), window, 1));
    }

    @Test
    void deterministicPureFunction() {
        for (long observed : new long[]{1, desired / 3, desired, desired * 7}) {
            assertEquals(
                DifficultyAdjustment.nextDifficulty(params, 20, window, observed),
                DifficultyAdjustment.nextDifficulty(params, 20, window, observed));
        }
    }

    @Test
    void retargetHeights() {
        assertFalse(DifficultyAdjustment.isRetargetHeight(params, 0));
        assertFalse(DifficultyAdjustment.isRetargetHeight(params, 1));
        assertTrue(DifficultyAdjustment.isRetargetHeight(params, params.difficultyLookback()));
        assertTrue(DifficultyAdjustment.isRetargetHeight(params, 5L * params.difficultyLookback()));
    }
}
