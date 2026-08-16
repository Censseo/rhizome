package rhizome.core.state.snapshot;

import java.util.Arrays;
import java.util.List;

import rhizome.core.state.SmtNodeStore;
import rhizome.core.state.SparseMerkleTree;
import rhizome.core.state.StateKeys;

/**
 * Rebuilds the authenticated state from snapshot chunks and verifies it against a committed
 * root <em>before</em> anything is seeded — the trust-minimised half of snap-sync. The
 * expected root comes from a block header the caller has already validated under the chain's
 * full proof-of-work, so a snapshot that matches it is as trustworthy as the chain itself;
 * any tampered, dropped or duplicated entry changes the reconstructed root and the whole
 * import is refused with the stores untouched.
 */
public final class StateSnapshotImporter {

    /** A snapshot failed verification: the rebuilt root does not match the committed one. */
    public static final class SnapshotVerificationException extends RuntimeException {
        public SnapshotVerificationException(String message) {
            super(message);
        }
    }

    /**
     * Entries rebuilt per batch flush during {@link #verify}. Each entry writes ~path-length
     * new SMT nodes, and a durable store syncs one batch per flush — unbatched, the rebuild
     * paid one fsync PER NODE and snap-sync was effectively unusable; unbounded, the staging
     * overlay held the whole rebuilt tree on the heap. 10k entries bounds the overlay to a
     * few tens of MB and the cost to one fsync per window. In-memory stores see no-ops.
     */
    private static final int IMPORT_BATCH_ENTRIES = 10_000;

    private StateSnapshotImporter() {}

    /**
     * Rebuilds the sparse-Merkle tree from {@code chunks} (any order) into {@code nodes} and
     * returns the resulting root, which must equal {@code expectedRoot}. Nothing else is
     * touched; on mismatch the only residue is unreferenced content-addressed tree nodes.
     */
    public static byte[] verify(List<SnapshotChunk> chunks, SmtNodeStore nodes, byte[] expectedRoot) {
        SparseMerkleTree tree = new SparseMerkleTree(nodes);
        byte[] root = SparseMerkleTree.EMPTY_ROOT;
        long entries = 0;
        int sinceFlush = 0;
        nodes.beginBatch();
        try {
            for (SnapshotChunk chunk : chunks) {
                for (SnapshotChunk.Entry e : chunk.entries()) {
                    root = tree.update(root, StateKeys.key(chunk.domain(), e.key()), StateKeys.valueHash(e.value()));
                    entries++;
                    if (++sinceFlush >= IMPORT_BATCH_ENTRIES) {
                        nodes.flushBatch();
                        nodes.beginBatch();
                        sinceFlush = 0;
                    }
                }
            }
            nodes.flushBatch();
        } catch (RuntimeException e) {
            // Drop the unflushed tail: the residue rule above holds on failure too — flushed
            // windows are unreferenced content-addressed nodes, re-derived by the next attempt.
            nodes.discardBatch();
            throw e;
        }
        if (!Arrays.equals(root, expectedRoot)) {
            throw new SnapshotVerificationException(
                "snapshot root mismatch after " + entries + " entries: rebuilt "
                    + rhizome.core.common.Utils.bytesToHex(root) + ", header commits "
                    + rhizome.core.common.Utils.bytesToHex(expectedRoot));
        }
        return root;
    }

    /**
     * Verifies {@code chunks} against {@code expectedRoot} (see {@link #verify}), then — and
     * only then — replays every binding into {@code sink} so the caller seeds its stores.
     * Returns the verified root.
     */
    public static byte[] importVerified(List<SnapshotChunk> chunks, SmtNodeStore nodes,
                                        byte[] expectedRoot, StateSink sink) {
        byte[] root = verify(chunks, nodes, expectedRoot);
        for (SnapshotChunk chunk : chunks) {
            for (SnapshotChunk.Entry e : chunk.entries()) {
                sink.put(chunk.domain(), e.key(), e.value());
            }
        }
        return root;
    }
}
