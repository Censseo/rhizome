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

    private final Map<Integer, ScanPredicate> scans = new ConcurrentHashMap<>();
    // Unguessable ids from a CSPRNG rather than a 1,2,3… counter: the endpoints are unauthenticated, so
    // a sequential id let any caller enumerate 1..N and deregister (wipe) every app's scans. A sparse
    // random id in a 2^31 space makes that enumeration infeasible while keeping the int-id API (audit S-10).
    private final SecureRandom rng = new SecureRandom();

    int register(ScanPredicate predicate) {
        synchronized (scans) {
            if (scans.size() >= MAX_SCANS) {
                throw new IllegalStateException("scan registry full (max " + MAX_SCANS + ")");
            }
            int id;
            do {
                id = rng.nextInt() & 0x7FFF_FFFF; // positive, non-sequential
            } while (id == 0 || scans.containsKey(id));
            scans.put(id, predicate);
            return id;
        }
    }

    ScanPredicate get(int id) {
        return scans.get(id);
    }

    boolean deregister(int id) {
        return scans.remove(id) != null;
    }

    Map<Integer, ScanPredicate> all() {
        return Map.copyOf(scans);
    }
}
