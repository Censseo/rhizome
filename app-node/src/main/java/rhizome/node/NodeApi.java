package rhizome.node;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.RoutingServlet;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.dto.BlockDto;
import rhizome.core.common.Constants;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.dto.TransactionDto;

import static io.activej.http.HttpMethod.GET;
import static io.activej.http.HttpMethod.POST;

/**
 * HTTP API of the node, over ActiveJ HTTP.
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

    /** Server-side ceiling on a read-only dry-run's gas: bounds free, unauthenticated VM compute. */
    private static final long MAX_READONLY_GAS = 50_000_000L;

    // Security headers for the browser-facing dashboard. The SPA loads only same-origin
    // scripts/styles (no inline <script>), talks only to its own node (fetch + SSE), and
    // uses inline style attributes — hence 'unsafe-inline' for style only. frame-ancestors
    // 'none' + X-Frame-Options DENY block clickjacking; a restrictive CSP contains any
    // injected markup and stops an attacker's inline script from reading wallet keys.
    private static final HttpHeader H_CSP = HttpHeaders.of("Content-Security-Policy");
    private static final HttpHeader H_XFO = HttpHeaders.of("X-Frame-Options");
    private static final HttpHeader H_XCTO = HttpHeaders.of("X-Content-Type-Options");
    private static final HttpHeader H_REFERRER = HttpHeaders.of("Referrer-Policy");
    private static final HttpHeader H_ORIGIN = HttpHeaders.of("Origin");
    private static final HttpHeader H_HOST = HttpHeaders.of("Host");
    private static final String DASHBOARD_CSP =
        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
        + "script-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

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
            .with(GET, "/", req -> ok(asset(dashboard.index())))
            .with(GET, "/dashboard", req -> ok(asset(dashboard.index())))
            .with(GET, "/dashboard/*", req -> guarded(() -> {
                DashboardAssets.Asset a = dashboard.get(req.getRelativePath());
                return a == null ? notFound("no such asset") : asset(a);
            }))
            // ---- dashboard/explorer API ----
            .with(GET, "/stats", req -> guarded(() -> stats(node)))
            .with(GET, "/features", req -> guarded(() -> features(node, sse)))
            .with(GET, "/blocks", req -> guarded(() -> blocks(node, req)))
            .with(GET, "/transaction", req -> guarded(() -> findTransaction(node, req)))
            .with(GET, "/address_txs", req -> guarded(() -> addressTransactions(node, req)))
            .with(GET, "/contract", req -> guarded(() -> contractInfo(node, req)))
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
            .with(GET, "/peers", req -> ok(json(new JSONObject()
                .put("peers", new org.json.JSONArray(node.knownPeers())))))
            .with(POST, "/add_peer", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                String url = new JSONObject(body.getString(StandardCharsets.UTF_8)).getString("url");
                node.addPeer(url);
                return json(new JSONObject().put("status", "OK"));
            })))
            .with(GET, "/block", req -> guarded(() -> {
                long id = parseLong(req.getQueryParameter("blockId"));
                if (id < 1 || id > node.blockCount()) {
                    return badRequest("blockId out of range");
                }
                return json(node.block(id).toJson());
            }))
            .with(GET, "/wallet", req -> guarded(() -> {
                PublicAddress wallet = PublicAddress.of(req.getQueryParameter("address"));
                return json(new JSONObject()
                    .put("address", wallet.toHexString())
                    .put("balance", node.balance(wallet))
                    .put("nextNonce", node.nextNonce(wallet)));
            }))
            .with(GET, "/box", req -> guarded(() -> box(node, req)))
            .with(GET, "/boxes", req -> guarded(() -> boxes(node, req)))
            .with(POST, "/scan/register", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                int id = node.registerScan(rhizome.core.box.ScanPredicate.fromJson(
                    new JSONObject(body.getString(StandardCharsets.UTF_8))));
                return json(new JSONObject().put("scanId", id));
            })))
            .with(POST, "/scan/deregister", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                int id = new JSONObject(body.getString(StandardCharsets.UTF_8)).getInt("scanId");
                return json(new JSONObject().put("removed", node.deregisterScan(id)));
            })))
            .with(GET, "/scan/list", req -> guarded(() -> scanList(node)))
            .with(GET, "/scan/boxes", req -> guarded(() -> scanBoxes(node, req)))
            .with(GET, "/token", req -> guarded(() -> token(node, req)))
            .with(GET, "/token_balance", req -> guarded(() -> tokenBalance(node, req)))
            .with(GET, "/tokens", req -> guarded(() -> tokens(node, req)))
            .with(GET, "/state", req -> guarded(() -> state(node)))
            .with(GET, "/state/proof", req -> guarded(() -> stateProof(node, req)))
            .with(GET, "/state/snapshot/info", req -> guarded(() -> snapshotInfo(node)))
            .with(GET, "/state/snapshot/chunk", req -> guarded(() -> snapshotChunk(node, req)))
            .with(GET, "/logs", req -> guarded(() -> logs(node, req)))
            .with(GET, "/logs/stream", req -> guarded(() -> logStream(sse)))
            .with(GET, "/sync", req -> guarded(() -> sync(node, req)))
            .with(GET, "/headers", req -> guarded(() -> headers(node, req)))
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
            .with(POST, "/call_readonly", req -> req.loadBody(TX_BODY).map(body -> guardedResponse(() ->
                callReadonly(node, new JSONObject(body.getString(StandardCharsets.UTF_8))))))
            .build();

        return request -> {
            if (!limiter.allow(clientKey(request), requestCost(request))) {
                return HttpResponse.ofCode(429)
                    .withJson(new JSONObject().put("error", "rate limited").toString())
                    .toPromise();
            }
            // CSRF / DNS-rebinding guard on state-changing requests: a browser attaches an
            // Origin header on cross-site POSTs, so reject any POST whose Origin is not this
            // node's own host. Peer and CLI clients send no Origin and are unaffected, so P2P
            // submit/gossip keeps working; the check fails open if headers can't be read, so
            // it can never block the dashboard's own same-origin requests.
            if (request.getMethod() == POST && isCrossOriginPost(request)) {
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

    // ---- dashboard/explorer handlers ----

    /** How many recent blocks the /stats aggregates cover (block time, tx rate). */
    private static final int STATS_WINDOW = 32;
    /** Default and maximum tip-backward scan depth for /transaction and /address_txs. */
    private static final int SCAN_DEPTH_DEFAULT = 250;
    private static final int SCAN_DEPTH_MAX = 1000;
    /** Rate-limit units per this many blocks scanned, so a deep scan draws proportionally more
     *  of the per-window budget than a flat 1 (audit M2). */
    private static final int SCAN_COST_PER_BLOCKS = 20;
    /** Rate-limit cost of a /call_readonly, which runs the VM up to MAX_READONLY_GAS. */
    private static final int CALL_READONLY_COST = 25;
    /** Result cap for /address_txs so a busy address cannot produce an unbounded body. */
    private static final int ADDRESS_TXS_MAX = 100;
    /** Block-range size cap for /blocks. */
    private static final int BLOCKS_RANGE_MAX = 50;

    /**
     * One-call network overview for the dashboard: chain identity, tip state and
     * aggregates over the last {@link #STATS_WINDOW} blocks (average block interval,
     * transaction count). Everything here is already public via other endpoints —
     * this only saves the UI a request storm.
     */
    private static HttpResponse stats(NodeService node) {
        long height = node.blockCount();
        var params = node.params();
        long windowStart = Math.max(1, height - STATS_WINDOW + 1);
        long txCount = 0;
        long firstTs = 0;
        long lastTs = 0;
        for (long h = windowStart; h <= height; h++) {
            var block = (rhizome.core.block.BlockImpl) node.block(h);
            txCount += block.transactions().size();
            if (h == windowStart) {
                firstTs = block.timestamp();
            }
            if (h == height) {
                lastTs = block.timestamp();
            }
        }
        long spanBlocks = height - windowStart;
        long avgIntervalMs = spanBlocks > 0 ? (lastTs - firstTs) / spanBlocks : 0;
        return json(new JSONObject()
            .put("chainId", node.chainId())
            .put("network", node.networkName())
            .put("height", height)
            .put("difficulty", node.difficulty())
            .put("totalWork", node.totalWork().toString())
            .put("mempool", node.mempoolSize())
            .put("peers", node.knownPeers().size())
            .put("desiredBlockTimeSec", params.desiredBlockTimeSec())
            .put("decimalScaleFactor", params.decimalScaleFactor())
            .put("miningReward", params.miningReward(height))
            .put("maxReorgDepth", params.maxReorgDepth())
            .put("lastBlockTimestamp", lastTs)
            .put("avgBlockIntervalMs", avgIntervalMs)
            .put("windowBlocks", height - windowStart + 1)
            .put("windowTxCount", txCount)
            // Box economics the UI needs to build BOX_* transactions client-side.
            .put("storagePeriodBlocks", params.storagePeriodBlocks())
            .put("storageFeeFactor", node.voteableParams()[0])
            .put("minValuePerByte", node.voteableParams()[1])
            .put("maxBoxRegisters", params.maxBoxRegisters())
            .put("stateRoot", node.stateRoot() == null ? JSONObject.NULL : hex(node.stateRoot())));
    }

    /**
     * Capability discovery for the dashboard, so the UI enables pages by what this
     * node actually supports (the boxes/tokens pages activate themselves from these
     * flags — a node built without those layers keeps them dormant).
     */
    private static HttpResponse features(NodeService node, SseLogHub sse) {
        boolean contracts = node.dryRunAvailable();
        return json(new JSONObject()
            .put("dashboard", true)
            .put("contracts", contracts)
            .put("contractQuery", contracts)
            .put("logStream", sse != null)
            .put("agents", contracts)
            .put("boxes", node.boxesAvailable())
            .put("tokens", node.tokensAvailable())
            .put("stateRoot", node.stateRoot() != null));
    }

    /** Block summaries for an inclusive height range (newest-first friendly, bounded). */
    private static HttpResponse blocks(NodeService node, HttpRequest req) {
        long start = parseLong(req.getQueryParameter("start"));
        long end = parseLong(req.getQueryParameter("end"));
        if (start < 1 || end < start) {
            return badRequest("invalid range");
        }
        if (end - start + 1 > BLOCKS_RANGE_MAX) {
            return badRequest("range too large (max " + BLOCKS_RANGE_MAX + ")");
        }
        long cappedEnd = Math.min(end, node.blockCount());
        org.json.JSONArray arr = new org.json.JSONArray();
        for (long h = start; h <= cappedEnd; h++) {
            var block = (rhizome.core.block.BlockImpl) node.block(h);
            arr.put(new JSONObject()
                .put("height", h)
                .put("hash", block.hash().toHexString())
                .put("timestamp", block.timestamp())
                .put("difficulty", block.difficulty())
                .put("txCount", block.transactions().size())
                .put("uncles", block.uncles().size()));
        }
        return json(new JSONObject().put("blocks", arr).put("height", node.blockCount()));
    }

    /**
     * Looks a transaction up by content hash (txid) with a bounded tip-backward
     * scan — the node keeps no txid index, so this trades depth for memory like
     * the /logs cursor does. {@code ?depth=} widens the scan up to the cap.
     */
    private static HttpResponse findTransaction(NodeService node, HttpRequest req) {
        String txid = req.getQueryParameter("txid");
        if (txid == null || txid.length() != 64) {
            return badRequest("txid must be 64 hex chars");
        }
        long depth = scanDepth(req);
        long tip = node.blockCount();
        long floor = Math.max(1, tip - depth + 1);
        for (long h = tip; h >= floor; h--) {
            for (Transaction t : node.block(h).transactions()) {
                if (t.hashContents().toHexString().equalsIgnoreCase(txid)) {
                    return json(new JSONObject()
                        .put("height", h)
                        .put("transaction", t.toJson()));
                }
            }
        }
        return notFound("transaction not found in scanned range (deepen with ?depth=)");
    }

    /** Transactions touching an address (as sender or recipient), bounded scan as above. */
    private static HttpResponse addressTransactions(NodeService node, HttpRequest req) {
        PublicAddress address = PublicAddress.of(req.getQueryParameter("address"));
        long depth = scanDepth(req);
        long tip = node.blockCount();
        long floor = Math.max(1, tip - depth + 1);
        org.json.JSONArray arr = new org.json.JSONArray();
        for (long h = tip; h >= floor && arr.length() < ADDRESS_TXS_MAX; h--) {
            for (Transaction t : node.block(h).transactions()) {
                if ((address.equals(t.from()) || address.equals(t.to())) && arr.length() < ADDRESS_TXS_MAX) {
                    arr.put(t.toJson().put("height", h));
                }
            }
        }
        return json(new JSONObject()
            .put("address", address.toHexString())
            .put("scannedFrom", floor)
            .put("scannedTo", tip)
            .put("transactions", arr));
    }

    private static long scanDepth(HttpRequest req) {
        String depthParam = req.getQueryParameter("depth");
        long depth = depthParam == null ? SCAN_DEPTH_DEFAULT : Long.parseLong(depthParam.trim());
        if (depth < 1 || depth > SCAN_DEPTH_MAX) {
            throw new IllegalArgumentException("depth must be in [1, " + SCAN_DEPTH_MAX + "]");
        }
        return depth;
    }

    /** Deployed-contract inspection: code presence/size/hash plus the account state. */
    private static HttpResponse contractInfo(NodeService node, HttpRequest req) {
        PublicAddress address = PublicAddress.of(req.getQueryParameter("address"));
        byte[] code = node.contractCode(address);
        JSONObject out = new JSONObject()
            .put("address", address.toHexString())
            .put("exists", code != null)
            .put("balance", node.balance(address));
        if (code != null) {
            out.put("codeSize", code.length).put("codeHash", sha256Hex(code));
        }
        return json(out);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static HttpResponse asset(DashboardAssets.Asset asset) {
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, asset.contentType())
            .withHeader(H_CSP, DASHBOARD_CSP)
            .withHeader(H_XFO, "DENY")
            .withHeader(H_XCTO, "nosniff")
            .withHeader(H_REFERRER, "no-referrer")
            .withBody(asset.bytes())
            .build();
    }

    /**
     * True when a POST carries a browser {@code Origin} header whose authority differs from
     * the request's {@code Host} — i.e. a cross-site request. Fails open (returns false) if
     * either header is absent or unparseable, so non-browser peers and the dashboard's own
     * same-origin requests are never blocked.
     */
    private static boolean isCrossOriginPost(HttpRequest request) {
        try {
            String origin = request.getHeader(H_ORIGIN);
            if (origin == null || origin.isEmpty()) {
                return false;
            }
            String host = request.getHeader(H_HOST);
            if (host == null || host.isEmpty()) {
                return false;
            }
            String authority = java.net.URI.create(origin).getAuthority();
            return authority == null || !authority.equalsIgnoreCase(host);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static HttpResponse notFound(String message) {
        return HttpResponse.ofCode(404)
            .withJson(new JSONObject().put("error", message).toString())
            .build();
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
            int depth = SCAN_DEPTH_DEFAULT;
            try {
                String d = request.getQueryParameter("depth");
                if (d != null && !d.isEmpty()) {
                    depth = Integer.parseInt(d.trim());
                }
            } catch (RuntimeException ignored) {
                // malformed depth: the handler will reject it; charge the default cost
            }
            depth = Math.max(1, Math.min(depth, SCAN_DEPTH_MAX));
            return Math.max(1, depth / SCAN_COST_PER_BLOCKS);
        }
        if ("/call_readonly".equals(path)) {
            return CALL_READONLY_COST;
        }
        return 1;
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

    /**
     * Contract event logs, the channel agents watch. Two modes:
     * <ul>
     *   <li>{@code ?height=N} — logs emitted by block N.</li>
     *   <li>{@code ?fromHeight=N} — a bounded height-cursor scan from N to the tip; the
     *       response's {@code toHeight} is the next cursor, so an agent streams by
     *       repeatedly polling from {@code toHeight + 1}.</li>
     * </ul>
     */
    private static HttpResponse logs(NodeService node, HttpRequest req) {
        String heightParam = req.getQueryParameter("height");
        if (heightParam != null) {
            long height = parseLong(heightParam);
            if (height < 1 || height > node.blockCount()) {
                return badRequest("height out of range");
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (var log : node.logsAt(height)) {
                arr.put(logJson(log));
            }
            return json(new JSONObject().put("height", height).put("logs", arr));
        }
        long fromHeight = parseLong(req.getQueryParameter("fromHeight"));
        if (fromHeight < 1) {
            return badRequest("fromHeight must be >= 1");
        }
        NodeService.LogPage page = node.logsFrom(fromHeight);
        org.json.JSONArray arr = new org.json.JSONArray();
        for (var entry : page.logs()) {
            arr.put(logJson(entry.log()).put("height", entry.height()));
        }
        return json(new JSONObject()
            .put("fromHeight", page.fromHeight())
            .put("toHeight", page.toHeight())
            .put("logs", arr));
    }

    /**
     * Live contract-log push over Server-Sent Events: a heartbeat comment per applied
     * block and one {@code data:} event per log (see {@link SseLogHub} for the format
     * and the slow-subscriber contract). 503 when streaming is not wired or full.
     */
    private static HttpResponse logStream(SseLogHub sse) {
        var stream = sse == null ? null : sse.subscribe();
        if (stream == null) {
            return HttpResponse.ofCode(503)
                .withJson(new JSONObject().put("error", "streaming unavailable").toString())
                .build();
        }
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream")
            .withHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
            .withBodyStream(stream)
            .build();
    }

    /**
     * Read-only contract call (dry run): {@code POST /call_readonly} with a JSON body
     * {@code {to, input?, from?, value?, gasLimit?}}. Runs the CALL against committed
     * state, discards all writes, and returns the would-be result — for querying
     * contract state without submitting a transaction. 503 if contracts are not wired.
     */
    private static HttpResponse callReadonly(NodeService node, JSONObject body) {
        if (!node.dryRunAvailable()) {
            return HttpResponse.ofCode(503)
                .withJson(new JSONObject().put("error", "contracts unavailable").toString()).build();
        }
        PublicAddress to = PublicAddress.of(body.getString("to"));
        PublicAddress from = body.has("from") && !body.getString("from").isEmpty()
            ? PublicAddress.of(body.getString("from")) : PublicAddress.empty();
        byte[] input = body.has("input") && !body.getString("input").isEmpty()
            ? rhizome.core.common.Utils.hexStringToByteArray(body.getString("input")) : new byte[0];
        long value = body.optLong("value", 0);
        // Clamp the caller-supplied gas: a dry-run is free and unauthenticated, so an
        // unbounded gasLimit would let anyone burn arbitrary node CPU. Bound it server-side.
        long gasLimit = Math.min(Math.max(1L, body.optLong("gasLimit", 10_000_000L)), MAX_READONLY_GAS);

        var result = node.dryRun(from, to, input, value, gasLimit);
        org.json.JSONArray logs = new org.json.JSONArray();
        for (var log : result.logs()) {
            logs.put(logJson(log));
        }
        return json(new JSONObject()
            .put("success", result.success())
            .put("output", hex(result.output()))
            .put("gasUsed", result.gasUsed())
            .put("error", result.error() == null ? JSONObject.NULL : result.error())
            .put("logs", logs));
    }

    /** A single data box by id: {@code GET /box?id=<hex64>}; 404 if absent. */
    private static HttpResponse box(NodeService node, HttpRequest req) {
        byte[] id = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("id"));
        if (id.length != 32) {
            return badRequest("id must be 32 bytes (64 hex chars)");
        }
        rhizome.core.box.Box b = node.box(id);
        if (b == null) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "box not found").toString())
                .build();
        }
        return json(boxJson(b, node.params().storagePeriodBlocks()));
    }

    /** Boxes owned by an address: {@code GET /boxes?owner=<hex50>&limit=&after=<boxIdHex>}. */
    private static HttpResponse boxes(NodeService node, HttpRequest req) {
        byte[] owner = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("owner"));
        if (owner.length != PublicAddress.SIZE) {
            return badRequest("owner must be 25 bytes (50 hex chars)");
        }
        String limitParam = req.getQueryParameter("limit");
        int limit = limitParam == null ? 50 : Math.min(100, Math.max(1, (int) parseLong(limitParam)));
        String afterParam = req.getQueryParameter("after");
        byte[] after = afterParam == null || afterParam.isEmpty()
            ? null : rhizome.core.common.Utils.hexStringToByteArray(afterParam);

        long period = node.params().storagePeriodBlocks();
        org.json.JSONArray arr = new org.json.JSONArray();
        byte[] last = null;
        for (byte[] id : node.boxIdsByOwner(owner, after, limit)) {
            rhizome.core.box.Box b = node.box(id);
            if (b != null) {
                arr.put(boxJson(b, period));
            }
            last = id;
        }
        JSONObject result = new JSONObject()
            .put("owner", rhizome.core.common.Utils.bytesToHex(owner))
            .put("boxes", arr);
        if (last != null) {
            result.put("next", rhizome.core.common.Utils.bytesToHex(last));
        }
        return json(result);
    }

    /** The current authenticated state root: {@code GET /state}. */
    private static HttpResponse state(NodeService node) {
        byte[] root = node.stateRoot();
        return json(new JSONObject().put("stateRoot", root == null ? JSONObject.NULL : hex(root)));
    }

    /**
     * A light-client membership proof: {@code GET /state/proof?domain=<d>&key=<hex>}, where
     * {@code d} is {@code ledger}/{@code box}/{@code token_meta}/{@code token_balance}. Returns
     * the root, the bound value hash and the sibling hashes; the client re-derives the SMT key
     * from {@code (domain, key)} and folds the siblings to check it against the root. 404 if absent.
     */
    private static HttpResponse stateProof(NodeService node, HttpRequest req) {
        byte[] root = node.stateRoot();
        if (root == null) {
            return HttpResponse.ofCode(503)
                .withJson(new JSONObject().put("error", "state root unavailable").toString()).build();
        }
        Byte domain = stateDomain(req.getQueryParameter("domain"));
        if (domain == null) {
            return badRequest("domain must be ledger|box|token_meta|token_balance|contract_code|contract_storage");
        }
        byte[] key = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("key"));
        rhizome.core.state.StateProof proof = node.stateProof(domain, key);
        if (proof == null) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "no such state entry").toString()).build();
        }
        org.json.JSONArray siblings = new org.json.JSONArray();
        for (byte[] s : proof.siblings()) {
            siblings.put(hex(s));
        }
        return json(new JSONObject()
            .put("root", hex(root))
            .put("valueHash", hex(proof.valueHash()))
            .put("siblings", siblings));
    }

    private static Byte stateDomain(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "ledger" -> rhizome.core.state.StateKeys.LEDGER;
            case "box" -> rhizome.core.state.StateKeys.BOX;
            case "token_meta" -> rhizome.core.state.StateKeys.TOKEN_META;
            case "token_balance" -> rhizome.core.state.StateKeys.TOKEN_BALANCE;
            case "contract_code" -> rhizome.core.state.StateKeys.CONTRACT_CODE;
            case "contract_storage" -> rhizome.core.state.StateKeys.CONTRACT_STORAGE;
            default -> null;
        };
    }

    /** Native token metadata: {@code GET /token?id=<hex64>}; 404 if absent. */
    private static HttpResponse token(NodeService node, HttpRequest req) {
        byte[] id = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("id"));
        if (id.length != 32) {
            return badRequest("id must be 32 bytes (64 hex chars)");
        }
        rhizome.core.token.TokenMeta meta = node.tokenMeta(id);
        if (meta == null) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "token not found").toString()).build();
        }
        return json(tokenJson(meta));
    }

    /** Token balance: {@code GET /token_balance?id=<hex64>&address=<hex50>}. */
    private static HttpResponse tokenBalance(NodeService node, HttpRequest req) {
        byte[] id = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("id"));
        byte[] address = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("address"));
        if (id.length != 32 || address.length != PublicAddress.SIZE) {
            return badRequest("id must be 32 bytes and address 25 bytes");
        }
        return json(new JSONObject()
            .put("token", hex(id))
            .put("address", hex(address))
            .put("balance", node.tokenBalance(id, address)));
    }

    /** Tokens by minter or holder: {@code GET /tokens?minter=<hex50>} or {@code ?holder=<hex50>}. */
    private static HttpResponse tokens(NodeService node, HttpRequest req) {
        String minter = req.getQueryParameter("minter");
        String holder = req.getQueryParameter("holder");
        byte[] key;
        java.util.List<byte[]> ids;
        if (minter != null) {
            key = rhizome.core.common.Utils.hexStringToByteArray(minter);
            ids = node.tokenIdsByMinter(key, null, 100);
        } else if (holder != null) {
            key = rhizome.core.common.Utils.hexStringToByteArray(holder);
            ids = node.tokenIdsByHolder(key, null, 100);
        } else {
            return badRequest("provide minter= or holder=");
        }
        if (key.length != PublicAddress.SIZE) {
            return badRequest("address must be 25 bytes (50 hex chars)");
        }
        org.json.JSONArray arr = new org.json.JSONArray();
        for (byte[] id : ids) {
            rhizome.core.token.TokenMeta meta = node.tokenMeta(id);
            if (meta != null) {
                JSONObject entry = tokenJson(meta);
                if (holder != null) {
                    entry.put("balance", node.tokenBalance(id, key));
                }
                arr.put(entry);
            }
        }
        return json(new JSONObject().put("tokens", arr));
    }

    private static JSONObject tokenJson(rhizome.core.token.TokenMeta meta) {
        return new JSONObject()
            .put("id", hex(meta.id()))
            .put("minter", meta.minter().toHexString())
            .put("symbol", meta.symbol())
            .put("name", meta.name())
            .put("decimals", meta.decimals())
            .put("totalSupply", meta.totalSupply())
            .put("createdHeight", meta.createdHeight());
    }

    /** Registered box scans: {@code GET /scan/list}. */
    private static HttpResponse scanList(NodeService node) {
        org.json.JSONArray arr = new org.json.JSONArray();
        node.scans().forEach((id, predicate) ->
            arr.put(new JSONObject().put("scanId", id).put("predicate", predicate.toJson())));
        return json(new JSONObject().put("scans", arr));
    }

    /**
     * Boxes matching a scan: {@code GET /scan/boxes?scanId=N&limit=&after=<boxIdHex>}. The
     * response's {@code next} cursor (a box id) resumes a bounded scan; absent when done.
     */
    private static HttpResponse scanBoxes(NodeService node, HttpRequest req) {
        rhizome.core.box.ScanPredicate predicate = node.scanPredicate((int) parseLong(req.getQueryParameter("scanId")));
        if (predicate == null) {
            return badRequest("unknown scanId");
        }
        String limitParam = req.getQueryParameter("limit");
        int limit = limitParam == null ? 50 : Math.min(100, Math.max(1, (int) parseLong(limitParam)));
        String afterParam = req.getQueryParameter("after");
        byte[] after = afterParam == null || afterParam.isEmpty()
            ? null : rhizome.core.common.Utils.hexStringToByteArray(afterParam);

        long period = node.params().storagePeriodBlocks();
        var page = node.scan(predicate, after, limit);
        org.json.JSONArray arr = new org.json.JSONArray();
        for (rhizome.core.box.Box b : page.matches()) {
            arr.put(boxJson(b, period));
        }
        JSONObject result = new JSONObject().put("boxes", arr);
        if (page.nextCursor() != null) {
            result.put("next", hex(page.nextCursor()));
        }
        return json(result);
    }

    private static JSONObject boxJson(rhizome.core.box.Box b, long storagePeriodBlocks) {
        org.json.JSONArray registers = new org.json.JSONArray();
        for (rhizome.core.box.BoxRegister r : b.registers()) {
            JSONObject reg = new JSONObject()
                .put("type", r.type().name())
                .put("hex", hex(r.payload()));
            if (r.type() == rhizome.core.box.BoxRegisterType.STRING) {
                reg.put("string", new String(r.payload(), StandardCharsets.UTF_8));
            }
            registers.put(reg);
        }
        return new JSONObject()
            .put("id", hex(b.id()))
            .put("owner", b.owner().toHexString())
            .put("value", b.value())
            .put("createdHeight", b.createdHeight())
            .put("rentPaidHeight", b.rentPaidHeight())
            .put("expiresAtHeight", b.expiryHeight(storagePeriodBlocks))
            .put("sizeBytes", b.serializedSize())
            .put("registers", registers);
    }

    private static JSONObject logJson(rhizome.core.blockchain.ContractProcessor.ContractLog log) {
        return new JSONObject()
            .put("contract", log.contract().toHexString())
            .put("topic", hex(log.topic()))
            .put("data", hex(log.data()));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static HttpResponse sync(NodeService node, HttpRequest req) {
        long start = parseLong(req.getQueryParameter("start"));
        long end = parseLong(req.getQueryParameter("end"));
        if (start < 1 || end < start) {
            return badRequest("invalid range");
        }
        if (end - start + 1 > Constants.BLOCKS_PER_FETCH) {
            return badRequest("range too large (max " + Constants.BLOCKS_PER_FETCH + ")");
        }
        // Pruned node: the requested range dips into bodies we have discarded. Answer 410 GONE
        // with the watermark so the caller sources these blocks (or a snapshot) from an archive.
        long prunedBelow = node.prunedBelow();
        if (prunedBelow > 0 && start < prunedBelow) {
            return HttpResponse.ofCode(410)
                .withJson(new JSONObject().put("error", "pruned").put("prunedBelow", prunedBelow).toString())
                .build();
        }
        long cappedEnd = Math.min(end, node.blockCount());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long h = start; h <= cappedEnd; h++) {
            byte[] encoded = BlockCodec.encode(node.block(h));
            out.write(encoded, 0, encoded.length);
        }
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .withBody(out.toByteArray())
            .build();
    }

    /**
     * Streams a self-framing run of block headers ({@link rhizome.core.block.HeaderCodec}),
     * the cheap path a headers-first peer validates before downloading any body. Bounded to
     * {@code BLOCK_HEADERS_PER_FETCH}.
     */
    private static HttpResponse headers(NodeService node, HttpRequest req) {
        long start = parseLong(req.getQueryParameter("start"));
        long end = parseLong(req.getQueryParameter("end"));
        if (start < 1 || end < start) {
            return badRequest("invalid range");
        }
        if (end - start + 1 > Constants.BLOCK_HEADERS_PER_FETCH) {
            return badRequest("range too large (max " + Constants.BLOCK_HEADERS_PER_FETCH + ")");
        }
        long cappedEnd = Math.min(end, node.blockCount());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long h = start; h <= cappedEnd; h++) {
            byte[] encoded = rhizome.core.block.HeaderCodec.encode(node.header(h));
            out.write(encoded, 0, encoded.length);
        }
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .withBody(out.toByteArray())
            .build();
    }

    /** Advertises the materialised state snapshot ({@code 404} when none has been captured). */
    private static HttpResponse snapshotInfo(NodeService node) {
        var snap = node.materializedSnapshot();
        if (snap == null) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "no snapshot materialized").toString())
                .build();
        }
        return json(new JSONObject()
            .put("pivotHeight", snap.pivotHeight())
            .put("stateRoot", rhizome.core.common.Utils.bytesToHex(snap.stateRoot()))
            .put("chunks", snap.chunks().size()));
    }

    /** One binary snapshot chunk by index (bounds-checked against the current materialisation). */
    private static HttpResponse snapshotChunk(NodeService node, HttpRequest req) {
        var snap = node.materializedSnapshot();
        int index = (int) parseLong(req.getQueryParameter("index"));
        if (snap == null || index < 0 || index >= snap.chunks().size()) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "no such snapshot chunk").toString())
                .build();
        }
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .withBody(snap.chunks().get(index))
            .build();
    }

    // ---- helpers: every path returns a response ----

    private static Promise<HttpResponse> ok(HttpResponse response) {
        return Promise.of(response);
    }

    private static Promise<HttpResponse> guarded(Callable<HttpResponse> action) {
        return Promise.of(guardedResponse(action));
    }

    private static HttpResponse guardedResponse(Callable<HttpResponse> action) {
        try {
            return action.call();
        } catch (Exception e) {
            // Generic client message; detail is logged server-side only (audit L3).
            log.debug("request rejected: {}", e.toString());
            return badRequest("bad request");
        }
    }

    private static HttpResponse statusResponse(ExecutionStatus status) {
        int code = status == ExecutionStatus.SUCCESS ? 200 : 400;
        return HttpResponse.ofCode(code)
            .withJson(new JSONObject().put("status", status.name()).toString())
            .build();
    }

    private static long parseLong(String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing parameter");
        }
        return Long.parseLong(value.trim());
    }

    private static HttpResponse text(String body) {
        return HttpResponse.ok200().withPlainText(body).build();
    }

    private static HttpResponse json(JSONObject body) {
        return HttpResponse.ok200().withJson(body.toString()).build();
    }

    private static HttpResponse badRequest(String message) {
        return HttpResponse.ofCode(400)
            .withJson(new JSONObject().put("error", message).toString())
            .build();
    }
}
