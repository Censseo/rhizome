package rhizome.core.blockchain;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.crypto.SHA256Hash;

/**
 * Append-only view of the chain the engine drives: blocks by height plus an
 * index of executed transaction content-hashes. Implementations must keep the
 * transaction index consistent with append/pop (a popped block's transactions
 * become unknown again).
 *
 * <p>Heights are 1-based; height {@code GenesisBlock.GENESIS_ID} is genesis.
 */
public interface ChainStore {

    /** Number of blocks in the chain (0 when uninitialised). */
    long height();

    Block blockAt(long height);

    /**
     * The logical header at a height. The engine's derived state (difficulty,
     * median-time, uncle work, votes) depends only on headers, so it reads
     * through here — a store that has pruned the body can still serve the
     * header. The default derives it from the full block; persistent stores
     * override it to read a dedicated header column family.
     */
    default BlockHeader headerAt(long height) {
        return BlockHeader.of(blockAt(height));
    }

    default Block tip() {
        return blockAt(height());
    }

    /** Appends the next block (must be height()+1). */
    void append(Block block);

    /** Removes the tip block and de-indexes its transactions. */
    void pop();

    /** True if a transaction with this content hash is in an applied block. */
    boolean hasTransaction(SHA256Hash contentHash);
}
