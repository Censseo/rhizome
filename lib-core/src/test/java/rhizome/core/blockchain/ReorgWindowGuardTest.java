package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;

/**
 * The reorg window guard (audit: non-atomic reorg window): while a synchronizer holds a
 * non-atomic pop → body-apply → restore sequence open, new-tip blocks from gossip//submit and
 * the local producer must fail fast instead of being accepted at the truncated fork height and
 * destroyed by the restore. The synchronizer's own trusted paths bypass the guard. In-package so
 * the test can drive the package-private window and trusted restore entries.
 */
class ReorgWindowGuardTest {

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
        engine = ChainEngine.boot(
                params,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, params.chainId()))
            .clock(clock::get)
            .build();
    }

    /** A mined next block on the current tip (coinbase only). */
    private BlockImpl mineNext() {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000L)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void newTipBlocksFailFastWhileTheWindowIsOpen() {
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext())); // height 2, control
        assertFalse(engine.isReorgInProgress());

        assertTrue(engine.beginReorgWindow());
        assertTrue(engine.isReorgInProgress());
        try {
            // A gossiped/submitted/produced block at the (possibly truncated) tip is refused
            // without any validation work, and the chain is untouched.
            assertEquals(ExecutionStatus.IS_SYNCING, engine.addBlock(mineNext()));
            assertEquals(2, engine.height());
            // The producer stands down before burning any PoW.
            var mempool = new MemPool(params, new SignatureVerifier(), engine, 100);
            assertTrue(new BlockProducer(engine, mempool, miner, clock::get).produce().isEmpty());
            assertEquals(2, engine.height());
        } finally {
            engine.endReorgWindow();
        }
        assertFalse(engine.isReorgInProgress());
        // After the window closes, the very same block is accepted.
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext()));
        assertEquals(3, engine.height());
    }

    @Test
    void trustedPathsBypassTheGuard() {
        assertTrue(engine.beginReorgWindow());
        try {
            // The synchronizer's body apply (addValidatedBody) still lands: build an otherwise
            // valid block with an UNMINED nonce so only the trusted-PoW path can accept it.
            BlockImpl body = mineNext();
            do { body.nonce(SHA256Hash.random()); } while (body.verifyNonce(params.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addValidatedBody(body));
            assertEquals(2, engine.height());
        } finally {
            engine.endReorgWindow();
        }
    }

    @Test
    void restoreSkipsPowOnlyForTheExactPoppedHeader() {
        BlockImpl tip = mineNext();
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(tip)); // height 2
        engine.popBlock();
        assertEquals(1, engine.height());

        // The exact popped object (hash AND proven nonce): the restore succeeds without
        // re-running the memory-hard PoW — the (hash, nonce) pair was proven at first acceptance
        // and PoW validity is tip-independent.
        assertEquals(ExecutionStatus.SUCCESS, engine.restoreBlock(tip));
        assertEquals(2, engine.height());
        assertEquals(tip.hash(), engine.tipHash());
    }

    @Test
    void restoreReVerifiesPowWhenTheHeaderIsNotThePoppedOne() {
        BlockImpl tip = mineNext();
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(tip)); // height 2
        engine.popBlock();

        // Mutate a hash-committed field (timestamp, kept within the time rules): the header no
        // longer matches the popped entry, so the restore falls back to the full PoW check — and
        // the nonce, mined for the ORIGINAL hash, does not prove the mutated one.
        long ts = tip.timestamp();
        do { tip.timestamp(++ts); } while (tip.verifyNonce(params.powAlgorithm()));
        assertEquals(ExecutionStatus.INVALID_NONCE, engine.restoreBlock(tip));
        assertEquals(1, engine.height());
    }

    @Test
    void restoreReVerifiesPowWhenTheNonceIsNotTheProvenOne() {
        BlockImpl tip = mineNext();
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(tip)); // height 2
        engine.popBlock();

        // The hash preimage does NOT commit the nonce, so wiping it leaves the hash unchanged —
        // but the popped entry stored the proven nonce alongside, so this swapped-nonce block
        // misses the gate and gets the full re-check, which the random nonce fails.
        do { tip.nonce(SHA256Hash.random()); } while (tip.verifyNonce(params.powAlgorithm()));
        assertEquals(ExecutionStatus.INVALID_NONCE, engine.restoreBlock(tip));
        assertEquals(1, engine.height());
    }

    @Test
    void degradedStateIsObservableAndClearable() {
        assertNull(engine.degradedState());
        assertFalse(engine.isDegraded());
        engine.markDegraded("test reason", false);
        assertTrue(engine.isDegraded());
        assertEquals("test reason", engine.degradedState());
        engine.clearDegraded();
        assertNull(engine.degradedState());
        assertFalse(engine.isDegraded());

        // A restart-required mark (torn pop) survives clearDegraded AND cannot be downgraded
        // by a later restore-failure mark — only a restart into boot recovery lifts it.
        engine.markDegraded("torn pop", true);
        engine.markDegraded("restore failure on top", false);
        engine.clearDegraded();
        assertTrue(engine.isDegraded());
    }
}
