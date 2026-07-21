package rhizome;

import org.junit.jupiter.api.Test;

import rhizome.core.crypto.SHA256Hash;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.merkletree.MerkleTree.HashTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.user.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

class MerkleTreeTest {

    @Test
    void singleNodeWorks() {
        MerkleTree m = new MerkleTree();
        User miner = User.create();
        Transaction a = miner.mine();
        List<Transaction> items = new ArrayList<>();
        items.add(a);
        m.setItems(items);
        var proof = m.getMerkleProof(a);
        // A single-transaction tree's root is the leaf itself (domain-separated), with no sibling —
        // no artificial self-doubling, matching the standard single-leaf Merkle convention.
        SHA256Hash leafA = MerkleTree.leafHash(a.hash());
        assertEquals(leafA, proof.get().hash());
        assertEquals(leafA, m.getRootHash());
    }

    private boolean checkProofRecursive(HashTree hashTree) {
        if (hashTree.left() == null && hashTree.right() == null) {
            // fringe node
            return true;
        } else {
            if (!MerkleTree.nodeHash(hashTree.left().hash(), hashTree.right().hash()).equals(hashTree.hash())) return false;
            return checkProofRecursive(hashTree.left()) && checkProofRecursive(hashTree.right());
        }
    }

    @Test
    void singleThreeNodesWorks() {
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
        var proof = m.getMerkleProof(a);

        assertEquals(MerkleTree.nodeHash(proof.get().left().hash(), proof.get().right().hash()), proof.get().hash());
        assertTrue(checkProofRecursive(proof.get()));
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

        assertTrue(!m1.getRootHash().equals(m2.getRootHash()), "root must depend on order");
    }

    @Test
    void largerTreeWorks() {
        MerkleTree m = new MerkleTree();
        User miner = User.create();
        User receiver = User.create();

        List<Transaction> items = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            items.add(miner.send(receiver, i));
        }
        m.setItems(items);
        var proof = m.getMerkleProof(items.get(4));
        assertTrue(checkProofRecursive(proof.get()));
    }
}
