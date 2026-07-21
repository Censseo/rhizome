package rhizome.node;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger nextId = new AtomicInteger(1);

    int register(ScanPredicate predicate) {
        if (scans.size() >= MAX_SCANS) {
            throw new IllegalStateException("scan registry full (max " + MAX_SCANS + ")");
        }
        int id = nextId.getAndIncrement();
        scans.put(id, predicate);
        return id;
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
