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
        NetworkParameters params = engine.params();

        // Reserve one slot for the coinbase.
        int maxTx = Math.max(0, params.maxTransactionsPerBlock() - 1);
        List<Transaction> selected = mempool.getTransactionsForBlock(maxTx);

        // One atomic snapshot of every engine field the candidate commits to. Previously each
        // accessor (height, timestamp, difficulty, tip hash, uncles, collectable boxes) took the
        // engine lock separately, so a block landing mid-assembly produced a TORN candidate —
        // e.g. height-H difficulty over an H+1 parent hash — wasted mining until addBlock's
        // revalidation rejected it (audit review). The mempool read stays OUTSIDE the engine
        // lock: the established lock order is mempool→engine, never engine→mempool.
        record TipView(long height, long timestamp, int difficulty, SHA256Hash tipHash,
                       java.util.List<rhizome.core.block.UncleRef> uncles,
                       java.util.List<byte[]> collectableBoxIds) {}
        TipView view = engine.withConsistentView(() -> {
            long h = engine.height() + 1;
            // Conservative collect budget: selected.size() >= what the size-capped inclusion
            // loop below actually fits, so this may UNDER-fetch collectable ids (a few collect
            // slots go unused when the size cap evicts selected txs) but can never push the
            // block past the per-block tx+collect count cap.
            int collectBudget = Math.min(params.maxBoxCollectsPerBlock(),
                Math.max(0, params.maxTransactionsPerBlock() - 1 - selected.size()));
            return new TipView(h, engine.nextBlockTimestamp(preferredTimestamp), engine.difficulty(),
                engine.tipHash(), engine.selectUncles(),
                collectBudget > 0 ? engine.collectableBoxIds(h, collectBudget) : java.util.List.of());
        });
        long height = view.height();

        var block = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(view.timestamp())
            .difficulty(view.difficulty())
            .lastBlockHash(view.tipHash())
            .nonce(SHA256Hash.empty())
            // Credit valid off-chain siblings as uncles (GHOST): weights the chain
            // toward the true majority of work when blocks are produced faster than
            // they propagate.
            .uncles(view.uncles())
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
        if (!view.collectableBoxIds().isEmpty()) {
            long ts = block.timestamp();
            for (byte[] boxId : view.collectableBoxIds()) {
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
