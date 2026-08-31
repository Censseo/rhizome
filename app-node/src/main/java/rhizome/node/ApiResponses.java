package rhizome.node;

import java.util.concurrent.Callable;

import io.activej.bytebuf.ByteBuf;
import io.activej.http.ContentTypes;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaderValue;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;
import io.activej.promise.Promise;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

/**
 * Shared response builders and parsing helpers for the node API handlers.
 * Every path returns a response: bad input maps to a clean 400 with a generic
 * message, the detail is logged server-side only (audit L3).
 */
final class ApiResponses {

    private static final Logger log = LoggerFactory.getLogger(NodeApi.class);

    static final HttpHeader H_XCTO = HttpHeaders.of("X-Content-Type-Options");

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_STATUS = Key.of("status");
    private static final Key K_ERROR = Key.of("error");
    private static final Key K_PRUNED_BELOW = Key.of("prunedBelow");

    // -- JsonSink size hints (see class javadoc on JsonSink) ------------------------------------
    private static final int STATUS_SIZE_HINT = 48;
    private static final int ERROR_SIZE_HINT = 96;
    private static final int GONE_SIZE_HINT = 64;

    private ApiResponses() {}

    static Promise<HttpResponse> ok(HttpResponse response) {
        return Promise.of(response);
    }

    static Promise<HttpResponse> guarded(Callable<HttpResponse> action) {
        return Promise.of(guardedResponse(action));
    }

    static HttpResponse guardedResponse(Callable<HttpResponse> action) {
        try {
            return action.call();
        } catch (Exception e) {
            // Generic client message; detail is logged server-side only (audit L3).
            log.debug("request rejected: {}", sanitizeForLog(e.toString()));
            return badRequest("bad request");
        }
    }

    /**
     * Exception detail made safe for a log line: CR/LF and other control characters are
     * replaced, and the text is capped. Exception messages can embed attacker-controlled
     * input (a malformed query parameter, a peer-supplied value), so logging them raw lets a
     * remote party forge log entries or smuggle terminal escape sequences into an operator's
     * console (audit: log injection via exception messages).
     */
    static String sanitizeForLog(String detail) {
        if (detail == null) {
            return "null";
        }
        int cap = Math.min(detail.length(), 512);
        StringBuilder sb = new StringBuilder(cap);
        for (int i = 0; i < cap; i++) {
            char c = detail.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '_' : c);
        }
        return sb.toString();
    }

    static HttpResponse statusResponse(ExecutionStatus status) {
        int code = switch (status) {
            case SUCCESS -> 200;
            case SUBMIT_THROTTLED -> 429; // anti-DoS shed, not a validity error — tell the peer to retry
            default -> 400;
        };
        // "status" stays a JSON STRING (enum name): WalletClient.submit's strict getString read
        // (the wallet CLI's own consumer) and NodeApiTest both depend on the exact enum name.
        JsonSink sink = JsonSink.create(STATUS_SIZE_HINT);
        sink.beginObject();
        sink.field(K_STATUS, status.name());
        sink.endObject();
        return jsonAtCode(code, sink);
    }

    /** Deepest bracket nesting accepted in a JSON request body. org.json parses recursively, so a
     *  deeply nested body (tens of thousands of '[') overflows the event-loop thread's stack with a
     *  {@link StackOverflowError} — an {@code Error} {@link #guardedResponse} does not catch, taking
     *  down the whole HTTP loop (audit F11). */
    private static final int MAX_JSON_DEPTH = 64;

    /**
     * Parses a JSON request body after a cheap depth pre-scan: bodies nested deeper than
     * {@link #MAX_JSON_DEPTH} are rejected with {@link IllegalArgumentException} (→ clean 400)
     * before the recursive parser runs. The scan skips string literals (brackets inside a
     * string don't nest) so a deep-looking payload inside a value is not a false positive.
     */
    static JSONObject parseJson(String body) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                if (++depth > MAX_JSON_DEPTH) {
                    throw new IllegalArgumentException("JSON nesting too deep (max " + MAX_JSON_DEPTH + ")");
                }
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
            }
        }
        return new JSONObject(body);
    }

    static long parseLong(String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing parameter");
        }
        return Long.parseLong(value.trim());
    }

    static HttpResponse text(String body) {
        // nosniff so a browser never re-interprets a reflected value as HTML (defence in depth,
        // matching the dashboard asset responses).
        return HttpResponse.ok200().withHeader(H_XCTO, "nosniff").withPlainText(body).build();
    }

    static HttpResponse json(JSONObject body) {
        return HttpResponse.ok200().withHeader(H_XCTO, "nosniff").withJson(body.toString()).build();
    }

    /**
     * {@link JsonSink} counterpart of {@link #json(JSONObject)} — same headers
     * ({@code Content-Type: application/json; charset=utf-8} via {@link ContentTypes#JSON_UTF_8},
     * plus {@link #H_XCTO}), but the body is handed to ActiveJ as a zero-copy wrap of the sink's
     * own backing array (bounded to {@code [0, sink.length())}) instead of a {@code String} that
     * gets re-encoded to UTF-8.
     */
    static HttpResponse json(JsonSink sink) {
        return jsonAtCode(200, sink.array(), sink.length());
    }

    /**
     * Serves an already-serialized, process-lifetime-immutable payload (e.g. the memoized
     * {@code GET /emission} body): the bytes are computed once by the caller, and each request
     * wraps them in a FRESH response — a served response's body {@link ByteBuf} is consumed by
     * the write, so the {@link HttpResponse} instance itself must never be shared across
     * requests. The payload array is only ever read, so the shared wrap is safe.
     */
    static HttpResponse json(byte[] payload) {
        return jsonAtCode(200, payload, payload.length);
    }

    /**
     * {@link #json(JsonSink)}'s headers (the same {@code Content-Type}/{@link #H_XCTO} pair, and
     * the same zero-copy body wrap) at an arbitrary status code — {@code json(JsonSink)} itself is
     * hardcoded to 200. {@link #badRequest}, {@link #notFound}, {@link #gone},
     * {@link #statusResponse} and {@link #errorJson} all share this instead of each re-deriving
     * the wrapping.
     */
    private static HttpResponse jsonAtCode(int code, JsonSink sink) {
        return jsonAtCode(code, sink.array(), sink.length());
    }

    private static HttpResponse jsonAtCode(int code, byte[] body, int length) {
        return HttpResponse.ofCode(code)
            .withHeader(HttpHeaders.CONTENT_TYPE, HttpHeaderValue.ofContentType(ContentTypes.JSON_UTF_8))
            .withHeader(H_XCTO, "nosniff")
            .withBody(ByteBuf.wrap(body, 0, length))
            .build();
    }

    /**
     * A one-field {@code {"error": message}} body at an arbitrary status code — the shape every
     * endpoint-local 503/429/404/401/403 body in the package needs. Package-visible so
     * {@code ContractApi}, {@code StateApi}, {@code SyncApi} and {@code NodeApi} can call it
     * directly for their own non-200 bodies instead of each keeping a private mirror of it.
     */
    static HttpResponse errorJson(int code, String message) {
        JsonSink sink = JsonSink.create(ERROR_SIZE_HINT);
        sink.beginObject();
        sink.field(K_ERROR, message);
        sink.endObject();
        return jsonAtCode(code, sink);
    }

    static HttpResponse badRequest(String message) {
        JsonSink sink = JsonSink.create(ERROR_SIZE_HINT);
        sink.beginObject();
        sink.field(K_ERROR, message);
        sink.endObject();
        return jsonAtCode(400, sink);
    }

    static HttpResponse notFound(String message) {
        JsonSink sink = JsonSink.create(ERROR_SIZE_HINT);
        sink.beginObject();
        sink.field(K_ERROR, message);
        sink.endObject();
        return jsonAtCode(404, sink);
    }

    /** 410 GONE with the prune watermark, matching /sync, so a client knows to source the block
     *  (or a snapshot) from an archive node rather than treating a pruned height as a bad request. */
    static HttpResponse gone(long prunedBelow) {
        JsonSink sink = JsonSink.create(GONE_SIZE_HINT);
        sink.beginObject();
        sink.field(K_ERROR, "pruned");
        sink.field(K_PRUNED_BELOW, prunedBelow);
        sink.endObject();
        return jsonAtCode(410, sink);
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
