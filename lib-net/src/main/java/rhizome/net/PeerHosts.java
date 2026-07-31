package rhizome.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
 *       virtual-hosted, so dropping the hostname in favour of the IP is safe — EXCEPT for
 *       {@code https://}, where the hostname must survive for TLS hostname verification
 *       (the certificate then provides the anti-rebinding binding instead).</li>
 * </ul>
 *
 * <p>Resolution goes through a short-TTL cache ({@link #resolveAll}) so admitting and then
 * fetching from one peer does not re-hit the resolver several times per round (admission alone
 * did up to three lookups; discovery pinned twice per peer). This does NOT weaken the anti-rebinding
 * guarantee: pinning's security property is "connect to a validated IP", which a short cache of
 * already-validated addresses preserves — the cached entry is the exact address set we classify and
 * pin to. FAILED resolutions are cached too, but with a much shorter TTL ({@link #NEGATIVE_TTL_NANOS}),
 * so a spray of unresolvable names cannot force a blocking resolver round-trip per call while an
 * honestly-mistyped name is retried soon after. The full address array is cached so the
 * all-addresses routability check and the first-address pin/subnet/ban keys stay consistent.
 */
final class PeerHosts {

    private PeerHosts() {}

    /** How long a successful DNS resolution is reused before re-resolving. */
    private static final long CACHE_TTL_NANOS = 60L * 1_000_000_000L;

    /** How long a FAILED resolution is reused: long enough to keep repeated lookups of an
     *  unresolvable (attacker-supplied) name off the blocking resolver, short enough that a
     *  transient DNS failure or a newly-registered name is retried promptly (audit F3). */
    private static final long NEGATIVE_TTL_NANOS = 30L * 1_000_000_000L;

    /**
     * Hard cap on distinct hostnames cached at once. The cache key is an attacker-influenced
     * hostname (peers arrive via the unauthenticated {@code /add_peer} and PEX, and every
     * admission resolves the host before the capacity/dedup checks), so without a bound a stream
     * of distinct resolvable names — e.g. {@code *.evil.example} behind one wildcard record — would
     * accumulate one permanent entry each and exhaust the heap. Access-order LRU eviction keeps the
     * live working set (the actual peer table is itself bounded well below this) while restoring the
     * §7.3 "every unbounded surface is capped" invariant.
     */
    private static final int MAX_ENTRIES = 4_096;

    /** A cached resolution; {@code addrs == null} marks a cached FAILURE (negative entry). */
    private record CacheEntry(InetAddress[] addrs, long expiresAtNanos) {}

    private static final Map<String, CacheEntry> DNS_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    /**
     * All addresses {@code host} resolves to, via a short-TTL cache. Mirrors
     * {@link InetAddress#getAllByName}; {@link InetAddress#getByName} returns the first of these, so
     * callers needing a single address use {@code resolveAll(host)[0]}. Successes are cached for
     * {@link #CACHE_TTL_NANOS}, failures (thrown here as {@link UnknownHostException}) for the
     * shorter {@link #NEGATIVE_TTL_NANOS}.
     */
    static InetAddress[] resolveAll(String host) throws UnknownHostException {
        String key = host.toLowerCase(Locale.ROOT);
        long now = System.nanoTime();
        CacheEntry cached = DNS_CACHE.get(key);
        if (cached != null && cached.expiresAtNanos() - now > 0) {
            if (cached.addrs() == null) {
                throw new UnknownHostException(host); // cached negative (short TTL)
            }
            return cached.addrs();
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            DNS_CACHE.put(key, new CacheEntry(addrs, now + CACHE_TTL_NANOS));
            return addrs;
        } catch (UnknownHostException e) {
            // Cache the miss briefly in the same bounded LRU: without it, every call for an
            // unresolvable name blocks on the resolver again (audit F3).
            DNS_CACHE.put(key, new CacheEntry(null, now + NEGATIVE_TTL_NANOS));
            throw e;
        }
    }

    /** The first resolved address for {@code host} (cached), matching {@link InetAddress#getByName}. */
    static InetAddress resolveFirst(String host) throws UnknownHostException {
        return resolveAll(host)[0];
    }

    /** Visible for testing: number of cached DNS entries (bounded by {@link #MAX_ENTRIES}). */
    static int cachedEntryCount() {
        return DNS_CACHE.size();
    }

    /** Visible for testing: seed the resolution cache for {@code host}. Lets a test exercise the
     *  all-addresses rules ({@link #isPubliclyRoutable}, {@link #pin}) without depending on a live
     *  resolver that hands back several A records, one of them private. */
    static void primeCacheForTests(String host, InetAddress... addrs) {
        DNS_CACHE.put(host.toLowerCase(Locale.ROOT),
            new CacheEntry(addrs.clone(), System.nanoTime() + CACHE_TTL_NANOS));
    }

    /** Visible for testing: true if {@code host} currently holds a live cached NEGATIVE resolution. */
    static boolean isCachedNegative(String host) {
        CacheEntry cached = DNS_CACHE.get(host.toLowerCase(Locale.ROOT));
        return cached != null && cached.addrs() == null && cached.expiresAtNanos() - System.nanoTime() > 0;
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
     * (100.64/10), IPv6 unique-local (fc00::/7), the IPv6 transition tunnels 6to4 (2002::/16)
     * and Teredo (2001::/32) — both embed an inner IPv4 address that would bypass the
     * v4-private filter (audit F10) — and multicast. Also rejected (audit: SSRF range gaps):
     * the NAT64 well-known prefix 64:ff9b::/96 and the deprecated v4-compatible ::/96 (both
     * embed an inner v4 address), the IETF-protocol block 192.0.0.0/24, the benchmark range
     * 198.18.0.0/15 and the reserved 240.0.0.0/4. An IPv4-mapped IPv6 address (::ffff:a.b.c.d)
     * is classified by its embedded v4 address, so the v4 rules cannot be dodged by encoding.
     */
    static boolean isRoutable(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
            || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            return isRoutableV4(b, 0);
        }
        if ((b[0] & 0xFE) == 0xFC) {
            return false; // fc00::/7 unique-local IPv6
        }
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            return false; // 2002::/16 6to4 tunnel (embeds a v4 address)
        }
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01
            && b[2] == 0 && b[3] == 0) {
            return false; // 2001:0000::/32 Teredo tunnel (embeds a v4 address)
        }
        if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64
            && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
            && isZero(b, 4, 12)) {
            return false; // 64:ff9b::/96 NAT64 well-known prefix (embeds a v4 address)
        }
        if (isZero(b, 0, 10) && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            // ::ffff:a.b.c.d v4-mapped: classify the embedded v4 address — a mapped loopback,
            // private, CGNAT or reserved target must be refused exactly like its v4 form
            // (the JDK usually hands these back as Inet4Address, but a raw 16-byte
            // Inet6Address — e.g. built with getByAddress — bypasses the v4 checks without this).
            return isRoutableV4(b, 12);
        }
        if (isZero(b, 0, 12)) {
            return false; // ::/96 deprecated v4-compatible (loopback/any-local already refused above)
        }
        return true;
    }

    /** v4 blocked ranges: loopback, any-local, link-local, RFC1918, CGNAT, IETF 192.0.0.0/24,
     *  benchmark 198.18.0.0/15, multicast and reserved 240/4. {@code q[offset..offset+3]} is the
     *  address — either a whole Inet4Address or the tail of a v4-mapped IPv6 one. */
    private static boolean isRoutableV4(byte[] q, int offset) {
        int b0 = q[offset] & 0xFF;
        int b1 = q[offset + 1] & 0xFF;
        int b2 = q[offset + 2] & 0xFF;
        if (b0 == 127 || b0 == 0) {
            return false; // 127/8 loopback, 0/8 "this host on this network"
        }
        if (b0 == 10 || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168)) {
            return false; // RFC1918 private
        }
        if (b0 == 169 && b1 == 254) {
            return false; // link-local (incl. 169.254.169.254 cloud metadata)
        }
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
            return false; // 100.64.0.0/10 carrier-grade NAT
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            return false; // 192.0.0.0/24 IETF protocol assignments
        }
        if (b0 == 198 && (b1 & 0xFE) == 18) {
            return false; // 198.18.0.0/15 benchmarking (RFC 2544)
        }
        if (b0 >= 224) {
            return false; // 224/4 multicast, 240/4 reserved (incl. 255.255.255.255 broadcast)
        }
        return true;
    }

    private static boolean isZero(byte[] b, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) {
            if (b[i] != 0) {
                return false;
            }
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
     *
     * <p>{@code https://} URLs are validated (resolution + routability) but returned with the
     * ORIGINAL hostname: an IP literal would fail TLS SAN verification. The dial re-resolves,
     * so the private-host check is best-effort for https — a rebound IP fails the handshake
     * (no data leaks) but the attempt itself can reach the internal network.
     *
     * <p>Every resolved address is checked, not just the pinned first one (audit B-1): the dial
     * picks its own address from the record for https (and the JDK may fail over to the next one
     * generally), so a name whose FIRST A record is public and whose second is {@code 10.x} used
     * to pass admission and still direct a connection attempt at the internal network. This is
     * the same all-addresses rule {@link #isPubliclyRoutable} applies at peer admission, so a
     * peer that was admitted also pins — one verdict, one place.
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
        InetAddress[] addrs;
        try {
            addrs = resolveAll(host);
        } catch (UnknownHostException e) {
            if (blockPrivate) {
                throw new SecurityException("unresolvable peer host: " + host);
            }
            return baseUrl; // permissive (dev/testnet): keep the hostname
        }
        if (addrs.length == 0) {
            if (blockPrivate) {
                throw new SecurityException("peer host resolves to no address: " + host);
            }
            return baseUrl;
        }
        if (blockPrivate) {
            // ALL addresses, not just the pinned first one: the https dial re-resolves and may
            // use any of them, so a public first record must not vouch for a private second one
            // (audit B-1). Same rule as isPubliclyRoutable at admission.
            for (InetAddress a : addrs) {
                if (!isRoutable(a)) {
                    throw new SecurityException("peer host " + host + " resolves to a non-routable "
                        + "address (" + addrs.length + " address(es) returned)");
                }
            }
        }
        InetAddress addr = addrs[0];
        // https keeps the HOSTNAME: TLS endpoint identification matches the certificate's SAN
        // against the host actually dialed, so rewriting to an IP literal fails the handshake
        // for every https peer (audit: DNS pinning breaks TLS). The anti-rebinding guarantee
        // is preserved by the certificate itself — a rebound IP cannot present a valid cert
        // for the hostname — and the routability check above still ran, so only the rewrite
        // is skipped. Documented best-effort caveat: the dial RE-RESOLVES the hostname, so
        // with blockPrivate set a rebind to a private IP is still ATTEMPTED (the TLS
        // handshake then fails — no data can leave — but the connection attempt itself can
        // reach the internal network, a port-scan oracle). Accepted trade-off: pinning the
        // IP would break every legitimate https peer. Cleartext http:// keeps the
        // IP-literal pin.
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return baseUrl;
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
