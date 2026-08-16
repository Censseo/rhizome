package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.persistence.rocksdb.RocksDbContractStore;
import rhizome.vm.ContractStore;
import rhizome.vm.ContractStoreContract;
import rhizome.vm.StorageChange;

/**
 * RocksDB-specific behaviour beyond {@link ContractStoreContract}: persistence across a reopen,
 * which an in-memory store has no equivalent of — see {@code InMemoryContractStoreTest} and
 * {@code ContractStoreContract} for the properties (including the double-apply refusal) both
 * backends share.
 */
class RocksDbContractStoreTest implements ContractStoreContract {

    @TempDir
    Path dir;

    private RocksDbContractStore opened;

    @Override
    public ContractStore newContractStore() throws Exception {
        opened = new RocksDbContractStore(dir.toString());
        return opened;
    }

    @AfterEach
    void tearDown() {
        if (opened != null) {
            opened.close();
        }
    }

    @Test
    void persistsCodeAndStorageAcrossReopen() throws Exception {
        PublicAddress contract = PublicAddress.random();
        byte[] code = {0x00, 0x61, 0x73, 0x6d, 1, 2, 3};
        byte[] key = {0};
        byte[] value = {9, 8, 7, 6};

        try (var store = new RocksDbContractStore(dir.toString())) {
            store.putCode(contract, code);
            store.putStorage(contract, key, value);
            assertArrayEquals(code, store.getCode(contract));
            assertArrayEquals(value, store.getStorage(contract, key));
        }

        // Reopen: state survived (it is on disk, not in memory).
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertArrayEquals(code, store.getCode(contract));
            assertArrayEquals(value, store.getStorage(contract, key));
        }
    }

    @Test
    void bulkImportedCodeAndStorageAreReadableAfterAReopen() throws Exception {
        // The snapshot-import window (beginBulkImport .. syncToDisk) seeds slots unsynced —
        // one fsync per slot made snap-sync unusable. What this pins: a slot written inside the
        // window still reads back after a close/reopen, exactly like the synced path outside it.
        //
        // What it CANNOT pin: the closing fsync barrier. A clean close() flushes RocksDB, so
        // this test passes with syncToDisk's barrier removed (the same mutation was run against
        // the node store's twin of this test). The barrier's value is under power loss, which no
        // JVM test observes; what covers a crash mid-import is the bootstrap marker in
        // RocksDbNodeStore, tested there.
        PublicAddress contract = PublicAddress.random();
        byte[] code = {0x00, 0x61, 0x73, 0x6d, 4, 5, 6};
        byte[] key = {7};
        byte[] value = {1, 2, 3};
        try (var store = new RocksDbContractStore(dir.toString())) {
            store.beginBulkImport();
            store.putCode(contract, code);
            store.putStorage(contract, key, value);
            store.syncToDisk(); // the barrier closes the window
        }
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertArrayEquals(code, store.getCode(contract));
            assertArrayEquals(value, store.getStorage(contract, key));
        }
    }

    @Test
    void undoJournalSurvivesReopen() throws Exception {
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
    void pruneThroughDropsJournalsAndReceiptsAcrossReopen() throws Exception {
        try (var store = new RocksDbContractStore(dir.toString())) {
            for (long h = 1; h <= 6; h++) {
                store.putJournal(h, new byte[] {(byte) h});
                store.putReceipts(h, new byte[] {(byte) (h + 10)});
            }
            store.pruneThrough(4);
        }
        // The interval drop is durable across a reopen.
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertNull(store.getJournal(4));
            assertNull(store.getReceipts(4));
            assertArrayEquals(new byte[] {5}, store.getJournal(5));
        }
    }

    @Test
    void receiptsSurviveReopenAndAreDroppedByRevert() throws Exception {
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        try (var store = new RocksDbContractStore(dir.toString())) {
            store.putStorage(contract, key, new byte[] {1});
            // The store GENERATES its own journal from the changes (audit: one undo protocol).
            store.applyBlock(10, List.of(StorageChange.putStorage(contract, key, new byte[] {2})),
                new byte[] {7, 7, 7});
        }
        // The block's journal AND receipts are on disk, not in memory.
        try (var store = new RocksDbContractStore(dir.toString())) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {7, 7, 7}, store.getReceipts(10));
            org.junit.jupiter.api.Assertions.assertNotNull(store.getJournal(10),
                "the store must have persisted the journal it generated");
            store.revertBlock(10); // the store decodes its own journal
        }
        // The revert's drops are durable too.
        try (var store = new RocksDbContractStore(dir.toString())) {
            assertNull(store.getReceipts(10));
            assertNull(store.getJournal(10));
            org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {1}, store.getStorage(contract, key));
        }
    }
}
