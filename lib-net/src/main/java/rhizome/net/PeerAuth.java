package rhizome.net;

import java.net.http.HttpRequest;

/**
 * Optional bearer token attached to OUTBOUND peer-to-peer requests (env
 * {@code RHIZOME_PEER_TOKEN}): when a node gates its ingest routes behind
 * {@code RHIZOME_API_TOKEN}, gossip and sync between operators of the same deployment
 * still need to authenticate, or every cross-node POST ({@code /submit},
 * {@code /add_transaction}, {@code /add_peer}) is refused with 401 and the mesh
 * silently stops converging (audit: token-gated node breaks gossip).
 *
 * <p>The token is a shared secret, never logged and never served back: it is only
 * ever written into the {@code Authorization} header of requests this node initiates.
 */
final class PeerAuth {

    private PeerAuth() {}

    /** Attaches {@code Authorization: Bearer <token>} to the builder when a token is configured. */
    static HttpRequest.Builder withToken(HttpRequest.Builder builder, String token) {
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }
}
