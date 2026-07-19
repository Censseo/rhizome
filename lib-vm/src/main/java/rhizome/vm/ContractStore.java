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

    /** Value at {@code key} in {@code contract}'s storage, or {@code null} if unset. */
    byte[] getStorage(PublicAddress contract, byte[] key);

    void putStorage(PublicAddress contract, byte[] key, byte[] value);
}
