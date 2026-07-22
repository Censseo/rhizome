package rhizome.core.blockchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import rhizome.core.block.Block;
import rhizome.crypto.SHA256Hash;

/**
 * A bounded, LRU cache of valid blocks the node has seen off the main chain —
 * competing siblings and short-forked blocks. These are the candidates a later
 * block may reference as uncles (GHOST). Bounded so it cannot grow without limit
 * under a spray of orphans.
 *
 * <p>Thread-safe via coarse synchronisation; access is infrequent (block arrival).
 */
public final class OrphanPool {

    private final Map<SHA256Hash, Block> pool;

    public OrphanPool(int maxSize) {
        this.pool = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SHA256Hash, Block> eldest) {
                return size() > maxSize;
            }
        };
    }

    public synchronized void put(Block block) {
        pool.put(block.hash(), block);
    }

    /** The orphan with this hash, or {@code null} if unknown. */
    public synchronized Block get(SHA256Hash hash) {
        return pool.get(hash);
    }

    public synchronized boolean contains(SHA256Hash hash) {
        return pool.containsKey(hash);
    }

    public synchronized int size() {
        return pool.size();
    }

    /** A snapshot of the pooled orphans, most-recently-used last. */
    public synchronized List<Block> snapshot() {
        return new ArrayList<>(pool.values());
    }
}
