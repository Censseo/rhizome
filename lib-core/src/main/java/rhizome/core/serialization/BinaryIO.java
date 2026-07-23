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

    /**
     * Writes exactly {@code size} bytes. The source must already be exactly {@code size} long — every
     * consensus field written here is a fixed-width type ({@code SHA256Hash}, {@code PublicAddress},
     * {@code PublicKey}, {@code TransactionSignature}) whose {@code toBytes()} is length-validated at
     * construction. Silently right-padding a short array or truncating a long one would emit a
     * different-but-valid-looking canonical preimage (hence a different hash / PoW) instead of failing,
     * so a wrong-length field is rejected here rather than corrupting the wire form (audit S10).
     */
    public static void putFixed(ByteBuffer buffer, byte[] src, int size) {
        if (src.length != size) {
            throw new IllegalArgumentException("fixed field must be " + size + " bytes, got " + src.length);
        }
        buffer.put(src, 0, size);
    }

    /** Reads exactly {@code size} bytes into a fresh array. */
    public static byte[] getFixed(ByteBuffer buffer, int size) {
        byte[] out = new byte[size];
        buffer.get(out);
        return out;
    }
}
