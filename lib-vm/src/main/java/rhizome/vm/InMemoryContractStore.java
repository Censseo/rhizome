package rhizome.vm;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import rhizome.core.ledger.PublicAddress;

/** In-memory {@link ContractStore} — the reference implementation and test backend. */
public final class InMemoryContractStore implements ContractStore {

    private final Map<PublicAddress, byte[]> code = new HashMap<>();
    private final Map<Slot, byte[]> storage = new HashMap<>();

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
}
