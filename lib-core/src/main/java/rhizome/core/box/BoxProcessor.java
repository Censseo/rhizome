package rhizome.core.box;

import java.util.List;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.TransactionKind;

/**
 * Runs box transactions for the {@link rhizome.core.blockchain.Executor}, without
 * touching the ledger (the executor applies the fee and value movements via its own
 * rolled-back ledger ops). Box state writes are staged in a per-block session:
 * {@link #begin()} opens it, {@link #run} validates and accumulates each box op, and
 * the executor ends the block with exactly one of {@link #commit(long)} (accepted)
 * or {@link #discard()} (rejected), so box state moves atomically with the block.
 *
 * <p>Mirrors {@link rhizome.core.blockchain.ContractProcessor}; consensus depends
 * only on this interface, never on the persistence layer.
 */
public interface BoxProcessor {

    /** Opens a fresh box session for one block. */
    void begin();

    /**
     * Validates and applies one box transaction against the open session. Must not
     * mutate the ledger. Returns a {@link BoxResult} whose status is {@code SUCCESS}
     * or the box failure code; the ledger deltas it carries tell the executor what to
     * move. Unlike a contract revert, a failed box op invalidates the block — box
     * ops are fully verifiable, so a failure is a malformed/illegal block.
     */
    BoxResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                  long amount, long nonce, byte[] data, long height);

    /** Persists the session and records an undo journal for {@code blockHeight}. */
    void commit(long blockHeight);

    /** Drops the session (block rejected), committing nothing. */
    void discard();

    /** Undoes the box-state changes committed for {@code blockHeight} (reorg). */
    void revertBlock(long blockHeight);

    /** Per-box-transaction receipts for {@code blockHeight}, in block order (reorg ledger reversal). */
    List<BoxReceipt> receipts(long blockHeight);

    /** Box lifecycle events emitted by {@code blockHeight}'s transactions (for the agent event feed). */
    default List<BoxEvent> events(long blockHeight) {
        return List.of();
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
     * Outcome of one box op. {@code debitFrom} is withdrawn from the sender (into the
     * box) on top of the fee; {@code creditFrom} is deposited to the sender (out of the
     * box). {@code boxId} identifies the affected box.
     */
    record BoxResult(ExecutionStatus status, long debitFrom, long creditFrom, byte[] boxId) {
        public boolean success() {
            return status == ExecutionStatus.SUCCESS;
        }

        public static BoxResult fail(ExecutionStatus status) {
            return new BoxResult(status, 0, 0, null);
        }
    }

    /** Ledger deltas of one applied box op, kept for exact reorg reversal. */
    record BoxReceipt(TransactionKind kind, long debitFrom, long creditFrom) {}

    /** A box lifecycle event: the box's owner, an event type, and the box id. */
    record BoxEvent(PublicAddress owner, String type, byte[] boxId) {}
}
