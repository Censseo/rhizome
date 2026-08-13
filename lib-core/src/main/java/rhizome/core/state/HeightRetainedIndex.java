package rhizome.core.state;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

/**
 * Derived state kept per block height for the reorg window, bounded by both a height count and a
 * byte budget.
 *
 * <p>Every reversible state domain needs the same structure — a height-keyed map, a retention
 * depth, a byte cap, oldest-first eviction, and a prune that drops everything the reorg window no
 * longer covers — and all three processors grew their own copy. They diverged in exactly the ways
 * parallel copies do: the contract processor gained byte budgets and an off-by-one fix (audit F10)
 * that the box and token copies never picked up, so two of the three retained one height too many
 * and had no memory bound at all. Sharing the type is what makes the next correction land in one
 * place instead of two out of three.
 *
 * <p>Eviction is RAM-only. Durable copies are deleted solely by {@link #pruneThrough}, so a read
 * path with a store-level fallback still finds an evicted height on disk.
 *
 * <p>Thread-safety: reads run lock-free on the concurrent map; every mutation of the byte counter
 * serialises on this instance, so the counter can never drift from the map's contents.
 */
public final class HeightRetainedIndex<T> {

    /**
     * Height-keyed, so ascending order IS the eviction order and the victim is the map head in
     * O(log n) rather than a scan of the key set.
     */
    private final ConcurrentNavigableMap<Long, List<T>> byHeight = new ConcurrentSkipListMap<>();
    private final int retainDepth;
    private final long byteBudget;
    private final ToLongFunction<List<T>> sizer;
    private long retainedBytes;

    /**
     * @param retainDepth heights to keep behind the chain tip; must be at least the chain's
     *                    maximum reorg depth, since a reorg replays every retained height
     * @param byteBudget  cap on the retained payload, independent of the height count: at a
     *                    production depth of 120 a height-only bound is no bound at all, since one
     *                    height's payload is capped only by the block size limit
     * @param sizer       retained size of one height's value
     */
    public HeightRetainedIndex(int retainDepth, long byteBudget, ToLongFunction<List<T>> sizer) {
        this.retainDepth = retainDepth;
        this.byteBudget = byteBudget;
        this.sizer = sizer;
    }

    /** The value retained for {@code height}, or an empty list. */
    public List<T> get(long height) {
        return byHeight.getOrDefault(height, List.of());
    }

    /** Whether {@code height} is retained at all, distinct from being retained as empty. */
    public boolean has(long height) {
        return byHeight.containsKey(height);
    }

    /**
     * Retains {@code value} for {@code height}, then evicts oldest-first until the byte budget is
     * met.
     *
     * <p>{@code height} itself is never evicted. Its value is read back immediately after commit —
     * {@code ChainEngine.collectStateChanges} folds it into the state root — so dropping it would
     * omit a whole domain from the root rather than merely lose a cache entry, which is a
     * consensus divergence. A single block cannot exceed the budget while the block size limit
     * holds, so the guard only fires on a misconfiguration, where keeping the oversized block is
     * the right call anyway.
     */
    public synchronized void retain(long height, List<T> value) {
        List<T> previous = byHeight.put(height, value);
        if (previous != null) {
            retainedBytes -= sizer.applyAsLong(previous);
        }
        retainedBytes += sizer.applyAsLong(value);
        while (retainedBytes > byteBudget) {
            Map.Entry<Long, List<T>> oldest = byHeight.firstEntry();
            if (oldest == null || oldest.getKey() == height) {
                break;
            }
            byHeight.remove(oldest.getKey());
            retainedBytes -= sizer.applyAsLong(oldest.getValue());
        }
    }

    /** Drops {@code height} — a reorg undid it — and credits its bytes back. */
    public synchronized List<T> forget(long height) {
        List<T> removed = byHeight.remove(height);
        if (removed != null) {
            retainedBytes -= sizer.applyAsLong(removed);
        }
        return removed;
    }

    /**
     * Drops every height the reorg window no longer covers, given the tip of the chain as
     * APPENDED — not as committed.
     *
     * <p>The difference is load-bearing. A commit can still be reverted inside the caller's
     * critical section (the stampStateRoot dry run over a candidate at tip+1, an addBlock
     * state-root rejection), so a watermark fed by commit attempts runs one height ahead of the
     * chain and prunes exactly the oldest in-window height a max-depth reorg still needs. That
     * reorg then dies on the rollback receipt guard, permanently, because the durable copy went
     * with the RAM one.
     *
     * @param durableDelete invoked per dropped height, before it leaves the map, for domains whose
     *                      store keeps a per-height row; {@code null} when there is none
     */
    public synchronized void pruneThrough(long chainTip, LongConsumer durableDelete) {
        long cutoff = chainTip - retainDepth;
        if (cutoff <= 0) {
            return;
        }
        // Inclusive of the cutoff: keep EXACTLY retainDepth heights, (cutoff, chainTip]. The
        // strict form kept one extra (audit F10).
        var expired = byHeight.headMap(cutoff, true);
        for (Map.Entry<Long, List<T>> e : expired.entrySet()) {
            if (durableDelete != null) {
                durableDelete.accept(e.getKey());
            }
            retainedBytes -= sizer.applyAsLong(e.getValue());
        }
        expired.clear();
    }

    /**
     * Retained payload size, for tests and diagnostics. Public because the VM tests in
     * {@code lib-vm} assert on it across the module boundary (the only module that cannot
     * see package-private members of this package).
     */
    public synchronized long retainedBytes() {
        return retainedBytes;
    }

    /**
     * Retained height count, for tests and diagnostics — public for the same reason as
     * {@link #retainedBytes()}.
     */
    public int retainedHeights() {
        return byHeight.size();
    }
}
