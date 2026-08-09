package rhizome.node;

import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;
import io.activej.http.HttpRequest;
import org.json.JSONObject;

import rhizome.core.blockchain.ContractProcessor.ContractLog;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.errorJson;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.parseLong;

/**
 * Smart-contract observability and querying: event logs (poll and SSE stream)
 * and the read-only dry-run endpoint.
 *
 * <p>Bodies are written with {@link JsonSink} instead of an {@code org.json} tree — see that
 * class's Javadoc, including this file's own small error bodies (503/429), which mirror
 * {@link ApiResponses#json(JsonSink)}'s headers at a non-200 status code since that helper is
 * hardcoded to 200.
 */
final class ContractApi {

    /** Server-side ceiling on a read-only dry-run's gas: bounds free, unauthenticated VM compute.
     *  Kept equal to the aggregate per-second budget (NodeService.READONLY_GAS_MAX_PER_SEC) so a
     *  clamped max-gas request is always admissible — a higher clamp would be shed with 429 every
     *  time it declared more than the budget, making the documented clamp unreachable. */
    private static final long MAX_READONLY_GAS = 25_000_000L;

    /**
     * Server-side ceiling on a dry-run's declared {@code value}: no consensus constant bounds
     * it (a dry-run never moves funds), so the bound is the representational safe range
     * 2^62 — keeping {@code value * price}-style arithmetic inside the VM far from long
     * overflow — and negative values are rejected outright (audit: readonly input validation).
     */
    private static final long MAX_READONLY_VALUE = 1L << 62;

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_HEIGHT = Key.of("height");
    private static final Key K_LOGS = Key.of("logs");
    private static final Key K_FROM_HEIGHT = Key.of("fromHeight");
    private static final Key K_TO_HEIGHT = Key.of("toHeight");
    private static final Key K_CONTRACT = Key.of("contract");
    private static final Key K_TOPIC = Key.of("topic");
    private static final Key K_DATA = Key.of("data");
    private static final Key K_SUCCESS = Key.of("success");
    private static final Key K_OUTPUT = Key.of("output");
    private static final Key K_GAS_USED = Key.of("gasUsed");
    private static final Key K_ERROR = Key.of("error");

    // -- JsonSink size hints (see class javadoc) -----------------------------------------------
    private static final int LOG_ENTRY_SIZE_HINT = 160;
    private static final int LOGS_SIZE_HINT = 128;
    private static final int CALL_READONLY_SIZE_HINT = 256;

    private ContractApi() {}

    /**
     * Contract event logs, the channel agents watch. Two modes:
     * <ul>
     *   <li>{@code ?height=N} — logs emitted by block N.</li>
     *   <li>{@code ?fromHeight=N} — a bounded height-cursor scan from N to the tip; the
     *       response's {@code toHeight} is the next cursor, so an agent streams by
     *       repeatedly polling from {@code toHeight + 1}.</li>
     * </ul>
     */
    static HttpResponse logs(NodeService node, HttpRequest req) {
        String heightParam = req.getQueryParameter("height");
        if (heightParam != null) {
            long height = parseLong(heightParam);
            if (height < 1 || height > node.blockCount()) {
                return badRequest("height out of range");
            }
            var logs = node.logsAt(height);
            JsonSink sink = JsonSink.create(LOGS_SIZE_HINT + logs.size() * LOG_ENTRY_SIZE_HINT);
            sink.beginObject();
            sink.field(K_HEIGHT, height);
            sink.name(K_LOGS);
            sink.beginArray();
            for (var log : logs) {
                sink.beginObject();
                writeLogBody(sink, log);
                sink.endObject();
            }
            sink.endArray();
            sink.endObject();
            return json(sink);
        }
        long fromHeight = parseLong(req.getQueryParameter("fromHeight"));
        if (fromHeight < 1) {
            return badRequest("fromHeight must be >= 1");
        }
        NodeService.LogPage page = node.logsFrom(fromHeight);
        JsonSink sink = JsonSink.create(LOGS_SIZE_HINT + page.logs().size() * LOG_ENTRY_SIZE_HINT);
        sink.beginObject();
        sink.field(K_FROM_HEIGHT, page.fromHeight());
        sink.field(K_TO_HEIGHT, page.toHeight());
        sink.name(K_LOGS);
        sink.beginArray();
        for (var entry : page.logs()) {
            sink.beginObject();
            writeLogBody(sink, entry.log());
            sink.field(K_HEIGHT, entry.height());
            sink.endObject();
        }
        sink.endArray();
        sink.endObject();
        return json(sink);
    }

    /**
     * Live contract-log push over Server-Sent Events: a heartbeat comment per applied
     * block and one {@code data:} event per log (see {@link SseLogHub} for the format
     * and the slow-subscriber contract). 503 when streaming is not wired or full.
     */
    static HttpResponse logStream(SseLogHub sse, String clientKey) {
        return logStream(sse, clientKey, clientKey);
    }

    /** As {@link #logStream(SseLogHub, String)}, with the site-aggregate tier key (IPv6 /48 or
     *  IPv4 /24 — audit F5 and the SSE v4 gap). */
    static HttpResponse logStream(SseLogHub sse, String clientKey, String subnetKey) {
        var stream = sse == null ? null : sse.subscribe(clientKey, subnetKey);
        if (stream == null) {
            return errorJson(503, "streaming unavailable");
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
    static HttpResponse callReadonly(NodeService node, JSONObject body) {
        // Validate inputs BEFORE touching availability, budgets or the VM (audit: readonly
        // input validation): a malformed call must be a cheap 400, never VM work.
        long value = body.optLong("value", 0);
        if (value < 0 || value > MAX_READONLY_VALUE) {
            return badRequest("value out of range");
        }
        if (body.has("from") && !body.getString("from").isEmpty()
            && !isHexAddress(body.getString("from"))) {
            return badRequest("from must be a 25-byte address (50 hex chars)");
        }
        if (!node.dryRunAvailable()) {
            return errorJson(503, "contracts unavailable");
        }
        PublicAddress to = PublicAddress.of(body.getString("to"));
        PublicAddress from = body.has("from") && !body.getString("from").isEmpty()
            ? PublicAddress.of(body.getString("from")) : PublicAddress.empty();
        byte[] input = body.has("input") && !body.getString("input").isEmpty()
            ? rhizome.core.common.Utils.hexStringToByteArray(body.getString("input")) : new byte[0];
        // Clamp the caller-supplied gas: a dry-run is free and unauthenticated, so an
        // unbounded gasLimit would let anyone burn arbitrary node CPU. Bound it server-side.
        long gasLimit = Math.min(Math.max(1L, body.optLong("gasLimit", 10_000_000L)), MAX_READONLY_GAS);

        // Aggregate (all-IP) dry-run gas budget: the per-IP RateLimiter cannot stop a handful of IPs
        // from pinning the event loop with back-to-back max-gas sink runs, so shed the call before it
        // reaches the VM once the global budget is spent (audit 5th-pass, net Finding 1).
        if (!node.tryReadonlyGasBudget(gasLimit)) {
            return errorJson(429, "readonly compute budget exceeded");
        }

        final rhizome.core.blockchain.ContractProcessor.ContractResult result;
        var dryRun = node.dryRun(from, to, input, value, gasLimit);
        if (dryRun.isEmpty()) {
            // Too many dry-runs already running or parked on the consensus lock: shed with 503
            // (retryable) instead of queueing another blocking-pool thread behind it (audit:
            // dry-run backlog bounded at admission, NodeService.MAX_CONCURRENT_DRY_RUNS).
            return errorJson(503, "dry-run busy, retry later");
        }
        result = dryRun.get();
        JsonSink sink = JsonSink.create(CALL_READONLY_SIZE_HINT
            + result.output().length * 2 + result.logs().size() * LOG_ENTRY_SIZE_HINT);
        sink.beginObject();
        sink.field(K_SUCCESS, result.success());
        sink.hexLower(K_OUTPUT, result.output());
        sink.field(K_GAS_USED, result.gasUsed());
        // Attacker/contract-controlled text (a VM revert/trap message) — routed through
        // JsonSink's normal string escaping like any other string field, not special-cased.
        if (result.error() == null) {
            sink.fieldNull(K_ERROR);
        } else {
            sink.field(K_ERROR, result.error());
        }
        sink.name(K_LOGS);
        sink.beginArray();
        for (var log : result.logs()) {
            sink.beginObject();
            writeLogBody(sink, log);
            sink.endObject();
        }
        sink.endArray();
        sink.endObject();
        return json(sink);
    }

    /** Writes one contract log's fields (no surrounding object) — {@code contract} in the
     *  node's uppercase hex convention ({@link PublicAddress#toHexString()}), {@code topic}/
     *  {@code data} in the lowercase convention {@link ApiResponses#hex} used elsewhere on this
     *  endpoint's payload. Split out (mirroring lib-core's {@code writeJsonBody}) so callers can
     *  splice in an extra field (the cursor scan's {@code height}) without a second object. */
    private static void writeLogBody(JsonSink sink, ContractLog log) {
        sink.hexUpper(K_CONTRACT, log.contract().toBytes());
        sink.hexLower(K_TOPIC, log.topic());
        sink.hexLower(K_DATA, log.data());
    }

    /** Strict hex-address shape check (50 hex chars) — the parser alone maps some non-hex
     *  input without failing. */
    private static boolean isHexAddress(String s) {
        if (s.length() != PublicAddress.SIZE * 2) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

}
