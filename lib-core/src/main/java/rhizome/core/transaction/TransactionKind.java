package rhizome.core.transaction;

/**
 * What a transaction does. TRANSFER is the classic value move (and the default,
 * so existing transactions are unchanged); DEPLOY installs contract code at a
 * derived address; CALL invokes a deployed contract. Contract kinds carry a
 * variable-length {@code data} payload plus a gas budget.
 */
public enum TransactionKind {
    TRANSFER,
    DEPLOY,
    CALL;

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

    public boolean isContract() {
        return this != TRANSFER;
    }
}
