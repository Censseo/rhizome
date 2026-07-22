package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * The consensus minimum-block-time rule: every node rejects a block too close to
 * its parent, so sustained block production is capped for everyone — not by the
 * producer's local pacing (which a modified node could remove). See
 * WHITEPAPER.md §3.4.
 */
class MinBlockTimeTest {

    // 1-block/s style params, but SHA-256 + low difficulty so tests mine instantly.
    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256)
        .genesisDifficulty(3)
        .minBlockTimeSec(1)         // 1000 ms floor between blocks
        .maxFutureBlockTimeSec(5)   // at most ~5 blocks can be mined in advance
        .build();

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ChainEngine engine = ChainEngine.init(PARAMS, new InMemoryLedger(),
        new InMemoryChainStore(), new LedgerSnapshot("t", 0, PARAMS.chainId()), null, now::get);
    private final PublicAddress miner = PublicAddress.random();

    /** Builds a fully valid next block with an explicit timestamp (not added). */
    private Block blockAt(long timestamp) {
        long h = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(timestamp)
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
        return b;
    }

    @Test
    void rejectsBlockTooCloseToParent() {
        // Genesis timestamp is 0; floor for block 2 is 0 + 1000 ms.
        assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_TOO_CLOSE, engine.addBlock(blockAt(999)));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(blockAt(1000)));

        // Block 3 must be >= 1000 + 1000.
        assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_TOO_CLOSE, engine.addBlock(blockAt(1999)));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(blockAt(2000)));
        assertEquals(3, engine.height());
    }

    @Test
    void futureBoundCapsBlocksMinedInAdvance() {
        // Freeze the clock: a miner can only place timestamps up to now + maxFuture,
        // spaced by at least minBlockTime -> a bounded number of blocks, then it must
        // wait for real time. This is the sustained-rate cap, majority hashrate or not.
        now.set(10_000L);
        long maxTs = now.get() + PARAMS.maxFutureBlockTimeSec() * 1000L; // 15_000

        int produced = 0;
        for (long ts = 1000; ts <= 30_000; ts += 1000) { // try well past the bound
            ExecutionStatus status = engine.addBlock(blockAt(ts));
            if (status == ExecutionStatus.SUCCESS) {
                produced++;
            } else {
                assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_IN_FUTURE, status);
                assertNotEquals(0, produced);
                break;
            }
        }

        // Last accepted block sits at or below the future bound; no unbounded flood.
        assertEquals(maxTs, ((BlockImpl) engine.blockAt(engine.height())).timestamp());
    }

    @Test
    void nextBlockTimestampRespectsTheFloor() {
        engine.addBlock(blockAt(1000));
        // Even if the caller "prefers" a time below parent + minBlockTime, the engine
        // floors it so an honest producer's block is valid.
        assertEquals(2000, engine.nextBlockTimestamp(1500));
        assertEquals(5000, engine.nextBlockTimestamp(5000)); // preferred already above floor
    }
}
