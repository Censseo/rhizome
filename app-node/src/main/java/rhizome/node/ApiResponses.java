package rhizome.node;

import java.util.concurrent.Callable;

import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;
import io.activej.promise.Promise;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.mempool.ExecutionStatus;

/**
 * Shared response builders and parsing helpers for the node API handlers.
 * Every path returns a response: bad input maps to a clean 400 with a generic
 * message, the detail is logged server-side only (audit L3).
 */
final class ApiResponses {

    private static final Logger log = LoggerFactory.getLogger(NodeApi.class);

    static final HttpHeader H_XCTO = HttpHeaders.of("X-Content-Type-Options");

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
        return HttpResponse.ofCode(code)
            .withJson(new JSONObject().put("status", status.name()).toString())
            .build();
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

    static HttpResponse badRequest(String message) {
        return HttpResponse.ofCode(400)
            .withJson(new JSONObject().put("error", message).toString())
            .build();
    }

    static HttpResponse notFound(String message) {
        return HttpResponse.ofCode(404)
            .withJson(new JSONObject().put("error", message).toString())
            .build();
    }

    /** 410 GONE with the prune watermark, matching /sync, so a client knows to source the block
     *  (or a snapshot) from an archive node rather than treating a pruned height as a bad request. */
    static HttpResponse gone(long prunedBelow) {
        return HttpResponse.ofCode(410)
            .withJson(new JSONObject().put("error", "pruned").put("prunedBelow", prunedBelow).toString())
            .build();
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
