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
        var source = new HttpPeerSource(baseUrl, false, HttpPeerSource.newClient(), Duration.ofMillis(500));
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
}
