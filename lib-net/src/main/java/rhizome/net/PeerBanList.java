package rhizome.net;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A ban-score table for misbehaving peers. Modelled on Bitcoin Core's banscore:
 * a peer accumulates points for protocol violations and, once it crosses a
 * threshold, is banned for a fixed window; scores decay over time so an honest
 * peer is not punished forever for the occasional blip.
 *
 * <p><b>Keyed by ENDPOINT (resolved IP + port), with an address-wide escalation
 * layer.</b> The historical key — resolved IP alone — made one peer's ban blind
 * every other peer sharing that address: on a devnet where every node lives on
 * {@code localhost}, banning {@code localhost:4102} banned all nine sibling
 * ports (testnet campaign S5: a single transient {@code PEER_INVALID} during a
 * reorg eclipsed a healthy node for 12 minutes). Scoring is therefore
 * per-endpoint, and only a peer that keeps offending ACROSS endpoints (port
 * rotation) accumulates toward an address-wide ban at an escalated threshold —
 * the two concerns (isolating one bad node vs. stopping an address that is bad
 * everywhere) no longer share a key.
 *
 * <p>The escalation demands genuine ROTATION, on both counts, because escalating
 * on points alone would rebuild the very eclipse the endpoint keying removes, one
 * layer up: on the S5 devnet, {@code addressThreshold / PENALTY_INVALID} transient
 * strikes spread over the ten localhost nodes would ban {@code 127.0.0.1} and
 * blind all of them at once. So (1) the address entry bans only once at least
 * {@link #MIN_ROTATED_ENDPOINTS} DISTINCT endpoints have offended — one loud port
 * can never condemn its neighbours, whatever it accumulates — and (2) addresses
 * that are not publicly routable are exempt from escalation entirely: a loopback
 * or RFC1918 address is a shared host (local devnet, compose stack, CI matrix)
 * where every port is a different operator-run node, so there is no address-wide
 * attacker to escalate against and the escalation would be pure collateral.
 *
 * <p>The table is hard-bounded (like {@link RateLimiter}) so a spray of distinct
 * endpoints cannot leak memory: when full, entries that are neither banned nor
 * recently active are swept, and if none can be reclaimed the offence is metered
 * against a shared OVERFLOW bucket instead of being dropped. The bucket only
 * COUNTS: unlike {@link RateLimiter}'s fail-closed overflow, it never bans
 * untracked endpoints. Extending the ban to every unknown endpoint turned the
 * memory bound into an eclipse lever — an attacker filling the table (4096
 * throwaway IPs, one offence each) made a freshly started node, which tracks
 * nothing yet, refuse admission to EVERY new peer for the ban window (audit
 * follow-up). An endpoint is banned only by an entry of its own; the overflow
 * score remains observable via {@link #overflowScore()} for operators. Active
 * bans are never swept. Bans are additionally mirrored onto hostname keys
 * (audit L1) in a separate, equally bounded table, so the anti-dodge protection
 * does not lapse when the offence table is full mid-spray. The name mirror is
 * PORT-SCOPED (hostname + port): on a shared-host network, a bare hostname
 * mirror would recreate the eclipse it exists to close.
 */
public final class PeerBanList {

    private final int banThreshold;
    private final long banMillis;
    private final long decayMillis;
    private final int maxTracked;
    private final int addressThreshold;
    private final LongSupplier nowMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /**
     * Shared bucket for offences by endpoints that cannot be tracked because the table
     * is full and nothing is reclaimable. It ACCUMULATES the spray's score (so the
     * attack is visible and {@code misbehave} still reports the offending endpoint as
     * banned once the bucket is hot) but is deliberately never consulted by
     * {@link #isBanned}: banning all untracked endpoints would let a table-filling
     * spray eclipse a freshly started node from every new peer (see class doc).
     */
    private final Entry overflow = new Entry(0);
    /**
     * Ban mirrors keyed by {@link #nameKey}, in a table SEPARATE from {@link #entries} (own
     * bound, same size): when the offence table is full mid-spray the mirror must STILL be
     * written — otherwise the anti-dodge protection (audit L1) silently lapses exactly under
     * attack, and a banned peer returns by flipping its DNS to a fresh IP. Mirrors carry no
     * score, only the ban window, and are swept lazily once their ban expires.
     */
    private final Map<String, Entry> mirrors = new ConcurrentHashMap<>();
    /**
     * Address-wide escalation table (anti-port-rotation), keyed by {@link #addressKey} (the
     * resolved IP, no port) in its own bounded table with the mirrors' discipline: a full
     * table means the escalation is ABANDONED (best-effort), never folded into a shared
     * bucket that would condemn innocent addresses. Carries a score like {@link #entries} —
     * accumulated across every endpoint of the address — and bans only at the escalated
     * {@link #addressThreshold} AND after {@link #MIN_ROTATED_ENDPOINTS} distinct endpoints
     * have offended, so a single transient strike (which now also bans only one endpoint), or
     * one loud endpoint hammering alone, can never blind the rest of the address.
     */
    private final Map<String, Entry> addresses = new ConcurrentHashMap<>();
    /**
     * Amortized-sweep watermarks (one per table), like {@link RateLimiter}'s: the due check
     * reads them OUTSIDE the monitor, so a sprayed miss no longer pays an O(maxTracked)
     * sweep inside the check-and-insert critical section on the event loop (audit: sweep
     * under the global monitor).
     */
    private volatile long lastEntriesSweepAt = Long.MIN_VALUE;
    private volatile long lastMirrorsSweepAt = Long.MIN_VALUE;
    private volatile long lastAddressesSweepAt = Long.MIN_VALUE;

    /**
     * Distinct endpoints of one address that must have offended before the address-wide ban can
     * fire. Port ROTATION is what the escalation exists to catch, so rotation is what it
     * requires: without this, the escalation degenerated into "enough points from anywhere",
     * and one endpoint that kept offending after its own ban (its score stops accumulating, the
     * address's does not) could reach the escalated threshold alone and ban every sibling.
     */
    private static final int MIN_ROTATED_ENDPOINTS = 3;

    /**
     * Cap on the distinct-endpoint set an address entry remembers. Only the "have we seen at
     * least {@link #MIN_ROTATED_ENDPOINTS}?" question is ever asked of it, so counting past a
     * small multiple of that is wasted memory — and an unbounded set would be a port-spray
     * memory lever inside a table that is otherwise strictly bounded.
     */
    private static final int MAX_ROTATED_ENDPOINTS_TRACKED = 8;

    private static final class Entry {
        // Volatile: sweep() reads this outside any entry monitor while misbehave mutates it
        // under synchronized(entry) — the race could sweep a freshly-offending entry (audit).
        volatile long lastOffenseAt;
        int score;
        volatile long bannedUntil;
        /**
         * Distinct offending endpoints, ADDRESS entries only (null everywhere else, so the two
         * other tables pay a reference and nothing more). Guarded by this entry's monitor, like
         * {@code score} — never read outside it.
         */
        Set<String> rotatedEndpoints;
        Entry(long now) { this.lastOffenseAt = now; }
    }

    public PeerBanList(int banThreshold, long banMillis, int maxTracked) {
        this(banThreshold, banMillis, maxTracked, System::currentTimeMillis);
    }

    /**
     * As above, with the clock injected — public for the same reason {@link
     * rhizome.core.blockchain.ChainEngine.Boot#clock} is: a virtual-time adversarial scenario
     * (NET-11) needs to drive a ban window measured in hours without spending real wall-clock time
     * on it, and this repo's established idiom for that is a {@link LongSupplier} seam, not a test
     * hook of its own.
     */
    public PeerBanList(int banThreshold, long banMillis, int maxTracked, LongSupplier nowMillis) {
        this.banThreshold = banThreshold;
        this.banMillis = banMillis;
        // A peer's score bleeds off completely over one ban window of good behaviour.
        this.decayMillis = banMillis;
        this.maxTracked = maxTracked;
        this.addressThreshold = 3 * banThreshold;
        this.nowMillis = nowMillis;
    }

    /**
     * Ban key for a peer URL's ENDPOINT: the host's resolved IP address plus the port
     * (with the hostname in place of the IP when it does not resolve). Port-scoped so
     * one node's ban never eclipses sibling ports on the same address; the address-wide
     * counterpart is {@link #addressKey}.
     */
    static String endpointKey(PeerId peer) {
        if (!peer.isValid()) {
            return "";
        }
        String canonical = peer.canonical();
        try {
            URI uri = URI.create(canonical);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                int port = portOf(uri);
                try {
                    return PeerHosts.resolveFirst(host).getHostAddress() + ":" + port;
                } catch (java.net.UnknownHostException unresolved) {
                    return host.toLowerCase(Locale.ROOT) + ":" + port;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // not a URI: fall through to the canonical form
        }
        return canonical.toLowerCase(Locale.ROOT);
    }

    /**
     * The URL's port with the scheme's DEFAULT folded to the absent form ({@code -1}), matching
     * what {@link PeerUrls#canonicalize} emits. Every port-scoped key here goes through this:
     * {@code http://h:80} and {@code http://h} are one endpoint, and keying them apart would be a
     * silent ban dodge for any caller that hands us a URL it did not canonicalize first. Relying
     * on canonicalization happening upstream would make the ban keys correct only by convention.
     */
    private static int portOf(URI uri) {
        int port = uri.getPort();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            return -1;
        }
        return port;
    }

    /**
     * Escalation key for a peer URL's ADDRESS: the resolved IP with NO port, prefixed so it
     * never collides with an endpoint {@link #endpointKey}. Every endpoint of one address
     * credits the same address entry, so a peer rotating ports accumulates toward the
     * escalated address-wide ban while single-port offences stay endpoint-isolated. Falls
     * back to the hostname (no port) when resolution is unavailable.
     */
    static String addressKey(PeerId peer) {
        if (!peer.isValid()) {
            return "addr:";
        }
        try {
            String host = URI.create(peer.canonical()).getHost();
            if (host != null && !host.isEmpty()) {
                try {
                    return "addr:" + PeerHosts.resolveFirst(host).getHostAddress();
                } catch (java.net.UnknownHostException unresolved) {
                    return "addr:" + host.toLowerCase(Locale.ROOT);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // not a URI: fall through to the canonical form
        }
        return "addr:" + peer.canonical().toLowerCase(Locale.ROOT);
    }

    /**
     * True if the URL's address is one the escalation may act on at all. A host that is not
     * publicly routable — loopback, RFC1918, link-local — is a SHARED HOST in practice (local
     * devnet, compose stack, CI matrix): every port behind it is a different operator-run node,
     * so there is no single address-wide actor to escalate against and an address-wide ban is
     * pure collateral against the operator's own nodes. That is the S5 eclipse rebuilt one layer
     * up, so the escalation simply does not apply there; endpoint bans still do, and on those
     * networks they are the correct granularity anyway.
     */
    private static boolean escalationApplies(PeerId peer) {
        if (!peer.isValid()) {
            return false;
        }
        try {
            String host = URI.create(peer.canonical()).getHost();
            return host != null && !host.isEmpty() && PeerHosts.isPubliclyRoutable(host);
        } catch (IllegalArgumentException notAUri) {
            return false;
        }
    }

    /**
     * Secondary ban key: the peer's hostname (no DNS) and PORT, prefixed so it never collides
     * with an IP {@link #endpointKey}. A ban is mirrored onto this key so an attacker cannot
     * dodge it by flipping the hostname's DNS to a fresh IP between the offence and
     * re-admission (audit L1) — the hostname still matches. Rotating both name and IP is the
     * inherent Sybil limit, out of scope. The key is PORT-SCOPED: on a shared-host network
     * (all peers on {@code localhost}, different ports) a bare hostname mirror would recreate
     * the very eclipse the endpoint-scoped keying removes (testnet campaign S5).
     */
    static String nameKey(PeerId peer) {
        if (!peer.isValid()) {
            return "name:";
        }
        try {
            URI uri = URI.create(peer.canonical());
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                return "name:" + host.toLowerCase(Locale.ROOT) + ":" + portOf(uri);
            }
        } catch (IllegalArgumentException ignored) {
            // not a URI: fall through to the canonical form
        }
        return "name:" + peer.canonical().toLowerCase(Locale.ROOT);
    }

    /**
     * Records {@code points} of misbehaviour against a peer's endpoint AND its address.
     * Returns true if the peer is now banned (its endpoint just crossed the threshold — or
     * was already within an active ban window — or its ADDRESS crossed the escalated
     * address-wide threshold).
     */
    public boolean misbehave(PeerId peer, int points) {
        long now = nowMillis.getAsLong();
        boolean endpointBanned = scoreEndpoint(peer, points, now);
        boolean addressBanned = escalateAddress(peer, points, now);
        return endpointBanned || addressBanned;
    }

    /** Credits the peer's endpoint entry; bans it (plus the name mirror) at the threshold. */
    private boolean scoreEndpoint(PeerId peer, int points, long now) {
        String key = endpointKey(peer);
        maybeSweepEntries(now);
        Entry entry = entries.get(key);
        if (entry == null) {
            // Check-and-insert under one monitor: a lock-free size() >= maxTracked check racing
            // computeIfAbsent let concurrent admissions push the table past its bound (audit).
            // The monitor covers ONLY the check+insert — expiry is reclaimed by the amortized
            // sweep above, never inside this critical section.
            synchronized (entries) {
                entry = entries.get(key);
                if (entry == null) {
                    if (entries.size() >= maxTracked) {
                        // Table full: meter the untracked endpoint against the shared overflow
                        // bucket so the spray's score still accumulates (and this offending
                        // endpoint is reported banned once the bucket is hot) — but the bucket
                        // never bans OTHER untracked endpoints (see above). The next amortized
                        // sweep reclaims decayed entries and tracking resumes.
                        entry = overflow;
                    } else {
                        entry = entries.computeIfAbsent(key, k -> new Entry(now));
                    }
                }
            }
        }
        synchronized (entry) {
            if (now < entry.bannedUntil) {
                return true; // already banned; extra offences don't shorten it
            }
            decay(entry, now, banThreshold);
            entry.score += points;
            entry.lastOffenseAt = now;
            if (entry.score >= banThreshold) {
                entry.bannedUntil = now + banMillis;
                entry.score = 0; // start clean once the ban lifts
                mirrorToName(peer, entry.bannedUntil, now);
                return true;
            }
            return false;
        }
    }

    /**
     * Credits the peer's ADDRESS entry toward the escalated address-wide ban (anti-port-
     * rotation). Best-effort by design: when the address table is full the escalation is
     * abandoned — the endpoint ban still stands — rather than folded into a shared bucket
     * that could condemn an innocent address (testnet campaign S5 collateral).
     *
     * <p>Two guards keep the escalation from becoming the eclipse it replaced. It does not
     * apply at all to shared-host addresses ({@link #escalationApplies}), and it needs genuine
     * ROTATION — {@link #MIN_ROTATED_ENDPOINTS} distinct offending endpoints — on top of the
     * escalated score. Without the rotation guard, points alone decided: an endpoint that keeps
     * offending after its own ban lands (its score stops accumulating, the address's does not)
     * would reach the address threshold single-handed and ban every sibling port with it.
     */
    private boolean escalateAddress(PeerId peer, int points, long now) {
        if (!escalationApplies(peer)) {
            return false;
        }
        String key = addressKey(peer);
        String endpoint = endpointKey(peer);
        maybeSweepAddresses(now);
        Entry entry = addresses.get(key);
        if (entry == null) {
            // Same atomic check-and-insert discipline as scoreEndpoint (audit: bounded-table
            // race) — expiry reclaimed by the amortized sweep, outside this critical section.
            synchronized (addresses) {
                entry = addresses.get(key);
                if (entry == null) {
                    if (addresses.size() >= maxTracked) {
                        return false; // escalation abandoned: best-effort
                    }
                    entry = new Entry(now);
                    addresses.put(key, entry);
                }
            }
        }
        synchronized (entry) {
            if (now < entry.bannedUntil) {
                return true; // already address-banned
            }
            // Decays against the ADDRESS threshold, not the endpoint one: an entry that needs 3×
            // the points to fire must bleed 3× as fast to keep the same "one quiet ban window
            // wipes the slate" half-life. Sharing the endpoint rate made the address ban — the
            // most collateral of the three — by far the stickiest.
            decay(entry, now, addressThreshold);
            entry.score += points;
            entry.lastOffenseAt = now;
            if (entry.rotatedEndpoints == null) {
                entry.rotatedEndpoints = new LinkedHashSet<>();
            }
            if (entry.rotatedEndpoints.size() < MAX_ROTATED_ENDPOINTS_TRACKED) {
                entry.rotatedEndpoints.add(endpoint);
            }
            if (entry.score >= addressThreshold
                    && entry.rotatedEndpoints.size() >= MIN_ROTATED_ENDPOINTS) {
                entry.bannedUntil = now + banMillis;
                entry.score = 0; // start clean once the ban lifts
                entry.rotatedEndpoints.clear(); // rotation must be re-proven after the ban
                return true;
            }
            return false;
        }
    }

    /**
     * Mirrors a ban onto the hostname key so a DNS rebind to a new IP cannot dodge it (L1).
     * Written to the separate {@link #mirrors} table: with the offence table full mid-spray the
     * mirror used to be skipped, abandoning the anti-dodge protection exactly under attack.
     */
    private void mirrorToName(PeerId peer, long bannedUntil, long now) {
        String key = nameKey(peer);
        maybeSweepMirrors(now);
        Entry entry = mirrors.get(key);
        if (entry == null) {
            // Same atomic check-and-insert discipline as misbehave (audit: bounded-table race) —
            // expiry reclaimed by the amortized sweep, outside this critical section.
            synchronized (mirrors) {
                entry = mirrors.get(key);
                if (entry == null) {
                    if (mirrors.size() >= maxTracked) {
                        // Full and nothing reclaimed by the last amortized sweep: this mirror is
                        // dropped — the IP-key ban still applies (best-effort, as before).
                        return;
                    }
                    entry = new Entry(now);
                    mirrors.put(key, entry);
                }
            }
        }
        synchronized (entry) {
            entry.bannedUntil = Math.max(entry.bannedUntil, bannedUntil);
        }
    }

    /** Bans a peer outright, regardless of its current score. */
    public void ban(PeerId peer) {
        misbehave(peer, banThreshold);
    }

    public boolean isBanned(PeerId peer) {
        long now = nowMillis.getAsLong();
        Entry byEndpoint = entries.get(endpointKey(peer));
        Entry byName = mirrors.get(nameKey(peer));
        Entry byAddress = addresses.get(addressKey(peer));
        // An untracked endpoint is NEVER banned: the shared overflow bucket only counts the
        // spray, it must not condemn an endpoint that committed no offence of its own (eclipse
        // lever). Likewise an address is only ever banned by its own escalation entry.
        if (byEndpoint == null && byName == null && byAddress == null) {
            return false;
        }
        return (byEndpoint != null && now < byEndpoint.bannedUntil)
            || (byName != null && now < byName.bannedUntil)
            || (byAddress != null && now < byAddress.bannedUntil);
    }

    /** Current score of the shared overflow bucket (spray observability; never a ban source). */
    public int overflowScore() {
        synchronized (overflow) {
            return overflow.score;
        }
    }

    /**
     * Bleeds off score at "{@code threshold} points per decay window", so every table keeps the
     * same half-life relative to ITS OWN threshold: one quiet ban window of good behaviour wipes
     * a full slate, whether the slate is an endpoint's {@code banThreshold} or an address's
     * escalated {@code addressThreshold}. Passing the endpoint rate to the address table made the
     * address ban — the one with the widest collateral — the slowest to forgive.
     */
    private void decay(Entry entry, long now, int threshold) {
        long elapsed = now - entry.lastOffenseAt;
        if (elapsed > 0 && entry.score > 0) {
            int dropped = (int) (elapsed * threshold / decayMillis);
            entry.score = Math.max(0, entry.score - dropped);
        }
    }

    /**
     * Amortized expiry sweep of the offence table: at most one full pass per decay window.
     * The due check reads the volatile watermark outside the monitor; only a due sweep locks
     * (re-checked inside). Sweeping only REMOVES entries that are neither banned nor recently
     * active, so it cannot race the check-and-insert bound in {@link #misbehave}.
     */
    private void maybeSweepEntries(long now) {
        if (now < lastEntriesSweepAt + decayMillis) {
            return;
        }
        synchronized (entries) {
            if (now < lastEntriesSweepAt + decayMillis) {
                return;
            }
            lastEntriesSweepAt = now;
            entries.values().removeIf(e -> now >= e.bannedUntil && now - e.lastOffenseAt >= decayMillis);
        }
    }

    /** As {@link #maybeSweepEntries}, for the name-mirror table (expired-ban mirrors). */
    private void maybeSweepMirrors(long now) {
        if (now < lastMirrorsSweepAt + banMillis) {
            return;
        }
        synchronized (mirrors) {
            if (now < lastMirrorsSweepAt + banMillis) {
                return;
            }
            lastMirrorsSweepAt = now;
            mirrors.values().removeIf(e -> now >= e.bannedUntil);
        }
    }

    /** As {@link #maybeSweepEntries}, for the address-escalation table (expired, decayed entries). */
    private void maybeSweepAddresses(long now) {
        if (now < lastAddressesSweepAt + decayMillis) {
            return;
        }
        synchronized (addresses) {
            if (now < lastAddressesSweepAt + decayMillis) {
                return;
            }
            lastAddressesSweepAt = now;
            addresses.values().removeIf(e -> now >= e.bannedUntil && now - e.lastOffenseAt >= decayMillis);
        }
    }

    public int trackedPeers() {
        return entries.size();
    }
}
