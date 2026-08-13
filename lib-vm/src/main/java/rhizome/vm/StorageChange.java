package rhizome.vm;

import rhizome.core.ledger.PublicAddress;

/**
 * One contract-state mutation inside an atomic block commit
 * ({@link ContractStore#applyBlock(long, java.util.List)} /
 * {@link ContractStore#revertBlock(long)}).
 *
 * <p>An entry is either a <em>code</em> mutation ({@code isCode == true}, {@code key} is null)
 * or a <em>storage</em> mutation ({@code isCode == false}, {@code key} is the storage slot key).
 * A {@code null} {@code value} means <em>delete</em>; a non-null {@code value} means <em>set</em>.
 * This mirrors what {@code SessionContractStore.flushWithJournal()} produces for the forward
 * direction (code and storage sets — contracts never delete, so forward changes always carry a
 * non-null value) and what the journal restore produces for the revert direction (sets and
 * deletes of both kinds).
 *
 * <p>Array fields are shared, not copied (same convention as {@link ContractUndo}) — callers
 * must not mutate them after handing them to a store.
 *
 * @param isCode true for a code entry ({@code key} must be null), false for a storage entry
 * @param contract the contract whose code/storage is mutated
 * @param key the storage slot key, or null for a code entry
 * @param value the new bytes, or null to delete the entry
 */
public record StorageChange(boolean isCode, PublicAddress contract, byte[] key, byte[] value) {

    public StorageChange {
        if (contract == null) {
            throw new IllegalArgumentException("contract is required");
        }
        if (isCode && key != null) {
            throw new IllegalArgumentException("a code change carries no storage key");
        }
        if (!isCode && key == null) {
            throw new IllegalArgumentException("a storage change requires a key");
        }
    }

    /** Sets (deploys/replaces) a contract's code. */
    public static StorageChange putCode(PublicAddress contract, byte[] code) {
        return new StorageChange(true, contract, null, code);
    }

    /** Removes a contract's code (used to undo a DEPLOY on reorg). */
    public static StorageChange deleteCode(PublicAddress contract) {
        return new StorageChange(true, contract, null, null);
    }

    /** Sets one storage slot of a contract. */
    public static StorageChange putStorage(PublicAddress contract, byte[] key, byte[] value) {
        return new StorageChange(false, contract, key, value);
    }

    /** Removes one storage slot of a contract (used to undo a first write on reorg). */
    public static StorageChange deleteStorage(PublicAddress contract, byte[] key) {
        return new StorageChange(false, contract, key, null);
    }

    /** Applies this mutation to {@code store} via its per-operation methods. */
    public void applyTo(ContractState store) {
        if (isCode) {
            if (value == null) {
                store.deleteCode(contract);
            } else {
                store.putCode(contract, value);
            }
        } else {
            if (value == null) {
                store.deleteStorage(contract, key);
            } else {
                store.putStorage(contract, key, value);
            }
        }
    }
}
