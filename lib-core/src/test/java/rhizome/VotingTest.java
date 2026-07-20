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
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.blockchain.VoteableParams;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * Miner-voted parameters: an epoch of "increase" votes moves the votable box parameter one
 * bounded step at the epoch boundary; the box processor reads the new value; and a reorg
 * across the boundary restores the previous value.
 */
class VotingTest {

    private NetworkParameters params;
    private DefaultBoxProcessor boxes;
    private ChainEngine engine;
    private AtomicLong clock;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        // 4-block voting epochs; storageFeeFactor starts at 5, step 1.
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3)
            .votingEpochLength(4).storageFeeFactor(5).storageFeeFactorStep(1).storageFeeFactorMax(1000).build();
        boxes = new DefaultBoxProcessor(new InMemoryBoxStore(), params);
        clock = new AtomicLong(1_000_000L);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(PublicAddress.random(), new TransactionAmount(1_000_000L));

        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(), snapshot, null,
            clock::get, new SignatureVerifier(), null, boxes, null);
    }

    private void mine(int vote) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).vote(vote).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
    }

    @Test
    void anEpochOfIncreaseVotesRaisesTheParameter() {
        assertEquals(5, engine.voteableParams()[0]); // storageFeeFactor default

        // Blocks 2,3,4 vote to raise storageFeeFactor; the epoch boundary is height 4.
        mine(VoteableParams.STORAGE_FEE_FACTOR);  // +1 at height 2
        mine(VoteableParams.STORAGE_FEE_FACTOR);  // height 3
        assertEquals(5, engine.voteableParams()[0], "no change before the epoch boundary");
        mine(VoteableParams.STORAGE_FEE_FACTOR);  // height 4 -> boundary, net +3 > 2

        assertEquals(6, engine.voteableParams()[0], "one step up at the boundary");
        // The box processor reads the new value at execution time.
        assertEquals(6, boxes.voteableParams().storageFeeFactor());
    }

    @Test
    void abstainingLeavesTheParameterUnchanged() {
        mine(VoteableParams.ABSTAIN);
        mine(VoteableParams.ABSTAIN);
        mine(VoteableParams.ABSTAIN);
        mine(VoteableParams.ABSTAIN); // boundary, no votes
        assertEquals(5, engine.voteableParams()[0]);
    }

    @Test
    void reorgAcrossTheBoundaryRestoresTheParameter() {
        // Genesis is height 1, so three mined blocks reach the height-4 epoch boundary.
        mine(VoteableParams.STORAGE_FEE_FACTOR); // height 2
        mine(VoteableParams.STORAGE_FEE_FACTOR); // height 3
        mine(VoteableParams.STORAGE_FEE_FACTOR); // height 4 -> boundary, param 5 -> 6
        assertEquals(6, engine.voteableParams()[0]);

        engine.popBlock(); // pop the boundary block -> its tally is dropped
        assertEquals(5, engine.voteableParams()[0]);
        assertEquals(5, boxes.voteableParams().storageFeeFactor());
    }
}
