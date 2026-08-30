package rhizome.core.blockchain;

import rhizome.core.block.BlockImpl;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;

/**
 * Assembles and mines the next candidate block for an already-booted {@link ChainEngine}: the
 * block shell (id, difficulty, parent hash, {@link SupplyStamp}-committed supply), the coinbase
 * the caller supplies, the Merkle root, the state-root stamp ({@link ChainEngine#stampStateRoot}
 * — a no-op when the engine has no accumulator wired), and real proof of work.
 *
 * <p>Four hand-rolled copies of exactly this recipe had already drifted (one called
 * {@code stampStateRoot}, three did not — harmless only because none of the three had wired an
 * accumulator; a future one that did would have silently minted a header with the wrong state
 * root). What varies per caller — the coinbase's exact shape (some tests need a
 * byte-deterministic timestamp for Merkle-root reproducibility, some don't care), whether the
 * built block is applied immediately or handed to a different submission path, and how the clock
 * is stepped — stays at the call site instead of being folded in here.
 */
public final class HonestBlockMiner {

    private HonestBlockMiner() {
    }

    /** Builds and mines the next block for {@code engine}'s current tip, carrying {@code coinbase}
     *  as its sole transaction. Not applied — the caller decides how (direct {@code addBlock},
     *  or a higher-level submission path). */
    public static BlockImpl mineNext(NetworkParameters params, ChainEngine engine, long timestamp,
            Transaction coinbase) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(timestamp)
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty()))
            .build();
        b.addTransaction(coinbase);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        engine.stampStateRoot(b);
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }
}
