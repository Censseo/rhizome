package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void allowsUpToLimitThenBlocksWithinWindow() {
        var limiter = new RateLimiter(3, 1000, 100);
        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a")); // 4th in the window
    }

    @Test
    void windowResetsAllowsAgain() {
        AtomicLong clock = new AtomicLong(0);
        var limiter = new RateLimiter(1, 1000, 100, clock::get);
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));
        // Sliding window: one window later the previous count still weighs (decaying), so the
        // budget is NOT fully back yet — two full windows must elapse (audit: boundary burst).
        clock.set(1000);
        assertFalse(limiter.allow("a"), "previous window still decays into the estimate");
        clock.set(2000);
        assertTrue(limiter.allow("a"));
    }

    @Test
    void noDoubleBudgetBurstAtWindowBoundary() {
        // The fixed-window flaw this replaces: a full budget fired at the end of one window and
        // another full budget at the start of the next (2x the limit inside a few milliseconds).
        // With the weighted sliding estimate, the just-closed window still counts (decaying)
        // against the next (audit).
        AtomicLong clock = new AtomicLong(0);
        var limiter = new RateLimiter(10, 1000, 100, clock::get);
        assertTrue(limiter.allow("a")); // anchors the window at t=0
        clock.set(999); // rest of the budget fired at the tail of window [0, 1000)
        for (int i = 0; i < 9; i++) {
            assertTrue(limiter.allow("a"), "budget fills the first window");
        }
        clock.set(1001); // just past the boundary: a fixed window would grant 10 fresh tokens
        assertFalse(limiter.allow("a"), "the previous window still weighs ~fully: no 2x burst");
        clock.set(1100); // prev has decayed to 0.9 weight: one unit of headroom
        assertTrue(limiter.allow("a"), "one request fits the decayed headroom");
        assertFalse(limiter.allow("a"), "but a second full budget right away is refused");
    }

    @Test
    void clientsPerBucketAreIndependent() {
        var limiter = new RateLimiter(1, 1000, 100);
        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("b"));
        assertFalse(limiter.allow("a"));
    }

    @Test
    void clientTableIsBoundedAndSweepsExpired() {
        AtomicLong clock = new AtomicLong(0);
        var limiter = new RateLimiter(10, 1000, 5, clock::get);
        for (int i = 0; i < 5; i++) {
            limiter.allow("client-" + i);
        }
        // Table is full at capacity.
        assertTrue(limiter.trackedClients() <= 5);

        // Advance past the window so the old entries are sweepable, then a burst of
        // new clients must not grow the table without bound.
        clock.set(2000);
        for (int i = 0; i < 1000; i++) {
            limiter.allow("spray-" + i);
        }
        assertTrue(limiter.trackedClients() <= 5, "tracked clients must stay bounded");
    }

    @Test
    void concurrentSprayKeepsTableBounded() throws Exception {
        // IP-spray from many threads at once: every request is a table miss under capacity
        // pressure — exactly the case where the sweep used to run inside the check-and-insert
        // monitor. The table bound must hold under the race, no admission may throw, and the
        // fail-closed overflow bucket keeps metering untracked clients.
        var limiter = new RateLimiter(10, 60_000, 64);
        int threads = 8, perThread = 500;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    limiter.allow("ip-" + (base + i));
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertTrue(limiter.trackedClients() <= 64,
            "the client table must stay bounded under a concurrent spray");
    }
}
