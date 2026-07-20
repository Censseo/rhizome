package rhizome.core.state.snapshot;

/**
 * Read side of a snapshot: streams every raw {@code (key, value)} binding of a committed
 * state domain, exactly as the engine folds them into the authenticated state root (same
 * key composition, same value encoding, zero-amount entries omitted). Implemented by an
 * adapter over the node's concrete stores; the exporter only sees this seam, so lib-core
 * stays free of store-backend dependencies.
 */
public interface StateSource {

    @FunctionalInterface
    interface EntryConsumer {
        void accept(byte[] key, byte[] value);
    }

    /** Streams every binding of {@code domain} (a {@link rhizome.core.state.StateKeys} constant). */
    void forEach(byte domain, EntryConsumer out);
}
