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
     * Best-effort durability barrier for unsynced bulk writes. Snapshot import seeds code/storage
     * slots through the straight-through path, which a durable store deliberately writes WITHOUT
     * per-entry fsyncs (one fsync per slot made snap-sync unusable); calling this at the end of
     * the import fsyncs the tail so a power loss cannot drop seeded slots the node has already
     * reported as imported. No-op for stores whose writes are always synced or non-durable.
     */
    default void syncToDisk() { }

    @FunctionalInterface
    interface StorageConsumer {
        void accept(PublicAddress contract, byte[] key, byte[] value);
    }
}
