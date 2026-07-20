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
}
