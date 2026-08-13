package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ContractStateSource.ContractChange;
import rhizome.core.blockchain.ContractApi.ContractLog;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.state.HeightRetainedIndex;

/**
 * Locks the shared retention index's byte accounting from the VM's side of the module
 * boundary: {@link WasmContractProcessor} migrated its four hand-rolled retention maps onto
 * {@link HeightRetainedIndex}, so the counter must track exactly the retained payload and
 * never evict the height being retained — a drifting counter would silently evict
 * in-window journals/receipts/changes, and a self-eviction would make the state-root
 * collection read an empty contract domain (consensus divergence).
 */
class HeightRetainedIndexVmTest {

    /** Mirror of the processor's log sizer: address + topic + data per entry. */
    private static long logBytes(List<ContractLog> logs) {
        long bytes = 0;
        for (ContractLog log : logs) {
            bytes += PublicAddress.SIZE + log.topic().length + log.data().length;
        }
        return bytes;
    }

    /** Mirror of the processor's change sizer: tag + address + key + value. */
    private static long changeBytes(List<ContractChange> changes) {
        long bytes = 0;
        for (ContractChange c : changes) {
            bytes += 1 + PublicAddress.SIZE
                + (c.key() == null ? 0 : c.key().length)
                + (c.value() == null ? 0 : c.value().length);
        }
        return bytes;
    }

    @Test
    void accountsTheRetainedPayloadExactly() {
        // retain / forget / pruneThrough must keep retainedBytes exactly equal to the surviving
        // entries' sizes — the invariant the processors' eviction and revert paths depend on.
        HeightRetainedIndex<ContractLog> index = new HeightRetainedIndex<>(3, 1_000_000,
            HeightRetainedIndexVmTest::logBytes);
        byte[] payload = new byte[100];
        List<ContractLog> at1 = List.of(new ContractLog(PublicAddress.random(), new byte[] {1}, payload));
        List<ContractLog> at2 = List.of(new ContractLog(PublicAddress.random(), new byte[] {1}, payload));
        index.retain(1, at1);
        index.retain(2, at2);
        assertEquals(logBytes(at1) + logBytes(at2), index.retainedBytes());
        assertEquals(2, index.retainedHeights());

        index.forget(1);
        assertEquals(logBytes(at2), index.retainedBytes(), "a reverted height credits its bytes back");
        assertEquals(1, index.retainedHeights());

        index.retain(2, List.of()); // replacing a height nets out the previous entry
        assertEquals(0, index.retainedBytes(), "replacement double-counts nothing");
        assertTrue(index.has(2), "an empty value is retained, distinct from absent");

        index.pruneThrough(5, null); // cutoff = 5 - 3 = 2: heights 1..2 leave the window
        assertEquals(0, index.retainedHeights(), "the prune drops everything the window no longer covers");
        assertEquals(0, index.retainedBytes());
    }

    @Test
    void evictsTheOldestHeightsFirstPastTheBudget() {
        HeightRetainedIndex<ContractLog> index = new HeightRetainedIndex<>(10, 5_000,
            HeightRetainedIndexVmTest::logBytes);
        byte[] payload = new byte[1_000];
        for (long h = 1; h <= 10; h++) {
            index.retain(h, List.of(new ContractLog(PublicAddress.random(), new byte[] {1}, payload)));
        }
        assertFalse(index.has(1), "the oldest height is evicted first");
        assertTrue(index.has(10), "the newest height is kept");
        assertEquals(5_000 / (PublicAddress.SIZE + 1 + payload.length), index.retainedHeights());
    }

    @Test
    void neverEvictsTheHeightBeingRetained() {
        // One block alone over the budget: the index must keep it (the state-root collection
        // reads it back immediately) and account the over-budget bytes, not evict the height.
        HeightRetainedIndex<ContractChange> index = new HeightRetainedIndex<>(3, 1_024,
            HeightRetainedIndexVmTest::changeBytes);
        List<ContractChange> huge = List.of(new ContractChange(
            false, PublicAddress.random(), new byte[] {1}, new byte[2_048]));
        index.retain(1, huge);
        assertEquals(1, index.get(1).size(), "the retained height is never its own victim");
        assertEquals(changeBytes(huge), index.retainedBytes(), "the over-budget payload stays accounted");
    }
}
