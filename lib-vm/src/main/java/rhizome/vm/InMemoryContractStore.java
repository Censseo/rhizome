package rhizome.vm;

import java.util.HashMap;
import java.util.Map;

import rhizome.core.ledger.PublicAddress;

/** In-memory {@link ContractStore} — the reference implementation and test backend. */
public final class InMemoryContractStore implements ContractStore {

    private final Map<String, byte[]> code = new HashMap<>();
    private final Map<String, byte[]> storage = new HashMap<>();

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private static String slot(PublicAddress contract, byte[] key) {
        return hex(contract.toBytes()) + ":" + hex(key);
    }

    @Override
    public byte[] getCode(PublicAddress contract) {
        byte[] c = code.get(hex(contract.toBytes()));
        return c == null ? null : c.clone();
    }

    @Override
    public void putCode(PublicAddress contract, byte[] c) {
        code.put(hex(contract.toBytes()), c.clone());
    }

    @Override
    public void deleteCode(PublicAddress contract) {
        code.remove(hex(contract.toBytes()));
    }

    @Override
    public byte[] getStorage(PublicAddress contract, byte[] key) {
        byte[] v = storage.get(slot(contract, key));
        return v == null ? null : v.clone();
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        storage.put(slot(contract, key), value.clone());
    }

    @Override
    public void deleteStorage(PublicAddress contract, byte[] key) {
        storage.remove(slot(contract, key));
    }
}
