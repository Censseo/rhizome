package rhizome.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The one shared transport for outbound peer exchanges, and the invariants every exchange
 * applies.
 *
 * <p>Before this type, the four exchange implementations (sync, PEX, gossip, the CLI node
 * client) each built their own JDK {@link HttpClient} — at connect timeouts of 3 s and 5 s,
 * inconsistently — so one selector-manager thread and one connection pool lived per component
 * (and, for sync, one per peer per round until the first shared client arrived). A node now
 * builds exactly ONE {@code PeerExchange} and hands it to sync, PEX and gossip: one selector
 * thread, one keep-alive pool, one connect timeout.
 *
 * <p>The bounded body read ({@link #readBounded}) and the client factory ({@link #newClient})
 * are static because they carry no per-node state: the bounds and the connect timeout are
 * protocol constants, not configuration.
 */
public final class PeerExchange {

    private final HttpClient client;

    /** An exchange over a freshly built default client. */
    public PeerExchange() {
        this(newClient());
    }

    /** An exchange over a caller-provided client, so several components share one transport. */
    public PeerExchange(HttpClient client) {
        this.client = client;
    }

    /** The shared client every request this exchange makes goes through. */
    public HttpClient client() {
        return client;
    }

    /**
     * A default JDK client with the standard 5 s connect timeout. Callers that share one
     * (the node builds exactly one and hands it to sync, PEX and gossip) build it once, so
     * one selector thread and one keep-alive connection pool serve every outbound exchange.
     */
    public static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Reads a response body, aborting if it would exceed {@code maxBytes} — the buffer never
     * grows past the cap, so a hostile endpoint cannot make the caller materialise an
     * unbounded body. {@code what} names the read in the failure message.
     */
    public static byte[] readBounded(InputStream in, long maxBytes, String what) throws IOException {
        // One byte over the cap is fetched to distinguish "exactly at cap" from "over".
        byte[] data = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
        if (data.length > maxBytes) {
            throw new IOException(what + " exceeds " + maxBytes + " bytes");
        }
        return data;
    }
}
