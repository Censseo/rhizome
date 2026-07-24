package rhizome.core.box;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.transaction.TransactionKind;

/**
 * Fixed-layout codec for one block's {@link BoxProcessor.BoxReceipt} list, persisted
 * through {@link BoxStore#putReceipts} so a reorg that follows a restart can still
 * reverse the block's box-ledger deltas exactly (audit F7):
 *
 * <pre>
 * count(4) || [kind(1) || debitFrom(8) || creditFrom(8)]*
 * </pre>
 *
 * <p>Big-endian, self-delimiting; a single-object decode must consume the whole
 * buffer (trailing bytes are a non-canonical stored form, as in the block codecs).
 */
public final class BoxReceiptCodec {

    private BoxReceiptCodec() {}

    /** Bytes per receipt record: kind(1) || debitFrom(8) || creditFrom(8). */
    private static final int RECORD_SIZE = 1 + Long.BYTES + Long.BYTES;

    public static byte[] encode(List<BoxProcessor.BoxReceipt> receipts) {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + receipts.size() * RECORD_SIZE);
        buffer.putInt(receipts.size());
        for (BoxProcessor.BoxReceipt r : receipts) {
            buffer.put(r.kind().code());
            buffer.putLong(r.debitFrom());
            buffer.putLong(r.creditFrom());
        }
        return buffer.array();
    }

    public static List<BoxProcessor.BoxReceipt> decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int count = buffer.getInt();
        // Defense-in-depth on a self-written blob (same guard as the box journal codec): bound
        // the count before allocating so a corrupt/truncated value throws a clean error rather
        // than new ArrayList<>(negative) or a huge pre-size.
        if (count < 0 || count > buffer.remaining() / RECORD_SIZE) {
            throw new IllegalStateException("box receipt count out of range: " + count);
        }
        List<BoxProcessor.BoxReceipt> receipts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            TransactionKind kind = TransactionKind.fromCode(buffer.get());
            receipts.add(new BoxProcessor.BoxReceipt(kind, buffer.getLong(), buffer.getLong()));
        }
        if (buffer.hasRemaining()) {
            throw new IllegalStateException("trailing bytes after box receipts: " + buffer.remaining());
        }
        return receipts;
    }
}
