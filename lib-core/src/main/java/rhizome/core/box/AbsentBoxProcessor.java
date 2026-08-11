package rhizome.core.box;

import java.util.List;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

/**
 * The box domain on a node that has no box store wired: {@link BoxProcessor#NONE}.
 *
 * <p>Stands in for a null processor so the engine carries no null checks. Absent is not
 * permissive — {@code Executor}'s first pass still rejects a box transaction with
 * {@code BOX_UNAVAILABLE} by testing {@link #available()}. Every read returns exactly what the
 * null guard it replaces returned, so a disabled node answers its box API the same way it did.
 *
 * <p>Stateless by construction, so the singleton is GraalVM build-time-init safe.
 */
final class AbsentBoxProcessor implements BoxProcessor {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void begin() {
        // no session to open
    }

    @Override
    public void commit(long blockHeight) {
        // no state to persist
    }

    @Override
    public void discard() {
        // no session to drop
    }

    @Override
    public void revertBlock(long blockHeight) {
        // nothing was ever committed for this height
    }

    @Override
    public BoxResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                         long amount, long nonce, byte[] data, long height) {
        throw new IllegalStateException(
            "no box processor is wired: a box transaction must have been rejected with "
            + "BOX_UNAVAILABLE in Executor's first pass and cannot reach the second");
    }

    @Override
    public List<BoxReceipt> receipts(long blockHeight) {
        return List.of();
    }

    @Override
    public Box get(byte[] boxId) {
        return null;
    }

    @Override
    public Box getCommitted(byte[] boxId) {
        return null;
    }

    @Override
    public List<byte[]> collectableBoxIds(long height, int limit) {
        return List.of();
    }

    @Override
    public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
        return List.of();
    }

    @Override
    public ScanPage scan(ScanPredicate predicate, byte[] afterId, int limit, int window) {
        return new ScanPage(List.of(), null);
    }
}
