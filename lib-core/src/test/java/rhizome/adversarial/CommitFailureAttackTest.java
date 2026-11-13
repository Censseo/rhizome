package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainEngineTestAccess;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.StateAccumulator;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;

/**
 * Attacks on the commit sequence of {@code addBlock}: what a rejected or failing block may leave
 * behind in the peripheral stores. In-memory stores, so the failure injection is deterministic.
 */
class CommitFailureAttackTest {

    private static final int REORG_DEPTH = 10;

    private NetworkParameters params;
    private StateAccumulator accumulator;
    private ChainEngine engine;
    private AtomicLong clock;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3)
            .maxReorgDepth(REORG_DEPTH).build();
        accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(), REORG_DEPTH);
        clock = new AtomicLong(1_000_000L);
        miner = PublicAddress.random();
        engine = bootOver(new InMemoryLedger(), new InMemoryChainStore());
    }

    private ChainEngine bootOver(InMemoryLedger ledger, rhizome.core.blockchain.ChainStore chain) {
        var snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(PublicAddress.of(generateKeyPairTyped().publicKey()), new TransactionAmount(1_000_000L));
        return ChainEngine.boot(params, TestNodeStores.mixing(ledger, chain), snapshot)
            .clock(clock::get).stateAccumulator(accumulator).build();
    }

    /** A coinbase-only block on the tip, state root stamped, mined. */
    private BlockImpl mine() {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        engine.stampStateRoot(b);
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /**
     * PERS-08 — Feed a block with a wrong state root exactly at the state-root prune cadence. The
     * accumulator used to prune root(H - maxReorgDepth) inside the commit attempt, before the engine
     * compared the root, so the REJECTED block still deleted a root the reorg window needs and a
     * later reorg of exactly maxReorgDepth died on "no state root recorded". Roots now prune only
     * once a block stands.
     */
    @Test
    void aStateRootRejectionAtThePruneCadenceDoesNotEatTheReorgWindow() {
        // Prune is first due when height - maxReorgDepth reaches PruneCadence.INTERVAL (32).
        while (engine.height() < 32 + REORG_DEPTH - 1) {
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine()));
        }
        BlockImpl bad = mine();
        bad.stateRoot(SHA256Hash.of(new byte[] {9}));
        bad.nonce(Miner.mineNonce(bad.hash(), bad.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.INVALID_STATE_ROOT, engine.addBlock(bad));

        long tip = engine.height();
        for (int i = 0; i < REORG_DEPTH; i++) {
            ChainEngineTestAccess.popBlock(engine); // throws "no state root recorded" pre-fix
        }
        assertEquals(tip - REORG_DEPTH, engine.height());
        assertNotNull(accumulator.rootAt(engine.height()));
        assertArrayEquals(accumulator.rootAt(engine.height()), engine.stateRoot());
    }

    /**
     * PERS-09 — Make the atomic block append fail (disk error, injected store fault) AFTER the
     * executor has committed the block's box state at that height. The box store used to stay at
     * height H with its journal while the chain sat at H-1, so the next valid block at H died on
     * "already has a journal at height H" and the node never advanced. The commit failure now
     * unwinds the domain commits.
     */
    @Test
    void aFailedAppendAfterTheDomainCommitLeavesNoJournalAndTheNextBlockApplies() {
        var pair = generateKeyPairTyped();
        var sender = PublicAddress.of(pair.publicKey());
        var snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));
        var boxStore = new rhizome.core.box.InMemoryBoxStore();
        var failing = new FailingAppendStore(new InMemoryChainStore());
        accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(), REORG_DEPTH);
        engine = ChainEngine.boot(params, TestNodeStores.mixing(new InMemoryLedger(), failing), snapshot)
            .clock(clock::get).stateAccumulator(accumulator)
            .boxes(new rhizome.core.box.DefaultBoxProcessor(boxStore, params)).build();

        var tx = rhizome.core.transaction.TransactionImpl.builder().from(sender).to(sender)
            .signingKey(pair.publicKey()).amount(new TransactionAmount(5_000)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(0).timestamp(clock.get())
            .kind(rhizome.core.transaction.TransactionKind.BOX_CREATE)
            .data(rhizome.core.box.BoxPayload.encodeCreate(
                java.util.List.of(rhizome.core.box.BoxRegister.string("memory"))))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(pair.privateKey());
        BlockImpl block = withTx(tx);

        byte[] rootBefore = engine.stateRoot();
        failing.failNextAppend = true;
        assertThrows(IllegalStateException.class, () -> engine.addBlock(block));
        assertEquals(1, engine.height());
        assertArrayEquals(rootBefore, engine.stateRoot(), "state root unwound with the domains");
        assertNull(boxStore.get(rhizome.core.box.Box.deriveId(sender, 0)), "box commit unwound");

        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(block), "the same block now applies");
        assertEquals(2, engine.height());
    }

    private BlockImpl withTx(Transaction tx) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        b.addTransaction(tx);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        engine.stampStateRoot(b);
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** A chain store whose next append throws, to inject a failure after the domain commit. */
    private static final class FailingAppendStore implements rhizome.core.blockchain.ChainStore {
        private final rhizome.core.blockchain.ChainStore inner;
        volatile boolean failNextAppend;

        FailingAppendStore(rhizome.core.blockchain.ChainStore inner) { this.inner = inner; }

        @Override public long height() { return inner.height(); }
        @Override public rhizome.core.block.Block blockAt(long h) { return inner.blockAt(h); }
        @Override public void append(rhizome.core.block.Block block) {
            if (failNextAppend) {
                failNextAppend = false;
                throw new IllegalStateException("injected append failure");
            }
            inner.append(block);
        }
        @Override public void pop() { inner.pop(); }
        @Override public boolean hasTransaction(SHA256Hash h) { return inner.hasTransaction(h); }
        @Override public Long transactionHeight(SHA256Hash h) { return inner.transactionHeight(h); }
        @Override public rhizome.core.block.Block uncleAt(SHA256Hash h) { return inner.uncleAt(h); }
    }
}
