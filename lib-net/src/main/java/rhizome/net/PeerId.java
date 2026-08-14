package rhizome.net;

import java.net.URI;
import java.util.Locale;

/**
 * A peer's identity: the CANONICAL form of its base URL.
 *
 * <p>Before this type the identity was a raw {@code String} re-derived in five places —
 * canonicalization ({@link PeerUrls}), the host, the endpoint key, the subnet bucket and
 * the dial pin, the last two through DNS — and eight bounded tables were keyed by
 * attacker-influenced strings (constat 26). A peer now has a typed identity: {@link #of}
 * canonicalizes ONCE (case, trailing dot, default port, trailing slash — so
 * {@code HTTP://Peer.Example.COM.:80/} and {@code http://peer.example.com} are the SAME
 * peer and can never squat two registry slots, audit: /add_peer coalescing), and value
 * equality is on that canonical form, so a map keyed on {@code PeerId} holds exactly one
 * entry per peer however its URL was spelled at admission.
 *
 * <p>The non-DNS derivations of the identity ({@link #host()}, {@link #endpoint()}) are
 * computed here from the canonical form — one definition instead of one per table. The
 * DNS-dependent derivations (subnet bucket, dial pin, ban keys) remain in
 * {@link PeerHosts}/{@link PeerBanList}, keyed off {@link #host()}.
 *
 * @param canonical the canonical base URL (see {@link PeerUrls#canonicalize}); null only
 *                  for the invalid identity ({@link #isValid()} false)
 */
public record PeerId(String canonical) {

    /** The one invalid identity every parse failure yields — never stored in any table. */
    private static final PeerId INVALID = new PeerId(null);

    /**
     * The canonical identity of {@code url}, or the invalid identity when the URL is not a
     * usable http(s) base URL. {@code null} input yields the invalid identity, never an
     * exception, so admission can test {@link #isValid()} instead of null-checking.
     */
    public static PeerId of(String url) {
        String canonical = PeerUrls.canonicalize(url);
        if (canonical == null || !isHttpUrl(canonical)) {
            return INVALID;
        }
        return new PeerId(canonical);
    }

    /** True if this is a real, usable identity (a canonical http(s) base URL). */
    public boolean isValid() {
        return canonical != null;
    }

    /**
     * The host portion of the canonical URL (lowercased; null when invalid). Parsed from the
     * canonical form, never from a caller's raw string, so every table derives the same host.
     */
    public String host() {
        if (canonical == null) {
            return null;
        }
        try {
            URI uri = URI.create(canonical);
            return uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The endpoint key ({@code host:port}, default port folded to the absent form) used by the
     * removal-cooldown and ban tables — the same port-scoping rule {@link PeerBanList} applies
     * to its keys, so {@code http://h:80} and {@code http://h} are one endpoint. Empty when
     * invalid.
     */
    public String endpoint() {
        if (canonical == null) {
            return "";
        }
        try {
            URI uri = URI.create(canonical);
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            int port = uri.getPort();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            return host.toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @Override
    public String toString() {
        return canonical == null ? "<invalid peer>" : canonical;
    }

    /** Strict http(s) URL with a host — rejects junk like {@code httpfoo://} that a prefix check let in. */
    private static boolean isHttpUrl(String canonical) {
        try {
            URI uri = URI.create(canonical);
            String scheme = uri.getScheme();
            return (("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
