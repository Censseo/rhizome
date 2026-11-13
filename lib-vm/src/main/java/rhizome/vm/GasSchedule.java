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

    /**
     * Charged per {@code (params + locals)} slot of every WASM activation. Each frame allocates a
     * slot array and reserves the same count from the tree-wide locals budget, but only the
     * {@code call} instruction itself was priced, so deep recursion of a wide function was
     * ~free per byte allocated. 1 gas/slot keeps ordinary compiled functions (tens of slots)
     * cheap against their instruction count.
     */
    public static final long FRAME_SLOT = 1L;

    /**
     * Charged per declared minimum table entry at instantiation, where {@link #MEMORY_PER_PAGE}
     * prices the initial memory pages: the entries are allocated eagerly, before any instruction.
     */
    public static final long TABLE_ENTRY = 1L;

    public static final long STORAGE_READ_BASE = 50L;
    public static final long STORAGE_WRITE_BASE = 200L;   // writes are the expensive, state-growing op

    /**
     * Per-byte price of a {@code storage_write}'s key+value — deliberately orders of magnitude
     * above {@link #PER_BYTE}. Those bytes become <em>permanent</em> on-chain state, replicated by
     * every full node forever, whereas {@code PER_BYTE} prices transient reads/copies whose cost
     * ends with the call. At 1 gas/byte a 0-gasPrice block (consensus-legal — a miner can always
     * self-include) could grow permanent state by tens of MB per block for free: unbounded state
     * bloat with no fee market signal (audit F5). 256 gas/byte is calibrated against the EVM's
     * SSTORE (~20 000 gas per 32-byte slot ≈ 625 gas/byte): the same order of magnitude, somewhat
     * cheaper to reflect this chain's simpler per-key storage layout (no slot-trie proof overhead).
     */
    public static final long STORAGE_WRITE_PER_BYTE = 256L;

    /** Per-byte price of <em>transient</em> work: reads, copies and event data (never persisted). */
    public static final long PER_BYTE = 1L;
    public static final long OUTPUT_BASE = 5L;

    /** Emitting an event log: a base cost plus per byte of topic + data. */
    public static final long LOG_BASE = 100L;

    /** Calling another contract: a base cost plus per byte of input and copied output. */
    public static final long CALL_BASE = 500L;

    /**
     * Deploying stores code on-chain: a base cost plus per code byte. Deployed code is permanent
     * replicated state exactly like a storage value, so it pays the storage byte rate — at the
     * old 10 gas/byte, state could be grown 25x cheaper through deploy than through
     * {@code storage_write}. (At this rate the {@code NetworkParameters.maxTxGas} cap, 50M, bounds a
     * deployable module to ~195 KB, just under {@link WasmVm#MAX_CODE_SIZE}.)
     */
    public static final long DEPLOY_BASE = 500L;
    public static final long DEPLOY_PER_CODE_BYTE = STORAGE_WRITE_PER_BYTE;

    /**
     * Charged on a CALL that misses the module cache and must re-parse + re-scan the whole module
     * (O(code) work). Without it, cycling more distinct max-size contracts than the LRU holds forces
     * that unpriced work on every call — a CPU amplifier (audit vm F3). Bounded by MAX_CODE_SIZE.
     */
    public static final long MODULE_PARSE_BASE = 500L;
    // 32, not 2: GasCostBenchmark measured cold calls of distinct max-size modules at ~33x the
    // CPU per gas of plain execution (a 250M-gas block of them ~90 s to validate); 32 brings them
    // to ~2x. Every CALL pays it (the charge must not depend on node-local cache state).
    public static final long MODULE_PARSE_PER_BYTE = 32L;

    /** Reading a data box (Ergo-style data input): cheap, base plus per copied byte. */
    public static final long BOX_READ_BASE = 100L;
}
