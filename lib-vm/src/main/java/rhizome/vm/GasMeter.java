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
    private long logBytes;

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

    /** Saturates the meter: {@code used} becomes the limit, with no exception (the caller reports it). */
    public void exhaust() {
        used = limit;
    }

    /**
     * Adds {@code n} to the bytes of event data this execution (the whole call tree shares the
     * meter) has emitted, and returns the running total — discarded sub-frames included, so the
     * count is a pure function of the instruction stream.
     */
    long addLogBytes(long n) {
        logBytes += n;
        return logBytes;
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
