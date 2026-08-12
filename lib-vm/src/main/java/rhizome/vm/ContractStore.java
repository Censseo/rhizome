package rhizome.vm;

/**
 * The full contract store: point state access, snapshot enumeration, per-block journals and
 * receipts, and the atomic block commit that spans them.
 *
 * <p>Persistent home of contract code and per-contract key/value storage, keyed by contract
 * address. The VM never talks to it directly — a {@link PersistentHostState} sits in between and
 * buffers writes so they commit only when a call succeeds.
 *
 * <p>This was one interface with twenty members and three disjoint audiences. It is now the
 * composition of the three roles — {@link ContractState}, {@link ContractSnapshotStore},
 * {@link ContractJournalStore} — so a consumer declares the role it uses:
 * {@link ContractExecutor}, {@link PersistentHostState}, {@link StorageChange} and
 * {@link SessionContractStore} take {@code ContractState}; only the durable store and the
 * processor that drives it need the whole thing. Every implementation and every existing call
 * site still sees the same twenty members through this type — the split narrows what callers
 * <em>ask for</em>, not what stores provide.
 *
 * <p>{@code applyBlock} and {@code revertBlock} stay here rather than in
 * {@code ContractJournalStore}: they commit mutations AND the journal as one unit, so they are
 * defined over both roles by nature. Pushing them down would have meant a journal interface whose
 * default implementation cannot reach the storage it mutates.
 */
public interface ContractStore extends ContractState, ContractSnapshotStore, ContractJournalStore {

    // ---- Atomic block commit (audit F1) ----
    // Committing a block's contract mutations one slot at a time and only THEN persisting the
    // undo journal was unrecoverable: a crash mid-flush left storage half-applied with no journal
    // to rewind it. applyBlock commits the mutations and the journal as ONE unit on stores that
    // support it (the RocksDB store uses a single synced WriteBatch); revertBlock likewise restores
    // a block's prior state and drops its journal as one unit.

    /**
     * Commits one block's contract mutations together with its serialized undo journal, as a
     * single atomic unit where the store supports it.
     *
     * <p>Semantics:
     * <ul>
     *   <li>{@code changes} are applied in list order; each is a set, or a delete when its value
     *       is null (see {@link StorageChange}). They are exactly what
     *       {@code SessionContractStore.flushWithJournal()} used to write one-by-one.</li>
     *   <li>{@code journal} is the block's serialized undo journal, persisted at {@code height}
     *       exactly as {@link #putJournal} would (a durable store keeps it so a reorg after a
     *       restart can still reverse the block); may be null to commit changes without a
     *       journal (e.g. a block that touched no contract state).</li>
     *   <li>Implementations may refuse a height that already has a journal (double-apply would
     *       capture already-mutated state as the "prior") by throwing
     *       {@link IllegalStateException}.</li>
     * </ul>
     *
     * <p>The default implementation loops the per-operation methods and then
     * {@link #putJournal} — correct, just not atomic — so existing stores keep working.
     */
    default void applyBlock(long height, java.util.List<StorageChange> changes, byte[] journal) {
        for (StorageChange change : changes) {
            change.applyTo(this);
        }
        if (journal != null) {
            putJournal(height, journal);
        }
    }

    /**
     * As {@link #applyBlock(long, java.util.List, byte[])}, additionally committing the block's
     * encoded receipts in the SAME atomic unit where the store supports it (RocksDB: they join
     * the single synced WriteBatch — previously a second fsync per block, audit perf). The
     * default falls back to the separate {@link #putReceipts} write, correct but not atomic.
     */
    default void applyBlock(long height, java.util.List<StorageChange> changes, byte[] journal,
                            byte[] encodedReceipts) {
        applyBlock(height, changes, journal);
        if (encodedReceipts != null) {
            putReceipts(height, encodedReceipts);
        }
    }

    /**
     * Reverts one block: applies {@code restores} (the block's undo journal turned back into
     * mutations — each entry's <em>prior</em> value, or a delete where the key did not exist
     * before the block), drops the persisted journal at {@code height}, AND drops the block's
     * persisted receipts, as a single atomic unit where the store supports it.
     *
     * <p>{@code restores} must already be in final application order (the caller applies its
     * journal in reverse, so that repeated writes to the same key restore the earliest prior).
     * An empty list still drops the journal and receipts: a receipts-carrying block can have
     * no journal (e.g. a reverting CALL that touched no storage).
     *
     * <p>The receipts MUST travel in the same unit as the restores: deleted separately and
     * first, a crash between the two writes left the journal present but the receipts gone, so
     * the rollback guard aborted every subsequent reorg attempt and wedged the node on its
     * fork (audit: revert-path tear).
     *
     * <p>The default implementation loops the per-operation methods, then
     * {@link #deleteJournal}, then {@link #deleteReceipts} — correct, just not atomic.
     */
    default void revertBlock(long height, java.util.List<StorageChange> restores) {
        for (StorageChange restore : restores) {
            restore.applyTo(this);
        }
        deleteJournal(height);
        deleteReceipts(height);
    }
}
