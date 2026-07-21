package rhizome.core.common;

public final class Constants {

    private Constants() {}

    // System
    public static final int DECIMAL_SCALE_FACTOR = 10000;
    public static final int TIMEOUT_MS = 5000;
    public static final int TIMEOUT_BLOCK_MS = 30000;
    public static final int TIMEOUT_BLOCKHEADERS_MS = 60000;
    public static final int TIMEOUT_SUBMIT_MS = 30000;
    public static final int BLOCKS_PER_FETCH = 200;
    public static final int BLOCK_HEADERS_PER_FETCH = 2000;
    public static final String BUILD_VERSION = "0.9.0-alpha";

    // Files
    public static final String LEDGER_FILE_PATH = "./data/ledger";
    public static final String TXDB_FILE_PATH = "./data/txdb";
    public static final String BLOCK_STORE_FILE_PATH = "./data/blocks";
    public static final String PUFFERFISH_CACHE_FILE_PATH = "./data/pufferfish";

    // Blocks
    public static final int MAX_TRANSACTIONS_PER_BLOCK = 25000;
    /**
     * Hard upper bound on the uncle count a decoder will accept before allocating, a
     * loose anti-DoS guard (an attacker-controlled 4-byte count must not size a
     * multi-GB list). Consensus enforces the real, per-network {@code maxUnclesPerBlock}
     * (2) later; this only stops the decode-time OOM and is set comfortably above any
     * plausible parameter.
     */
    public static final int MAX_UNCLES_PER_BLOCK = 128;
    /** Max serialized block size (4 MiB): fits a full transfer block and bounds contract payloads. */
    public static final int MAX_BLOCK_SIZE_BYTES = 4 * 1024 * 1024;
    public static final int PUFFERFISH_START_BLOCK = 124500;

    // Difficulty
    public static final int DIFFICULTY_LOOKBACK = 100;
    public static final int DESIRED_BLOCK_TIME_SEC = 90;
    public static final int MIN_DIFFICULTY = 6;
    public static final int MAX_DIFFICULTY = 255;
}
