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

    /**
     * Charged per 64 KiB page of linear memory — for the eagerly-allocated initial
     * memory at instantiation and for each page a {@code memory.grow} adds. Without
     * this, a module could allocate gigabytes for a couple of gas (the "1 gas per
     * memory.grow" bug); paired with the hard page cap in {@link WasmVm}.
     */
    public static final long MEMORY_PER_PAGE = 128L;

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

    /**
     * Charged on a CALL that misses the module cache and must re-parse + re-scan the whole module
     * (O(code) work). Without it, cycling more distinct max-size contracts than the LRU holds forces
     * that unpriced work on every call — a CPU amplifier (audit vm F3). Bounded by MAX_CODE_SIZE.
     */
    public static final long MODULE_PARSE_BASE = 500L;
    public static final long MODULE_PARSE_PER_BYTE = 2L;

    /** Reading a data box (Ergo-style data input): cheap, base plus per copied byte. */
    public static final long BOX_READ_BASE = 100L;
}
