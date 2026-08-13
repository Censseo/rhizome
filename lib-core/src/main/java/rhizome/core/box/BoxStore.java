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
     *
     * <p>A height that already carries a NON-EMPTY journal MUST be refused with
     * {@link IllegalStateException}: a double-apply would journal the already-mutated state as
     * the "prior", so a later {@link #revertBlock} would restore the wrong values (audit F10).
     * An apply whose {@code mutations} is empty persists no journal (there is nothing to undo),
     * so it does not itself trigger this refusal on a later call at the same height.
     */
    void applyBlock(long height, List<BoxMutation> mutations);

    /**
     * As {@link #applyBlock(long, List)}, additionally committing the block's encoded receipts in
     * the SAME atomic batch where the store supports it (RocksDB: previously a second synced
     * write per block, audit perf). The default falls back to the separate {@link #putReceipts}
     * write — correct, just not atomic with the mutations.
     */
    default void applyBlock(long height, List<BoxMutation> mutations, byte[] encodedReceipts) {
        applyBlock(height, mutations);
        if (encodedReceipts != null) {
            putReceipts(height, encodedReceipts);
        }
    }

    /**
     * Reverts the box changes committed for {@code height} using the persisted journal, and
     * drops the block's receipts — as one atomic unit where the store supports it. The receipts
     * MUST travel with the restore: deleted separately and first, a crash between the two
     * writes left the journal present but the receipts gone, and the rollback guard then
     * aborted every reorg retry (audit: revert-path tear). A receipts-only block (no journal)
     * must still have its receipts dropped.
     */
    void revertBlock(long height);

    /**
     * Drops journals — AND the receipts of the same heights — for heights strictly below
     * {@code minHeight} (unreachable by any reorg). Receipts ride the same schedule because the
     * processor's per-height receipt deletes only cover heights still in its RAM map, which is
     * empty after a restart; without a store-side interval drop, pre-restart receipts would
     * accumulate forever (audit follow-up).
     */
    void pruneJournals(long minHeight);

    // ---- per-block receipt persistence (audit F7) ----

    /**
     * Persists the encoded per-transaction box receipts committed for {@code height}
     * (see {@code BoxReceiptCodec}). Receipts are what {@code Executor.rollbackBlock}
     * consumes to reverse a block's box-ledger deltas; without durable receipts a
     * restart followed by a reorg of a box-carrying block would reverse against an
     * empty receipt list and corrupt the ledger.
     *
     * <p>The default is a no-op: stores without a receipt column keep the pre-F7
     * RAM-only behaviour. Durable implementations MUST override this together with
     * {@link #getReceipts} and {@link #deleteReceipts}, in the same atomic write as
     * {@link #applyBlock}'s journal where possible.
     */
    default void putReceipts(long height, byte[] encodedReceipts) {
        // no-op default: stores without receipt persistence keep RAM-only receipts
    }

    /** The bytes stored by {@link #putReceipts} for {@code height}, or {@code null} if none. */
    default byte[] getReceipts(long height) {
        return null;
    }

    /** Deletes the receipts stored for {@code height} (block reverted, or pruned past the reorg horizon). */
    default void deleteReceipts(long height) {
        // no-op default; see putReceipts
    }

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
