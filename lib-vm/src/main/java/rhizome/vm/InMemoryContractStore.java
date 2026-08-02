package rhizome.vm;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import rhizome.core.ledger.PublicAddress;

/** In-memory {@link ContractStore} — the reference implementation and test backend. */
public final class InMemoryContractStore implements ContractStore {

    // LinkedHashMap, not HashMap: the forEach* enumeration paths feed the state-snapshot
    // export, whose iteration order must not depend on JVM hash placement (audit: deterministic
    // enumeration). Insertion order is well-defined for every operation sequence.
    private final Map<PublicAddress, byte[]> code = new LinkedHashMap<>();
    private final Map<Slot, byte[]> storage = new LinkedHashMap<>();
    // Journal/receipt columns, mirroring the durable store's hooks: the processor's RAM maps
    // are byte-budgeted caches that load through these on a miss (eviction, or a revert after
    // simulated restart), so the fallback must exist even in this non-durable configuration
    // (audit: bounded RAM retention of journals/receipts).
    private final Map<Long, byte[]> journals = new HashMap<>();
    private final Map<Long, byte[]> receipts = new HashMap<>();

    /** A (contract, storage-key) pair with value-based equality, for use as a map key. */
    private record Slot(PublicAddress contract, byte[] key) {
        @Override public boolean equals(Object o) {
            return o instanceof Slot s && contract.equals(s.contract) && Arrays.equals(key, s.key);
        }
        @Override public int hashCode() {
            return 31 * contract.hashCode() + Arrays.hashCode(key);
        }
    }

    @Override
    public byte[] getCode(PublicAddress contract) {
        byte[] c = code.get(contract);
        return c == null ? null : c.clone();
    }

    @Override
    public void putCode(PublicAddress contract, byte[] c) {
        code.put(contract, c.clone());
    }

    @Override
    public void deleteCode(PublicAddress contract) {
        code.remove(contract);
    }

    @Override
    public byte[] getStorage(PublicAddress contract, byte[] key) {
        byte[] v = storage.get(new Slot(contract, key));
        return v == null ? null : v.clone();
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        storage.put(new Slot(contract, key), value.clone());
    }

    @Override
    public void deleteStorage(PublicAddress contract, byte[] key) {
        storage.remove(new Slot(contract, key));
    }

    @Override
    public void forEachCode(java.util.function.BiConsumer<PublicAddress, byte[]> consumer) {
        code.forEach((contract, c) -> consumer.accept(contract, c.clone()));
    }

    @Override
    public void forEachStorage(StorageConsumer consumer) {
        storage.forEach((slot, value) -> consumer.accept(slot.contract(), slot.key().clone(), value.clone()));
    }

    @Override
    public void putJournal(long height, byte[] serialized) {
        journals.put(height, serialized.clone());
    }

    @Override
    public byte[] getJournal(long height) {
        byte[] j = journals.get(height);
        return j == null ? null : j.clone();
    }

    @Override
    public void deleteJournal(long height) {
        journals.remove(height);
    }

    @Override
    public void putReceipts(long height, byte[] encoded) {
        receipts.put(height, encoded.clone());
    }

    @Override
    public byte[] getReceipts(long height) {
        byte[] r = receipts.get(height);
        return r == null ? null : r.clone();
    }

    @Override
    public void deleteReceipts(long height) {
        receipts.remove(height);
    }

    @Override
    public void pruneThrough(long maxHeight) {
        journals.keySet().removeIf(h -> h <= maxHeight);
        receipts.keySet().removeIf(h -> h <= maxHeight);
    }
}
