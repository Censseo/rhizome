package rhizome.core.serialization;

import java.nio.ByteBuffer;

/**
 * Fixed-width, big-endian byte helpers for the hand-written canonical codec of
 * the core objects (block header, transaction).
 *
 * <p>Deliberately manual: the core types have a fixed layout, so a
 * {@link ByteBuffer} written field by field is both the fastest option and the
 * only one safe for consensus (no schema drift between library versions), and it
 * carries no runtime-codegen dependency — which keeps GraalVM native-image
 * viable. Multi-byte integers are big-endian, matching the hash preimages.
 */
public final class BinaryIO {

    private BinaryIO() {}

    /** Writes exactly {@code size} bytes: right-pads with zeros if shorter, truncates if longer. */
    public static void putFixed(ByteBuffer buffer, byte[] src, int size) {
        if (src.length >= size) {
            buffer.put(src, 0, size);
        } else {
            buffer.put(src);
            buffer.put(new byte[size - src.length]);
        }
    }

    /** Reads exactly {@code size} bytes into a fresh array. */
    public static byte[] getFixed(ByteBuffer buffer, int size) {
        byte[] out = new byte[size];
        buffer.get(out);
        return out;
    }
}
