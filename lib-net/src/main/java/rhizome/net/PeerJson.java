package rhizome.net;

import org.json.JSONObject;

/**
 * Depth-bounded parsing of PEER-CONTROLLED JSON. org.json parses recursively, so a peer
 * answer nested tens of thousands of brackets deep overflows the sync/PEX thread's stack
 * with a {@link StackOverflowError} — an {@code Error} the peer-error handling does not
 * catch (audit F11; same fix as app-node's {@code ApiResponses.parseJson}). A cheap pre-scan
 *  rejects anything deeper than {@link #MAX_JSON_DEPTH} with a plain
 *  {@link IllegalArgumentException} BEFORE the recursive parser runs, so an over-deep body
 *  surfaces as an ordinary peer protocol error (penalised/ban-scored), never as a fatal SOE.
 *  Public for CLI-side consumers (the wallet) that parse NODE-controlled bodies the same way.
 */
public final class PeerJson {

    /** Deepest bracket nesting accepted from a peer (matches app-node's request-body bound). */
    private static final int MAX_JSON_DEPTH = 64;

    private PeerJson() {}

    /**
     * Parses {@code body} as a JSON object after the depth pre-scan. Brackets inside string
     * literals don't nest, so the scan skips them (a deep-looking payload inside a value is
     * not a false positive).
     */
    public static JSONObject parseObject(String body) {
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
}
