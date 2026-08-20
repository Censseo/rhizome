package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.node.RhizomeNode;

/**
 * Abuse of the node's HTTP surface from outside the process, on a real socket.
 *
 * <p>{@code NodeApiTest} drives the same servlet in-process and asserts the same statuses, so what
 * is added here is not the rule but the assembly around it: a real listening socket, the real
 * header parser, the real middleware order, and the node's own producer running throughout. The
 * questions that only exist at this level are whether a forged header survives the wire, whether
 * an abusive client can be refused without taking the event loop down with it, and whether the
 * node is still making blocks afterwards.
 *
 * <p>Requests are written byte by byte ({@link RawHttp}) because the two browser guards are keyed
 * on {@code Host} and {@code Origin}, and a standard client refuses to forge those — which is the
 * attacker's whole job.
 */
class E2EApiAbuseTest {

    @TempDir
    Path tempDir;

    private static final String TOKEN = "s3cret-operator-token";

    /** A syntactically fine but semantically empty JSON body, so only the guard under test fires. */
    private static byte[] json() {
        return "{}".getBytes(StandardCharsets.UTF_8);
    }

    private static void assertStillProducing(RhizomeNode node) throws InterruptedException {
        long before = node.engine().height();
        TestNetwork.await(() -> node.engine().height() > before,
            () -> "the node stopped producing blocks after the abuse (stuck at " + before + ")");
    }

    /**
     * E2E-05 — the operator token actually gates the state-changing routes on the wire, and a
     * refused caller costs the node nothing: it keeps mining.
     */
    @Test
    void stateChangingRoutesAreRefusedWithoutTheBearerAndTheNodeKeepsProducing() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("guarded")
                .mining().blockInterval(150).apiToken(TOKEN).start();
            int port = node.apiPort();

            assertEquals(401, RawHttp.post(port, "/submit", Map.of(), json()).status(),
                "an unauthenticated /submit must be refused");
            assertEquals(401, RawHttp.post(port, "/add_transaction", Map.of(), json()).status());
            assertEquals(401, RawHttp.post(port, "/add_transaction",
                    Map.of("Authorization", "Bearer wrong-token"), json()).status(),
                "a wrong bearer is no better than none");

            // With the right bearer the request reaches the handler: it is rejected on its content
            // (an empty object is not a transaction), which is a different refusal entirely.
            int authorized = RawHttp.post(port, "/add_transaction",
                Map.of("Authorization", "Bearer " + TOKEN), json()).status();
            assertNotEquals(401, authorized, "the correct bearer must pass the gate");

            assertStillProducing(node);
        }
    }

    /**
     * E2E-33 — no bearer prefix is treated as "close enough". Every one of the token's strict
     * prefixes, presented over the same real socket and header parser as the test above, must be
     * refused exactly like a wrong token entirely — a truncating or length-only comparison would
     * let a sufficiently long shared prefix slip through undetected. This is the behavioural half
     * of the API-13 proof that the bearer comparison cannot be shortcut; the structural half, that
     * the comparison is actually {@code MessageDigest.isEqual} rather than a comparison this test
     * could pass by accident, is {@code TokenComparisonAttackTest}.
     */
    @Test
    void everyStrictPrefixOfTheBearerIsRefusedAndOnlyTheFullTokenPasses() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("guarded")
                .mining().blockInterval(150).apiToken(TOKEN).start();
            int port = node.apiPort();

            for (int len = 0; len < TOKEN.length(); len++) {
                String prefix = TOKEN.substring(0, len);
                assertEquals(401, RawHttp.post(port, "/add_transaction",
                        Map.of("Authorization", "Bearer " + prefix), json()).status(),
                    "a " + len + "-byte prefix of the " + TOKEN.length()
                        + "-byte token must be refused, not partially accepted");
            }
            assertEquals(401, RawHttp.post(port, "/add_transaction",
                    Map.of("Authorization", "Bearer " + TOKEN + "x"), json()).status(),
                "a token one byte too long must also be refused");

            int prefixAuthorized = RawHttp.post(port, "/add_transaction",
                Map.of("Authorization", "Bearer " + TOKEN), json()).status();
            assertNotEquals(401, prefixAuthorized, "the exact, full-length token must still pass the gate");

            assertStillProducing(node);
        }
    }

    /**
     * E2E-06 — a classic cross-site POST from a page the operator visited. The browser attaches
     * {@code Origin} automatically and cannot attach the custom marker without a preflight the
     * node never answers, so the request is refused.
     */
    @Test
    void aCrossOriginBrowserPostIsRefused() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("csrf").mining().blockInterval(150).start();
            int port = node.apiPort();

            assertEquals(403, RawHttp.post(port, "/add_transaction",
                    Map.of("Origin", "http://evil.example"), json()).status(),
                "a browser POST from another origin must be refused");

            // Same-origin but still without the marker: a rebound page is same-origin by
            // construction, so Origin alone can never be the control.
            assertEquals(403, RawHttp.post(port, "/add_transaction",
                    Map.of("Origin", "http://127.0.0.1:" + port), json()).status(),
                "same-origin alone is not enough — the non-simple marker header is required");

            int withMarker = RawHttp.post(port, "/add_transaction",
                Map.of("Origin", "http://127.0.0.1:" + port, "X-Rhizome-Request", "1"), json()).status();
            assertNotEquals(403, withMarker,
                "same-origin plus the marker is the node's own dashboard, which must work");

            assertStillProducing(node);
        }
    }

    /**
     * E2E-07 — DNS rebinding. The attacker's page resolves its own hostname to the node's address,
     * so {@code Origin} and {@code Host} agree and the same-origin check passes; the request still
     * carries the attacker's hostname, which the Host allowlist does not contain.
     */
    @Test
    void aRebindingPostThatLooksSameOriginIsRefusedByTheHostAllowlist() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("rebind").mining().blockInterval(150).start();
            int port = node.apiPort();

            var refused = RawHttp.post(port, "/add_transaction",
                Map.of("Host", "attacker.example",
                       "Origin", "http://attacker.example",
                       "X-Rhizome-Request", "1"),
                json());
            assertEquals(403, refused.status(),
                "the rebound hostname is not an authority this node answers for");

            assertStillProducing(node);
        }
    }

    /**
     * E2E-08 — a client that drives expensive reads is shed with 429 rather than being allowed to
     * contend with block production for the consensus lock. The scan endpoints are weighted by the
     * blocks they actually decode, so the budget goes in a handful of requests.
     */
    @Test
    void anExpensiveReadFloodIsShedWith429AndTheNodeKeepsProducing() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("flood").mining().blockInterval(150).start();
            int port = node.apiPort();
            TestNetwork.awaitHeight(node, 3);

            String path = "/transaction?txid="
                + "00".repeat(32) + "&depth=500"; // a txid that will never match: the full scan runs
            boolean shed = false;
            for (int i = 0; i < 60 && !shed; i++) {
                shed = RawHttp.get(port, path, Map.of()).status() == 429;
            }
            assertTrue(shed, "an expensive read flood must eventually be rate limited");

            assertStillProducing(node);
        }
    }

    /**
     * E2E-09 — malformed and hostile inputs across the surface. The contract is that every one of
     * them produces an HTTP status rather than a crash, and that the node is still mining after.
     */
    @Test
    void malformedInputIsAlwaysAnswereWithAStatusAndNeverKillsTheNode() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("junk").mining().blockInterval(150).start();
            int port = node.apiPort();

            record Probe(String method, String path, byte[] body) {}
            var probes = java.util.List.of(
                new Probe("GET", "/block?blockId=-1", new byte[0]),
                new Probe("GET", "/block?blockId=99999999999999999999", new byte[0]),
                new Probe("GET", "/block?blockId=notanumber", new byte[0]),
                new Probe("GET", "/blocks?start=0&end=999999999", new byte[0]),
                new Probe("GET", "/sync?start=-5&end=-1", new byte[0]),
                new Probe("GET", "/wallet?address=zzzz", new byte[0]),
                new Probe("GET", "/token_balance?tokenId=nothex", new byte[0]),
                new Probe("GET", "/does-not-exist", new byte[0]),
                new Probe("POST", "/add_transaction", "not json at all".getBytes(StandardCharsets.UTF_8)),
                new Probe("POST", "/submit", new byte[] {0x00, (byte) 0xFF, 0x10, 0x7F}),
                new Probe("POST", "/call_readonly", "{\"broken\":".getBytes(StandardCharsets.UTF_8)));

            for (Probe probe : probes) {
                var response = RawHttp.send(port, probe.method(), probe.path(), Map.of(), probe.body());
                assertTrue(response.status() >= 200 && response.status() < 600,
                    probe.method() + " " + probe.path() + " produced no valid status");
                assertNotEquals(500, response.status(),
                    probe.method() + " " + probe.path() + " reached an unhandled failure");
            }

            assertStillProducing(node);
        }
    }

    /**
     * E2E-10 — a slow-loris client announces a body and never sends it. The node must reclaim the
     * exchange on its inactivity deadline instead of holding the single event-loop thread, and must
     * still be serving and producing while the stalled socket sits there.
     */
    @Test
    void aStalledClientDoesNotHoldTheNodeHostage() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("loris").mining().blockInterval(150).start();
            int port = node.apiPort();

            try (var stalled = RawHttp.startAndStall(port, "/add_transaction", 4096)) {
                // While that socket sits half-open, an ordinary client must still be served...
                assertEquals(200, RawHttp.get(port, "/block_count", Map.of()).status(),
                    "a stalled exchange must not block the event loop");
                // ...and the producer must still be running.
                assertStillProducing(node);
            }
        }
    }
}
