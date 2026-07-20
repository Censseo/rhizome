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

    private StateSnapshotExporter() {}

    /** Dumps every domain of {@code source} into chunks of at most {@code maxEntriesPerChunk}. */
    public static List<SnapshotChunk> export(StateSource source, int maxEntriesPerChunk) {
        if (maxEntriesPerChunk <= 0) {
            throw new IllegalArgumentException("maxEntriesPerChunk must be positive");
        }
        List<SnapshotChunk> chunks = new ArrayList<>();
        for (byte domain : DOMAINS) {
            List<SnapshotChunk.Entry> buffer = new ArrayList<>();
            source.forEach(domain, (key, value) -> {
                buffer.add(new SnapshotChunk.Entry(key, value));
                if (buffer.size() >= maxEntriesPerChunk) {
                    chunks.add(new SnapshotChunk(domain, new ArrayList<>(buffer)));
                    buffer.clear();
                }
            });
            if (!buffer.isEmpty()) {
                chunks.add(new SnapshotChunk(domain, buffer));
            }
        }
        return chunks;
    }
}
