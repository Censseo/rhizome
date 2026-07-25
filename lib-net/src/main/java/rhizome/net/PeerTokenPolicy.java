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

    /**
     * Legacy/test policy presenting the token to EVERY peer, over any scheme — including
     * gossip-learned peers reached over cleartext {@code http://}. This reproduces the exact
     * pre-fix behaviour behind the deprecated {@code String peerToken} constructors and is
     * INSECURE on a registry populated by unauthenticated PEX: any peer that gets itself added
     * receives the shared secret. Only use in tests or single-operator deployments where every
     * registry entry is trusted.
     */
    public static PeerTokenPolicy trustAll(String token) {
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
            return token; // legacy trust-all: any peer, any scheme (see trustAll javadoc)
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
