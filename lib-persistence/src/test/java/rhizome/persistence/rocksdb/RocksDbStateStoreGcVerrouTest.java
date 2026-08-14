package rhizome.persistence.rocksdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.state.SparseMerkleTree;

/**
 * L30 verrou: the SMT node GC must never delete a node a RETAINED root reaches. Nodes are
 * content-addressed and shared across consecutive roots, so an over-delete corrupts every
 * root whose subtree contains the node — the exact failure a per-root diff or a transposed
 * node-format reader would produce. The property: a tree committed above the prune floor,
 * swept, then REBUILT from the surviving nodes must yield the identical root. Written before
 * the refactor that moves the node format out of this adapter and makes the GC trigger
 * explicit — it stays green byte-for-byte afterwards.
 */
class RocksDbStateStoreGcVerrouTest {

    private static byte[] key32(int i) {
        byte[] k = new byte[32];
        k[0] = (byte) (i & 0xFF);
        k[31] = (byte) (i & 0xFF);
        return k;
    }

    private static void awaitSweep(RocksDbStateStore store) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (store.gcSweptThrough() < 1024 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    void aRetainedRootsSubtreeSurvivesTheSweepIntact(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbStateStore(dir.toString())) {
            // A tree whose root is recorded ABOVE the prune floor (retained): 50 keys so the
            // root spans many shared inner nodes — a GC that over-deletes any of them would
            // change the rebuilt root (or fail on a missing node).
            SparseMerkleTree tree = new SparseMerkleTree(store);
            byte[] root = SparseMerkleTree.EMPTY_ROOT;
            for (int i = 1; i <= 50; i++) {
                root = tree.update(root, key32(i), key32(i));
            }
            store.putRoot(2048, root);
            byte[] preGcRoot = root.clone();

            // Garbage below the retained set: a single-leaf tree whose root is never recorded,
            // so the sweep has something to delete past the live set.
            byte[] orphanLeafHash = new SparseMerkleTree(store)
                .update(SparseMerkleTree.EMPTY_ROOT, key32(200), key32(201));
            assertNotNull(store.get(orphanLeafHash), "orphan node is on disk before the sweep");

            store.pruneBelow(1024); // roots only — the GC trigger is explicit
            store.gcNodesIfDue(1024); // watermark 0 -> one full interval -> async sweep triggered
            awaitSweep(store);
            assertNull(store.get(orphanLeafHash), "the orphan leaf is deleted as garbage");

            // Rebuild the retained tree FROM THE SURVIVING NODES: every node the root reaches
            // must still be present, so re-deriving the same keys yields the identical root.
            SparseMerkleTree rebuilt = new SparseMerkleTree(store);
            byte[] rebuiltRoot = SparseMerkleTree.EMPTY_ROOT;
            for (int i = 1; i <= 50; i++) {
                rebuiltRoot = rebuilt.update(rebuiltRoot, key32(i), key32(i));
            }
            assertArrayEquals(preGcRoot, rebuiltRoot,
                "the root rebuilt after the GC must equal the pre-GC root — a deleted reachable "
                + "node would change it");
        }
    }
}
