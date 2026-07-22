package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.ChainEngine;
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
import rhizome.core.transaction.TransactionImpl;

/** Uncle references (GHOST): block preimage/codec, full uncle validation, work weighting, rewards. */
class BlockUnclesTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private InMemoryLedger ledger;
    private AtomicLong clock;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        clock = new AtomicLong(1_000_000L);
        miner = PublicAddress.random();
        ledger = new InMemoryLedger();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        engine = ChainEngine.init(params, ledger, new InMemoryChainStore(), snapshot, null, clock::get);
    }

    /** The coinbase (mining fee) recipient of a block. */
    private static PublicAddress minerOf(Block block) {
        return ((TransactionImpl) block.transactions().get(0)).to();
    }

    /** A reference to an orphan, committing its real difficulty and miner address. */
    private static UncleRef ref(BlockImpl orphan) {
        return new UncleRef(orphan.hash(), orphan.difficulty(), minerOf(orphan));
    }

    /** A mined next block on the current tip, carrying the given uncle references. */
    private BlockImpl mineNext(List<UncleRef> uncles) {
        return mine(engine.height() + 1, engine.tipHash(), uncles, 7, PublicAddress.random());
    }

    /** A mined block at an arbitrary height/parent with a distinguishing salt (for orphans). */
    private BlockImpl mine(long height, SHA256Hash parent, List<UncleRef> uncles, int salt, PublicAddress coinbaseTo) {
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000L + salt)).difficulty(engine.difficulty())
            .lastBlockHash(parent).uncles(new java.util.ArrayList<>(uncles)).build();
        b.addTransaction(Transaction.of(coinbaseTo, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** Registers an orphan sibling of the current tip (forks from the tip's parent). */
    private BlockImpl registerOrphanSiblingOfTip() {
        return registerOrphanSiblingOfTip(PublicAddress.random());
    }

    private BlockImpl registerOrphanSiblingOfTip(PublicAddress orphanMiner) {
        long tipHeight = engine.height();
        SHA256Hash grandparent = engine.blockAt(tipHeight - 1).hash();
        BlockImpl orphan = mine(tipHeight, grandparent, List.of(), 500, orphanMiner);
        engine.registerOrphan(orphan);
        return orphan;
    }

    @Test
    void unclesCommitToTheHashAndRoundTripThroughTheCodec() {
        UncleRef u = new UncleRef(SHA256Hash.random(), 5, PublicAddress.random());
        BlockImpl withUncle = mineNext(List.of(u));
        BlockImpl without = mineNext(List.of());
        assertNotEquals(without.hash(), withUncle.hash()); // committed to the hash

        Block decoded = BlockCodec.decode(BlockCodec.encode(withUncle));
        assertEquals(List.of(u), decoded.uncles());
        assertEquals(withUncle.hash(), decoded.hash());
    }

    @Test
    void acceptsBlockReferencingAKnownValidOrphanUncle() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip(); // orphan at height 2, forks from genesis
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(List.of(ref(orphan))))); // height 3
        assertEquals(3, engine.height());
    }

    @Test
    void selectUnclesReturnsEligibleOrphansAndAssembledBlockIsAccepted() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip(); // orphan at height 2

        List<UncleRef> picked = engine.selectUncles();
        assertEquals(1, picked.size());
        assertEquals(orphan.hash(), picked.get(0).hash());
        assertEquals(orphan.difficulty(), picked.get(0).difficulty());
        assertEquals(minerOf(orphan), picked.get(0).miner());

        // A block carrying exactly the selection is accepted.
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(picked)));
        assertEquals(3, engine.height());
        // Once referenced, the orphan is no longer offered for the next block.
        assertTrue(engine.selectUncles().isEmpty());
    }

    @Test
    void uncleMinerIsPaidAndTheRewardIsReversedOnPop() {
        engine.addBlock(mineNext(List.of())); // height 2
        PublicAddress uncleMiner = PublicAddress.random();
        BlockImpl orphan = registerOrphanSiblingOfTip(uncleMiner); // orphan at height 2

        long before = balance(uncleMiner);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(List.of(ref(orphan))))); // height 3
        assertEquals(before + params.uncleReward(3), balance(uncleMiner));

        engine.popBlock(); // reversal must return the uncle miner to its prior balance
        assertEquals(before, balance(uncleMiner));
    }

    @Test
    void uncleWorkIsAddedToTheChainWeight() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl plain = mineNext(List.of()); // height 3, no uncle
        BigInteger before = engine.totalWork();
        BlockImpl orphan = registerOrphanSiblingOfTip(); // orphan at height 2
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(List.of(ref(orphan))))); // height 3 w/ uncle

        // The uncle-bearing tip weighs its own block work plus the uncle's 2^difficulty.
        BigInteger blockWork = BigInteger.TWO.pow(plain.difficulty());
        BigInteger uncleWork = BigInteger.TWO.pow(orphan.difficulty());
        assertEquals(before.add(blockWork).add(uncleWork), engine.totalWork());
    }

    @Test
    void baseWorkExcludesUncleWorkThatTotalWorkIncludes() {
        // The reorg PREFILTER now ranks branches by baseWork() (Σ 2^difficulty, no uncle term), the
        // same metric as the base-only ADOPTION gate — so heavy local uncle work can no longer make a
        // node refuse to even look at a base-heavier peer, the deadlock the two-metric mismatch caused
        // (audit 5th-pass, reorg-gate metric). baseWork must count only own-block work; totalWork adds
        // the validated uncle weight on top; both must revert exactly on pop.
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip();
        BigInteger baseBefore = engine.baseWork();
        BigInteger totalBefore = engine.totalWork();
        assertEquals(baseBefore, totalBefore, "no uncles yet -> base == total");

        BlockImpl tip = mineNext(List.of(ref(orphan))); // height 3 with an uncle
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(tip));
        BigInteger blockWork = BigInteger.TWO.pow(tip.difficulty());
        BigInteger uncleWork = BigInteger.TWO.pow(orphan.difficulty());
        assertEquals(baseBefore.add(blockWork), engine.baseWork(), "base gains only own-block work");
        assertEquals(totalBefore.add(blockWork).add(uncleWork), engine.totalWork(), "total adds uncle work");
        assertTrue(engine.baseWork().compareTo(engine.totalWork()) < 0, "uncle work lives only in total");

        engine.popBlock();
        assertEquals(baseBefore, engine.baseWork(), "base work reverts on pop");
        assertEquals(totalBefore, engine.totalWork(), "total work reverts on pop");
    }

    @Test
    void uncleWorkSurvivesPopAndRebuild() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip();
        engine.addBlock(mineNext(List.of(ref(orphan)))); // height 3 with uncle
        BigInteger withUncle = engine.totalWork();

        engine.popBlock(); // drop height 3
        assertEquals(2, engine.height());
        // Re-add the same shape; the uncle work must return identically.
        engine.registerOrphan(orphan);
        engine.addBlock(mineNext(List.of(ref(orphan))));
        assertEquals(withUncle, engine.totalWork());
    }

    @Test
    void rejectsUncleWithInflatedDifficulty() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip();
        // Claim a higher difficulty than the orphan actually has: work inflation.
        UncleRef inflated = new UncleRef(orphan.hash(), orphan.difficulty() + 10, minerOf(orphan));
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(inflated))));
        assertEquals(2, engine.height());
    }

    @Test
    void rejectsUncleWithForgedMiner() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl orphan = registerOrphanSiblingOfTip();
        // Redirect the reward to an address that did not mine the orphan.
        UncleRef forged = new UncleRef(orphan.hash(), orphan.difficulty(), PublicAddress.random());
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(forged))));
        assertEquals(2, engine.height());
    }

    @Test
    void rejectsUnknownUncle() {
        engine.addBlock(mineNext(List.of()));
        assertEquals(ExecutionStatus.INVALID_UNCLES,
            engine.addBlock(mineNext(List.of(new UncleRef(SHA256Hash.random(), 3, PublicAddress.random())))));
        assertEquals(2, engine.height());
    }

    @Test
    void blocksOwnPowIsVerifiedBeforeUncleWork() {
        // Audit 5th-pass (uncle-PoW-before-block-PoW DoS): validateUncles runs a memory-hard PoW hash
        // per referenced uncle, and it used to run BEFORE the block's own PoW — so a PoW-free /submit
        // forced up to maxUnclesPerBlock uncle hashes it was never budgeted for (the submitPowGate sizes
        // for one hash/submit). The block's own PoW must be checked first: a block whose own nonce is
        // invalid is rejected as INVALID_NONCE regardless of its uncle list, so no uncle hashing runs.
        engine.addBlock(mineNext(List.of())); // height 2

        // A block that carries an (unknown, would-be-INVALID_UNCLES) uncle AND an invalid own PoW.
        BlockImpl bad = mineNext(List.of(new UncleRef(SHA256Hash.random(), 3, PublicAddress.random())));
        do { bad.nonce(SHA256Hash.random()); } while (bad.verifyNonce(params.powAlgorithm())); // fails PoW
        // Own-PoW gate fires first -> INVALID_NONCE (before the reorder this returned INVALID_UNCLES).
        assertEquals(ExecutionStatus.INVALID_NONCE, engine.addBlock(bad));
        assertEquals(2, engine.height());
    }

    @Test
    void rejectsUncleThatIsAMainChainBlock() {
        engine.addBlock(mineNext(List.of())); // height 2
        BlockImpl canonical = (BlockImpl) engine.blockAt(2); // the canonical block 2
        engine.registerOrphan(canonical); // even if known, it is on-chain, not an orphan
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(ref(canonical)))));
    }

    @Test
    void rejectsTooManyUncles() {
        assertEquals(ExecutionStatus.INVALID_UNCLES,
            engine.addBlock(mineNext(List.of(
                new UncleRef(SHA256Hash.random(), 3, PublicAddress.random()),
                new UncleRef(SHA256Hash.random(), 3, PublicAddress.random()),
                new UncleRef(SHA256Hash.random(), 3, PublicAddress.random())))));
        assertEquals(1, engine.height());
    }

    @Test
    void rejectsDuplicateUncles() {
        UncleRef u = new UncleRef(SHA256Hash.random(), 3, PublicAddress.random());
        assertEquals(ExecutionStatus.INVALID_UNCLES, engine.addBlock(mineNext(List.of(u, u))));
    }

    @Test
    void unclelessBlocksAreAcceptedAndHashUnchanged() {
        BlockImpl a = mineNext(List.of());
        assertTrue(a.uncles().isEmpty());
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(a));
    }

    private long balance(PublicAddress a) {
        return ledger.hasWallet(a) ? ledger.getWalletValue(a).amount() : 0L;
    }

    private static long balanceOf(InMemoryLedger l, PublicAddress a) {
        return l.hasWallet(a) ? l.getWalletValue(a).amount() : 0L;
    }

    /** A mined block at an explicit difficulty (so an orphan can fall below the nephew's). */
    private static BlockImpl mineAt(NetworkParameters p, AtomicLong clk, long height, SHA256Hash parent,
                                    List<UncleRef> uncles, int salt, PublicAddress coinbaseTo, int difficulty) {
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clk.addAndGet(1000L + salt)).difficulty(difficulty)
            .lastBlockHash(parent).uncles(new java.util.ArrayList<>(uncles)).build();
        b.addTransaction(Transaction.of(coinbaseTo, new TransactionAmount(p.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), p.powAlgorithm()));
        return b;
    }

    /**
     * Security regression: payUncleRewards scales each uncle/nephew reward to the uncle's proven
     * work (base >>> deficit, audit C1), so the pop MUST reverse the same scaled amount. Reverting
     * the flat base over-subtracted on every reorg touching a sub-difficulty uncle, throwing
     * LedgerException mid-revert (corrupt ledger) or silently destroying coins and forking the
     * state root. This exercises a genuine deficit (>0), which the deficit-0 tests above never hit.
     */
    @Test
    void subDifficultyUncleRewardIsScaledAndExactlyReversedOnPop() {
        NetworkParameters p = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(5).minDifficulty(3).build();
        AtomicLong clk = new AtomicLong(2_000_000L);
        InMemoryLedger led = new InMemoryLedger();
        ChainEngine eng = ChainEngine.init(p, led, new InMemoryChainStore(),
            new LedgerSnapshot("t", 0, p.chainId()), null, clk::get);

        // height 2 at difficulty 5
        eng.addBlock(mineAt(p, clk, eng.height() + 1, eng.tipHash(), List.of(), 7,
            PublicAddress.random(), eng.difficulty()));

        // A sub-difficulty (3) orphan sibling of the tip: deficit vs the difficulty-5 nephew.
        PublicAddress uncleMiner = PublicAddress.random();
        SHA256Hash grandparent = eng.blockAt(eng.height() - 1).hash();
        BlockImpl orphan = mineAt(p, clk, eng.height(), grandparent, List.of(), 500, uncleMiner, 3);
        eng.registerOrphan(orphan);

        long nephewHeight = eng.height() + 1;
        int deficit = eng.difficulty() - orphan.difficulty(); // 5 - 3 = 2
        long scaledUncle = p.uncleReward(nephewHeight) >>> deficit;
        long scaledNephew = p.nephewReward(nephewHeight) >>> deficit;
        // The scaling must genuinely reduce the reward, else this test could not distinguish the bug.
        assertTrue(scaledUncle > 0 && scaledUncle < p.uncleReward(nephewHeight));

        long uncleBefore = balanceOf(led, uncleMiner);
        UncleRef uref = new UncleRef(orphan.hash(), orphan.difficulty(), uncleMiner);
        PublicAddress nephewMiner = PublicAddress.random();
        assertEquals(ExecutionStatus.SUCCESS, eng.addBlock(mineAt(p, clk, nephewHeight, eng.tipHash(),
            List.of(uref), 7, nephewMiner, eng.difficulty())));
        assertEquals(uncleBefore + scaledUncle, balanceOf(led, uncleMiner));
        assertEquals(p.miningReward(nephewHeight) + scaledNephew, balanceOf(led, nephewMiner));

        // The pop must reverse EXACTLY the scaled amounts; the flat-base revert threw or corrupted.
        eng.popBlock();
        assertEquals(uncleBefore, balanceOf(led, uncleMiner));
        assertEquals(0L, balanceOf(led, nephewMiner));
    }
}
