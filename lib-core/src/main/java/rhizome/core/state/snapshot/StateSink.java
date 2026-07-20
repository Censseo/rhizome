package rhizome.core.state.snapshot;

/**
 * Write side of a snapshot import: receives every verified raw {@code (key, value)} binding
 * so an adapter can seed the node's concrete stores (ledger balances, account nonces, boxes
 * with their secondary indexes, token metadata and balances, contract code and storage).
 * Called only after the reconstructed root has matched the committed one.
 */
@FunctionalInterface
public interface StateSink {

    /** Seeds one binding of {@code domain} (a {@link rhizome.core.state.StateKeys} constant). */
    void put(byte domain, byte[] key, byte[] value);
}
