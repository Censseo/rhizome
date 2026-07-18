package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * Builds an unmined candidate block for the current chain tip: a coinbase paying
 * the miner the height's reward, followed by transactions selected from the
 * mempool, with the merkle root computed. The nonce is left empty for the miner
 * to solve.
 */
public final class BlockAssembler {

    private BlockAssembler() {}

    public static Block assemble(ChainEngine engine, MemPool mempool, PublicAddress miner, long preferredTimestamp) {
        long height = engine.height() + 1;
        NetworkParameters params = engine.params();

        // Reserve one slot for the coinbase.
        int maxTx = Math.max(0, params.maxTransactionsPerBlock() - 1);
        List<Transaction> selected = mempool.getTransactionsForBlock(maxTx);

        var block = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(engine.nextBlockTimestamp(preferredTimestamp))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .nonce(SHA256Hash.empty())
            .build();

        block.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        selected.forEach(block::addTransaction);

        var tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        return block;
    }
}
