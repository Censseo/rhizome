package rhizome.vm;

/**
 * Fixed gas costs. Deliberately simple and integer-only for determinism; the
 * numbers are placeholders to be tuned once real contracts and fee economics
 * exist. What matters now is that compute and storage both cost gas.
 */
public final class GasSchedule {

    private GasSchedule() {}

    /** Charged for every executed WASM instruction (the compute meter). */
    public static final long PER_INSTRUCTION = 1L;

    public static final long STORAGE_READ_BASE = 50L;
    public static final long STORAGE_WRITE_BASE = 200L;   // writes are the expensive, state-growing op
    public static final long PER_BYTE = 1L;
    public static final long OUTPUT_BASE = 5L;

    /** Emitting an event log: a base cost plus per byte of topic + data. */
    public static final long LOG_BASE = 100L;

    /** Calling another contract: a base cost plus per byte of input and copied output. */
    public static final long CALL_BASE = 500L;

    /** Deploying stores code on-chain: a base cost plus per code byte. */
    public static final long DEPLOY_BASE = 500L;
    public static final long DEPLOY_PER_CODE_BYTE = 10L;

    /** Reading a data box (Ergo-style data input): cheap, base plus per copied byte. */
    public static final long BOX_READ_BASE = 100L;
}
