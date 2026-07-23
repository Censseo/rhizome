package rhizome.node;

import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;
import org.json.JSONObject;

import static rhizome.node.ApiResponses.hex;
import static rhizome.node.ApiResponses.json;

/**
 * Browser-facing dashboard endpoints: the embedded SPA assets (with their
 * security headers) plus the {@code /stats} overview and {@code /features}
 * capability discovery the UI bootstraps from.
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
    static HttpResponse features(NodeService node, SseLogHub sse) {
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
}
