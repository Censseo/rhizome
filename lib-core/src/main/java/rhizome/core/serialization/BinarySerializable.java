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

    static <T extends BinarySerializable> T fromBuffer(byte[] buffer, Class<T> clazz) {
        return fromBuffer(buffer, 0, clazz);
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
