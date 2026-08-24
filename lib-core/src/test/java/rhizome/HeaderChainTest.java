package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.HeaderChain;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
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
        engine = ChainEngine.boot(
                params,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, params.chainId()))
            .clock(clock::get)
            .build();
    }

    /** Mines the next block on the engine's tip and applies it. */
    private void mineOnEngine() {
        long h = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, h, engine.difficulty())).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
    }

    /** Builds and PoW-mines a standalone header (any timestamp/uncles) for adversarial cases. */
    private BlockHeader mineHeader(long id, SHA256Hash parent, int difficulty, long ts, List<UncleRef> uncles) {
        // Stamp the SAME Issuance.minted formula the header-only supply check enforces, reading
        // the immediate parent height's committed supply (every caller here builds directly off
        // engine.headerAt(id - 1)) -- so these adversarial fixtures keep exercising the check they
        // were written for (timestamp/PoW/uncle structure) instead of tripping the new
        // prefix-closure rule for a reason unrelated to what the test actually means to exercise.
        long parentSupply = engine.headerAt(id - 1).supply();
        long supply = parentSupply == BlockImpl.SUPPLY_ABSENT
            ? BlockImpl.SUPPLY_ABSENT
            : Math.addExact(parentSupply, Issuance.minted(params, id, parentSupply, difficulty, uncles));
        var b = (BlockImpl) BlockImpl.builder().id((int) id).timestamp(ts).difficulty(difficulty)
            .lastBlockHash(parent).merkleRoot(SHA256Hash.random())
            .supply(supply)
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

    /** Mines the next block on {@code e} with an explicit timestamp (adversarial pacing cases). */
    private static void mineOnEngineAt(ChainEngine e, NetworkParameters p, PublicAddress miner, long ts) {
        long h = e.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(ts)
            .difficulty(e.difficulty()).lastBlockHash(e.tipHash())
            .supply(SupplyStamp.next(e, h, e.difficulty())).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(p.miningReward(h))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), p.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, e.addBlock(b));
    }

    @Test
    void inflatedBoundaryTimestampNoLongerDragsDifficultyDown() {
        // Timewarp fix (audit: retarget on 2 raw timestamps). Lookback 10 on a 1 s target: the
        // first window measures ts(boundary 10) - ts(height 2) over 8 intervals (desired 8 s).
        // Under the old rule, inflating ONLY the boundary block's timestamp by +15 s/block
        // (9 × 15 s = 135 s) stretched the observed duration to 143 s and crashed the difficulty
        // by the full MAX_STEP_BITS (10 → 6) at no hash cost. The median-of-3 bound takes the
        // middle of the last 3 timestamps, so the single inflated point is ignored: the window
        // measures 7 s and the difficulty holds at 10 — exactly the uninflated control's outcome.
        NetworkParameters p = params.toBuilder()
            .difficultyLookback(10).genesisDifficulty(10).minDifficulty(4).build();
        AtomicLong c = new AtomicLong(1_000_000L);
        ChainEngine attacked = ChainEngine.boot(
                p,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, p.chainId()))
            .clock(c::get)
            .build();
        ChainEngine control = ChainEngine.boot(
                p,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, p.chainId()))
            .clock(c::get)
            .build();
        long base = 1_000L;
        for (int h = 2; h <= 10; h++) {
            long onTarget = base + h * 1000L;
            // Boundary block of the attacked chain: timestamp inflated +15 s per block of window.
            mineOnEngineAt(attacked, p, miner, h == 10 ? onTarget + 9 * 15_000L : onTarget);
            mineOnEngineAt(control, p, miner, onTarget);
        }
        assertEquals(10, control.difficulty(), "sanity: on-target window keeps the difficulty");
        assertEquals(10, attacked.difficulty(),
            "median-of-3 bound absorbs the single inflated boundary timestamp "
                + "(raw-timestamp rule would have dropped 10 → 6)");
    }

    @Test
    void sustainedSlowWindowStillRetargetsDown() {
        // The median bound must not make the retarget blind: a window that is GENUINELY slow
        // (every block +16 s on a 1 s target, so the median itself reflects the slowdown) still
        // steps the difficulty down — only single-point manipulation is absorbed.
        NetworkParameters p = params.toBuilder()
            .difficultyLookback(10).genesisDifficulty(10).minDifficulty(4).build();
        AtomicLong c = new AtomicLong(1_000_000L);
        ChainEngine e = ChainEngine.boot(
                p,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, p.chainId()))
            .clock(c::get)
            .build();
        for (int h = 2; h <= 10; h++) {
            mineOnEngineAt(e, p, miner, 1_000L + h * 16_000L);
        }
        assertTrue(e.difficulty() < 10,
            "a genuinely slow window still retargets down, was " + e.difficulty());
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
    void rejectsBranchThatViolatesACheckpointInTheHeaderGate() {
        // audit V6d: a base-heavier branch diverging at a checkpointed height must be refused by the
        // header gate itself, before any local pop — not only later in ChainEngine.addBlock. The
        // engine mines with no checkpoints; validation then runs under params that pin height 8 to a
        // hash the branch does not carry, so the gate rejects at height 8.
        for (int i = 0; i < 11; i++) mineOnEngine(); // heights 2..12
        List<BlockHeader> candidates = headers(7, 12);
        NetworkParameters pinned = params.toBuilder()
            .checkpoints(java.util.Map.of(8L, SHA256Hash.random())).build();
        HeaderChain.Result r = HeaderChain.validate(pinned, engine::headerAt, 6, candidates, clock.get());
        assertEquals(HeaderChain.Rejection.CHECKPOINT_MISMATCH, r.rejection());
        assertEquals(8, r.rejectedHeight());
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
            good.stateRoot(), good.vote(), good.supply(), good.uncles());
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
            good.stateRoot(), good.vote(), good.supply(), good.uncles());
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
                good.stateRoot(), good.vote(), good.supply(), good.uncles());
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
    void rejectsOutOfRangeVote() {
        // audit F1: the header gate enforces the same canonical vote rule as ChainEngine.addBlock
        // and the codecs — an otherwise valid header carrying |vote| > 2 is rejected at its height.
        for (int i = 0; i < 7; i++) mineOnEngine();
        BlockHeader good = engine.headerAt(7);
        BlockHeader tampered = new BlockHeader(good.id(), good.timestamp(), good.difficulty(),
            good.numTransactions(), good.lastBlockHash(), good.merkleRoot(), good.nonce(),
            good.stateRoot(), 3, good.supply(), good.uncles());
        HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 6, List.of(tampered), clock.get());
        assertEquals(HeaderChain.Rejection.INVALID_VOTE, r.rejection());
        assertEquals(7, r.rejectedHeight());
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

    @Test
    void headerGateRejectsForgedSupplyBeforeProofOfWork() {
        // US3/FR-006/FR-007: the header-only supply check reuses Issuance.minted (the SAME
        // formula ChainEngine.addBlock enforces) and sits after vote/difficulty, before PoW -- so
        // a forged emission chain is rejected at the offending height, before that header's own
        // PoW is verified, and before any later header is even looked at.
        for (int i = 0; i < 7; i++) mineOnEngine(); // heights 2..8, each honestly supply-committed
        BlockHeader parent6 = engine.headerAt(6);
        BlockHeader honest7 = engine.headerAt(7);
        BlockHeader honest8 = engine.headerAt(8);
        int diff = honest7.difficulty();
        long correctSupply = Math.addExact(parent6.supply(),
            Issuance.minted(params, 7, parent6.supply(), diff, List.of()));
        assertEquals(honest7.supply(), correctSupply,
            "sanity: the honestly-mined header already matches the shared formula");

        // Over-commit by one base unit, and pick a nonce that GENUINELY fails PoW at the resulting
        // hash (supply is folded into the preimage, so a wrong supply changes the hash anyway) --
        // if the gate checked PoW before supply, this header would be rejected INVALID_POW, not
        // INVALID_SUPPLY, so the assertion below proves the ordering, not just the outcome.
        BlockHeader forged;
        do {
            var b = (BlockImpl) BlockImpl.builder().id(7).timestamp(honest7.timestamp())
                .difficulty(diff).lastBlockHash(parent6.hash()).merkleRoot(SHA256Hash.random())
                .nonce(SHA256Hash.random())
                .supply(correctSupply + 1)
                .uncles(new ArrayList<>()).build();
            forged = BlockHeader.of(b);
        } while (forged.verifyNonce(params.powAlgorithm()));

        // A trailing, otherwise-honest header straight off the engine: if the gate ever reached
        // past height 7 despite the forgery, it would reject THIS header too -- with BROKEN_CHAIN,
        // not INVALID_SUPPLY, since its lastBlockHash points at the REAL height-7 hash, not the
        // forged one. Its mere presence proves the loop never touches height 8 at all.
        List<BlockHeader> forgedBranch = List.of(forged, honest8);
        HeaderChain.Result forgedResult =
            HeaderChain.validate(params, engine::headerAt, 6, forgedBranch, clock.get());
        assertEquals(HeaderChain.Rejection.INVALID_SUPPLY, forgedResult.rejection(),
            "over-committed supply must be caught before PoW/later headers, got "
                + forgedResult.rejection() + " @" + forgedResult.rejectedHeight());
        assertEquals(7, forgedResult.rejectedHeight());

        // Regression (spec US1 AC4 / US3 AC1): the SAME headers, un-forged, still validate exactly
        // as an honest branch always has.
        List<BlockHeader> honestBranch = List.of(honest7, honest8);
        HeaderChain.Result honestResult =
            HeaderChain.validate(params, engine::headerAt, 6, honestBranch, clock.get());
        assertTrue(honestResult.valid(), "honest branch must still validate, got "
            + honestResult.rejection() + " @" + honestResult.rejectedHeight());

        // Regression (spec US1 AC4 / US3 AC3): a legacy all-absent branch -- no header at any
        // height commits supply -- still validates exactly as before this feature. Built entirely
        // independently of `engine`: a fresh chain's genesis always commits supply post this
        // feature (FR-005) and so cannot itself be built supply-less through ChainEngine.boot.
        var legacyRoot = (BlockImpl) BlockImpl.builder().id(1).timestamp(params.genesisTimestamp())
            .difficulty(params.genesisDifficulty()).lastBlockHash(SHA256Hash.empty())
            .nonce(SHA256Hash.empty()).merkleRoot(SHA256Hash.random()).build();
        BlockHeader legacyGenesis = BlockHeader.of(legacyRoot);
        assertEquals(BlockImpl.SUPPLY_ABSENT, legacyGenesis.supply(),
            "sanity: a hand-built legacy header commits no supply");

        var legacyNext = (BlockImpl) BlockImpl.builder().id(2)
            .timestamp(legacyGenesis.timestamp() + 1000).difficulty(legacyGenesis.difficulty())
            .lastBlockHash(legacyGenesis.hash()).merkleRoot(SHA256Hash.random()).build();
        legacyNext.nonce(Miner.mineNonce(legacyNext.hash(), legacyNext.difficulty(), params.powAlgorithm()));
        BlockHeader legacyChild = BlockHeader.of(legacyNext);
        assertEquals(BlockImpl.SUPPLY_ABSENT, legacyChild.supply(),
            "sanity: the legacy branch's child stays supply-less too");

        HeaderChain.Result legacyResult = HeaderChain.validate(params,
            h -> h == 1 ? legacyGenesis : null, 1, List.of(legacyChild), legacyGenesis.timestamp() + 1000);
        assertTrue(legacyResult.valid(),
            "an all-absent chain must keep validating exactly as before this feature, got "
                + legacyResult.rejection() + " @" + legacyResult.rejectedHeight());
    }

    @Test
    void theHeaderOnlyGateRevalidatesTheSupplyIdentityUnderTheCurve() {
        // FR-008/FR-009: the header-only gate must re-derive the SAME curve-aware supply identity
        // ChainEngine/Issuance will enforce once migrated (T012) -- a separate curve-active engine,
        // since the shared params/engine fixture above never activates the curve.
        NetworkParameters curveParams = CurveActiveNetwork.curveActiveTestnet();
        AtomicLong clk = new AtomicLong(1_000_000L);
        ChainEngine curveEngine = ChainEngine.boot(
                curveParams,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, curveParams.chainId()))
            .clock(clk::get)
            .build();

        BlockHeader genesis = curveEngine.headerAt(curveEngine.height());
        long parentSupply = genesis.supply();
        assertNotEquals(BlockImpl.SUPPLY_ABSENT, parentSupply,
            "curve activation needs a real committed parent supply");
        assertTrue(parentSupply >= 0, "sanity: a committed supply is never negative");

        // First block after genesis; the curve is active there under curveActiveTestnet()'s
        // emissionCurveHeight(1).
        long height = curveEngine.height() + 1;
        long expectedReward = curveParams.miningReward(height, parentSupply);
        long expectedSupply = Math.addExact(parentSupply, expectedReward);

        int difficulty = curveEngine.difficulty();
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clk.get())
            .difficulty(difficulty).lastBlockHash(genesis.hash()).merkleRoot(SHA256Hash.random())
            .supply(expectedSupply).build();
        b.nonce(Miner.mineNonce(b.hash(), difficulty, curveParams.powAlgorithm()));
        BlockHeader candidateHeader = BlockHeader.of(b);

        HeaderChain.Result result = HeaderChain.validate(curveParams, curveEngine::headerAt,
            curveEngine.height(), List.of(candidateHeader), clk.get() + 60_000L);

        // The header-only gate must re-derive the curve-aware formula (checkSupply threads
        // parentSupply into Issuance.minted), not the one-arg geometric form -- a header stamped
        // with the correct curve supply must validate.
        assertTrue(result.valid(), "expected the curve-correct supply to validate, got "
            + result.rejection() + " @" + result.rejectedHeight());
        assertEquals(HeaderChain.Rejection.NONE, result.rejection());
    }
}
