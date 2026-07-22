package rhizome.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Blocking JDK-client transport for a node's HTTP API, shared by CLI-side
 * consumers (the wallet, tools). Unlike {@link HttpPeerSource} — which speaks
 * the peer-sync protocol and treats transport failures as peer misbehaviour —
 * this client returns the node's response body as-is on any status code (the
 * API answers 4xx with a JSON status/error the caller wants to surface), and
 * every read is bounded so a hostile or broken endpoint can never hand back an
 * unbounded body.
 */
public final class NodeHttpClient {

    /** Response cap: API replies are server-side bounded (paginated lists, single objects). */
    private static final long RESPONSE_CAP = 4L * 1024 * 1024;

    private final String baseUrl;
    private final HttpClient http;

    public NodeHttpClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** GET returning the response body (any status) as UTF-8 text. */
    public String get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15)).GET().build());
    }

    /** POST of a JSON body returning the response body (any status) as UTF-8 text. */
    public String postJson(String path, String jsonBody) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build());
    }

    /** POST of a binary body returning the response body (any status) as UTF-8 text. */
    public String postBinary(String path, byte[] body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                return new String(readBounded(in, RESPONSE_CAP, request.uri().getPath()), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new NodeUnavailableException("node request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeUnavailableException("interrupted: " + request.uri(), e);
        }
    }

    /** Reads the stream, aborting if it would exceed {@code maxBytes} (never buffers past the cap). */
    private static byte[] readBounded(InputStream in, long maxBytes, String path) throws IOException {
        // One byte over the cap is fetched to distinguish "exactly at cap" from "over".
        byte[] data = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
        if (data.length > maxBytes) {
            throw new IOException("node " + path + " response exceeds " + maxBytes + " bytes");
        }
        return data;
    }

    /** Signals a transport-level failure talking to the node. */
    public static final class NodeUnavailableException extends RuntimeException {
        public NodeUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
