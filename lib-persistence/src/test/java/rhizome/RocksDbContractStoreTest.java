package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.persistence.rocksdb.RocksDbContractStore;
import rhizome.vm.StorageChange;

class RocksDbContractStoreTest {

    @Test
    void persistsCodeAndStorageAcrossReopen(@TempDir Path dir) throws Exception {
        PublicAddress contract = PublicAddress.random();
        byte[] code = {0x00, 0x61, 0x73, 0x6d, 1, 2, 3};
        byte[] key = {0};
        byte[] value = {9, 8, 7, 6};

        try (var store = new RocksDbContractStore(dir.toString())) {
            store.putCode(contract, code);
            store.putStorage(contract, key, value);
            assertArrayEquals(code, store.getCode(contract));
            assertArrayEquals(value, store.getStorage(contract, key));
            assertNull(store.getStorage(contract, new byte[] {1}));
            assertNull(store.getCode(PublicAddress.random()));
        }

        // Reopen: state survived (it is on disk, not in memory).
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertArrayEquals(code, store.getCode(contract));
            assertArrayEquals(value, store.getStorage(contract, key));
        }
    }

    @Test
    void storageIsIsolatedPerContract(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbContractStore(dir.toString())) {
            PublicAddress a = PublicAddress.random();
            PublicAddress b = PublicAddress.random();
            byte[] key = {0};
            store.putStorage(a, key, new byte[] {1});
            store.putStorage(b, key, new byte[] {2});
            assertArrayEquals(new byte[] {1}, store.getStorage(a, key));
            assertArrayEquals(new byte[] {2}, store.getStorage(b, key));
        }
    }

    @Test
    void unusedKeysAreNull(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertNull(store.getStorage(PublicAddress.random(), new byte[] {42}));
            assertEquals(null, store.getCode(PublicAddress.random()));
        }
    }

    @Test
    void undoJournalSurvivesReopen(@TempDir Path dir) throws Exception {
        byte[] journal = {1, 2, 3, 4, 5};
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertNull(store.getJournal(7), "no journal yet");
            store.putJournal(7, journal);
            assertArrayEquals(journal, store.getJournal(7));
        }
        // A reorg that follows a restart must still find the persisted journal (audit M9).
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertArrayEquals(journal, store.getJournal(7));
            store.deleteJournal(7);
            assertNull(store.getJournal(7));
        }
    }

    @Test
    void applyBlockCommitsChangesAndJournalAtomicallyAndRefusesDoubleApply(@TempDir Path dir) throws Exception {
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        byte[] fresh = {7};
        byte[] journal = {9, 9, 9};
        try (var store = new RocksDbContractStore(dir.toString())) {
            store.putStorage(contract, key, new byte[] {1});
            // One call commits every slot mutation AND the journal in a single WriteBatch (audit F1).
            store.applyBlock(10, List.of(
                StorageChange.putStorage(contract, key, new byte[] {2}),
                StorageChange.putStorage(contract, fresh, new byte[] {3}),
                StorageChange.putCode(contract, new byte[] {0x00, 0x61, 0x73, 0x6d})), journal);
            assertArrayEquals(new byte[] {2}, store.getStorage(contract, key));
            assertArrayEquals(new byte[] {3}, store.getStorage(contract, fresh));
            assertArrayEquals(new byte[] {0x00, 0x61, 0x73, 0x6d}, store.getCode(contract));
            assertArrayEquals(journal, store.getJournal(10));
            // Re-applying the same height would journal the already-mutated state as "prior" (audit F10).
            assertThrows(IllegalStateException.class,
                () -> store.applyBlock(10, List.of(StorageChange.putStorage(contract, key, new byte[] {4})), journal));
        }
        // The commit survived the reopen — mutations and journal together.
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertArrayEquals(new byte[] {2}, store.getStorage(contract, key));
            assertArrayEquals(journal, store.getJournal(10));
        }
    }

    @Test
    void pruneThroughDropsJournalsAndReceiptsByInterval(@TempDir Path dir) throws Exception {
        // The processor's per-height deletes only reach heights still in its RAM maps (empty
        // after a restart); pruneThrough is the interval drop that keeps pre-restart rows from
        // accumulating forever.
        try (var store = new RocksDbContractStore(dir.toString())) {
            for (long h = 1; h <= 6; h++) {
                store.putJournal(h, new byte[] {(byte) h});
                store.putReceipts(h, new byte[] {(byte) (h + 10)});
            }
            store.pruneThrough(4);
            for (long h = 1; h <= 4; h++) {
                assertNull(store.getJournal(h), "journal at/below the cutoff is gone");
                assertNull(store.getReceipts(h), "receipts at/below the cutoff are gone");
            }
            assertArrayEquals(new byte[] {5}, store.getJournal(5));
            assertArrayEquals(new byte[] {15}, store.getReceipts(5));
            assertArrayEquals(new byte[] {6}, store.getJournal(6));
            assertArrayEquals(new byte[] {16}, store.getReceipts(6));
        }
        // The interval drop is durable across a reopen.
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertNull(store.getJournal(4));
            assertNull(store.getReceipts(4));
            assertArrayEquals(new byte[] {5}, store.getJournal(5));
        }
    }

    @Test
    void revertBlockRestoresPriorsAndDropsJournalAtomically(@TempDir Path dir) throws Exception {
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        byte[] fresh = {7};
        try (var store = new RocksDbContractStore(dir.toString())) {
            store.putStorage(contract, key, new byte[] {1});
            store.applyBlock(10, List.of(
                StorageChange.putStorage(contract, key, new byte[] {2}),
                StorageChange.putStorage(contract, fresh, new byte[] {3})), new byte[] {9});
            // Restores are the undo journal in final application order; null value = delete (audit F1).
            store.revertBlock(10, List.of(
                StorageChange.putStorage(contract, key, new byte[] {1}),
                StorageChange.deleteStorage(contract, fresh)));
            assertArrayEquals(new byte[] {1}, store.getStorage(contract, key));
            assertNull(store.getStorage(contract, fresh));
            assertNull(store.getJournal(10));
        }
    }
}
