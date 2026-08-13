package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    default void applyBlockCommitsChangesAndGeneratesTheJournal() throws Exception {
        ContractStore store = newContractStore();
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        byte[] fresh = {7};
        store.putStorage(contract, key, new byte[] {1});
        store.putCode(contract, new byte[] {0x00, 0x61, 0x73, 0x6d, 9});
        store.applyBlock(10, List.of(
            StorageChange.putStorage(contract, key, new byte[] {2}),
            StorageChange.putStorage(contract, fresh, new byte[] {3}),
            StorageChange.putCode(contract, new byte[] {0x00, 0x61, 0x73, 0x6d})));
        assertArrayEquals(new byte[] {2}, store.getStorage(contract, key));
        assertArrayEquals(new byte[] {3}, store.getStorage(contract, fresh));
        assertArrayEquals(new byte[] {0x00, 0x61, 0x73, 0x6d}, store.getCode(contract));
        // The store generated the journal itself: each prior, in change order (audit: one undo
        // protocol). Decodable and re-revertable — see applyThenRevertRestoresTheWholeStoreByteForByte.
        List<ContractUndo> journal = ContractJournalCodec.decode(store.getJournal(10));
        assertEquals(3, journal.size());
        assertArrayEquals(new byte[] {1}, journal.get(0).prior());
        assertNull(journal.get(1).prior(), "a fresh key's prior is a delete");
        assertArrayEquals(new byte[] {0x00, 0x61, 0x73, 0x6d, 9}, journal.get(2).prior());
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
            StorageChange.putStorage(contract, fresh, new byte[] {3})));
        store.revertBlock(10);
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
        store.applyBlock(10, List.of(StorageChange.putStorage(contract, key, new byte[] {2})));
        store.putReceipts(10, new byte[] {7, 7, 7});
        store.revertBlock(10);
        assertArrayEquals(new byte[] {1}, store.getStorage(contract, key));
        assertNull(store.getJournal(10));
        assertNull(store.getReceipts(10), "receipts drop with the revert, not separately");
    }

    @Test
    default void revertBlockWithReceiptsAndNoJournalStillDropsThem() throws Exception {
        // A receipts-only height (a reverting CALL that touched no storage): the store-level
        // revert must find no journal and still drop the receipts.
        ContractStore store = newContractStore();
        store.putReceipts(11, new byte[] {1, 2, 3});
        store.revertBlock(11);
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
        store.applyBlock(10, List.of(StorageChange.putStorage(contract, key, new byte[] {2})));
        // Re-applying the same height would journal the already-mutated state as "prior" (audit F10).
        List<StorageChange> repeat = List.of(StorageChange.putStorage(contract, key, new byte[] {4}));
        assertThrows(IllegalStateException.class, () -> store.applyBlock(10, repeat));
    }

    @Test
    default void aNullJournalApplyStaysReAppliable() throws Exception {
        // A block that touches no contract state has nothing to undo, so it must not persist a
        // journal — otherwise a legitimately journal-less block would falsely trip the
        // double-apply guard the moment it, or a later journal-less apply at the same height, ran
        // again.
        ContractStore store = newContractStore();
        store.applyBlock(5, List.of());
        store.applyBlock(5, List.of()); // must not throw
        PublicAddress contract = PublicAddress.random();
        byte[] key = {0};
        // A later real apply at that same height is still accepted — no phantom journal blocks it.
        store.applyBlock(5, List.of(StorageChange.putStorage(contract, key, new byte[] {1})), new byte[] {7});
        assertArrayEquals(new byte[] {1}, store.getStorage(contract, key));
        assertNotNull(store.getJournal(5));
    }

    /**
     * The exact-inverse property that makes a reorg safe: applying {@code N} blocks and then
     * reverting them in reverse order must restore the WHOLE store, byte for byte — not just
     * the few keys a test author thought to check. A journal that captures a wrong "prior"
     * (or the wrong order, or misses a change) passes a hand-picked assertion and corrupts a
     * max-depth reorg on the network.
     */
    @Test
    default void applyThenRevertRestoresTheWholeStoreByteForByte() throws Exception {
        ContractStore store = newContractStore();
        PublicAddress contract = PublicAddress.random();
        byte[] keyA = {0};
        byte[] keyB = {1};
        store.putStorage(contract, keyA, new byte[] {1});
        store.putCode(contract, new byte[] {0x00, 0x61, 0x73, 0x6d});
        String before = wholeStoreBytes(store);

        store.applyBlock(10, List.of(
            StorageChange.putStorage(contract, keyA, new byte[] {2}),
            StorageChange.putStorage(contract, keyB, new byte[] {3})));
        store.applyBlock(11, List.of(
            StorageChange.putStorage(contract, keyA, new byte[] {4}),
            StorageChange.deleteStorage(contract, keyB),
            StorageChange.putCode(contract, new byte[] {0x00, 0x61, 0x73, 0x6d, 5})));
        assertTrue(!wholeStoreBytes(store).equals(before),
            "the later blocks must actually change the store for the test to mean anything");

        // The store generated AND decodes its own journal — the revert takes no caller-supplied
        // restores (audit: one undo protocol).
        store.revertBlock(11);
        store.revertBlock(10);
        assertEquals(before, wholeStoreBytes(store),
            "reverting N blocks must restore the store to its pre-apply state, byte for byte");
    }

    /** The store's entire content as a deterministic, order-independent byte string. */
    private static String wholeStoreBytes(ContractStore store) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        store.forEachCode((contract, code) -> parts.add(
            "C" + contract.toHexString() + rhizome.core.common.Utils.bytesToHex(code)));
        store.forEachStorage((contract, key, value) -> parts.add(
            "S" + contract.toHexString() + rhizome.core.common.Utils.bytesToHex(key)
                + rhizome.core.common.Utils.bytesToHex(value)));
        parts.sort(String::compareTo);
        return String.join("|", parts);
    }
}
