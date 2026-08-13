package rhizome.core.common;

public final class Constants {

    private Constants() {}

    public static final int DECIMAL_SCALE_FACTOR = 10000;
    public static final int BLOCKS_PER_FETCH = 200;
    public static final int BLOCK_HEADERS_PER_FETCH = 2000;

    public static final int MAX_TRANSACTIONS_PER_BLOCK = 25000;
    /**
     * Hard upper bound on the uncle count a decoder will accept before allocating, a
     * loose anti-DoS guard (an attacker-controlled 4-byte count must not size a
     * multi-GB list). Consensus enforces the real, per-network {@code maxUnclesPerBlock}
     * (2) later; this only stops the decode-time OOM and is set comfortably above any
     * plausible parameter. Kept small (8×) over the consensus max so a peer cannot pad a
     * header/block on the /headers and /sync streams with dozens of soon-to-be-rejected uncle
     * records — a decode-accepted bloat amplifier — while still leaving headroom (audit L7).
     */
    public static final int MAX_UNCLES_PER_BLOCK = 16;
    /** Max serialized block size (4 MiB): fits a full transfer block and bounds contract payloads. */
    public static final int MAX_BLOCK_SIZE_BYTES = 4 * 1024 * 1024;

    public static final int MIN_DIFFICULTY = 6;
    public static final int MAX_DIFFICULTY = 255;
}
