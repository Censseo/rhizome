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
    // A null value is a TOMBSTONE: the key was deleted in this session. HashMap keeps null values,
    // so containsKey distinguishes "deleted here" from "not touched here" (audit F9).
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
        if (storageWrites.containsKey(k)) {
            byte[] v = storageWrites.get(k);
            return v == null ? null : v.clone(); // tombstone reads as absent, never the base's old value
        }
        return base.getStorage(contract, key);
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        storageWrites.put(new Slot(contract, key), value.clone());
    }

    @Override
    public void deleteStorage(PublicAddress contract, byte[] key) {
        // Tombstone, not removal: removing the pending write let a later read fall through to the
        // base and return the OLD value — the delete silently vanished from this session's view
        // (audit F9). The null marker makes reads return null and makes the flush emit a delete.
        storageWrites.put(new Slot(contract, key), null);
    }

    /**
     * The forward changes buffered in this session, with their final values — for the
     * authenticated state root. Contracts only ever set (a write of empty bytes is a
     * value, not a deletion), so every change carries a non-null value. A storage tombstone
     * ({@link #deleteStorage}) has no representation in the forward {@code ContractChange} /
     * state-root format, which models sets only; none can arise from contract execution today,
     * so one here fails loud rather than silently dropping a deletion from the state root.
     */
    List<rhizome.core.blockchain.ContractProcessor.ContractChange> forwardChanges() {
        List<rhizome.core.blockchain.ContractProcessor.ContractChange> out = new ArrayList<>();
        codeWrites.forEach((contract, v) ->
            out.add(new rhizome.core.blockchain.ContractProcessor.ContractChange(true, contract, null, v)));
        storageWrites.forEach((slot, v) -> {
            if (v == null) {
                throw new IllegalStateException(
                    "storage deletions cannot be expressed as forward state-root changes");
            }
            out.add(new rhizome.core.blockchain.ContractProcessor.ContractChange(
                false, slot.contract(), slot.key(), v));
        });
        return out;
    }

    /**
     * The buffered writes as {@link StorageChange} mutations for the atomic
     * {@link ContractStore#applyBlock(long, List, byte[])} commit path: code and storage sets, and
     * a {@link StorageChange#deleteStorage} for each tombstone. Does not touch the base store.
     */
    List<StorageChange> pendingChanges() {
        List<StorageChange> out = new ArrayList<>(codeWrites.size() + storageWrites.size());
        codeWrites.forEach((contract, v) -> out.add(StorageChange.putCode(contract, v)));
        storageWrites.forEach((slot, v) -> out.add(v == null
            ? StorageChange.deleteStorage(slot.contract(), slot.key())
            : StorageChange.putStorage(slot.contract(), slot.key(), v)));
        return out;
    }

    /**
     * The undo journal for this session's buffered writes — each written key's prior base value
     * ({@code null} if it did not exist) — WITHOUT applying the writes. Paired with {@link
     * #pendingChanges()} so the processor can hand mutations and journal to {@link
     * ContractStore#applyBlock(long, List, byte[])} and have them commit as one atomic unit.
     */
    List<ContractUndo> captureJournal() {
        List<ContractUndo> journal = new ArrayList<>();
        codeWrites.forEach((contract, v) ->
            journal.add(new ContractUndo(true, contract, null, base.getCode(contract))));
        storageWrites.forEach((slot, v) ->
            journal.add(new ContractUndo(false, slot.contract(), slot.key(),
                base.getStorage(slot.contract(), slot.key()))));
        return journal;
    }

    /**
     * Writes every buffered change into the base store and returns the undo journal
     * (each written key's prior base value, {@code null} if it did not exist), so the
     * block can be reverted exactly. Used to fold a call frame into its parent session;
     * the top-level block commit instead uses {@link #captureJournal()} + {@link
     * #pendingChanges()} with the store's atomic applyBlock.
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
            if (v == null) {
                base.deleteStorage(contract, key); // tombstone flushes as a delete (audit F9)
            } else {
                base.putStorage(contract, key, v);
            }
        });
        return journal;
    }
}
