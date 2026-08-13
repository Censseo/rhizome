package rhizome.core.state;

/**
 * Cadence of the amortized durable interval prune, shared by every store-backed domain
 * processor.
 *
 * <p>The per-height point deletes keep the RAM maps and their durable rows at exactly the
 * retention depth on every appended block; the interval deleteRange is only the backstop for
 * rows committed before a restart (the RAM maps are empty then, so nothing points the
 * per-height deletes at them). Running it every block paid ~2 synced range-tombstone fsyncs
 * per block for rows that rarely exist (audit perf), so it is paid every {@link #INTERVAL}
 * blocks instead. The reorg window is unaffected: per-height deletes cover it exactly, and
 * the interval prune only ever lags them — never leads them.
 *
 * <p>Callers invoke {@link #due} under the engine lock; the state is a single long and is
 * never shared across threads.
 */
public final class PruneCadence {

    /** Blocks between amortized durable interval prunes. */
    public static final long INTERVAL = 32;

    private long lastCutoff;

    /**
     * Whether the interval prune is due now: {@code cutoff} is positive (something is prunable)
     * and at least {@link #INTERVAL} heights past the last accepted one. Accepting advances the
     * watermark, so the prune runs exactly once per {@link #INTERVAL} heights as the chain grows.
     */
    public boolean due(long cutoff) {
        if (cutoff <= 0 || cutoff - lastCutoff < INTERVAL) {
            return false;
        }
        lastCutoff = cutoff;
        return true;
    }
}
