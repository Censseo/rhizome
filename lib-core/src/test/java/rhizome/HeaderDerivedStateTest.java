package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

/**
 * A3: the engine's derived state — difficulty retargets, median-time-past, uncle
 * validation and vote tallies — must read only block <em>headers</em>, never the
 * historical bodies. A {@link ChainStore} that raises if a non-tip body is read
 * proves it: every steady-state operation runs green through it. This is the
 * property that lets a pruned or snap-synced node run with bodies discarded.
 */
class HeaderDerivedStateTest {

    /** Delegates to an in-memory store but forbids reading any body below the tip. */
    static final class NoHistoricalBodyStore implements ChainStore {
        private final ChainStore inner;
        NoHistoricalBodyStore(ChainStore inner) { this.inner = inner; }

        @Override public long height() { return inner.height(); }

        @Override public Block blockAt(long height) {
            if (height < inner.height()) {
                throw new AssertionError("engine read a historical block BODY at height " + height
                    + " (tip=" + inner.height() + "); derived state must use headerAt");
            }
            return inner.blockAt(height);
        }

        @Override public BlockHeader headerAt(long height) { return inner.headerAt(height); }
        @Override public void append(Block block) { inner.append(block); }
        @Override public void pop() { inner.pop(); }
        @Override public boolean hasTransaction(SHA256Hash contentHash) { return inner.hasTransaction(contentHash); }
    }

    private final AtomicLong clock = new AtomicLong(10_000_000L);

    private ChainEngine engineWith(NetworkParameters params) {
        return ChainEngine.init(params, new InMemoryLedger(),
            new NoHistoricalBodyStore(new InMemoryChainStore()),
            new LedgerSnapshot("t", 0, params.chainId()), null, clock::get);
    }

    private BlockImpl mine(ChainEngine engine, NetworkParameters params, long timestampMs,
                           int vote, List<UncleRef> uncles, PublicAddress miner) {
        long h = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(timestampMs)
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .vote(vote).uncles(new java.util.ArrayList<>(uncles)).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void retargetsAndVoteTalliesReadHeadersOnly() {
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256)
            .genesisDifficulty(4).minDifficulty(4).maxDifficulty(64)
            .difficultyLookback(4).desiredBlockTimeSec(1).minBlockTimeSec(0)
            .maxFutureBlockTimeSec(1_000_000)
            .votingEpochLength(4)
            .build();
        ChainEngine engine = engineWith(params);
        PublicAddress miner = PublicAddress.random();

        // Cross two retarget windows and two voting epochs. Every added block triggers a
        // difficulty recompute (boundary header timestamps), median-time-past (recent
        // header timestamps) and, at the epoch boundaries, a vote tally (epoch headers).
        long ts = 1;
        for (int i = 0; i < 12; i++) {
            int vote = 1; // all vote to raise storageFeeFactor
            assertEquals(ExecutionStatus.SUCCESS,
                engine.addBlock(mine(engine, params, ts++, vote, List.of(), miner)),
                "block " + (i + 1) + " must apply without reading a historical body");
        }
        assertEquals(13, engine.height());
        assertTrue(engine.difficulty() >= 4);

        // A pop recomputes difficulty from headers too; still no historical body read.
        engine.popBlock();
        assertEquals(12, engine.height());
    }

    @Test
    void uncleValidationReadsHeadersOnly() {
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256)
            .genesisDifficulty(3).minDifficulty(3)
            .difficultyLookback(100).minBlockTimeSec(0).maxFutureBlockTimeSec(1_000_000)
            .build();
        ChainEngine engine = engineWith(params);
        PublicAddress miner = PublicAddress.random();

        // height 2
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, clock.addAndGet(1000), 0, List.of(), miner)));

        // An orphan sibling of the tip (forks from genesis), registered in the pool.
        // Read the fork point from the tip's parent link (a header field) rather than
        // the historical body — the guard forbids the latter.
        PublicAddress orphanMiner = PublicAddress.random();
        SHA256Hash grandparent = engine.blockAt(engine.height()).lastBlockHash();
        var orphan = (BlockImpl) BlockImpl.builder().id((int) engine.height())
            .timestamp(clock.addAndGet(500)).difficulty(engine.difficulty())
            .lastBlockHash(grandparent).build();
        orphan.addTransaction(Transaction.of(orphanMiner, new TransactionAmount(params.miningReward(engine.height()))));
        var tree = new MerkleTree();
        tree.setItems(orphan.transactions());
        orphan.merkleRoot(tree.getRootHash());
        orphan.nonce(Miner.mineNonce(orphan.hash(), orphan.difficulty(), params.powAlgorithm()));
        engine.registerOrphan(orphan);

        // A block referencing that orphan as an uncle: validation walks recent headers
        // (uncleContext / uncleEligible) — none of it may read a historical body.
        UncleRef ref = new UncleRef(orphan.hash(), orphan.difficulty(), minerOf(orphan));
        assertEquals(ExecutionStatus.SUCCESS,
            engine.addBlock(mine(engine, params, clock.addAndGet(1000), 0, List.of(ref), miner)));
        assertEquals(3, engine.height());
    }

    private static PublicAddress minerOf(Block block) {
        return ((TransactionImpl) block.transactions().get(0)).to();
    }
}
