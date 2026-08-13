package rhizome.vm;

import rhizome.core.ledger.PublicAddress;

/**
 * Point access to contract code and per-contract key/value storage: the role the VM and its
 * session buffers need, and nothing else.
 *
 * <p>This is the narrow half of what used to be one twenty-member {@code ContractStore}. The cost
 * of the wide interface was not abstract: {@link SessionContractStore} is a per-call write buffer
 * that implements exactly these seven methods, yet it had to declare itself a whole
 * {@code ContractStore} and inherit thirteen defaults that are wrong for it — enumeration that
 * throws, journal writes that no-op, and an {@code applyBlock} that would happily commit a block
 * into a scratch buffer. It now implements only this.
 *
 * <p>{@link PersistentHostState} and {@link StorageChange} take this type for the same reason:
 * each uses two or three of these methods and none of the rest.
 */
public interface ContractState {

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
     * Sets a storage slot. Durable: a durable store commits this write with fsync, so the value
     * is on disk before the call returns — no caller has to guess whether a straight-through
     * write survived a crash (audit: durability in the type). Block commits additionally ride
     * {@code ContractStore.applyBlock}/{@code revertBlock} for atomicity with the journal.
     */
    void putStorage(PublicAddress contract, byte[] key, byte[] value);

    /** Removes a storage entry (used to undo a first write on reorg). */
    void deleteStorage(PublicAddress contract, byte[] key);
}
