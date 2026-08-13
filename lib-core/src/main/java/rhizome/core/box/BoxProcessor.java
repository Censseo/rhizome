package rhizome.core.box;

import java.util.List;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.TransactionKind;

/**
 * Runs box transactions for the {@link rhizome.core.blockchain.Executor}, without
 * touching the ledger (the executor applies the fee and value movements via its own
 * rolled-back ledger ops). Box state writes are staged in a per-block session:
 * {@link rhizome.core.blockchain.BlockStateProcessor#begin()} opens it,
 * {@link #run} validates and accumulates each box op, and the executor ends the
 * block with exactly one of {@link rhizome.core.blockchain.BlockStateProcessor#commit(long)}
 * (accepted) or {@link rhizome.core.blockchain.BlockStateProcessor#discard()} (rejected),
 * so box state moves atomically with the block.
 *
 * <p>The session lifecycle — begin, commit, discard, revert, prune — is declared once, on
 * {@link rhizome.core.blockchain.BlockStateProcessor}; this interface adds only the box
 * domain's own surface. Consensus depends only on these interfaces, never on the
 * persistence layer.
 */
public interface BoxProcessor extends rhizome.core.blockchain.BlockStateProcessor {

    /**
     * The box domain on a node with no box store wired. Replaces a null field; a box transaction
     * is still rejected, by {@code available()} rather than by a null test.
     */
    BoxProcessor NONE = new AbsentBoxProcessor();

    /**
     * Validates and applies one box transaction against the open session. Must not
     * mutate the ledger. Returns a {@link BoxResult} whose status is {@code SUCCESS}
     * or the box failure code; the ledger deltas it carries tell the executor what to
     * move. A failed box op does NOT invalidate the block: the executor treats a
     * precondition failure like an Ethereum-style soft revert — the op's state writes
     * are dropped, its fee still moves to the miner, a zero-delta receipt is recorded
     * (so {@link #receipts} stays aligned with the block's box txs for
     * {@link #revertBlock}'s per-receipt reversal walk), and the block stays valid.
     * See {@code Executor.applyBox} and {@code DefaultBoxProcessor}'s javadoc.
     */
    BoxResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                  long amount, long nonce, byte[] data, long height);

    /** Per-box-transaction receipts for {@code blockHeight}, in block order (reorg ledger reversal). */
    List<BoxReceipt> receipts(long blockHeight);

    /** Box lifecycle events emitted by {@code blockHeight}'s transactions (for the agent event feed). */
    default List<BoxEvent> events(long blockHeight) {
        return List.of();
    }

    /** Box mutations committed by {@code blockHeight}, for the authenticated state root. */
    default List<BoxStore.BoxMutation> changes(long blockHeight) {
        return List.of();
    }

    /**
     * The mutable holder of the miner-votable box parameters ({@code storageFeeFactor},
     * {@code minValuePerByte}) this processor reads at execution time, or {@code null} if
     * voting is not wired. The engine updates it at each voting-epoch boundary.
     */
    default rhizome.core.blockchain.VoteableParams voteableParams() {
        return null;
    }

    /** Session-aware read (sees boxes written earlier in the open block): for the VM's {@code box_read}. */
    Box get(byte[] boxId);

    /** Committed-state read (ignores any open session): for concurrent API reads. */
    Box getCommitted(byte[] boxId);

    /** Rent-collectable box ids at {@code height} (lowest expiry first), for the block producer. */
    List<byte[]> collectableBoxIds(long height, int limit);

    /** Box ids owned by {@code owner}, paginated after {@code afterId} (null = start). */
    List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit);

    /**
     * Evaluates {@code predicate} against committed boxes: examines at most {@code window}
     * boxes from {@code afterId} (using the owner index when the predicate is owner-anchored,
     * else a full-table page), returning up to {@code limit} matches and a cursor to
     * continue (null when the scan reached the end).
     */
    ScanPage scan(ScanPredicate predicate, byte[] afterId, int limit, int window);

    /** One page of a scan: the matched boxes and the cursor to resume from (null = done). */
    record ScanPage(List<Box> matches, byte[] nextCursor) {}

    /**
     * Outcome of one box op. {@code debitFrom} is withdrawn from the sender (into the
     * box) on top of the fee; {@code creditFrom} is deposited to the sender (out of the
     * box); {@code rentToMiner} is the accrued storage rent deducted from the box's locked
     * value and paid to the block miner (audit M7). {@code boxId} identifies the affected box.
     */
    record BoxResult(ExecutionStatus status, long debitFrom, long creditFrom, long rentToMiner,
                     byte[] boxId) {
        public boolean success() {
            return status == ExecutionStatus.SUCCESS;
        }

        public static BoxResult fail(ExecutionStatus status) {
            return new BoxResult(status, 0, 0, 0, null);
        }
    }

    /** Ledger deltas of one applied box op, kept for exact reorg reversal. */
    record BoxReceipt(TransactionKind kind, long debitFrom, long creditFrom, long rentToMiner) {}

    /** A box lifecycle event: the box's owner, an event type, and the box id. */
    record BoxEvent(PublicAddress owner, String type, byte[] boxId) {}
}
