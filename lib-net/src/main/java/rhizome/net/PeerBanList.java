package rhizome.net;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A ban-score table for misbehaving peers, keyed by host so a peer cannot dodge
 * a ban by rotating its port or path. Modelled on Bitcoin Core's banscore: a
 * peer accumulates points for protocol violations and, once it crosses a
 * threshold, is banned for a fixed window; scores decay over time so an honest
 * peer is not punished forever for the occasional blip.
 *
 * <p>The table is hard-bounded (like {@link RateLimiter}) so a spray of distinct
 * hosts cannot leak memory: when full, entries that are neither banned nor
 * recently active are swept, and if none can be reclaimed a brand-new host is
 * simply left untracked (fail-open on tracking, never unbounded growth). Active
 * bans are never swept.
 */
public final class PeerBanList {

    private final int banThreshold;
    private final long banMillis;
    private final long decayMillis;
    private final int maxTracked;
    private final LongSupplier nowMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private static final class Entry {
        long lastOffenseAt;
        int score;
        volatile long bannedUntil;
        Entry(long now) { this.lastOffenseAt = now; }
    }

    public PeerBanList(int banThreshold, long banMillis, int maxTracked) {
        this(banThreshold, banMillis, maxTracked, System::currentTimeMillis);
    }

    PeerBanList(int banThreshold, long banMillis, int maxTracked, LongSupplier nowMillis) {
        this.banThreshold = banThreshold;
        this.banMillis = banMillis;
        // A peer's score bleeds off completely over one ban window of good behaviour.
        this.decayMillis = banMillis;
        this.maxTracked = maxTracked;
        this.nowMillis = nowMillis;
    }

    /**
     * Ban key for a peer URL: the host's resolved IP address when it resolves, else the
     * hostname. Keying by IP means a peer cannot dodge a ban by rotating the DNS name it
     * advertises (all names for one address share the ban); it falls back to the hostname
     * (and finally the raw string) when resolution is unavailable.
     */
    static String hostKey(String peerUrl) {
        if (peerUrl == null) {
            return "";
        }
        try {
            String host = URI.create(peerUrl.trim()).getHost();
            if (host != null && !host.isEmpty()) {
                try {
                    return java.net.InetAddress.getByName(host).getHostAddress();
                } catch (java.net.UnknownHostException unresolved) {
                    return host.toLowerCase();
                }
            }
        } catch (IllegalArgumentException ignored) {
            // not a URI: fall through to the trimmed raw form
        }
        return peerUrl.trim().toLowerCase();
    }

    /**
     * Secondary ban key: the peer's hostname (no DNS), prefixed so it never collides with an IP
     * {@link #hostKey}. A ban is mirrored onto this key so an attacker cannot dodge it by flipping
     * the hostname's DNS to a fresh IP between the offence and re-admission (audit L1) — the
     * hostname still matches. Rotating both name and IP is the inherent Sybil limit, out of scope.
     */
    static String nameKey(String peerUrl) {
        if (peerUrl == null) {
            return "name:";
        }
        try {
            String host = URI.create(peerUrl.trim()).getHost();
            if (host != null && !host.isEmpty()) {
                return "name:" + host.toLowerCase();
            }
        } catch (IllegalArgumentException ignored) {
            // not a URI: fall through to the trimmed raw form
        }
        return "name:" + peerUrl.trim().toLowerCase();
    }

    /**
     * Records {@code points} of misbehaviour against a peer. Returns true if the
     * peer is now banned (either it just crossed the threshold or was already
     * within an active ban window).
     */
    public boolean misbehave(String peerUrl, int points) {
        String key = hostKey(peerUrl);
        long now = nowMillis.getAsLong();
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= maxTracked && !sweep(now)) {
                return false; // tracking full, nothing reclaimable: don't grow
            }
            entry = entries.computeIfAbsent(key, k -> new Entry(now));
        }
        synchronized (entry) {
            if (now < entry.bannedUntil) {
                return true; // already banned; extra offences don't shorten it
            }
            decay(entry, now);
            entry.score += points;
            entry.lastOffenseAt = now;
            if (entry.score >= banThreshold) {
                entry.bannedUntil = now + banMillis;
                entry.score = 0; // start clean once the ban lifts
                mirrorToName(peerUrl, entry.bannedUntil, now);
                return true;
            }
            return false;
        }
    }

    /** Mirrors a ban onto the hostname key so a DNS rebind to a new IP cannot dodge it (L1). */
    private void mirrorToName(String peerUrl, long bannedUntil, long now) {
        String key = nameKey(peerUrl);
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= maxTracked && !sweep(now)) {
                return; // tracking full: best-effort, the IP-key ban still applies
            }
            entry = entries.computeIfAbsent(key, k -> new Entry(now));
        }
        synchronized (entry) {
            entry.bannedUntil = Math.max(entry.bannedUntil, bannedUntil);
        }
    }

    /** Bans a peer outright, regardless of its current score. */
    public void ban(String peerUrl) {
        misbehave(peerUrl, banThreshold);
    }

    public boolean isBanned(String peerUrl) {
        long now = nowMillis.getAsLong();
        return activeBan(hostKey(peerUrl), now) || activeBan(nameKey(peerUrl), now);
    }

    private boolean activeBan(String key, long now) {
        Entry entry = entries.get(key);
        return entry != null && now < entry.bannedUntil;
    }

    private void decay(Entry entry, long now) {
        long elapsed = now - entry.lastOffenseAt;
        if (elapsed > 0 && entry.score > 0) {
            int dropped = (int) (elapsed * banThreshold / decayMillis);
            entry.score = Math.max(0, entry.score - dropped);
        }
    }

    /** Removes entries that are not banned and have fully decayed. Returns true if any went. */
    private boolean sweep(long now) {
        int before = entries.size();
        entries.values().removeIf(e -> now >= e.bannedUntil && now - e.lastOffenseAt >= decayMillis);
        return entries.size() < before;
    }

    public int trackedPeers() {
        return entries.size();
    }
}
