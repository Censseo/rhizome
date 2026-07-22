package rhizome.vm;

/**
 * The world a contract call sees: its own key/value storage, the call context
 * (caller, input, attached value), and a slot to write its return data. One
 * instance backs exactly one call.
 *
 * <p>In M1 an in-memory {@link MapHostState} is enough; later this interface is
 * backed by the persistent contract store and wired to the ledger, so the VM
 * layer never depends on how state is persisted.
 */
public interface HostState {

    /** Value stored at {@code key} for this contract, or {@code null} if unset. */
    byte[] storageRead(byte[] key);

    /** Sets {@code key} to {@code value} for this contract. */
    void storageWrite(byte[] key, byte[] value);

    /** Address that initiated the call (may be empty in M1). */
    byte[] caller();

    /** Call input data (may be empty). */
    byte[] input();

    /** Amount of native coin attached to the call, in base units. */
    long value();

    /** Records the contract's return data. */
    void setOutput(byte[] output);

    /** The return data set by the contract (empty if none). */
    byte[] output();

    /** Records an event log emitted by the contract during this call. */
    void emitLog(byte[] topic, byte[] data);

    /** Event logs emitted during this call, in emission order. */
    java.util.List<LogEntry> logs();

    /** The executing contract's own address (EVM ADDRESS); empty when not applicable. */
    default byte[] selfAddress() {
        return new byte[0];
    }

    /**
     * The address that deployed this contract, recorded once at deploy and immutable thereafter;
     * empty when not applicable (e.g. an in-memory test host). Templates use it to bind one-time
     * setup (init) to the deployer so it cannot be front-run (audit T1).
     */
    default byte[] deployer() {
        return new byte[0];
    }

    /**
     * Pays {@code amount} of native coin from this contract's own balance to the 25-byte address
     * {@code to}. Returns 0 on success, -1 if rejected (unaffordable, bad recipient, or unsupported
     * host). Lets a contract disburse funds it holds — e.g. a launchpad paying out sale proceeds
     * (audit T4). Default -1 (in-memory test hosts have no ledger).
     */
    default int transferValue(byte[] to, long amount) {
        return -1;
    }

    /**
     * Reads the {@link rhizome.core.box.Box data box} at {@code id} without consuming
     * it (Ergo-style data input), or {@code null} if none exists. Sees boxes written
     * earlier in the same block. Default {@code null} (boxes not wired).
     */
    default rhizome.core.box.Box boxRead(byte[] id) {
        return null;
    }
}
