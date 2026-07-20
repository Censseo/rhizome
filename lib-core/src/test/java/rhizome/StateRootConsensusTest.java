package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.generateKeyPair;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.common.Utils;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.SparseMerkleTree;
import rhizome.core.state.StateAccumulator;
import rhizome.core.state.StateKeys;
import rhizome.core.state.StateProof;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenPayload;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

/**
 * The authenticated state root end to end: the producer stamps the header with the root
 * it produces, every node re-derives and validates it, a tampered root is rejected with
 * full rollback, a reorg moves the root back, and a light client proves individual ledger,
 * box and token entries against the committed root.
 */
class StateRootConsensusTest {

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private DefaultBoxProcessor boxes;
    private DefaultTokenProcessor tokens;
    private StateAccumulator accumulator;
    private ChainEngine engine;
    private AtomicLong clock;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress bob;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        ledger = new InMemoryLedger();
        boxes = new DefaultBoxProcessor(new InMemoryBoxStore(), params);
        tokens = new DefaultTokenProcessor(new InMemoryTokenStore(), params);
        accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(),
            params.maxReorgDepth());
        clock = new AtomicLong(1_000_000L);

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        bob = PublicAddress.random();
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));

        engine = ChainEngine.init(params, ledger, new InMemoryChainStore(), snapshot, null,
            clock::get, null, null, boxes, tokens, accumulator);
    }

    private Transaction transfer(long amount, long nonce) {
        Transaction t = Transaction.of(sender, bob, new TransactionAmount(amount), key,
            new TransactionAmount(0), clock.get(), params.chainId(), nonce);
        t.sign(priv);
        return t;
    }

    private Transaction boxCreate(long value, long nonce, BoxRegister... regs) {
        var tx = TransactionImpl.builder().from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).timestamp(clock.get())
            .kind(TransactionKind.BOX_CREATE).data(BoxPayload.encodeCreate(List.of(regs)))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(priv);
        return tx;
    }

    private Transaction tokenMint(long amount, long nonce) {
        var tx = TransactionImpl.builder().from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).timestamp(clock.get())
            .kind(TransactionKind.TOKEN_MINT).data(TokenPayload.encodeMint(amount, 2, "PNDA", "Panda"))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(priv);
        return tx;
    }

    /** Builds a block, stamps its state root (as the producer does), mines it, and adds it. */
    private BlockImpl mine(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        engine.stampStateRoot(b);
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    private byte[] ledgerValue(long balance) {
        return Utils.longToBytes(balance);
    }

    @Test
    void producerStampsAndNodeValidatesTheStateRoot() {
        byte[] genesisRoot = engine.stateRoot();
        assertNotNull(genesisRoot);

        BlockImpl b = mine(List.of(transfer(1_000, 0)));
        assertFalse(b.stateRoot().equals(SHA256Hash.empty()), "producer stamped a state root");
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        // The committed root matches the header and advanced from genesis.
        assertArrayEquals(engine.stateRoot(), b.stateRoot().toBytes());
        assertFalse(java.util.Arrays.equals(genesisRoot, engine.stateRoot()));
    }

    @Test
    void tamperedStateRootIsRejectedWithRollback() {
        BlockImpl b = mine(List.of(transfer(1_000, 0)));
        long senderBefore = ledger.getWalletValue(sender).amount();
        // Corrupt the committed root (keep it non-empty so it is actually validated).
        byte[] bogus = new byte[32];
        bogus[0] = (byte) 0xAB;
        b.stateRoot(SHA256Hash.of(bogus));
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));

        assertEquals(ExecutionStatus.INVALID_STATE_ROOT, engine.addBlock(b));
        // Fully rolled back: height, ledger and the accumulator are untouched.
        assertEquals(1, engine.height());
        assertEquals(senderBefore, ledger.getWalletValue(sender).amount());
    }

    @Test
    void reorgMovesTheStateRootBack() {
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(List.of(transfer(1_000, 0)))));
        byte[] rootAt2 = engine.stateRoot();
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(List.of(transfer(500, 1)))));
        assertFalse(java.util.Arrays.equals(rootAt2, engine.stateRoot()));

        engine.popBlock();
        assertArrayEquals(rootAt2, engine.stateRoot()); // root rewound with the block
    }

    @Test
    void lightClientProvesLedgerBoxAndTokenAgainstRoot() {
        byte[] boxId = rhizome.core.box.Box.deriveId(sender, 1);
        byte[] tokenId = TokenMeta.deriveId(sender, 2);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(List.of(
            transfer(1_000, 0),
            boxCreate(5_000, 1, BoxRegister.string("memory")),
            tokenMint(1_000_000, 2)))));

        byte[] root = engine.stateRoot();

        // Ledger: bob's balance is provable.
        StateProof ledgerProof = engine.stateProof(StateKeys.LEDGER, bob.toBytes());
        assertNotNull(ledgerProof);
        assertArrayEquals(StateKeys.valueHash(ledgerValue(1_000)), ledgerProof.valueHash());
        assertTrue(SparseMerkleTree.verify(root, StateKeys.key(StateKeys.LEDGER, bob.toBytes()),
            ledgerProof.valueHash(), ledgerProof));

        // Box: the box's serialized form is provable.
        byte[] boxBytes = engine.box(boxId).serialize();
        StateProof boxProof = engine.stateProof(StateKeys.BOX, boxId);
        assertTrue(SparseMerkleTree.verify(root, StateKeys.key(StateKeys.BOX, boxId),
            StateKeys.valueHash(boxBytes), boxProof));

        // Token metadata is provable.
        byte[] metaBytes = engine.tokenMeta(tokenId).serialize();
        StateProof tokenProof = engine.stateProof(StateKeys.TOKEN_META, tokenId);
        assertTrue(SparseMerkleTree.verify(root, StateKeys.key(StateKeys.TOKEN_META, tokenId),
            StateKeys.valueHash(metaBytes), tokenProof));

        // A wrong value must not verify against the honest proof.
        assertFalse(SparseMerkleTree.verify(root, StateKeys.key(StateKeys.LEDGER, bob.toBytes()),
            StateKeys.valueHash(ledgerValue(999)), ledgerProof));
    }

    @Test
    void accountNonceIsCommittedAndProvableAgainstRoot() {
        // Three account transactions from the sender (nonces 0,1,2) advance its next nonce to 3.
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(List.of(transfer(1_000, 0)))));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(List.of(transfer(1_000, 1)))));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(List.of(transfer(1_000, 2)))));
        assertEquals(3L, engine.nextNonce(sender));

        byte[] root = engine.stateRoot();
        // The committed next-expected nonce (3, big-endian 8 bytes) is provable to a light client.
        byte[] nonceValue = Utils.longToBytes(3L);
        StateProof nonceProof = engine.stateProof(StateKeys.ACCOUNT_NONCE, sender.toBytes());
        assertNotNull(nonceProof);
        assertArrayEquals(StateKeys.valueHash(nonceValue), nonceProof.valueHash());
        assertTrue(SparseMerkleTree.verify(root, StateKeys.key(StateKeys.ACCOUNT_NONCE, sender.toBytes()),
            nonceProof.valueHash(), nonceProof));

        // A different nonce value must not verify against the honest proof.
        assertFalse(SparseMerkleTree.verify(root, StateKeys.key(StateKeys.ACCOUNT_NONCE, sender.toBytes()),
            StateKeys.valueHash(Utils.longToBytes(2L)), nonceProof));

        // A pop rewinds the nonce leaf with the block: next nonce and the committed value both drop.
        engine.popBlock();
        assertEquals(2L, engine.nextNonce(sender));
        StateProof after = engine.stateProof(StateKeys.ACCOUNT_NONCE, sender.toBytes());
        assertArrayEquals(StateKeys.valueHash(Utils.longToBytes(2L)), after.valueHash());
    }
}
