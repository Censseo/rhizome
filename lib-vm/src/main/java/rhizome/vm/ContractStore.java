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

    @FunctionalInterface
    interface StorageConsumer {
        void accept(PublicAddress contract, byte[] key, byte[] value);
    }
}
