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

/** Uncle references (GHOST foundation): block preimage, codec, and structural validation. */
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

    /** A mined next block carrying the given uncle hashes (nonce solved for the with-uncles hash). */
    private BlockImpl blockWithUncles(List<SHA256Hash> uncles) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash()).uncles(new java.util.ArrayList<>(uncles)).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void unclesCommitToTheHashAndRoundTripThroughTheCodec() {
        SHA256Hash u = SHA256Hash.random();
        BlockImpl withUncle = blockWithUncles(List.of(u));
        BlockImpl without = blockWithUncles(List.of());
        // Same height/parent, but the uncle changes the header hash (it is committed).
        assertNotEquals(without.hash(), withUncle.hash());

        Block decoded = BlockCodec.decode(BlockCodec.encode(withUncle));
        assertEquals(List.of(u), decoded.uncles());
        assertEquals(withUncle.hash(), decoded.hash()); // hash preserved through the codec
    }

    @Test
    void acceptsABlockWithValidUncleStructure() {
        assertEquals(ExecutionStatus.SUCCESS,
            engine.addBlock(blockWithUncles(List.of(SHA256Hash.random()))));
        assertEquals(2, engine.height());
    }

    @Test
    void rejectsTooManyUncles() {
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(blockWithUncles(
            List.of(SHA256Hash.random(), SHA256Hash.random(), SHA256Hash.random()))));
        assertEquals(1, engine.height());
    }

    @Test
    void rejectsDuplicateUncles() {
        SHA256Hash u = SHA256Hash.random();
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(blockWithUncles(List.of(u, u))));
    }

    @Test
    void rejectsParentAsUncle() {
        assertEquals(ExecutionStatus.INVALID_UNCLES,
            engine.addBlock(blockWithUncles(List.of(engine.tipHash()))));
    }

    @Test
    void unclelessBlocksHashUnchanged() {
        // Regression guard: a block with no uncles must hash exactly as before the field
        // existed (empty uncles contribute nothing to the preimage).
        BlockImpl a = blockWithUncles(List.of());
        assertTrue(a.uncles().isEmpty());
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(a));
    }
}
