package rhizome.vm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rhizome.core.ledger.PublicAddress;

/**
 * A write-buffering overlay over a base {@link ContractStore}: reads fall through
 * to the base unless the key was written in this session; writes stay in memory
 * until {@link #flushWithJournal()}. This is the per-block session — the executor
 * flushes it when the block is accepted and drops it otherwise, so contract state
 * moves atomically with the block.
 *
 * <p>Flushing also captures an undo journal (the base's prior value for every
 * written key), so a reorg can restore the exact pre-block state.
 *
 * <p>Keys are typed ({@link PublicAddress} and {@link Slot}, both value-equality) rather than hex
 * Strings: storage read/write is on the block's hottest path, so per-op {@code StringBuilder} hex
 * encoding and per-byte {@code Integer.parseInt} decoding on flush were pure allocation/CPU churn.
 */
final class SessionContractStore implements ContractStore {

    private final ContractStore base;
    private final Map<PublicAddress, byte[]> codeWrites = new HashMap<>();
    private final Map<Slot, byte[]> storageWrites = new HashMap<>();

    /** A (contract, storage-key) pair with value-based equality, for use as a map key. */
    private record Slot(PublicAddress contract, byte[] key) {
        @Override public boolean equals(Object o) {
            return o instanceof Slot s && contract.equals(s.contract) && Arrays.equals(key, s.key);
        }
        @Override public int hashCode() {
            return 31 * contract.hashCode() + Arrays.hashCode(key);
        }
    }

    SessionContractStore(ContractStore base) {
        this.base = base;
    }

    @Override
    public byte[] getCode(PublicAddress contract) {
        return codeWrites.containsKey(contract) ? codeWrites.get(contract).clone() : base.getCode(contract);
    }

    @Override
    public void putCode(PublicAddress contract, byte[] code) {
        codeWrites.put(contract, code.clone());
    }

    @Override
    public void deleteCode(PublicAddress contract) {
        codeWrites.remove(contract);
    }

    @Override
    public byte[] getStorage(PublicAddress contract, byte[] key) {
        Slot k = new Slot(contract, key);
        return storageWrites.containsKey(k) ? storageWrites.get(k).clone() : base.getStorage(contract, key);
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        storageWrites.put(new Slot(contract, key), value.clone());
    }

    @Override
    public void deleteStorage(PublicAddress contract, byte[] key) {
        storageWrites.remove(new Slot(contract, key));
    }

    /**
     * The forward changes buffered in this session, with their final values — for the
     * authenticated state root. Contracts only ever set (a write of empty bytes is a
     * value, not a deletion), so every change carries a non-null value.
     */
    List<rhizome.core.blockchain.ContractProcessor.ContractChange> forwardChanges() {
        List<rhizome.core.blockchain.ContractProcessor.ContractChange> out = new ArrayList<>();
        codeWrites.forEach((contract, v) ->
            out.add(new rhizome.core.blockchain.ContractProcessor.ContractChange(true, contract, null, v)));
        storageWrites.forEach((slot, v) ->
            out.add(new rhizome.core.blockchain.ContractProcessor.ContractChange(
                false, slot.contract(), slot.key(), v)));
        return out;
    }

    /**
     * Writes every buffered change into the base store and returns the undo journal
     * (each written key's prior base value, {@code null} if it did not exist), so the
     * block can be reverted exactly.
     */
    List<ContractUndo> flushWithJournal() {
        List<ContractUndo> journal = new ArrayList<>();
        codeWrites.forEach((contract, v) -> {
            journal.add(new ContractUndo(true, contract, null, base.getCode(contract)));
            base.putCode(contract, v);
        });
        storageWrites.forEach((slot, v) -> {
            PublicAddress contract = slot.contract();
            byte[] key = slot.key();
            journal.add(new ContractUndo(false, contract, key, base.getStorage(contract, key)));
            base.putStorage(contract, key, v);
        });
        return journal;
    }
}
