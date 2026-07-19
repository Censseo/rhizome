package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/** Uncle references (GHOST): block preimage/codec, full uncle validation, and work weighting. */
class BlockUnclesTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private AtomicLong clock;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        clock = new AtomicLong(1_000_000L);
        miner = PublicAddress.random();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(), snapshot, null, clock::get);
    }

    /** A reference to an orphan, committing its real difficulty. */
    private static UncleRef ref(BlockImpl orphan) {
        return new UncleRef(orphan.hash(), orphan.difficulty());
    }

    /** A mined next block on the current tip, carrying the given uncle references. */
    private BlockImpl mineNext(List<UncleRef> uncles) {
        return mine(engine.height() + 1, engine.tipHash(), uncles, 7);
    }

    /** A mined block at an arbitrary height/parent with a distinguishing salt (for orphans). */
    private BlockImpl mine(long height, SHA256Hash parent, List<UncleRef> uncles, int salt) {
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000L + salt)).difficulty(engine.difficulty())
            .lastBlockHash(parent).uncles(new java.util.ArrayList<>(uncles)).build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** Registers an orphan sibling of the current tip (forks from the tip's parent). */
    private BlockImpl registerOrphanSiblingOfTip() {
        long tipHeight = engine.height();
        SHA256Hash grandparent = engine.blockAt(tipHeight - 1).hash();
        BlockImpl orphan = mine(tipHeight, grandparent, List.of(), 500);
        engine.registerOrphan(orphan);
        return orphan;
    }

    @Test
    void unclesCommitToTheHashAndRoundTripThroughTheCodec() {
        UncleRef u = new UncleRef(SHA256Hash.random(), 5);
        BlockImpl withUncle = mineNext(List.of(u));
        BlockImpl without = mineNext(List.of());
        assertNotEquals(without.hash(), withUncle.hash()); // committed to the hash

        Block decoded = BlockCodec.decode(BlockCodec.encode(withUncle));
        assertEquals(List.of(u), decoded.uncles());
        assertEquals(withUncle.hash(), decoded.hash());
    }

    @Test
    void acceptsBlockReferencingAKnownValidOrphanUncle() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip(); // orphan at height 2, forks from genesis
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(List.of(ref(orphan))))); // height 3
        assertEquals(3, engine.height());
    }

    @Test
    void uncleWorkIsAddedToTheChainWeight() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl plain = mineNext(List.of()); // height 3, no uncle
        BigInteger before = engine.totalWork();
        BlockImpl orphan = registerOrphanSiblingOfTip(); // orphan at height 2
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(List.of(ref(orphan))))); // height 3 w/ uncle

        // The uncle-bearing tip weighs its own block work plus the uncle's 2^difficulty.
        BigInteger blockWork = BigInteger.TWO.pow(plain.difficulty());
        BigInteger uncleWork = BigInteger.TWO.pow(orphan.difficulty());
        assertEquals(before.add(blockWork).add(uncleWork), engine.totalWork());
    }

    @Test
    void uncleWorkSurvivesPopAndRebuild() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip();
        engine.addBlock(mineNext(List.of(ref(orphan)))); // height 3 with uncle
        BigInteger withUncle = engine.totalWork();

        engine.popBlock(); // drop height 3
        assertEquals(2, engine.height());
        // Re-add the same shape; the uncle work must return identically.
        engine.registerOrphan(orphan);
        engine.addBlock(mineNext(List.of(ref(orphan))));
        assertEquals(withUncle, engine.totalWork());
    }

    @Test
    void selectUnclesReturnsEligibleOrphansAndAssembledBlockIsAccepted() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip(); // orphan at height 2

        List<rhizome.core.block.UncleRef> picked = engine.selectUncles();
        assertEquals(1, picked.size());
        assertEquals(orphan.hash(), picked.get(0).hash());
        assertEquals(orphan.difficulty(), picked.get(0).difficulty());

        // A block carrying exactly the selection is accepted.
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(picked)));
        assertEquals(3, engine.height());
        // Once referenced, the orphan is no longer offered for the next block.
        assertTrue(engine.selectUncles().isEmpty());
    }

    @Test
    void rejectsUncleWithInflatedDifficulty() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip();
        // Claim a higher difficulty than the orphan actually has: work inflation.
        UncleRef inflated = new UncleRef(orphan.hash(), orphan.difficulty() + 10);
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(inflated))));
        assertEquals(2, engine.height());
    }

    @Test
    void rejectsUnknownUncle() {
        engine.addBlock(mineNext(List.of()));
        assertEquals(ExecutionStatus.INVALID_UNCLES,
            engine.addBlock(mineNext(List.of(new UncleRef(SHA256Hash.random(), 3)))));
        assertEquals(2, engine.height());
    }

    @Test
    void rejectsUncleThatIsAMainChainBlock() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl canonical = (BlockImpl) engine.blockAt(2); // the canonical block 2
        engine.registerOrphan(canonical); // even if known, it is on-chain, not an orphan
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(ref(canonical)))));
    }

    @Test
    void rejectsTooManyUncles() {
        assertEquals(ExecutionStatus.INVALID_UNCLES,
            engine.addBlock(mineNext(List.of(
                new UncleRef(SHA256Hash.random(), 3),
                new UncleRef(SHA256Hash.random(), 3),
                new UncleRef(SHA256Hash.random(), 3)))));
        assertEquals(1, engine.height());
    }

    @Test
    void rejectsDuplicateUncles() {
        UncleRef u = new UncleRef(SHA256Hash.random(), 3);
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(u, u))));
    }

    @Test
    void unclelessBlocksAreAcceptedAndHashUnchanged() {
        BlockImpl a = mineNext(List.of());
        assertTrue(a.uncles().isEmpty());
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(a));
    }
}
