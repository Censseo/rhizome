package rhizome.vm;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link HostState}: storage in a map, keyed by the hex of the raw key
 * bytes. Used for tests and as the reference implementation; the persistent,
 * ledger-backed host state reuses the same contract.
 */
public final class MapHostState implements HostState {

    private final Map<String, byte[]> storage;
    private final byte[] caller;
    private final byte[] input;
    private final long value;
    private byte[] output = new byte[0];
    private final java.util.List<LogEntry> logs = new java.util.ArrayList<>();

    public MapHostState(byte[] caller, byte[] input, long value) {
        this(new HashMap<>(), caller, input, value);
    }

    /** Shares {@code storage} across calls so a contract's state persists between them. */
    public MapHostState(Map<String, byte[]> storage, byte[] caller, byte[] input, long value) {
        this.storage = storage;
        this.caller = caller.clone();
        this.input = input.clone();
        this.value = value;
    }

    private static String key(byte[] k) {
        StringBuilder sb = new StringBuilder(k.length * 2);
        for (byte b : k) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Override
    public byte[] storageRead(byte[] k) {
        byte[] v = storage.get(key(k));
        return v == null ? null : v.clone();
    }

    @Override
    public void storageWrite(byte[] k, byte[] v) {
        storage.put(key(k), v.clone());
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
    public java.util.List<LogEntry> logs() {
        return java.util.List.copyOf(logs);
    }
}
