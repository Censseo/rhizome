package rhizome.persistence.rocksdb;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.state.RootStore;
import rhizome.core.state.SmtNodeStore;

import static rhizome.core.common.Utils.longToBytes;

/**
 * RocksDB backing for the authenticated state: the content-addressed sparse-Merkle nodes
 * ({@link SmtNodeStore}) and the per-height state roots ({@link RootStore}). Nodes are
 * immutable and keyed by their hash, so old roots stay resolvable for reorg reversal;
 * roots are 32 bytes per height, keyed by big-endian height for ordered iteration.
 */
public final class RocksDbStateStore extends RocksDbStore implements SmtNodeStore, RootStore {

    private static final Logger log = LoggerFactory.getLogger(RocksDbStateStore.class);

    private static final byte[] CF_NODES = "smt_nodes".getBytes();
    private static final byte[] CF_ROOTS = "state_roots".getBytes();

    private final ColumnFamilyHandle nodesCf;
    private final ColumnFamilyHandle rootsCf;

    /**
     * SMT nodes staged for the block being applied, keyed by hash hex (audit P8). Applying a block
     * updates the tree once per touched key, each creating ~depth new content-addressed nodes that the
     * next update reads back, so a plain per-node {@code db.put} is hundreds of point writes a block.
     * Buffering them here and flushing one {@link org.rocksdb.WriteBatch} cuts that to a single write;
     * reads consult the overlay first (read-your-writes) so an update sees the nodes an earlier update
     * in the same block just wrote. Null outside a batch, so proofs and snapshot import write through
     * unchanged. Node bytes are content-addressed and immutable, so this only changes write timing.
     *
     * <p>Keyed by a value-equality {@link ByteKey} rather than a hex String: the tree does hundreds of
     * node put/read-back pairs per block, so hex-encoding every 32-byte hash to a 64-char String (and
     * decoding it back on flush) was thousands of avoidable String allocations per block on the
     * consensus-critical path.
     */
    private volatile java.util.concurrent.ConcurrentHashMap<ByteKey, byte[]> pendingNodes;

    /** Immutable byte[] wrapper with value-based equals/hashCode, for use as a hash-map key. */
    private record ByteKey(byte[] bytes) {
        @Override public boolean equals(Object o) {
            return o instanceof ByteKey k && java.util.Arrays.equals(bytes, k.bytes);
        }
        @Override public int hashCode() {
            return java.util.Arrays.hashCode(bytes);
        }
    }

    public RocksDbStateStore(String path) throws java.io.IOException {
        super(path, "state store", CF_NODES, CF_ROOTS);
        this.nodesCf = handles.get(1);
        this.rootsCf = handles.get(2);
        // Cache the GC watermark so the per-block pruneBelow never reads RocksDB on the
        // consensus path; the sweep thread refreshes the field after each persist.
        byte[] watermark = raw(defaultCf, GC_SWEPT_THROUGH_KEY);
        this.gcSweptThrough = watermark == null ? 0 : rhizome.core.common.Utils.bytesToLong(watermark);
    }

    // ---- SmtNodeStore ----

    @Override
    public byte[] get(byte[] hash) {
        var pending = pendingNodes;
        if (pending != null) {
            byte[] staged = pending.get(new ByteKey(hash));
            if (staged != null) {
                return staged; // read-your-writes: a node an earlier update in this block just wrote
            }
        }
        return raw(nodesCf, hash);
    }

    @Override
    public void put(byte[] hash, byte[] node) {
        var pending = pendingNodes;
        if (pending != null) {
            pending.put(new ByteKey(hash), node);
            return;
        }
        // Record BEFORE the write lands: a node re-put while a GC sweep runs must survive
        // the sweep's delete-by-key (see gcProtected). Recording after the write would leave
        // a window where the delete chunk flushes first.
        var protectedWrites = gcProtected;
        if (protectedWrites != null) {
            protectedWrites.add(new ByteKey(hash));
        }
        putBulk(nodesCf, hash, node); // unsynced, see bulkWriteOptions
    }

    @Override
    public void beginBatch() {
        if (pendingNodes != null) {
            throw new IllegalStateException("a batch is already open"); // audit F8
        }
        pendingNodes = new java.util.concurrent.ConcurrentHashMap<>();
    }

    @Override
    public void flushBatch() {
        var pending = pendingNodes;
        if (pending == null || pending.isEmpty()) {
            pendingNodes = null;
            return;
        }
        try {
            // Record the staged hashes BEFORE the write lands: a block applied while a GC
            // sweep runs re-creates nodes the sweep's snapshot may show as unreachable
            // garbage; its deletes must spare these (see gcProtected).
            var protectedWrites = gcProtected;
            if (protectedWrites != null) {
                protectedWrites.addAll(pending.keySet());
            }
            try (org.rocksdb.WriteBatch batch = new org.rocksdb.WriteBatch()) {
                for (var e : pending.entrySet()) {
                    batch.put(nodesCf, e.getKey().bytes(), e.getValue());
                }
                db.write(writeOptions, batch);
            }
        } catch (RocksDBException e) {
            throw new IllegalStateException("state node batch write failed", e);
        }
        // Clear the overlay only AFTER the write succeeded: nulling it first let concurrent readers
        // observe the nodes as missing while they were not yet durable, and on failure the caller
        // can retry the flush with the overlay still intact (audit F8).
        pendingNodes = null;
    }

    @Override
    public void discardBatch() {
        pendingNodes = null; // dry-run nodes are content-addressed and re-derived on a real apply
    }

    // ---- RootStore ----

    @Override
    public byte[] getRoot(long height) {
        return raw(rootsCf, longToBytes(height));
    }

    @Override
    public void putRoot(long height, byte[] root) {
        put(rootsCf, longToBytes(height), root);
    }

    @Override
    public void deleteRoot(long height) {
        delete(rootsCf, longToBytes(height));
    }

    @Override
    public long latestHeight() {
        try (RocksIterator it = db.newIterator(rootsCf)) {
            it.seekToLast();
            return it.isValid() ? rhizome.core.common.Utils.bytesToLong(it.key()) : -1;
        }
    }

    @Override
    public void pruneBelow(long minHeight) {
        try {
            // Synced, consistent with every other write/delete in this store (audit: prune durability).
            db.deleteRange(rootsCf, writeOptions, longToBytes(0), longToBytes(minHeight));
        } catch (RocksDBException e) {
            throw new IllegalStateException("state root prune failed", e);
        }
        sweepNodesIfDue(minHeight);
    }

    // ---- smt_nodes garbage collection ----

    /**
     * Nodes are content-addressed and immutable, so every committed block strands the nodes its
     * state changes replaced; without collection {@code smt_nodes} grows forever (audit: SMT node
     * GC). The same node is shared by every root whose subtree contains it (consecutive roots
     * differ only along the changed paths), so a node may be deleted only when NO retained root
     * reaches it — a per-root diff would delete shared nodes still needed for reorg reversal.
     * Hence mark-and-sweep: walk every retained root collecting reachable hashes (already-collected
     * subtrees short-circuit, so shared structure is visited once), then delete the unmarked in
     * synced chunks. A full mark is O(live nodes) — too expensive per block — so it runs every
     * {@link #GC_SWEEP_INTERVAL} pruned heights (watermark persisted); garbage between sweeps is
     * bounded by the interval.
     *
     * <p>The sweep runs OFF the consensus lock, on {@link #gcExecutor}: {@code pruneBelow} is
     * invoked from block application with the engine lock held, and a synchronous sweep paused
     * all block acceptance for minutes on a large state — at a deterministic cadence, i.e.
     * externally observable (audit follow-up). Safety without the lock rests on TWO mechanisms:
     *
     * <ol>
     *   <li>A RocksDB {@link Snapshot} taken AT TRIGGER TIME, inside {@code sweepNodesIfDue} —
     *   still under the consensus lock, after the block's {@code flushBatch} and {@code putRoot}
     *   (see StateAccumulator.applyBlock). Every node written before the snapshot is either
     *   reachable from a root the snapshot marks, or genuine garbage. Taking the snapshot later,
     *   on the GC thread, could land between a block's flushBatch and its putRoot: the new nodes
     *   would be visible in the snapshot but unreachable from any recorded root → wrongly
     *   deleted under the root that is about to reference them.</li>
     *   <li>A protection set ({@link #gcProtected}) recording every node hash written while the
     *   sweep runs. Nodes are content-addressed: a node that is garbage at snapshot time is
     *   re-created byte-identically as soon as its content returns (a balance cycling back to a
     *   previous value, a subtree regaining a former shape), and a delete-by-key applies to the
     *   CURRENT sequence, not the snapshot's — so it would erase that fresh copy. The set guards
     *   each delete chunk TWICE: at batch construction (filter), and again AFTER the delete write
     *   has landed — a delete only takes effect at {@code db.write}, so a re-put slipping between
     *   the filter and the write is erased by it; the post-write pass restores any such key from
     *   the snapshot (the snapshot bytes are by construction identical to the re-put ones). Any
     *   re-put landing after the post-write check survives on its own, its put being ordered
     *   after the delete. The repair therefore closes the filter→write window without any lock
     *   on either side; its cost is one set lookup per deleted key, and it is bounded by one
     *   chunk's deletes.</li>
     * </ol>
     *
     * <p>The mark phase materialises the live set in RAM on the GC thread (~tens of bytes per
     * live node) — a spike, but one that no longer stalls consensus. The trigger-time snapshot
     * is held for the whole mark+sweep: RocksDB retains every superseded version until it is
     * released, which shows as extra DISK usage (pinned SST files), not RSS — the assumed cost
     * of a consistent off-lock view. The watermark is persisted
     * only after the deletes land, so a crash mid-sweep re-sweeps (the sweep is idempotent).
     * Snapshot import (the lock-free straight-through put path) runs at bootstrap, before sync,
     * so it never straddles a trigger; any put that runs DURING a sweep records into the
     * protection set regardless of which thread it is on.
     */
    private static final byte[] GC_SWEPT_THROUGH_KEY = "nodesGcSweptThrough".getBytes();
    private static final long GC_SWEEP_INTERVAL = 1024;
    private static final int GC_DELETE_CHUNK = 4096;
    /** SparseMerkleTree node encoding: type(1) ‖ word(32) ‖ word(32); LEAF = 0x00 (words are key/valueHash). */
    private static final int SMT_NODE_BYTES = 65;
    private static final byte SMT_LEAF = 0x00;

    /** Single daemon thread: sweeps are serialized by {@link #gcRunning} and never block callers. */
    private final ExecutorService gcExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "state-store-gc");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean gcRunning = new AtomicBoolean();
    /** Hashes written while a sweep runs, excluded from its deletes (see the GC javadoc). Null outside a sweep. */
    private volatile java.util.Set<ByteKey> gcProtected;
    /** Set by close(): the sweep aborts at its next loop bound instead of racing handle teardown. */
    private volatile boolean closing;
    /** Last persisted GC watermark, cached at open and refreshed by the sweep thread after each persist. */
    private volatile long gcSweptThrough;
    /** Test seam: run on the GC thread after the mark phase, before any delete. Null in production. */
    static volatile Runnable gcAfterMarkHook;
    /** Test seam: run on the GC thread inside deleteChunk, batch built but not yet written. Null in production. */
    static volatile Runnable gcBeforeDeleteFlushHook;
    /**
     * The trigger-time snapshot of the in-flight (or merely queued) sweep. Released by the GC
     * task's finally — or by close() if shutdownNow() discarded the task before it ever ran
     * (its finally never executes in that case). getAndSet(null) on both sides makes a double
     * release impossible.
     */
    private final java.util.concurrent.atomic.AtomicReference<Snapshot> gcSnapshotRef =
        new java.util.concurrent.atomic.AtomicReference<>();

    long gcSweptThrough() { // package-private, for tests
        return gcSweptThrough;
    }

    private void sweepNodesIfDue(long minHeight) {
        if (minHeight - gcSweptThrough < GC_SWEEP_INTERVAL) {
            return;
        }
        if (!gcRunning.compareAndSet(false, true)) {
            return; // a previous sweep is still running — garbage accumulates one more interval
        }
        final long floor = minHeight;
        // Install the protection set, then take the snapshot HERE, at trigger time (under the
        // consensus lock, after this block's flushBatch and putRoot) — see the GC javadoc for
        // why a GC-thread snapshot and unprotected re-puts both delete live nodes.
        gcProtected = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Snapshot snapshot;
        try {
            snapshot = db.getSnapshot();
            gcSnapshotRef.set(snapshot);
        } catch (RuntimeException e) {
            gcProtected = null;
            gcRunning.set(false);
            throw e;
        }
        try {
            gcExecutor.execute(() -> {
                try {
                    sweepNodes(floor, snapshot);
                } catch (Throwable t) {
                    // Never propagate: the watermark simply stays put and the sweep retries one
                    // interval later. This MUST be logged loudly — a persistently failing GC
                    // grows smt_nodes forever.
                    log.error("state node sweep failed; will retry after the next interval", t);
                } finally {
                    gcProtected = null; // stop recording; late adds land in the dropped set, harmless
                    Snapshot s = gcSnapshotRef.getAndSet(null);
                    if (s != null) {
                        db.releaseSnapshot(s);
                    }
                    gcRunning.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // close() raced the trigger: the store is going away, there is nothing to sweep.
            gcProtected = null;
            Snapshot s = gcSnapshotRef.getAndSet(null);
            if (s != null) {
                db.releaseSnapshot(s);
            }
            gcRunning.set(false);
        }
    }

    private void sweepNodes(long floor, Snapshot snapshot) {
        try (ReadOptions readOptions = new ReadOptions().setSnapshot(snapshot)) {
            // Mark: the union of nodes reachable from ALL retained roots is the live set — anything
            // else on disk is unreachable garbage (orphaned by replaced/pruned roots).
            java.util.Set<ByteKey> live = new java.util.HashSet<>();
            try (RocksIterator it = db.newIterator(rootsCf, readOptions)) {
                for (it.seekToFirst(); it.isValid() && !closing; it.next()) {
                    collectReachable(it.value(), live, readOptions);
                }
            }
            if (closing) {
                return; // close() is tearing the store down: abort quietly, the watermark stays put
            }
            Runnable hook = gcAfterMarkHook; // test seam, null in production
            if (hook != null) {
                hook.run();
            }
            if (live.isEmpty()) {
                // No retained root reaches anything: keep every node rather than risk an
                // over-delete. The watermark still advances — otherwise every later pruneBelow
                // would re-trigger this whole scan for nothing.
                persistGcWatermark(floor);
                return;
            }
            List<byte[]> chunk = new ArrayList<>(GC_DELETE_CHUNK);
            try (RocksIterator it = db.newIterator(nodesCf, readOptions)) {
                for (it.seekToFirst(); it.isValid() && !closing; it.next()) {
                    if (live.contains(new ByteKey(it.key()))) {
                        continue;
                    }
                    chunk.add(it.key());
                    if (chunk.size() >= GC_DELETE_CHUNK) {
                        deleteChunk(chunk, readOptions);
                        chunk.clear();
                    }
                }
            }
            if (closing) {
                return;
            }
            deleteChunk(chunk, readOptions);
            // Persist the watermark only after the sweep landed, so a crash mid-sweep re-sweeps
            // (the sweep is idempotent: it deletes only currently-unreachable nodes).
            persistGcWatermark(floor);
        } catch (RocksDBException e) {
            throw new IllegalStateException("state node sweep failed", e);
        }
    }

    /**
     * Deletes one chunk of garbage keys (synced, like every delete in this store) against the
     * protection set, in two passes:
     *
     * <ol>
     *   <li>FILTER at batch construction — a key already re-put is not even queued.</li>
     *   <li>REPAIR after the delete write — the filter runs at batch CONSTRUCTION, but a delete
     *   only takes effect at {@code db.write} (the linearization point). A content-identical
     *   re-put landing between the two is erased by the write, so the deleted keys are
     *   re-checked once the write has landed and any raced key is restored FROM THE SNAPSHOT:
     *   nodes are content-addressed, so the snapshot bytes are by construction identical to
     *   the ones the writer re-put. A re-put landing after this second check survives on its
     *   own (its put is ordered after the delete), which closes the window entirely — the
     *   post-write check is the new linearization point.</li>
     * </ol>
     */
    private void deleteChunk(List<byte[]> chunk, ReadOptions readOptions) throws RocksDBException {
        if (chunk.isEmpty()) {
            return;
        }
        var protectedWrites = gcProtected;
        List<byte[]> deleted = new ArrayList<>(chunk.size());
        try (org.rocksdb.WriteBatch batch = new org.rocksdb.WriteBatch()) {
            for (byte[] key : chunk) {
                if (protectedWrites != null && protectedWrites.contains(new ByteKey(key))) {
                    continue;
                }
                batch.delete(nodesCf, key);
                deleted.add(key);
            }
            if (deleted.isEmpty()) {
                return;
            }
            Runnable hook = gcBeforeDeleteFlushHook; // test seam, null in production
            if (hook != null) {
                hook.run();
            }
            db.write(writeOptions, batch);
        }
        List<byte[]> restore = null;
        for (byte[] key : deleted) {
            if (protectedWrites != null && protectedWrites.contains(new ByteKey(key))) {
                if (restore == null) {
                    restore = new ArrayList<>();
                }
                restore.add(key);
            }
        }
        if (restore == null) {
            return;
        }
        try (org.rocksdb.WriteBatch batch = new org.rocksdb.WriteBatch()) {
            int restored = 0;
            for (byte[] key : restore) {
                byte[] node = db.get(nodesCf, readOptions, key); // snapshot view: trigger-time bytes
                if (node != null) { // the iterator saw it, so the snapshot must have it — be defensive
                    batch.put(nodesCf, key, node);
                    restored++;
                }
            }
            if (restored > 0) {
                db.write(writeOptions, batch);
            }
        }
    }

    private void persistGcWatermark(long floor) throws RocksDBException {
        put(defaultCf, GC_SWEPT_THROUGH_KEY, longToBytes(floor));
        gcSweptThrough = floor;
    }

    /**
     * Adds every node hash reachable from {@code root} to {@code live} (iterative, subtree-deduped).
     * Reads go through the sweep's snapshot: the GC must not consult the live CF (or an open
     * block-commit overlay), only the consistent trigger-time view it deletes from.
     */
    private void collectReachable(byte[] root, java.util.Set<ByteKey> live, ReadOptions readOptions)
            throws RocksDBException {
        if (root == null || root.length != 32 || isAllZero(root)) {
            return; // no root recorded, or the empty tree (32 zero bytes — never a stored node)
        }
        java.util.ArrayDeque<byte[]> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && !closing) {
            byte[] hash = stack.pop();
            if (!live.add(new ByteKey(hash))) {
                continue; // subtree already collected: consecutive roots share nearly all their nodes
            }
            byte[] node = db.get(nodesCf, readOptions, hash);
            if (node == null || node.length != SMT_NODE_BYTES) {
                continue; // missing/corrupt node: a GC must not fail the prune; nothing to recurse into
            }
            if (node[0] == SMT_LEAF) {
                continue; // a leaf's words are key/valueHash, not child node hashes
            }
            byte[] left = java.util.Arrays.copyOfRange(node, 1, 33);
            byte[] right = java.util.Arrays.copyOfRange(node, 33, SMT_NODE_BYTES);
            if (!isAllZero(left)) {
                stack.push(left);
            }
            if (!isAllZero(right)) {
                stack.push(right);
            }
        }
    }

    private static boolean isAllZero(byte[] hash) {
        for (byte b : hash) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        // Stop an in-flight GC sweep BEFORE touching the handles: rocksdbjni's isOwningHandle
        // guards are plain asserts (inert without -ea), so a sweep JNI call on a closed handle
        // dereferences freed native memory — SIGSEGV, not a catchable Java exception. The
        // closing flag makes the sweep abort at its next loop bound; shutdownNow additionally
        // interrupts any park, so the drain below should take well under the 60s budget.
        closing = true;
        List<Runnable> droppedSweeps = gcExecutor.shutdownNow();
        boolean drained;
        try {
            drained = gcExecutor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            drained = false;
        }
        if (!drained) {
            // A sweep is still inside RocksDB. Closing the handles under it risks crashing the
            // JVM; leaking them to process exit is strictly safer. The GC thread is a daemon,
            // so it cannot outlive the process. Its snapshot is likewise left to process exit.
            log.warn("state node GC did not stop within 60s of close(); "
                + "leaving the store handles to process exit rather than crashing under it");
            return;
        }
        if (!droppedSweeps.isEmpty()) {
            // shutdownNow() discarded a submitted-but-never-started sweep: its finally never
            // ran, so the trigger-time snapshot it captured was never released — release it
            // before closing the DB under it.
            Snapshot s = gcSnapshotRef.getAndSet(null);
            if (s != null) {
                db.releaseSnapshot(s);
            }
        }
        // Best-effort fsync of any bulk-seeded nodes not yet covered by a synced write. A
        // failure here must NOT abort close(): leaking native CF/DB handles on the shutdown
        // path is worse than a best-effort fsync lost on a store that is about to be closed.
        try {
            syncWal();
        } catch (RuntimeException e) {
            log.warn("state store WAL sync on close failed; closing anyway", e);
        }
        super.close();
    }
}
