package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.box.Box;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.BoxStore;
import rhizome.core.ledger.PublicAddress;
import rhizome.persistence.rocksdb.RocksDbBoxStore;

class RocksDbBoxStoreTest {

    private static Box box(PublicAddress owner, long nonce, long value, long rentPaidHeight) {
        return new Box(Box.deriveId(owner, nonce), owner, value, 1, rentPaidHeight,
            List.of(BoxRegister.string("m" + nonce)));
    }

    @Test
    void persistsBoxesAcrossReopen(@TempDir Path dir) throws Exception {
        PublicAddress owner = PublicAddress.random();
        Box a = box(owner, 0, 1000, 5);
        try (var store = new RocksDbBoxStore(dir.toString())) {
            store.applyBlock(2, List.of(BoxStore.BoxMutation.write(a)));
            assertEquals(a, store.get(a.id()));
            assertNull(store.get(Box.deriveId(owner, 99)));
        }
        try (var store = new RocksDbBoxStore(dir.toString())) {
            assertEquals(a, store.get(a.id())); // survived on disk
        }
    }

    @Test
    void ownerIndexListsAndPaginates(@TempDir Path dir) throws Exception {
        PublicAddress owner = PublicAddress.random();
        PublicAddress other = PublicAddress.random();
        try (var store = new RocksDbBoxStore(dir.toString())) {
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
    }

    @Test
    void expiryIndexReturnsCollectableLowestFirst(@TempDir Path dir) throws Exception {
        PublicAddress owner = PublicAddress.random();
        try (var store = new RocksDbBoxStore(dir.toString())) {
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
    }

    @Test
    void revertRestoresPriorStateAndReindexes(@TempDir Path dir) throws Exception {
        PublicAddress owner = PublicAddress.random();
        Box original = box(owner, 0, 1000, 5);
        try (var store = new RocksDbBoxStore(dir.toString())) {
            store.applyBlock(2, List.of(BoxStore.BoxMutation.write(original)));

            // Block 3 updates the box (new value + rent clock) and creates a second.
            Box updated = original.updated(2000, List.of(BoxRegister.i64(7)), 3);
            Box second = box(owner, 1, 500, 3);
            store.applyBlock(3, List.of(
                BoxStore.BoxMutation.write(updated),
                BoxStore.BoxMutation.write(second)));
            assertEquals(updated, store.get(original.id()));
            assertNotNull(store.get(second.id()));

            store.revertBlock(3);
            assertEquals(original, store.get(original.id())); // prior state restored
            assertNull(store.get(second.id()));               // creation undone
            // Owner index reflects only the surviving box.
            assertEquals(1, store.boxIdsByOwner(owner.toBytes(), null, 10).size());
            // Expiry index reflects the restored rent clock (5), not the reverted update's (3).
            assertEquals(1, store.collectableBoxIds(20, 10, 10).size());
        }
    }

    @Test
    void deleteRemovesBoxAndIndexes(@TempDir Path dir) throws Exception {
        PublicAddress owner = PublicAddress.random();
        Box a = box(owner, 0, 1000, 5);
        try (var store = new RocksDbBoxStore(dir.toString())) {
            store.applyBlock(2, List.of(BoxStore.BoxMutation.write(a)));
            store.applyBlock(3, List.of(BoxStore.BoxMutation.delete(a.id())));
            assertNull(store.get(a.id()));
            assertTrue(store.boxIdsByOwner(owner.toBytes(), null, 10).isEmpty());
            assertTrue(store.collectableBoxIds(100, 10, 10).isEmpty());
        }
    }

    @Test
    void pruneJournalsBlocksLaterRevert(@TempDir Path dir) throws Exception {
        PublicAddress owner = PublicAddress.random();
        Box a = box(owner, 0, 1000, 5);
        try (var store = new RocksDbBoxStore(dir.toString())) {
            store.applyBlock(2, List.of(BoxStore.BoxMutation.write(a)));
            store.pruneJournals(3); // drop journals below height 3, including height 2
            // With the journal gone, reverting block 2 is a no-op (state unchanged).
            store.revertBlock(2);
            assertEquals(a, store.get(a.id()));
        }
    }
}
