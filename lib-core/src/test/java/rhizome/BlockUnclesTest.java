package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
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

/** Uncle references (GHOST): block preimage/codec, and full uncle validation. */
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

    /** A mined next block on the current tip, carrying the given uncle hashes. */
    private BlockImpl mineNext(List<SHA256Hash> uncles) {
        return mine(engine.height() + 1, engine.tipHash(), uncles, 7);
    }

    /** A mined block at an arbitrary height/parent with a distinguishing salt (for orphans). */
    private BlockImpl mine(long height, SHA256Hash parent, List<SHA256Hash> uncles, int salt) {
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
        SHA256Hash u = SHA256Hash.random();
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
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(List.of(orphan.hash())))); // height 3
        assertEquals(3, engine.height());
    }

    @Test
    void rejectsUnknownUncle() {
        engine.addBlock(mineNext(List.of()));
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(SHA256Hash.random()))));
        assertEquals(2, engine.height());
    }

    @Test
    void rejectsUncleThatIsAMainChainBlock() {
        engine.addBlock(mineNext(List.of())); // height 2
        SHA256Hash canonical = engine.tipHash(); // the canonical block 2
        engine.registerOrphan(engine.blockAt(2)); // even if known, it is on-chain, not an orphan
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(canonical))));
    }

    @Test
    void rejectsTooManyUncles() {
        assertEquals(ExecutionStatus.INVALID_UNCLES,
            engine.addBlock(mineNext(List.of(SHA256Hash.random(), SHA256Hash.random(), SHA256Hash.random()))));
        assertEquals(1, engine.height());
    }

    @Test
    void rejectsDuplicateUncles() {
        SHA256Hash u = SHA256Hash.random();
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(u, u))));
    }

    @Test
    void unclelessBlocksAreAcceptedAndHashUnchanged() {
        BlockImpl a = mineNext(List.of());
        assertTrue(a.uncles().isEmpty());
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(a));
    }
}
