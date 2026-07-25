package rhizome.net;

import java.net.URI;
import java.util.Locale;

/**
 * Canonical form for peer base URLs, applied BEFORE dedup, self-comparison and ban-list
 * keying: scheme and host lower-cased, the host's trailing dot stripped (DNS names are
 * case- and trailing-dot-insensitive), the scheme's default port omitted, and trailing
 * slashes removed. Without it, one peer could squat several registry slots — and bypass
 * the self-pairing refusal — as {@code HTTP://Example.COM.:80/}, {@code http://example.com}
 * and {@code http://example.com:80/} (audit: /add_peer coalescing & self-pairing bypass).
 */
public final class PeerUrls {

    private PeerUrls() {}

    /**
     * The canonical form of {@code url}, or {@code null} when {@code url} is null. An
     * unparseable URL (or one without a scheme/host) degrades to the historical
     * trim + trailing-slash strip — the admission checks reject it downstream.
     */
    public static String canonicalize(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        URI uri;
        try {
            uri = URI.create(u);
        } catch (IllegalArgumentException e) {
            return stripTrailingSlashes(u);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return stripTrailingSlashes(u);
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1); // URI.getHost may keep the v6 brackets
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1); // "example.com." == "example.com"
        }
        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1; // default port: omit so :80 and bare forms coalesce
        }
        StringBuilder out = new StringBuilder(scheme).append("://");
        if (host.indexOf(':') >= 0) {
            out.append('[').append(host).append(']');
        } else {
            out.append(host);
        }
        if (port >= 0) {
            out.append(':').append(port);
        }
        // Peer URLs are base URLs; a non-root path is preserved so no two distinct
        // mounts silently coalesce (user-info and query are dropped: never meaningful here).
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            out.append(stripTrailingSlashes(path));
        }
        return out.toString();
    }

    private static String stripTrailingSlashes(String u) {
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
