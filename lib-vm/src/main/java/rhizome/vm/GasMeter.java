package rhizome.vm;

/**
 * A gas budget for one contract execution. Charged per WASM instruction (via the
 * VM's execution listener) and per host call, so compute and I/O both cost gas
 * and an infinite loop is bounded deterministically rather than hanging the node.
 *
 * <p>Not thread-safe: a meter belongs to a single execution.
 */
public final class GasMeter {

    private final long limit;
    private long used;

    public GasMeter(long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("gas limit must be non-negative");
        }
        this.limit = limit;
    }

    /** Deducts {@code amount} gas; throws {@link OutOfGasException} if the budget is exceeded. */
    public void charge(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("gas charge must be non-negative");
        }
        // Saturating add so a huge charge cannot wrap past the limit unnoticed.
        long next = used + amount;
        if (next < used || next > limit) {
            used = limit;
            throw new OutOfGasException("out of gas (limit " + limit + ")");
        }
        used = next;
    }

    public long used() {
        return used;
    }

    public long remaining() {
        return limit - used;
    }

    public long limit() {
        return limit;
    }
}
