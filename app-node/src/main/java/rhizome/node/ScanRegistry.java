package rhizome.node;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import rhizome.core.box.ScanPredicate;

/**
 * Per-node registry of box scans (EIP-1 style): a declarative {@link ScanPredicate}
 * mapped to an id an app uses to query matching boxes. Node-local and non-consensus —
 * scans are a query convenience, not part of the chain — so this is a simple in-memory
 * table (lost on restart; re-registered by the app).
 */
final class ScanRegistry {

    /** Cap on live scans: the register endpoint is unauthenticated, so an unbounded map is
     *  a remote memory-exhaustion DoS. Callers deregister when done; 1024 is ample per node. */
    private static final int MAX_SCANS = 1024;

    /** Cap on live scans per client key (the same IP / IPv6-/64 keying as the rate limiter):
     *  the register endpoint is unauthenticated and scan ids are unguessable, so without a
     *  per-client bound one IP could claim every global slot in about a second and legitimate
     *  users could never free them (audit F1). 32 concurrent scans is ample for one app. */
    private static final int MAX_SCANS_PER_CLIENT = 32;

    /** A registered scan with its owner (for per-client caps and non-disclosing listings) and
     *  the last time it was registered or queried (for idle eviction). */
    private record Entry(String ownerKey, ScanPredicate predicate, long lastActivity) {}

    private final Map<Integer, Entry> scans = new ConcurrentHashMap<>();
    // Unguessable ids from a CSPRNG rather than a 1,2,3… counter: the endpoints are unauthenticated, so
    // a sequential id let any caller enumerate 1..N and deregister (wipe) every app's scans. A sparse
    // random id in a 2^31 space makes that enumeration infeasible while keeping the int-id API (audit S-10).
    private final SecureRandom rng = new SecureRandom();

    int register(String clientKey, ScanPredicate predicate) {
        synchronized (scans) {
            int mine = 0;
            for (Entry e : scans.values()) {
                if (e.ownerKey().equals(clientKey)) {
                    mine++;
                }
            }
            if (mine >= MAX_SCANS_PER_CLIENT) {
                throw new IllegalStateException(
                    "too many scans registered for this client (max " + MAX_SCANS_PER_CLIENT + ")");
            }
            if (scans.size() >= MAX_SCANS) {
                evictIdlest();
            }
            int id;
            do {
                id = rng.nextInt() & 0x7FFF_FFFF; // positive, non-sequential
            } while (id == 0 || scans.containsKey(id));
            scans.put(id, new Entry(clientKey, predicate, System.currentTimeMillis()));
            return id;
        }
    }

    /**
     * Drops the longest-idle scan to make room for a newcomer (LRU/idle eviction). A full
     * registry is almost always abandoned scans that were never deregistered; evicting the
     * idlest keeps the endpoint available instead of hard-rejecting every new registration
     * once an attacker (or a leaky app) has filled the table (audit F1).
     */
    private void evictIdlest() {
        int idlestId = -1;
        long idlest = Long.MAX_VALUE;
        for (Map.Entry<Integer, Entry> e : scans.entrySet()) {
            if (e.getValue().lastActivity() < idlest) {
                idlest = e.getValue().lastActivity();
                idlestId = e.getKey();
            }
        }
        if (idlestId >= 0) {
            scans.remove(idlestId);
        }
    }

    ScanPredicate get(int id) {
        // Querying a scan counts as activity, so an actively-polled scan is never the idle-eviction
        // victim when the table fills up.
        Entry e = scans.computeIfPresent(id,
            (k, v) -> new Entry(v.ownerKey(), v.predicate(), System.currentTimeMillis()));
        return e == null ? null : e.predicate();
    }

    /**
     * Removes a scan, but only for its owner: the endpoint is unauthenticated and the random
     * 2^31 ids make guessing expensive, not impossible — without the owner check anyone who
     * learns or grinds an id could delete another app's scan (audit follow-up). A foreign id
     * returns false, indistinguishable from an unknown one (non-disclosing).
     */
    boolean deregister(int id, String clientKey) {
        Entry e = scans.get(id);
        if (e == null || !e.ownerKey().equals(clientKey)) {
            return false;
        }
        return scans.remove(id, e);
    }

    /** Scans registered by {@code clientKey} only — /scan/list must not disclose every other
     *  client's predicates to an unauthenticated caller (audit F1). */
    Map<Integer, ScanPredicate> scansOf(String clientKey) {
        Map<Integer, ScanPredicate> out = new java.util.HashMap<>();
        for (Map.Entry<Integer, Entry> e : scans.entrySet()) {
            if (e.getValue().ownerKey().equals(clientKey)) {
                out.put(e.getKey(), e.getValue().predicate());
            }
        }
        return Map.copyOf(out);
    }
}
