package rhizome.core.merkletree;

import java.util.ArrayList;
import java.util.List;

import rhizome.crypto.Crypto;
import rhizome.crypto.SHA256Hash;
import rhizome.core.transaction.Transaction;

/**
 * Computes a block's transaction Merkle root. Only the root is ever read back (block assembly and
 * validation both discard the tree the moment they have it), so this folds level by level into a
 * single hash rather than retaining a graph of parent-linked nodes — the inclusion-proof machinery
 * that graph existed for had no production consumer.
 */
public class MerkleTree {

    /**
     * Domain-separation prefixes. Leaves and internal nodes are hashed in DISTINCT domains so an
     * internal node's 64-byte preimage can never be reinterpreted as a leaf (or vice versa) — the
     * classic Merkle second-preimage attack (audit M5). This matches what the project's
     * SparseMerkleTree already does (0x00 leaf / 0x01 inner).
     */
    private static final byte LEAF_PREFIX = 0x00;
    private static final byte NODE_PREFIX = 0x01;

    private SHA256Hash rootHash = SHA256Hash.empty();

    /** Leaf hash: {@code SHA-256(0x00 || txHash)}. */
    public static SHA256Hash leafHash(SHA256Hash txHash) {
        byte[] t = txHash.raw(); // copied into `in` below, never retained (hot path — see raw())
        byte[] in = new byte[1 + t.length];
        in[0] = LEAF_PREFIX;
        System.arraycopy(t, 0, in, 1, t.length);
        return Crypto.SHA256(in);
    }

    /** Internal-node hash: {@code SHA-256(0x01 || left || right)}. */
    public static SHA256Hash nodeHash(SHA256Hash left, SHA256Hash right) {
        byte[] l = left.raw(); // same: consumed by the arraycopy immediately
        byte[] r = right.raw();
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
        if (items.isEmpty()) {
            // Defined empty-tree root (audit L8): a block always carries a coinbase so this is not
            // reached on the consensus path, but the class must not NPE for an empty item list.
            this.rootHash = SHA256Hash.empty();
            return;
        }

        List<SHA256Hash> level = new ArrayList<>(items.size());
        for (Transaction item : items) {
            level.add(leafHash(item.hash())); // item.hash() is memoized in TransactionImpl
        }

        // Canonical level-by-level fold. An odd level duplicates its LAST hash (not the first, and
        // not by folding a leaf back in) — the previous single-queue build mixed tree levels and
        // duplicated the first leaf, so distinct transaction lists could collide on the root
        // (audit L7). Domain separation above makes the duplicate a node, not a forgeable leaf.
        while (level.size() > 1) {
            List<SHA256Hash> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                SHA256Hash a = level.get(i);
                SHA256Hash b = (i + 1 < level.size()) ? level.get(i + 1) : a;
                next.add(nodeHash(a, b));
            }
            level = next;
        }

        this.rootHash = level.get(0);
    }

    public SHA256Hash getRootHash() {
        return rootHash;
    }
}
