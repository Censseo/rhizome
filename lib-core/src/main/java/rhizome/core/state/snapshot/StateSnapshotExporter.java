package rhizome.core.state.snapshot;

import java.util.ArrayList;
import java.util.List;

import rhizome.core.state.StateKeys;

/**
 * Dumps the full committed state into bounded {@link SnapshotChunk}s, one domain at a time
 * in canonical order. The caller is responsible for capturing a consistent view (the engine
 * exposes a lock-held capture point); the exporter itself just enumerates and slices.
 *
 * <p>Unlike Ergo's AVL+ manifest/subtree scheme, no tree structure is exported: the
 * sparse-Merkle root is order-independent, so flat per-domain dumps suffice and the importer
 * verifies the whole snapshot with one root equality.
 */
public final class StateSnapshotExporter {

    /** The committed domains, in canonical export order. */
    public static final byte[] DOMAINS = {
        StateKeys.LEDGER, StateKeys.ACCOUNT_NONCE, StateKeys.BOX, StateKeys.TOKEN_META,
        StateKeys.TOKEN_BALANCE, StateKeys.CONTRACT_CODE, StateKeys.CONTRACT_STORAGE
    };

    /** Default byte bound per chunk (a chunk flushes once it crosses this). */
    public static final int DEFAULT_CHUNK_BYTES = 1 << 20;

    private StateSnapshotExporter() {}

    /** As {@link #export(StateSource, int, int)} with the default ~1 MiB byte bound. */
    public static List<SnapshotChunk> export(StateSource source, int maxEntriesPerChunk) {
        return export(source, maxEntriesPerChunk, DEFAULT_CHUNK_BYTES);
    }

    /**
     * Dumps every domain of {@code source} into chunks of at most {@code maxEntriesPerChunk}
     * entries, flushing early once a chunk crosses {@code maxBytesPerChunk} (a single
     * oversized entry — e.g. large contract code — still lands whole in its own chunk).
     *
     * <p>Materialises the whole list in memory; for large states prefer
     * {@link #export(StateSource, int, int, java.util.function.Consumer)}, which hands each
     * chunk off as it is produced and never holds more than one.
     */
    public static List<SnapshotChunk> export(StateSource source, int maxEntriesPerChunk, int maxBytesPerChunk) {
        List<SnapshotChunk> chunks = new ArrayList<>();
        export(source, maxEntriesPerChunk, maxBytesPerChunk, chunks::add);
        return chunks;
    }

    /**
     * Streaming variant of {@link #export(StateSource, int, int)}: emits each chunk to
     * {@code sink} as soon as it is complete, so at most one chunk is ever materialised
     * (audit: whole-snapshot RAM materialisation). Chunk order is identical to the list
     * form (canonical domain order), and a chunk's entries are owned by the chunk — the
     * sink may retain or hand them off without copying.
     */
    public static void export(StateSource source, int maxEntriesPerChunk, int maxBytesPerChunk,
                              java.util.function.Consumer<SnapshotChunk> sink) {
        if (maxEntriesPerChunk <= 0 || maxBytesPerChunk <= 0) {
            throw new IllegalArgumentException("chunk bounds must be positive");
        }
        long[] bufferedBytes = {0};
        for (byte domain : DOMAINS) {
            List<SnapshotChunk.Entry> buffer = new ArrayList<>();
            source.forEach(domain, (key, value) -> {
                buffer.add(new SnapshotChunk.Entry(key, value));
                bufferedBytes[0] += key.length + value.length;
                if (buffer.size() >= maxEntriesPerChunk || bufferedBytes[0] >= maxBytesPerChunk) {
                    sink.accept(new SnapshotChunk(domain, new ArrayList<>(buffer)));
                    buffer.clear();
                    bufferedBytes[0] = 0;
                }
            });
            if (!buffer.isEmpty()) {
                sink.accept(new SnapshotChunk(domain, buffer));
            }
            bufferedBytes[0] = 0;
        }
    }
}
