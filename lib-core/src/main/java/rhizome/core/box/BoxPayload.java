package rhizome.core.box;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.transaction.TransactionKind;

/**
 * Codec for the {@code data} payload of a box transaction. The layout depends on
 * the kind:
 * <ul>
 *   <li>{@code BOX_CREATE}: {@code regCount(1) || regs...}</li>
 *   <li>{@code BOX_UPDATE}: {@code boxId(32) || regCount(1) || regs...}</li>
 *   <li>{@code BOX_SPEND} / {@code BOX_COLLECT}: {@code boxId(32)}</li>
 * </ul>
 * A register is {@code typeTag(1) || len(2, BE unsigned) || payload(len)}, matching
 * the box's own register encoding.
 *
 * <p>{@link #decode} is strict: it rejects unknown tags, malformed register
 * payloads, more than {@code maxRegisters} registers, and any trailing bytes — so a
 * structurally invalid payload never reaches state application.
 */
public final class BoxPayload {

    private final byte[] boxId;              // null for BOX_CREATE
    private final List<BoxRegister> registers; // empty for SPEND/COLLECT

    private BoxPayload(byte[] boxId, List<BoxRegister> registers) {
        this.boxId = boxId;
        this.registers = registers;
    }

    /** The target box id (32 bytes) for UPDATE/SPEND/COLLECT; {@code null} for CREATE. */
    public byte[] boxId() {
        return boxId == null ? null : boxId.clone();
    }

    public List<BoxRegister> registers() {
        return registers;
    }

    // ---- encoding (wallet / tests) ----

    public static byte[] encodeCreate(List<BoxRegister> registers) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + registersSize(registers));
        putRegisters(buffer, registers);
        return buffer.array();
    }

    public static byte[] encodeUpdate(byte[] boxId, List<BoxRegister> registers) {
        ByteBuffer buffer = ByteBuffer.allocate(32 + 1 + registersSize(registers));
        buffer.put(boxId);
        putRegisters(buffer, registers);
        return buffer.array();
    }

    public static byte[] encodeTarget(byte[] boxId) {
        return boxId.clone();
    }

    private static int registersSize(List<BoxRegister> registers) {
        int size = 0;
        for (BoxRegister r : registers) {
            size += r.serializedSize();
        }
        return size;
    }

    private static void putRegisters(ByteBuffer buffer, List<BoxRegister> registers) {
        buffer.put((byte) registers.size());
        for (BoxRegister r : registers) {
            buffer.put(r.type().code());
            buffer.putShort((short) r.payload().length);
            buffer.put(r.payload());
        }
    }

    // ---- decoding (executor / processor) ----

    /**
     * Parses the payload for {@code kind}, enforcing the structural rules. Throws
     * {@link IllegalArgumentException} on any malformation (the caller maps that to
     * {@code BOX_PAYLOAD_INVALID}).
     */
    public static BoxPayload decode(TransactionKind kind, byte[] data, int maxRegisters) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        try {
            byte[] boxId = null;
            List<BoxRegister> registers = List.of();
            switch (kind) {
                case BOX_CREATE -> registers = readRegisters(buffer, maxRegisters);
                case BOX_UPDATE -> {
                    boxId = readId(buffer);
                    registers = readRegisters(buffer, maxRegisters);
                }
                case BOX_SPEND, BOX_COLLECT -> boxId = readId(buffer);
                default -> throw new IllegalArgumentException("not a box kind: " + kind);
            }
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("trailing bytes in box payload");
            }
            return new BoxPayload(boxId, registers);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated box payload", e);
        }
    }

    private static byte[] readId(ByteBuffer buffer) {
        byte[] id = new byte[32];
        buffer.get(id);
        return id;
    }

    private static List<BoxRegister> readRegisters(ByteBuffer buffer, int maxRegisters) {
        int count = buffer.get() & 0xFF;
        if (count > maxRegisters) {
            throw new IllegalArgumentException("too many registers: " + count);
        }
        List<BoxRegister> registers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BoxRegisterType type = BoxRegisterType.fromCode(buffer.get());
            int len = buffer.getShort() & 0xFFFF;
            byte[] payload = new byte[len];
            buffer.get(payload);
            if (type == null) {
                throw new IllegalArgumentException("unknown box register tag");
            }
            BoxRegister register = new BoxRegister(type, payload);
            if (!register.validate()) {
                throw new IllegalArgumentException("malformed register payload for " + type);
            }
            registers.add(register);
        }
        return registers;
    }
}
