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
    private final RootStore roots;
    private final int retainDepth;
    private volatile byte[] currentRoot;

    public StateAccumulator(SmtNodeStore nodes, RootStore roots, int retainDepth) {
        this.tree = new SparseMerkleTree(nodes);
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
        return applyTo(currentRoot, changes);
    }

    /** Applies {@code changes} at {@code height}, persists the new root, and advances the current root. */
    public byte[] applyBlock(long height, List<StateChange> changes) {
        byte[] root = applyTo(currentRoot, changes);
        roots.putRoot(height, root);
        currentRoot = root;
        long cutoff = height - retainDepth;
        if (cutoff > 1) {
            roots.pruneBelow(cutoff); // keep genesis (height 1) and the reorg window
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
