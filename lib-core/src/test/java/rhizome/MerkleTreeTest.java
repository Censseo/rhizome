package rhizome;

import org.junit.jupiter.api.Test;

import rhizome.crypto.SHA256Hash;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.user.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;

class MerkleTreeTest {

    @Test
    void singleNodeRootIsTheLeafItself() {
        MerkleTree m = new MerkleTree();
        User miner = User.create();
        Transaction a = miner.mine();
        List<Transaction> items = new ArrayList<>();
        items.add(a);
        m.setItems(items);
        // A single-transaction tree's root is the leaf itself (domain-separated), with no sibling —
        // no artificial self-doubling, matching the standard single-leaf Merkle convention.
        assertEquals(MerkleTree.leafHash(a.hash()), m.getRootHash());
    }

    @Test
    void threeItemsFoldToTheExpectedRoot() {
        MerkleTree m = new MerkleTree();
        User miner = User.create();
        User receiver = User.create();
        Transaction a = miner.mine();
        Transaction b = miner.send(receiver, 50);
        Transaction c = miner.send(receiver, 50);
        List<Transaction> items = new ArrayList<>();
        items.add(a);
        items.add(b);
        items.add(c);
        m.setItems(items);

        // Odd level: c's leaf is duplicated as its own sibling, matching setItems' documented fold.
        SHA256Hash leafA = MerkleTree.leafHash(a.hash());
        SHA256Hash leafB = MerkleTree.leafHash(b.hash());
        SHA256Hash leafC = MerkleTree.leafHash(c.hash());
        SHA256Hash left = MerkleTree.nodeHash(leafA, leafB);
        SHA256Hash right = MerkleTree.nodeHash(leafC, leafC);
        assertEquals(MerkleTree.nodeHash(left, right), m.getRootHash());
    }

    @Test
    void emptyTreeHasDefinedRoot() {
        // An empty item list must yield a defined root, not an NPE (audit L8).
        MerkleTree m = new MerkleTree();
        m.setItems(new ArrayList<>());
        assertEquals(SHA256Hash.empty(), m.getRootHash());
    }

    @Test
    void leafAndNodeDomainsAreSeparated() {
        // A 64-byte internal-node preimage must not be reinterpretable as a leaf (second-preimage,
        // audit M5): the leaf and node hashes of the same bytes must differ.
        User miner = User.create();
        SHA256Hash h = miner.mine().hash();
        assertNotEquals(MerkleTree.leafHash(h), MerkleTree.nodeHash(h, h));
    }

    @Test
    void rootCommitsToTransactionOrder() {
        // Reordering the same set of transactions must change the root, otherwise a
        // reordered variant of a valid block would share its hash (and PoW) yet be
        // validated differently — an order-dependent consensus split.
        User miner = User.create();
        User receiver = User.create();
        Transaction a = miner.mine();
        Transaction b = miner.send(receiver, 50);
        Transaction c = miner.send(receiver, 60);

        MerkleTree m1 = new MerkleTree();
        m1.setItems(new ArrayList<>(List.of(a, b, c)));
        MerkleTree m2 = new MerkleTree();
        m2.setItems(new ArrayList<>(List.of(a, c, b)));

        assertNotEquals(m1.getRootHash(), m2.getRootHash(), "root must depend on order");
    }

    @Test
    void largerTreeFoldsToADefinedRoot() {
        MerkleTree m = new MerkleTree();
        User miner = User.create();
        User receiver = User.create();

        List<Transaction> items = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            items.add(miner.send(receiver, i));
        }
        m.setItems(items);
        assertNotEquals(SHA256Hash.empty(), m.getRootHash());

        // Deterministic: rebuilding from the same (large, odd-at-several-levels) list folds to the
        // identical root.
        MerkleTree m2 = new MerkleTree();
        m2.setItems(items);
        assertEquals(m.getRootHash(), m2.getRootHash());
    }
}
