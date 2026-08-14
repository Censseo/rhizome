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
    /**
     * Hard cap on the snapshot chunk count a peer may advertise. {@code chunkCount} arrives
     * verbatim from an untrusted peer's {@code /state/snapshot/info}, and both sides of the
     * bootstrap act on it before anything is verified: the wire parser rejects an out-of-range
     * count, and the bootstrap loops and pre-sizes on it — {@code new ArrayList<>(Integer.MAX_VALUE)}
     * allocates a multi-gigabyte backing array and OOMs a bootstrapping node before a single chunk
     * is fetched or the root is checked (audit F6). Each chunk carries many bindings, so even a
     * full-chain snapshot stays far under this.
     *
     * <p>One definition, because a decoder that accepts what its consumer refuses (or the reverse)
     * is a bug with no owner: this used to be two private copies, in lib-net and app-node, held in
     * agreement by a comment that said "mirroring app-node's MAX_SNAPSHOT_CHUNKS" — and they had
     * already diverged on the lower bound.
     */
    public static final int MAX_SNAPSHOT_CHUNKS = 1_000_000;

    public static final int MIN_DIFFICULTY = 6;
    public static final int MAX_DIFFICULTY = 255;
}
