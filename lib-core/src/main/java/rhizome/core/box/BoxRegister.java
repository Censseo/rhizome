package rhizome.core.box;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One typed data cell of a {@link Box}: a {@link BoxRegisterType} tag plus the raw
 * payload bytes. The payload is validated structurally against the tag
 * ({@link #validate()}) — fixed-length tags must match exactly, {@code STRING}
 * must be valid UTF-8 — but the protocol attaches no meaning to the value.
 */
public record BoxRegister(BoxRegisterType type, byte[] payload) {

    public BoxRegister {
        if (type == null) {
            throw new IllegalArgumentException("register type is null");
        }
        payload = payload == null ? new byte[0] : payload.clone();
    }

    /** A register holding a signed 64-bit integer. */
    public static BoxRegister i64(long value) {
        return new BoxRegister(BoxRegisterType.I64, rhizome.core.common.Utils.longToBytes(value));
    }

    /** A register holding a boolean. */
    public static BoxRegister bool(boolean value) {
        return new BoxRegister(BoxRegisterType.BOOL, new byte[] {(byte) (value ? 1 : 0)});
    }

    /** A register holding UTF-8 text. */
    public static BoxRegister string(String value) {
        return new BoxRegister(BoxRegisterType.STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    /** A register holding a free-form byte blob. */
    public static BoxRegister bytes(byte[] value) {
        return new BoxRegister(BoxRegisterType.BYTES, value);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * Whether this register's payload is structurally valid for its tag. A
     * fixed-length type must match its length exactly; {@code BOOL} must be
     * {@code 0x00}/{@code 0x01}; {@code STRING} must be valid UTF-8.
     */
    public boolean validate() {
        if (!type.isVariableLength() && payload.length != type.fixedLength()) {
            return false;
        }
        return switch (type) {
            case BOOL -> payload[0] == 0 || payload[0] == 1;
            case STRING -> isValidUtf8(payload);
            default -> true;
        };
    }

    /** Serialized size of this register: tag(1) + len(2) + payload. */
    public int serializedSize() {
        return 1 + 2 + payload.length;
    }

    private static boolean isValidUtf8(byte[] bytes) {
        // Round-trip: decode replacing malformed input, re-encode, compare. Cheap and
        // dependency-free; a payload with any invalid sequence fails to reproduce itself.
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        return Arrays.equals(decoded.getBytes(StandardCharsets.UTF_8), bytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof BoxRegister other
            && type == other.type
            && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "BoxRegister[" + type + ", " + payload.length + "B]";
    }
}
