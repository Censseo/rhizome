package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.HeaderChain;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * The stateless header validator: a genuine peer branch validates and reports its
 * cumulative work; every way a hostile peer can corrupt a header run is rejected at
 * the offending height — without ever touching a body.
 */
class HeaderChainTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private AtomicLong clock;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4).maxDifficulty(64)
            .difficultyLookback(4).desiredBlockTimeSec(1).minBlockTimeSec(0)
            .maxFutureBlockTimeSec(3600).build();
        clock = new AtomicLong(1_000_000L);
        miner = PublicAddress.random();
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            new LedgerSnapshot("t", 0, params.chainId()), null, clock::get);
    }

    /** Mines the next block on the engine's tip and applies it. */
    private void mineOnEngine() {
        long h = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
    }

    /** Builds and PoW-mines a standalone header (any timestamp/uncles) for adversarial cases. */
    private BlockHeader mineHeader(long id, SHA256Hash parent, int difficulty, long ts, List<UncleRef> uncles) {
        var b = (BlockImpl) BlockImpl.builder().id((int) id).timestamp(ts).difficulty(difficulty)
            .lastBlockHash(parent).merkleRoot(SHA256Hash.random())
            .uncles(new ArrayList<>(uncles)).build();
        b.nonce(Miner.mineNonce(b.hash(), difficulty, params.powAlgorithm()));
        return BlockHeader.of(b);
    }

    private List<BlockHeader> headers(long from, long to) {
        List<BlockHeader> out = new ArrayList<>();
        for (long h = from; h <= to; h++) {
            out.add(engine.headerAt(h));
        }
        return out;
    }

    @Test
    void validBranchValidatesAndReportsCumulativeWork() {
        for (int i = 0; i < 11; i++) {
            mineOnEngine(); // heights 2..12, crossing retarget boundaries at 4, 8, 12
        }
        long fork = 6;
        List<BlockHeader> candidates = headers(fork + 1, 12);
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, fork, candidates, clock.get());
        assertTrue(r.valid(), "genuine branch must validate, got " + r.rejection() + " @" + r.rejectedHeight());

        BigInteger expected = BigInteger.ZERO;
        for (BlockHeader h : candidates) {
            expected = expected.add(BigInteger.TWO.pow(h.difficulty()));
        }
        assertEquals(expected, r.work());
    }

    @Test
    void rejectsDiscontinuousId() {
        for (int i = 0; i < 7; i++) mineOnEngine(); // heights 2..8
        List<BlockHeader> candidates = headers(8, 8); // starts at 8, but fork+1 = 7
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 6, candidates, clock.get());
        assertEquals(HeaderChain.Rejection.DISCONTINUOUS_ID, r.rejection());
    }

    @Test
    void rejectsBrokenChain() {
        for (int i = 0; i < 7; i++) mineOnEngine();
        BlockHeader good = engine.headerAt(7);
        // Same height, wrong parent link — detected before PoW even matters.
        BlockHeader tampered = new BlockHeader(good.id(), good.timestamp(), good.difficulty(),
            good.numTransactions(), SHA256Hash.random(), good.merkleRoot(), good.nonce(),
            good.stateRoot(), good.vote(), good.uncles());
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 6, List.of(tampered), clock.get());
        assertEquals(HeaderChain.Rejection.BROKEN_CHAIN, r.rejection());
        assertEquals(7, r.rejectedHeight());
    }

    @Test
    void rejectsWrongDifficulty() {
        for (int i = 0; i < 7; i++) mineOnEngine();
        BlockHeader good = engine.headerAt(7);
        BlockHeader tampered = new BlockHeader(good.id(), good.timestamp(), good.difficulty() + 1,
            good.numTransactions(), good.lastBlockHash(), good.merkleRoot(), good.nonce(),
            good.stateRoot(), good.vote(), good.uncles());
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 6, List.of(tampered), clock.get());
        assertEquals(HeaderChain.Rejection.WRONG_DIFFICULTY, r.rejection());
    }

    @Test
    void rejectsInvalidProofOfWork() {
        for (int i = 0; i < 7; i++) mineOnEngine();
        BlockHeader good = engine.headerAt(7);
        // Correct difficulty and parent, but a bogus nonce: fails PoW. Pick a nonce that
        // genuinely does not satisfy the target (at low test difficulty a single random nonce
        // could accidentally pass, making the assertion flaky) so the check is deterministic.
        BlockHeader tampered;
        do {
            tampered = new BlockHeader(good.id(), good.timestamp(), good.difficulty(),
                good.numTransactions(), good.lastBlockHash(), good.merkleRoot(), SHA256Hash.random(),
                good.stateRoot(), good.vote(), good.uncles());
        } while (tampered.verifyNonce(params.powAlgorithm()));
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 6, List.of(tampered), clock.get());
        assertEquals(HeaderChain.Rejection.INVALID_POW, r.rejection());
    }

    @Test
    void rejectsTimestampAtOrBelowMedianTimePast() {
        for (int i = 0; i < 7; i++) mineOnEngine();
        int diff = engine.headerAt(7).difficulty();
        // Valid PoW, correct difficulty/parent, but a timestamp far in the past (≤ MTP).
        BlockHeader old = mineHeader(7, engine.headerAt(6).hash(), diff, 1L, List.of());
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 6, List.of(old), clock.get());
        assertEquals(HeaderChain.Rejection.TIMESTAMP_TOO_OLD, r.rejection());
    }

    @Test
    void rejectsTimestampInFuture() {
        for (int i = 0; i < 7; i++) mineOnEngine();
        int diff = engine.headerAt(7).difficulty();
        long now = clock.get();
        long tooFar = now + params.maxFutureBlockTimeSec() * 1000L + 60_000L;
        BlockHeader future = mineHeader(7, engine.headerAt(6).hash(), diff, tooFar, List.of());
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 6, List.of(future), now);
        assertEquals(HeaderChain.Rejection.TIMESTAMP_IN_FUTURE, r.rejection());
    }

    @Test
    void committedUncleWorkDoesNotInflateTheReorgGateWork() {
        // M4 (header-sync path): a header's committed uncles cannot be confirmed as real, pooled,
        // eligible orphans without the bodies, so their claimed work must NOT count toward the work
        // total the reorg gate compares. Otherwise an attacker pads a cheap branch with same-
        // difficulty fake uncles, inflating its claimed work and forcing a deep pop/restore with a
        // fraction of honest work. A structurally-valid uncle is still accepted (branch validates),
        // but the reported work stays base-only.
        for (int i = 0; i < 7; i++) mineOnEngine();
        int diff = engine.headerAt(7).difficulty();
        // One structurally-valid uncle at the maximum allowed difficulty (= the header's own): the
        // most an attacker could claim. Old code counted it and doubled the header's work.
        UncleRef u = new UncleRef(SHA256Hash.random(), diff, PublicAddress.random());
        BlockHeader padded = mineHeader(7, engine.headerAt(6).hash(), diff, clock.get() + 1000, List.of(u));
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 6, List.of(padded), clock.get() + 10_000);
        assertTrue(r.valid(), "a structurally-valid uncle must not reject the branch, got " + r.rejection());
        assertEquals(BigInteger.TWO.pow(diff), r.work()); // base only — NOT 2^diff + 2^diff
    }

    @Test
    void rejectsMalformedUncleReferences() {
        for (int i = 0; i < 7; i++) mineOnEngine();
        int diff = engine.headerAt(7).difficulty();
        UncleRef u = new UncleRef(SHA256Hash.random(), 4, PublicAddress.random());
        // Duplicate uncle within one block: structurally invalid, even before body-level eligibility.
        BlockHeader dup = mineHeader(7, engine.headerAt(6).hash(), diff, clock.get() + 1000, List.of(u, u));
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 6, List.of(dup), clock.get() + 10_000);
        assertEquals(HeaderChain.Rejection.INVALID_UNCLES, r.rejection());
    }
}
