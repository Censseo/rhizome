package rhizome.node;

import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;

import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

import static rhizome.node.ApiResponses.json;

/**
 * Browser-facing dashboard endpoints: the embedded SPA assets (with their
 * security headers) plus the {@code /stats} overview and {@code /features}
 * capability discovery the UI bootstraps from.
 *
 * <p>Bodies are written with {@link JsonSink} instead of an {@code org.json} tree — see that
 * class's Javadoc. The size-hint constants below are allocation hints only: {@link JsonSink}
 * grows its buffer automatically, so an under-estimate just costs a doubling, not a correctness
 * bug.
 */
final class DashboardApi {

    // Security headers for the browser-facing dashboard. The SPA loads only same-origin
    // scripts/styles (no inline <script>), talks only to its own node (fetch + SSE), and
    // uses inline style attributes — hence 'unsafe-inline' for style only. frame-ancestors
    // 'none' + X-Frame-Options DENY block clickjacking; a restrictive CSP contains any
    // injected markup and stops an attacker's inline script from reading wallet keys.
    private static final HttpHeader H_CSP = HttpHeaders.of("Content-Security-Policy");
    private static final HttpHeader H_XFO = HttpHeaders.of("X-Frame-Options");
    private static final HttpHeader H_REFERRER = HttpHeaders.of("Referrer-Policy");
    private static final String DASHBOARD_CSP =
        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
        + "script-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    /** How many recent blocks the /stats aggregates cover (block time, tx rate). */
    static final int STATS_WINDOW = 32;

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_CHAIN_ID = Key.of("chainId");
    private static final Key K_NETWORK = Key.of("network");
    private static final Key K_HEIGHT = Key.of("height");
    private static final Key K_TIP_HASH = Key.of("tipHash");
    private static final Key K_DIFFICULTY = Key.of("difficulty");
    private static final Key K_TOTAL_WORK = Key.of("totalWork");
    private static final Key K_MEMPOOL = Key.of("mempool");
    private static final Key K_PEERS = Key.of("peers");
    private static final Key K_DESIRED_BLOCK_TIME_SEC = Key.of("desiredBlockTimeSec");
    private static final Key K_DECIMAL_SCALE_FACTOR = Key.of("decimalScaleFactor");
    private static final Key K_MINING_REWARD = Key.of("miningReward");
    private static final Key K_MAX_REORG_DEPTH = Key.of("maxReorgDepth");
    private static final Key K_LAST_BLOCK_TIMESTAMP = Key.of("lastBlockTimestamp");
    private static final Key K_AVG_BLOCK_INTERVAL_MS = Key.of("avgBlockIntervalMs");
    private static final Key K_WINDOW_BLOCKS = Key.of("windowBlocks");
    private static final Key K_WINDOW_TX_COUNT = Key.of("windowTxCount");
    private static final Key K_STORAGE_PERIOD_BLOCKS = Key.of("storagePeriodBlocks");
    private static final Key K_STORAGE_FEE_FACTOR = Key.of("storageFeeFactor");
    private static final Key K_MIN_VALUE_PER_BYTE = Key.of("minValuePerByte");
    private static final Key K_MAX_BOX_REGISTERS = Key.of("maxBoxRegisters");
    private static final Key K_STATE_ROOT = Key.of("stateRoot");
    private static final Key K_DEGRADED = Key.of("degraded");
    private static final Key K_REORG_IN_PROGRESS = Key.of("reorgInProgress");
    private static final Key K_SYNC_ROUNDS_WITHOUT_PROGRESS = Key.of("syncRoundsWithoutProgress");
    private static final Key K_SYNC_PEERS_BANNED = Key.of("syncPeersBanned");
    private static final Key K_SYNC_ECLIPSED = Key.of("syncEclipsed");

    private static final Key K_DASHBOARD = Key.of("dashboard");
    private static final Key K_CONTRACTS = Key.of("contracts");
    private static final Key K_CONTRACT_QUERY = Key.of("contractQuery");
    private static final Key K_LOG_STREAM = Key.of("logStream");
    private static final Key K_AGENTS = Key.of("agents");
    private static final Key K_BOXES = Key.of("boxes");
    private static final Key K_TOKENS = Key.of("tokens");

    // -- JsonSink size hints (see class javadoc) -----------------------------------------------
    private static final int STATS_SIZE_HINT = 768;
    private static final int FEATURES_SIZE_HINT = 192;

    private DashboardApi() {}

    static HttpResponse asset(DashboardAssets.Asset asset) {
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, asset.contentType())
            .withHeader(H_CSP, DASHBOARD_CSP)
            .withHeader(H_XFO, "DENY")
            .withHeader(ApiResponses.H_XCTO, "nosniff")
            .withHeader(H_REFERRER, "no-referrer")
            .withBody(asset.bytes())
            .build();
    }

    /**
     * One-call network overview for the dashboard: chain identity, tip state and
     * aggregates over the last {@link #STATS_WINDOW} blocks (average block interval,
     * transaction count). Everything here is already public via other endpoints —
     * this only saves the UI a request storm.
     */
    static HttpResponse stats(NodeService node) {
        long height = node.blockCount();
        var params = node.params();
        // The window aggregate (decodes STATS_WINDOW blocks) is cached by tip height in NodeService, so
        // repeated dashboard polls at the same tip don't re-decode the blocks.
        var window = node.statsWindow(STATS_WINDOW);
        long windowStart = window.windowStart();
        long txCount = window.txCount();
        long firstTs = window.firstTs();
        long lastTs = window.lastTs();
        long spanBlocks = height - windowStart;
        long avgIntervalMs = spanBlocks > 0 ? (lastTs - firstTs) / spanBlocks : 0;

        JsonSink sink = JsonSink.create(STATS_SIZE_HINT);
        sink.beginObject();
        sink.field(K_CHAIN_ID, node.chainId());
        sink.field(K_NETWORK, node.networkName());
        sink.field(K_HEIGHT, height);
        // The branch this node is on. Height, difficulty and (on a metastable split) total
        // work are all equal across camps mining at the same rate — the tip is the only
        // field that differs, so it is what makes a silent split observable.
        sink.hexUpper(K_TIP_HASH, node.tipHash().toBytes());
        sink.field(K_DIFFICULTY, node.difficulty());
        // totalWork stays a JSON STRING: it's a BigInteger that can exceed 2^53, and the
        // dashboard reads it with JS BigInt(s.totalWork) — DashboardApiTest asserts getString.
        sink.field(K_TOTAL_WORK, node.totalWork().toString());
        sink.field(K_MEMPOOL, node.mempoolSize());
        sink.field(K_PEERS, node.knownPeers().size());
        sink.field(K_DESIRED_BLOCK_TIME_SEC, params.desiredBlockTimeSec());
        sink.field(K_DECIMAL_SCALE_FACTOR, params.decimalScaleFactor());
        // Height-only (geometric) reward: exact on every shipped network, where
        // emissionCurveHeight == 0 (never active). On the first curve-active network this must
        // become the supply-aware dispatch miningReward(height, tipParentSupply) — which needs
        // the tip's committed supply, a surface NodeService does not expose yet. Display-only;
        // tracked as part of feature 05 (curve activation), which schedules the height.
        sink.field(K_MINING_REWARD, params.miningReward(height));
        sink.field(K_MAX_REORG_DEPTH, params.maxReorgDepth());
        sink.field(K_LAST_BLOCK_TIMESTAMP, lastTs);
        sink.field(K_AVG_BLOCK_INTERVAL_MS, avgIntervalMs);
        sink.field(K_WINDOW_BLOCKS, height - windowStart + 1);
        sink.field(K_WINDOW_TX_COUNT, txCount);
        // Box economics the UI needs to build BOX_* transactions client-side.
        sink.field(K_STORAGE_PERIOD_BLOCKS, params.storagePeriodBlocks());
        sink.field(K_STORAGE_FEE_FACTOR, node.voteableParams()[0]);
        sink.field(K_MIN_VALUE_PER_BYTE, node.voteableParams()[1]);
        sink.field(K_MAX_BOX_REGISTERS, params.maxBoxRegisters());
        byte[] stateRoot = node.stateRoot();
        if (stateRoot == null) {
            sink.fieldNull(K_STATE_ROOT);
        } else {
            sink.hexLower(K_STATE_ROOT, stateRoot);
        }
        // Operator-visible degraded marker (e.g. failed reorg restore); null when healthy.
        String degraded = node.degradedState();
        if (degraded == null) {
            sink.fieldNull(K_DEGRADED);
        } else {
            sink.field(K_DEGRADED, degraded);
        }
        // A normal reorg window also pauses block production — distinguishable from degraded.
        sink.field(K_REORG_IN_PROGRESS, node.isReorgInProgress());
        // Sync observability (testnet campaign S5): rounds with neither sync progress nor a
        // height advance, how many peers the last round skipped as banned, and whether it had
        // any usable sync source at all. A node wedged at a height with healthy peers shows a
        // climbing rounds-without-progress here in seconds instead of in a log post-mortem (a
        // gossip-fed healthy node stays at 0). syncEclipsed is reported separately because the
        // usual eclipse empties the registry (bans evict) rather than filling it with banned
        // entries, so the peer counts alone cannot tell it apart from a node still bootstrapping.
        sink.field(K_SYNC_ROUNDS_WITHOUT_PROGRESS, node.syncHealth().roundsWithoutProgress());
        sink.field(K_SYNC_PEERS_BANNED, node.syncHealth().peersSkippedBanned());
        sink.field(K_SYNC_ECLIPSED, node.syncHealth().eclipsed());
        sink.endObject();
        return json(sink);
    }

    /**
     * Capability discovery for the dashboard, so the UI enables pages by what this
     * node actually supports (the boxes/tokens pages activate themselves from these
     * flags — a node built without those layers keeps them dormant).
     */
    static HttpResponse features(NodeService node, SseLogHub sse) {
        boolean contracts = node.dryRunAvailable();
        JsonSink sink = JsonSink.create(FEATURES_SIZE_HINT);
        sink.beginObject();
        sink.field(K_DASHBOARD, true);
        sink.field(K_CONTRACTS, contracts);
        sink.field(K_CONTRACT_QUERY, contracts);
        sink.field(K_LOG_STREAM, sse != null);
        sink.field(K_AGENTS, contracts);
        sink.field(K_BOXES, node.boxesAvailable());
        sink.field(K_TOKENS, node.tokensAvailable());
        sink.field(K_STATE_ROOT, node.stateRoot() != null);
        sink.endObject();
        return json(sink);
    }
}
