package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.BlockProducer;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
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

        var pair = generateKeyPairTyped();
        key = pair.publicKey();
        priv = pair.privateKey();
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        var verifier = new SignatureVerifier();
        engine = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot)
            .clock(clock::get)
            .verifier(verifier)
            .build();
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
    void setVoteRejectsOutOfRangeValues() {
        // audit F1: the producer validates the canonical vote rule (0 or ±paramId) up front, so it
        // can never mine a block every node's consensus gate would reject as INVALID_VOTE.
        producer.setVote(0);
        producer.setVote(1);
        producer.setVote(-2);
        assertThrows(IllegalArgumentException.class, () -> producer.setVote(3));
        assertThrows(IllegalArgumentException.class, () -> producer.setVote(-3));
        assertThrows(IllegalArgumentException.class, () -> producer.setVote(Integer.MIN_VALUE));
    }

    @Test
    void respectsMedianTimePastFloorEvenWithStaleClock() {
        // Freeze the clock in the past; the producer must still pick an acceptable timestamp.
        BlockProducer stale = new BlockProducer(engine, mempool, miner, () -> 0L);
        assertTrue(stale.produce().isPresent());
        assertEquals(2, engine.height());
    }

    @Test
    void aProducedBurningBlockIsAcceptedByTheValidatorItWillFace() {
        // 009 T053 (FR-018): an honestly-assembled candidate at a curve-active, above-target
        // height — carrying real fee flow, so the dry run genuinely burns — must satisfy the
        // exact supply identity its own validator enforces. The producer stamps supply from the
        // dry run (the only execution that knows the pool); this test recomputes the expected
        // burn independently from the flows it pooled and demands the committed figure match.
        NetworkParameters curveParams = CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        NetworkParameters saved = params;
        params = curveParams;
        try {
            LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
            snapshot.put(sender, new TransactionAmount(20_000_000L));
            var verifier = new SignatureVerifier();
            engine = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot)
                .clock(clock::get)
                .verifier(verifier)
                .build();
            mempool = new MemPool(params, verifier, engine, 1000);
            producer = new BlockProducer(engine, mempool, miner, () -> clock.addAndGet(90_000));

            long parentSupply = engine.headerAt(1).supply();
            assertEquals(20_000_000L, parentSupply);
            long minted = rhizome.core.blockchain.Issuance.minted(params, 2, parentSupply,
                engine.difficulty(), List.of());
            long debt = rhizome.core.blockchain.Burn.debt(params, 2, parentSupply, minted);
            long pool = 4_000L;
            long expectedBurned = rhizome.core.blockchain.Burn.burned(params, 2, pool, debt);
            assertTrue(expectedBurned > 0, "sanity: the flow really burns under this profile");

            Transaction feeTx = Transaction.of(sender, PublicAddress.random(),
                new TransactionAmount(0), key, new TransactionAmount(pool), clock.get(),
                params.chainId(), 0);
            feeTx.sign(priv);
            assertEquals(ExecutionStatus.SUCCESS, mempool.addTransaction(feeTx));

            Optional<Block> produced = producer.produce();
            assertTrue(produced.isPresent(),
                "the produced burning block must be accepted by the validator it will face");
            assertEquals(2, engine.height());
            assertEquals(parentSupply + minted - expectedBurned,
                engine.headerAt(2).supply(),
                "the committed supply is exactly parent + minted - burned from the one dry run");
            assertEquals(minted + pool - expectedBurned, engine.confirmedBalance(miner),
                "the miner was paid the subsidy and the kept half of the pool");
            assertEquals(20_000_000L - pool, engine.confirmedBalance(sender),
                "the sender paid exactly its fee");
        } finally {
            params = saved;
        }
    }
}
