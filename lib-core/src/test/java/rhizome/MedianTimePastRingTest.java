package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * The incremental median-time ring (audit P6) must produce byte-identical medians to a fresh
 * store-read computation at every point of an add/pop/re-extend walk — a maintenance bug would be a
 * consensus fork, since median-time-past gates block validity.
 */
class MedianTimePastRingTest {

    private static final long NOW = 100_000_000_000L; // wall clock far ahead of the mined timestamps

    private NetworkParameters params;
    private ChainEngine engine;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4)
            .medianTimeWindow(5).minBlockTimeSec(0).maxFutureBlockTimeSec(1_000_000).build();
        miner = PublicAddress.random();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, () -> NOW);
    }

    private BlockImpl mineOn(long ts) {
        long h = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(ts)
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** The old implementation: re-read the window from headers and take the median. Ground truth. */
    private long expectedMtp() {
        long height = engine.height();
        int window = (int) Math.min(params.medianTimeWindow(), height);
        long[] ts = new long[window];
        for (int k = 0; k < window; k++) {
            ts[k] = engine.headerAt(height - window + 1 + k).timestamp();
        }
        java.util.Arrays.sort(ts);
        return ts[window / 2];
    }

    @Test
    void ringMedianMatchesFreshStoreComputationAcrossAddsPopsAndReExtension() {
        assertEquals(expectedMtp(), engine.medianTimePastForTest(), "at genesis");

        long ts = 1_000_000L;
        // Fill and slide the window past its size (window = 5): add 12 blocks.
        for (int i = 0; i < 12; i++) {
            ts += 1_000 + (i % 3) * 250; // strictly increasing, with jitter so the median is non-trivial
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineOn(ts)));
            assertEquals(expectedMtp(), engine.medianTimePastForTest(), "after add to height " + engine.height());
        }

        // Pop 8 — slides the window back down across its own size, exercising the front re-entry.
        for (int i = 0; i < 8; i++) {
            engine.popBlock();
            assertEquals(expectedMtp(), engine.medianTimePastForTest(), "after pop to height " + engine.height());
        }

        // Re-extend with fresh timestamps (a reorg's apply half): add 7 more.
        ts = engine.headerAt(engine.height()).timestamp();
        for (int i = 0; i < 7; i++) {
            ts += 2_000 + (i % 2) * 500;
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineOn(ts)));
            assertEquals(expectedMtp(), engine.medianTimePastForTest(), "after re-add to height " + engine.height());
        }
    }
}
