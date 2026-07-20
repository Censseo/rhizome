package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.generateKeyPair;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.BlockProducer;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

class BlockProducerTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private MemPool mempool;
    private BlockProducer producer;
    private AtomicLong clock;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        clock = new AtomicLong(1000);

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        var verifier = new SignatureVerifier();
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, clock::get, verifier);
        mempool = new MemPool(params, verifier, engine, 1000);
        // Advance the clock each read so successive blocks get increasing timestamps.
        producer = new BlockProducer(engine, mempool, miner, () -> clock.addAndGet(90_000));
    }

    private Transaction send(long amount, long nonce) {
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(amount),
            key, new TransactionAmount(0), 2000L + nonce, params.chainId(), nonce);
        t.sign(priv);
        return t;
    }

    @Test
    void producesEmptyBlockWithCoinbase() {
        Optional<Block> produced = producer.produce();
        assertTrue(produced.isPresent());
        assertEquals(2, engine.height());
        Block block = produced.get();
        assertEquals(1, block.transactions().size()); // coinbase only
        assertEquals(params.miningReward(2), engine.confirmedBalance(miner));
        assertTrue(((BlockImpl) block).verifyNonce(params.powAlgorithm()));
    }

    @Test
    void drainsMempoolIntoBlockAndPurges() {
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(100_000, 0)));
        assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(send(50_000, 1)));
        assertEquals(2, mempool.size());

        Block block = producer.produce().orElseThrow();
        assertEquals(3, block.transactions().size()); // coinbase + 2
        assertEquals(0, mempool.size());               // purged
        assertEquals(2, engine.nextNonce(sender));
        assertEquals(1_000_000L - 150_000L, engine.confirmedBalance(sender));
    }

    @Test
    void producesSeveralBlocksInARow() {
        for (int i = 0; i < 4; i++) {
            assertTrue(producer.produce().isPresent());
        }
        assertEquals(5, engine.height());
    }

    @Test
    void backgroundLoopProducesThenStops() throws InterruptedException {
        producer.start();
        assertTrue(producer.isRunning());
        long deadline = System.currentTimeMillis() + 5000;
        while (engine.height() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        producer.stop();
        assertFalse(producer.isRunning());
        assertTrue(engine.height() >= 3, "expected the loop to mine a few blocks");
    }

    @Test
    void respectsMedianTimePastFloorEvenWithStaleClock() {
        // Freeze the clock in the past; the producer must still pick an acceptable timestamp.
        BlockProducer stale = new BlockProducer(engine, mempool, miner, () -> 0L);
        assertTrue(stale.produce().isPresent());
        assertEquals(2, engine.height());
    }
}
