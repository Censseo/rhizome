package rhizome.vm;

/**
 * A node-local infrastructure failure surfaced underneath a contract execution — a
 * contract-store, box-store or ledger read failing inside a host function — as opposed
 * to a contract-controlled outcome (trap, revert, out-of-gas).
 *
 * <p>This is an {@link Error}, never a {@link RuntimeException}, so no verdict-converting
 * {@code catch (RuntimeException)} or {@code catch (Throwable)} in the VM/processor stack
 * may swallow it into a deterministic-looking revert: a node with a transient store fault
 * must fail the block loudly (the executor's fatal catch-all rethrows it) rather than
 * revert a call healthy nodes execute — which would diverge {@code gasUsed} and the state
 * root, i.e. fork consensus. A crash is preferable to a fork.
 */
public final class HostFault extends Error {

    public HostFault(String message, Throwable cause) {
        super(message, cause);
    }

    /** The nearest {@link HostFault} in {@code e}'s cause chain, or {@code null}. */
    public static HostFault of(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HostFault f) {
                return f;
            }
        }
        return null;
    }

    /** Wraps {@code t} as a {@link HostFault}, unless it already is one. */
    public static HostFault wrap(String message, Throwable t) {
        HostFault existing = of(t);
        return existing != null ? existing : new HostFault(message, t);
    }
}
