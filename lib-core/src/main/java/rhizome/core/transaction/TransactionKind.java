package rhizome.core.transaction;

/**
 * What a transaction does. TRANSFER is the classic value move (and the default,
 * so existing transactions are unchanged); DEPLOY installs contract code at a
 * derived address; CALL invokes a deployed contract; the BOX_* kinds operate on
 * {@link rhizome.core.box.Box data boxes}.
 *
 * <p>All non-TRANSFER kinds carry the variable-length {@code data} payload on the
 * wire (and in the signed preimage) — that is {@link #hasPayload()}. Only DEPLOY
 * and CALL actually run the WASM VM — that is {@link #isContract()}; the box kinds
 * are deterministic protocol operations with no VM and no gas (their
 * {@code gasLimit}/{@code gasPrice} must be zero).
 *
 * <p>The wire codes are explicit constructor arguments, not {@code ordinal()}s,
 * because the byte is consensus-visible (signed preimage, {@code TransactionDto}
 * wire, persisted box receipts): reordering or inserting a constant must not
 * silently reinterpret chain data. {@code TransactionKindWireTest} pins the
 * vectors as literals. The same logic that keeps {@code SignatureScheme}'s codes
 * explicit applies here.
 */
public enum TransactionKind {
    TRANSFER((byte) 0),
    DEPLOY((byte) 1),
    CALL((byte) 2),
    BOX_CREATE((byte) 3),
    BOX_UPDATE((byte) 4),
    BOX_SPEND((byte) 5),
    BOX_COLLECT((byte) 6),
    TOKEN_MINT((byte) 7),
    TOKEN_TRANSFER((byte) 8),
    TOKEN_BURN((byte) 9);

    private final byte code;

    TransactionKind(byte code) {
        this.code = code;
    }

    /** Consensus-visible discriminant: signed preimage byte, wire prefix, receipt code. */
    public byte code() {
        return code;
    }

    /** Dense lookup over the implemented range; unknown codes stay null and are rejected. */
    private static final TransactionKind[] BY_CODE = byCode();

    private static TransactionKind[] byCode() {
        int max = 0;
        for (TransactionKind kind : values()) {
            max = Math.max(max, kind.code & 0xFF);
        }
        TransactionKind[] table = new TransactionKind[max + 1];
        for (TransactionKind kind : values()) {
            table[kind.code & 0xFF] = kind;
        }
        return table;
    }

    public static TransactionKind fromCode(byte code) {
        int i = code & 0xFF;
        if (i >= BY_CODE.length || BY_CODE[i] == null) {
            throw new IllegalArgumentException("unknown transaction kind: " + i);
        }
        return BY_CODE[i];
    }

    /** DEPLOY/CALL — runs the WASM VM and is routed through the contract processor. */
    public boolean isContract() {
        return this == DEPLOY || this == CALL;
    }

    /** BOX_CREATE/UPDATE/SPEND/COLLECT — routed through the box processor. */
    public boolean isBox() {
        return this == BOX_CREATE || this == BOX_UPDATE || this == BOX_SPEND || this == BOX_COLLECT;
    }

    /** TOKEN_MINT/TRANSFER/BURN — routed through the token processor. */
    public boolean isToken() {
        return this == TOKEN_MINT || this == TOKEN_TRANSFER || this == TOKEN_BURN;
    }

    /**
     * Whether this kind serializes the {@code kind || gasLimit || gasPrice ||
     * dataLen || data} suffix (everything except a plain transfer). The box kinds
     * reuse the contract suffix byte-for-byte, with gas fields pinned to zero.
     */
    public boolean hasPayload() {
        return this != TRANSFER;
    }
}
