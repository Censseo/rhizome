package rhizome.core.state;

/**
 * Persists the authenticated state root per block height, so the accumulator can roll the
 * current root back to a previous block on a reorg and restore it after a restart. Small
 * (32 bytes per height); implemented in-memory and on RocksDB.
 */
public interface RootStore {

    /** The committed root at {@code height}, or {@code null} if none recorded. */
    byte[] getRoot(long height);

    void putRoot(long height, byte[] root);

    void deleteRoot(long height);

    /** The highest height with a recorded root, or -1 if empty (for restart recovery). */
    long latestHeight();

    /** Drops roots for heights strictly below {@code minHeight}. */
    void pruneBelow(long minHeight);
}
