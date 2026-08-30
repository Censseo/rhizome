package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.SHA256Hash;

/**
 * Supply header commitment (§ supply header commitment, FR-003/FR-004): {@code addBlock} must
 * enforce {@code block.supply == parent.supply + minted(block)} whenever supply is committed, and
 * the commitment must be prefix-closed — a chain commits supply at every height from genesis, or
 * at none.
 */
class SupplyCommitmentTest {

    /** A coinbase-only block, hand-mined, with an explicit committed supply. */
    private static Block mineOnto(NetworkParameters params, long height, SHA256Hash parentHash,
            int difficulty, long timestamp, PublicAddress miner, long supply, List<UncleRef> uncles) {
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(timestamp)
            .difficulty(difficulty)
            .lastBlockHash(parentHash)
            .uncles(new ArrayList<>(uncles))
            .supply(supply)
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void supplyCommitmentMatchesScheduledIssuanceExactly() {
        NetworkParameters params = NetworkParameters.testnet();
        AtomicLong clock = new AtomicLong(0L);
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();

        // Fresh chain, empty snapshot: genesis commits supply 0 (FR-005) — not absent.
        long s0 = engine.headerAt(1).supply();
        assertEquals(0L, s0, "the empty snapshot commits genesis supply 0");

        PublicAddress miner = PublicAddress.random();
        long expected = s0 + params.miningReward(2); // no uncles: minted() is just the coinbase reward
        long ts = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);

        Block tooHigh = mineOnto(params, 2, engine.tipHash(), engine.difficulty(), ts, miner,
            expected + 1, List.of());
        assertEquals(ExecutionStatus.INVALID_SUPPLY, engine.addBlock(tooHigh));

        Block tooLow = mineOnto(params, 2, engine.tipHash(), engine.difficulty(), ts, miner,
            expected - 1, List.of());
        assertEquals(ExecutionStatus.INVALID_SUPPLY, engine.addBlock(tooLow));
        assertEquals(1, engine.height(), "neither one-base-unit forgery may extend the chain");

        Block honest = mineOnto(params, 2, engine.tipHash(), engine.difficulty(), ts, miner,
            expected, List.of());
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(honest));
        assertEquals(2, engine.height());
        assertEquals(expected, engine.headerAt(2).supply());
    }

    @Test
    void supplyCommitmentIsPrefixClosed() {
        NetworkParameters params = NetworkParameters.testnet();
        PublicAddress miner = PublicAddress.random();

        // --- Dropped commitment: parent (genesis) commits, child omits -> rejected. ---
        AtomicLong honestClock = new AtomicLong(0L);
        InMemoryLedger honestLedger = new InMemoryLedger();
        InMemoryChainStore honestStore = new InMemoryChainStore();
        LedgerSnapshot honestSnapshot = new LedgerSnapshot("t", 0, params.chainId());
        ChainEngine honestEngine = ChainEngine.boot(
                params, TestNodeStores.mixing(honestLedger, honestStore), honestSnapshot)
            .clock(honestClock::get)
            .build();
        assertTrue(honestEngine.headerAt(1).supply() >= 0, "genesis always commits post this feature");

        long ts1 = honestClock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        Block dropped = mineOnto(params, 2, honestEngine.tipHash(), honestEngine.difficulty(), ts1,
            miner, BlockImpl.SUPPLY_ABSENT, List.of());
        assertEquals(ExecutionStatus.INVALID_SUPPLY, honestEngine.addBlock(dropped));
        assertEquals(1, honestEngine.height());

        // --- Mid-chain start / all-absent stays valid. ---
        // A supply-less parent at height 2 cannot arise from a normal addBlock (that IS the
        // dropped-commitment rule just proven above), so it is manufactured directly on the
        // store — the point of this half is the general, LOCAL parent/child rule the check
        // enforces at any height, not how a legacy all-absent chain historically came to be one.
        //
        // Two manufactured absent blocks (heights 2 and 3), not one, before the engine ever
        // boots over this store: 006-emission-fork-activation's boot-time consistency check
        // (data-model.md's verdict table) refuses a tip whose PARENT commits but which itself
        // does not (rule 5 -- an unreachable-via-addBlock shape, exactly what this section's
        // manufacturing bypasses addBlock to construct). Booting only once both the tip AND its
        // parent are absent satisfies rule 4 (a legitimately supply-less chain) instead.
        AtomicLong clock = new AtomicLong(0L);
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        Block genesis = rhizome.core.blockchain.GenesisBlock.build(params, snapshot);
        store.append(genesis);

        long ts2 = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        Block absentAt2 = mineOnto(params, 2, genesis.hash(), params.genesisDifficulty(), ts2,
            miner, BlockImpl.SUPPLY_ABSENT, List.of());
        store.append(absentAt2);
        long ts3 = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        Block absentAt3 = mineOnto(params, 3, absentAt2.hash(), params.genesisDifficulty(), ts3,
            miner, BlockImpl.SUPPLY_ABSENT, List.of());
        store.append(absentAt3);

        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
        assertEquals(BlockImpl.SUPPLY_ABSENT, engine.headerAt(2).supply());
        assertEquals(BlockImpl.SUPPLY_ABSENT, engine.headerAt(3).supply());

        long ts4 = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        Block midChainStart = mineOnto(params, 4, absentAt3.hash(), engine.difficulty(), ts4,
            miner, 0L, List.of());
        assertEquals(ExecutionStatus.INVALID_SUPPLY, engine.addBlock(midChainStart));
        assertEquals(3, engine.height(), "a mid-chain start must not extend the chain");

        Block stillAbsent = mineOnto(params, 4, absentAt3.hash(), engine.difficulty(), ts4,
            miner, BlockImpl.SUPPLY_ABSENT, List.of());
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(stillAbsent),
            "an all-absent chain must keep validating exactly as before this feature");
        assertEquals(4, engine.height());
    }

    @Test
    void supplyAccountingIncludesWorkScaledUncleAndNephewIssuance() {
        // A genuine difficulty deficit needs headroom between genesisDifficulty and minDifficulty
        // (testnet() pins them equal), mirroring BlockUnclesTest's sub-difficulty-uncle setup.
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .genesisDifficulty(5).minDifficulty(3).build();
        AtomicLong clock = new AtomicLong(0L);
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();

        PublicAddress miner = PublicAddress.random();
        long s0 = engine.headerAt(1).supply();
        assertEquals(0L, s0);

        // Height 2: honest, uncle-less.
        long s1 = s0 + params.miningReward(2);
        long ts2 = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        Block block2 = mineOnto(params, 2, engine.tipHash(), engine.difficulty(), ts2, miner, s1, List.of());
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(block2));

        // A sub-difficulty orphan sibling of block2 (same parent: genesis), difficulty 3 vs the
        // height-3 nephew's difficulty 5 -> deficit 2.
        PublicAddress uncleMiner = PublicAddress.random();
        Block orphan = mineOnto(params, 2, genesisHash(engine), 3, ts2 + 1, uncleMiner,
            BlockImpl.SUPPLY_ABSENT, List.of());
        engine.registerOrphan(orphan);

        UncleRef uref = new UncleRef(orphan.hash(), 3, uncleMiner);
        int deficit = engine.difficulty() - 3; // 5 - 3 = 2
        long uncleTerm = params.uncleReward(3) >>> deficit;
        long nephewTerm = params.nephewReward(3) >>> deficit;
        assertTrue(uncleTerm > 0 && uncleTerm < params.uncleReward(3),
            "the deficit must genuinely scale the reward down, not zero it or leave it flat");

        long expected = s1 + params.miningReward(3) + uncleTerm + nephewTerm;
        long ts3 = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);

        // Missing the scaled uncle/nephew terms entirely: flat coinbase only, uncle still referenced.
        Block missingScaledTerm = mineOnto(params, 3, block2.hash(), engine.difficulty(), ts3, miner,
            s1 + params.miningReward(3), List.of(uref));
        assertEquals(ExecutionStatus.INVALID_SUPPLY, engine.addBlock(missingScaledTerm));
        assertEquals(2, engine.height());

        Block honestWithUncle = mineOnto(params, 3, block2.hash(), engine.difficulty(), ts3, miner,
            expected, List.of(uref));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(honestWithUncle));
        assertEquals(3, engine.height());
        assertEquals(expected, engine.headerAt(3).supply());
    }

    /**
     * Regression guard (FR-001, FR-010): on the shipped {@code testnet()} profile, where
     * {@code emissionCurveHeight == 0} means the supply-driven curve never activates, the new
     * two-arg {@code miningReward(height, parentSupply)} dispatch must be a true no-op — it must
     * equal the pre-feature one-arg geometric form exactly, for the real supply values a live
     * chain actually produces, and the committed {@code supply} field after {@code addBlock} must
     * still equal {@code parentSupply + miningReward(height)} exactly, just as before this
     * feature existed. This is a guard, not a red-then-green TDD test: it is expected to already
     * pass, since the curve-dispatch plumbing added by earlier tasks in this feature preserves
     * pre-activation behavior exactly.
     */
    @Test
    void belowActivationTheGeometricRewardAndSupplyIdentityAreUnchanged() {
        NetworkParameters params = NetworkParameters.testnet();
        AtomicLong clock = new AtomicLong(0L);
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();

        PublicAddress miner = PublicAddress.random();
        long supply = engine.headerAt(1).supply();
        assertEquals(0L, supply, "the empty snapshot commits genesis supply 0");
        assertTrue(!params.emissionCurveActiveAt(1),
            "sanity: the curve must be inactive at genesis on the shipped testnet profile");

        for (long height = 2; height <= 5; height++) {
            assertTrue(!params.emissionCurveActiveAt(height),
                "sanity: the curve must remain inactive at height " + height
                    + " on the shipped testnet profile (emissionCurveHeight == 0)");

            // The two-arg supply-aware dispatch must be a true no-op below activation, for the
            // ACTUAL supply value a real chain carries at this height (not an arbitrary probe).
            assertEquals(params.miningReward(height), params.miningReward(height, supply),
                "below activation, the supply-aware reward must equal the geometric reward "
                    + "at height " + height + " for the real chain supply " + supply);

            long expected = supply + params.miningReward(height);
            long ts = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
            Block block = mineOnto(params, height, engine.tipHash(), engine.difficulty(), ts, miner,
                expected, List.of());
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(block));
            assertEquals(height, engine.height());

            // The pre-feature identity: committed supply == parent supply + geometric reward.
            assertEquals(expected, engine.headerAt(height).supply());
            supply = expected;
        }
    }

    private static SHA256Hash genesisHash(ChainEngine engine) {
        return engine.blockAt(1).hash();
    }

    // ---- User Story 2 (reorg restores supply structurally, FR-009) ----

    /** Mines a coinbase-only block onto {@code engine}'s current tip, applies it, and returns it. */
    private static BlockImpl mineCoinbaseOnly(NetworkParameters params, ChainEngine engine,
            AtomicLong clock, PublicAddress miner) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty()))
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        return b;
    }

    /** Mines and registers an orphan sibling of {@code engine}'s tip at an explicit sub-difficulty,
     *  so a nephew referencing it pays a genuinely work-scaled (not flat) uncle/nephew reward. */
    private static BlockImpl orphanSiblingOfTip(NetworkParameters params, ChainEngine engine,
            AtomicLong clock, PublicAddress orphanMiner, int difficulty) {
        long tipHeight = engine.height();
        SHA256Hash grandparent = engine.blockAt(tipHeight - 1).hash();
        var orphan = (BlockImpl) BlockImpl.builder().id((int) tipHeight)
            .timestamp(clock.addAndGet(500)).difficulty(difficulty)
            .lastBlockHash(grandparent).uncles(new ArrayList<>()).build();
        orphan.addTransaction(Transaction.of(orphanMiner, new TransactionAmount(params.miningReward(tipHeight))));
        var tree = new MerkleTree();
        tree.setItems(orphan.transactions());
        orphan.merkleRoot(tree.getRootHash());
        orphan.nonce(Miner.mineNonce(orphan.hash(), orphan.difficulty(), params.powAlgorithm()));
        engine.registerOrphan(orphan);
        return orphan;
    }

    private static UncleRef refOf(BlockImpl orphan) {
        return new UncleRef(orphan.hash(), orphan.difficulty(), orphan.transactions().get(0).to());
    }

    /** A mined (but not yet applied) next block on {@code engine}'s tip carrying {@code uncles}. */
    private static BlockImpl mineNephewCandidate(NetworkParameters params, ChainEngine engine,
            AtomicLong clock, PublicAddress miner, List<UncleRef> uncles) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .uncles(new ArrayList<>(uncles))
            .supply(SupplyStamp.next(engine, height, engine.difficulty(), uncles))
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** A {@link PeerSource} backed by another engine, serving uncle orphans too (audit: uncle-sync). */
    private static final class EnginePeer implements PeerSource {
        final ChainEngine engine;
        EnginePeer(ChainEngine engine) { this.engine = engine; }
        public long height() { return engine.height(); }
        public BigInteger totalWork() { return engine.totalWork(); }
        public SHA256Hash blockHash(long height) { return engine.blockAt(height).hash(); }
        public List<Block> blocks(long start, long end) {
            List<Block> out = new ArrayList<>();
            for (long h = start; h <= end; h++) out.add(engine.blockAt(h));
            return out;
        }
        public Block orphan(SHA256Hash hash) { return engine.orphanBlock(hash); }
    }

    @Test
    void reorgRestoresCommittedSupplyStructurally() {
        // Uncle deficit needs headroom between genesisDifficulty and minDifficulty (mirrors
        // supplyAccountingIncludesWorkScaledUncleAndNephewIssuance's setup). A small, EXPLICIT
        // maxReorgDepth (mirrors ReorgWindowGuardTest/ReorgAttackTest): the test pops and adopts
        // EXACTLY this many blocks -- the configured maximum, read back from params, not an
        // arbitrary smaller number -- so it genuinely exercises "a reorg of depth k <=
        // maxReorgDepth" (SC-003) at the boundary without mining hundreds of real blocks.
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .genesisDifficulty(5).minDifficulty(3)
            .maxReorgDepth(5)
            .build();
        int depth = params.maxReorgDepth();

        AtomicLong clock = new AtomicLong(1_000_000L);
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        ChainEngine local = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot)
            .clock(clock::get).build();

        // A shared, non-trivial prefix -- the fork point is NOT genesis, so "the fork-point
        // header needed no recomputation" is a real assertion, not a vacuous one about genesis.
        List<Block> prefix = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            prefix.add(mineCoinbaseOnly(params, local, clock, PublicAddress.random()));
        }
        long forkHeight = local.height();
        long forkSupply = local.headerAt(forkHeight).supply();
        Block forkBlockBefore = local.blockAt(forkHeight);
        assertTrue(forkSupply >= 0, "the prefix commits supply");

        ChainEngine peer = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot)
            .clock(clock::get).build();
        for (Block b : prefix) {
            assertEquals(ExecutionStatus.SUCCESS, peer.addBlock(b));
        }
        assertEquals(forkHeight, peer.height());

        // Branch A -- local's own suffix: coinbase-only, `depth` blocks.
        for (int i = 0; i < depth; i++) {
            mineCoinbaseOnly(params, local, clock, PublicAddress.random());
        }
        assertEquals(forkHeight + depth, local.height());

        // Branch B -- the peer's heavier suffix: depth+1 blocks, alternating uncle inclusion so
        // per-block issuance genuinely diverges from branch A's (not a longer no-op-equal chain).
        for (int i = 1; i <= depth + 1; i++) {
            List<UncleRef> uncles;
            if (i % 2 == 0) {
                BlockImpl orphan = orphanSiblingOfTip(params, peer, clock, PublicAddress.random(),
                    params.minDifficulty());
                uncles = List.of(refOf(orphan));
            } else {
                uncles = List.of();
            }
            BlockImpl nephew = mineNephewCandidate(params, peer, clock, PublicAddress.random(), uncles);
            assertEquals(ExecutionStatus.SUCCESS, peer.addBlock(nephew));
        }
        assertEquals(forkHeight + depth + 1, peer.height());
        assertTrue(peer.totalWork().compareTo(local.totalWork()) > 0, "the peer branch must be heavier");

        ChainSynchronizer.Result result = new ChainSynchronizer(local).syncFrom(new EnginePeer(peer));

        assertEquals(ChainSynchronizer.Result.REORGED, result);
        assertEquals(peer.height(), local.height());
        assertEquals(peer.tipHash(), local.tipHash());

        // The fork-point block is the exact SAME object after the reorg: it sat below the pop
        // boundary and was never touched, so its committed supply needed no recomputation at all
        // (FR-009 -- the popped-to header's committed value IS the supply, no rollback code runs).
        assertSame(forkBlockBefore, local.blockAt(forkHeight),
            "the fork-point block must be untouched by the reorg -- it was never popped");
        assertEquals(forkSupply, local.headerAt(forkHeight).supply());

        // Every new tip's committed supply equals an independently walked issuance sum from the
        // fork point -- the same shared formula (FR-007), but summed here by the TEST walking the
        // adopted branch, not read back from what addBlock validated inline.
        long running = forkSupply;
        for (long h = forkHeight + 1; h <= local.height(); h++) {
            Block block = local.blockAt(h);
            running = Math.addExact(running, Issuance.minted(params, h, running, block.difficulty(), block.uncles()));
            assertEquals(running, local.headerAt(h).supply(),
                "height " + h + " committed supply must equal the independently recomputed issuance sum");
        }
    }

    @Test
    void failedReorgRestoreRevalidatesIdenticalSupply() {
        // Mirrors ChainSynchronizerTest's restore-coverage pattern (e.g. lyingPeerDoesNotCorruptLocalState /
        // fallbackGateRejectsWrongDifficultyBeforeAnyPop): a scripted PeerSource serves a hand-built
        // branch, never applied to any live engine, so an otherwise-fully-valid block can carry one
        // deliberately wrong field. HeaderChain.validate does not check supply yet (that gate is
        // User Story 3 / Phase 5), so a branch with a forged LAST-block supply clears the stateless
        // pre-pop gate exactly like a "wrong derived difficulty" branch (ReorgSupport's own javadoc
        // example) -- the local suffix IS popped, the stateful addValidatedBody check (the same
        // checkSupply as addBlock) rejects the forged block, and the trusted-restore path
        // (ReorgSupport.restore -> engine.restoreBlock) puts the local suffix back.
        NetworkParameters params = NetworkParameters.testnet();
        AtomicLong clock = new AtomicLong(1_000_000L);
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        ChainEngine local = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot)
            .clock(clock::get).build();

        for (int i = 0; i < 3; i++) {
            mineCoinbaseOnly(params, local, clock, PublicAddress.random());
        }
        long forkHeight = 1; // genesis: local and the forged peer branch share nothing beyond it
        Map<Long, Long> supplyBeforeAttempt = new HashMap<>();
        for (long h = forkHeight; h <= local.height(); h++) {
            supplyBeforeAttempt.put(h, local.headerAt(h).supply());
        }
        SHA256Hash tipBefore = local.tipHash();
        BigInteger workBefore = local.totalWork();
        long heightBefore = local.height();

        // A synthetic peer chain: 3 fully legitimate blocks (built via a throwaway engine sharing
        // the same genesis, so their committed supply is genuinely correct), then a 4th block that
        // is correctly mined/PoW'd/merkle-rooted but commits a supply one base unit too high --
        // never applied to the throwaway engine (which would itself reject it), only served raw.
        ChainEngine scratch = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot)
            .clock(clock::get).build();
        List<Block> branch = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            branch.add(mineCoinbaseOnly(params, scratch, clock, PublicAddress.random()));
        }
        long forgedHeight = scratch.height() + 1;
        long wrongSupply = SupplyStamp.next(scratch, forgedHeight, scratch.difficulty()) + 1;
        var forged = (BlockImpl) BlockImpl.builder().id((int) forgedHeight)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(scratch.difficulty())
            .lastBlockHash(scratch.tipHash())
            .supply(wrongSupply)
            .build();
        forged.addTransaction(Transaction.of(PublicAddress.random(),
            new TransactionAmount(params.miningReward(forgedHeight))));
        var tree = new MerkleTree();
        tree.setItems(forged.transactions());
        forged.merkleRoot(tree.getRootHash());
        forged.nonce(Miner.mineNonce(forged.hash(), forged.difficulty(), params.powAlgorithm()));
        branch.add(forged);
        assertEquals(4, branch.size());
        // A branch of 4 blocks strictly outweighs local's 3 at the same constant difficulty
        // (testnet's genesisDifficulty == minDifficulty), so it clears the base-work prefilter.
        assertTrue(branch.size() > heightBefore - forkHeight);

        BigInteger claimedWork = workBefore.add(BigInteger.valueOf(1_000_000)); // self-reported; not trusted
        PeerSource forger = new PeerSource() {
            public long height() { return forkHeight + branch.size(); }
            public BigInteger totalWork() { return claimedWork; }
            public SHA256Hash blockHash(long h) {
                return h == forkHeight ? local.blockAt(forkHeight).hash() : branch.get((int) (h - forkHeight - 1)).hash();
            }
            public List<Block> blocks(long start, long end) {
                List<Block> out = new ArrayList<>();
                for (long h = start; h <= end; h++) {
                    out.add(branch.get((int) (h - forkHeight - 1)));
                }
                return out;
            }
        };

        ChainSynchronizer.Result result = new ChainSynchronizer(local).syncFrom(forger);

        assertEquals(ChainSynchronizer.Result.PEER_INVALID, result,
            "the forged-supply block must reject the branch mid-apply, after the local suffix was popped");
        assertEquals(heightBefore, local.height(), "the local chain must be restored to its exact height");
        assertEquals(tipBefore, local.tipHash(), "the local chain must be restored to its exact tip");
        assertEquals(workBefore, local.totalWork(), "restored total work must match exactly");

        // Each restored block's supply RE-VALIDATED (via restoreBlock's ordinary checkSupply call,
        // not a skip) to the identical value it carried before the pop -- the "Restore path" edge
        // case: re-validated, not skipped.
        for (Map.Entry<Long, Long> entry : supplyBeforeAttempt.entrySet()) {
            assertEquals(entry.getValue(), local.headerAt(entry.getKey()).supply(),
                "height " + entry.getKey() + "'s restored supply must equal its pre-pop value exactly");
        }
    }
}
