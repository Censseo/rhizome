package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static rhizome.core.common.Crypto.generateKeyPair;

import java.math.BigInteger;
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
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

/**
 * End-to-end engine test: mines real blocks (Pufferfish2 at low difficulty),
 * exercises every rejection path, pops blocks, and rebuilds engine state from
 * an existing store.
 */
class ChainEngineTest {

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private InMemoryChainStore store;
    private AtomicLong clock;
    private ChainEngine engine;

    private PublicKey senderKey;
    private PrivateKey senderPrivate;
    private PublicAddress sender;
    private PublicAddress recipient;
    private PublicAddress miner;

    private static final long START = 1_000_000_000L; // ms

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet();
        ledger = new InMemoryLedger();
        store = new InMemoryChainStore();
        clock = new AtomicLong(START);

        var pair = generateKeyPair();
        senderKey = PublicKey.of(pair.getPublic());
        senderPrivate = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(senderKey);
        recipient = PublicAddress.random();
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        engine = ChainEngine.init(params, ledger, store, snapshot, null, clock::get);
    }

    private Transaction send(long amount, long fee, long nonce) {
        Transaction t = Transaction.of(sender, recipient, new TransactionAmount(amount), senderKey,
            new TransactionAmount(fee), clock.get(), params.chainId(), nonce);
        t.sign(senderPrivate);
        return t;
    }

    /** Builds a fully valid next block for the engine's current tip. */
    private Block nextBlock(List<Transaction> transactions) {
        long height = engine.height() + 1;
        var b = BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        transactions.forEach(b::addTransaction);

        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        ((BlockImpl) b).merkleRoot(tree.getRootHash());
        ((BlockImpl) b).nonce(Miner.mineNonce(b.hash(), ((BlockImpl) b).difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void minesAndAppliesBlocks() {
        assertEquals(1, engine.height());

        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nextBlock(List.of(send(100_000, 500, 0)))));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nextBlock(List.of(send(50_000, 500, 1)))));

        assertEquals(3, engine.height());
        assertEquals(1_000_000L - 150_000L - 1_000L, ledger.getWalletValue(sender).amount());
        assertEquals(150_000L, ledger.getWalletValue(recipient).amount());
        assertEquals(2 * params.miningReward(2) + 1_000L, ledger.getWalletValue(miner).amount());
        assertEquals(2, engine.nextNonce(sender));
        assertEquals(BigInteger.TWO.pow(params.genesisDifficulty()).multiply(BigInteger.TWO), engine.totalWork());
    }

    @Test
    void rejectsBrokenChaining() {
        Block ok = nextBlock(List.of());

        var wrongId = (BlockImpl) rhizome.core.block.Block.of(ok);
        wrongId.id(99);
        assertEquals(ExecutionStatus.INVALID_BLOCK_ID, engine.addBlock(wrongId));

        var wrongPrev = (BlockImpl) rhizome.core.block.Block.of(ok);
        wrongPrev.lastBlockHash(rhizome.core.crypto.SHA256Hash.random());
        assertEquals(ExecutionStatus.INVALID_LASTBLOCK_HASH, engine.addBlock(wrongPrev));

        var wrongDifficulty = (BlockImpl) rhizome.core.block.Block.of(ok);
        wrongDifficulty.difficulty(engine.difficulty() + 1);
        assertEquals(ExecutionStatus.INVALID_DIFFICULTY, engine.addBlock(wrongDifficulty));
    }

    @Test
    void rejectsBadTimestamps() {
        Block stale = nextBlock(List.of());
        ((BlockImpl) stale).timestamp(0); // <= median time past (genesis ts 0)
        assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_TOO_OLD, engine.addBlock(stale));

        Block future = nextBlock(List.of());
        ((BlockImpl) future).timestamp(clock.get() + params.maxFutureBlockTimeSec() * 1000L + 60_000);
        assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_IN_FUTURE, engine.addBlock(future));
    }

    @Test
    void rejectsBadMerkleAndBadPow() {
        Block badMerkle = nextBlock(List.of(send(100, 0, 0)));
        ((BlockImpl) badMerkle).merkleRoot(rhizome.core.crypto.SHA256Hash.random());
        assertEquals(ExecutionStatus.INVALID_MERKLE_ROOT, engine.addBlock(badMerkle));

        BlockImpl badPow = (BlockImpl) nextBlock(List.of());
        // Perturb the timestamp after mining so the header hash changes and the nonce
        // no longer satisfies PoW. At low difficulty a single bump can still pass by
        // luck, so keep bumping until the nonce is provably invalid (deterministic).
        while (badPow.verifyNonce(params.powAlgorithm())) {
            badPow.timestamp(badPow.timestamp() + 1);
        }
        assertEquals(ExecutionStatus.INVALID_NONCE, engine.addBlock(badPow));
    }

    @Test
    void enforcesAccountNonceSequence() {
        // Gap: nonce 5 while 0 expected.
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE,
            engine.addBlock(nextBlock(List.of(send(100, 0, 5)))));

        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nextBlock(List.of(send(100, 0, 0)))));

        // Reuse: nonce 0 again after it was consumed.
        assertEquals(ExecutionStatus.INVALID_TRANSACTION_NONCE,
            engine.addBlock(nextBlock(List.of(send(100, 0, 0)))));

        // In-block sequence 1,2 accepted.
        assertEquals(ExecutionStatus.SUCCESS,
            engine.addBlock(nextBlock(List.of(send(100, 0, 1), send(100, 0, 2)))));
    }

    @Test
    void popRestoresLedgerNoncesDifficultyAndWork() {
        BigInteger workBefore = engine.totalWork();
        long senderBefore = ledger.getWalletValue(sender).amount();

        Block block = nextBlock(List.of(send(100_000, 500, 0)));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(block));
        assertNotEquals(senderBefore, ledger.getWalletValue(sender).amount());

        engine.popBlock();

        assertEquals(1, engine.height());
        assertEquals(senderBefore, ledger.getWalletValue(sender).amount());
        assertEquals(0, engine.nextNonce(sender));
        assertEquals(workBefore, engine.totalWork());

        // The same block chains again after the pop (reorg replay).
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(block));
        assertThrows(IllegalStateException.class, () -> { engine.popBlock(); engine.popBlock(); });
    }

    @Test
    void difficultyRetargetsFromTimestamps() {
        // SHA-256 + tiny lookback so the test mines instantly and hits a boundary.
        NetworkParameters fast = params.toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256)
            .difficultyLookback(3)
            .build();
        InMemoryLedger l = new InMemoryLedger();
        InMemoryChainStore s = new InMemoryChainStore();
        LedgerSnapshot snap = new LedgerSnapshot("test", 0, fast.chainId());
        // Clock aligned with the genesis timestamp (0), so the first retarget
        // window measures the 1s block cadence, not the genesis offset.
        AtomicLong fastClock = new AtomicLong(0);
        ChainEngine e = ChainEngine.init(fast, l, s, snap, null, fastClock::get);

        int initialDifficulty = e.difficulty();
        PublicAddress m = PublicAddress.random();

        // Mine to the retarget boundary with 1s blocks (90x too fast).
        while (e.height() < fast.difficultyLookback()) {
            long h = e.height() + 1;
            var b = BlockImpl.builder()
                .id((int) h)
                .timestamp(fastClock.addAndGet(1000))
                .difficulty(e.difficulty())
                .lastBlockHash(e.tipHash())
                .build();
            b.addTransaction(Transaction.of(m, new TransactionAmount(fast.miningReward(h))));
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            ((BlockImpl) b).merkleRoot(tree.getRootHash());
            ((BlockImpl) b).nonce(Miner.mineNonce(b.hash(), e.difficulty(), fast.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, e.addBlock(b));
        }

        assertEquals(initialDifficulty + rhizome.core.blockchain.DifficultyAdjustment.MAX_STEP_BITS,
            e.difficulty());
    }

    @Test
    void reinitFromExistingStoreRebuildsState() {
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nextBlock(List.of(send(100_000, 500, 0)))));

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));
        ChainEngine rebooted = ChainEngine.init(params, ledger, store, snapshot, null, clock::get);

        assertEquals(engine.height(), rebooted.height());
        assertEquals(engine.tipHash(), rebooted.tipHash());
        assertEquals(engine.difficulty(), rebooted.difficulty());
        assertEquals(engine.totalWork(), rebooted.totalWork());
        assertEquals(1, rebooted.nextNonce(sender));
    }
}
