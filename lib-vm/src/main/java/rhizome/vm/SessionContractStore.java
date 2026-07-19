package rhizome.vm;

import java.util.HashMap;
import java.util.Map;

import rhizome.core.ledger.PublicAddress;

/**
 * A write-buffering overlay over a base {@link ContractStore}: reads fall through
 * to the base unless the key was written in this session; writes stay in memory
 * until {@link #flush()}. This is the per-block session — the executor flushes it
 * when the block is accepted and drops it otherwise, so contract state moves
 * atomically with the block.
 */
final class SessionContractStore implements ContractStore {

    private final ContractStore base;
    private final Map<String, byte[]> codeWrites = new HashMap<>();
    private final Map<String, byte[]> storageWrites = new HashMap<>();

    SessionContractStore(ContractStore base) {
        this.base = base;
    }

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
        String k = hex(contract.toBytes());
        return codeWrites.containsKey(k) ? codeWrites.get(k).clone() : base.getCode(contract);
    }

    @Override
    public void putCode(PublicAddress contract, byte[] code) {
        codeWrites.put(hex(contract.toBytes()), code.clone());
    }

    @Override
    public byte[] getStorage(PublicAddress contract, byte[] key) {
        String k = slot(contract, key);
        return storageWrites.containsKey(k) ? storageWrites.get(k).clone() : base.getStorage(contract, key);
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        storageWrites.put(slot(contract, key), value.clone());
    }

    /** Writes every buffered change into the base store. */
    void flush() {
        codeWrites.forEach((k, v) -> base.putCode(PublicAddress.of(hexToBytes(k)), v));
        storageWrites.forEach((slot, v) -> {
            int sep = slot.indexOf(':');
            PublicAddress contract = PublicAddress.of(hexToBytes(slot.substring(0, sep)));
            byte[] key = hexToBytes(slot.substring(sep + 1));
            base.putStorage(contract, key, v);
        });
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
