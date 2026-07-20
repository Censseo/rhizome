package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import rhizome.core.common.PowAlgorithm;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/** The consensus block-size cap: a block over the byte limit is refused. */
class BlockSizeLimitTest {

    // Tiny cap: header (116) + coinbase (159) + one transfer (159) = 434 fits under 500;
    // a second transfer (593) does not.
    private NetworkParameters params;
    private ChainEngine engine;
    private AtomicLong clock;
    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3)
            .maxBlockSizeBytes(500).build();
        clock = new AtomicLong(1_000_000L);
        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(), snapshot, null, clock::get);
    }

    private Transaction send(long amount, long nonce) {
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(amount),
            key, new TransactionAmount(0), clock.get(), params.chainId(), nonce);
        t.sign(priv);
        return t;
    }

    private Block nextBlock(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void acceptsBlockUnderTheSizeCap() {
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nextBlock(List.of(send(100, 0)))));
    }

    @Test
    void rejectsBlockOverTheSizeCap() {
        assertEquals(ExecutionStatus.BLOCK_TOO_LARGE,
            engine.addBlock(nextBlock(List.of(send(100, 0), send(100, 1)))));
        assertEquals(1, engine.height()); // nothing appended
    }
}
