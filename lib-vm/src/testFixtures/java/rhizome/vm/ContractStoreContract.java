package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;

/**
 * The behaviour every {@link ContractStore} owes its callers, run against each implementation.
 *
 * <p>Persistence across a reopen is deliberately absent — the in-memory store has no equivalent.
 * The double-apply refusal (audit F10) IS included: {@link ContractStore#applyBlock}'s default
 * now guards on {@link ContractStore#getJournal}, so {@link InMemoryContractStore} — which does
 * not override {@code applyBlock} — gets it for free, the same way
 * {@code RocksDbContractStore}'s atomic, guarded override always has.
 */
public interface ContractStoreContract {

    /** A fresh, empty store. */
    ContractStore newContractStore() throws Exception;

    @Test
    default void codeAndStorageRoundTripAndUnusedKeysAreNull() throws Exception {
        ContractStore store = newContractStore();
        PublicAddress contract = PublicAddress.random();
        byte[] code = {0x00, 0x61, 0x73, 0x6d, 1, 2, 3};
        byte[] key = {0};
        byte[] value = {9, 8, 7, 6};

        assertNull(store.getCode(PublicAddress.random()));
        assertNull(store.getStorage(contract, key));

        store.putCode(contract, code);
        store.putStorage(contract, key, value);
        assertArrayEquals(code, store.getCode(contract));
        assertArrayEquals(value, store.getStorage(contract, key));
        assertNull(store.getStorage(contract, new byte[] {1}));
    }

    @Test
    default void storageIsIsolatedPerContract() throws Exception {
        ContractStore store = newContractStore();
        PublicAddress a = PublicAddress.random();
        PublicAddress b = PublicAddress.random();
        byte[] key = {0};
        store.putStorage(a, key, new byte[] {1});
        store.putStorage(b, key, new byte[] {2});
        assertArrayEquals(new byte[] {1}, store.getStorage(a, key));
        assertArrayEquals(new byte[] {2}, store.getStorage(b, key));
    }

    @Test
    default void applyBlockCommitsChangesAndJournal() throws Exception {
        ContractStore store = newContractStore();
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        byte[] fresh = {7};
        byte[] journal = {9, 9, 9};
        store.putStorage(contract, key, new byte[] {1});
        store.applyBlock(10, List.of(
            StorageChange.putStorage(contract, key, new byte[] {2}),
            StorageChange.putStorage(contract, fresh, new byte[] {3}),
            StorageChange.putCode(contract, new byte[] {0x00, 0x61, 0x73, 0x6d})), journal);
        assertArrayEquals(new byte[] {2}, store.getStorage(contract, key));
        assertArrayEquals(new byte[] {3}, store.getStorage(contract, fresh));
        assertArrayEquals(new byte[] {0x00, 0x61, 0x73, 0x6d}, store.getCode(contract));
        assertArrayEquals(journal, store.getJournal(10));
    }

    @Test
    default void revertBlockRestoresPriorsAndDropsJournal() throws Exception {
        ContractStore store = newContractStore();
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        byte[] fresh = {7};
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

    @Test
    default void revertBlockDropsReceiptsInTheSameUnit() throws Exception {
        ContractStore store = newContractStore();
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        store.putStorage(contract, key, new byte[] {1});
        store.applyBlock(10, List.of(StorageChange.putStorage(contract, key, new byte[] {2})), new byte[] {9});
        store.putReceipts(10, new byte[] {7, 7, 7});
        store.revertBlock(10, List.of(StorageChange.putStorage(contract, key, new byte[] {1})));
        assertArrayEquals(new byte[] {1}, store.getStorage(contract, key));
        assertNull(store.getJournal(10));
        assertNull(store.getReceipts(10), "receipts drop with the revert, not separately");
    }

    @Test
    default void revertBlockWithReceiptsAndNoJournalStillDropsThem() throws Exception {
        // A receipts-only height (a reverting CALL that touched no storage): the store-level
        // revert must accept an empty restore list and still drop the receipts.
        ContractStore store = newContractStore();
        store.putReceipts(11, new byte[] {1, 2, 3});
        store.revertBlock(11, List.of());
        assertNull(store.getReceipts(11));
        assertNull(store.getJournal(11));
    }

    @Test
    default void pruneThroughDropsJournalsAndReceiptsAtOrBelowTheCutoff() throws Exception {
        ContractStore store = newContractStore();
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

    @Test
    default void applyBlockRefusesADoubleApply() throws Exception {
        ContractStore store = newContractStore();
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        byte[] journal = {9, 9, 9};
        store.applyBlock(10, List.of(StorageChange.putStorage(contract, key, new byte[] {2})), journal);
        // Re-applying the same height would journal the already-mutated state as "prior" (audit F10).
        List<StorageChange> repeat = List.of(StorageChange.putStorage(contract, key, new byte[] {4}));
        assertThrows(IllegalStateException.class, () -> store.applyBlock(10, repeat, journal));
    }

    @Test
    default void aNullJournalApplyStaysReAppliable() throws Exception {
        // A block that touches no contract state has nothing to undo, so it must not persist a
        // journal — otherwise a legitimately journal-less block would falsely trip the
        // double-apply guard the moment it, or a later journal-less apply at the same height, ran
        // again.
        ContractStore store = newContractStore();
        store.applyBlock(5, List.of(), null);
        store.applyBlock(5, List.of(), null); // must not throw
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        // A later real apply at that same height is still accepted — no phantom journal blocks it.
        store.applyBlock(5, List.of(StorageChange.putStorage(contract, key, new byte[] {1})), new byte[] {7});
        assertArrayEquals(new byte[] {1}, store.getStorage(contract, key));
        assertArrayEquals(new byte[] {7}, store.getJournal(5));
    }
}
