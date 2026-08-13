package rhizome.core.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Locks the amortized prune cadence: the interval deleteRange is paid exactly once every
 * {@link PruneCadence#INTERVAL} heights of cutoff growth, never for a non-positive cutoff —
 * the lag-only backstop shape that keeps the reorg window covered (audit perf).
 */
class PruneCadenceTest {

    @Test
    void firesOncePerIntervalAsTheCutoffGrows() {
        PruneCadence cadence = new PruneCadence();
        assertFalse(cadence.due(0), "nothing is prunable at cutoff 0");
        assertFalse(cadence.due(-5), "a negative cutoff never prunes");
        for (long i = 1; i < PruneCadence.INTERVAL; i++) {
            assertFalse(cadence.due(i), "the interval has not elapsed");
        }
        assertTrue(cadence.due(PruneCadence.INTERVAL), "the prune fires exactly at the interval");
        for (long i = PruneCadence.INTERVAL + 1; i < 2 * PruneCadence.INTERVAL; i++) {
            assertFalse(cadence.due(i), "one prune per interval, no matter the growth");
        }
        assertTrue(cadence.due(2 * PruneCadence.INTERVAL), "the next interval prunes again");
    }

    @Test
    void anEarlyCutoffDoesNotConsumeTheInterval() {
        PruneCadence cadence = new PruneCadence();
        assertFalse(cadence.due(1));
        assertFalse(cadence.due(PruneCadence.INTERVAL - 1),
            "the watermark only advances when the prune actually fires");
        assertTrue(cadence.due(PruneCadence.INTERVAL));
    }
}
