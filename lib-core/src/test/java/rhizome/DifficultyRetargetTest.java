package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * Guards the fix for the 1-block/s difficulty-starvation flaw: with no per-block
 * time floor, difficulty must be able to RISE when the network produces blocks
 * faster than the target. If a future change re-pinned the timestamp floor to the
 * target, the retarget would go blind and this test would fail.
 */
class DifficultyRetargetTest {

    // Fast, controllable profile: SHA-256 + low difficulty (instant mining), a short
    // retarget window, and crucially minBlockTimeSec = 0 so the producer does not
    // floor timestamps up to the target.
    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256)
        .genesisDifficulty(4).minDifficulty(4).maxDifficulty(64)
        .difficultyLookback(10)
        .desiredBlockTimeSec(1)
        .minBlockTimeSec(0)
        .maxFutureBlockTimeSec(1_000_000)
        .build();

    private final AtomicLong clock = new AtomicLong(10_000_000L);
    private final ChainEngine engine = ChainEngine.init(PARAMS, new InMemoryLedger(),
        new InMemoryChainStore(), new LedgerSnapshot("t", 0, PARAMS.chainId()), null, clock::get);
    private final PublicAddress miner = PublicAddress.random();

    private Block blockAt(long timestampMs) {
        long h = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(timestampMs)
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
        return b;
    }

    @Test
    void difficultyRisesWhenBlocksAreFasterThanTarget() {
        assertEquals(4, engine.difficulty());

        // Fill a full retarget window with blocks 1 ms apart — far faster than the
        // 1 s target. The window's observed duration (~9 ms) is a fraction of the
        // desired (~9 s), so difficulty must step up.
        for (long ts = 1; engine.height() < PARAMS.difficultyLookback() + 1; ts++) {
            assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS, engine.addBlock(blockAt(ts)));
        }

        assertTrue(engine.difficulty() > 4,
            "difficulty must rise to track hashrate when blocks beat the target, was "
                + engine.difficulty());
    }
}
