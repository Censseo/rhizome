package rhizome.net;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The node's live set of known peer base URLs. Thread-safe and bounded; never
 * contains this node's own advertised URL. Feeds sync, gossip and discovery.
 *
 * <p>Every table here keys on the peer's typed IDENTITY ({@link PeerId}), never on the
 * received string: admission canonicalizes once and stores the canonical form, so
 * case/trailing-dot/default-port/trailing-slash spellings of one URL can never occupy
 * two table slots (audit: /add_peer coalescing &amp; self-pairing bypass).
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

    /** Cap on the removal-cooldown table (bounded LRU; attacker-influenced keys, so hard-bounded). */
    private static final int MAX_COOLDOWN_ENTRIES = 256;
    /** How long a discovered peer removed as failed/evicted is refused re-admission. */
    private static final long REMOVAL_COOLDOWN_MILLIS = 5 * 60_000L;

    private final PeerId self;
    private final int maxPeers;
    private final PeerBanList banList;
    private final boolean blockPrivateHosts;
    private final LongSupplier nowMillis;

    /**
     * Facts derived ONCE at admission, stored alongside the peer so later paths never touch DNS:
     * the subnet bucket its table slot was counted against (decremented at removal, so a DNS
     * flip cannot corrupt the accounting — audit F5) and, on mainnet, the routability verdict
     * served by {@link #publicSnapshot()} (audit F3).
     */
    private record Entry(String subnetBucket, boolean publiclyRoutable) {}

    /** Placeholder entry for seed peers (never subnet-counted, never publicly advertised). */
    private static final Entry SEED_ENTRY = new Entry("", true);

    private final Map<PeerId, Entry> peers = new ConcurrentHashMap<>();
    /** Config/seed peers: exempt from SSRF + subnet caps and never evicted by capacity. */
    private final Set<PeerId> seeds = ConcurrentHashMap.newKeySet();
    /**
     * Peers that have answered at least one well-formed protocol exchange, i.e. that are
     * demonstrably Rhizome nodes rather than whatever host an unauthenticated {@code /add_peer}
     * pointed us at. Bounded by the peer table (cleared in {@link #remove}). See
     * {@link #markConfirmed} for why the distinction carries a ban decision.
     */
    private final Set<PeerId> confirmed = ConcurrentHashMap.newKeySet();
    /** Live count of discovered (non-seed) peers per subnet bucket. */
    private final Map<String, Integer> subnetCounts = new ConcurrentHashMap<>();
    /**
     * Recently removed (failed / evicted) discovered peers, with the earliest re-admission
     * time. Keyed by ENDPOINT (host + port), so a removed peer cannot dodge the window by
     * rotating its path while a collision on a shared host (all testnet peers on
     * {@code localhost}) does not make one peer's failure lock out its siblings — the same
     * port-scoping PeerBanList applies to its ban keys (testnet campaign S5). Bounded LRU;
     * a peer dropped for repeated failures cannot be instantly re-added via PEX to squat its
     * bucket slot again (audit F5).
     */
    private final Map<String, Long> removalCooldowns = Collections.synchronizedMap(
        new LinkedHashMap<String, Long>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_COOLDOWN_ENTRIES;
            }
        });
    private final Object lock = new Object();

    public PeerRegistry(String selfUrl, int maxPeers) {
        this(selfUrl, maxPeers, null, false);
    }

    public PeerRegistry(String selfUrl, int maxPeers, PeerBanList banList) {
        this(selfUrl, maxPeers, banList, false);
    }

    public PeerRegistry(String selfUrl, int maxPeers, PeerBanList banList, boolean blockPrivateHosts) {
        this(selfUrl, maxPeers, banList, blockPrivateHosts, System::currentTimeMillis);
    }

    /**
     * As above, with an injectable clock for the removal cooldown. Public for the same reason
     * {@link PeerBanList}'s clock-injecting constructor is: a virtual-time adversarial scenario
     * (NET-11) needs to drive the removal cooldown across a horizon measured in hours without
     * spending real wall-clock time on it.
     */
    public PeerRegistry(String selfUrl, int maxPeers, PeerBanList banList, boolean blockPrivateHosts,
                         LongSupplier nowMillis) {
        this.self = PeerId.of(selfUrl);
        this.maxPeers = maxPeers;
        this.banList = banList;
        this.blockPrivateHosts = blockPrivateHosts;
        this.nowMillis = nowMillis;
    }

    /**
     * Registers trusted seed/config peers. These are exempt from the SSRF and subnet
     * filters (the operator chose them) and are never evicted by capacity, so they always
     * remain as honest connectivity anchors even under an eclipse attempt.
     */
    public void addSeeds(Iterable<String> urls) {
        for (String url : urls) {
            PeerId id = PeerId.of(url);
            if (!id.isValid() || id.equals(self)) {
                continue;
            }
            if (banList != null && banList.isBanned(id)) {
                continue;
            }
            synchronized (lock) {
                if (peers.putIfAbsent(id, SEED_ENTRY) == null) {
                    seeds.add(id);
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
        PeerId id = PeerId.of(url);
        if (!id.isValid() || id.equals(self)) {
            return false;
        }
        if (banList != null && banList.isBanned(id)) {
            return false;
        }
        String host = id.host();
        // A peer recently removed as failed/evicted is refused re-admission for a short window,
        // so it cannot flap straight back into its bucket slot (audit F5). Keyed by ENDPOINT
        // (host + port): a full-URL key was dodgeable by rotating the port or path, and a
        // bare-host key let one peer's failure lock out sibling ports on a shared host
        // (audit follow-up; testnet campaign S5 — same port-scoping as PeerBanList).
        Long cooldownUntil = removalCooldowns.get(id.endpoint());
        if (cooldownUntil != null) {
            if (nowMillis.getAsLong() < cooldownUntil) {
                return false;
            }
            removalCooldowns.remove(id.endpoint()); // window expired: eligible again
        }
        // Cheap rejections BEFORE the blocking DNS resolution below: an exact duplicate or a
        // full table is decided on the live map directly (both are re-checked authoritatively
        // under the lock at insertion). A flood of duplicate/full-table adds must not each pay
        // a resolver round-trip (audit: admission ordering).
        if (peers.containsKey(id) || peers.size() >= maxPeers) {
            return false;
        }
        // The routability verdict is computed ONCE here, at admission; publicSnapshot serves this
        // stored verdict and never resolves DNS on the request path (audit F3).
        boolean routable = !blockPrivateHosts || isPubliclyRoutable(host);
        if (blockPrivateHosts && !routable) {
            return false; // SSRF: refuse internal / metadata / private targets
        }
        String bucket = subnetKey(host);
        synchronized (lock) {
            if (peers.containsKey(id)) {
                return false;
            }
            if (peers.size() >= maxPeers) {
                return false;
            }
            if (subnetCounts.getOrDefault(bucket, 0) >= MAX_PER_SUBNET) {
                return false; // eclipse: one subnet cannot monopolise the table
            }
            if (peers.putIfAbsent(id, new Entry(bucket, routable)) == null) {
                subnetCounts.merge(bucket, 1, Integer::sum);
                return true;
            }
            return false;
        }
    }

    /**
     * Records that {@code url} answered a well-formed protocol exchange. Only a CONFIRMED peer
     * can earn ban score (see {@link #penalize}): {@code /add_peer} is unauthenticated on an open
     * node, so anyone could have us add {@code https://victim.example} — an ordinary web server
     * answering 200 to everything — and its junk "protocol data" then banned the victim's
     * resolved IP for the full ban window, renewable indefinitely (audit B-3, ban by proxy). A
     * host that has never spoken the protocol is not a misbehaving peer, it is a bad address:
     * the proportionate answer is eviction plus the re-admission cooldown, not a blocklist entry
     * that would also refuse the victim's honest node later.
     */
    public void markConfirmed(String url) {
        PeerId id = PeerId.of(url);
        if (id.isValid() && peers.containsKey(id)) {
            confirmed.add(id);
        }
    }

    /** True if {@code url} has answered a valid protocol exchange, or is a configured seed
     *  (the operator vouched for it). See {@link #markConfirmed}. */
    public boolean isConfirmed(String url) {
        PeerId id = PeerId.of(url);
        return id.isValid() && (confirmed.contains(id) || seeds.contains(id));
    }

    /** Records misbehaviour; if it tips the peer over the ban threshold, evicts it. Seed peers
     *  are trusted anchors and are never auto-banned or evicted (audit M4 collateral bans).
     *  Callers must not penalize a peer that {@link #isConfirmed} rejects — see there. */
    public boolean penalize(String url, int points) {
        PeerId id = PeerId.of(url);
        if (banList == null || !id.isValid() || seeds.contains(id)) {
            return false;
        }
        boolean banned = banList.misbehave(id, points);
        if (banned) {
            remove(url);
        }
        return banned;
    }

    public boolean isBanned(String url) {
        return banList != null && banList.isBanned(PeerId.of(url));
    }

    /** True if {@code url} names a configured seed peer (trusted anchor, never PEX-evicted). */
    public boolean isSeed(String url) {
        return seeds.contains(PeerId.of(url));
    }

    public void addAll(Iterable<String> urls) {
        for (String url : urls) {
            add(url);
        }
    }

    public void remove(String url) {
        PeerId id = PeerId.of(url);
        synchronized (lock) {
            Entry entry = peers.remove(id);
            confirmed.remove(id); // bounded by the peer table: never outlives its entry
            if (entry == null) {
                return;
            }
            if (!seeds.remove(id)) {
                // Decrement the bucket recorded at ADMISSION — never re-resolve DNS at removal,
                // so a DNS flip between add and remove cannot corrupt the accounting (audit F5).
                subnetCounts.computeIfPresent(entry.subnetBucket(), (k, v) -> v <= 1 ? null : v - 1);
                String endpoint = id.endpoint();
                if (!endpoint.isEmpty()) {
                    removalCooldowns.put(endpoint, nowMillis.getAsLong() + REMOVAL_COOLDOWN_MILLIS);
                }
            }
        }
    }

    public List<String> snapshot() {
        List<String> out = new ArrayList<>(peers.size());
        for (PeerId id : peers.keySet()) {
            out.add(id.canonical());
        }
        return out;
    }

    /** The configured table capacity (used by PeerDiscovery to bound its round queue). */
    int maxPeers() {
        return maxPeers;
    }

    /**
     * Peers safe to advertise to an unauthenticated caller (e.g. {@code GET /peers}). Seeds are
     * always withheld: they are exempt from the SSRF/subnet filter and may be an operator's private
     * infrastructure (e.g. {@code http://10.0.0.5:3000}), so serving them verbatim leaks internal
     * topology (audit S-6). On a public mainnet node ({@code blockPrivateHosts}) any non-routable
     * host is withheld too, as defense in depth; on a private/dev network the loopback/private PEX
     * mesh is legitimate, so discovered peers are advertised regardless of routability.
     */
    public List<String> publicSnapshot() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<PeerId, Entry> e : peers.entrySet()) {
            if (seeds.contains(e.getKey())) {
                continue;
            }
            // Serve the verdict snapshotted at admission — never resolve DNS here: this runs on
            // the HTTP eventloop (GET /peers) and a blocking lookup per peer per request is a
            // stall vector (audit F3).
            if (blockPrivateHosts && !e.getValue().publiclyRoutable()) {
                continue;
            }
            out.add(e.getKey().canonical());
        }
        return out;
    }

    public int size() {
        return peers.size();
    }

    public String self() {
        return self.canonical();
    }

    // ---- host classification ----

    /** Delegates to {@link PeerHosts#isPubliclyRoutable} (kept here as the admission-time entry point). */
    static boolean isPubliclyRoutable(String host) {
        return PeerHosts.isPubliclyRoutable(host);
    }

    /** Delegates to {@link PeerHosts#subnetKey}. */
    static String subnetKey(String host) {
        return PeerHosts.subnetKey(host);
    }
}
