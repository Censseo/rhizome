package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.ledger.PublicAddress;

/**
 * The state-root audience of the contract domain: the committed contract writes one block
 * contributed, which {@code ChainEngine} folds into the authenticated state root through
 * {@code BlockStateChanges}. Declared apart from {@link ContractProcessor} so the state-root
 * translation layer depends on exactly this read — it neither executes contracts nor serves
 * the dashboard API.
 */
public interface ContractStateSource {

    /** Contract code/storage writes committed by {@code blockHeight}, for the authenticated state root. */
    default List<ContractChange> changes(long blockHeight) {
        return List.of();
    }

    /**
     * One committed contract write with its final value. {@code code} distinguishes a code
     * write (deploy — {@code key} null) from a storage write. Contracts never delete forward
     * (a storage write of empty bytes is a value, not a deletion), so {@code value} is non-null.
     */
    record ContractChange(boolean code, PublicAddress contract, byte[] key, byte[] value) {}
}
