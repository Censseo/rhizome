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
 * recently active are swept, and if none can be reclaimed the offence is metered
 * against a shared OVERFLOW bucket instead of being dropped. The bucket only
 * COUNTS: unlike {@link RateLimiter}'s fail-closed overflow, it never bans
 * untracked hosts. Extending the ban to every unknown host turned the memory
 * bound into an eclipse lever — an attacker filling the table (4096 throwaway
 * IPs, one offence each) made a freshly started node, which tracks nothing yet,
 * refuse admission to EVERY new peer for the ban window (audit follow-up). A
 * host is banned only by an entry of its own; the overflow score remains
 * observable via {@link #overflowScore()} for operators. Active bans are never
 * swept. Bans are additionally mirrored onto hostname keys (audit L1) in a
 * separate, equally bounded table, so the anti-dodge protection does not lapse
 * when the offence table is full mid-spray.
 */
public final class PeerBanList {

    private final int banThreshold;
    private final long banMillis;
    private final long decayMillis;
    private final int maxTracked;
    private final LongSupplier nowMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /**
     * Shared bucket for offences by hosts that cannot be tracked because the table
     * is full and nothing is reclaimable. It ACCUMULATES the spray's score (so the
     * attack is visible and {@code misbehave} still reports the offending host as
     * banned once the bucket is hot) but is deliberately never consulted by
     * {@link #isBanned}: banning all untracked hosts would let a table-filling
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
     * Amortized-sweep watermarks (one per table), like {@link RateLimiter}'s: the due check
     * reads them OUTSIDE the monitor, so a sprayed miss no longer pays an O(maxTracked)
     * sweep inside the check-and-insert critical section on the event loop (audit: sweep
     * under the global monitor).
     */
    private volatile long lastEntriesSweepAt = Long.MIN_VALUE;
    private volatile long lastMirrorsSweepAt = Long.MIN_VALUE;

    private static final class Entry {
        // Volatile: sweep() reads this outside any entry monitor while misbehave mutates it
        // under synchronized(entry) — the race could sweep a freshly-offending entry (audit).
        volatile long lastOffenseAt;
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
                    return PeerHosts.resolveFirst(host).getHostAddress();
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
                        // Table full: meter the untracked host against the shared overflow bucket so the
                        // spray's score still accumulates (and this offending host is reported banned once
                        // the bucket is hot) — but the bucket never bans OTHER untracked hosts (see above).
                        // The next amortized sweep reclaims decayed entries and tracking resumes.
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

    /**
     * Mirrors a ban onto the hostname key so a DNS rebind to a new IP cannot dodge it (L1).
     * Written to the separate {@link #mirrors} table: with the offence table full mid-spray the
     * mirror used to be skipped, abandoning the anti-dodge protection exactly under attack.
     */
    private void mirrorToName(String peerUrl, long bannedUntil, long now) {
        String key = nameKey(peerUrl);
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
    public void ban(String peerUrl) {
        misbehave(peerUrl, banThreshold);
    }

    public boolean isBanned(String peerUrl) {
        long now = nowMillis.getAsLong();
        Entry byHost = entries.get(hostKey(peerUrl));
        Entry byName = mirrors.get(nameKey(peerUrl));
        // An untracked host is NEVER banned: the shared overflow bucket only counts the spray,
        // it must not condemn a host that committed no offence of its own (eclipse lever).
        if (byHost == null && byName == null) {
            return false;
        }
        return (byHost != null && now < byHost.bannedUntil) || (byName != null && now < byName.bannedUntil);
    }

    /** Current score of the shared overflow bucket (spray observability; never a ban source). */
    public int overflowScore() {
        synchronized (overflow) {
            return overflow.score;
        }
    }

    private void decay(Entry entry, long now) {
        long elapsed = now - entry.lastOffenseAt;
        if (elapsed > 0 && entry.score > 0) {
            int dropped = (int) (elapsed * banThreshold / decayMillis);
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

    public int trackedPeers() {
        return entries.size();
    }
}
