package rhizome.core.state;

import java.util.List;

/**
 * Maintains the authenticated state root: a {@link SparseMerkleTree} over the committed
 * ledger, box and token state, plus the root at each block height for reorg reversal. The
 * engine feeds it each block's state changes; the resulting 32-byte root is committed in
 * the block header, so a light client can prove any single state entry against it.
 *
 * <p>Rollback is cheap and journal-free: SMT nodes are content-addressed and immutable, so
 * an old root stays resolvable; reverting a block just moves the current root back to the
 * previous height's (kept in the {@link RootStore}).
 */
public final class StateAccumulator {

    private final SparseMerkleTree tree;
    private final SmtNodeStore nodes;
    private final RootStore roots;
    private final int retainDepth;
    private volatile byte[] currentRoot;

    /**
     * Amortized cadence of the durable root prune ({@link RootStore#pruneBelow}) — see
     * {@link PruneCadence}: pruning every block paid a synced range tombstone per block;
     * pruning every PRUNE_INTERVAL blocks keeps up to {@code retainDepth + PRUNE_INTERVAL}
     * roots instead — the safe direction, since the retained window must cover the max reorg
     * depth (audit perf). The SMT node GC rides the same calls, triggered EXPLICITLY
     * (constat 41): {@code pruneBelow} only prunes roots, {@code gcNodesIfDue} is the
     * named maintenance call, so a caller cannot collect nodes by accident.
     */
    private final PruneCadence rootPrune = new PruneCadence();

    public StateAccumulator(SmtNodeStore nodes, RootStore roots, int retainDepth) {
        this.tree = new SparseMerkleTree(nodes);
        this.nodes = nodes;
        this.roots = roots;
        this.retainDepth = Math.max(1, retainDepth);
        long latest = roots.latestHeight();
        this.currentRoot = latest >= 0 ? roots.getRoot(latest) : SparseMerkleTree.EMPTY_ROOT;
    }

    /** The current committed state root (32 bytes). */
    public byte[] root() {
        return currentRoot.clone();
    }

    /** Whether any state has been committed yet (genesis seeded). */
    public boolean isSeeded() {
        return roots.latestHeight() >= 0;
    }

    /** The highest block height whose state root is committed (−1 if none) — for boot reconciliation. */
    public long committedHeight() {
        return roots.latestHeight();
    }

    /** The root recorded for {@code height}, or {@code null} if none. */
    public byte[] rootAt(long height) {
        return roots.getRoot(height);
    }

    /** The root that applying {@code changes} to the current root would yield — no persistence. */
    public byte[] dryApply(List<StateChange> changes) {
        // Buffer the nodes this trial creates and drop them: a dry run (the producer stamping a
        // candidate root) must not grow the store with nodes that may never be committed. They are
        // content-addressed, so the real applyBlock re-derives the identical nodes if the block lands.
        nodes.beginBatch();
        try {
            return applyTo(currentRoot, changes);
        } finally {
            nodes.discardBatch();
        }
    }

    /** Applies {@code changes} at {@code height}, persists the new root, and advances the current root. */
    public byte[] applyBlock(long height, List<StateChange> changes) {
        // Stage this block's new SMT nodes and flush them in one batch (audit P8). The nodes are made
        // durable BEFORE putRoot, so the committed root always references nodes that are already on
        // disk — a crash between them leaves only harmless orphan nodes (re-created on re-apply), never
        // a root pointing at a missing node.
        nodes.beginBatch();
        byte[] root;
        try {
            root = applyTo(currentRoot, changes);
        } catch (RuntimeException e) {
            nodes.discardBatch();
            throw e;
        }
        nodes.flushBatch();
        roots.putRoot(height, root);
        currentRoot = root;
        long cutoff = height - retainDepth;
        // Amortized prune (see PruneCadence): roots older than the window linger at most
        // PRUNE_INTERVAL extra heights; the reorg window (retainDepth) always stays covered.
        if (cutoff > 1 && rootPrune.due(cutoff)) {
            roots.pruneBelow(cutoff); // keep genesis (height 1) and the reorg window
            nodes.gcNodesIfDue(cutoff); // explicit node GC at the same cadence (constat 41)
        }
        return root;
    }

    /** Reverts the block at {@code height}, moving the current root back to {@code height - 1}. */
    public void revertBlock(long height) {
        byte[] prior = roots.getRoot(height - 1);
        if (prior == null) {
            throw new IllegalStateException("no state root recorded at height " + (height - 1));
        }
        roots.deleteRoot(height);
        currentRoot = prior;
    }

    private byte[] applyTo(byte[] root, List<StateChange> changes) {
        byte[] r = root;
        for (StateChange c : changes) {
            byte[] key = StateKeys.key(c.domain(), c.rawKey());
            r = c.value() == null
                ? tree.remove(r, key)
                : tree.update(r, key, StateKeys.valueHash(c.value()));
        }
        return r;
    }

    /** Membership proof for {@code rawKey} in {@code domain} at the current root, or null if absent. */
    public StateProof prove(byte domain, byte[] rawKey) {
        return tree.prove(currentRoot, StateKeys.key(domain, rawKey));
    }
}
