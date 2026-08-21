package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainEngineTestAccess;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.blockchain.VoteableParams;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.crypto.PowAlgorithm;
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

        engine = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot)
            .clock(clock::get)
            .verifier(new SignatureVerifier())
            .boxes(boxes)
            .build();
    }

    private void mine(int vote) {
        assertEquals(ExecutionStatus.SUCCESS, mineStatus(vote));
    }

    /** Mines one block carrying {@code vote} and returns the engine's verdict on it. */
    private ExecutionStatus mineStatus(int vote) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).vote(vote)
            .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return engine.addBlock(b);
    }

    @Test
    void consensusGateRejectsOutOfRangeVote() {
        // audit F1: one canonical vote rule at consensus, not just in the codecs — a block carrying
        // |vote| > 2 (however it was built: JSON, local producer bug, hand-rolled) must be rejected
        // by addBlock exactly as BlockDto/HeaderCodec reject it on the wire.
        assertEquals(ExecutionStatus.INVALID_VOTE, mineStatus(3));
        assertEquals(ExecutionStatus.INVALID_VOTE, mineStatus(-3));
        assertEquals(ExecutionStatus.INVALID_VOTE, mineStatus(Integer.MIN_VALUE));
        assertEquals(1, engine.height(), "no out-of-range vote may be applied");
        // The boundary values remain valid.
        mine(2);
        mine(-2);
        assertEquals(3, engine.height());
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

        ChainEngineTestAccess.popBlock(engine); // pop the boundary block -> its tally is dropped
        assertEquals(5, engine.voteableParams()[0]);
        assertEquals(5, boxes.voteableParams().storageFeeFactor());
    }
}
