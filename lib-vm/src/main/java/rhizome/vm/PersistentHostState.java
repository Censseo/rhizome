package rhizome.vm;

import java.util.HashMap;
import java.util.Map;

import rhizome.core.ledger.PublicAddress;

/**
 * {@link HostState} backed by a {@link ContractStore}, scoped to one contract and
 * one call. Writes are buffered in memory and only pushed to the store by
 * {@link #commit()}, which the executor calls on success — so a reverted or
 * out-of-gas call leaves persistent storage untouched (transactional execution).
 */
public final class PersistentHostState implements HostState {

    private final ContractStore store;
    private final PublicAddress contract;
    private final byte[] caller;
    private final byte[] input;
    private final long value;
    private final BoxReader boxReader;
    private final NativeTransferHandler transferHandler;

    private final Map<String, byte[]> pending = new HashMap<>();
    private final java.util.List<LogEntry> logs = new java.util.ArrayList<>();
    private byte[] output = new byte[0];

    public PersistentHostState(ContractStore store, PublicAddress contract,
                               byte[] caller, byte[] input, long value) {
        this(store, contract, caller, input, value, null);
    }

    public PersistentHostState(ContractStore store, PublicAddress contract,
                               byte[] caller, byte[] input, long value, BoxReader boxReader) {
        this(store, contract, caller, input, value, boxReader, null);
    }

    public PersistentHostState(ContractStore store, PublicAddress contract,
                               byte[] caller, byte[] input, long value, BoxReader boxReader,
                               NativeTransferHandler transferHandler) {
        this.store = store;
        this.contract = contract;
        this.caller = caller.clone();
        this.input = input.clone();
        this.value = value;
        this.boxReader = boxReader;
        this.transferHandler = transferHandler;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    @Override
    public byte[] storageRead(byte[] key) {
        String k = hex(key);
        if (pending.containsKey(k)) {
            byte[] v = pending.get(k);
            return v == null ? null : v.clone();
        }
        return store.getStorage(contract, key);
    }

    @Override
    public void storageWrite(byte[] key, byte[] value) {
        pending.put(hex(key), value.clone());
    }

    /** Flushes buffered writes to the backing store. Call only when the execution succeeded. */
    public void commit() {
        pending.forEach((k, v) -> store.putStorage(contract, hexToBytes(k), v));
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Override
    public byte[] caller() {
        return caller.clone();
    }

    @Override
    public byte[] input() {
        return input.clone();
    }

    @Override
    public long value() {
        return value;
    }

    @Override
    public void setOutput(byte[] out) {
        this.output = out.clone();
    }

    @Override
    public byte[] output() {
        return output.clone();
    }

    @Override
    public void emitLog(byte[] topic, byte[] data) {
        logs.add(new LogEntry(topic, data));
    }

    @Override
    public byte[] selfAddress() {
        return contract.toBytes();
    }

    /**
     * The deployer recorded at deploy time under the reserved empty storage key. Contracts cannot
     * write that key (the storage_write host function rejects a zero-length key), so this value is
     * unspoofable. Read straight from the store — it is set once at deploy, never in a call session.
     */
    @Override
    public byte[] deployer() {
        byte[] d = store.getStorage(contract, DEPLOYER_KEY);
        return d == null ? new byte[0] : d;
    }

    /** Reserved (zero-length) storage key holding the deployer address; unwritable by contracts. */
    static final byte[] DEPLOYER_KEY = new byte[0];

    @Override
    public int transferValue(byte[] to, long amount) {
        return transferHandler == null ? -1 : transferHandler.transfer(to, amount);
    }

    @Override
    public rhizome.core.box.Box boxRead(byte[] id) {
        return boxReader == null ? null : boxReader.read(id);
    }

    @Override
    public java.util.List<LogEntry> logs() {
        return java.util.List.copyOf(logs);
    }
}
