package rhizome.vm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import rhizome.core.ledger.PublicAddress;

/**
 * A write-buffering overlay over a base {@link ContractState}: reads fall through
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
 *
 * <p><b>Array ownership.</b> WRITES defensively copy every entering array (key and value —
 * {@link #putCode}, {@link #putStorage}, {@link #deleteStorage}), so an array the caller still
 * holds can never alias session state: mutating it after the call changes nothing here. The copy
 * is write-only and cheap; the invariant it replaces ("no caller ever mutates an array it handed
 * over") was untestable, and for a storage key it failed worse than a stale value — the key array
 * lives INSIDE a {@link Slot} map key, so post-hoc mutation made the whole entry unreachable
 * (hash lookup) and silently lost the write. READS ({@link #getCode}/{@link #getStorage}) return
 * the session's own array WITHOUT copying: every downstream consumer treats it as read-only (the
 * VM copies it into contract memory via {@code Memory.write}, the state-root path only hashes it,
 * the flush/journal captures pass it on unmutated, the durable base store copies at its own
 * boundary). The load-bearing read-side invariant: nothing mutates a store-returned array in
 * place.
 */
final class SessionContractStore implements ContractState {

    private final ContractState base;
    // LinkedHashMap, not HashMap: forwardChanges/pendingChanges/captureJournal iterate these maps
    // into CONSENSUS-VISIBLE lists (state-root change order, undo-journal order). HashMap order is
    // a function of hashes and growth history; insertion order is explicit and stable across JVMs.
    private final Map<PublicAddress, byte[]> codeWrites = new LinkedHashMap<>();
    // A null value is a TOMBSTONE: the key was deleted in this session. LinkedHashMap keeps null values,
    // so containsKey distinguishes "deleted here" from "not touched here" (audit F9).
    private final Map<Slot, byte[]> storageWrites = new LinkedHashMap<>();

    /** A (contract, storage-key) pair with value-based equality, for use as a map key. */
    private record Slot(PublicAddress contract, byte[] key) {
        @Override public boolean equals(Object o) {
            return o instanceof Slot s && contract.equals(s.contract) && Arrays.equals(key, s.key);
        }
        @Override public int hashCode() {
            return 31 * contract.hashCode() + Arrays.hashCode(key);
        }
    }

    SessionContractStore(ContractState base) {
        this.base = base;
    }

    /** Read path: zero-copy by design — see the class-level array-ownership invariant. */
    @Override
    public byte[] getCode(PublicAddress contract) {
        return codeWrites.containsKey(contract) ? codeWrites.get(contract) : base.getCode(contract);
    }

    @Override
    public void putCode(PublicAddress contract, byte[] code) {
        codeWrites.put(contract, code.clone()); // defensive copy: class-level ownership invariant
    }

    @Override
    public void deleteCode(PublicAddress contract) {
        codeWrites.remove(contract);
    }

    @Override
    public byte[] getStorage(PublicAddress contract, byte[] key) {
        // The lookup key is borrowed for this call only and never retained — no copy.
        Slot k = new Slot(contract, key);
        if (storageWrites.containsKey(k)) {
            return storageWrites.get(k); // tombstone reads as absent, never the base's old value
        }
        return base.getStorage(contract, key);
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        // Both arrays are copied: the key lives inside the Slot map key (post-hoc caller mutation
        // would make the entry unreachable), the value IS session state.
        storageWrites.put(new Slot(contract, key.clone()), value.clone());
    }

    @Override
    public void deleteStorage(PublicAddress contract, byte[] key) {
        // Tombstone, not removal: removing the pending write let a later read fall through to the
        // base and return the OLD value — the delete silently vanished from this session's view
        // (audit F9). The null marker makes reads return null and makes the flush emit a delete.
        // The key is copied for the same map-key aliasing reason as putStorage.
        storageWrites.put(new Slot(contract, key.clone()), null);
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
     * {@link ContractStore#applyBlock(long, List)} commit path: code and storage sets, and
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
     * Writes every buffered change into the base store and returns the undo journal
     * (each written key's prior base value, {@code null} if it did not exist), so the
     * block can be reverted exactly. Used to fold a call frame into its parent session;
     * the top-level block commit hands the buffered mutations to the store's atomic
     * applyBlock, which generates the journal itself (audit: one undo protocol).
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
