package rhizome.vm;

/**
 * Per-block undo journals and contract receipts, plus their interval prune: the role only a
 * durable store implements and only the reorg/recovery paths call. Every method is an optional
 * no-op, because the in-memory store keeps both in RAM and light stores need neither.
 */
public interface ContractJournalStore {

    // ---- Optional persistent per-block undo journal (audit M9) ----
    // A durable store (RocksDB) persists each block's contract undo journal so a reorg that
    // follows a process restart can still be reversed exactly, instead of relying only on the
    // processor's in-memory journals (lost on crash). Default no-ops: the in-memory store keeps
    // its journals in RAM, and enumeration-only/light stores need none.

    /** Persists the serialized undo journal for {@code height} (durable stores only). */
    default void putJournal(long height, byte[] serialized) { }

    /** The persisted undo journal for {@code height}, or {@code null} if none (or not durable). */
    default byte[] getJournal(long height) {
        return null;
    }

    /** Drops the persisted journal for {@code height}. */
    default void deleteJournal(long height) { }

    // ---- Optional persistent per-block contract receipts (audit F3) ----
    // The Executor's rollback consumes a block's contract receipts (gas used, success, native
    // transfers) to reverse each contract tx's ledger effects on a reorg. Kept only in the
    // processor's RAM, they are lost on restart: a reorg that follows one then crashes
    // mid-rollback (indexing an empty receipt list) with the ledger half-reverted. A durable
    // store persists them alongside the undo journal — same height key, same prune schedule.
    // Default no-ops: the in-memory store keeps receipts in RAM, and light stores need none.

    /** Persists the encoded contract receipts for {@code height} (durable stores only). */
    default void putReceipts(long height, byte[] encoded) { }

    /** The persisted encoded contract receipts for {@code height}, or {@code null} if none. */
    default byte[] getReceipts(long height) {
        return null;
    }

    /** Drops the persisted receipts for {@code height}. */
    default void deleteReceipts(long height) { }

    /**
     * Drops ALL persisted journals and receipts for heights {@code <= maxHeight}, in one
     * interval operation (durable stores only). The processor prunes per-height from its RAM
     * maps, but those maps are empty after a restart — without an interval prune every journal
     * and receipt written before the restart would stay on disk forever. Symmetric with
     * {@code BoxStore.pruneJournals}, which drops both journals and receipts by range.
     */
    default void pruneThrough(long maxHeight) { }
}
