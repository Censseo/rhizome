package rhizome;

import org.junit.jupiter.api.Test;

import rhizome.core.crypto.SHA256Hash;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.merkletree.MerkleTree.HashTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.user.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.concatHashes;

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
        assertEquals(proof.get().left().hash(), a.hash());
        assertEquals(proof.get().right().hash(), a.hash());
        SHA256Hash ha = a.hash();
        assertEquals(proof.get().hash(), concatHashes(ha, ha, false, false));
    }

    private boolean checkProofRecursive(HashTree hashTree) {
        if (hashTree.left() == null && hashTree.right() == null) {
            // fringe node
            return true;
        } else {
            if (!concatHashes(hashTree.left().hash(), hashTree.right().hash(), false, false).equals(hashTree.hash())) return false;
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
        
        assertEquals(concatHashes(proof.get().left().hash(), proof.get().right().hash(), false, false), proof.get().hash());
        assertTrue(checkProofRecursive(proof.get()));
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
