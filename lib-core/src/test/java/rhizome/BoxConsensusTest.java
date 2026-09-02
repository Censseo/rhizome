package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.BlockAssembler;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainEngineTestAccess;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.box.Box;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxProcessor;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.StateAccumulator;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

/**
 * Data boxes through consensus: a ChainEngine wired with the box processor runs
 * BOX_CREATE/UPDATE/SPEND through addBlock, reverts them exactly on pop, and mints
 * rent collection during block assembly. End-to-end through the real engine
 * (nonces, PoW, per-block session, ledger).
 */
class BoxConsensusTest {

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private InMemoryChainStore store;
    private InMemoryBoxStore boxStore;
    private DefaultBoxProcessor boxes;
    private ChainEngine engine;
    private MemPool mempool;
    private AtomicLong clock;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3)
            .storagePeriodBlocks(3).storageFeeFactor(1).minValuePerByte(1).build();
        ledger = new InMemoryLedger();
        store = new InMemoryChainStore();
        boxStore = new InMemoryBoxStore();
        boxes = new DefaultBoxProcessor(boxStore, params);
        clock = new AtomicLong(1_000_000L);

        var pair = generateKeyPairTyped();
        key = pair.publicKey();
        priv = pair.privateKey();
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));

        engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .boxes(boxes)
            .build();
        var verifier = new rhizome.core.blockchain.SignatureVerifier();
        mempool = new MemPool(params, verifier, engine, 1024);
    }

    private Transaction boxTx(TransactionKind kind, byte[] data, long value, long nonce) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).timestamp(clock.get())
            .kind(kind).data(data).gasLimit(0).gasPrice(0)
            .build();
        t.sign(priv);
        return t;
    }

    private ExecutionStatus mine(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return engine.addBlock(b);
    }

    private ExecutionStatus mineEmpty() {
        return mine(List.of());
    }

    @Test
    void createUpdateSpendThroughConsensus() {
        byte[] id = Box.deriveId(sender, 0);
        long start = ledger.getWalletValue(sender).amount();

        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(boxTx(TransactionKind.BOX_CREATE,
            BoxPayload.encodeCreate(List.of(BoxRegister.string("agent-memory"))), 5000, 0))));
        Box created = engine.box(id);
        assertNotNull(created);
        assertEquals(5000, created.value());
        assertEquals(start - 5000, ledger.getWalletValue(sender).amount());

        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(boxTx(TransactionKind.BOX_UPDATE,
            BoxPayload.encodeUpdate(id, List.of(BoxRegister.i64(42))), 0, 1))));
        assertEquals(List.of(BoxRegister.i64(42)), engine.box(id).registers());

        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(boxTx(TransactionKind.BOX_SPEND,
            BoxPayload.encodeTarget(id), 0, 2))));
        assertNull(engine.box(id));
        assertEquals(start, ledger.getWalletValue(sender).amount()); // value fully returned
    }

    @Test
    void popRevertsBoxStateExactly() {
        byte[] id = Box.deriveId(sender, 0);
        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(boxTx(TransactionKind.BOX_CREATE,
            BoxPayload.encodeCreate(List.of(BoxRegister.string("v1"))), 5000, 0))));
        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(boxTx(TransactionKind.BOX_UPDATE,
            BoxPayload.encodeUpdate(id, List.of(BoxRegister.string("v2"))), 1000, 1))));
        assertEquals(6000, engine.box(id).value());

        ChainEngineTestAccess.popBlock(engine); // undo the update
        assertEquals(5000, engine.box(id).value());
        assertEquals(List.of(BoxRegister.string("v1")), engine.box(id).registers());

        ChainEngineTestAccess.popBlock(engine); // undo the create
        assertNull(engine.box(id));
    }

    @Test
    void blockAssemblerMintsRentCollection() {
        byte[] id = Box.deriveId(sender, 0);
        // Create a box (rentPaidHeight = 2), value above the floor so a rent charge is partial.
        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(boxTx(TransactionKind.BOX_CREATE,
            BoxPayload.encodeCreate(List.of()), 5000, 0))));
        long size = engine.box(id).serializedSize();
        long minerBefore = ledger.getWalletValue(miner).amount();

        // Advance to a height where the box is collectable (storagePeriod = 3): heights 3, 4.
        assertEquals(ExecutionStatus.SUCCESS, mineEmpty()); // height 3
        assertEquals(ExecutionStatus.SUCCESS, mineEmpty()); // height 4

        // Assemble height 5 through the real producer path: it must mint a BOX_COLLECT.
        Block candidate = BlockAssembler.assemble(engine, mempool, miner, clock.addAndGet(1000));
        boolean hasCollect = candidate.transactions().stream()
            .anyMatch(t -> ((TransactionImpl) t).kind() == TransactionKind.BOX_COLLECT);
        assertTrue(hasCollect, "assembler should mint a rent collection for the expired box");

        var b = (BlockImpl) candidate;
        engine.stampStateRoot(b); // producer order: assemble -> dry-run stamp (exact supply) -> mine
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));

        // Rent charged: box value reduced by its size, rent paid to the miner.
        Box charged = engine.box(id);
        assertNotNull(charged);
        assertEquals(5000 - size, charged.value());
        assertEquals(5L, charged.rentPaidHeight());
        long minerReward = params.miningReward(3) + params.miningReward(4) + params.miningReward(5);
        assertEquals(minerBefore + minerReward + size, ledger.getWalletValue(miner).amount());
    }

    /**
     * Security regression (audit F7): {@code Executor.rollbackBlock} consumes exactly one box
     * receipt per box transaction to reverse the block's ledger deltas. RAM-only receipts meant a
     * restart followed by a reorg of a box-carrying block reversed against an empty receipt list —
     * crashing or corrupting the ledger mid-pop. The receipts are now persisted through the box
     * store, so a fresh processor over the same store (a stand-in for the restarted node) recovers
     * them and the pop still reverses the ledger exactly.
     */
    @Test
    void boxReceiptsSurviveAProcessorRestartAndPopStaysExact() {
        byte[] id = Box.deriveId(sender, 0);
        long start = ledger.getWalletValue(sender).amount();
        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(boxTx(TransactionKind.BOX_CREATE,
            BoxPayload.encodeCreate(List.of(BoxRegister.string("v1"))), 5000, 0))));
        long height = engine.height();
        List<BoxProcessor.BoxReceipt> committed = boxes.receipts(height);
        assertEquals(1, committed.size());
        assertEquals(5000, committed.get(0).debitFrom());

        // The restart: a new processor over the SAME store has an empty RAM cache but must recover
        // the committed receipts from the durable copy.
        DefaultBoxProcessor restarted = new DefaultBoxProcessor(boxStore, params);
        assertEquals(committed, restarted.receipts(height));

        // A pop through the restarted processor must reverse the block's ledger delta exactly —
        // the scenario that corrupted the ledger when receipts were RAM-only.
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));
        ChainEngine restartedEngine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .boxes(restarted)
            .build();
        ChainEngineTestAccess.popBlock(restartedEngine);
        assertNull(restartedEngine.box(id));
        assertEquals(start, ledger.getWalletValue(sender).amount());
    }

    /**
     * Security regression: on a block-producing node every mining round runs
     * {@code stampStateRoot}, which executes a candidate at tip+1 and rolls it back. When
     * retention pruning was keyed on processor commits, that phantom commit moved the watermark
     * one height past the chain tip and pruned the oldest in-window height's receipts — the RAM
     * copy AND the durable one. The legal max-depth reorg that later popped that height died on
     * {@code Executor.rollbackBlock}'s missing-receipts guard, and with the durable copy gone no
     * restart could heal it: the node never adopted a heavier branch again. The dry run must
     * leave the reorg window byte-for-byte intact.
     */
    @Test
    void aStampStateRootDryRunKeepsTheReorgWindowIntact() {
        var deepParams = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3)
            .storagePeriodBlocks(3).storageFeeFactor(1).minValuePerByte(1)
            .maxReorgDepth(3).build();
        InMemoryLedger deepLedger = new InMemoryLedger();
        var deepBoxes = new DefaultBoxProcessor(new InMemoryBoxStore(), deepParams);
        AtomicLong deepClock = new AtomicLong(1_000_000L);
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, deepParams.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));
        var accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(),
            deepParams.maxReorgDepth());
        ChainEngine deepEngine = ChainEngine.boot(
                deepParams,
                TestNodeStores.mixing(deepLedger, new InMemoryChainStore()),
                snapshot)
            .clock(deepClock::get)
            .boxes(deepBoxes)
            .stateAccumulator(accumulator)
            .build();

        // Assemble exactly like the producer: stamp the root BEFORE mining the nonce.
        java.util.function.Function<List<Transaction>, ExecutionStatus> mineStamped = txs -> {
            long height = deepEngine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder()
                .id((int) height).timestamp(deepClock.addAndGet(1000))
                .difficulty(deepEngine.difficulty()).lastBlockHash(deepEngine.tipHash())
                .supply(SupplyStamp.next(deepEngine, height, deepEngine.difficulty())).build();
            b.addTransaction(Transaction.of(miner, new TransactionAmount(deepParams.miningReward(height))));
            txs.forEach(b::addTransaction);
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            deepEngine.stampStateRoot(b);
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), deepParams.powAlgorithm()));
            return deepEngine.addBlock(b);
        };

        // A box-carrying block at height 2, then empties to tip 4: with retainDepth 3 the window
        // is (1, 4], so height 2 is exactly the oldest height a max-depth reorg can pop.
        byte[] id = Box.deriveId(sender, 0);
        long start = deepLedger.getWalletValue(sender).amount();
        assertEquals(ExecutionStatus.SUCCESS, mineStamped.apply(List.of(boxTx(
            TransactionKind.BOX_CREATE, BoxPayload.encodeCreate(List.of(BoxRegister.string("v1"))),
            5000, 0))));
        assertEquals(ExecutionStatus.SUCCESS, mineStamped.apply(List.of())); // height 3
        assertEquals(ExecutionStatus.SUCCESS, mineStamped.apply(List.of())); // height 4

        // The producer's dry run over a candidate at 5 (never submitted): in the regression this
        // pruned height 2's receipts from RAM and disk alike.
        long height = deepEngine.height() + 1;
        var candidate = (BlockImpl) BlockImpl.builder()
            .id((int) height).timestamp(deepClock.addAndGet(1000))
            .difficulty(deepEngine.difficulty()).lastBlockHash(deepEngine.tipHash())
            .supply(SupplyStamp.next(deepEngine, height, deepEngine.difficulty())).build();
        candidate.addTransaction(
            Transaction.of(miner, new TransactionAmount(deepParams.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(candidate.transactions());
        candidate.merkleRoot(tree.getRootHash());
        deepEngine.stampStateRoot(candidate);

        assertEquals(1, deepBoxes.receipts(2).size(),
            "the dry run must not prune the oldest in-window height's receipts");

        // The max-depth reorg: pops 4, 3 and the box-carrying 2 — exactly the depth the retained
        // receipts exist for. Pre-fix this threw on the deepest pop and wedged the node.
        ChainEngineTestAccess.popBlock(deepEngine);
        ChainEngineTestAccess.popBlock(deepEngine);
        ChainEngineTestAccess.popBlock(deepEngine);
        assertNull(deepEngine.box(id), "the create is reverted");
        assertEquals(start, deepLedger.getWalletValue(sender).amount(),
            "the ledger reversal stays exact through the deepest in-window pop");
    }

    /**
     * Security regression: a self-authorized BOX_COLLECT must never name a funded sender or carry a
     * fee. BOX_COLLECT skips signature verification (signatureValid() == true) and the account-nonce
     * rule, so its only gate on `from` is PublicAddress.of(signingKey).equals(from) — which an
     * attacker satisfies with the victim's PUBLIC key (no private key, no signature). Without the
     * guard, applyBox would then debit the fee from that victim into the miner's coinbase, letting
     * any block producer drain an arbitrary account. The block must be rejected and the victim's
     * balance left untouched.
     */
    @Test
    void maliciousBoxCollectCannotDrainAnArbitraryWallet() {
        long victimBefore = ledger.getWalletValue(sender).amount();
        assertTrue(victimBefore > 0);

        // from = victim, signingKey = victim's PUBLIC key, fee = victim's whole balance. Unsigned.
        Transaction theft = TransactionImpl.builder()
            .kind(TransactionKind.BOX_COLLECT)
            .from(sender)                 // the victim being debited
            .to(miner)                    // where released box value would land
            .signingKey(key)              // victim's public key — public data, not a signature
            .amount(new TransactionAmount(0))
            .fee(new TransactionAmount(victimBefore))
            .chainId(params.chainId()).nonce(0).timestamp(clock.get())
            .data(BoxPayload.encodeTarget(Box.deriveId(sender, 0)))
            .gasLimit(0).gasPrice(0)
            .build();
        // deliberately NOT signed — BOX_COLLECT is self-authorized

        assertEquals(ExecutionStatus.BOX_PAYLOAD_INVALID, mine(List.of(theft)));
        // The block was rejected; the victim keeps every coin.
        assertEquals(victimBefore, ledger.getWalletValue(sender).amount());
    }
}
