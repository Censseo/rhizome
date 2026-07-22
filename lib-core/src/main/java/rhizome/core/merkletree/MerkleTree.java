package rhizome.core.merkletree;
import lombok.Getter;
import lombok.Setter;
import rhizome.crypto.SHA256Hash;
import rhizome.core.transaction.Transaction;

import rhizome.crypto.Crypto;

import java.util.*;

@Getter @Setter
public class MerkleTree {

    /**
     * Domain-separation prefixes. Leaves and internal nodes are hashed in DISTINCT domains so an
     * internal node's 64-byte preimage can never be reinterpreted as a leaf (or vice versa) — the
     * classic Merkle second-preimage attack (audit M5). This matches what the project's
     * SparseMerkleTree already does (0x00 leaf / 0x01 inner).
     */
    private static final byte LEAF_PREFIX = 0x00;
    private static final byte NODE_PREFIX = 0x01;

    private HashTree root;
    private Map<SHA256Hash, HashTree> fringeNodes;

    public MerkleTree() {
        this.root = null;
        this.fringeNodes = new HashMap<>();
    }

    /** Leaf hash: {@code SHA-256(0x00 || txHash)}. */
    public static SHA256Hash leafHash(SHA256Hash txHash) {
        byte[] t = txHash.toBytes();
        byte[] in = new byte[1 + t.length];
        in[0] = LEAF_PREFIX;
        System.arraycopy(t, 0, in, 1, t.length);
        return Crypto.SHA256(in);
    }

    /** Internal-node hash: {@code SHA-256(0x01 || left || right)}. */
    public static SHA256Hash nodeHash(SHA256Hash left, SHA256Hash right) {
        byte[] l = left.toBytes();
        byte[] r = right.toBytes();
        byte[] in = new byte[1 + l.length + r.length];
        in[0] = NODE_PREFIX;
        System.arraycopy(l, 0, in, 1, l.length);
        System.arraycopy(r, 0, in, 1 + l.length, r.length);
        return Crypto.SHA256(in);
    }

    public void setItems(List<Transaction> items) {
        // Insertion order is preserved (no sort): the root then commits to the transaction ORDER,
        // not just the set. Sorting would make [t0,t1] and [t1,t0] share a root — hence a block
        // hash — so a reordered variant of a valid block would carry valid PoW yet be accepted or
        // rejected depending on which order a node received, splitting consensus.
        fringeNodes.clear();
        if (items.isEmpty()) {
            // Defined empty-tree root (audit L8): a block always carries a coinbase so this is not
            // reached on the consensus path, but the class must not NPE for an empty item list.
            this.root = new HashTree(SHA256Hash.empty());
            return;
        }

        List<HashTree> level = new ArrayList<>(items.size());
        for (Transaction item : items) {
            HashTree leaf = new HashTree(leafHash(item.hash()));
            fringeNodes.put(item.hash(), leaf);
            level.add(leaf);
        }

        // Canonical level-by-level build. An odd level duplicates its LAST node (a fresh node with
        // the same hash) rather than folding the first leaf back in — the previous single-queue
        // build mixed tree levels and duplicated the first leaf, so distinct transaction lists
        // could collide on the root (audit L7). Domain separation above makes the duplicate a
        // node, not a forgeable leaf.
        while (level.size() > 1) {
            List<HashTree> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                HashTree a = level.get(i);
                HashTree b = (i + 1 < level.size()) ? level.get(i + 1) : new HashTree(a.hash());
                HashTree parent = new HashTree(nodeHash(a.hash(), b.hash()));
                a.parent(parent);
                b.parent(parent);
                parent.left(a);
                parent.right(b);
                next.add(parent);
            }
            level = next;
        }

        this.root = level.get(0);
    }

    public SHA256Hash getRootHash() {
        return this.root.hash();
    }

    public Optional<HashTree> getMerkleProof(Transaction t) {
        return Optional.ofNullable(fringeNodes.get(t.hash()))
                       .map(f -> buildProof(f, null));
    }

    private HashTree buildProof(HashTree fringe, HashTree previousNode) {
        var result = new HashTree(fringe.hash());
        if (previousNode != null) {
            if (fringe.left() != null && fringe.left() != previousNode) {
                result.left(fringe.left());
                result.right(previousNode);
            } else if (fringe.right() != null && fringe.right() != previousNode) {
                result.right(fringe.right());
                result.left(previousNode);
            }
        }
        if (fringe.parent() != null) {
            return buildProof(fringe.parent(), fringe);
        } else {
            return result;
        }
    }

    @Getter
    @Setter
    public static class HashTree {
        private SHA256Hash hash;
        private HashTree parent;
        private HashTree left;
        private HashTree right;

        public HashTree(SHA256Hash hash) {
            this.hash = hash;
            this.parent = this.left = this.right = null;
        }
    }

}
