package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class BodyReadDeadlineTest {

    @Test
    void saturatedPoolRejectsInsteadOfRunningInline() throws Exception {
        // With all 16 workers busy, the next exchange must fail fast with a saturation
        // rejection. Running it inline on the caller (the old CallerRunsPolicy) returned an
        // already-computed result from future.get(), silently disabling the wall-clock deadline
        // exactly under load — an unbounded slow-drip stall (audit). The rejection is a
        // DEDICATED BodyReadSaturatedException: local backpressure is not a peer failure, so
        // callers (PeerDiscovery failure counts, the sync round's ban score) must be able to
        // tell it apart from a genuine transport IOException.
        CountDownLatch started = new CountDownLatch(16);
        CountDownLatch release = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 16; i++) {
            Thread t = new Thread(() -> {
                try {
                    BodyReadDeadline.call(Duration.ofSeconds(30), new AtomicReference<>(), () -> {
                        started.countDown();
                        release.await();
                        return null;
                    });
                } catch (IOException | InterruptedException ignored) {
                    // torn down by the test: irrelevant
                }
            });
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS), "all workers occupied");
            var ex = assertThrows(BodyReadSaturatedException.class, () ->
                BodyReadDeadline.call(Duration.ofSeconds(1), new AtomicReference<>(), () -> null));
            assertTrue(String.valueOf(ex.getMessage()).contains("saturated"),
                "expected a saturation rejection, got: " + ex.getMessage());
        } finally {
            release.countDown();
            for (Thread t : threads) {
                t.join(5_000);
            }
        }
    }

    @Test
    void idleDeadlineKillsAStalledExchange() {
        // No progress is ever signalled: the idle bound must fire even though the exchange
        // itself "never" returns (slow-drip stall).
        AtomicLong noProgress = new AtomicLong(System.nanoTime());
        long t0 = System.nanoTime();
        var ex = assertThrows(IOException.class, () ->
            BodyReadDeadline.callIdle(Duration.ofMillis(400), new AtomicReference<>(), noProgress, () -> {
                Thread.sleep(30_000);
                return null;
            }));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 10_000, "stalled exchange must hit the idle deadline: " + elapsedMs + "ms");
        assertTrue(String.valueOf(ex.getMessage()).contains("idle"),
            "expected an idle-deadline rejection, got: " + ex.getMessage());
    }

    @Test
    void progressKeepsASlowExchangeAlive() throws Exception {
        // Total runtime (~1.2s) far exceeds the 400ms idle bound, but progress every 100ms
        // keeps the exchange alive — a FIXED whole-exchange deadline would have killed it
        // (the /sync slow-link fix).
        AtomicLong progress = new AtomicLong(System.nanoTime());
        String out = BodyReadDeadline.callIdle(Duration.ofMillis(400), new AtomicReference<>(), progress, () -> {
            for (int i = 0; i < 12; i++) {
                Thread.sleep(100);
                progress.set(System.nanoTime());
            }
            return "done";
        });
        assertEquals("done", out);
    }
}
