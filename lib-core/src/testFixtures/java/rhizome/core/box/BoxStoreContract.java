package rhizome.core.box;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;

/**
 * The behaviour every {@link BoxStore} owes its callers, run against each implementation.
 *
 * <p>Includes the double-apply refusal (audit F10): {@link InMemoryBoxStore} used to overwrite
 * the journal silently where RocksDB threw, so most of the test suite — which runs against the
 * in-memory store — could not have caught the bug the durable one is guarded for. Both now
 * refuse alike.
 *
 * <p>An interface with {@code default} {@code @Test} methods rather than an abstract base class:
 * the repo has no abstract class, and a test class implementing this keeps its single
 * inheritance AND room for its own backend-specific tests (persistence across reopen) beside
 * the shared ones.
 */
public interface BoxStoreContract {

    /**
     * A fresh, empty store. Called at most once per test method; the implementor owns the
     * lifecycle (a durable backend opens under its own {@code @TempDir} field and closes in
     * an {@code @AfterEach}).
     */
    BoxStore newBoxStore() throws Exception;

    private static Box box(PublicAddress owner, long nonce, long value, long rentPaidHeight) {
        return new Box(Box.deriveId(owner, nonce), owner, value, 1, rentPaidHeight,
            List.of(BoxRegister.string("m" + nonce)));
    }

    @Test
    default void ownerIndexListsAndPaginates() throws Exception {
        BoxStore store = newBoxStore();
        PublicAddress owner = PublicAddress.random();
        PublicAddress other = PublicAddress.random();
        store.applyBlock(2, List.of(
            BoxStore.BoxMutation.write(box(owner, 0, 1000, 5)),
            BoxStore.BoxMutation.write(box(owner, 1, 1000, 5)),
            BoxStore.BoxMutation.write(box(other, 0, 1000, 5))));

        List<byte[]> all = store.boxIdsByOwner(owner.toBytes(), null, 10);
        assertEquals(2, all.size());
        // Pagination: after the first id yields only the rest.
        List<byte[]> page = store.boxIdsByOwner(owner.toBytes(), all.get(0), 10);
        assertEquals(1, page.size());
        assertEquals(1, store.boxIdsByOwner(other.toBytes(), null, 10).size());
    }

    @Test
    default void expiryIndexReturnsCollectableLowestFirst() throws Exception {
        BoxStore store = newBoxStore();
        PublicAddress owner = PublicAddress.random();
        // rentPaidHeight 5 and 50; storagePeriod 10 -> collectable at height >= rent+10.
        store.applyBlock(2, List.of(
            BoxStore.BoxMutation.write(box(owner, 0, 1000, 5)),
            BoxStore.BoxMutation.write(box(owner, 1, 1000, 50))));

        // At height 20: only the rentPaid=5 box (5 <= 20-10) is collectable.
        List<byte[]> collectable = store.collectableBoxIds(20, 10, 10);
        assertEquals(1, collectable.size());
        assertEquals(Box.deriveId(owner, 0)[0], collectable.get(0)[0]);
        // At height 100: both are collectable, lowest rent-clock first.
        assertEquals(2, store.collectableBoxIds(100, 10, 10).size());
    }

    @Test
    default void revertRestoresPriorStateAndReindexes() throws Exception {
        BoxStore store = newBoxStore();
        PublicAddress owner = PublicAddress.random();
        Box original = box(owner, 0, 1000, 5);
        store.applyBlock(2, List.of(BoxStore.BoxMutation.write(original)));

        // Block 3 updates the box (new value + rent clock) and creates a second.
        Box updated = original.updated(2000, List.of(BoxRegister.i64(7)), 3);
        Box second = box(owner, 1, 500, 3);
        store.applyBlock(3, List.of(
            BoxStore.BoxMutation.write(updated),
            BoxStore.BoxMutation.write(second)));
        assertEquals(updated, store.get(original.id()));

        store.revertBlock(3);
        assertEquals(original, store.get(original.id())); // prior state restored
        assertNull(store.get(second.id()));               // creation undone
        // Owner index reflects only the surviving box.
        assertEquals(1, store.boxIdsByOwner(owner.toBytes(), null, 10).size());
        // Expiry index reflects the restored rent clock (5), not the reverted update's (3).
        assertEquals(1, store.collectableBoxIds(20, 10, 10).size());
    }

    @Test
    default void deleteRemovesBoxAndIndexes() throws Exception {
        BoxStore store = newBoxStore();
        PublicAddress owner = PublicAddress.random();
        Box a = box(owner, 0, 1000, 5);
        store.applyBlock(2, List.of(BoxStore.BoxMutation.write(a)));
        store.applyBlock(3, List.of(BoxStore.BoxMutation.delete(a.id())));
        assertNull(store.get(a.id()));
        assertTrue(store.boxIdsByOwner(owner.toBytes(), null, 10).isEmpty());
        assertTrue(store.collectableBoxIds(100, 10, 10).isEmpty());
    }

    @Test
    default void pruneJournalsBlocksLaterRevert() throws Exception {
        BoxStore store = newBoxStore();
        PublicAddress owner = PublicAddress.random();
        Box a = box(owner, 0, 1000, 5);
        store.applyBlock(2, List.of(BoxStore.BoxMutation.write(a)));
        store.pruneJournals(3); // drop journals below height 3, including height 2
        // With the journal gone, reverting block 2 is a no-op (state unchanged).
        store.revertBlock(2);
        assertEquals(a, store.get(a.id()));
    }

    @Test
    default void applyBlockRefusesADoubleApply() throws Exception {
        BoxStore store = newBoxStore();
        PublicAddress owner = PublicAddress.random();
        Box a = box(owner, 0, 1000, 5);
        store.applyBlock(2, List.of(BoxStore.BoxMutation.write(a)));
        // A second apply at the same height would journal the already-mutated state as the
        // "prior", corrupting any later revert — it must be refused (audit F10).
        List<BoxStore.BoxMutation> repeat = List.of(BoxStore.BoxMutation.write(a));
        assertThrows(IllegalStateException.class, () -> store.applyBlock(2, repeat));
        assertEquals(a, store.get(a.id())); // the first commit is untouched
    }

    @Test
    default void aMutationLessApplyRecordsNoJournalAndStaysReAppliable() throws Exception {
        // A block that touches no box has nothing to undo, so it must not persist an (empty)
        // journal — otherwise a legitimately mutation-less block would falsely trip the
        // double-apply guard the moment it, or a later empty apply at the same height, ran again.
        BoxStore store = newBoxStore();
        store.applyBlock(5, List.of());
        store.applyBlock(5, List.of()); // must not throw
        PublicAddress owner = PublicAddress.random();
        Box a = box(owner, 0, 1000, 5);
        // A later real apply at that same height is still accepted — no phantom journal blocks it.
        store.applyBlock(5, List.of(BoxStore.BoxMutation.write(a)));
        assertEquals(a, store.get(a.id()));
    }
}
