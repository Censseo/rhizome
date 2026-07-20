package rhizome.core.state;

import java.util.concurrent.ConcurrentSkipListMap;

/** In-memory {@link RootStore} for tests and light nodes. */
public final class InMemoryRootStore implements RootStore {

    private final ConcurrentSkipListMap<Long, byte[]> roots = new ConcurrentSkipListMap<>();

    @Override
    public byte[] getRoot(long height) {
        byte[] r = roots.get(height);
        return r == null ? null : r.clone();
    }

    @Override
    public void putRoot(long height, byte[] root) {
        roots.put(height, root.clone());
    }

    @Override
    public void deleteRoot(long height) {
        roots.remove(height);
    }

    @Override
    public long latestHeight() {
        return roots.isEmpty() ? -1 : roots.lastKey();
    }

    @Override
    public void pruneBelow(long minHeight) {
        roots.headMap(minHeight).clear();
    }
}
