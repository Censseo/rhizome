package rhizome.core.box;

import java.util.List;

/**
 * Persistent home of {@link Box data boxes}, plus the per-block undo journal that
 * makes box state exactly reversible on a reorg. Implemented in-memory
 * ({@link InMemoryBoxStore}) and on RocksDB (in {@code lib-persistence}); the
 * consensus core depends only on this interface.
 *
 * <p>A block's box changes are applied as one atomic batch ({@link #applyBlock}),
 * which also records the journal for that height. Unlike the contract store's
 * in-memory journals, the box journal is persisted, so a reorg that follows a
 * restart can still restore box state.
 */
public interface BoxStore {

    /** The box at {@code id}, or {@code null} if none exists. */
    Box get(byte[] id);

    /**
     * Applies one block's box mutations atomically and records an undo journal keyed
     * by {@code height}. Each mutation either writes a box ({@link BoxMutation#box()}
     * non-null) or deletes the box at {@link BoxMutation#id()} (box null).
     */
    void applyBlock(long height, List<BoxMutation> mutations);

    /** Reverts the box changes committed for {@code height} using the persisted journal. */
    void revertBlock(long height);

    /** Drops journals for heights strictly below {@code minHeight} (unreachable by any reorg). */
    void pruneJournals(long minHeight);

    /**
     * Ids of boxes whose {@code expiryHeight <= height} (rent-collectable), lowest
     * expiry first, at most {@code limit}. Used by the block producer to mint
     * {@code BOX_COLLECT} transactions.
     */
    List<byte[]> collectableBoxIds(long height, long storagePeriodBlocks, int limit);

    /** Box ids owned by {@code owner}, paginated after {@code afterId} (null = from start). */
    List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit);

    /** All box ids in id order, after {@code afterId} (null = start), at most {@code limit} (full-table scan page). */
    List<byte[]> boxIdsFrom(byte[] afterId, int limit);

    /**
     * Visits every live box — the state-snapshot export path. Optional: stores that never
     * serve snapshots may leave the unsupported default.
     */
    default void forEachBox(java.util.function.Consumer<Box> consumer) {
        throw new UnsupportedOperationException("this box store does not support enumeration");
    }

    /** One box change in a block: write {@code box}, or delete {@code id} when {@code box} is null. */
    record BoxMutation(byte[] id, Box box) {
        public static BoxMutation write(Box box) {
            return new BoxMutation(box.id(), box);
        }

        public static BoxMutation delete(byte[] id) {
            return new BoxMutation(id, null);
        }
    }
}
