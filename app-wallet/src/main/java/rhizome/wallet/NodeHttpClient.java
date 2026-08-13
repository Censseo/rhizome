package rhizome.wallet;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.net.BodyReadDeadline;
import rhizome.net.PeerAuth;
import rhizome.net.PeerExchange;

/**
 * Blocking JDK-client transport for a node's HTTP API, used by the wallet CLI. Unlike
 * {@code HttpPeerSource} — which speaks the peer-sync protocol and treats transport failures
 * as peer misbehaviour — this client returns the node's response body as-is on any status
 * code (the API answers 4xx with a JSON status/error the caller wants to surface), and
 * every read is bounded so a hostile or broken endpoint can never hand back an unbounded
 * body. The whole-exchange deadline and the bounded read come from lib-net's shared
 * {@link BodyReadDeadline} / {@link PeerExchange} machinery.
 */
public final class NodeHttpClient {

    private static final Logger log = LoggerFactory.getLogger(NodeHttpClient.class);

    /** Response cap: API replies are server-side bounded (paginated lists, single objects). */
    private static final long RESPONSE_CAP = 4L * 1024 * 1024;

    /** Backs the once-per-process warning for the cleartext-loopback TOCTOU caveat (below). */
    private static final AtomicBoolean WARNED_LOOPBACK_TOCTOU = new AtomicBoolean();

    private final String baseUrl;
    private final HttpClient http;
    /** Optional bearer token attached to requests (e.g. RHIZOME_PEER_TOKEN for a token-gated
     *  peer node); never logged, and never sent in CLEARTEXT to a non-loopback host — the
     *  same https-only rule {@link PeerTokenPolicy} enforces for gossip (audit: peer token
     *  exfiltration). */
    private final String bearerToken;

    public NodeHttpClient(String baseUrl) {
        this(baseUrl, null);
    }

    /** As above, presenting {@code bearerToken} (nullable), subject to the cleartext guard. */
    public NodeHttpClient(String baseUrl, String bearerToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.bearerToken = cleartextGuard(this.baseUrl, bearerToken);
        this.http = PeerExchange.newClient();
    }

    /**
     * The token is a shared secret: it may ride https to any host, or cleartext http to a
     * LOOPBACK host (the local dev/wallet pattern, where nothing leaves the machine), but is
     * stripped — with a warning — from a cleartext request to a remote host rather than
     * leaked. Never logs the token itself.
     *
     * <p>Known limitation (TOCTOU): the loopback verdict is resolved ONCE here, at
     * construction, but the JDK client re-resolves the hostname on every request — a hostname
     * that later flips to a non-loopback address would receive the token in cleartext.
     * Re-checking at dial time would mean replacing the client transport (out of scope for a
     * CLI-side client), and pinning the IP was rejected: the node API may sit behind
     * name-based virtual hosting. Mitigation: pass an IP literal ({@code http://127.0.0.1}) —
     * literals cannot flip — which deployed configs already do; a hostname verdict therefore
     * logs a one-time warning.
     */
    private static String cleartextGuard(String baseUrl, String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            log.warn("malformed node base URL; bearer token will not be sent");
            return null;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return token;
        }
        String host = uri.getHost();
        if (host != null) {
            try {
                // Resolution-based, not a literal allowlist: 127.0.0.2, ::ffff:127.0.0.1 and
                // any hostname resolving to loopback are all local (cleartext never leaves
                // the machine). An unresolvable host is not provably loopback — strip.
                java.net.InetAddress addr = java.net.InetAddress.getByName(host);
                if (addr.isLoopbackAddress()) {
                    if (!addr.getHostAddress().equals(host)
                            && WARNED_LOOPBACK_TOCTOU.compareAndSet(false, true)) {
                        log.warn("bearer token trusted to loopback '{}' in cleartext: the verdict "
                            + "was resolved once at construction and the dial re-resolves the host "
                            + "each request — a DNS flip to a non-loopback address would leak the "
                            + "token. Use an IP literal (e.g. http://127.0.0.1) to pin it.", host);
                    }
                    return token;
                }
            } catch (java.net.UnknownHostException e) {
                // fall through to the strip warning
            }
        }
        log.warn("bearer token NOT attached: refusing to send it in cleartext to {}://{}"
            + " (use https, or a loopback address)", uri.getScheme(), host);
        return null;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** GET returning the response body (any status) as UTF-8 text. */
    public String get(String path) {
        return send(PeerAuth.withToken(HttpRequest.newBuilder(URI.create(baseUrl + path)), bearerToken)
            .timeout(Duration.ofSeconds(15)).GET().build());
    }

    /** POST of a JSON body returning the response body (any status) as UTF-8 text. */
    public String postJson(String path, String jsonBody) {
        return send(PeerAuth.withToken(HttpRequest.newBuilder(URI.create(baseUrl + path)), bearerToken)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build());
    }

    /** POST of a binary body returning the response body (any status) as UTF-8 text. */
    public String postBinary(String path, byte[] body) {
        return send(PeerAuth.withToken(HttpRequest.newBuilder(URI.create(baseUrl + path)), bearerToken)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build());
    }

    private String send(HttpRequest request) {
        try {
            // Whole-exchange deadline (BodyReadDeadline, audit F1/F2): the request timeout
            // covers only up to the response headers, so a slow-drip endpoint could otherwise
            // stall the caller in InputStream.read past it.
            Duration deadline = request.timeout().orElse(Duration.ofSeconds(30));
            AtomicReference<AutoCloseable> openBody = new AtomicReference<>();
            return BodyReadDeadline.call(deadline, openBody, () -> {
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                InputStream in = response.body();
                openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
                try (in) {
                    return new String(PeerExchange.readBounded(in, RESPONSE_CAP,
                        "node " + request.uri().getPath() + " response"), StandardCharsets.UTF_8);
                }
            });
        } catch (IOException e) {
            throw new NodeUnavailableException("node request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeUnavailableException("interrupted: " + request.uri(), e);
        }
    }

    /** Signals a transport-level failure talking to the node. */
    public static final class NodeUnavailableException extends RuntimeException {
        public NodeUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
