package rhizome.core.state.snapshot;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * One bounded slice of a state snapshot: a run of raw {@code (key, value)} bindings from a
 * single committed state domain — exactly the leaves of the authenticated state tree, so an
 * importer can rebuild the tree from chunks alone and check the root against a block header.
 *
 * <p>Wire form (all integers big-endian):
 * <pre>domain(1) ‖ count(4) ‖ [keyLen(2) ‖ key ‖ valLen(4) ‖ val]*</pre>
 *
 * <p>Because the sparse-Merkle root is a function of the <em>set</em> of bindings,
 * independent of insertion order, chunks carry no tree structure and may be imported in any
 * order. Any tampering — a flipped byte, a dropped or duplicated entry — changes the
 * reconstructed root and fails the final equality check.
 */
public record SnapshotChunk(byte domain, List<Entry> entries) {

    /** One raw state binding: {@code key} and {@code value} exactly as committed in the root. */
    public record Entry(byte[] key, byte[] value) {}

    public byte[] encode() {
        int size = 1 + 4;
        for (Entry e : entries) {
            size += 2 + e.key().length + 4 + e.value().length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(domain);
        buf.putInt(entries.size());
        for (Entry e : entries) {
            buf.putShort((short) e.key().length);
            buf.put(e.key());
            buf.putInt(e.value().length);
            buf.put(e.value());
        }
        return buf.array();
    }

    public static SnapshotChunk decode(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte domain = buf.get();
        int count = buf.getInt();
        if (count < 0 || count > bytes.length) {
            throw new IllegalArgumentException("corrupt snapshot chunk: count " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] key = new byte[Short.toUnsignedInt(buf.getShort())];
            buf.get(key);
            int valLen = buf.getInt();
            if (valLen < 0 || valLen > buf.remaining()) {
                throw new IllegalArgumentException("corrupt snapshot chunk: value length " + valLen);
            }
            byte[] value = new byte[valLen];
            buf.get(value);
            entries.add(new Entry(key, value));
        }
        if (buf.hasRemaining()) {
            throw new IllegalArgumentException("corrupt snapshot chunk: trailing bytes");
        }
        return new SnapshotChunk(domain, entries);
    }
}
