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

    private final Map<Integer, ScanPredicate> scans = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    int register(ScanPredicate predicate) {
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
