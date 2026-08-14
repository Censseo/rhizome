package rhizome.core.state;

/**
 * Content-addressed node store for the {@link SparseMerkleTree}: nodes are keyed by
 * their own 32-byte hash, so they are immutable and deduplicate naturally. Because a
 * node is never mutated in place, an old tree root stays resolvable after new writes —
 * which is what lets the state accumulator roll back to a previous block's root without
 * an undo journal (it just moves the root pointer). Implemented in-memory and on RocksDB.
 */
public interface SmtNodeStore {

    /** The node bytes stored under {@code hash}, or {@code null} if absent. */
    byte[] get(byte[] hash);

    /** Stores {@code node} under {@code hash} (idempotent — same content, same hash). */
    void put(byte[] hash, byte[] node);

    /**
     * Opens a write batch: nodes {@link #put} until the matching {@link #flushBatch}/{@link
     * #discardBatch} buffer in a read-your-writes overlay and commit in one atomic batch instead of
     * one {@code db.put} per node (audit P8). Applying a single block updates the tree once per touched
     * key, each creating ~depth new nodes read back by the next update — hundreds of point writes a
     * block. A persistent store overrides this; an in-memory store has no write amplification, so the
     * default is a no-op and nodes are stored immediately.
     */
    default void beginBatch() {
        // no-op for stores with no per-node write cost
    }

    /** Flushes the open batch's staged nodes in one atomic write. */
    default void flushBatch() {
        // no-op; see beginBatch
    }

    /** Drops the open batch's staged nodes (a discarded dry-run), persisting none. */
    default void discardBatch() {
        // no-op; see beginBatch
    }

    /**
     * Explicit garbage-collection trigger: a durable store may delete nodes unreachable from
     * any retained root, at its own cadence; an in-memory store has nothing to collect. Called
     * alongside the root prune (never as a hidden side effect of it — constat 41), so the
     * caller names the maintenance it wants. {@code chainTip} is the pruning floor: nodes only
     * reachable past it are garbage.
     */
    default void gcNodesIfDue(long chainTip) {
        // no-op for stores that keep every node (in-memory, or a store without collection)
    }
}
