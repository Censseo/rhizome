package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The GHOST uncle reward is scaled to the uncle's proven work relative to the nephew's difficulty
 * (audit C1 residual): a flat reward let cheap minDifficulty orphans mint ~half a block each.
 */
class ExecutorRewardScalingTest {

    @Test
    void sameDifficultyEarnsFullReward() {
        assertEquals(1000L, Executor.scaleRewardToWork(1000L, 0));
    }

    @Test
    void eachMissingDifficultyBitHalvesTheReward() {
        assertEquals(500L, Executor.scaleRewardToWork(1000L, 1));
        assertEquals(250L, Executor.scaleRewardToWork(1000L, 2));
        assertEquals(1000L >>> 10, Executor.scaleRewardToWork(1000L, 10));
    }

    @Test
    void largeDeficitEarnsNothing() {
        // A minDifficulty (16) orphan against a difficulty-40 nephew: deficit 24 -> ~0.
        assertEquals(0L, Executor.scaleRewardToWork(1000L, 24));
        assertEquals(0L, Executor.scaleRewardToWork(Long.MAX_VALUE, 63));
        assertEquals(0L, Executor.scaleRewardToWork(Long.MAX_VALUE, 64));
        assertEquals(0L, Executor.scaleRewardToWork(Long.MAX_VALUE, 1000));
    }

    @Test
    void negativeDeficitIsClampedToFull() {
        assertEquals(1000L, Executor.scaleRewardToWork(1000L, -5));
    }
}
