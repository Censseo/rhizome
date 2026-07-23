package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
