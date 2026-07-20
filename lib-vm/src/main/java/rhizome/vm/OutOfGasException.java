package rhizome.vm;

/** Thrown when a contract execution exceeds its gas budget. Deterministic: it is
 *  raised from the per-instruction meter, so every node aborts at the same step. */
public final class OutOfGasException extends RuntimeException {
    public OutOfGasException(String message) {
        super(message);
    }
}
