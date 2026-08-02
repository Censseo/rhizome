package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpHeaders;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.mempool.MemPool;
import rhizome.crypto.PowAlgorithm;

/**
 * The two RHIZOME_* hardening switches (audit 17th pass):
 *
 * <ul>
 *   <li><b>RHIZOME_TRUST_XFF</b> — the forwarded hop is accepted only as a real IP literal.
 *       A dotted quad with an overflowing octet ({@code 300.1.1.1}) is NOT an IPv4 literal:
 *       handing it to {@code InetAddress.getByName} fell back to a blocking DNS lookup of it
 *       as a hostname — seconds of event-loop stall per spoofed request. The hop is now
 *       parsed octet-by-octet and built via {@code getByAddress}, which never resolves.</li>
 *   <li><b>RHIZOME_PROTECT_READS</b> — every route requires the bearer EXCEPT the static
 *       SPA/docs shell: a browser cannot attach a token to a plain navigation, so gating
 *       the shell made the embedded explorer unreachable.</li>
 * </ul>
 */
class NodeApiHardeningFlagsTest {

    private Eventloop eventloop;
    private Thread eventloopThread;
    private AsyncServlet privateNode;

    @BeforeEach
    void setUp() {
        var params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        eventloop = Eventloop.create();
        var engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            new LedgerSnapshot("test", 0, params.chainId()), null, new AtomicLong(0)::get,
            new SignatureVerifier());
        var node = new NodeService(engine, new MemPool(params, new SignatureVerifier(), engine, 100));
        // apiToken + protectReads=true + trustXff=false: the private-node configuration.
        privateNode = NodeApi.servlet(eventloop, node, new rhizome.net.RateLimiter(1000, 1000, 8192),
            null, null, "s3cret", null, true, false);
        eventloop.keepAlive(true);
        eventloopThread = new Thread(eventloop, "test-eventloop");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        eventloop.execute(eventloop::breakEventloop);
        eventloopThread.join(2000);
    }

    private HttpResponse call(HttpRequest request) throws Exception {
        return eventloop.<HttpResponse>submit(() ->
            privateNode.serve(request).then(resp -> resp.loadBody().map($ -> resp))
        ).get();
    }

    // ---- XFF literal parsing ----

    @Test
    void canonicalDottedQuadsParse() {
        assertArrayEquals(new byte[] {1, 2, 3, 4}, NodeApi.parseIpv4Literal("1.2.3.4"));
        assertArrayEquals(new byte[] {0, 0, 0, 0}, NodeApi.parseIpv4Literal("0.0.0.0"));
        assertArrayEquals(new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255},
            NodeApi.parseIpv4Literal("255.255.255.255"));
    }

    @Test
    void overflowingOctetsAreRejectedSoGetByNameCanNeverDnsResolveThem() {
        // Each of these passed the old \d{1,3}(\.\d{1,3}){3} regex and went to getByName,
        // which treated them as HOSTNAMES — a blocking resolver lookup on the event loop.
        assertNull(NodeApi.parseIpv4Literal("300.1.1.1"));
        assertNull(NodeApi.parseIpv4Literal("999.999.999.999"));
        assertNull(NodeApi.parseIpv4Literal("1.2.3.256"));
    }

    @Test
    void malformedQuadsAreRejected() {
        assertNull(NodeApi.parseIpv4Literal(""));
        assertNull(NodeApi.parseIpv4Literal("1.2.3"));
        assertNull(NodeApi.parseIpv4Literal("1.2.3.4.5"));
        assertNull(NodeApi.parseIpv4Literal("1.2.3."));
        assertNull(NodeApi.parseIpv4Literal("1..2.3"));
        assertNull(NodeApi.parseIpv4Literal("1234.1.1.1"));
        assertNull(NodeApi.parseIpv4Literal("a.b.c.d"));
        assertNull(NodeApi.parseIpv4Literal("1.2.3.4 "));
    }

    @Test
    void xffHopResolutionUsesOnlyTheParsedLiteralNeverTheResolver() throws Exception {
        var socket = java.net.InetAddress.getByAddress(new byte[] {10, 0, 0, 1});

        // A syntactically valid hop keys on the literal; the first hop of a list wins.
        assertEquals("1.2.3.4",
            NodeApi.resolveXffHop(socket, "1.2.3.4").getHostAddress());
        assertEquals("9.9.9.9",
            NodeApi.resolveXffHop(socket, "9.9.9.9, 1.2.3.4").getHostAddress());
        assertEquals("0:0:0:0:0:0:0:1",
            NodeApi.resolveXffHop(socket, "::1").getHostAddress());

        // Everything else falls back to the socket address WITHOUT touching DNS: an overflowing
        // octet is not an IPv4 literal (getByName would have resolved it as a hostname — the
        // blocking-lookup DoS this guards against), and a hostname hop is never resolved.
        assertSame(socket, NodeApi.resolveXffHop(socket, "300.1.1.1"));
        assertSame(socket, NodeApi.resolveXffHop(socket, "999.999.999.999"));
        assertSame(socket, NodeApi.resolveXffHop(socket, "attacker.example.com"));
        assertSame(socket, NodeApi.resolveXffHop(socket, null));
    }

    // ---- protectReads SPA-shell exemption ----

    @Test
    void protectReadsGatesEveryDataRouteBehindTheBearer() throws Exception {
        assertEquals(401, call(HttpRequest.get("http://x/stats").build()).getCode());
        assertEquals(401, call(HttpRequest.get("http://x/block_count").build()).getCode());
        assertEquals(401, call(HttpRequest.get("http://x/peers").build()).getCode());
        assertEquals(200, call(HttpRequest.get("http://x/stats")
            .withHeader(HttpHeaders.AUTHORIZATION, "Bearer s3cret").build()).getCode());
        assertEquals(200, call(HttpRequest.get("http://x/block_count")
            .withHeader(HttpHeaders.AUTHORIZATION, "Bearer s3cret").build()).getCode());
    }

    @Test
    void protectReadsLeavesTheStaticSpaShellReachable() throws Exception {
        // A plain browser navigation cannot carry a bearer: gating the shell made the
        // "private explorer" impossible to load (audit 17th pass). The shell is static
        // content; the SPA's own API fetches carry the token (asserted above).
        assertEquals(200, call(HttpRequest.get("http://x/").build()).getCode());
        assertEquals(200, call(HttpRequest.get("http://x/dashboard").build()).getCode());
        assertEquals(200, call(HttpRequest.get("http://x/dashboard/app.js").build()).getCode());
        assertEquals(200, call(HttpRequest.get("http://x/docs/manifest.json").build()).getCode());
    }
}
