package rhizome.node;

import io.activej.eventloop.Eventloop;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.mempool.MemPool;
import rhizome.crypto.PowAlgorithm;
import rhizome.net.RateLimiter;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The middleware must classify a request against the path the ROUTER dispatches on, not against the
 * raw {@code HttpRequest.getPath()} slice.
 *
 * <p>ActiveJ routes on percent-DECODED segments and stops at the first EMPTY segment
 * ({@code UrlParser.pollUrlPart} feeding {@code RoutingServlet.tryServe}), while {@code getPath()}
 * returns the undecoded bytes. Comparing that raw slice to route literals — which every policy
 * predicate in {@link NodeApi} used to do — meant {@code POST /submit/}, {@code /submit//x},
 * {@code /%73ubmit} and {@code /submit&x} all reached the block-ingest handler while failing
 * {@code "/submit".equals(path)}: no bearer gate, no push shed, no aggregate submit budget, and a
 * request cost of 1 instead of 8. One trailing slash was an unauthenticated ingest bypass.
 *
 * <p>These tests drive the real servlet, so they fail if {@link NodeApi#routingKey} ever stops
 * agreeing with the router rather than merely if the helper changes.
 */
class RoutePathNormalizationTest {

    /** Every spelling below reaches the SAME handler as the bare path — verified by the router itself. */
    private static final String[] SUBMIT_ALIASES = {
        "/submit", "/submit/", "/submit//", "/submit//x", "/%73ubmit", "/%73%75bmit", "/submit&x",
    };

    private Eventloop eventloop;
    private Thread eventloopThread;
    private NodeService node;

    @BeforeEach
    void setUp() {
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        eventloop = Eventloop.create();
        AtomicLong clock = new AtomicLong(0);

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        var verifier = new SignatureVerifier();
        ChainEngine engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, clock::get, verifier);
        node = new NodeService(engine, new MemPool(params, verifier, engine, 1000));

        eventloop.keepAlive(true);
        eventloopThread = new Thread(eventloop, "test-eventloop");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        eventloop.keepAlive(false);
        eventloop.execute(eventloop::breakEventloop);
        eventloopThread.join(2000);
    }

    private HttpResponse call(io.activej.http.AsyncServlet servlet, HttpRequest request) throws Exception {
        return eventloop.<HttpResponse>submit(() ->
            servlet.serve(request).then(resp -> resp.loadBody().map($ -> resp))
        ).get();
    }

    /** A token-gated node, protectReads off — the documented default posture for a private operator. */
    private io.activej.http.AsyncServlet tokenGated() {
        return NodeApi.servlet(eventloop, node, new RateLimiter(1000, 1000, 8192),
            null, null, "s3cret", null, false, false);
    }

    @Test
    void everySpellingThatReachesTheIngestHandlerIsBearerGated() throws Exception {
        var servlet = tokenGated();
        for (String alias : SUBMIT_ALIASES) {
            assertEquals(401, call(servlet, HttpRequest.post("http://x" + alias).build()).getCode(),
                alias + " reaches the /submit handler, so it must be bearer-gated like /submit");
        }
    }

    @Test
    void everySpellingThatReachesTheIngestHandlerCarriesTheSubmitCost() {
        for (String alias : SUBMIT_ALIASES) {
            assertEquals(8, NodeApi.requestCost(HttpRequest.post("http://x" + alias).build()),
                alias + " does the same block decode as /submit, so it must cost the same");
        }
    }

    @Test
    void readCostsSurviveTrailingSlashesAndPercentEncoding() {
        // /stats decodes STATS_WINDOW full blocks under the consensus lock; at cost 1 these
        // spellings were 32x cheaper than the bare path for identical work.
        assertEquals(DashboardApi.STATS_WINDOW, NodeApi.requestCost(HttpRequest.get("http://x/stats").build()));
        assertEquals(DashboardApi.STATS_WINDOW, NodeApi.requestCost(HttpRequest.get("http://x/stats/").build()));
        assertEquals(DashboardApi.STATS_WINDOW, NodeApi.requestCost(HttpRequest.get("http://x/%73tats").build()));
    }

    @Test
    void routingKeyReproducesTheRoutersOwnSegmentWalk() {
        assertEquals("/", NodeApi.routingKey(HttpRequest.get("http://x/").build()));
        assertEquals("/submit", NodeApi.routingKey(HttpRequest.get("http://x/submit").build()));
        // An empty segment ends the walk: the router serves at the node reached so far.
        assertEquals("/submit", NodeApi.routingKey(HttpRequest.get("http://x/submit/").build()));
        assertEquals("/submit", NodeApi.routingKey(HttpRequest.get("http://x/submit//x").build()));
        // Percent-decoding, '+' as space, and '&'/'#' terminating a segment — all mirrored from UrlParser.
        assertEquals("/submit", NodeApi.routingKey(HttpRequest.get("http://x/%73ubmit").build()));
        assertEquals("/submit", NodeApi.routingKey(HttpRequest.get("http://x/submit&anything").build()));
        // Deeper paths keep every non-empty segment, so the wildcard trees still classify.
        assertEquals("/dashboard/app.js", NodeApi.routingKey(HttpRequest.get("http://x/dashboard/app.js").build()));
        assertEquals("/state/snapshot/chunk",
            NodeApi.routingKey(HttpRequest.get("http://x/state/snapshot/chunk?index=0").build()));
        // A leading empty segment matches nothing — the router 404s it, and so must we.
        assertEquals("/", NodeApi.routingKey(HttpRequest.get("http://x//submit").build()));
        // Bad percent-encoding: the router answers 400 before any handler, so there is no route.
        assertNull(NodeApi.routingKey(HttpRequest.get("http://x/%zz").build()));
        assertNull(NodeApi.routingKey(HttpRequest.get("http://x/sub%4").build()));
    }

    @Test
    void aPathTheRouterDoesNotDispatchIsNotClassifiedAsItsPrefix() throws Exception {
        // "/submit/x" has a non-empty trailing segment, so the router finds no child and no
        // fallback under /submit: it must NOT inherit /submit's classification.
        assertEquals("/submit/x", NodeApi.routingKey(HttpRequest.post("http://x/submit/x").build()));
        assertEquals(1, NodeApi.requestCost(HttpRequest.post("http://x/submit/x").build()));
        // 400, not 401: it never reaches the bearer gate as /submit, and the middleware maps every
        // router failure (404 included) to one generic bad-request body so nothing is reflected back.
        assertEquals(400, call(tokenGated(), HttpRequest.post("http://x/submit/x").build()).getCode());
    }

    @Test
    void theStaticShellStaysReachableUnderProtectReads() throws Exception {
        var privateNode = NodeApi.servlet(eventloop, node, new RateLimiter(1000, 1000, 8192),
            null, null, "s3cret", null, true, false);
        // A browser cannot attach a bearer to a plain navigation, so the static shell must stay open.
        // /docs and /dashboard reach the same wildcard handlers as /docs/ and /dashboard/ do.
        for (String shell : new String[] {"/", "/dashboard", "/dashboard/", "/dashboard/app.js",
                                          "/docs/manifest.json"}) {
            assertEquals(200, call(privateNode, HttpRequest.get("http://x" + shell).build()).getCode(),
                shell + " is static shell content and must not be bearer-gated");
        }
        // The bare wildcard bases reach the same static handlers, so they are shell too: the asset
        // lookup answers 404 for an empty asset name, but the bearer gate must not answer 401 first.
        for (String base : new String[] {"/docs", "/docs/", "/dashboard/nope.js"}) {
            assertEquals(404, call(privateNode, HttpRequest.get("http://x" + base).build()).getCode(),
                base + " must fall through to the static handler, not the bearer gate");
        }
        // …while chain data stays gated, including through an aliased spelling.
        assertEquals(401, call(privateNode, HttpRequest.get("http://x/stats").build()).getCode());
        assertEquals(401, call(privateNode, HttpRequest.get("http://x/stats/").build()).getCode());
    }
}
