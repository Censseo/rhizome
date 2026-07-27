package rhizome.vm;

import rhizome.core.ledger.PublicAddress;

/**
 * Persistent home of contract code and per-contract key/value storage, keyed by
 * contract address. The VM never talks to it directly — a {@link PersistentHostState}
 * sits in between and buffers writes so they commit only when a call succeeds.
 */
public interface ContractStore {

    /** Deployed code for {@code contract}, or {@code null} if no contract lives there. */
    byte[] getCode(PublicAddress contract);

    void putCode(PublicAddress contract, byte[] code);

    /** Removes a contract's code (used to undo a DEPLOY on reorg). */
    void deleteCode(PublicAddress contract);

    /** Value at {@code key} in {@code contract}'s storage, or {@code null} if unset. */
    byte[] getStorage(PublicAddress contract, byte[] key);

    /**
     * Batch variant of {@link #getStorage}: the values for many (contract, key) pairs — possibly
     * spanning contracts — as a parallel list (null where unset), in the argument order. Used by
     * the block commit's journal capture: the default loops the point read (correct everywhere);
     * durable stores override with a single multi-get so a K-write block costs one store
     * round-trip instead of K (audit: journal-capture N+1).
     */
    default java.util.List<byte[]> getStorageMulti(java.util.List<PublicAddress> contracts,
                                                   java.util.List<byte[]> keys) {
        if (contracts.size() != keys.size()) {
            throw new IllegalArgumentException("contracts/keys length mismatch: "
                + contracts.size() + " vs " + keys.size());
        }
        java.util.List<byte[]> out = new java.util.ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            out.add(getStorage(contracts.get(i), keys.get(i)));
        }
        return out;
    }

    /**
     * Sets a storage slot. Straight-through calls are for session buffers and snapshot
     * seeding ONLY: a durable store writes this path deliberately UNSYNCED (bulk-import
     * throughput), so committing block state through it could silently lose the write on
     * power loss. Block commits must go through {@link #applyBlock}/{@link #revertBlock}
     * (synced, atomic with the journal); bulk seeding must end with {@link #syncToDisk()}.
     */
    void putStorage(PublicAddress contract, byte[] key, byte[] value);

    /** Removes a storage entry (used to undo a first write on reorg). */
    void deleteStorage(PublicAddress contract, byte[] key);

    /**
     * Visits every deployed contract's code — the state-snapshot export path. Optional:
     * stores that never serve snapshots may leave the unsupported default.
     */
    default void forEachCode(java.util.function.BiConsumer<PublicAddress, byte[]> consumer) {
        throw new UnsupportedOperationException("this contract store does not support enumeration");
    }

    /** Visits every {@code (contract, key, value)} storage entry — the snapshot export path. */
    default void forEachStorage(StorageConsumer consumer) {
        throw new UnsupportedOperationException("this contract store does not support enumeration");
    }

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

    /**
     * Best-effort durability barrier for unsynced bulk writes. Snapshot import seeds code/storage
     * slots through the straight-through path, which a durable store deliberately writes WITHOUT
     * per-entry fsyncs (one fsync per slot made snap-sync unusable); calling this at the end of
     * the import fsyncs the tail so a power loss cannot drop seeded slots the node has already
     * reported as imported. No-op for stores whose writes are always synced or non-durable.
     */
    default void syncToDisk() { }

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
     * before the block) and drops the persisted journal at {@code height}, as a single atomic
     * unit where the store supports it.
     *
     * <p>{@code restores} must already be in final application order (the caller applies its
     * journal in reverse, so that repeated writes to the same key restore the earliest prior).
     *
     * <p>The default implementation loops the per-operation methods and then
     * {@link #deleteJournal} — correct, just not atomic.
     */
    default void revertBlock(long height, java.util.List<StorageChange> restores) {
        for (StorageChange restore : restores) {
            restore.applyTo(this);
        }
        deleteJournal(height);
    }

    @FunctionalInterface
    interface StorageConsumer {
        void accept(PublicAddress contract, byte[] key, byte[] value);
    }
}
