package rhizome.node;

import rhizome.net.RateLimiter;

import java.nio.charset.StandardCharsets;

import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.RoutingServlet;
import io.activej.reactor.Reactor;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.common.Constants;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.dto.TransactionDto;

import static io.activej.http.HttpMethod.GET;
import static io.activej.http.HttpMethod.POST;
import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.guarded;
import static rhizome.node.ApiResponses.guardedResponse;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.notFound;
import static rhizome.node.ApiResponses.ok;
import static rhizome.node.ApiResponses.statusResponse;
import static rhizome.node.ApiResponses.text;

/**
 * HTTP API of the node, over ActiveJ HTTP: the routing table plus the
 * cross-cutting middleware (per-client rate limiting, aggregate read budgets,
 * the browser CSRF/rebinding guard and last-resort error mapping). The
 * handlers themselves live in the per-domain classes: {@link DashboardApi},
 * {@link ExplorerApi}, {@link SyncApi}, {@link BoxApi}, {@link TokenApi},
 * {@link StateApi} and {@link ContractApi}.
 *
 * <p>Robustness rules learned from Pandanite (§4.10 of the analysis): every
 * handler always produces a response (bad input → 400, never a crash or a hung
 * connection), range endpoints are hard-bounded before any work, and binary
 * payloads use the fixed-layout codec.
 */
public final class NodeApi {

    private static final Logger log = LoggerFactory.getLogger(NodeApi.class);

    private static final int SMALL_BODY = 8 * 1024;                 // tx / peer announcements
    // A single transaction body may be a contract deploy/call carrying up to MAX_DATA
    // bytes of payload (plus the kind tag and gas fields), so size the cap for that.
    private static final int TX_BODY = TransactionDto.BUFFER_SIZE + 1 + 20 + TransactionDto.MAX_DATA + 1024;

    // Well-known ActiveJ header tokens: the HTTP parser interns incoming Origin/Host under these, and
    // a custom HttpHeaders.of("Origin"/"Host") token no longer matches them (it did in 6.0-beta2, but
    // 6.0-rc2 tightened the lookup) — so reading the CSRF/rebinding guard's Origin/Host through of(...)
    // silently returned null and fail-opened. Use the interned constants so the guard sees the values.
    private static final HttpHeader H_ORIGIN = HttpHeaders.ORIGIN;
    private static final HttpHeader H_HOST = HttpHeaders.HOST;
    /** Non-simple header the dashboard sends on every state-changing POST; forces a CORS preflight
     *  a cross-site/rebinding page cannot satisfy, so its POST is blocked by the browser. */
    private static final HttpHeader H_RZ_REQUEST = HttpHeaders.of("X-Rhizome-Request");

    /** Rate-limit cost of a /call_readonly, which runs the VM up to its readonly gas cap. */
    private static final int CALL_READONLY_COST = 25;
    /**
     * Rate-limit cost of a /submit. Accepting a block runs consensus validation and, for a
     * plausible recent sibling, one memory-hard Pufferfish2 hash (registerOrphan) — far dearer than
     * a flat read. Weighted so a single IP cannot drive thousands of block validations/hashes per
     * window (audit H3); still generous for honest block propagation (~1 block / few seconds).
     */
    private static final int SUBMIT_COST = 8;

    private NodeApi() {}

    /** Servlet with a default, lenient rate limiter (for tests and simple embeds). */
    public static AsyncServlet servlet(Reactor reactor, NodeService node) {
        return servlet(reactor, node, new RateLimiter(1000, 1000, 8192));
    }

    /**
     * Node servlet wrapped with a per-client rate limiter (429 over the limit)
     * and per-endpoint request-body size caps (memory-bounded parsing), on top
     * of the always-responds robustness rules.
     */
    public static AsyncServlet servlet(Reactor reactor, NodeService node, RateLimiter limiter) {
        return servlet(reactor, node, limiter, null);
    }

    /** As above, with an optional SSE hub backing {@code GET /logs/stream}. */
    public static AsyncServlet servlet(Reactor reactor, NodeService node, RateLimiter limiter, SseLogHub sse) {
        int maxBlockBody = node.params().maxBlockSizeBytes() + 1024;
        DashboardAssets dashboard = DashboardAssets.load();

        RoutingServlet routing = RoutingServlet.builder(reactor)
            // ---- embedded dashboard SPA ----
            .with(GET, "/", req -> ok(DashboardApi.asset(dashboard.index())))
            .with(GET, "/dashboard", req -> ok(DashboardApi.asset(dashboard.index())))
            .with(GET, "/dashboard/*", req -> guarded(() -> {
                DashboardAssets.Asset a = dashboard.get(req.getRelativePath());
                return a == null ? notFound("no such asset") : DashboardApi.asset(a);
            }))
            // ---- dashboard/explorer API ----
            .with(GET, "/stats", req -> guarded(() -> DashboardApi.stats(node)))
            .with(GET, "/features", req -> guarded(() -> DashboardApi.features(node, sse)))
            .with(GET, "/blocks", req -> guarded(() -> ExplorerApi.blocks(node, req)))
            .with(GET, "/block", req -> guarded(() -> ExplorerApi.block(node, req)))
            .with(GET, "/transaction", req -> guarded(() -> ExplorerApi.findTransaction(node, req)))
            .with(GET, "/address_txs", req -> guarded(() -> ExplorerApi.addressTransactions(node, req)))
            .with(GET, "/contract", req -> guarded(() -> ExplorerApi.contractInfo(node, req)))
            .with(GET, "/wallet", req -> guarded(() -> ExplorerApi.wallet(node, req)))
            // ---- chain scalars ----
            .with(GET, "/block_count", req -> ok(text(String.valueOf(node.blockCount()))))
            .with(GET, "/total_work", req -> ok(json(new JSONObject().put("totalWork", node.totalWork().toString()))))
            .with(GET, "/difficulty", req -> ok(text(String.valueOf(node.difficulty()))))
            .with(GET, "/mempool", req -> ok(json(new JSONObject().put("size", node.mempoolSize()))))
            .with(GET, "/info", req -> ok(json(new JSONObject()
                .put("chainId", node.chainId())
                .put("network", node.networkName())
                .put("height", node.blockCount())
                .put("difficulty", node.difficulty())
                .put("mempool", node.mempoolSize())
                .put("prunedBelow", node.prunedBelow())
                .put("snapshotPivot", node.snapshotPivot())
                .put("storageFeeFactor", node.voteableParams()[0])
                .put("minValuePerByte", node.voteableParams()[1]))))
            // ---- peer registry ----
            .with(GET, "/peers", req -> ok(json(new JSONObject()
                .put("peers", new org.json.JSONArray(node.knownPeers())))))
            .with(POST, "/add_peer", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                String url = new JSONObject(body.getString(StandardCharsets.UTF_8)).getString("url");
                node.addPeer(url);
                return json(new JSONObject().put("status", "OK"));
            })))
            // ---- boxes / scans ----
            .with(GET, "/box", req -> guarded(() -> BoxApi.box(node, req)))
            .with(GET, "/boxes", req -> guarded(() -> BoxApi.boxes(node, req)))
            .with(POST, "/scan/register", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                int id = node.registerScan(rhizome.core.box.ScanPredicate.fromJson(
                    new JSONObject(body.getString(StandardCharsets.UTF_8))));
                return json(new JSONObject().put("scanId", id));
            })))
            .with(POST, "/scan/deregister", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                int id = new JSONObject(body.getString(StandardCharsets.UTF_8)).getInt("scanId");
                return json(new JSONObject().put("removed", node.deregisterScan(id)));
            })))
            .with(GET, "/scan/list", req -> guarded(() -> BoxApi.scanList(node)))
            .with(GET, "/scan/boxes", req -> guarded(() -> BoxApi.scanBoxes(node, req)))
            // ---- tokens ----
            .with(GET, "/token", req -> guarded(() -> TokenApi.token(node, req)))
            .with(GET, "/token_balance", req -> guarded(() -> TokenApi.tokenBalance(node, req)))
            .with(GET, "/tokens", req -> guarded(() -> TokenApi.tokens(node, req)))
            // ---- authenticated state ----
            .with(GET, "/state", req -> guarded(() -> StateApi.state(node)))
            .with(GET, "/state/proof", req -> guarded(() -> StateApi.stateProof(node, req)))
            .with(GET, "/state/snapshot/info", req -> guarded(() -> SyncApi.snapshotInfo(node)))
            .with(GET, "/state/snapshot/chunk", req -> guarded(() -> SyncApi.snapshotChunk(node, req)))
            // ---- contract logs / dry run ----
            .with(GET, "/logs", req -> guarded(() -> ContractApi.logs(node, req)))
            .with(GET, "/logs/stream", req -> guarded(() -> ContractApi.logStream(sse, clientKey(req))))
            .with(POST, "/call_readonly", req -> req.loadBody(TX_BODY).map(body -> guardedResponse(() ->
                ContractApi.callReadonly(node, new JSONObject(body.getString(StandardCharsets.UTF_8))))))
            // ---- peer sync / gossip ingest ----
            .with(GET, "/sync", req -> guarded(() -> SyncApi.sync(node, req)))
            .with(GET, "/headers", req -> guarded(() -> SyncApi.headers(node, req)))
            .with(POST, "/add_transaction_json", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                Transaction t = Transaction.of(new JSONObject(body.getString(StandardCharsets.UTF_8)));
                return statusResponse(node.submitTransaction(t));
            })))
            .with(POST, "/add_transaction", req -> req.loadBody(TX_BODY).map(body -> guardedResponse(() -> {
                Transaction t = Transaction.of(BinarySerializable.fromBuffer(body.getArray(), TransactionDto.class));
                return statusResponse(node.submitTransaction(t));
            })))
            .with(POST, "/submit", req -> req.loadBody(maxBlockBody).map(body -> guardedResponse(() -> {
                Block block = BlockCodec.decode(body.getArray());
                return statusResponse(node.submitBlock(block));
            })))
            .build();

        return request -> {
            int cost = requestCost(request);
            if (!limiter.allow(clientKey(request), cost)) {
                return HttpResponse.ofCode(429)
                    .withJson(new JSONObject().put("error", "rate limited").toString())
                    .toPromise();
            }
            // Aggregate (all-IP) budget for the explorer reads that decode blocks under the consensus
            // lock: the per-IP limiter above cannot stop a distributed flood from summing past it, so a
            // process-wide bucket bounds the total lock-guarded decode work on the event-loop thread
            // (audit 5th-pass, net Finding 2). Shed over-budget reads before they touch the store.
            if (isConsensusLockRead(request) && !node.tryReadBudget(cost)) {
                return HttpResponse.ofCode(429)
                    .withJson(new JSONObject().put("error", "read budget exceeded").toString())
                    .toPromise();
            }
            // CSRF / DNS-rebinding guard on state-changing requests. A browser attaches an Origin
            // header on every POST, so any POST carrying an Origin is browser-originated. Such a
            // request is refused unless it is (a) same-origin AND (b) carries the non-simple
            // X-Rhizome-Request header. (a) blocks classic cross-site POSTs; (b) blocks DNS
            // rebinding, which defeats (a) by making Origin==Host — a rebinding page cannot set a
            // custom header without a CORS preflight this node never grants (no Access-Control-*
            // response), so the browser blocks the request. Peer and CLI clients send no Origin and
            // are unaffected, so P2P submit/gossip keeps working.
            if (request.getMethod() == POST && isForbiddenBrowserPost(request)) {
                return HttpResponse.ofCode(403)
                    .withJson(new JSONObject().put("error", "cross-origin request refused").toString())
                    .toPromise();
            }
            // Convert any handler failure (incl. body-size overflow) into a clean, generic
            // response; the detail is logged server-side only, never reflected to the client
            // (audit L3 — reflected exception text leaks internal detail for reconnaissance).
            return routing.serve(request).map(r -> r, e -> {
                log.debug("request handling failed: {}", e.toString());
                return badRequest("bad request");
            });
        };
    }

    // ---- middleware: cost model, budgets and the browser guard ----

    /**
     * True when a browser-originated POST must be refused. A request carrying an {@code Origin} is
     * browser-originated (browsers attach Origin to every POST); it is allowed only when it is
     * same-origin ({@code Origin} authority == {@code Host}) AND carries the {@code X-Rhizome-Request}
     * header. The same-origin check stops classic cross-site POSTs; the custom-header requirement
     * stops DNS rebinding (which makes Origin==Host) because a rebinding page cannot set a non-simple
     * header without a CORS preflight this node never grants. Requests with no {@code Origin}
     * (peers, CLI — not browsers, so not a CSRF vector) are always allowed. Fails open only when a
     * present {@code Origin}/{@code Host} is unparseable.
     */
    private static boolean isForbiddenBrowserPost(HttpRequest request) {
        try {
            String origin = request.getHeader(H_ORIGIN);
            if (origin == null || origin.isEmpty()) {
                return false; // not a browser request
            }
            String host = request.getHeader(H_HOST);
            if (host == null || host.isEmpty()) {
                return false;
            }
            String authority = java.net.URI.create(origin).getAuthority();
            if (authority == null || !authority.equalsIgnoreCase(host)) {
                return true; // cross-site
            }
            // Same-origin (or rebound to look same-origin): require the custom header.
            String marker = request.getHeader(H_RZ_REQUEST);
            return marker == null || marker.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Rate-limit cost of a request. Cheap endpoints cost 1; the deep chain scans and the VM
     * dry-run cost proportionally more so a single client cannot drive orders of magnitude more
     * work than the flat per-request budget implies (audit M2).
     */
    private static int requestCost(HttpRequest request) {
        String path;
        try {
            path = request.getPath();
        } catch (RuntimeException e) {
            return 1;
        }
        if ("/transaction".equals(path) || "/address_txs".equals(path)) {
            int depth = ExplorerApi.SCAN_DEPTH_DEFAULT;
            try {
                String d = request.getQueryParameter("depth");
                if (d != null && !d.isEmpty()) {
                    depth = Integer.parseInt(d.trim());
                }
            } catch (RuntimeException ignored) {
                // malformed depth: the handler will reject it; charge the default cost
            }
            depth = Math.max(1, Math.min(depth, ExplorerApi.SCAN_DEPTH_MAX));
            return Math.max(1, depth / ExplorerApi.SCAN_COST_PER_BLOCKS);
        }
        if ("/call_readonly".equals(path)) {
            return CALL_READONLY_COST;
        }
        if ("/submit".equals(path)) {
            return SUBMIT_COST;
        }
        // /sync and /headers were left at cost 1 by the M2 fix, yet they are the heaviest read
        // endpoints: /sync reads and buffers up to BLOCKS_PER_FETCH full blocks (each up to
        // MAX_BLOCK_SIZE) and /headers does up to BLOCK_HEADERS_PER_FETCH block reads. Weight both
        // by their requested range so one IP cannot drive hundreds of full-block reads per token
        // (audit: unweighted amplification on the block-serving paths).
        if ("/sync".equals(path)) {
            return rangeCost(request, 1, Constants.BLOCKS_PER_FETCH); // full-block reads: ~1 per block
        }
        if ("/headers".equals(path)) {
            return rangeCost(request, ExplorerApi.SCAN_COST_PER_BLOCKS, Constants.BLOCK_HEADERS_PER_FETCH);
        }
        // The explorer read endpoints also fully decode blocks from RocksDB under the consensus lock
        // (ChainEngine.blockAt), yet were left at cost 1 by the M2 weighting pass. /blocks serves up to
        // BLOCKS_RANGE_MAX full blocks and /stats reads STATS_WINDOW of them per call, so at cost 1 one
        // IP could drive tens of thousands of lock-guarded block decodes/s, contending block production
        // and sync. Weight them by the blocks they actually read (audit 5th-pass, net Finding 2).
        if ("/blocks".equals(path)) {
            return rangeCost(request, 1, ExplorerApi.BLOCKS_RANGE_MAX); // full-block reads: ~1 unit per block
        }
        if ("/stats".equals(path)) {
            // STATS_WINDOW full-block decodes, each under the consensus lock — weight ~1 per block like
            // /sync and /blocks (NOT divided by SCAN_COST_PER_BLOCKS, which is the lighter header-scan
            // rate and rounds 32/20 down to 1, leaving /stats effectively unweighted).
            return DashboardApi.STATS_WINDOW;
        }
        return 1;
    }

    /**
     * The browser-facing explorer reads that fully decode blocks from RocksDB under the consensus lock
     * (ChainEngine.blockAt). These are additionally charged to the process-wide aggregate read budget
     * (NodeService.tryReadBudget) so a distributed flood can't sum past the per-IP limiter and pin the
     * event loop / contend the lock (audit 5th-pass, net Finding 2). The peer-sync paths /sync and
     * /headers are deliberately excluded — throttling them would slow honest chain sync.
     */
    private static boolean isConsensusLockRead(HttpRequest request) {
        String path;
        try {
            path = request.getPath();
        } catch (RuntimeException e) {
            return false;
        }
        return "/stats".equals(path) || "/blocks".equals(path) || "/block".equals(path)
            || "/transaction".equals(path) || "/address_txs".equals(path);
    }

    /**
     * Rate-limit cost of a start/end range request: its block span divided by {@code blocksPerUnit}.
     * A range that is missing, malformed, or larger than {@code maxRange} does no block-reading work
     * (the handler rejects it with 400 before touching the store), so it stays at the flat cost of 1
     * — only ranges the endpoint will actually serve are weighted by their span.
     */
    private static int rangeCost(HttpRequest request, int blocksPerUnit, int maxRange) {
        long start;
        long end;
        try {
            start = Long.parseLong(request.getQueryParameter("start").trim());
            end = Long.parseLong(request.getQueryParameter("end").trim());
        } catch (RuntimeException e) {
            return 1; // malformed/missing: the handler rejects it, charge the default
        }
        long range = end - start + 1;
        if (range < 1 || range > maxRange) {
            return 1; // out of range: rejected cheaply, no store reads
        }
        return (int) Math.max(1, range / blocksPerUnit);
    }

    private static String clientKey(HttpRequest request) {
        // getRemoteAddress() runs a checkNotNull under ActiveJ's CHECKS flag, so a
        // request with no live connection (tests, in-process calls) throws instead
        // of returning null; treat any such request as a single "local" bucket.
        try {
            java.net.InetAddress addr = request.getRemoteAddress();
            if (addr == null) {
                return "local";
            }
            byte[] b = addr.getAddress();
            if (b.length == 16) {
                // Key IPv6 clients by their /64 prefix: a single allocation hands out 2^64
                // addresses, so keying by the full address would let one host spray the
                // client table and (pre-fail-closed) evade the limiter (audit M1).
                StringBuilder sb = new StringBuilder("v6:");
                for (int i = 0; i < 8; i++) {
                    sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16)).append(Character.forDigit(b[i] & 0xF, 16));
                }
                return sb.toString();
            }
            return addr.getHostAddress();
        } catch (RuntimeException e) {
            return "local";
        }
    }
}
