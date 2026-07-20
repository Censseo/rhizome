package rhizome.node;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The node's live set of known peer base URLs. Thread-safe and bounded; never
 * contains this node's own advertised URL. Feeds sync, gossip and discovery.
 *
 * <p>Hardened against eclipse and SSRF:
 * <ul>
 *   <li><b>Seed peers</b> (from config) are trusted, never subject to the SSRF/subnet
 *       filters, and never evicted by capacity — so a flood of {@code /add_peer} or PEX
 *       entries cannot crowd out the operator's connectivity anchors.</li>
 *   <li><b>Discovered peers</b> (PEX / {@code /add_peer}) are bucketed by IP subnet with a
 *       per-bucket cap, so a single host or subnet cannot fill the table (eclipse).</li>
 *   <li>When {@code blockPrivateHosts} is set (production mainnet), a discovered peer whose
 *       host resolves into a loopback / private / link-local / carrier-NAT / ULA / multicast
 *       range — including the {@code 169.254.169.254} cloud-metadata address — is refused,
 *       so an attacker cannot make the node fetch internal services (SSRF).</li>
 * </ul>
 */
public final class PeerRegistry {

    /** Max discovered peers per IP subnet bucket (/16 v4, /48 v6). Bounds eclipse via one subnet. */
    private static final int MAX_PER_SUBNET = 16;

    private final String self;
    private final int maxPeers;
    private final PeerBanList banList;
    private final boolean blockPrivateHosts;

    private final Set<String> peers = ConcurrentHashMap.newKeySet();
    /** Config/seed peers: exempt from SSRF + subnet caps and never evicted by capacity. */
    private final Set<String> seeds = ConcurrentHashMap.newKeySet();
    /** Live count of discovered (non-seed) peers per subnet bucket. */
    private final Map<String, Integer> subnetCounts = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public PeerRegistry(String selfUrl, int maxPeers) {
        this(selfUrl, maxPeers, null, false);
    }

    public PeerRegistry(String selfUrl, int maxPeers, PeerBanList banList) {
        this(selfUrl, maxPeers, banList, false);
    }

    public PeerRegistry(String selfUrl, int maxPeers, PeerBanList banList, boolean blockPrivateHosts) {
        this.self = normalize(selfUrl);
        this.maxPeers = maxPeers;
        this.banList = banList;
        this.blockPrivateHosts = blockPrivateHosts;
    }

    static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * Registers trusted seed/config peers. These are exempt from the SSRF and subnet
     * filters (the operator chose them) and are never evicted by capacity, so they always
     * remain as honest connectivity anchors even under an eclipse attempt.
     */
    public void addSeeds(Iterable<String> urls) {
        for (String url : urls) {
            String u = normalize(url);
            if (u == null || u.isEmpty() || !isHttpUrl(u) || u.equals(self)) {
                continue;
            }
            if (banList != null && banList.isBanned(u)) {
                continue;
            }
            synchronized (lock) {
                if (peers.add(u)) {
                    seeds.add(u);
                }
            }
        }
    }

    /**
     * Adds a discovered peer (PEX or {@code /add_peer}) if it is a well-formed http(s) URL,
     * not ourselves, not banned, passes the SSRF host filter (when enabled), and fits under
     * both the global capacity and its subnet's bucket cap. Single admission choke point, so
     * a banned or internal-pointing peer cannot be introduced via any path.
     */
    public boolean add(String url) {
        String u = normalize(url);
        if (u == null || u.isEmpty() || !isHttpUrl(u) || u.equals(self)) {
            return false;
        }
        if (banList != null && banList.isBanned(u)) {
            return false;
        }
        String host = hostOf(u);
        if (blockPrivateHosts && !isPubliclyRoutable(host)) {
            return false; // SSRF: refuse internal / metadata / private targets
        }
        String bucket = subnetKey(host);
        synchronized (lock) {
            if (peers.contains(u)) {
                return false;
            }
            if (peers.size() >= maxPeers) {
                return false;
            }
            if (subnetCounts.getOrDefault(bucket, 0) >= MAX_PER_SUBNET) {
                return false; // eclipse: one subnet cannot monopolise the table
            }
            if (peers.add(u)) {
                subnetCounts.merge(bucket, 1, Integer::sum);
                return true;
            }
            return false;
        }
    }

    /** Records misbehaviour; if it tips the peer over the ban threshold, evicts it. */
    public boolean penalize(String url, int points) {
        if (banList == null) {
            return false;
        }
        boolean banned = banList.misbehave(url, points);
        if (banned) {
            remove(url);
        }
        return banned;
    }

    public boolean isBanned(String url) {
        return banList != null && banList.isBanned(url);
    }

    public void addAll(Iterable<String> urls) {
        for (String url : urls) {
            add(url);
        }
    }

    public void remove(String url) {
        String u = normalize(url);
        synchronized (lock) {
            if (!peers.remove(u)) {
                return;
            }
            if (!seeds.remove(u)) {
                String bucket = subnetKey(hostOf(u));
                subnetCounts.computeIfPresent(bucket, (k, v) -> v <= 1 ? null : v - 1);
            }
        }
    }

    public List<String> snapshot() {
        return List.copyOf(peers);
    }

    public int size() {
        return peers.size();
    }

    public String self() {
        return self;
    }

    // ---- URL / host classification ----

    /** Strict http(s) URL with a host — rejects junk like {@code httpfoo://} that a prefix check let in. */
    static boolean isHttpUrl(String u) {
        try {
            URI uri = URI.create(u);
            String scheme = uri.getScheme();
            return (("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * True only if every address {@code host} resolves to is a globally routable unicast
     * address. Rejects loopback, any-local, link-local (incl. 169.254.169.254 metadata),
     * IPv4 private (RFC1918) and carrier-grade NAT (100.64/10), IPv6 unique-local (fc00::/7),
     * and multicast. Resolution failure is treated as not-routable (fail closed).
     */
    static boolean isPubliclyRoutable(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            if (addrs.length == 0) {
                return false;
            }
            for (InetAddress a : addrs) {
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
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Subnet bucket key for eclipse-resistant diversity: /16 (v4) or /48 (v6); host string if unresolved. */
    static String subnetKey(String host) {
        if (host == null) {
            return "host:";
        }
        try {
            byte[] b = InetAddress.getByName(host).getAddress();
            if (b.length == 4) {
                return "v4:" + (b[0] & 0xFF) + "." + (b[1] & 0xFF);
            }
            return String.format("v6:%02x%02x:%02x%02x:%02x%02x",
                b[0], b[1], b[2], b[3], b[4], b[5]);
        } catch (UnknownHostException e) {
            return "host:" + host;
        }
    }
}
