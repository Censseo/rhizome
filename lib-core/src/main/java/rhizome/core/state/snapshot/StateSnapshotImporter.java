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
        for (SnapshotChunk chunk : chunks) {
            for (SnapshotChunk.Entry e : chunk.entries()) {
                root = tree.update(root, StateKeys.key(chunk.domain(), e.key()), StateKeys.valueHash(e.value()));
                entries++;
            }
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
