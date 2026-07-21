package rhizome.core.state;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A canonical sparse Merkle tree over 256-bit keys, backed by a content-addressed
 * {@link SmtNodeStore}. The tree authenticates a set of {@code key -> valueHash}
 * bindings in a single 32-byte root, supports membership proofs a light client can
 * verify with only the root (see {@link #verify}), and — because nodes are immutable
 * and content-addressed — lets a caller keep old roots resolvable for reorg reversal.
 *
 * <p>Construction (standard leaf-path SMT): a leaf commits its <em>full</em> key, so it
 * is valid at any depth and can be pulled up to its shortest unique prefix. Internal
 * nodes exist only where two or more keys diverge. This makes the root a deterministic
 * function of the binding <em>set</em> alone — independent of insertion/removal order.
 *
 * <ul>
 *   <li>{@code leafHash  = H(0x00 ‖ key ‖ valueHash)}</li>
 *   <li>{@code innerHash = H(0x01 ‖ left ‖ right)}</li>
 *   <li>empty subtree = 32 zero bytes (never stored)</li>
 * </ul>
 */
public final class SparseMerkleTree {

    /** The root of an empty tree: 32 zero bytes. */
    public static final byte[] EMPTY_ROOT = new byte[32];

    private static final byte LEAF = 0x00;
    private static final byte INNER = 0x01;
    private static final int KEY_BITS = 256;

    private final SmtNodeStore store;

    public SparseMerkleTree(SmtNodeStore store) {
        this.store = store;
    }

    /** Sets {@code key} (32 bytes) to {@code valueHash} (32 bytes) and returns the new root. */
    public byte[] update(byte[] root, byte[] key, byte[] valueHash) {
        require32(key, "key");
        require32(valueHash, "valueHash");
        return update(root, key, valueHash, 0);
    }

    /** Removes {@code key} and returns the new root (unchanged if the key was absent). */
    public byte[] remove(byte[] root, byte[] key) {
        require32(key, "key");
        return remove(root, key, 0);
    }

    private byte[] update(byte[] nodeHash, byte[] key, byte[] valueHash, int depth) {
        if (isEmpty(nodeHash)) {
            return putLeaf(key, valueHash);
        }
        Node node = load(nodeHash);
        if (node.leaf) {
            if (Arrays.equals(node.key, key)) {
                return putLeaf(key, valueHash); // in-place update
            }
            return split(node.key, node.value, key, valueHash, depth);
        }
        if (bit(key, depth) == 0) {
            return putInner(update(node.left, key, valueHash, depth + 1), node.right);
        }
        return putInner(node.left, update(node.right, key, valueHash, depth + 1));
    }

    /** Builds the internal nodes for two distinct keys that coincide until some bit >= depth. */
    private byte[] split(byte[] keyA, byte[] valA, byte[] keyB, byte[] valB, int depth) {
        int bA = bit(keyA, depth);
        int bB = bit(keyB, depth);
        if (bA == bB) {
            byte[] child = split(keyA, valA, keyB, valB, depth + 1);
            return bA == 0 ? putInner(child, EMPTY_ROOT) : putInner(EMPTY_ROOT, child);
        }
        byte[] leafA = putLeaf(keyA, valA);
        byte[] leafB = putLeaf(keyB, valB);
        return bA == 0 ? putInner(leafA, leafB) : putInner(leafB, leafA);
    }

    private byte[] remove(byte[] nodeHash, byte[] key, int depth) {
        if (isEmpty(nodeHash)) {
            return EMPTY_ROOT;
        }
        Node node = load(nodeHash);
        if (node.leaf) {
            return Arrays.equals(node.key, key) ? EMPTY_ROOT : nodeHash;
        }
        boolean goLeft = bit(key, depth) == 0;
        byte[] newLeft = goLeft ? remove(node.left, key, depth + 1) : node.left;
        byte[] newRight = goLeft ? node.right : remove(node.right, key, depth + 1);
        return canonical(newLeft, newRight);
    }

    /**
     * Canonicalises an internal node after a removal. A subtree with a single leaf must
     * become that bare leaf pulled up to its shortest unique prefix (so the root depends
     * only on the key set), which propagates upward level by level.
     */
    private byte[] canonical(byte[] left, byte[] right) {
        if (isEmpty(left) && isEmpty(right)) {
            return EMPTY_ROOT;
        }
        if (isEmpty(left) && load(right).leaf) {
            return right;
        }
        if (isEmpty(right) && load(left).leaf) {
            return left;
        }
        return putInner(left, right);
    }

    // ---- proofs ----

    /**
     * A membership proof for {@code key}: its {@code valueHash} and the sibling hashes
     * along the root-to-leaf path (top-down). Returns {@code null} if the key is absent.
     */
    public StateProof prove(byte[] root, byte[] key) {
        require32(key, "key");
        List<byte[]> siblings = new ArrayList<>();
        byte[] nodeHash = root;
        int depth = 0;
        while (!isEmpty(nodeHash)) {
            Node node = load(nodeHash);
            if (node.leaf) {
                return Arrays.equals(node.key, key) ? new StateProof(node.value, siblings) : null;
            }
            if (bit(key, depth) == 0) {
                siblings.add(node.right);
                nodeHash = node.left;
            } else {
                siblings.add(node.left);
                nodeHash = node.right;
            }
            depth++;
        }
        return null;
    }

    /**
     * Stateless verification a light client runs with only the committed {@code root}:
     * recomputes the root from {@code key}, {@code valueHash} and the proof's siblings.
     */
    public static boolean verify(byte[] root, byte[] key, byte[] valueHash, StateProof proof) {
        byte[] h = leafHash(key, valueHash);
        List<byte[]> siblings = proof.siblings();
        // A key is 256 bits, so a real proof has at most 256 siblings. An over-long attacker-supplied
        // proof would drive bit(key, depth) past the key's bit width and throw — a light client
        // verifying an untrusted proof must get a clean `false`, not an exception (audit L3).
        if (siblings.size() > KEY_BITS) {
            return false;
        }
        for (int depth = siblings.size() - 1; depth >= 0; depth--) {
            byte[] sib = siblings.get(depth);
            h = bit(key, depth) == 0 ? innerHash(h, sib) : innerHash(sib, h);
        }
        return Arrays.equals(h, root);
    }

    // ---- node encoding ----

    private record Node(boolean leaf, byte[] key, byte[] value, byte[] left, byte[] right) {}

    private Node load(byte[] hash) {
        byte[] bytes = store.get(hash);
        if (bytes == null) {
            throw new IllegalStateException("missing SMT node " + rhizome.core.common.Utils.bytesToHex(hash));
        }
        // A node is exactly type(1) + 32 + 32 = 65 bytes. Rejecting any other length stops
        // Arrays.copyOfRange from silently zero-padding a truncated/corrupt node into a valid-looking
        // one (the authentication guarantee otherwise quietly assumes the store never returns a
        // wrong-length blob) (audit L4).
        if (bytes.length != 65) { // type(1) + 32 + 32, matching the copyOfRange bounds below
            throw new IllegalStateException("corrupt SMT node (" + bytes.length + " bytes) for "
                + rhizome.core.common.Utils.bytesToHex(hash));
        }
        if (bytes[0] == LEAF) {
            return new Node(true, Arrays.copyOfRange(bytes, 1, 33), Arrays.copyOfRange(bytes, 33, 65), null, null);
        }
        return new Node(false, null, null, Arrays.copyOfRange(bytes, 1, 33), Arrays.copyOfRange(bytes, 33, 65));
    }

    private byte[] putLeaf(byte[] key, byte[] valueHash) {
        byte[] node = encode(LEAF, key, valueHash);
        byte[] hash = sha256(node);
        store.put(hash, node);
        return hash;
    }

    private byte[] putInner(byte[] left, byte[] right) {
        byte[] node = encode(INNER, left, right);
        byte[] hash = sha256(node);
        store.put(hash, node);
        return hash;
    }

    private static byte[] leafHash(byte[] key, byte[] valueHash) {
        return sha256(encode(LEAF, key, valueHash));
    }

    private static byte[] innerHash(byte[] left, byte[] right) {
        return sha256(encode(INNER, left, right));
    }

    private static byte[] encode(byte prefix, byte[] a, byte[] b) {
        byte[] out = new byte[65];
        out[0] = prefix;
        System.arraycopy(a, 0, out, 1, 32);
        System.arraycopy(b, 0, out, 33, 32);
        return out;
    }

    // ---- helpers ----

    private static boolean isEmpty(byte[] hash) {
        return Arrays.equals(hash, EMPTY_ROOT);
    }

    /** Bit {@code i} of a 32-byte key, most-significant first. */
    private static int bit(byte[] key, int i) {
        if (i >= KEY_BITS) {
            throw new IllegalStateException("SMT depth exceeded key length");
        }
        return (key[i >>> 3] >> (7 - (i & 7))) & 1;
    }

    private static void require32(byte[] b, String what) {
        if (b == null || b.length != 32) {
            throw new IllegalArgumentException(what + " must be 32 bytes");
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
