package rhizome.vm;

import java.util.ArrayList;
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
    public void deleteCode(PublicAddress contract) {
        codeWrites.remove(hex(contract.toBytes()));
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

    @Override
    public void deleteStorage(PublicAddress contract, byte[] key) {
        storageWrites.remove(slot(contract, key));
    }

    /**
     * The forward changes buffered in this session, with their final values — for the
     * authenticated state root. Contracts only ever set (a write of empty bytes is a
     * value, not a deletion), so every change carries a non-null value.
     */
    List<rhizome.core.blockchain.ContractProcessor.ContractChange> forwardChanges() {
        List<rhizome.core.blockchain.ContractProcessor.ContractChange> out = new ArrayList<>();
        codeWrites.forEach((k, v) ->
            out.add(new rhizome.core.blockchain.ContractProcessor.ContractChange(
                true, PublicAddress.of(hexToBytes(k)), null, v)));
        storageWrites.forEach((slot, v) -> {
            int sep = slot.indexOf(':');
            PublicAddress contract = PublicAddress.of(hexToBytes(slot.substring(0, sep)));
            byte[] key = hexToBytes(slot.substring(sep + 1));
            out.add(new rhizome.core.blockchain.ContractProcessor.ContractChange(false, contract, key, v));
        });
        return out;
    }

    /**
     * Writes every buffered change into the base store and returns the undo journal
     * (each written key's prior base value, {@code null} if it did not exist), so the
     * block can be reverted exactly.
     */
    List<ContractUndo> flushWithJournal() {
        List<ContractUndo> journal = new ArrayList<>();
        codeWrites.forEach((k, v) -> {
            PublicAddress contract = PublicAddress.of(hexToBytes(k));
            journal.add(new ContractUndo(true, contract, null, base.getCode(contract)));
            base.putCode(contract, v);
        });
        storageWrites.forEach((slot, v) -> {
            int sep = slot.indexOf(':');
            PublicAddress contract = PublicAddress.of(hexToBytes(slot.substring(0, sep)));
            byte[] key = hexToBytes(slot.substring(sep + 1));
            journal.add(new ContractUndo(false, contract, key, base.getStorage(contract, key)));
            base.putStorage(contract, key, v);
        });
        return journal;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
