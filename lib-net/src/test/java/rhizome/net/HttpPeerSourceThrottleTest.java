package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A peer answering 429 is PACING us, not failing: the node weights {@code /sync} at one unit per
 * block against a per-IP budget, so a node applying blocks faster than that budget out-runs a
 * perfectly healthy peer. Waiting out the peer's window costs a fraction of a second; treating it
 * as a transport failure forfeits the whole sync round for that peer until the next one (~10 s).
 * These lock the distinction — and its bounds, so a peer that refuses everything cannot park the
 * sync thread.
 */
class HttpPeerSourceThrottleTest {

    /** Matches HttpPeerSource.MAX_THROTTLE_RETRIES: the sends after the first are the retries. */
    private static final int EXPECTED_SENDS_WHEN_ALWAYS_THROTTLED = 3;

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

    /** Serves {@code status} for the first {@code times} calls, then 200 with {@code body}. */
    private AtomicInteger respondThenSucceed(String path, int status, int times, String body,
                                             String retryAfter) {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(path, exchange -> {
            byte[] payload;
            int code;
            if (calls.incrementAndGet() <= times) {
                code = status;
                payload = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
                if (retryAfter != null) {
                    exchange.getResponseHeaders().add("Retry-After", retryAfter);
                }
            } else {
                code = 200;
                payload = body.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(code, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        return calls;
    }

    @Test
    void aThrottledReadIsRetriedAndSucceeds() {
        AtomicInteger calls = respondThenSucceed("/block_count", 429, 1, "42", null);

        long t0 = System.nanoTime();
        long height = new HttpPeerSource(baseUrl).height();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(42L, height, "the retry returns the peer's real answer");
        assertEquals(2, calls.get(), "one refused send, one that succeeded");
        assertTrue(elapsedMs >= 200,
            "must actually wait out the peer's rate-limit window, waited " + elapsedMs + " ms");
    }

    @Test
    void aThrottledBlockFetchIsRetriedToo() {
        // The /sync path has its own send loop (streamed decode under an idle deadline), so it
        // needs its own coverage: an empty 200 body decodes to an empty window.
        AtomicInteger calls = respondThenSucceed("/sync", 429, 1, "", null);

        assertEquals(java.util.List.of(), new HttpPeerSource(baseUrl).blocks(1, 1));
        assertEquals(2, calls.get(), "one refused send, one that succeeded");
    }

    @Test
    void aPeerThatKeepsThrottlingEndsAsUnavailableAndNeverEarnsBanScore() {
        // Bounded: the retry budget runs out and the exchange fails exactly as it did before
        // backoff existed — PeerUnavailableException, which the sync round retries without a ban.
        // PeerProtocolException here would be a regression: it feeds ban score.
        AtomicInteger calls = respondThenSucceed("/block_count", 429, Integer.MAX_VALUE, "42", null);

        var source = new HttpPeerSource(baseUrl);
        assertThrows(HttpPeerSource.PeerUnavailableException.class, source::height);
        assertEquals(EXPECTED_SENDS_WHEN_ALWAYS_THROTTLED, calls.get(),
            "the retry budget is bounded, so a hostile peer cannot park the sync thread");
    }

    @Test
    void a503IsNotRetriedBecauseItIsNotAPacingSignal() {
        // A peer 503s while it is itself mid-reorg — busy for seconds, not for a rate-limit
        // window. Retrying would burn the round's time on a peer that cannot answer yet; the
        // round must move on to the next peer instead.
        AtomicInteger calls = respondThenSucceed("/block_count", 503, Integer.MAX_VALUE, "42", null);

        var source = new HttpPeerSource(baseUrl);
        assertThrows(HttpPeerSource.PeerUnavailableException.class, source::height);
        assertEquals(1, calls.get(), "503 stays a one-shot unavailable");
    }

    @Test
    void anAbsurdRetryAfterIsClampedSoAPeerCannotParkTheSyncThread() {
        // Retry-After is peer-controlled. Honoured, but never beyond the ceiling — an hour here
        // must cost about a second, not an hour.
        AtomicInteger calls = respondThenSucceed("/block_count", 429, 1, "42", "3600");

        long t0 = System.nanoTime();
        long height = new HttpPeerSource(baseUrl).height();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(42L, height);
        assertEquals(2, calls.get());
        assertTrue(elapsedMs >= 900, "the header was honoured up to the ceiling, waited " + elapsedMs + " ms");
        assertTrue(elapsedMs < 5_000, "the header was clamped, waited " + elapsedMs + " ms");
    }
}
