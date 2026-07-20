package rhizome.node;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Per-client fixed-window rate limiter with a hard cap on the number of tracked
 * clients — so it cannot leak memory under a spray of distinct source IPs
 * (Pandanite issue #52, where the limiter accumulated IPs without eviction).
 *
 * <p>When at capacity, entries whose window has fully expired are swept; if none
 * can be reclaimed, a new client is simply allowed (fail-open on tracking, never
 * unbounded growth).
 */
public final class RateLimiter {

    private final int maxRequestsPerWindow;
    private final long windowMs;
    private final int maxClients;
    private final LongSupplier nowMillis;
    private final Map<String, Window> clients = new ConcurrentHashMap<>();

    private static final class Window {
        volatile long start;
        final AtomicInteger count = new AtomicInteger();
        Window(long start) { this.start = start; }
    }

    public RateLimiter(int maxRequestsPerWindow, long windowMs, int maxClients) {
        this(maxRequestsPerWindow, windowMs, maxClients, System::currentTimeMillis);
    }

    RateLimiter(int maxRequestsPerWindow, long windowMs, int maxClients, LongSupplier nowMillis) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowMs = windowMs;
        this.maxClients = maxClients;
        this.nowMillis = nowMillis;
    }

    /** Records a request from {@code client}; returns false if it is over the limit. */
    public boolean allow(String client) {
        long now = nowMillis.getAsLong();
        Window window = clients.get(client);
        if (window == null) {
            if (clients.size() >= maxClients && !sweepExpired(now)) {
                return true; // tracking full and nothing reclaimable: don't grow, don't block
            }
            window = clients.computeIfAbsent(client, k -> new Window(now));
        }
        synchronized (window) {
            if (now - window.start >= windowMs) {
                window.start = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= maxRequestsPerWindow;
        }
    }

    /** Removes clients whose window has fully elapsed. Returns true if any were removed. */
    private boolean sweepExpired(long now) {
        int before = clients.size();
        clients.values().removeIf(w -> now - w.start >= windowMs);
        return clients.size() < before;
    }

    public int trackedClients() {
        return clients.size();
    }
}
