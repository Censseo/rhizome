package rhizome.persistence.rocksdb;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import rhizome.core.state.SparseMerkleTree;

/**
 * Concurrency regression tests for the async smt_nodes GC (audit follow-up): a node that is
 * unreachable garbage at snapshot time but re-created byte-identically WHILE the sweep runs
 * (a balance cycling back to a previous value does exactly this, nodes being content-addressed)
 * must survive the sweep — a delete-by-key applies to the current sequence, so without the
 * protection set it would erase the fresh copy.
 *
 * <p>Both tests drive the GC thread through the {@link RocksDbStateStore#gcAfterMarkHook} /
 * {@link RocksDbStateStore#gcBeforeDeleteFlushHook} seams (static, mutable), so they must never
 * run concurrently with each other — the {@link ResourceLock} guards that if parallel test
 * execution is ever enabled; JUnit's default sequential execution already serializes them.
 */
@ResourceLock("RocksDbStateStore.gcHooks")
class RocksDbStateStoreGcTest {

    private static byte[] key32(int i) {
        byte[] k = new byte[32];
        k[0] = (byte) (i & 0xFF);
        k[31] = (byte) (i & 0xFF);
        return k;
    }

    private static byte[] seedOrphanLeaf(RocksDbStateStore store) {
        // Garbage at snapshot time: a single-leaf tree whose root is never recorded, so no
        // retained root reaches its leaf node. A one-leaf tree's root IS the leaf node hash.
        return new SparseMerkleTree(store).update(SparseMerkleTree.EMPTY_ROOT, key32(200), key32(201));
    }

    private static byte[] seedLiveRoot(RocksDbStateStore store) {
        // A retained root above the prune floor, so the sweep's live set is non-empty
        // (an empty live set makes the sweep keep everything by design).
        SparseMerkleTree tree = new SparseMerkleTree(store);
        byte[] liveRoot = SparseMerkleTree.EMPTY_ROOT;
        for (int i = 1; i <= 5; i++) {
            liveRoot = tree.update(liveRoot, key32(i), key32(i));
        }
        store.putRoot(2048, liveRoot);
        return liveRoot;
    }

    private static void awaitSweep(RocksDbStateStore store) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (store.gcSweptThrough() < 1024 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    void nodeRePutDuringSweepSurvivesTheDeletes(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbStateStore(dir.toString())) {
            seedLiveRoot(store);
            byte[] orphanLeafHash = seedOrphanLeaf(store);
            assertNotNull(store.get(orphanLeafHash), "orphan node is on disk before the sweep");

            // Re-create the orphan byte-identically after the mark phase but before the deletes:
            // the hook runs on the GC thread, so the re-put deterministically precedes the delete
            // phase — without the protection-set filter the delete would erase the fresh copy.
            RocksDbStateStore.gcAfterMarkHook = () -> seedOrphanLeaf(store);
            try {
                store.pruneBelow(1024); // watermark 0 -> one full interval -> async sweep triggered
                awaitSweep(store);
            } finally {
                RocksDbStateStore.gcAfterMarkHook = null;
            }
            assertNotNull(store.get(orphanLeafHash),
                "a node re-put while the sweep runs must survive the sweep's delete-by-key");
        }
    }

    @Test
    void nodeRePutBetweenBatchBuildAndDeleteFlushIsRestored(@TempDir Path dir) throws Exception {
        try (var store = new RocksDbStateStore(dir.toString())) {
            seedLiveRoot(store);
            byte[] orphanLeafHash = seedOrphanLeaf(store);
            assertNotNull(store.get(orphanLeafHash), "orphan node is on disk before the sweep");

            // Re-create the orphan byte-identically in the filter→write window: the hook runs on
            // the GC thread after the delete batch was BUILT (the orphan passed the filter, being
            // not yet protected) but before db.write. The write then erases the fresh copy — only
            // the post-write repair pass can bring it back, from the snapshot.
            RocksDbStateStore.gcBeforeDeleteFlushHook = () -> seedOrphanLeaf(store);
            try {
                store.pruneBelow(1024);
                awaitSweep(store);
            } finally {
                RocksDbStateStore.gcBeforeDeleteFlushHook = null;
            }
            assertNotNull(store.get(orphanLeafHash),
                "a node re-put between the batch filter and the delete write must be restored by the repair pass");
        }
    }
}
