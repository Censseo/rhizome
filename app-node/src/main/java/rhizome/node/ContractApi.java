package rhizome.node;

import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;
import io.activej.http.HttpRequest;
import org.json.JSONObject;

import rhizome.core.ledger.PublicAddress;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.hex;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.parseLong;

/**
 * Smart-contract observability and querying: event logs (poll and SSE stream)
 * and the read-only dry-run endpoint.
 */
final class ContractApi {

    /** Server-side ceiling on a read-only dry-run's gas: bounds free, unauthenticated VM compute. */
    private static final long MAX_READONLY_GAS = 50_000_000L;

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
    static HttpResponse logStream(SseLogHub sse, String clientKey) {
        var stream = sse == null ? null : sse.subscribe(clientKey);
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
    static HttpResponse callReadonly(NodeService node, JSONObject body) {
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

        // Aggregate (all-IP) dry-run gas budget: the per-IP RateLimiter cannot stop a handful of IPs
        // from pinning the event loop with back-to-back max-gas sink runs, so shed the call before it
        // reaches the VM once the global budget is spent (audit 5th-pass, net Finding 1).
        if (!node.tryReadonlyGasBudget(gasLimit)) {
            return HttpResponse.ofCode(429)
                .withJson(new JSONObject().put("error", "readonly compute budget exceeded").toString()).build();
        }

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

    static JSONObject logJson(rhizome.core.blockchain.ContractProcessor.ContractLog log) {
        return new JSONObject()
            .put("contract", log.contract().toHexString())
            .put("topic", hex(log.topic()))
            .put("data", hex(log.data()));
    }
}
