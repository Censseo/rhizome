package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Peer discovery must never buffer an unbounded {@code /peers} body from a hostile peer (audit V2).
 * {@code fetchPeers} runs automatically against every known peer each round, so a multi-GB response
 * would OOM the node; the read must be size-capped exactly like {@link HttpPeerSource}.
 */
class PeerDiscoveryBodyBoundTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(byte[] body) {
        server.createContext("/peers", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @Test
    void oversizedPeersBodyIsRejectedNotBuffered() {
        // 1 MiB of junk — far over the 64 KiB cap. Before the fix this went through
        // BodyHandlers.ofString(), which buffers the whole body (here, and a multi-GB body all the
        // same) before MAX_PEX_PER_PEER ever applies. It must now abort on the size cap.
        byte[] huge = new byte[1024 * 1024];
        java.util.Arrays.fill(huge, (byte) '9');
        respond(huge);

        var discovery = new PeerDiscovery(new PeerRegistry(null, 128), baseUrl);
        var ex = assertThrows(Exception.class, () -> discovery.fetchPeers(baseUrl));
        assertTrue(String.valueOf(ex.getMessage()).contains("exceeds"),
            "expected a size-cap rejection, got: " + ex.getMessage());
    }

    @Test
    void normalPeersBodyStillParses() {
        respond("{\"peers\":[\"http://10.1.2.3:3000\",\"http://10.1.2.4:3000\"]}"
            .getBytes(StandardCharsets.UTF_8));
        var discovery = new PeerDiscovery(new PeerRegistry(null, 128), baseUrl);
        List<String> peers = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> discovery.fetchPeers(baseUrl));
        assertEquals(2, peers.size());
        assertTrue(peers.contains("http://10.1.2.3:3000"));
    }

    @Test
    void slowDripPeersBodyHitsDeadline() {
        // Headers arrive, then the body stalls: the request timeout alone only covers up to the
        // headers, so without the whole-exchange deadline a slow-drip peer would park a PEX pool
        // thread in InputStream.read (audit F2). With a short test deadline it must fail fast.
        server.createContext("/peers", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write(new byte[]{'{'});
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(5_000); // the rest "never" arrives
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        var discovery = new PeerDiscovery(new PeerRegistry(null, 128), null, false, Duration.ofMillis(500));
        long t0 = System.nanoTime();
        var ex = assertThrows(Exception.class, () -> discovery.fetchPeers(baseUrl));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 4_000, "slow-drip PEX read must hit the deadline, not hang: " + elapsedMs + "ms");
        assertTrue(String.valueOf(ex.getMessage()).contains("exceeded"),
            "expected a deadline rejection, got: " + ex.getMessage());
    }

    @Test
    void discoveredPeersAreEvictedAfterRepeatedPexFailures() {
        var registry = new PeerRegistry(null, 128);
        assertTrue(registry.add("http://127.0.0.1:1")); // port 1: connection refused
        var discovery = new PeerDiscovery(registry, null);
        for (int i = 0; i < 4; i++) {
            discovery.round();
        }
        assertEquals(0, registry.size(), "a discovered peer is pruned after MAX_FAILURES rounds");
    }

    @Test
    void seedsSurviveRepeatedPexFailures() {
        // 3 failed PEX rounds evict a discovered peer — but a SEED is an operator-configured
        // anchor and is never evicted by the failure path (mirrors penalize()'s seed exemption):
        // an attacker that can briefly DoS the seeds must not be able to unanchor the node.
        var registry = new PeerRegistry(null, 128);
        registry.addSeeds(List.of("http://127.0.0.1:1")); // port 1: connection refused
        var discovery = new PeerDiscovery(registry, null);
        for (int i = 0; i < 4; i++) {
            discovery.round();
        }
        assertTrue(registry.isSeed("http://127.0.0.1:1"),
            "a seed must survive repeated PEX failures");
        assertEquals(1, registry.size());
    }

    @Test
    void localPoolSaturationIsNotCountedAsPeerFailure() throws Exception {
        // Saturate the shared body-read pool (all 16 workers blocked) so the peer's PEX
        // exchange is rejected LOCALLY, before any I/O reaches it. Local backpressure is not
        // a peer failure: it must not increment the failure count and must never evict the
        // peer — even past MAX_FAILURES rounds (audit: AbortPolicy saturation imputed to peers).
        var started = new java.util.concurrent.CountDownLatch(16);
        var release = new java.util.concurrent.CountDownLatch(1);
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 16; i++) {
            Thread t = new Thread(() -> {
                try {
                    BodyReadDeadline.call(Duration.ofSeconds(30),
                        new java.util.concurrent.atomic.AtomicReference<>(), () -> {
                            started.countDown();
                            release.await();
                            return null;
                        });
                } catch (Exception ignored) {
                    // torn down by the test: irrelevant
                }
            });
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        try {
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS), "all workers occupied");
            var registry = new PeerRegistry(null, 128);
            assertTrue(registry.add(baseUrl));
            var discovery = new PeerDiscovery(registry, null);
            for (int i = 0; i < 4; i++) { // past MAX_FAILURES: an eviction would have happened by now
                discovery.round();
            }
            assertTrue(discovery.failures.isEmpty(),
                "local saturation must not count as a peer failure");
            assertEquals(1, registry.size(),
                "an honest peer must not be evicted over LOCAL pool saturation");
        } finally {
            release.countDown();
            for (Thread t : threads) {
                t.join(5_000);
            }
        }
    }

    @Test
    void failureCountsArePrunedWhenPeerLeavesRegistry() {
        // A peer that failed is failure-counted; once it leaves the registry (e.g. pruned after
        // repeated failures), the next round must drop its stale failure entry so the map cannot
        // leak entries for long-gone peers (audit F8).
        var registry = new PeerRegistry(null, 128);
        assertTrue(registry.add("http://127.0.0.1:1")); // port 1: connection refused
        var discovery = new PeerDiscovery(registry, null);
        discovery.round();
        assertTrue(discovery.failures.containsKey("http://127.0.0.1:1"),
            "a failed peer should be failure-counted");
        registry.remove("http://127.0.0.1:1");
        discovery.round(); // empty registry: the stale failure entry must still be pruned
        assertFalse(discovery.failures.containsKey("http://127.0.0.1:1"));
    }
}
