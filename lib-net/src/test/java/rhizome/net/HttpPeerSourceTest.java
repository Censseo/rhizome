package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The peer source must never buffer an unbounded body from a hostile peer. */
class HttpPeerSourceTest {

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

    private void respond(String path, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @Test
    void oversizedTotalWorkIsRejectedNotParsed() {
        // A malicious peer returns a multi-megabyte "totalWork" string; without the
        // cap this would be an O(n^2) BigInteger-parse DoS. It must be refused before
        // parsing.
        byte[] huge = new byte[8 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) '9');
        respond("/total_work", huge);

        var source = new HttpPeerSource(baseUrl);
        assertThrows(HttpPeerSource.PeerUnavailableException.class, source::totalWork);
    }

    @Test
    void normalScalarResponseStillWorks() {
        respond("/block_count", "42".getBytes(StandardCharsets.UTF_8));
        var source = new HttpPeerSource(baseUrl);
        assertEquals(42L, source.height());
    }

    @Test
    void slowDripBodyHitsWholeExchangeDeadline() {
        // Headers arrive promptly, then the body stalls: the JDK request timeout only covers up
        // to the response headers, so without the whole-exchange deadline this read would hang
        // the single sync thread forever (audit F1). With a short test deadline it must fail
        // fast, surfaced as a transport failure (PeerUnavailableException).
        server.createContext("/block_count", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(new byte[]{'4'});
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(5_000); // the remaining bytes "never" arrive
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        var source = new HttpPeerSource(baseUrl, false, PeerExchange.newClient(), Duration.ofMillis(500));
        long t0 = System.nanoTime();
        var ex = assertThrows(HttpPeerSource.PeerUnavailableException.class, source::height);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 4_000, "slow-drip read must hit the deadline, not hang: " + elapsedMs + "ms");
        assertTrue(String.valueOf(ex.getCause()).contains("exceeded"),
            "expected a deadline rejection, got: " + ex.getCause());
    }

    @Test
    void malformedScalarEarnsProtocolException() {
        // A syntactically-invalid scalar is peer MISBEHAVIOUR (PeerProtocolException, ban-score
        // eligible), not a transport outage (audit F9).
        respond("/block_count", "not-a-number".getBytes(StandardCharsets.UTF_8));
        var source = new HttpPeerSource(baseUrl);
        assertThrows(HttpPeerSource.PeerProtocolException.class, source::height);
    }

    @Test
    void oversizedSnapshotChunkCountIsRejected() {
        // The chunk count is peer-controlled and SnapshotBootstrap loops/pre-sizes on it; out-of-range
        // values must be rejected as misbehaviour before they are ever acted on (audit F6).
        String root = "00".repeat(32);
        respond("/state/snapshot/info",
            ("{\"pivotHeight\":10,\"stateRoot\":\"" + root + "\",\"chunks\":2000000}")
                .getBytes(StandardCharsets.UTF_8));
        var source = new HttpPeerSource(baseUrl);
        assertThrows(HttpPeerSource.PeerProtocolException.class, source::snapshotInfo);
    }

    @Test
    void zeroSnapshotChunkCountIsRejected() {
        String root = "00".repeat(32);
        respond("/state/snapshot/info",
            ("{\"pivotHeight\":10,\"stateRoot\":\"" + root + "\",\"chunks\":0}")
                .getBytes(StandardCharsets.UTF_8));
        var source = new HttpPeerSource(baseUrl);
        assertThrows(HttpPeerSource.PeerProtocolException.class, source::snapshotInfo);
    }

    @Test
    void validSnapshotInfoStillParses() {
        String root = "00".repeat(32);
        respond("/state/snapshot/info",
            ("{\"pivotHeight\":10,\"stateRoot\":\"" + root + "\",\"chunks\":3}")
                .getBytes(StandardCharsets.UTF_8));
        var source = new HttpPeerSource(baseUrl);
        var info = org.junit.jupiter.api.Assertions.assertDoesNotThrow(source::snapshotInfo);
        assertEquals(3, info.chunkCount());
        assertEquals(10L, info.pivotHeight());
    }

    @Test
    void orphanRoundTripsABlockBody() {
        // The /orphan endpoint serves one binary block (BlockCodec) by hash — the uncle body a
        // syncing peer needs for validateUncles (audit: uncle-sync blocker).
        var block = rhizome.core.block.BlockImpl.builder()
            .id(7).timestamp(123456789L).difficulty(4)
            .merkleRoot(rhizome.crypto.SHA256Hash.random())
            .lastBlockHash(rhizome.crypto.SHA256Hash.random())
            .nonce(rhizome.crypto.SHA256Hash.random())
            .build();
        respond("/orphan", rhizome.core.block.BlockCodec.encode(block));
        var source = new HttpPeerSource(baseUrl);
        var fetched = source.orphan(block.hash());
        org.junit.jupiter.api.Assertions.assertNotNull(fetched);
        assertEquals(block.hash(), fetched.hash(), "decoded orphan must be the served block");
    }

    @Test
    void orphan404MeansAbsentNotMisbehaviour() {
        // No context registered -> the server answers 404. That is "the peer does not hold this
        // orphan" (a legacy peer without the route answers 404 the same way), surfaced as null —
        // never as a ban-score-earning protocol violation.
        var source = new HttpPeerSource(baseUrl);
        org.junit.jupiter.api.Assertions.assertNull(source.orphan(rhizome.crypto.SHA256Hash.random()));
    }

    @Test
    void orphanJunkBodyEarnsProtocolException() {
        // A 200 with a body that is not a decodable block is junk no honest node would serve
        // (audit F9): misbehaviour, not "absent".
        respond("/orphan", new byte[]{1, 2, 3, 4, 5});
        var source = new HttpPeerSource(baseUrl);
        assertThrows(HttpPeerSource.PeerProtocolException.class,
            () -> source.orphan(rhizome.crypto.SHA256Hash.random()));
    }

    @Test
    void localPoolSaturationIsNotAPeerFailure() throws Exception {
        // Saturate the shared body-read pool (all 16 workers blocked): the exchange is
        // rejected LOCALLY before any I/O. It must surface as LocalSaturationException —
        // NOT PeerUnavailableException (a transport failure) and NOT PeerProtocolException
        // (misbehaviour) — so the sync round cannot read our own backpressure as a peer
        // fault and ban-score an honest peer (audit: AbortPolicy saturation imputed to peers).
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
            var source = new HttpPeerSource(baseUrl);
            assertThrows(rhizome.core.blockchain.LocalSaturationException.class, source::height);
            assertThrows(rhizome.core.blockchain.LocalSaturationException.class,
                () -> source.blocks(1, 10));
        } finally {
            release.countDown();
            for (Thread t : threads) {
                t.join(5_000);
            }
        }
    }

    @Test
    void configuredPeerTokenIsAttachedAsBearer() {
        // RHIZOME_PEER_TOKEN: outbound requests carry Authorization: Bearer <token> so a
        // token-gated peer accepts them (audit: token-gated node breaks gossip). The policy
        // decides WHETHER to hand over the token (PeerTokenPolicyTest); this asserts the
        // transport actually attaches what it is handed — and nothing when it is handed null.
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/block_count", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "42".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        assertEquals(42L, new HttpPeerSource(baseUrl, false, PeerExchange.newClient(),
            PeerTokenPolicy.unsafeTrustAllForTests("s3cret")).height());
        assertEquals("Bearer s3cret", seen.get());
        // A tokenless source sends no Authorization header at all.
        seen.set(null);
        assertEquals(42L, new HttpPeerSource(baseUrl).height());
        org.junit.jupiter.api.Assertions.assertNull(seen.get());
    }

    @Test
    void aConfiguredPeerReachedOverCleartextGetsNoToken() {
        // End-to-end counterpart of PeerTokenPolicyTest: the real policy is wired into the real
        // transport and the peer IS configured — but the URL is http://, so the shared secret
        // must not cross the wire (audit B-2: the deprecated constructors used to send it).
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/block_count", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "42".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var policy = new PeerTokenPolicy("s3cret", java.util.List.of(baseUrl));
        assertEquals(42L, new HttpPeerSource(baseUrl, false, PeerExchange.newClient(), policy).height());
        org.junit.jupiter.api.Assertions.assertNull(seen.get(),
            "the peer token must never travel over cleartext http, configured or not");
    }
}
