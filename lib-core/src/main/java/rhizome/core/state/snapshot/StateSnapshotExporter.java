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
     */
    public static List<SnapshotChunk> export(StateSource source, int maxEntriesPerChunk, int maxBytesPerChunk) {
        if (maxEntriesPerChunk <= 0 || maxBytesPerChunk <= 0) {
            throw new IllegalArgumentException("chunk bounds must be positive");
        }
        List<SnapshotChunk> chunks = new ArrayList<>();
        long[] bufferedBytes = {0};
        for (byte domain : DOMAINS) {
            List<SnapshotChunk.Entry> buffer = new ArrayList<>();
            source.forEach(domain, (key, value) -> {
                buffer.add(new SnapshotChunk.Entry(key, value));
                bufferedBytes[0] += key.length + value.length;
                if (buffer.size() >= maxEntriesPerChunk || bufferedBytes[0] >= maxBytesPerChunk) {
                    chunks.add(new SnapshotChunk(domain, new ArrayList<>(buffer)));
                    buffer.clear();
                    bufferedBytes[0] = 0;
                }
            });
            if (!buffer.isEmpty()) {
                chunks.add(new SnapshotChunk(domain, buffer));
            }
            bufferedBytes[0] = 0;
        }
        return chunks;
    }
}
