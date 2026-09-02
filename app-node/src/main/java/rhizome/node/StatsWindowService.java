package rhizome.node;

import java.util.function.LongFunction;
import java.util.function.LongSupplier;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.GenesisBlock;

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

    /** Aggregate over the last stats window: total tx count, the first/last block timestamps,
     *  the tip's parent supply ({@link BlockImpl#SUPPLY_ABSENT} at genesis) — captured here
     *  rather than re-read by the caller on every poll, since it is gated by the same
     *  recompute-on-tip-movement cache — and the tip's own committed supply ({@link
     *  BlockImpl#SUPPLY_ABSENT} on a chain that commits none), which feeds the emission
     *  fragment. Captured in the same loop iteration that already visits the tip, so the
     *  per-tip recompute cost is unchanged. */
    record StatsWindow(long windowStart, long height, long txCount, long firstTs, long lastTs,
                        long parentSupply, long tipSupply, int tipDifficulty,
                        java.util.List<rhizome.core.block.UncleRef> tipUncles) {}

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
            long parentSupply = BlockImpl.SUPPLY_ABSENT;
            long tipSupply = BlockImpl.SUPPLY_ABSENT;
            int tipDifficulty = 0;
            java.util.List<rhizome.core.block.UncleRef> tipUncles = java.util.List.of();
            for (long i = windowStart; i <= h; i++) {
                var b = (BlockImpl) blockAt.apply(i);
                txCount += b.transactions().size();
                if (i == windowStart) {
                    firstTs = b.timestamp();
                }
                if (i == h) {
                    lastTs = b.timestamp();
                    tipSupply = b.supply();
                    tipDifficulty = b.difficulty();
                    tipUncles = b.uncles();
                }
                if (i == h - 1) {
                    parentSupply = b.supply();
                }
            }
            // The window (32 blocks) always covers h - 1 for any h >= 2 in practice, but a
            // window narrower than 2 blocks would not visit it above — fetch it directly rather
            // than assume coverage. Still only once per tip movement, not once per /stats poll.
            if (h > GenesisBlock.GENESIS_ID && windowStart > h - 1) {
                parentSupply = ((BlockImpl) blockAt.apply(h - 1)).supply();
            }
            StatsWindow w = new StatsWindow(windowStart, h, txCount, firstTs, lastTs, parentSupply,
                tipSupply, tipDifficulty, tipUncles);
            cache = w;
            return w;
        }
    }
}
