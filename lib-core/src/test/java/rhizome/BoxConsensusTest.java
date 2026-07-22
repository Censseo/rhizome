package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.BlockAssembler;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.box.Box;
import rhizome.core.box.BoxPayload;
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

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));

        engine = ChainEngine.init(params, ledger, store, snapshot, null, clock::get, null, null, boxes);
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
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
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

        engine.popBlock(); // undo the update
        assertEquals(5000, engine.box(id).value());
        assertEquals(List.of(BoxRegister.string("v1")), engine.box(id).registers());

        engine.popBlock(); // undo the create
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
