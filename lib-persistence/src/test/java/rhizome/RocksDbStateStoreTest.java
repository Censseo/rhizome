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
