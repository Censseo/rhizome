package rhizome.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared host classification and DNS-pinning for outbound peer traffic.
 *
 * <p>Two jobs, both anti-SSRF:
 * <ul>
 *   <li>{@link #isPubliclyRoutable} / {@link #isRoutable} decide whether a host may be
 *       contacted at all (used at peer admission and before each fetch on mainnet).</li>
 *   <li>{@link #pin} resolves a base URL's host once and rewrites it to the resolved IP
 *       literal, so the connection goes to the exact address we validated — a later DNS
 *       flip (rebinding) cannot redirect it to an internal service. The node API is not
 *       virtual-hosted, so dropping the hostname in favour of the IP is safe.</li>
 * </ul>
 *
 * <p>Resolution goes through a short-TTL cache ({@link #resolveAll}) so admitting and then
 * fetching from one peer does not re-hit the resolver several times per round (admission alone
 * did up to three lookups; discovery pinned twice per peer). This does NOT weaken the anti-rebinding
 * guarantee: pinning's security property is "connect to a validated IP", which a short cache of
 * already-validated addresses preserves — the cached entry is the exact address set we classify and
 * pin to. Only successful resolutions are cached (never negatives, so an unresolvable host keeps
 * being retried), and the full address array is cached so the all-addresses routability check and the
 * first-address pin/subnet/ban keys stay consistent.
 */
final class PeerHosts {

    private PeerHosts() {}

    /** How long a successful DNS resolution is reused before re-resolving. */
    private static final long CACHE_TTL_NANOS = 60L * 1_000_000_000L;

    private record CacheEntry(InetAddress[] addrs, long expiresAtNanos) {}

    private static final ConcurrentHashMap<String, CacheEntry> DNS_CACHE = new ConcurrentHashMap<>();

    /**
     * All addresses {@code host} resolves to, via a short-TTL cache. Mirrors
     * {@link InetAddress#getAllByName}; {@link InetAddress#getByName} returns the first of these, so
     * callers needing a single address use {@code resolveAll(host)[0]}. Only successes are cached.
     */
    static InetAddress[] resolveAll(String host) throws UnknownHostException {
        String key = host.toLowerCase(Locale.ROOT);
        long now = System.nanoTime();
        CacheEntry cached = DNS_CACHE.get(key);
        if (cached != null && cached.expiresAtNanos() - now > 0) {
            return cached.addrs();
        }
        InetAddress[] addrs = InetAddress.getAllByName(host); // throws (uncached) on failure
        DNS_CACHE.put(key, new CacheEntry(addrs, now + CACHE_TTL_NANOS));
        return addrs;
    }

    /** The first resolved address for {@code host} (cached), matching {@link InetAddress#getByName}. */
    static InetAddress resolveFirst(String host) throws UnknownHostException {
        return resolveAll(host)[0];
    }

    /** True only if every address {@code host} resolves to is globally routable unicast. */
    static boolean isPubliclyRoutable(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress[] addrs = resolveAll(host);
            if (addrs.length == 0) {
                return false;
            }
            for (InetAddress a : addrs) {
                if (!isRoutable(a)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * True if {@code a} is a globally routable unicast address. Rejects loopback, any-local,
     * link-local (incl. 169.254.169.254 metadata), IPv4 private (RFC1918) and CGNAT
     * (100.64/10), IPv6 unique-local (fc00::/7), and multicast.
     */
    static boolean isRoutable(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
            || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 16 && (b[0] & 0xFE) == 0xFC) {
            return false; // fc00::/7 unique-local IPv6
        }
        if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xFF) >= 64 && (b[1] & 0xFF) <= 127) {
            return false; // 100.64.0.0/10 carrier-grade NAT
        }
        return true;
    }

    /** Subnet bucket key for eclipse-resistant diversity: /16 (v4) or /48 (v6); host string if unresolved. */
    static String subnetKey(String host) {
        if (host == null) {
            return "host:";
        }
        try {
            byte[] b = resolveFirst(host).getAddress();
            if (b.length == 4) {
                return "v4:" + (b[0] & 0xFF) + "." + (b[1] & 0xFF);
            }
            return String.format("v6:%02x%02x:%02x%02x:%02x%02x",
                b[0], b[1], b[2], b[3], b[4], b[5]);
        } catch (UnknownHostException e) {
            return "host:" + host;
        }
    }

    /** Resolved IP a URL's host maps to, or {@code null} if it cannot be resolved. */
    static InetAddress resolve(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : resolveFirst(host);
        } catch (IllegalArgumentException | UnknownHostException e) {
            return null;
        }
    }

    /**
     * Resolves the base URL's host and returns an equivalent URL with the resolved IP literal
     * in place of the hostname, pinning the connection to the validated address (anti-rebinding).
     * When {@code blockPrivate} is set, a host that resolves to a non-routable address — or does
     * not resolve — is refused with a {@link SecurityException}. When it is not set (dev/testnet),
     * an unresolvable host is left untouched so local hostnames keep working.
     */
    static String pin(String baseUrl, boolean blockPrivate) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            if (blockPrivate) {
                throw new SecurityException("malformed peer URL: " + baseUrl);
            }
            return baseUrl;
        }
        String host = uri.getHost();
        if (host == null) {
            if (blockPrivate) {
                throw new SecurityException("peer URL has no host: " + baseUrl);
            }
            return baseUrl;
        }
        InetAddress addr;
        try {
            addr = resolveFirst(host);
        } catch (UnknownHostException e) {
            if (blockPrivate) {
                throw new SecurityException("unresolvable peer host: " + host);
            }
            return baseUrl; // permissive (dev/testnet): keep the hostname
        }
        if (blockPrivate && !isRoutable(addr)) {
            throw new SecurityException("peer host resolves to a non-routable address: " + host);
        }
        String ip = addr.getHostAddress();
        int scope = ip.indexOf('%');
        if (scope >= 0) {
            ip = ip.substring(0, scope);
        }
        String literal = (addr instanceof Inet6Address) ? "[" + ip + "]" : ip;
        int port = uri.getPort();
        return uri.getScheme() + "://" + literal + (port >= 0 ? ":" + port : "");
    }
}
