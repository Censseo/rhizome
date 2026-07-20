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
 */
public enum TransactionKind {
    TRANSFER,      // 0
    DEPLOY,        // 1
    CALL,          // 2
    BOX_CREATE,    // 3
    BOX_UPDATE,    // 4
    BOX_SPEND,     // 5
    BOX_COLLECT;   // 6

    private static final TransactionKind[] VALUES = values();

    public byte code() {
        return (byte) ordinal();
    }

    public static TransactionKind fromCode(byte code) {
        int i = code & 0xFF;
        if (i < 0 || i >= VALUES.length) {
            throw new IllegalArgumentException("unknown transaction kind: " + i);
        }
        return VALUES[i];
    }

    /** DEPLOY/CALL — runs the WASM VM and is routed through the contract processor. */
    public boolean isContract() {
        return this == DEPLOY || this == CALL;
    }

    /** BOX_CREATE/UPDATE/SPEND/COLLECT — routed through the box processor. */
    public boolean isBox() {
        return ordinal() >= BOX_CREATE.ordinal();
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
