package rhizome.node;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.RoutingServlet;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import org.json.JSONObject;

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

    private static final int SMALL_BODY = 8 * 1024;                 // tx / peer announcements
    // A single transaction body may be a contract deploy/call carrying up to MAX_DATA
    // bytes of payload (plus the kind tag and gas fields), so size the cap for that.
    private static final int TX_BODY = TransactionDto.BUFFER_SIZE + 1 + 20 + TransactionDto.MAX_DATA + 1024;

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
            .with(POST, "/contract/query", req -> req.loadBody(TX_BODY).map(body -> guardedResponse(() ->
                contractQuery(node, new JSONObject(body.getString(StandardCharsets.UTF_8))))))
            .with(GET, "/block_count", req -> ok(text(String.valueOf(node.blockCount()))))
            .with(GET, "/total_work", req -> ok(json(new JSONObject().put("totalWork", node.totalWork().toString()))))
            .with(GET, "/difficulty", req -> ok(text(String.valueOf(node.difficulty()))))
            .with(GET, "/mempool", req -> ok(json(new JSONObject().put("size", node.mempoolSize()))))
            .with(GET, "/info", req -> ok(json(new JSONObject()
                .put("chainId", node.chainId())
                .put("network", node.networkName())
                .put("height", node.blockCount())
                .put("difficulty", node.difficulty())
                .put("mempool", node.mempoolSize()))))
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
            .with(GET, "/logs", req -> guarded(() -> logs(node, req)))
            .with(GET, "/logs/stream", req -> guarded(() -> logStream(sse)))
            .with(GET, "/sync", req -> guarded(() -> sync(node, req)))
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
            if (!limiter.allow(clientKey(request))) {
                return HttpResponse.ofCode(429)
                    .withJson(new JSONObject().put("error", "rate limited").toString())
                    .toPromise();
            }
            // Convert any handler failure (incl. body-size overflow) into a clean response.
            return routing.serve(request).map(r -> r, e -> badRequest(e.getClass().getSimpleName()));
        };
    }

    // ---- dashboard/explorer handlers ----

    /** How many recent blocks the /stats aggregates cover (block time, tx rate). */
    private static final int STATS_WINDOW = 32;
    /** Default and maximum tip-backward scan depth for /transaction and /address_txs. */
    private static final int SCAN_DEPTH_DEFAULT = 250;
    private static final int SCAN_DEPTH_MAX = 2000;
    /** Result cap for /address_txs so a busy address cannot produce an unbounded body. */
    private static final int ADDRESS_TXS_MAX = 100;
    /** Gas ceiling for read-only /contract/query calls (never charged, only bounds work). */
    private static final long QUERY_GAS_LIMIT = 50_000_000L;
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
            .put("windowTxCount", txCount));
    }

    /**
     * Capability discovery for the dashboard, so the UI enables pages by what this
     * node actually supports (e.g. the boxes page stays dormant until the data-box
     * layer — spec'd on the ergo-analysis branch — lands and flips its flag).
     */
    private static HttpResponse features(NodeService node, SseLogHub sse) {
        boolean contracts = node.queryContract(PublicAddress.empty(),
            PublicAddress.empty(), new byte[0], 0) != null;
        return json(new JSONObject()
            .put("dashboard", true)
            .put("contracts", contracts)
            .put("contractQuery", contracts)
            .put("logStream", sse != null)
            .put("agents", contracts)
            .put("boxes", false));
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

    /**
     * Read-only contract call: runs the VM against a throwaway overlay of the
     * committed state, so it inspects (token balances, agent sessions, oracle
     * values) without a transaction, gas payment or state change. Body:
     * {@code {"address": hex25, "input": hex, "caller"?: hex25, "gasLimit"?: n}}.
     */
    private static HttpResponse contractQuery(NodeService node, JSONObject body) {
        PublicAddress contract = PublicAddress.of(body.getString("address"));
        byte[] input = rhizome.core.common.Utils.hexStringToByteArray(body.optString("input", ""));
        PublicAddress caller = body.has("caller")
            ? PublicAddress.of(body.getString("caller"))
            : PublicAddress.empty();
        long gasLimit = Math.min(body.optLong("gasLimit", QUERY_GAS_LIMIT), QUERY_GAS_LIMIT);
        NodeService.QueryOutcome outcome = node.queryContract(caller, contract, input, gasLimit);
        if (outcome == null) {
            return HttpResponse.ofCode(503)
                .withJson(new JSONObject().put("error", "contract queries unavailable").toString())
                .build();
        }
        JSONObject out = new JSONObject()
            .put("success", outcome.success())
            .put("gasUsed", outcome.gasUsed());
        if (outcome.success()) {
            out.put("output", hex(outcome.output()));
        } else {
            out.put("error", outcome.error() == null ? "reverted" : outcome.error());
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
            .withBody(asset.bytes())
            .build();
    }

    private static HttpResponse notFound(String message) {
        return HttpResponse.ofCode(404)
            .withJson(new JSONObject().put("error", message).toString())
            .build();
    }

    private static String clientKey(HttpRequest request) {
        // getRemoteAddress() runs a checkNotNull under ActiveJ's CHECKS flag, so a
        // request with no live connection (tests, in-process calls) throws instead
        // of returning null; treat any such request as a single "local" bucket.
        try {
            java.net.InetAddress addr = request.getRemoteAddress();
            return addr != null ? addr.getHostAddress() : "local";
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
            return badRequest(e.getClass().getSimpleName() + ": " + e.getMessage());
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
