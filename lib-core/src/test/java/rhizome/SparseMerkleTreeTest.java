package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.SparseMerkleTree;
import rhizome.core.state.StateProof;

class SparseMerkleTreeTest {

    private SparseMerkleTree tree() {
        return new SparseMerkleTree(new InMemorySmtNodeStore());
    }

    private static byte[] h(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] buildInOrder(List<String> keys) {
        SparseMerkleTree t = tree();
        byte[] root = SparseMerkleTree.EMPTY_ROOT;
        for (String k : keys) {
            root = t.update(root, h(k), h("val-" + k));
        }
        return root;
    }

    @Test
    void emptyTreeHasZeroRoot() {
        assertArrayEquals(new byte[32], SparseMerkleTree.EMPTY_ROOT);
    }

    @Test
    void rootIsIndependentOfInsertionOrder() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            keys.add("key-" + i);
        }
        byte[] forward = buildInOrder(keys);
        Collections.reverse(keys);
        byte[] reverse = buildInOrder(keys);
        Collections.shuffle(keys, new java.util.Random(12345));
        byte[] shuffled = buildInOrder(keys);
        assertArrayEquals(forward, reverse);
        assertArrayEquals(forward, shuffled);
    }

    @Test
    void updateChangesRootAndValue() {
        SparseMerkleTree t = tree();
        byte[] root = t.update(SparseMerkleTree.EMPTY_ROOT, h("a"), h("v1"));
        byte[] root2 = t.update(root, h("a"), h("v2"));
        assertFalse(java.util.Arrays.equals(root, root2));
        StateProof p = t.prove(root2, h("a"));
        assertArrayEquals(h("v2"), p.valueHash());
    }

    @Test
    void removeIsInverseOfInsertAndCanonical() {
        SparseMerkleTree t = tree();
        // Build {a,b,c}, then remove c: root must equal a freshly-built {a,b}.
        byte[] abc = SparseMerkleTree.EMPTY_ROOT;
        abc = t.update(abc, h("a"), h("va"));
        abc = t.update(abc, h("b"), h("vb"));
        abc = t.update(abc, h("c"), h("vc"));
        byte[] afterRemove = t.remove(abc, h("c"));

        byte[] ab = SparseMerkleTree.EMPTY_ROOT;
        ab = t.update(ab, h("a"), h("va"));
        ab = t.update(ab, h("b"), h("vb"));
        assertArrayEquals(ab, afterRemove);

        // Removing the last keys returns to the empty root.
        byte[] emptied = t.remove(t.remove(afterRemove, h("a")), h("b"));
        assertArrayEquals(SparseMerkleTree.EMPTY_ROOT, emptied);
    }

    @Test
    void membershipProofVerifies() {
        SparseMerkleTree t = tree();
        byte[] root = SparseMerkleTree.EMPTY_ROOT;
        for (int i = 0; i < 30; i++) {
            root = t.update(root, h("k" + i), h("v" + i));
        }
        StateProof proof = t.prove(root, h("k7"));
        assertNotNull(proof);
        assertArrayEquals(h("v7"), proof.valueHash());
        assertTrue(SparseMerkleTree.verify(root, h("k7"), h("v7"), proof));
        // Tampered value must fail.
        assertFalse(SparseMerkleTree.verify(root, h("k7"), h("wrong"), proof));
        // Wrong key against this proof must fail.
        assertFalse(SparseMerkleTree.verify(root, h("k8"), h("v7"), proof));
    }

    @Test
    void verifyReturnsFalseOnMalformedProofInsteadOfThrowing() {
        // A proof server is untrusted: a wrong-length sibling (or key/valueHash) must yield a clean
        // false, never an ArrayIndexOutOfBounds inside encode()'s fixed 32-byte copy that would
        // crash a verifying light client (audit L3, residual).
        SparseMerkleTree t = tree();
        byte[] root = t.update(SparseMerkleTree.EMPTY_ROOT, h("k"), h("v"));
        StateProof good = t.prove(root, h("k"));
        // A single 5-byte sibling instead of 32 bytes.
        StateProof badSibling = new StateProof(h("v"), List.of(new byte[5]));
        assertFalse(SparseMerkleTree.verify(root, h("k"), h("v"), badSibling));
        // A short key against an otherwise-valid proof.
        assertFalse(SparseMerkleTree.verify(root, new byte[5], h("v"), good));
        // A short valueHash.
        assertFalse(SparseMerkleTree.verify(root, h("k"), new byte[5], good));
    }

    @Test
    void absentKeyHasNoProof() {
        SparseMerkleTree t = tree();
        byte[] root = t.update(SparseMerkleTree.EMPTY_ROOT, h("present"), h("v"));
        assertNull(t.prove(root, h("absent")));
    }

    @Test
    void oldRootStaysResolvableAfterNewWrites() {
        SparseMerkleTree t = tree();
        byte[] r1 = t.update(SparseMerkleTree.EMPTY_ROOT, h("a"), h("va"));
        byte[] r2 = t.update(r1, h("b"), h("vb"));
        // r1 is still provable (content-addressed nodes are never overwritten).
        assertTrue(SparseMerkleTree.verify(r1, h("a"), h("va"), t.prove(r1, h("a"))));
        assertNull(t.prove(r1, h("b")));
        assertTrue(SparseMerkleTree.verify(r2, h("b"), h("vb"), t.prove(r2, h("b"))));
    }

    @Test
    void fabricatedProofsDoNotVerifyAgainstTheCommittedRoot() {
        // Proof SOUNDNESS is the light-client security property (audit S10 / crypto F3): an attacker
        // who does not know a preimage cannot forge a (key, value, siblings) that folds to the honest
        // root. Soundness holds by construction (a leaf commits its full 256-bit key; the bottom-up
        // fold is collision-resistant), but the forgery surface had no negative test.
        SparseMerkleTree t = tree();
        byte[] root = SparseMerkleTree.EMPTY_ROOT;
        for (int i = 0; i < 40; i++) {
            root = t.update(root, h("k" + i), h("v" + i));
        }
        StateProof real = t.prove(root, h("k5"));
        assertTrue(SparseMerkleTree.verify(root, h("k5"), h("v5"), real));

        // (a) A fabricated value for a real key, reusing that key's real siblings, must fail.
        assertFalse(SparseMerkleTree.verify(root, h("k5"), h("forged-value"), real));

        // (b) Non-membership presented as membership: an absent key, with a real key's proof, must fail.
        assertFalse(SparseMerkleTree.verify(root, h("absent-key"), h("v5"), real));

        // (c) A wholly fabricated proof (random 32-byte siblings) for a plausible key must fail.
        List<byte[]> junk = new ArrayList<>();
        for (int i = 0; i < real.siblings().size(); i++) {
            junk.add(h("junk-sibling-" + i));
        }
        assertFalse(SparseMerkleTree.verify(root, h("k5"), h("v5"), new StateProof(h("v5"), junk)));

        // (d) A genuine proof under one root must not verify against a different root.
        byte[] otherRoot = t.update(root, h("k5"), h("changed"));
        assertFalse(SparseMerkleTree.verify(otherRoot, h("k5"), h("v5"), real));
    }
}
