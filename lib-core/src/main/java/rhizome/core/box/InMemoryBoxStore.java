package rhizome.core.box;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import rhizome.core.common.Utils;

/**
 * In-memory {@link BoxStore} for tests and light nodes. Boxes live in a map; the
 * per-height undo journal is kept in memory too (the RocksDB store persists it).
 * Thread-safe for concurrent reads alongside single-threaded block application.
 */
public final class InMemoryBoxStore implements BoxStore {

    private final Map<String, Box> boxes = new ConcurrentHashMap<>();
    private final Map<Long, List<Undo>> journals = new ConcurrentSkipListMap<>();

    private record Undo(byte[] id, Box prior) {}

    @Override
    public Box get(byte[] id) {
        return boxes.get(hex(id));
    }

    @Override
    public void applyBlock(long height, List<BoxMutation> mutations) {
        List<Undo> journal = new ArrayList<>(mutations.size());
        for (BoxMutation m : mutations) {
            String key = hex(m.id());
            Box prior = boxes.get(key);
            journal.add(new Undo(m.id().clone(), prior));
            if (m.box() == null) {
                boxes.remove(key);
            } else {
                boxes.put(key, m.box());
            }
        }
        journals.put(height, journal);
    }

    @Override
    public void revertBlock(long height) {
        List<Undo> journal = journals.remove(height);
        if (journal == null) {
            return;
        }
        for (int i = journal.size() - 1; i >= 0; i--) {
            Undo u = journal.get(i);
            String key = hex(u.id());
            if (u.prior() == null) {
                boxes.remove(key);
            } else {
                boxes.put(key, u.prior());
            }
        }
    }

    @Override
    public void pruneJournals(long minHeight) {
        journals.keySet().removeIf(h -> h < minHeight);
    }

    @Override
    public List<byte[]> collectableBoxIds(long height, long storagePeriodBlocks, int limit) {
        List<Box> collectable = new ArrayList<>();
        for (Box box : boxes.values()) {
            if (box.expiryHeight(storagePeriodBlocks) <= height) {
                collectable.add(box);
            }
        }
        collectable.sort(Comparator.comparingLong(b -> b.expiryHeight(storagePeriodBlocks)));
        List<byte[]> out = new ArrayList<>(Math.min(limit, collectable.size()));
        for (Box box : collectable) {
            if (out.size() >= limit) {
                break;
            }
            out.add(box.id());
        }
        return out;
    }

    @Override
    public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
        List<byte[]> ids = new ArrayList<>();
        for (Box box : boxes.values()) {
            if (Arrays.equals(box.owner().toBytes(), owner)) {
                ids.add(box.id());
            }
        }
        ids.sort((a, b) -> Arrays.compareUnsigned(a, b));
        List<byte[]> out = new ArrayList<>();
        boolean started = afterId == null;
        for (byte[] id : ids) {
            if (!started) {
                if (Arrays.equals(id, afterId)) {
                    started = true;
                }
                continue;
            }
            if (out.size() >= limit) {
                break;
            }
            out.add(id);
        }
        return out;
    }

    @Override
    public List<byte[]> boxIdsFrom(byte[] afterId, int limit) {
        List<byte[]> ids = new ArrayList<>();
        for (Box box : boxes.values()) {
            ids.add(box.id());
        }
        ids.sort((a, b) -> Arrays.compareUnsigned(a, b));
        List<byte[]> out = new ArrayList<>();
        for (byte[] id : ids) {
            if (afterId != null && Arrays.compareUnsigned(id, afterId) <= 0) {
                continue;
            }
            if (out.size() >= limit) {
                break;
            }
            out.add(id);
        }
        return out;
    }

    @Override
    public void forEachBox(java.util.function.Consumer<Box> consumer) {
        boxes.values().forEach(consumer);
    }

    private static String hex(byte[] b) {
        return Utils.bytesToHex(b);
    }
}
