package rhizome.net;

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
    /**
     * Shared fallback bucket used when the per-client table is full and nothing can be
     * reclaimed. Instead of failing open (letting an IP-spray disable rate limiting for
     * everyone — audit M1), all otherwise-untracked clients are metered together against a
     * single conservative bucket, so total overflow traffic stays bounded.
     */
    private final Window overflow = new Window(0);

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
        return allow(client, 1);
    }

    /**
     * Records a request from {@code client} that costs {@code cost} units of budget, returning
     * false if it takes the client over the window limit. Weighting expensive endpoints (deep
     * chain scans, VM dry-runs) by their true cost stops one client from driving orders of
     * magnitude more work than a flat per-request budget would imply (audit M2).
     */
    public boolean allow(String client, int cost) {
        long now = nowMillis.getAsLong();
        Window window = clients.get(client);
        if (window == null) {
            if (clients.size() >= maxClients && !sweepExpired(now)) {
                // Fail closed: meter every untracked client against one shared bucket rather
                // than allowing them all unlimited (which an IP-spray could exploit to disable
                // rate limiting globally). Conservative but bounded.
                return count(overflow, now, cost);
            }
            window = clients.computeIfAbsent(client, k -> new Window(now));
        }
        return count(window, now, cost);
    }

    private boolean count(Window window, long now, int cost) {
        synchronized (window) {
            if (now - window.start >= windowMs) {
                window.start = now;
                window.count.set(0);
            }
            return window.count.addAndGet(Math.max(1, cost)) <= maxRequestsPerWindow;
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
