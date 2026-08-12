package rhizome.node;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import rhizome.net.PeerUrls;

/**
 * Off-loop admission of self-announced peers ({@code /add_peer}), bounded and coalescing.
 *
 * <p>Admitting a peer resolves DNS — ban check, routability, subnet bucket — and that blocking
 * work must never run on the ActiveJ event-loop thread that serves the request, or a peer whose
 * hostname resolves slowly (or times out) would freeze the entire node.
 *
 * <p>Three bounds hold, and they are the reason this is a type rather than four fields: the queue
 * is capped (an unbounded one grew ~1000 entries/s under an {@code /add_peer} flood, each
 * retaining a URL and an ~5 s blocking resolve, until the heap gave out — audit), duplicate URLs
 * coalesce, and duplicate <em>hosts</em> coalesce too, because an attacker varies the port or path
 * of one slow-resolving host to queue an unbounded number of resolves for the same host against
 * the single admission thread (audit: /add_peer coalescing bypass by port variation). The two sets
 * move in lock-step with the queue — every task clears its entries on completion and a rejected
 * enqueue clears them inline — so neither can outgrow the queue bound.
 *
 * <p>Closing it is what {@code NodeService} never did: the executor was a field with no shutdown,
 * so every node ever built leaked its {@code rhizome-peer-admit} thread. Daemon threads hide that
 * in production and accumulate one per node across a test run.
 */
final class PeerAdmissionQueue implements AutoCloseable {

    /** Bound on peer admissions queued off-loop at once; excess {@code /add_peer} calls are shed. */
    private static final int MAX_PENDING_ADMISSIONS = 256;

    private final ExecutorService workers = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(MAX_PENDING_ADMISSIONS),
        r -> {
            Thread t = new Thread(r, "rhizome-peer-admit");
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.AbortPolicy());

    /** URLs currently queued or running, so duplicate {@code /add_peer} coalesce. */
    private final Set<String> pendingUrls = ConcurrentHashMap.newKeySet();

    /** Hosts currently queued or running, in lock-step with {@link #pendingUrls}. */
    private final Set<String> pendingHosts = ConcurrentHashMap.newKeySet();

    /**
     * Queues {@code url} for off-loop admission and returns immediately. {@code admit} receives the
     * canonical form — the same form the registry applies at admission, which is why canonicalising
     * happens BEFORE the coalescing sets: case, trailing dot, default port and slash variants of
     * one URL must occupy a single in-flight slot (audit: /add_peer coalescing bypass).
     *
     * <p>Silently does nothing when the URL is unusable, when an equivalent admission is already in
     * flight, or when the queue is full. All three are load shedding, not errors: the caller is an
     * unauthenticated endpoint whose contract is "best effort, never blocks".
     */
    void enqueue(String url, Consumer<String> admit) {
        String canonical = PeerUrls.canonicalize(url);
        if (canonical == null || canonical.isEmpty()) {
            return;
        }
        if (!pendingUrls.add(canonical)) {
            return; // already queued or running
        }
        String host = hostOf(canonical);
        if (host != null && !pendingHosts.add(host)) {
            pendingUrls.remove(canonical);
            return; // another admission for this host is already queued or running
        }
        try {
            workers.execute(() -> {
                try {
                    admit.accept(canonical);
                } catch (RuntimeException e) {
                    // Best-effort: a malformed or unresolvable peer is simply not added.
                } finally {
                    release(canonical, host);
                }
            });
        } catch (RejectedExecutionException rejected) {
            release(canonical, host); // queue full: shed load, keep the sets bounded
        }
    }

    private void release(String canonical, String host) {
        pendingUrls.remove(canonical);
        if (host != null) {
            pendingHosts.remove(host);
        }
    }

    /** Lower-cased host of a canonical peer URL, or {@code null} when it has none (unparseable). */
    private static String hostOf(String canonicalUrl) {
        try {
            String host = java.net.URI.create(canonicalUrl).getHost();
            return host == null || host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** In-flight admissions, for tests and diagnostics. */
    int inFlight() {
        return pendingUrls.size();
    }

    /**
     * Stops the worker. Not drained: a queued admission is a best-effort DNS resolve whose only
     * output is a registry entry the node is about to stop using, and the running one may be
     * parked in a ~5 s resolve that shutdown should not wait on.
     */
    @Override
    public void close() {
        workers.shutdownNow();
    }
}
