package rhizome.core.box;

/**
 * The type tag of a {@link BoxRegister}. Registers carry raw bytes plus one of
 * these tags; the protocol validates only the tag and the payload's structural
 * shape (length, UTF-8 for {@link #STRING}), never the value's meaning — tags are
 * annotations for readers (agents, indexers, wallets), not typed VM values.
 *
 * <p>The tag space grows only by version activation, never silently: an unknown
 * tag byte makes the carrying transaction invalid.
 */
public enum BoxRegisterType {

    /** Free-form blob: serialized data, embeddings, anything. Length bounded by the box. */
    BYTES(0, -1),
    /** A signed 64-bit integer: counter, timestamp, price. Exactly 8 bytes. */
    I64(1, 8),
    /** A boolean, one byte {@code 0x00} or {@code 0x01}. */
    BOOL(2, 1),
    /** A 25-byte {@link rhizome.core.ledger.PublicAddress} reference. */
    ADDRESS(3, 25),
    /** A 32-byte hash: content hash of an off-chain blob, or a box id. */
    HASH32(4, 32),
    /** UTF-8 text: name, URL, agent endpoint, document. Length bounded by the box. */
    STRING(5, -1);

    private final byte code;
    /** Exact required payload length, or -1 for variable-length (bounded only by box size). */
    private final int fixedLength;

    BoxRegisterType(int code, int fixedLength) {
        this.code = (byte) code;
        this.fixedLength = fixedLength;
    }

    public byte code() {
        return code;
    }

    public boolean isVariableLength() {
        return fixedLength < 0;
    }

    public int fixedLength() {
        return fixedLength;
    }

    private static final BoxRegisterType[] BY_CODE = buildIndex();

    private static BoxRegisterType[] buildIndex() {
        BoxRegisterType[] values = values();
        int max = 0;
        for (BoxRegisterType t : values) {
            max = Math.max(max, t.code & 0xFF);
        }
        BoxRegisterType[] index = new BoxRegisterType[max + 1];
        for (BoxRegisterType t : values) {
            index[t.code & 0xFF] = t;
        }
        return index;
    }

    /** The type for a tag byte, or {@code null} if the tag is unknown (invalid). */
    public static BoxRegisterType fromCode(byte code) {
        int i = code & 0xFF;
        return i < BY_CODE.length ? BY_CODE[i] : null;
    }
}
