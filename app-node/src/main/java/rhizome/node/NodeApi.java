package rhizome.node;

import rhizome.net.RateLimiter;

import java.nio.charset.StandardCharsets;

import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.RoutingServlet;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.common.Constants;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.dto.TransactionDto;

import static io.activej.http.HttpMethod.GET;
import static io.activej.http.HttpMethod.POST;
import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.errorJson;
import static rhizome.node.ApiResponses.guarded;
import static rhizome.node.ApiResponses.guardedResponse;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.notFound;
import static rhizome.node.ApiResponses.ok;
import static rhizome.node.ApiResponses.parseJson;
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
    private static final int TX_BODY = TransactionDto.MAX_FIXED_SIZE + 1 + 20 + TransactionDto.MAX_DATA + 1024;
    // The JSON form of the same transaction hex-encodes the payload (2 chars per byte), so a
    // deploy/call near MAX_DATA is ~2x the binary size; capping it at SMALL_BODY rejected JSON
    // transactions whose binary equivalent passes (audit: JSON/binary tx cap asymmetry).
    private static final int JSON_TX_BODY =
        TransactionDto.MAX_FIXED_SIZE + 2 * TransactionDto.MAX_DATA + 2048;

    // Well-known ActiveJ header tokens: the HTTP parser interns incoming Origin/Host under these, and
    // a custom HttpHeaders.of("Origin"/"Host") token no longer matches them (it did in 6.0-beta2, but
    // 6.0-rc2 tightened the lookup; v7.0.0 keeps the interned lookup) — so reading the CSRF/rebinding
    // guard's Origin/Host through of(...) silently returned null and fail-opened. Use the interned
    // constants so the guard sees the values (re-verified on v7.0.0 via NodeApiHardeningFlagsTest).
    private static final HttpHeader H_ORIGIN = HttpHeaders.ORIGIN;
    private static final HttpHeader H_HOST = HttpHeaders.HOST;
    /** Non-simple header the dashboard sends on every state-changing POST; forces a CORS preflight
     *  a cross-site/rebinding page cannot satisfy, so its POST is blocked by the browser. */
    private static final HttpHeader H_RZ_REQUEST = HttpHeaders.of("X-Rhizome-Request");
    /** Optional scan-ownership secret; see {@link ScanRegistry.Owner} and {@link #scanOwner}. */
    private static final HttpHeader H_SCAN_OWNER = HttpHeaders.of("X-Scan-Owner");
    /** Cap on the accepted secret: it is only ever hashed, and an unauthenticated route must not
     *  let a caller push arbitrary bytes through SHA-256. Anything longer is truncated, which
     *  costs the caller nothing — it is their own opaque token. */
    private static final int SCAN_OWNER_MAX_CHARS = 256;

    /** Rate-limit cost of a /call_readonly, which runs the VM up to its readonly gas cap. */
    private static final int CALL_READONLY_COST = 25;
    /**
     * Rate-limit cost of a /submit. Accepting a block runs consensus validation and, for a
     * plausible recent sibling, one memory-hard Pufferfish2 hash (registerOrphan) — far dearer than
     * a flat read. Weighted so a single IP cannot drive thousands of block validations/hashes per
     * window (audit H3); still generous for honest block propagation (~1 block / few seconds).
     */
    private static final int SUBMIT_COST = 8;

    /**
     * Rate-limit cost of an /add_transaction(JSON). Every admission runs one Ed25519 verification
     * inline on the event-loop thread (~100 µs), and invalid signatures are never cached, so a
     * replayed corrupt-signature tx re-pays the crypto each time — far dearer than a flat read
     * (audit M1). Paired with the aggregate {@code tryMempoolSigBudget} gate below.
     */
    private static final int TX_SUBMIT_COST = 4;

    /**
     * Fallback rate-limit cost of serving a state-snapshot chunk, charged when the actual
     * chunk size cannot be resolved at the gate (no snapshot materialised, or a missing /
     * malformed / out-of-range index the handler will reject cheaply). When the chunk IS
     * resolvable the cost is proportional to its size — see {@link #snapshotChunkCost}
     * (audit F3).
     */
    private static final int SNAPSHOT_CHUNK_COST = 75;

    /**
     * Egress granularity of the snapshot-chunk cost model: one rate-limit unit per 64 KiB
     * of chunk served, so at the default 1000 units/s an IP is bounded to ~62 MiB/s of
     * snapshot egress instead of ~200 MiB/s under the old flat cost (audit F3).
     */
    private static final long CHUNK_COST_UNIT_BYTES = 64L * 1024;

    /** Floor of the byte-proportional snapshot-chunk cost (lookup + response overhead). */
    private static final int SNAPSHOT_CHUNK_MIN_COST = 2;

    /**
     * Rate-limit cost of an /add_peer: admission queues a blocking DNS resolve (ban check,
     * routability, subnet bucket) on a single off-loop thread behind a bounded queue, so the
     * flat cost of 1 let one IP enqueue ~1000 resolves/s against a ~5 s/thread drain rate.
     * Weighted like /submit so the admission queue sees bounded arrivals (audit: add_peer
     * cost vs blocking DNS).
     */
    private static final int ADD_PEER_COST = 8;

    /**
     * Rate-limit cost of an /orphan fetch: a lock-guarded orphan-pool / uncle-store read plus
     * a full-block binary egress, for an unauthenticated P2P caller — weighted like the other
     * block-serving reads (~1 unit per block) with a small surcharge for the hash-keyed lookup.
     */
    private static final int ORPHAN_COST = 2;

    /**
     * Rate-limit cost of a /boxes listing: one owner-index scan plus up to {@code limit} (capped
     * at 100) per-id box reads, every one of them taking the consensus lock
     * ({@code ChainEngine.boxIdsByOwner} / {@code ChainEngine.box}). At the flat cost of 1 that was
     * ~101 lock acquisitions for the price of one /block, which takes exactly one — the widest
     * cost/work gap left in the table. Weighted at the project's own rate of ~1 unit per 4 bounded
     * reads, the same rule that gives /scan/boxes its BOX_SCAN_WINDOW/4.
     *
     * <p>Charged for the worst case rather than for {@code limit}, deliberately: /scan/boxes does
     * the same, and the only real caller (the dashboard) uses the default.
     */
    private static final int BOXES_COST = 25;

    /**
     * Rate-limit cost of a /tokens listing. Worse than /boxes: the limit is hard-coded at 100 and
     * not client-controllable, and the {@code holder=} form does a third lock-guarded read per
     * token — one index scan + 100 tokenMeta + 100 tokenBalance, so ~201 lock acquisitions.
     * Weighted at the same ~1-unit-per-4-reads rate.
     */
    private static final int TOKENS_COST = 50;

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_TOTAL_WORK = Key.of("totalWork");
    private static final Key K_SIZE = Key.of("size");
    private static final Key K_CHAIN_ID = Key.of("chainId");
    private static final Key K_NETWORK = Key.of("network");
    private static final Key K_HEIGHT = Key.of("height");
    private static final Key K_DIFFICULTY = Key.of("difficulty");
    private static final Key K_MEMPOOL = Key.of("mempool");
    private static final Key K_PRUNED_BELOW = Key.of("prunedBelow");
    private static final Key K_SNAPSHOT_PIVOT = Key.of("snapshotPivot");
    private static final Key K_STORAGE_FEE_FACTOR = Key.of("storageFeeFactor");
    private static final Key K_MIN_VALUE_PER_BYTE = Key.of("minValuePerByte");
    private static final Key K_PEERS = Key.of("peers");
    private static final Key K_STATUS = Key.of("status");
    private static final Key K_SCAN_ID = Key.of("scanId");
    private static final Key K_REMOVED = Key.of("removed");

    // -- JsonSink size hints (see class javadoc on JsonSink) -------------------------------------
    private static final int TOTAL_WORK_SIZE_HINT = 96;
    private static final int MEMPOOL_SIZE_HINT = 32;
    private static final int INFO_SIZE_HINT = 256;
    private static final int PEERS_BASE_SIZE_HINT = 64;
    private static final int PEER_ENTRY_SIZE_HINT = 48;
    private static final int STATUS_OK_SIZE_HINT = 32;
    private static final int SCAN_ID_SIZE_HINT = 32;
    private static final int REMOVED_SIZE_HINT = 32;

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
        return servlet(reactor, node, limiter, sse, null);
    }

    /**
     * As above, with an optional allowlist of legitimate {@code Host} authorities (host or host:port)
     * for the DNS-rebinding defense on state-changing POSTs. When non-null and non-empty, a browser POST
     * is refused unless its {@code Host} is in the set — this is what actually stops rebinding, since a
     * rebound page carries the attacker's own hostname as Host (audit S-2). Pass {@code null} to keep the
     * Origin/marker-only behavior (tests and simple embeds that don't know their public host).
     */
    public static AsyncServlet servlet(Reactor reactor, NodeService node, RateLimiter limiter, SseLogHub sse,
                                       java.util.Set<String> allowedHosts) {
        return servlet(reactor, node, limiter, sse, allowedHosts, null);
    }

    /**
     * As above, with an optional bearer token ({@code RHIZOME_API_TOKEN}) gating the
     * state-changing/operator routes. Pass {@code null} to leave every route open (the
     * historical default for a localhost-bound node).
     */
    public static AsyncServlet servlet(Reactor reactor, NodeService node, RateLimiter limiter, SseLogHub sse,
                                       java.util.Set<String> allowedHosts, String apiToken) {
        return servlet(reactor, node, limiter, sse, allowedHosts, apiToken, null);
    }

    /**
     * As above, additionally running the consensus-heavy handlers (block/tx ingest, VM dry-run,
     * lock-guarded explorer reads) on a bounded {@code blocking} worker pool instead of the
     * event-loop thread. Without it every PoW check, Ed25519 verify, VM execution and RocksDB
     * fsync runs on the single HTTP loop: one valid-but-heavy block, or one max-gas dry-run,
     * freezes every route (including SSE and peer heartbeats) for its whole duration (audit:
     * eventloop blocked by consensus work). Pass {@code null} to run inline (tests). The
     * streaming sync endpoints stay on the loop: they are bounded to one block/header in
     * flight and already aggregate-gated, so offloading them cannot pin the loop for long.
     */
    public static AsyncServlet servlet(Reactor reactor, NodeService node, RateLimiter limiter, SseLogHub sse,
                                       java.util.Set<String> allowedHosts, String apiToken,
                                       java.util.concurrent.Executor blocking) {
        return servlet(reactor, node, limiter, sse, allowedHosts, apiToken, blocking, false, false);
    }

    /**
     * As above, with two opt-in hardening flags (env-only; both default off):
     *
     * <p>{@code protectReads} ({@code RHIZOME_PROTECT_READS}) extends the bearer-token gate to
     * EVERY route, not just the state-changing POSTs — the "private node / private explorer"
     * switch (audit: no read protection). Without it a token-configured node still serves
     * {@code /stats}, balances and {@code /logs/stream} to anyone who can reach the port. With
     * it, peering requires every peer to present a matching bearer (via their
     * {@code RHIZOME_PEER_TOKEN} to this node), so it suits private clusters, not public relays.
     * The static SPA/docs shell ({@code /}, {@code /dashboard/*}, {@code /docs/*}) stays open:
     * a browser cannot attach a bearer to a plain navigation, and the shell is static content
     * with no chain data — the SPA's own API fetches carry the token and stay gated.
     *
     * <p>{@code trustXff} ({@code RHIZOME_TRUST_XFF}) keys rate limits, push-strike tables and
     * scan ownership on the FIRST {@code X-Forwarded-For} hop instead of the socket address —
     * required when the node sits behind a reverse proxy, without which every client shares the
     * proxy's budget and scan namespace (audit: no reverse-proxy support). DANGEROUS when the
     * port is directly reachable: clients could then spoof the header and evade per-IP limits.
     * Only enable it when the socket can ONLY be reached from the trusted proxy.
     */
    public static AsyncServlet servlet(Reactor reactor, NodeService node, RateLimiter limiter, SseLogHub sse,
                                       java.util.Set<String> allowedHosts, String apiToken,
                                       java.util.concurrent.Executor blocking,
                                       boolean protectReads, boolean trustXff) {
        int maxBlockBody = node.params().maxBlockSizeBytes() + 1024;
        DashboardAssets dashboard = DashboardAssets.load();
        DocsAssets docs = DocsAssets.load();

        RoutingServlet routing = RoutingServlet.builder(reactor)
            // ---- embedded dashboard SPA ----
            .with(GET, "/", req -> ok(DashboardApi.asset(dashboard.index())))
            .with(GET, "/dashboard", req -> ok(DashboardApi.asset(dashboard.index())))
            .with(GET, "/dashboard/*", req -> guarded(() -> {
                DashboardAssets.Asset a = dashboard.get(req.getRelativePath());
                return a == null ? notFound("no such asset") : DashboardApi.asset(a);
            }))
            // ---- the node's own documentation (markdown + manifest, rendered by the SPA) ----
            .with(GET, "/docs/*", req -> guarded(() -> {
                DashboardAssets.Asset a = docs.get(req.getRelativePath());
                return a == null ? notFound("no such document") : DashboardApi.asset(a);
            }))
            // ---- dashboard/explorer API ----
            .with(GET, "/stats", req -> offload(blocking, () -> DashboardApi.stats(node)))
            .with(GET, "/features", req -> guarded(() -> DashboardApi.features(node, sse)))
            .with(GET, "/blocks", req -> offload(blocking, () -> whenNotReorging(node, () -> ExplorerApi.blocks(node, req))))
            .with(GET, "/block", req -> offload(blocking, () -> whenNotReorging(node, () -> ExplorerApi.block(node, req))))
            .with(GET, "/transaction", req -> offload(blocking, () -> ExplorerApi.findTransaction(node, req)))
            .with(GET, "/address_txs", req -> offload(blocking, () -> ExplorerApi.addressTransactions(node, req)))
            .with(GET, "/contract", req -> offload(blocking, () -> ExplorerApi.contractInfo(node, req)))
            .with(GET, "/wallet", req -> offload(blocking, () -> ExplorerApi.wallet(node, req)))
            // ---- chain scalars ----
            // /block_count and /total_work are what peers read first in a sync round; during a
            // reorg window they answer 503 like the other chain routes so a peer never sees the
            // "height before the pop, headers after it" inconsistency (testnet campaign S5).
            .with(GET, "/block_count", req -> ok(whenNotReorging(node, () -> text(String.valueOf(node.blockCount())))))
            .with(GET, "/total_work", req -> ok(whenNotReorging(node, () -> totalWorkResponse(node))))
            .with(GET, "/difficulty", req -> ok(text(String.valueOf(node.difficulty()))))
            .with(GET, "/mempool", req -> ok(mempoolResponse(node)))
            .with(GET, "/info", req -> ok(infoResponse(node)))
            // ---- peer registry ----
            .with(GET, "/peers", req -> ok(peersResponse(node)))
            .with(POST, "/add_peer", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                String url = parseJson(body.getString(StandardCharsets.UTF_8)).getString("url");
                node.addPeer(url);
                return statusOkResponse();
            })))
            // ---- boxes / scans ----
            .with(GET, "/box", req -> offload(blocking, () -> BoxApi.box(node, req)))
            .with(GET, "/boxes", req -> offload(blocking, () -> BoxApi.boxes(node, req)))
            .with(POST, "/scan/register", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                int id = node.registerScan(scanOwner(req, trustXff), rhizome.core.box.ScanPredicate.fromJson(
                    parseJson(body.getString(StandardCharsets.UTF_8))));
                return scanIdResponse(id);
            })))
            .with(POST, "/scan/deregister", req -> req.loadBody(SMALL_BODY).map(body -> guardedResponse(() -> {
                int id = parseJson(body.getString(StandardCharsets.UTF_8)).getInt("scanId");
                return removedResponse(node.deregisterScan(scanOwner(req, trustXff), id));
            })))
            .with(GET, "/scan/list", req -> offload(blocking, () -> BoxApi.scanList(node, scanOwner(req, trustXff))))
            .with(GET, "/scan/boxes", req -> offload(blocking, () -> BoxApi.scanBoxes(node, scanOwner(req, trustXff), req)))
            // ---- tokens ----
            .with(GET, "/token", req -> offload(blocking, () -> TokenApi.token(node, req)))
            .with(GET, "/token_balance", req -> offload(blocking, () -> TokenApi.tokenBalance(node, req)))
            .with(GET, "/tokens", req -> offload(blocking, () -> TokenApi.tokens(node, req)))
            // ---- authenticated state ----
            .with(GET, "/state", req -> offload(blocking, () -> StateApi.state(node)))
            .with(GET, "/state/proof", req -> offload(blocking, () -> StateApi.stateProof(node, req)))
            .with(GET, "/state/snapshot/info", req -> guarded(() -> SyncApi.snapshotInfo(node)))
            // Chunk reads are disk I/O (file-backed spool) — off the event loop like /orphan.
            .with(GET, "/state/snapshot/chunk", req -> offload(blocking, () -> SyncApi.snapshotChunk(node, req)))
            // ---- contract logs / dry run ----
            .with(GET, "/logs", req -> offload(blocking, () -> ContractApi.logs(node, req)))
            .with(GET, "/logs/stream", req -> guarded(() -> ContractApi.logStream(sse, clientKey(req, trustXff), clientSubnetKey(req, trustXff))))
            .with(POST, "/call_readonly", req -> req.loadBody(TX_BODY).then(body -> offload(blocking, () ->
                ContractApi.callReadonly(node, parseJson(body.getString(StandardCharsets.UTF_8))))))
            // ---- peer sync / gossip ingest ----
            .with(GET, "/sync", req -> guarded(() -> SyncApi.sync(node, req)))
            .with(GET, "/headers", req -> guarded(() -> SyncApi.headers(node, req)))
            .with(GET, "/orphan", req -> offload(blocking, () -> SyncApi.orphan(node, req)))
            .with(POST, "/add_transaction_json", req -> req.loadBody(JSON_TX_BODY).then(body -> offload(blocking, () -> {
                Transaction t = Transaction.of(parseJson(body.getString(StandardCharsets.UTF_8)));
                return statusResponse(node.submitTransaction(t, clientKey(req, trustXff)));
            })))
            .with(POST, "/add_transaction", req -> req.loadBody(TX_BODY).then(body -> offload(blocking, () -> {
                Transaction t = Transaction.of(BinarySerializable.fromBuffer(body.getArray(), TransactionDto.class));
                return statusResponse(node.submitTransaction(t, clientKey(req, trustXff)));
            })))
            .with(POST, "/submit", req -> req.loadBody(maxBlockBody).then(body -> offload(blocking, () -> {
                Block block = BlockCodec.decode(body.getArray());
                return statusResponse(node.submitBlock(block, clientKey(req, trustXff)));
            })))
            .build();

        return request -> {
            // Resolve the path the ROUTER will dispatch on, once, and classify every gate below
            // against it. request.getPath() is the raw slice and does NOT agree with the router
            // (see routingKey): classifying on it let "/submit/" reach the block-ingest handler
            // while missing the bearer gate, the push shed and the submit budget.
            String path = routingKey(request);
            int cost = requestCost(node, request, path);
            String client = clientKey(request, trustXff);
            if (!limiter.allow(client, cost)) {
                return errorJson(429, "rate limited").toPromise();
            }
            // Early shed of push-abusers (audit: gossip push ban-score): a client that kept
            // feeding invalid blocks / corrupt-signature transactions is refused BEFORE the
            // token check and the body decode, for the strike window. A per-client-key shed
            // like the limiter above — never an aggregate gate, so honest peers are unaffected.
            if ((isSubmitPost(request, path) || isAddTransactionPost(request, path)) && node.isPushShed(client)) {
                return errorJson(429, "push temporarily refused").toPromise();
            }
            // Optional bearer-token gate (RHIZOME_API_TOKEN) on the state-changing/operator
            // routes, checked BEFORE the aggregate gates below are consumed: an unauthenticated
            // flood must not burn the shared submit/mempool budgets that gated (authenticated)
            // peers depend on — a 401 is cheap, a global budget is not (audit: auth after
            // budgets). The P2P protocol endpoints stay open so peering keeps working (audit F4).
            // With protectReads (RHIZOME_PROTECT_READS) EVERY route is gated instead — the
            // private-node/private-explorer switch (audit: no read protection) — except the
            // static SPA/docs shell, which a plain browser navigation cannot bear a token for.
            if (apiToken != null && isTokenProtectedRoute(request, path, protectReads) && !bearerMatches(request, apiToken)) {
                return errorJson(401, "unauthorized").toPromise();
            }
            // Aggregate (all-IP) budget for the explorer reads that decode blocks under the consensus
            // lock: the per-IP limiter above cannot stop a distributed flood from summing past it, so a
            // process-wide bucket bounds the total lock-guarded decode work on the event-loop thread
            // (audit 5th-pass, net Finding 2). Shed over-budget reads before they touch the store.
            if (isConsensusLockRead(path) && !node.tryReadBudget(cost)) {
                return errorJson(429, "read budget exceeded").toPromise();
            }
            // Aggregate submit gate, consumed BEFORE the /submit handler decodes the block body. The
            // decode (up to maxBlockSizeBytes, ~25 000 tx allocations) and the memory-hard PoW hash it
            // triggers both run on the event-loop thread; the per-IP limiter cannot stop a distributed
            // flood from summing past it. Shedding here — rather than inside submitBlock, after the
            // decode already ran — closes the decode-before-gate asymmetry (audit S6).
            if (isSubmitPost(request, path) && !node.trySubmitBudget()) {
                return errorJson(429, "submit throttled").toPromise();
            }
            // Aggregate mempool-admission gate, consumed BEFORE the tx body is decoded — symmetric
            // to the /submit gate above. /add_transaction runs one Ed25519 verify per admission on
            // the event-loop thread and never caches invalid signatures, so without an aggregate cap
            // a distributed corrupt-signature flood sums past the per-IP limiter and pins the loop
            // (audit M1).
            if (isAddTransactionPost(request, path) && !node.tryMempoolSigBudget()) {
                return errorJson(429, "transaction throttled").toPromise();
            }
            // DNS-rebinding Host check for ALL browser-reachable requests when an allowlist is
            // configured — not just POSTs: a rebound page can otherwise READ every data endpoint
            // (/stats, /wallet, /logs, …) through the attacker's hostname (audit F6). The P2P
            // protocol endpoints fail open (peers send whatever Host) so peering isn't broken;
            // a missing Host header also fails open (HTTP/1.0 / non-browser CLI clients).
            if (allowedHosts != null && !allowedHosts.isEmpty() && !isPeerProtocolRequest(path)) {
                String host = request.getHeader(H_HOST);
                if (host != null && !host.isEmpty()
                    && !allowedHosts.contains(host.toLowerCase(java.util.Locale.ROOT))) {
                    return errorJson(403, "host not allowed").toPromise();
                }
            }
            // CSRF / DNS-rebinding guard on state-changing requests. A browser attaches an Origin
            // header on every POST, so any POST carrying an Origin is browser-originated. Such a
            // request is refused unless it is (a) same-origin AND (b) carries the non-simple
            // X-Rhizome-Request header. (a) blocks classic cross-site POSTs; (b) blocks DNS
            // rebinding, which defeats (a) by making Origin==Host — a rebinding page cannot set a
            // custom header without a CORS preflight this node never grants (no Access-Control-*
            // response), so the browser blocks the request. Peer and CLI clients send no Origin and
            // are unaffected, so P2P submit/gossip keeps working.
            if (request.getMethod() == POST && isForbiddenBrowserPost(request, allowedHosts)) {
                return errorJson(403, "cross-origin request refused").toPromise();
            }
            // Convert any handler failure (incl. body-size overflow) into a clean, generic
            // response; the detail is logged server-side only, never reflected to the client
            // (audit L3 — reflected exception text leaks internal detail for reconnaissance).
            return routing.serve(request).map(r -> r, e -> {
                log.debug("request handling failed: {}", ApiResponses.sanitizeForLog(e.toString()));
                return badRequest("bad request");
            });
        };
    }

    // ---- response bodies (JsonSink) ------------------------------------------------------------
    //
    // /total_work, /info and /peers are the highest-caution bodies in this file: they are read by
    // OTHER NODES over the sync/gossip protocol and by the wallet CLI with STRICT typed parsing,
    // not just by the dashboard — see each method's javadoc for the exact consumer and read.

    /** {@code totalWork} stays a JSON STRING (mirrors {@code DashboardApi#stats}): it is a
     *  {@code BigInteger} that can exceed 2^53, and {@code HttpPeerSource.totalWork()} does a
     *  strict {@code getString("totalWork")} read on it. */
    private static HttpResponse totalWorkResponse(NodeService node) {
        JsonSink sink = JsonSink.create(TOTAL_WORK_SIZE_HINT);
        sink.beginObject();
        sink.field(K_TOTAL_WORK, node.totalWork().toString());
        sink.endObject();
        return json(sink);
    }

    private static HttpResponse mempoolResponse(NodeService node) {
        JsonSink sink = JsonSink.create(MEMPOOL_SIZE_HINT);
        sink.beginObject();
        sink.field(K_SIZE, node.mempoolSize());
        sink.endObject();
        return json(sink);
    }

    /**
     * Peer/wallet identity and sync scalars. {@code chainId} stays a JSON NUMBER:
     * {@code WalletClient.chainId()} does a strict {@code getInt("chainId")} read that rejects a
     * quoted string. {@code prunedBelow} stays a JSON NUMBER too: {@code HttpPeerSource.prunedBelow()}
     * reads it with {@code optLong("prunedBelow", 0)}, and {@code PrunedNodeApiTest} asserts
     * {@code getLong} on it directly.
     */
    private static HttpResponse infoResponse(NodeService node) {
        JsonSink sink = JsonSink.create(INFO_SIZE_HINT);
        sink.beginObject();
        sink.field(K_CHAIN_ID, node.chainId());
        sink.field(K_NETWORK, node.networkName());
        sink.field(K_HEIGHT, node.blockCount());
        sink.field(K_DIFFICULTY, node.difficulty());
        sink.field(K_MEMPOOL, node.mempoolSize());
        sink.field(K_PRUNED_BELOW, node.prunedBelow());
        sink.field(K_SNAPSHOT_PIVOT, node.snapshotPivot());
        sink.field(K_STORAGE_FEE_FACTOR, node.voteableParams()[0]);
        sink.field(K_MIN_VALUE_PER_BYTE, node.voteableParams()[1]);
        sink.endObject();
        return json(sink);
    }

    /** {@code peers} stays a JSON array of STRINGS: {@code PeerDiscovery#fetchPeersPinned} reads
     *  it with {@code getJSONArray("peers")} and stringifies each element. */
    private static HttpResponse peersResponse(NodeService node) {
        java.util.List<String> peers = node.publicPeers();
        JsonSink sink = JsonSink.create(PEERS_BASE_SIZE_HINT + peers.size() * PEER_ENTRY_SIZE_HINT);
        sink.beginObject();
        sink.name(K_PEERS);
        sink.beginArray();
        for (String peer : peers) {
            sink.value(peer);
        }
        sink.endArray();
        sink.endObject();
        return json(sink);
    }

    private static HttpResponse statusOkResponse() {
        JsonSink sink = JsonSink.create(STATUS_OK_SIZE_HINT);
        sink.beginObject();
        sink.field(K_STATUS, "OK");
        sink.endObject();
        return json(sink);
    }

    private static HttpResponse scanIdResponse(int id) {
        JsonSink sink = JsonSink.create(SCAN_ID_SIZE_HINT);
        sink.beginObject();
        sink.field(K_SCAN_ID, id);
        sink.endObject();
        return json(sink);
    }

    private static HttpResponse removedResponse(boolean removed) {
        JsonSink sink = JsonSink.create(REMOVED_SIZE_HINT);
        sink.beginObject();
        sink.field(K_REMOVED, removed);
        sink.endObject();
        return json(sink);
    }

    // ---- middleware: cost model, budgets and the browser guard ----

    /**
     * Reorg-window gate for the chain-serving routes (/blocks, /block, /block_count,
     * /total_work — plus /sync and /headers in {@link SyncApi}): while the node's canonical
     * chain sits truncated at a fork height it must not serve the chain as authoritative —
     * the same doctrine the write side already enforces (beginReorgWindow → new-tip addBlock
     * fails IS_SYNCING). A peer reads these routes during a reorg window and gets a
     * transport failure (retried, never penalized) instead of a truncated view it could
     * misread as a protocol violation and ban the node for (testnet campaign S5).
     */
    private static HttpResponse whenNotReorging(NodeService node,
                                                java.util.function.Supplier<HttpResponse> serve) {
        if (node.isReorgInProgress()) {
            return SyncApi.busyDuringReorg();
        }
        return serve.get();
    }

    /**
     * Runs a consensus-heavy handler on the bounded worker pool (when wired) instead of the
     * event-loop thread, mapping failures to the same generic 400 as {@code guarded}. A full
     * worker queue sheds the request with 429 rather than queueing unbounded latency.
     */
    private static Promise<HttpResponse> offload(java.util.concurrent.Executor blocking,
                                                 java.util.concurrent.Callable<HttpResponse> job) {
        if (blocking == null) {
            return guarded(job);
        }
        // Submit explicitly rather than via Promise.ofBlocking: ofBlocking catches
        // RejectedExecutionException itself and completes the promise exceptionally (→ 500),
        // so a try/catch around it would be dead code and a saturated pool could never yield
        // the intended 429 (audit review). The completion below mirrors ofBlocking's protocol
        // exactly: the promise is completed ON the reactor (never from the worker thread) and
        // the external-task counter keeps the eventloop alive while the job runs.
        // (v7.0.0 keeps ofBlocking's signature and semantics; verified via NodeApiTest 429 path.)
        SettablePromise<HttpResponse> result = new SettablePromise<>();
        Reactor reactor = Reactor.getCurrentReactor();
        reactor.startExternalTask();
        try {
            blocking.execute(() -> {
                try {
                    HttpResponse response = guardedResponse(job);
                    reactor.execute(() -> result.set(response));
                } catch (Throwable t) {
                    reactor.execute(() ->
                        result.setException(t instanceof Exception e ? e : new RuntimeException(t)));
                } finally {
                    reactor.completeExternalTask();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            reactor.completeExternalTask();
            return errorJson(429, "server busy").toPromise();
        }
        return result;
    }

    /**
     * True when a browser-originated POST must be refused. A request carrying an {@code Origin} is
     * browser-originated (browsers attach Origin to every POST); it is allowed only when it is
     * same-origin ({@code Origin} authority == {@code Host}) AND carries the {@code X-Rhizome-Request}
     * header. The same-origin check stops classic cross-site POSTs; the custom-header requirement
     * stops DNS rebinding at the Host layer: when {@code allowedHosts} is configured, the request's
     * {@code Host} must be one of the node's legitimate authorities — a rebound page carries the
     * attacker's own hostname as Host, so it is rejected even though Origin==Host makes it look
     * same-origin (the marker/CORS-preflight reasoning alone does NOT stop rebinding, since a
     * same-origin page sets custom headers freely — audit S-2). The Origin+marker check remains the
     * cross-site layer. Requests with no {@code Origin} (peers, CLI — not browsers, so not a CSRF
     * vector) are always allowed. Fails open only when a present {@code Origin}/{@code Host} is
     * unparseable and no allowlist is configured.
     */
    private static boolean isForbiddenBrowserPost(HttpRequest request, java.util.Set<String> allowedHosts) {
        try {
            String origin = request.getHeader(H_ORIGIN);
            if (origin == null || origin.isEmpty()) {
                return false; // not a browser request
            }
            String host = request.getHeader(H_HOST);
            if (host == null || host.isEmpty()) {
                return false;
            }
            // Host allowlist: the load-bearing anti-rebinding control when configured. A rebound page's
            // Host is the attacker's hostname, absent from the node's legitimate authorities → refuse.
            if (allowedHosts != null && !allowedHosts.isEmpty()
                && !allowedHosts.contains(host.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
            java.net.URI originUri = java.net.URI.create(origin);
            if (!originMatchesHost(originUri, host)) {
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
     * Same-origin test for the CSRF guard: the Origin's host must equal the {@code Host} header's
     * host, comparing EFFECTIVE ports — a browser omits the scheme's default port in {@code Origin}
     * ({@code http://h} for {@code http://h:80}, {@code https://h} for {@code https://h:443}) while
     * a proxy or explicit client may still send {@code Host: h:80}. A literal authority string
     * comparison rejected those same-origin requests as cross-site (audit: CSRF default-port false
     * positive). A missing port on either side resolves to the Origin scheme's default; any
     * unparseable shape fails closed (refused as cross-site).
     */
    private static boolean originMatchesHost(java.net.URI origin, String hostHeader) {
        String originHost = origin.getHost();
        if (originHost == null || originHost.isEmpty()) {
            return false;
        }
        String scheme = origin.getScheme();
        int defaultPort = "http".equalsIgnoreCase(scheme) ? 80
            : "https".equalsIgnoreCase(scheme) ? 443 : -1;
        int originPort = origin.getPort() >= 0 ? origin.getPort() : defaultPort;
        if (hostHeader == null || hostHeader.isEmpty()) {
            return false;
        }
        // Split host[:port], keeping IPv6 brackets: the port separator is the LAST ':' only
        // when it follows any ']' (or there is no bracket) and is trailed by digits.
        String host = hostHeader;
        int hostPort = defaultPort;
        int colon = hostHeader.lastIndexOf(':');
        if (colon > hostHeader.indexOf(']')) {
            String port = hostHeader.substring(colon + 1);
            boolean digits = !port.isEmpty();
            for (int i = 0; i < port.length(); i++) {
                digits &= Character.isDigit(port.charAt(i));
            }
            if (!digits) {
                return false; // a non-numeric port is not a same-origin Host — fail closed
            }
            try {
                hostPort = Integer.parseInt(port);
            } catch (NumberFormatException e) {
                return false; // out-of-int-range port — fail closed
            }
            host = hostHeader.substring(0, colon);
        }
        return originHost.equalsIgnoreCase(host) && originPort == hostPort;
    }

    /**
     * The path the ROUTER matches on — which is <em>not</em> {@link HttpRequest#getPath()}.
     *
     * <p>ActiveJ dispatches on percent-DECODED path segments and treats the first EMPTY segment as
     * "serve at the node reached so far" ({@code UrlParser.pollUrlPart} feeding
     * {@code RoutingServlet.tryServe}), while {@code getPath()} returns the raw, undecoded slice.
     * Every policy predicate in this file compares a path against route literals, so classifying on
     * the raw slice missed requests the router happily dispatched: {@code POST /submit/},
     * {@code /submit//x}, {@code /%73ubmit} and {@code /submit&x} all reach the {@code /submit}
     * handler while failing {@code "/submit".equals(path)} — escaping the bearer gate, the push
     * shed, the aggregate submit budget and the request cost alike. Normalising once, here, closes
     * the whole class rather than the four spellings.
     *
     * <p>The decoding mirrors {@code UrlParser.urlParse} exactly: {@code +} becomes a space,
     * {@code %XX} decodes (a truncated or non-hex escape is a hard failure), and {@code &} or
     * {@code #} terminate the segment.
     *
     * @return the decoded path the router will dispatch on, or {@code null} when a segment carries
     *         bad percent-encoding — the router answers 400 itself in that case, so no handler runs
     *         and every caller can safely treat it as "no route".
     */
    static String routingKey(HttpRequest request) {
        String raw;
        try {
            raw = request.getPath();
        } catch (RuntimeException e) {
            return null;
        }
        if (raw.isEmpty() || raw.charAt(0) != '/') {
            return null;
        }
        StringBuilder key = new StringBuilder(raw.length());
        int pos = 0;
        while (pos < raw.length()) {
            int start = pos + 1;
            int nextSlash = raw.indexOf('/', start);
            int end = nextSlash < 0 ? raw.length() : nextSlash;
            String segment = decodeSegment(raw, start, end);
            if (segment == null) {
                return null; // bad percent-encoding: the router rejects the request with 400
            }
            if (segment.isEmpty()) {
                break; // an empty segment ends the walk — the router serves at this node
            }
            key.append('/').append(segment);
            pos = end;
        }
        return key.isEmpty() ? "/" : key.toString();
    }

    /**
     * One path segment, decoded the way {@code UrlParser.urlParse} decodes it. Returns {@code null}
     * for a truncated ({@code "%4"}) or non-hex ({@code "%zz"}) escape, matching the parser's own
     * null return that {@code tryServe} turns into a 400.
     */
    private static String decodeSegment(String raw, int start, int end) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(end - start);
        int i = start;
        while (i < end) {
            char c = raw.charAt(i);
            if (c == '&' || c == '#') {
                break; // the parser ends the segment here
            }
            if (c == '+') {
                out.write(' ');
                i++;
            } else if (c == '%') {
                if (i + 2 >= end) {
                    return null; // parser guard: (pos + 2 < limit) fails, then b == '%' -> null
                }
                int hi = hexDigit(raw.charAt(i + 1));
                int lo = hexDigit(raw.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    return null; // decodeHex throws, the parser catches it and returns null
                }
                out.write((hi << 4) + lo);
                i += 3;
            } else {
                out.write(c & 0xFF);
                i++;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * Rate-limit cost of a request. Cheap endpoints cost 1; the deep chain scans and the VM
     * dry-run cost proportionally more so a single client cannot drive orders of magnitude more
     * work than the flat per-request budget implies (audit M2).
     */
    static int requestCost(HttpRequest request) {
        return requestCost(null, request);
    }

    /**
     * As {@link #requestCost(HttpRequest)}, with the node available so byte-sized responses
     * (the state-snapshot chunk) can be charged proportionally to the bytes they will serve.
     */
    static int requestCost(NodeService node, HttpRequest request) {
        return requestCost(node, request, routingKey(request));
    }

    /**
     * As above, against a {@link #routingKey(HttpRequest)} the caller already computed — the
     * middleware resolves the key once per request and hands the same value to every classifier.
     */
    private static int requestCost(NodeService node, HttpRequest request, String path) {
        if (path == null) {
            return 1; // unroutable: the router rejects it before any handler work
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
            // These two scans decode up to `depth` FULL blocks from RocksDB under the consensus lock
            // (ExplorerApi.findTransaction / addressTransactions call node.block(h) per height, worst
            // case a never-matching txid/address running the whole depth) — the same cost class as
            // /blocks and /stats, which are weighted ~1 unit per block decoded. The old depth/20
            // (SCAN_COST_PER_BLOCKS, a light header-scan rate) under-weighted them ~20x, so one IP
            // could drive ~20x the lock-guarded block decodes/s the aggregate readGate was sized to
            // admit. Weight by the blocks actually read (audit: explorer full-block scans under-weighted).
            return depth;
        }
        if ("/call_readonly".equals(path)) {
            return CALL_READONLY_COST;
        }
        if ("/submit".equals(path)) {
            return SUBMIT_COST;
        }
        if ("/add_transaction".equals(path) || "/add_transaction_json".equals(path)) {
            return TX_SUBMIT_COST;
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
        // /logs was left at cost 1, yet its fromHeight cursor scan walks up to LOG_SCAN_WINDOW
        // heights × 3 event sources (contract + box + token) per call (audit F2). Weight the
        // cursor scan by its bounded span — the /logs request carries no end parameter, so the
        // served span is the window cap itself; a single-height lookup stays at cost 1. Not added
        // to the aggregate read gate below: the event sources are per-height map/store reads, not
        // full-block decodes under the consensus lock, so the readGate's rationale does not apply.
        if ("/logs".equals(path)) {
            try {
                String height = request.getQueryParameter("height");
                if (height != null && !height.isEmpty()) {
                    return 1;
                }
            } catch (RuntimeException ignored) {
                // malformed query: the handler rejects it; charge the bounded-scan cost
            }
            return NodeService.LOG_SCAN_WINDOW;
        }
        // /state/snapshot/chunk serves a ~MiB-scale binary chunk (the peer-side fetch cap is
        // 16 MiB); a flat per-request cost is a bandwidth amplifier (audit F3). Charge
        // proportionally to the chunk's actual size when it is resolvable at the gate.
        if ("/state/snapshot/chunk".equals(path)) {
            return snapshotChunkCost(node, request);
        }
        if ("/orphan".equals(path)) {
            return ORPHAN_COST;
        }
        if ("/add_peer".equals(path)) {
            return ADD_PEER_COST;
        }
        // /scan/boxes examines up to BOX_SCAN_WINDOW boxes per call (bounded store reads, no
        // consensus lock but real disk I/O) — leaving it at cost 1 let a client drive ~512k
        // reads/s inside its 1000 req/s budget (audit: scan cost under-weighted). Weight it like
        // the /logs cursor scan (~1 unit per 4 reads), so the same budget admits ~7 scans/s.
        if ("/scan/boxes".equals(path)) {
            return NodeService.BOX_SCAN_WINDOW / 4;
        }
        // /boxes and /tokens fan one request out into ~101 and ~201 CONSENSUS-LOCKED store reads
        // (see the constants). They sat at the default cost of 1 — cheaper per lock acquisition
        // than any other read in the table — because nothing ties registering a route to weighting
        // it (audit: unclassified routes fall through to 1).
        if ("/boxes".equals(path)) {
            return BOXES_COST;
        }
        if ("/tokens".equals(path)) {
            return TOKENS_COST;
        }
        return 1;
    }

    /**
     * The reads charged to the process-wide aggregate read budget (NodeService.tryReadBudget) so
     * a distributed flood can't sum past the per-IP limiter and pin the event loop / contend the
     * consensus lock (audit 5th-pass, net Finding 2). Two families:
     * <ul>
     *   <li>the browser-facing explorer reads ({@code /stats}, {@code /blocks}, {@code /block},
     *       {@code /transaction}, {@code /address_txs}), which fully decode blocks from RocksDB
     *       under the consensus lock;</li>
     *   <li>the peer-serving heavyweights {@code /sync}, {@code /headers} and
     *       {@code /state/snapshot/chunk}: /sync and /headers run the same lock-guarded store
     *       reads per block served, and all three amplify egress by up to hundreds of blocks (or
     *       ~16 MiB) per request, so leaving them ungated let a flood of "peers" drive unbounded
     *       lock-holding reads and outbound bandwidth at cost ~1 (audit: aggregate bound on the
     *       sync/snapshot serving paths). Honest sync still fits the aggregate budget — it is
     *       sized far above any plausible convergence traffic.</li>
     *   <li>the box/token listings {@code /boxes} and {@code /tokens}, which fan a single request
     *       into ~101 and ~201 lock-guarded store reads. They were outside this gate <em>and</em>
     *       at the default cost of 1, so they escaped both bounds at once.</li>
     * </ul>
     *
     * <p>Deliberately NOT here: {@code /scan/boxes} and {@code /logs}. Both are weighted, but
     * {@code ChainEngine.scanBoxes} runs OUTSIDE the consensus lock behind the stamp seqlock, and
     * {@code /logs} does per-height map/store reads rather than lock-guarded block decodes — this
     * gate bounds lock contention specifically, not store I/O in general.
     */
    private static boolean isConsensusLockRead(String path) {
        if (path == null) {
            return false;
        }
        return "/stats".equals(path) || "/blocks".equals(path) || "/block".equals(path)
            || "/transaction".equals(path) || "/address_txs".equals(path)
            || "/sync".equals(path) || "/headers".equals(path) || "/state/snapshot/chunk".equals(path)
            || "/orphan".equals(path)
            || "/boxes".equals(path) || "/tokens".equals(path);
    }

    /** True for a POST /submit — the block-ingest route whose body decode must be gated (audit S6). */
    private static boolean isSubmitPost(HttpRequest request, String path) {
        return request.getMethod() == POST && "/submit".equals(path);
    }

    /** True for a POST /add_transaction(JSON) — the tx-ingest routes gated like /submit (audit M1). */
    private static boolean isAddTransactionPost(HttpRequest request, String path) {
        return request.getMethod() == POST
            && ("/add_transaction".equals(path) || "/add_transaction_json".equals(path));
    }

    /**
     * The state-changing/operator routes gated by {@code RHIZOME_API_TOKEN} when configured
     * (audit F4). The P2P protocol endpoints ({@code /sync}, {@code /headers}, {@code /blocks},
     * {@code /peers}, {@code /block_count}, {@code /total_work}) deliberately stay open so
     * peering keeps working. Note {@code /submit} and {@code /add_transaction} ARE gated: they
     * are the operator's block/tx ingest routes and already carry their own anti-DoS budgets —
     * an operator enabling the token on a gossiping node must have peers present it too.
     *
     * <p>With {@code protectReads} it instead gates EVERY route (any method) — the
     * RHIZOME_PROTECT_READS private-node switch: the read surface (stats, balances, SSE logs) is
     * otherwise public by design even with a token configured (audit: no read protection). The
     * static SPA/docs shell is EXEMPT ({@link #isSpaShell}): a browser cannot attach a bearer to a
     * plain navigation, so gating {@code /} or {@code /dashboard/app.js} would make the embedded
     * explorer unreachable (audit 17th pass). The shell is static content identical for every
     * client; the SPA's own fetches ({@code /stats}, {@code /blocks}, …) carry the bearer and stay
     * gated.
     *
     * <p>An unroutable path ({@code path == null}) is gated under protectReads and ungated
     * otherwise, exactly as before: the router answers 400/404 for it either way.
     */
    private static boolean isTokenProtectedRoute(HttpRequest request, String path, boolean protectReads) {
        if (protectReads) {
            return !isSpaShell(request, path); // private node: everything but the static SPA shell
        }
        if (request.getMethod() != POST) {
            return false;
        }
        if (path == null) {
            return false;
        }
        return switch (path) {
            case "/add_peer", "/scan/register", "/scan/deregister",
                 "/add_transaction", "/add_transaction_json", "/submit", "/call_readonly" -> true;
            default -> false;
        };
    }

    /**
     * True for the static SPA/docs shell served to browsers: {@code GET /}, {@code /dashboard}
     * and the asset trees under {@code /dashboard/} and {@code /docs/}. These paths serve only
     * bundled static files (no chain data), so exempting them from the protectReads bearer
     * leaks nothing a client could not read out of the jar.
     *
     * <p>{@code /dashboard} and {@code /docs} (no trailing slash) are shell too: ActiveJ hangs a
     * wildcard route off the node itself, so both reach the same static handler as
     * {@code /docs/index.md} does. Classifying them as chain data made {@code /docs} bearer-gated
     * while {@code /docs/} was not, for identical content.
     */
    private static boolean isSpaShell(HttpRequest request, String path) {
        if (request.getMethod() != GET || path == null) {
            return false;
        }
        return "/".equals(path) || "/dashboard".equals(path) || "/docs".equals(path)
            || path.startsWith("/dashboard/") || path.startsWith("/docs/");
    }

    /** Constant-time check of {@code Authorization: Bearer <token>} against the configured token. */
    private static boolean bearerMatches(HttpRequest request, String token) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        // MessageDigest.isEqual: a naive equals() short-circuits on the first mismatch and leaks
        // the correct prefix via timing (audit F4).
        return java.security.MessageDigest.isEqual(
            header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The P2P protocol surface: endpoints a peer's sync/PEX/gossip clients call. These fail open
     * on the Host-allowlist check (audit F6) because peers legitimately send whatever Host (and
     * no Origin) — gating them would break peering. Everything else (the browser-reachable data
     * endpoints) is Host-checked when an allowlist is configured.
     */
    private static boolean isPeerProtocolRequest(String path) {
        if (path == null) {
            return false;
        }
        return switch (path) {
            case "/sync", "/headers", "/blocks", "/block", "/peers", "/block_count", "/total_work",
                 "/info", "/state/snapshot/info", "/state/snapshot/chunk", "/orphan",
                 "/add_peer", "/add_transaction", "/add_transaction_json", "/submit" -> true;
            default -> false;
        };
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

    /**
     * Rate-limit cost of serving one state-snapshot chunk, proportional to the bytes actually
     * served: {@code ceil(chunkBytes / 64 KiB)} floored at {@link #SNAPSHOT_CHUNK_MIN_COST}, so
     * the per-IP token budget maps to a bounded egress rate (audit F3 — a flat cost let one IP
     * pull ~200 MiB/s at the default 1000 units/s). Falls back to the flat
     * {@link #SNAPSHOT_CHUNK_COST} when the chunk is not resolvable at the gate — no snapshot
     * materialised, or a missing/malformed/out-of-range index — because the handler then
     * answers 404/400 without serving any chunk bytes. Only the chunk's recorded length is
     * read here (the in-RAM spool index); the chunk bytes themselves are never touched at
     * the gate.
     */
    private static int snapshotChunkCost(NodeService node, HttpRequest request) {
        var snap = node == null ? null : node.materializedSnapshot();
        if (snap == null) {
            return SNAPSHOT_CHUNK_COST; // 404 path: flat
        }
        long index;
        try {
            String raw = request.getQueryParameter("index");
            if (raw == null) {
                return SNAPSHOT_CHUNK_COST; // the handler rejects it cheaply
            }
            index = Long.parseLong(raw.trim());
        } catch (RuntimeException e) {
            return SNAPSHOT_CHUNK_COST; // malformed: the handler rejects it cheaply
        }
        if (index < 0 || index >= snap.chunkCount()) {
            return SNAPSHOT_CHUNK_COST; // 400/404 path: flat
        }
        long units = (snap.chunkLength((int) index) + CHUNK_COST_UNIT_BYTES - 1)
            / CHUNK_COST_UNIT_BYTES;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(SNAPSHOT_CHUNK_MIN_COST, units));
    }

    /**
     * The identity a scan request acts under: the client key, plus the optional
     * {@code X-Scan-Owner} secret that makes ownership independent of the source address
     * (audit B-5). A header rather than a query parameter: the value is a capability, and query
     * strings end up in access logs, proxies and browser history.
     */
    private static ScanRegistry.Owner scanOwner(HttpRequest request) {
        return scanOwner(request, false);
    }

    /** As {@link #scanOwner(HttpRequest)}, honouring the trusted-proxy XFF hop. */
    private static ScanRegistry.Owner scanOwner(HttpRequest request, boolean trustXff) {
        String secret;
        try {
            secret = request.getHeader(H_SCAN_OWNER);
        } catch (RuntimeException e) {
            secret = null;
        }
        if (secret != null && secret.length() > SCAN_OWNER_MAX_CHARS) {
            secret = secret.substring(0, SCAN_OWNER_MAX_CHARS);
        }
        return ScanRegistry.Owner.of(clientKey(request, trustXff), secret);
    }

    private static String clientKey(HttpRequest request) {
        return clientKey(request, false);
    }

    /**
     * As {@link #clientKey(HttpRequest)}, but when {@code trustXff} the FIRST
     * {@code X-Forwarded-For} hop replaces the socket address — for nodes only reachable
     * through a trusted reverse proxy (audit: no reverse-proxy support). The header value is
     * parsed as an IP LITERAL ONLY (never via a DNS lookup, which a spoofed hostname would
     * otherwise trigger on the event loop); anything unparsable falls back to the socket
     * address, so a forged header can only miskey its own sender, never target a victim's
     * budget.
     */
    private static String clientKey(HttpRequest request, boolean trustXff) {
        // getRemoteAddress() runs a checkNotNull under ActiveJ's CHECKS flag, so a
        // request with no live connection (tests, in-process calls) throws instead
        // of returning null; treat any such request as a single "local" bucket.
        try {
            java.net.InetAddress addr = clientAddress(request, trustXff);
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
     * Aggregate key one tier up from {@link #clientKey}: the IPv6 /48 (first 6 bytes of the
     * address) — the typical ISP/site allocation, which hands out 2^16 /64s — or the IPv4 /24.
     * The per-client SSE cap keys on the /64 (or the full v4 address), so rotating inside the
     * site allocation could still exhaust the global subscriber cap: ~64 IPv4 addresses suffice
     * at 4 streams each against a 256-slot hub, and a /48 trivially so. This second tier bounds
     * the site in aggregate on both families (audit F5; v4 gap closed in the SSE-cap pass).
     */
    private static String clientSubnetKey(HttpRequest request) {
        return clientSubnetKey(request, false);
    }

    /** As {@link #clientSubnetKey(HttpRequest)}, honouring the trusted-proxy XFF hop. */
    private static String clientSubnetKey(HttpRequest request, boolean trustXff) {
        try {
            java.net.InetAddress addr = clientAddress(request, trustXff);
            if (addr == null) {
                return "local";
            }
            byte[] b = addr.getAddress();
            if (b.length == 16) {
                StringBuilder sb = new StringBuilder("v6net:");
                for (int i = 0; i < 6; i++) {
                    sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16)).append(Character.forDigit(b[i] & 0xF, 16));
                }
                return sb.toString();
            }
            return "v4net:" + (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF);
        } catch (RuntimeException e) {
            return "local";
        }
    }

    private static final HttpHeader H_X_FORWARDED_FOR = HttpHeaders.of("X-Forwarded-For");

    /**
     * The address identifying this request's client: the socket address, or — when
     * {@code trustXff} and an {@code X-Forwarded-For} header is present — the FIRST hop of
     * that header, resolved by {@link #resolveXffHop} as an IP LITERAL only (never a hostname,
     * so no DNS lookup can be triggered on the event loop by a spoofed header). An absent or
     * unparsable hop falls back to the socket address: a client can then only miskey itself.
     */
    static java.net.InetAddress clientAddress(HttpRequest request, boolean trustXff) { // package-private for tests
        java.net.InetAddress socket = request.getRemoteAddress();
        if (!trustXff) {
            return socket;
        }
        String xff;
        try {
            xff = request.getHeader(H_X_FORWARDED_FOR);
        } catch (RuntimeException e) {
            return socket;
        }
        return resolveXffHop(socket, xff);
    }

    /**
     * The XFF first hop resolved to an address, or {@code socket} when the header is absent or
     * carries no usable literal (package-private for tests). IPv4 hops are parsed octet-by-octet
     * with a 0..255 range check and built via {@link java.net.InetAddress#getByAddress(byte[])}:
     * a dotted quad with an overflowing octet ({@code 300.1.1.1}) is NOT an IPv4 literal, and
     * handing it to {@code getByName} would fall back to a BLOCKING resolver lookup of it as a
     * hostname — seconds of event-loop stall per spoofed request, a worse DoS than the
     * rate-limit evasion this flag exists to prevent (audit 17th pass). Values containing
     * {@code ':'} go through {@code getByName}: the JDK only accepts those as IPv6 literals and
     * throws immediately on anything else, never consulting DNS.
     */
    static java.net.InetAddress resolveXffHop(java.net.InetAddress socket, String xff) {
        if (xff == null) {
            return socket;
        }
        try {
            int comma = xff.indexOf(',');
            String first = (comma < 0 ? xff : xff.substring(0, comma)).trim();
            if (first.indexOf(':') >= 0) {
                // IPv6 literal only: invalid forms throw fast, with no resolver round-trip.
                return java.net.InetAddress.getByName(first);
            }
            byte[] v4 = parseIpv4Literal(first);
            if (v4 == null) {
                return socket; // hostname or junk: never resolved (no DNS on the loop)
            }
            return java.net.InetAddress.getByAddress(v4);
        } catch (RuntimeException | java.net.UnknownHostException e) {
            return socket;
        }
    }

    /**
     * Parses {@code a.b.c.d} — four decimal octets, each 0..255 — into 4 bytes, or returns
     * {@code null} when the value is not a valid dotted quad. Leading zeros parse as decimal
     * ({@code 010.0.0.1} → {@code 10.0.0.1}), exactly like the JDK literal parser; hex and
     * shorthand forms are rejected.
     */
    static byte[] parseIpv4Literal(String s) { // package-private for tests
        byte[] out = new byte[4];
        int start = 0;
        for (int i = 0; i < 4; i++) {
            int end = i < 3 ? s.indexOf('.', start) : s.length();
            if (end < 0 || end - start < 1 || end - start > 3) {
                return null; // missing dot, or an empty / over-long octet
            }
            int value = 0;
            for (int j = start; j < end; j++) {
                char c = s.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null; // octet overflow: not a literal — getByName would DNS-resolve it
            }
            out[i] = (byte) value;
            start = end + 1;
        }
        return out;
    }
}
