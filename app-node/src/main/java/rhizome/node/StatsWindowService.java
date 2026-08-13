package rhizome.node;

import java.util.function.LongFunction;
import java.util.function.LongSupplier;

import rhizome.core.block.Block;

/**
 * The cached {@code GET /stats} window aggregate, extracted from NodeService (archi-review lot
 * L22, constat 18): the double-checked cache and its recompute lock own the invalidation
 * policy. The dashboard polls stats on a timer, but the window only changes when the tip
 * advances, so it is recomputed — and the window's blocks re-decoded under the read path — only
 * when the injected {@code height} moved since the last call (audit optimization). Live scalars
 * (mempool, peers, difficulty) are cheap and stay uncached in the handler.
 *
 * <p>The accessors are injected so this class stays free of the engine: the service passes
 * {@code engine::height} and its own block lookup, and tests can drive the cache with a stub.
 */
final class StatsWindowService {

    /** Aggregate over the last stats window: total tx count and the first/last block timestamps. */
    record StatsWindow(long windowStart, long height, long txCount, long firstTs, long lastTs) {}

    private final LongSupplier height;
    private final LongFunction<Block> blockAt;

    private volatile StatsWindow cache;
    /** Serializes recomputes: without it, N concurrent callers on a stale cache all re-decode
     *  the same window under the read path (duplicate lock-guarded work). */
    private final Object lock = new Object();

    StatsWindowService(LongSupplier height, LongFunction<Block> blockAt) {
        this.height = height;
        this.blockAt = blockAt;
    }

    StatsWindow statsWindow(int window) {
        long h = height.getAsLong();
        StatsWindow cached = cache;
        if (cached != null && cached.height() == h) {
            return cached;
        }
        synchronized (lock) {
            // Re-check inside the lock: a concurrent caller may already have recomputed this
            // height — only one thread re-decodes the window per tip movement.
            cached = cache;
            if (cached != null && cached.height() == h) {
                return cached;
            }
            long windowStart = Math.max(1, h - window + 1);
            long txCount = 0;
            long firstTs = 0;
            long lastTs = 0;
            for (long i = windowStart; i <= h; i++) {
                var b = (rhizome.core.block.BlockImpl) blockAt.apply(i);
                txCount += b.transactions().size();
                if (i == windowStart) {
                    firstTs = b.timestamp();
                }
                if (i == h) {
                    lastTs = b.timestamp();
                }
            }
            StatsWindow w = new StatsWindow(windowStart, h, txCount, firstTs, lastTs);
            cache = w;
            return w;
        }
    }
}
