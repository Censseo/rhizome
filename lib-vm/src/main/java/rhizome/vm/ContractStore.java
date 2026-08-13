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
 * {@link PersistentHostState}, {@link StorageChange} and {@link SessionContractStore} take
 * {@code ContractState}; only the durable store and the
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
     * Commits one block's contract mutations and records its undo journal, as a single atomic
     * unit where the store supports it.
     *
     * <p>The store GENERATES the journal itself — it captures each key's prior value before
     * applying the change, exactly like the box and token stores, so the caller hands over
     * plain mutations and the undo protocol is one (audit: one undo protocol). Semantics:
     * <ul>
     *   <li>{@code changes} are applied in list order; each is a set, or a delete when its value
     *       is null (see {@link StorageChange}).</li>
     *   <li>The undo journal — every prior, or a delete where the key did not exist before the
     *       block — is persisted at {@code height} exactly as {@link #putJournal} would (a
     *       durable store keeps it so a reorg after a restart can still reverse the block). A
     *       mutation-less apply persists no journal.</li>
     *   <li>A height that already has a journal MUST be refused with {@link IllegalStateException}:
     *       a double-apply would capture the already-mutated state as the journal's "prior", so a
     *       later revert would restore the wrong values (audit F10).</li>
     * </ul>
     *
     * <p>The default implementation checks the guard, then captures each prior, loops the
     * per-operation methods, and calls {@link #putJournal} — correct, just not atomic — so a
     * store that does not override this gets the guard and the journal for free rather than
     * opting out of them by omission.
     */
    default void applyBlock(long height, java.util.List<StorageChange> changes) {
        if (getJournal(height) != null) {
            throw new IllegalStateException("contract store already has a journal at height " + height);
        }
        java.util.List<ContractUndo> journal = new java.util.ArrayList<>(changes.size());
        for (StorageChange change : changes) {
            byte[] prior = change.isCode()
                ? getCode(change.contract())
                : getStorage(change.contract(), change.key());
            journal.add(new ContractUndo(change.isCode(), change.contract(),
                change.isCode() ? null : change.key(), prior));
        }
        for (StorageChange change : changes) {
            change.applyTo(this);
        }
        if (!journal.isEmpty()) {
            putJournal(height, ContractJournalCodec.encode(journal));
        }
    }

    /**
     * As {@link #applyBlock(long, java.util.List)}, additionally committing the block's encoded
     * receipts in the SAME atomic unit where the store supports it (RocksDB: they join the
     * single synced WriteBatch — previously a second fsync per block, audit perf). The default
     * falls back to the separate {@link #putReceipts} write, correct but not atomic.
     */
    default void applyBlock(long height, java.util.List<StorageChange> changes,
                            byte[] encodedReceipts) {
        applyBlock(height, changes);
        if (encodedReceipts != null) {
            putReceipts(height, encodedReceipts);
        }
    }

    /**
     * Reverts one block: reads the block's persisted undo journal at {@code height} and applies
     * it back — the store decodes its own journal, exactly like {@code BoxStore} and {@code
     * TokenStore} do, rather than requiring the caller to have turned it into mutations already
     * (audit: who decodes the journal). The revert restores every prior value (a null prior is a
     * delete), drops the persisted journal, AND drops the block's persisted receipts, as a single
     * atomic unit where the store supports it.
     *
     * <p>A missing journal still drops the journal and receipts: a receipts-carrying block can
     * have no journal (e.g. a reverting CALL that touched no storage). The receipts MUST travel
     * in the same unit as the restores: deleted separately and first, a crash between the two
     * writes left the journal present but the receipts gone, so the rollback guard aborted every
     * subsequent reorg attempt and wedged the node on its fork (audit: revert-path tear).
     *
     * <p>The default implementation reads the journal, decodes it via
     * {@link ContractJournalCodec}, loops the per-operation methods, then {@link #deleteJournal},
     * then {@link #deleteReceipts} — correct, just not atomic.
     */
    default void revertBlock(long height) {
        byte[] journal = getJournal(height);
        boolean hasReceipts = getReceipts(height) != null;
        if (journal == null && !hasReceipts) {
            // Nothing committed at this height (e.g. a transfer-only block): the revert is a
            // strict no-op, not an empty atomic unit. Boot recovery replays reverts over heights
            // that may have nothing, and an empty write here is pure cost (audit: empty reverts).
            return;
        }
        for (StorageChange restore : journal == null ? java.util.List.<StorageChange>of()
                : ContractJournalCodec.restores(journal)) {
            restore.applyTo(this);
        }
        deleteJournal(height);
        deleteReceipts(height);
    }
}
