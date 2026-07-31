package rhizome.node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /**
     * Who a scan belongs to: the client key (source IP, or the IPv6 /64) and — when the caller
     * sent an {@code X-Scan-Owner} header — the SHA-256 of that secret.
     *
     * <p>The IP alone was the whole ownership proof, which holds only where the source address
     * identifies a user (audit B-5). Behind a NAT, a shared VPN or a reverse proxy — where every
     * request arrives with one address — co-tenants shared an owner key: {@code /scan/list}
     * handed them each other's scan ids and predicates, and {@code /scan/deregister} then let
     * anyone wipe them, no id-guessing needed. A caller that sends the header gets ownership
     * bound to a secret only it knows; one that does not keeps the previous IP-only behaviour
     * (unchanged for existing apps, and still all a single-tenant deployment needs).
     *
     * <p>Both halves are required to match, so a stolen secret from another address does not
     * carry ownership either. The secret is stored HASHED — a heap dump of the node must not
     * hand out live capabilities — and compared in constant time.
     */
    record Owner(String clientKey, String secretHash) {

        /** {@code secret} is the raw {@code X-Scan-Owner} header value; null/blank means the
         *  caller opted out and ownership rests on {@code clientKey} alone. */
        static Owner of(String clientKey, String secret) {
            return new Owner(clientKey == null ? "local" : clientKey, hash(secret));
        }

        private static String hash(String secret) {
            if (secret == null || secret.isBlank()) {
                return null;
            }
            try {
                return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e); // never on a JRE
            }
        }

        /** True if this owner may act on a scan registered by {@code registered}. */
        boolean matches(Owner registered) {
            if (!clientKey.equals(registered.clientKey())) {
                return false;
            }
            if (secretHash == null || registered.secretHash() == null) {
                // A secret-less scan is IP-owned as before; a secret-protected one can never be
                // reached without presenting the secret (and vice versa — no downgrade).
                return secretHash == null && registered.secretHash() == null;
            }
            return MessageDigest.isEqual(secretHash.getBytes(StandardCharsets.UTF_8),
                registered.secretHash().getBytes(StandardCharsets.UTF_8));
        }
    }

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
    private record Entry(Owner owner, ScanPredicate predicate, long lastActivity) {}

    private final Map<Integer, Entry> scans = new ConcurrentHashMap<>();
    // Unguessable ids from a CSPRNG rather than a 1,2,3… counter: the endpoints are unauthenticated, so
    // a sequential id let any caller enumerate 1..N and deregister (wipe) every app's scans. A sparse
    // random id in a 2^31 space makes that enumeration infeasible while keeping the int-id API (audit S-10).
    private final SecureRandom rng = new SecureRandom();

    int register(Owner owner, ScanPredicate predicate) {
        synchronized (scans) {
            // The per-client cap counts by CLIENT KEY, not by owner: it bounds memory against an
            // unauthenticated endpoint, so varying the X-Scan-Owner secret must not mint fresh
            // quota (one IP, one budget).
            int mine = 0;
            for (Entry e : scans.values()) {
                if (e.owner().clientKey().equals(owner.clientKey())) {
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
            scans.put(id, new Entry(owner, predicate, System.currentTimeMillis()));
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

    /**
     * The predicate of {@code id} for its owner only — a foreign id is indistinguishable from
     * an unknown one (non-disclosing, like {@link #deregister}). Only an owner query counts as
     * activity: refreshing for any caller let a non-owner keep a learned victim scan alive
     * against idle eviction (audit: scan activity refresh gating). The non-owner fast path is a
     * pure read — no {@link Entry} allocation on the query path (audit: read-path allocation);
     * the refresh re-allocates only here, for the owner.
     */
    ScanPredicate getForOwner(int id, Owner owner) {
        Entry e = scans.get(id);
        if (e == null || !owner.matches(e.owner())) {
            return null;
        }
        // Querying their own scan counts as activity, so an actively-polled scan is never the
        // idle-eviction victim when the table fills up. computeIfPresent re-checks the mapping
        // so a concurrently deregistered scan is not resurrected.
        scans.computeIfPresent(id, (k, v) -> owner.matches(v.owner())
            ? new Entry(v.owner(), v.predicate(), System.currentTimeMillis()) : v);
        return e.predicate();
    }

    /**
     * Removes a scan, but only for its owner: the endpoint is unauthenticated and the random
     * 2^31 ids make guessing expensive, not impossible — without the owner check anyone who
     * learns or grinds an id could delete another app's scan (audit follow-up). A foreign id
     * returns false, indistinguishable from an unknown one (non-disclosing).
     */
    boolean deregister(int id, Owner owner) {
        Entry e = scans.get(id);
        if (e == null || !owner.matches(e.owner())) {
            return false;
        }
        return scans.remove(id, e);
    }

    /** Scans registered by {@code owner} only — /scan/list must not disclose every other
     *  client's predicates to an unauthenticated caller (audit F1), nor a NAT co-tenant's
     *  (audit B-5). */
    Map<Integer, ScanPredicate> scansOf(Owner owner) {
        Map<Integer, ScanPredicate> out = new java.util.HashMap<>();
        for (Map.Entry<Integer, Entry> e : scans.entrySet()) {
            if (owner.matches(e.getValue().owner())) {
                out.put(e.getKey(), e.getValue().predicate());
            }
        }
        return Map.copyOf(out);
    }
}
