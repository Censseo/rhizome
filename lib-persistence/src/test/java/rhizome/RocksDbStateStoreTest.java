package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.state.SparseMerkleTree;
import rhizome.core.state.StateProof;
import rhizome.persistence.rocksdb.RocksDbStateStore;

class RocksDbStateStoreTest {

    private static byte[] key32(int i) {
        byte[] k = new byte[32];
        k[0] = (byte) i;
        k[31] = (byte) i;
        return k;
    }

    @Test
    void nodesAndRootsSurviveReopen(@TempDir Path dir) throws Exception {
        byte[] root;
        try (var store = new RocksDbStateStore(dir.toString())) {
            SparseMerkleTree tree = new SparseMerkleTree(store);
            root = SparseMerkleTree.EMPTY_ROOT;
            for (int i = 1; i <= 20; i++) {
                root = tree.update(root, key32(i), key32(i));
            }
            store.putRoot(5, root);
            assertEquals(5, store.latestHeight());
            assertArrayEquals(root, store.getRoot(5));
        }
        // Reopen: nodes and the recorded root are on disk, so proofs still verify.
        try (var store = new RocksDbStateStore(dir.toString())) {
            SparseMerkleTree tree = new SparseMerkleTree(store);
            byte[] persisted = store.getRoot(5);
            assertArrayEquals(root, persisted);
            StateProof proof = tree.prove(persisted, key32(7));
            assertTrue(SparseMerkleTree.verify(persisted, key32(7), key32(7), proof));
        }
    }

    @Test
    void batchedNodeWritesAreReadYourWritesFlushDurablyAndDropOnDiscard(@TempDir Path dir) throws Exception {
        byte[] hash = new byte[32];
        hash[0] = 0x42;
        byte[] node = {9, 8, 7};
        try (var store = new RocksDbStateStore(dir.toString())) {
            // Staged then discarded: visible within the batch (read-your-writes), never persisted (P8).
            store.beginBatch();
            store.put(hash, node);
            assertArrayEquals(node, store.get(hash));
            store.discardBatch();
            assertNull(store.get(hash));

            // Staged then flushed: one atomic write, then durably readable.
            store.beginBatch();
            store.put(hash, node);
            store.flushBatch();
            assertArrayEquals(node, store.get(hash));
        }
        try (var store = new RocksDbStateStore(dir.toString())) {
            assertArrayEquals(node, store.get(hash)); // survived the reopen
        }
    }

    @Test
    void batchedTreeBuildProducesTheSameRootAsUnbatched(@TempDir Path dir) throws Exception {
        // Determinism: buffering the block's SMT nodes must not change the root. The batched build also
        // exercises read-your-writes — each of the 30 updates reads back the new root node the previous
        // update just staged in the overlay (P8).
        try (var store = new RocksDbStateStore(dir.toString())) {
            SparseMerkleTree tree = new SparseMerkleTree(store);
            byte[] plain = SparseMerkleTree.EMPTY_ROOT;
            for (int i = 1; i <= 30; i++) {
                plain = tree.update(plain, key32(i), key32(i));
            }
            store.beginBatch();
            byte[] batched = SparseMerkleTree.EMPTY_ROOT;
            for (int i = 1; i <= 30; i++) {
                batched = tree.update(batched, key32(i), key32(i));
            }
            store.flushBatch();
            assertArrayEquals(plain, batched, "batching must not change the root");
        }
    }

    @Test
    void rootStorePrunesAndReportsLatest(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbStateStore(dir.toString())) {
            for (long h = 1; h <= 10; h++) {
                store.putRoot(h, key32((int) h));
            }
            assertEquals(10, store.latestHeight());
            store.deleteRoot(10);
            assertEquals(9, store.latestHeight());
            store.pruneBelow(5);
            assertNull(store.getRoot(3));
            assertArrayEquals(key32(5), store.getRoot(5));
        }
    }

    @Test
    void rootsAndPruneSurviveReopen(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbStateStore(dir.toString())) {
            for (long h = 1; h <= 10; h++) {
                store.putRoot(h, key32((int) h));
            }
            store.deleteRoot(10);
            store.pruneBelow(5);
        }
        // The root store is on disk, not in memory: the deleted and pruned rows stay gone.
        try (var store = new RocksDbStateStore(dir.toString())) {
            assertNull(store.getRoot(10), "the deleted root must not resurrect");
            assertNull(store.getRoot(3), "the pruned root must not resurrect");
            assertArrayEquals(key32(5), store.getRoot(5));
            assertArrayEquals(key32(9), store.getRoot(9));
        }
    }

    @Test
    void beginBatchRefusesASecondOpenBatch(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbStateStore(dir.toString())) {
            store.beginBatch();
            // Opening a second batch would silently drop the first batch's staged nodes (audit F8).
            assertThrows(IllegalStateException.class, store::beginBatch);
            store.discardBatch();
            store.beginBatch(); // after a discard, a new batch opens cleanly
            store.flushBatch();
        }
    }

    @Test
    void asyncSweepDeletesUnreachableNodesAndKeepsRetainedRootsResolvable(@TempDir Path dir)
            throws Exception {
        try (var store = new RocksDbStateStore(dir.toString())) {
            SparseMerkleTree tree = new SparseMerkleTree(store);
            byte[] liveRoot = SparseMerkleTree.EMPTY_ROOT;
            for (int i = 1; i <= 10; i++) {
                liveRoot = tree.update(liveRoot, key32(i), key32(i));
            }
            // Retained ABOVE the prune floor, so the sweep's mark phase keeps its nodes.
            store.putRoot(2048, liveRoot);
            // Garbage: a whole tree whose root is never recorded — unreachable from any root.
            byte[] orphanRoot = SparseMerkleTree.EMPTY_ROOT;
            for (int i = 101; i <= 110; i++) {
                orphanRoot = tree.update(orphanRoot, key32(i), key32(i));
            }
            assertNotNull(store.get(orphanRoot), "orphan nodes are on disk before the sweep");

            store.pruneBelow(1024); // roots only — the GC trigger is explicit
            store.gcNodesIfDue(1024); // watermark 0 -> one full interval -> async sweep triggered

            // The sweep runs OFF the calling thread (consensus lock must never wait for it),
            // so poll for the garbage to disappear instead of asserting synchronously.
            long deadline = System.currentTimeMillis() + 30_000;
            while (store.get(orphanRoot) != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertNull(store.get(orphanRoot), "sweep must delete nodes no retained root reaches");
            StateProof proof = tree.prove(liveRoot, key32(3));
            assertTrue(SparseMerkleTree.verify(liveRoot, key32(3), key32(3), proof),
                "the retained root's nodes survive the sweep");
        }
    }

    @Test
    void asyncSweepSkipsACorruptNodeInsteadOfFailingForever(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbStateStore(dir.toString())) {
            SparseMerkleTree tree = new SparseMerkleTree(store);
            byte[] liveRoot = SparseMerkleTree.EMPTY_ROOT;
            for (int i = 1; i <= 10; i++) {
                liveRoot = tree.update(liveRoot, key32(i), key32(i));
            }
            store.putRoot(2048, liveRoot);
            // Corrupt the retained root's on-disk bytes: a valid-length node with a tag that is
            // neither LEAF nor INNER (disk rot). The mark phase reaches it first.
            byte[] corrupt = new byte[SparseMerkleTree.NODE_BYTES];
            corrupt[0] = 0x7F;
            store.put(liveRoot, corrupt);
            // Garbage the sweep must still collect: reachable from no recorded root.
            byte[] orphanRoot = SparseMerkleTree.EMPTY_ROOT;
            for (int i = 101; i <= 110; i++) {
                orphanRoot = tree.update(orphanRoot, key32(i), key32(i));
            }
            assertNotNull(store.get(orphanRoot), "orphan nodes are on disk before the sweep");

            store.pruneBelow(1024);
            store.gcNodesIfDue(1024);

            // A sweep that THREW on the corrupt tag would die with its watermark frozen and retry
            // the same node at every interval, forever — the orphan below would never be deleted.
            // Skipping the corrupt node (nothing to recurse into) lets the sweep complete.
            long deadline = System.currentTimeMillis() + 30_000;
            while (store.get(orphanRoot) != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertNull(store.get(orphanRoot),
                "a corrupt reachable node must not stop the sweep from collecting unreachable ones");
        }
    }
}
