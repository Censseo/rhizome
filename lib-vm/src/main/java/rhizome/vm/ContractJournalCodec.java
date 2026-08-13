package rhizome.vm;

import java.util.ArrayList;
import java.util.List;

import rhizome.core.ledger.PublicAddress;

/**
 * The wire codec for the persisted contract undo journal: {@link ContractUndo} entries to and
 * from the serialized form the stores keep per height.
 *
 * <p>This is the codec the block-commit path encodes with and — since a store must be able to
 * revert a block it persisted without help from the caller that applied it — the codec every
 * {@link ContractStore} decodes with on revert. It lives here rather than in the processor so
 * the store does not depend on the processor's internals, and rather than in the durable store
 * so the in-memory store's default {@code revertBlock} decodes the same bytes.
 *
 * <p>Layout: {@code count(4)} then per entry: {@code isCode(1) | contract(25) | keyLen(4,
 * -1=null) | key | priorLen(4, -1=null) | prior}.
 */
public final class ContractJournalCodec {

    private ContractJournalCodec() {}

    /** Smallest possible serialized journal entry: isCode(1) + address(25) + keyLen(4) + priorLen(4). */
    public static final int MIN_JOURNAL_RECORD_BYTES = 1 + PublicAddress.SIZE
        + Integer.BYTES + Integer.BYTES;

    public static byte[] encode(List<ContractUndo> journal) {
        int size = Integer.BYTES;
        for (ContractUndo u : journal) {
            size += 1 + PublicAddress.SIZE + Integer.BYTES
                + (u.key() == null ? 0 : u.key().length) + Integer.BYTES
                + (u.prior() == null ? 0 : u.prior().length);
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(size);
        b.putInt(journal.size());
        for (ContractUndo u : journal) {
            b.put((byte) (u.isCode() ? 1 : 0));
            b.put(u.contract().toBytes());
            putNullable(b, u.key());
            putNullable(b, u.prior());
        }
        return b.array();
    }

    public static List<ContractUndo> decode(byte[] bytes) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes);
        int count = b.getInt();
        // Defense-in-depth on a self-written blob: bound count/length before allocating so a corrupt
        // or truncated journal throws a clean error instead of new ArrayList<>(negative) or
        // new byte[huge]. The count is bounded by the SMALLEST possible record (isCode 1 + address
        // 25 + keyLen 4 + priorLen 4), not by the raw remaining byte count.
        if (count < 0 || count > b.remaining() / MIN_JOURNAL_RECORD_BYTES) {
            throw new IllegalStateException("contract journal count out of range: " + count);
        }
        List<ContractUndo> journal = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean isCode = b.get() != 0;
            PublicAddress contract = PublicAddress.of(read(b, PublicAddress.SIZE));
            // keyLen is always in the stream (null encodes as -1) — a code entry's null key is
            // still a written -1, so the reader must consume it unconditionally.
            byte[] key = readNullable(b);
            byte[] prior = readNullable(b);
            journal.add(new ContractUndo(isCode, contract, key, prior));
        }
        return journal;
    }

    /**
     * The journal turned back into restore mutations, in final application order — the caller
     * applies its journal in reverse, so that repeated writes to the same key restore the
     * earliest prior.
     */
    public static List<StorageChange> restores(byte[] journal) {
        List<ContractUndo> undo = decode(journal);
        List<StorageChange> restores = new ArrayList<>(undo.size());
        for (int i = undo.size() - 1; i >= 0; i--) {
            ContractUndo u = undo.get(i);
            if (u.isCode()) {
                restores.add(u.prior() == null
                    ? StorageChange.deleteCode(u.contract())
                    : StorageChange.putCode(u.contract(), u.prior()));
            } else {
                restores.add(u.prior() == null
                    ? StorageChange.deleteStorage(u.contract(), u.key())
                    : StorageChange.putStorage(u.contract(), u.key(), u.prior()));
            }
        }
        return restores;
    }

    private static void putNullable(java.nio.ByteBuffer b, byte[] value) {
        if (value == null) {
            b.putInt(-1);
        } else {
            b.putInt(value.length);
            b.put(value);
        }
    }

    private static byte[] readNullable(java.nio.ByteBuffer b) {
        int len = b.getInt();
        return len < 0 ? null : read(b, len);
    }

    private static byte[] read(java.nio.ByteBuffer b, int len) {
        if (len < 0 || len > b.remaining()) {
            throw new IllegalStateException("contract journal length out of range: " + len);
        }
        byte[] out = new byte[len];
        b.get(out);
        return out;
    }
}
