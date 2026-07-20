package rhizome.core.blockchain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionImpl;

/**
 * In-memory {@link ChainStore} — reference implementation for tests and local
 * development; a LevelDB-backed one lives in lib-persistence.
 */
public final class InMemoryChainStore implements ChainStore {

    private final List<Block> blocks = new ArrayList<>();
    private final Map<SHA256Hash, Long> txIndex = new HashMap<>();

    @Override
    public long height() {
        return blocks.size();
    }

    @Override
    public Block blockAt(long height) {
        if (height < 1 || height > blocks.size()) {
            throw new IllegalArgumentException("No block at height " + height);
        }
        return blocks.get((int) height - 1);
    }

    @Override
    public void append(Block block) {
        long expected = height() + 1;
        if (((BlockImpl) block).id() != expected) {
            throw new IllegalArgumentException(
                "Expected block " + expected + " but got " + ((BlockImpl) block).id());
        }
        blocks.add(block);
        for (Transaction t : block.transactions()) {
            if (!((TransactionImpl) t).isTransactionFee()) {
                txIndex.put(t.hashContents(), expected);
            }
        }
    }

    @Override
    public void pop() {
        if (blocks.isEmpty()) {
            throw new IllegalStateException("Cannot pop an empty chain");
        }
        Block removed = blocks.remove(blocks.size() - 1);
        for (Transaction t : removed.transactions()) {
            if (!((TransactionImpl) t).isTransactionFee()) {
                txIndex.remove(t.hashContents());
            }
        }
    }

    @Override
    public boolean hasTransaction(SHA256Hash contentHash) {
        return txIndex.containsKey(contentHash);
    }
}
