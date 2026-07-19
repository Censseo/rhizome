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
}
