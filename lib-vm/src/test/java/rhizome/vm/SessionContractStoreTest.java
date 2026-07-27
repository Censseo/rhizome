package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;

/**
 * Regression tests for {@link SessionContractStore} delete semantics (audit F9): a
 * {@code deleteStorage} must TOMBSTONE the key in the session, not drop the pending write —
 * dropping it let a later read fall through to the base store and return the OLD value, so the
 * delete silently vanished from the session's own view. The tombstone must read as absent, flush
 * as a real delete (journalling the prior value), and map to a {@link StorageChange} delete for
 * the atomic applyBlock path.
 */
class SessionContractStoreTest {

    private final InMemoryContractStore base = new InMemoryContractStore();
    private final SessionContractStore session = new SessionContractStore(base);

    private static final PublicAddress CONTRACT = PublicAddress.random();
    private static final byte[] KEY = {1};
    private static final byte[] OLD = {9};
    private static final byte[] NEW = {7};

    @Test
    void deletedKeyReadsAsAbsentNotAsTheBasesOldValue() {
        base.putStorage(CONTRACT, KEY, OLD);
        session.deleteStorage(CONTRACT, KEY);
        assertNull(session.getStorage(CONTRACT, KEY),
            "a deleted key must not fall through to the base's old value (audit F9)");
    }

    @Test
    void flushAppliesADeleteAndJournalsThePriorValue() {
        base.putStorage(CONTRACT, KEY, OLD);
        session.deleteStorage(CONTRACT, KEY);
        List<ContractUndo> journal = session.flushWithJournal();
        assertNull(base.getStorage(CONTRACT, KEY), "flush must apply the delete to the base");
        assertEquals(1, journal.size());
        assertFalse(journal.get(0).isCode());
        assertArrayEquals(OLD, journal.get(0).prior(),
            "the undo journal must capture the pre-delete value so a reorg can restore it");
    }

    @Test
    void pendingChangesMapsATombstoneToADeleteStorageChange() {
        session.deleteStorage(CONTRACT, KEY);
        List<StorageChange> changes = session.pendingChanges();
        assertEquals(1, changes.size());
        StorageChange change = changes.get(0);
        assertFalse(change.isCode());
        assertArrayEquals(KEY, change.key());
        assertNull(change.value(), "a tombstone must map to a delete (null value) change");
    }

    @Test
    void putThenDeleteLeavesATombstone() {
        base.putStorage(CONTRACT, KEY, OLD);
        session.putStorage(CONTRACT, KEY, NEW);
        session.deleteStorage(CONTRACT, KEY);
        assertNull(session.getStorage(CONTRACT, KEY));
        assertNull(session.pendingChanges().get(0).value(),
            "a put overwritten by a delete flushes as a delete");
    }

    @Test
    void deleteThenPutReadsTheNewValue() {
        base.putStorage(CONTRACT, KEY, OLD);
        session.deleteStorage(CONTRACT, KEY);
        session.putStorage(CONTRACT, KEY, NEW);
        assertArrayEquals(NEW, session.getStorage(CONTRACT, KEY),
            "a put overwrites the tombstone");
        assertArrayEquals(NEW, session.pendingChanges().get(0).value());
    }

    @Test
    void forwardChangesRejectsATombstone() {
        // The forward ContractChange / state-root format models sets only — a deletion has no
        // representation, so it must fail loud rather than silently drop from the state root.
        session.deleteStorage(CONTRACT, KEY);
        assertThrows(IllegalStateException.class, session::forwardChanges);
    }

    // ---- array ownership (class-level invariant: writes copy, reads share read-only) ----

    @Test
    void mutatingTheCallersArrayAfterPutStorageDoesNotChangeSessionState() {
        byte[] key = {1};
        byte[] value = {7};
        session.putStorage(CONTRACT, key, value);
        key[0] = 99;
        value[0] = 99;
        assertArrayEquals(new byte[] {7}, session.getStorage(CONTRACT, new byte[] {1}),
            "a write takes a defensive copy: the caller's array never aliases session state");
        assertNull(session.getStorage(CONTRACT, new byte[] {99}),
            "mutating the caller's key array must not move the entry (the Slot map key is a copy)");
    }

    @Test
    void mutatingTheCallersKeyAfterDeleteStorageKeepsTheTombstoneOnTheRightKey() {
        base.putStorage(CONTRACT, KEY, OLD);
        byte[] key = {1};
        session.deleteStorage(CONTRACT, key);
        key[0] = 99;
        session.flushWithJournal();
        assertNull(base.getStorage(CONTRACT, KEY),
            "the tombstone must stay on the key as it was at delete time");
        assertNull(base.getStorage(CONTRACT, new byte[] {99}),
            "no tombstone may leak onto the mutated key");
    }

    @Test
    void mutatingTheCallersArrayAfterPutCodeDoesNotChangeSessionState() {
        byte[] code = {1, 2, 3};
        session.putCode(CONTRACT, code);
        code[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, session.getCode(CONTRACT));
    }

    @Test
    void consecutiveReadsAreCoherentAcrossAnIntermediatePut() {
        session.putStorage(CONTRACT, KEY, new byte[] {1});
        byte[] first = session.getStorage(CONTRACT, KEY);
        assertArrayEquals(new byte[] {1}, first);
        assertArrayEquals(first, session.getStorage(CONTRACT, KEY),
            "repeated reads of an untouched key agree");
        session.putStorage(CONTRACT, KEY, new byte[] {2});
        assertArrayEquals(new byte[] {2}, session.getStorage(CONTRACT, KEY),
            "an intermediate put is reflected by the next read");
        assertArrayEquals(new byte[] {1}, first,
            "a put REPLACES the entry — the array returned before it still holds the old value");
    }

    @Test
    void aFoldedChildSharesNoArraysWithItsParent() {
        SessionContractStore parent = new SessionContractStore(base);
        SessionContractStore child = new SessionContractStore(parent);
        byte[] key = {1};
        byte[] value = {7};
        child.putStorage(CONTRACT, key, value);
        child.flushWithJournal(); // fold: child writes merge into the parent

        key[0] = 99;
        value[0] = 99;
        assertArrayEquals(new byte[] {7}, parent.getStorage(CONTRACT, new byte[] {1}),
            "the fold copies at the parent boundary — mutating the child's source arrays "
                + "after the fold must not be visible in the parent");

        // A later write in the child belongs to a new frame and must not reach the parent.
        child.putStorage(CONTRACT, new byte[] {1}, new byte[] {42});
        assertArrayEquals(new byte[] {7}, parent.getStorage(CONTRACT, new byte[] {1}));

        // Reverse direction: a parent write after the fold is equally insulated — the child
        // reads the parent's copy through the fall-through, unaffected by caller mutation.
        byte[] parentValue = {5};
        parent.putStorage(CONTRACT, new byte[] {2}, parentValue);
        parentValue[0] = 77;
        assertArrayEquals(new byte[] {5}, child.getStorage(CONTRACT, new byte[] {2}));
    }
}
