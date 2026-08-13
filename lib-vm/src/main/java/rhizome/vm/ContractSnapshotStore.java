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
     * Durability barrier after bulk seeding. Kept for API compatibility with stores that
     * predate durable straight-through writes: every current write path commits with fsync
     * (audit: durability in the type), so a durable store has nothing left to flush here —
     * the call is a safe no-op. Stores that ever wrote without fsync must still implement
     * this as the barrier their callers rely on.
     */
    default void syncToDisk() { }

    @FunctionalInterface
    interface StorageConsumer {
        void accept(PublicAddress contract, byte[] key, byte[] value);
    }
}
