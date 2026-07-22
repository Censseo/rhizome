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
        clock.set(1000); // next window
        assertTrue(limiter.allow("a"));
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
}
