package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenPayload;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

/**
 * Native tokens through consensus: a ChainEngine wired with the token processor runs a
 * mint, a transfer and a burn through addBlock, and reverts them exactly on pop.
 */
class TokenConsensusTest {

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private DefaultTokenProcessor tokens;
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
        tokens = new DefaultTokenProcessor(new InMemoryTokenStore(), params);
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
            clock::get, null, null, null, tokens);
    }

    private Transaction tokenTx(TransactionKind kind, PublicAddress to, byte[] data, long nonce) {
        var tx = TransactionImpl.builder()
            .from(sender).to(to).signingKey(key)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).timestamp(clock.get())
            .kind(kind).data(data).gasLimit(0).gasPrice(0).build();
        tx.sign(priv);
        return tx;
    }

    private ExecutionStatus mine(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return engine.addBlock(b);
    }

    @Test
    void mintTransferBurnThenPop() {
        byte[] id = TokenMeta.deriveId(sender, 0);

        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(tokenTx(TransactionKind.TOKEN_MINT, sender,
            TokenPayload.encodeMint(1_000, 2, "PNDA", "Panda"), 0))));
        assertNotNull(engine.tokenMeta(id));
        assertEquals(1_000, engine.tokenBalance(id, sender.toBytes()));

        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(tokenTx(TransactionKind.TOKEN_TRANSFER, bob,
            TokenPayload.encodeAmount(id, 400), 1))));
        assertEquals(400, engine.tokenBalance(id, bob.toBytes()));

        assertEquals(ExecutionStatus.SUCCESS, mine(List.of(tokenTx(TransactionKind.TOKEN_BURN, sender,
            TokenPayload.encodeAmount(id, 100), 2))));
        assertEquals(500, engine.tokenBalance(id, sender.toBytes()));
        assertEquals(900, engine.tokenMeta(id).totalSupply());

        engine.popBlock(); // undo burn
        assertEquals(600, engine.tokenBalance(id, sender.toBytes()));
        assertEquals(1_000, engine.tokenMeta(id).totalSupply());

        engine.popBlock(); // undo transfer
        assertEquals(0, engine.tokenBalance(id, bob.toBytes()));
        assertEquals(1_000, engine.tokenBalance(id, sender.toBytes()));

        engine.popBlock(); // undo mint
        assertNull(engine.tokenMeta(id));
    }
}
