package rhizome.core.ledger;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire codec for the persisted per-block ledger undo journal: {@link LedgerOp} entries to
 * and from the serialized form a durable ledger keeps per height, so a reorg can reverse a block
 * it committed without help from the executor that applied it — the same division as the box,
 * token and contract journals (audit: one undo protocol, and a journal for the ledger).
 *
 * <p>Layout: {@code count(4)} then per entry: {@code op(1: 0=WITHDRAW, 1=DEPOSIT) | wallet(25)
 * | amount(8)}.
 */
public final class LedgerJournalCodec {

    private LedgerJournalCodec() {}

    /** Smallest possible serialized journal entry: op(1) + wallet(25) + amount(8). */
    public static final int MIN_JOURNAL_RECORD_BYTES = 1 + PublicAddress.SIZE + Long.BYTES;

    public static byte[] encode(List<LedgerOp> journal) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(
            Integer.BYTES + journal.size() * MIN_JOURNAL_RECORD_BYTES);
        b.putInt(journal.size());
        for (LedgerOp op : journal) {
            b.put((byte) (op.op() == LedgerOp.Op.WITHDRAW ? 0 : 1));
            b.put(op.wallet().toBytes());
            b.putLong(op.amount());
        }
        return b.array();
    }

    public static List<LedgerOp> decode(byte[] bytes) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes);
        int count = b.getInt();
        // Defense-in-depth on a self-written blob: bound count before allocating so a corrupt or
        // truncated journal throws a clean error instead of new ArrayList<>(negative).
        if (count < 0 || count > b.remaining() / MIN_JOURNAL_RECORD_BYTES) {
            throw new IllegalStateException("ledger journal count out of range: " + count);
        }
        List<LedgerOp> journal = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LedgerOp.Op op = b.get() == 0 ? LedgerOp.Op.WITHDRAW : LedgerOp.Op.DEPOSIT;
            byte[] wallet = new byte[PublicAddress.SIZE];
            b.get(wallet);
            long amount = b.getLong();
            journal.add(new LedgerOp(op, PublicAddress.of(wallet), amount));
        }
        return journal;
    }
}
