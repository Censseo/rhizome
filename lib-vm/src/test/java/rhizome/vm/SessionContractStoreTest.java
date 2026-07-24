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
}
