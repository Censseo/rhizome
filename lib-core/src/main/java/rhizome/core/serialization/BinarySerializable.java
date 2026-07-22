package rhizome.core.serialization;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.NotNull;

import rhizome.core.block.dto.BlockDto;
import rhizome.core.transaction.dto.TransactionDto;

/**
 * Fixed-layout binary form for the core wire/storage objects.
 *
 * <p>Encoding is hand-written per type ({@link #writeTo}/{@code readFrom}) rather
 * than delegated to a reflective/codegen serializer: the core types have a fixed
 * shape, so a direct {@link ByteBuffer} write is the fastest option, is
 * deterministic (no schema drift across library versions), and pulls in no
 * runtime bytecode generation — a prerequisite for GraalVM native-image.
 */
public interface BinarySerializable {

    /** Serialized size in bytes (fixed per type). */
    @NotNull
    int getSize();

    /** Writes this object's fixed-layout encoding at the buffer's current position. */
    void writeTo(ByteBuffer buffer);

    default byte[] toBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(getSize());
        writeTo(buffer);
        return buffer.array();
    }

    /**
     * Strict single-object decode: the whole {@code buffer} must be exactly one {@code T}. Trailing
     * bytes are rejected so a wire object has a unique encoding, matching the P7 strictness of
     * BlockCodec/HeaderCodec.decode (identity is content-hash, so this is wire-hygiene, not a
     * correctness fix — but it closes the last non-strict single-object path, /add_transaction).
     * The offset overload below stays lenient: PeerInterface reads consecutive objects from one
     * multi-object buffer through it, where remaining bytes are the next object, not junk.
     */
    static <T extends BinarySerializable> T fromBuffer(byte[] buffer, Class<T> clazz) {
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        T result = clazz.cast(read(bb, clazz));
        if (bb.hasRemaining()) {
            throw new IllegalArgumentException(
                "trailing bytes after " + clazz.getSimpleName() + " (" + bb.remaining() + " left)");
        }
        return result;
    }

    static <T extends BinarySerializable> T fromBuffer(byte[] buffer, int pos, Class<T> clazz) {
        ByteBuffer bb = ByteBuffer.wrap(buffer, pos, buffer.length - pos);
        return clazz.cast(read(bb, clazz));
    }

    private static BinarySerializable read(ByteBuffer bb, Class<?> clazz) {
        if (clazz == BlockDto.class) {
            return BlockDto.readFrom(bb);
        }
        if (clazz == TransactionDto.class) {
            return TransactionDto.readFrom(bb);
        }
        throw new IllegalArgumentException("No binary codec registered for " + clazz.getName());
    }
}
