package rhizome.net;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which outbound peer requests may carry the {@code RHIZOME_PEER_TOKEN} bearer
 * secret. The peer registry is populated by UNAUTHENTICATED {@code /add_peer} and PEX
 * gossip, so attaching the token to every request (the historical behaviour) handed the
 * deployment's shared admin secret — in cleartext over {@code http://} — to anyone who got
 * themselves added to the registry (audit: peer token exfiltration via gossip).
 *
 * <p>The token is therefore only ever presented to a peer the operator EXPLICITLY configured
 * ({@code RHIZOME_PEERS} / {@code config.peers()}), and only over TLS: {@link #tokenFor}
 * returns the token when (a) the canonicalized URL ({@link PeerUrls#canonicalize}) is in the
 * configured set AND (b) the scheme is {@code https://} — never in cleartext over http.
 * Gossip/sync to any other peer proceeds unauthenticated.
 *
 * <p>These are the only two policies production code can build: this constructor and
 * {@link #none()}. The former trust-all escape hatch (and the deprecated {@code String
 * peerToken} constructors that reached it) is gone — it was a standing invitation to
 * reintroduce the exfiltration by wiring a new component the convenient way (audit B-2).
 */
public final class PeerTokenPolicy {

    private final String token;
    /** Canonicalized configured peers; null marks the legacy trust-all policy. */
    private final Set<String> trusted;

    /**
     * A policy trusting exactly {@code configuredPeers} (canonicalized). {@code token} may be
     * null/empty, in which case {@link #tokenFor} always returns null.
     */
    public PeerTokenPolicy(String token, Collection<String> configuredPeers) {
        this.token = token == null || token.isEmpty() ? null : token;
        this.trusted = new HashSet<>();
        if (configuredPeers != null) {
            for (String peer : configuredPeers) {
                String canonical = PeerUrls.canonicalize(peer);
                if (canonical != null) {
                    trusted.add(canonical);
                }
            }
        }
    }

    private PeerTokenPolicy(String token, boolean legacy) {
        this.token = token == null || token.isEmpty() ? null : token;
        this.trusted = null;
    }

    /** The policy for a node with no peer token: nothing is ever authenticated outbound. */
    public static PeerTokenPolicy none() {
        return new PeerTokenPolicy(null, java.util.List.of());
    }

    /**
     * Test-only policy presenting the token to EVERY peer over ANY scheme — the pre-fix
     * behaviour, kept solely so the transport tests can assert that {@code HttpPeerSource}
     * actually attaches {@code Authorization: Bearer} when a policy hands it a token (a plain
     * {@code HttpServer} cannot speak https, and the real policy refuses cleartext).
     *
     * <p>Deliberately NOT public (audit B-2): it was reachable from production wiring through
     * deprecated {@code String peerToken} constructors, so any component built that way handed
     * the deployment's shared admin secret — in cleartext over {@code http://} — to every
     * gossip-learned peer. Production code builds {@link #PeerTokenPolicy(String, Collection)}
     * or {@link #none()}; there is no supported way back to trust-all.
     */
    static PeerTokenPolicy unsafeTrustAllForTests(String token) {
        return new PeerTokenPolicy(token, true);
    }

    /**
     * The token to present to {@code url}, or null when the request must go out unauthenticated
     * (no token configured, peer not explicitly configured, or — for the configured-peers
     * policy — a cleartext {@code http://} scheme). The URL is canonicalized before the
     * membership check, so {@code HTTPS://Example.COM:443/} matches a configured
     * {@code https://example.com}.
     */
    public String tokenFor(String url) {
        if (token == null || url == null) {
            return null;
        }
        if (trusted == null) {
            return token; // test-only trust-all (see unsafeTrustAllForTests): any peer, any scheme
        }
        String canonical = PeerUrls.canonicalize(url);
        if (canonical == null || !trusted.contains(canonical)) {
            return null;
        }
        if (!canonical.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return null; // never send the shared secret in cleartext
        }
        return token;
    }
}
