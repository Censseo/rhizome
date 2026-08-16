package rhizome.vm;

import rhizome.core.ledger.PublicAddress;

/**
 * Whole-store enumeration and the bulk-import durability barrier: the role the snapshot export and
 * import paths need. Optional — a store that never serves or seeds snapshots leaves the defaults.
 */
public interface ContractSnapshotStore {

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

    /**
     * Opens the bulk-import window: between this call and {@link #syncToDisk()}, a durable
     * store may seed code/storage slots UNSYNCED (WAL-sync throttled), because one fsync per
     * slot made snap-sync effectively unusable. The window is only sound inside the snap-sync
     * bootstrap's marker-guarded seeding — the caller must hold the node store's synced
     * bootstrap marker for the whole window, so a crash mid-import is detected at the next
     * boot rather than leaving silently half-seeded slots (audit M8). Outside that window
     * every write path stays synced (audit: durability in the type). Default no-op: an
     * in-memory store has nothing to throttle.
     */
    default void beginBulkImport() { }

    /**
     * The durability barrier that CLOSES the window opened by {@link #beginBulkImport()}:
     * fsyncs every bulk-seeded write not yet covered by the throttle, so the bootstrap marker
     * may clear with the whole seed durable. On a store that never bulk-writes (or after it)
     * the call is a safe no-op.
     */
    default void syncToDisk() { }

    @FunctionalInterface
    interface StorageConsumer {
        void accept(PublicAddress contract, byte[] key, byte[] value);
    }
}
