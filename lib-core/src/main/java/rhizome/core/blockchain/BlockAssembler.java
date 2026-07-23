package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.box.BoxPayload;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

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
            // Credit valid off-chain siblings as uncles (GHOST): weights the chain
            // toward the true majority of work when blocks are produced faster than
            // they propagate.
            .uncles(engine.selectUncles())
            .build();

        Transaction coinbase = Transaction.of(miner, new TransactionAmount(params.miningReward(height)));
        block.addTransaction(coinbase);

        // Include transactions only while the block stays under the consensus size cap
        // (contract payloads are variable length), so the producer never builds a block
        // the network would reject as too large.
        // Size the block from sizeBytes() rather than serialize().getSize() (audit P7): the latter
        // allocates a full DTO (copying signature/key/data) per transaction just to read a length.
        long size = rhizome.core.block.dto.BlockDto.BUFFER_SIZE + coinbase.sizeBytes();
        for (Transaction t : selected) {
            long next = size + t.sizeBytes();
            if (next > params.maxBlockSizeBytes()) {
                break;
            }
            block.addTransaction(t);
            size = next;
        }

        // Rent collection (GHOST-like opportunistic clean-up): mint an unsigned BOX_COLLECT
        // for each expired box, crediting the rent to the miner. Bounded per block, and
        // included only while the block stays under the size cap.
        int slotsLeft = Math.max(0, params.maxTransactionsPerBlock() - block.transactions().size());
        int collectBudget = Math.min(params.maxBoxCollectsPerBlock(), slotsLeft);
        if (collectBudget > 0) {
            long ts = block.timestamp();
            for (byte[] boxId : engine.collectableBoxIds(height, collectBudget)) {
                Transaction collect = TransactionImpl.builder()
                    .kind(TransactionKind.BOX_COLLECT)
                    .from(PublicAddress.empty())
                    .to(miner)
                    .amount(new TransactionAmount(0))
                    .fee(new TransactionAmount(0))
                    .isTransactionFee(false)
                    .chainId(params.chainId())
                    .nonce(0)
                    .timestamp(ts)
                    .data(BoxPayload.encodeTarget(boxId))
                    .build();
                long next = size + collect.sizeBytes();
                if (next > params.maxBlockSizeBytes()) {
                    break;
                }
                block.addTransaction(collect);
                size = next;
            }
        }

        var tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        return block;
    }
}
