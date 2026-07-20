package rhizome.core.state;

import java.util.List;

/**
 * A sparse-Merkle membership proof: the {@code valueHash} bound to a key, and the sibling
 * hashes along the root-to-leaf path (top-down, one per level). A light client verifies it
 * against a committed state root with {@link SparseMerkleTree#verify}, needing nothing else.
 */
public record StateProof(byte[] valueHash, List<byte[]> siblings) {

    public StateProof {
        valueHash = valueHash.clone();
        siblings = List.copyOf(siblings);
    }

    @Override
    public byte[] valueHash() {
        return valueHash.clone();
    }
}
