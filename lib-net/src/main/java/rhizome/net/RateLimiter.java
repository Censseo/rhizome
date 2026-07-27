package rhizome.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-client SLIDING-window rate limiter with a hard cap on the number of tracked
 * clients — so it cannot leak memory under a spray of distinct source IPs
 * (Pandanite issue #52, where the limiter accumulated IPs without eviction).
 *
 * <p>The window glides rather than resetting: each client keeps the current and the
 * previous window's counters, and the admitted rate is the weighted sum
 * {@code prev * (1 - elapsed/window) + curr}. A fixed window lets a client fire a full
 * budget at the end of one window and another full budget at the start of the next — a
 * 2× burst the limit was meant to preclude (audit: fixed-window boundary burst); the
 * weighted sum keeps the long-run rate at the configured budget from any phase.
 *
 * <p>When at capacity, entries whose windows have fully expired are swept; if none
 * can be reclaimed, a new client is metered against a shared overflow bucket
 * (fail-closed on tracking, never unbounded growth).
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
    /**
     * Amortized-sweep watermark (epoch millis of the last full sweep). Volatile: the due
     * check runs OUTSIDE the monitor, so the common path never enters {@code synchronized}
     * just to sweep — under an IP-spray every request is a table miss, and running the
     * O(maxClients) sweep inside the check-and-insert monitor made every sprayed request
     * pay it on the event loop (audit: sweep under the global monitor).
     */
    private volatile long lastSweepAt = Long.MIN_VALUE;

    private static final class Window {
        volatile long start;
        int prev; // previous window's total (sliding-weighted into the current estimate)
        int curr; // current window's total
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
        maybeSweep(now);
        Window window = clients.get(client);
        if (window == null) {
            // Check-and-insert under one monitor: a lock-free size() >= maxClients check racing
            // computeIfAbsent let concurrent admissions push the table past its bound (audit).
            // The monitor now covers ONLY the check+insert; expiry is reclaimed by the
            // amortized sweep above, never inside this critical section.
            synchronized (clients) {
                window = clients.get(client);
                if (window == null) {
                    if (clients.size() >= maxClients) {
                        // Fail closed: meter every untracked client against one shared bucket rather
                        // than allowing them all unlimited (which an IP-spray could exploit to disable
                        // rate limiting globally). Conservative but bounded; the next amortized
                        // sweep reclaims any expired entries and tracking resumes.
                        return count(overflow, now, cost);
                    }
                    window = clients.computeIfAbsent(client, k -> new Window(now));
                }
            }
        }
        return count(window, now, cost);
    }

    private boolean count(Window window, long now, int cost) {
        synchronized (window) {
            long elapsed = now - window.start;
            if (elapsed >= windowMs) {
                // Slide one window forward, ANCHORED to the boundary (not to now) so the decay
                // phase stays stable across requests: the just-closed window becomes the
                // decaying previous one; two or more elapsed windows means nothing counts.
                if (elapsed >= 2 * windowMs) {
                    window.prev = 0;
                    window.start = now;
                } else {
                    window.prev = window.curr;
                    window.start += windowMs;
                }
                window.curr = 0;
                elapsed = now - window.start;
            }
            // Weighted sliding rate: the previous window's contribution decays linearly as
            // the current window advances. Checked BEFORE charging: a denied request must not
            // consume budget, or its charge would cascade into the next window's decaying
            // previous count and lock an honest borderline client out permanently.
            double estimate = window.prev * (1.0 - (double) elapsed / windowMs) + window.curr;
            int c = Math.max(1, cost);
            if (estimate + c > maxRequestsPerWindow) {
                return false;
            }
            window.curr += c;
            return true;
        }
    }

    /**
     * Amortized expiry sweep: at most one full pass per window. The due check reads the
     * volatile watermark outside the monitor; only a due sweep takes the lock (re-checked
     * inside, so concurrent callers cannot stampede). Sweeping only REMOVES entries, so it
     * can never race the check-and-insert bound — the monitor-protected size check in
     * {@link #allow} is what keeps the table at {@code maxClients}.
     */
    private void maybeSweep(long now) {
        if (now < lastSweepAt + windowMs) {
            return;
        }
        synchronized (clients) {
            if (now < lastSweepAt + windowMs) {
                return;
            }
            lastSweepAt = now;
            clients.values().removeIf(w -> now - w.start >= 2 * windowMs);
        }
    }

    public int trackedClients() {
        return clients.size();
    }
}
