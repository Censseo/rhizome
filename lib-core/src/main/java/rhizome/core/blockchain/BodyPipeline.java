package rhizome.core.blockchain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import rhizome.core.block.Block;
import rhizome.core.common.Constants;

/**
 * The fetch/apply pipeline both synchronizers run: split {@code [from..to]} into
 * {@link Constants#BLOCKS_PER_FETCH} windows and, while window K is applied, download window K+1
 * on a helper thread.
 *
 * <p>{@code ChainSynchronizer.applyRange} and {@code HeaderSynchronizer.applyBodies} were two
 * transcriptions of this loop, plus a byte-identical private {@code submitFetch} in each. They
 * differ in exactly three places, and those three are what this class takes as parameters: the
 * helper thread's name, what a fetch failure means (the two paths classify the same exceptions
 * differently — see below), and what to do with a window's blocks. Everything else — the window
 * arithmetic, the one-fetch-outstanding invariant, the interrupt handling, the
 * {@code shutdownNow} in the finally — was duplicated, which is the kind of duplication where the
 * two copies drift on the detail that matters and no test notices.
 *
 * <p>What the pipeline does NOT change is the point: application stays strictly serial and in
 * order on the calling thread, so the applied sequence — and therefore every {@code addBlock}
 * verdict and the state root — is byte-for-byte what a plain serial loop produced. Only read-only
 * network I/O moves off-thread. Exactly one fetch is ever outstanding, so peak memory is two
 * windows rather than one, and the peer source is still used from one thread at a time. Measured
 * at ~1.7x on a loopback peer with 16 KiB blocks ({@code SyncThroughputBenchmark}).
 */
final class BodyPipeline {

    private BodyPipeline() {
    }

    /** What to do with one window's blocks; {@code false} aborts the pipeline. */
    @FunctionalInterface
    interface WindowApply {
        boolean apply(List<Block> blocks);
    }

    /**
     * How a caller classifies a failed window fetch.
     *
     * <p>This is the one place the two synchronizers genuinely disagree, and the reason it is a
     * parameter rather than a shared body. The full-block path propagates every
     * {@link RuntimeException} to its caller, which maps {@code PeerUnavailableException} and
     * {@code LocalSaturationException} differently from a malformed response. The headers-first
     * path rethrows only those two — local backpressure and a transport failure are not the peer's
     * fault and must never read as PEER_INVALID — and treats anything else as a failed window.
     *
     * @param cause the {@link ExecutionException}'s cause; throw it (or a wrapper) to propagate,
     *              or return normally to abort the sync with a {@code false} verdict
     */
    @FunctionalInterface
    interface FetchFailurePolicy {
        void onFetchFailure(Throwable cause);
    }

    /**
     * Runs the pipeline over {@code [from..to]}. Returns true when every window applied, false when
     * {@code applyWindow} rejected a block, the thread was interrupted, or {@code onFetchFailure}
     * chose to abort rather than throw. An empty range is a success.
     */
    static boolean run(String threadName, PeerSource peer, long from, long to,
                       FetchFailurePolicy onFetchFailure, WindowApply applyWindow) {
        List<long[]> windows = new ArrayList<>();
        for (long start = from; start <= to; start += Constants.BLOCKS_PER_FETCH) {
            windows.add(new long[] {start, Math.min(to, start + Constants.BLOCKS_PER_FETCH - 1)});
        }
        if (windows.isEmpty()) {
            return true;
        }
        ExecutorService fetcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        try {
            Future<List<Block>> pending = submitFetch(fetcher, peer, windows.get(0));
            for (int i = 0; i < windows.size(); i++) {
                List<Block> blocks;
                try {
                    blocks = pending.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (ExecutionException e) {
                    onFetchFailure.onFetchFailure(e.getCause()); // throws, or falls through to abort
                    return false;
                }
                // Start the next window's fetch BEFORE applying this one, so the two overlap.
                if (i + 1 < windows.size()) {
                    pending = submitFetch(fetcher, peer, windows.get(i + 1));
                }
                if (!applyWindow.apply(blocks)) {
                    return false;
                }
            }
            return true;
        } finally {
            // Cancel a still-running prefetch (early return on a rejected block, or a throw) and
            // free the helper thread. The fetch is read-only, so a discarded result changes nothing.
            fetcher.shutdownNow();
        }
    }

    private static Future<List<Block>> submitFetch(ExecutorService fetcher, PeerSource peer, long[] window) {
        return fetcher.submit(() -> peer.blocks(window[0], window[1]));
    }
}
