package rhizome;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.HonestBlockMiner;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.InMemoryNonceStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.NodeStores;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.SHA256Hash;

/**
 * 006-emission-fork-activation, User Story 4: a node whose stored chain disagrees with the
 * emission rule now in force refuses to start, per data-model.md's six ordered boot-time
 * verdicts (first match wins). Each test below is named for the rule it exercises.
 */
class ChainEngineBootConsistencyTest {

    /** Mines and applies an honest next block, correct under either dispatch arity. */
    private static BlockImpl mineOnto(NetworkParameters params, ChainEngine engine, AtomicLong clock,
            PublicAddress miner) {
        long height = engine.height() + 1;
        long parentSupply = engine.headerAt(engine.height()).supply();
        long ts = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        Transaction coinbase = Transaction.of(miner,
            new TransactionAmount(params.miningReward(height, parentSupply)));
        BlockImpl b = HonestBlockMiner.mineNext(params, engine, ts, coinbase);
        assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS, engine.addBlock(b));
        return b;
    }

    /** A {@link ChainStore} decorator that fails to serve the header at one specific height,
     *  simulating the snapshot-bootstrap/pruned-prefix gap (rule 2). */
    private static final class HeaderGapStore implements ChainStore {
        private final ChainStore delegate;
        private final long gapHeight;
        HeaderGapStore(ChainStore delegate, long gapHeight) {
            this.delegate = delegate;
            this.gapHeight = gapHeight;
        }
        @Override public long height() { return delegate.height(); }
        @Override public Block blockAt(long height) { return delegate.blockAt(height); }
        @Override public BlockHeader headerAt(long height) {
            if (height == gapHeight) {
                throw new IllegalArgumentException("simulated unavailable header at " + height);
            }
            return delegate.headerAt(height);
        }
        @Override public void append(Block block) { delegate.append(block); }
        @Override public void pop() { delegate.pop(); }
        @Override public boolean hasTransaction(SHA256Hash contentHash) { return delegate.hasTransaction(contentHash); }
    }

    @Test
    void aConsistentTipBootsNormallyOnBothProfiles() {
        NetworkParameters params = CurveActiveNetwork.curveActiveTestnet(); // activation height 1
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        NodeStores stores = TestNodeStores.mixing(ledger, store, new InMemoryNonceStore());
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        AtomicLong clock = new AtomicLong(1_000_000L);

        ChainEngine first = ChainEngine.boot(params, stores, snapshot).clock(clock::get).build();
        for (int i = 0; i < 3; i++) {
            mineOnto(params, first, clock, PublicAddress.random());
        }
        long heightAfterFirstBoot = first.height();

        // Reopen the SAME store under the SAME profile: the tip is consistent with the rule now
        // in force, so this must not throw.
        ChainEngine reopened = assertDoesNotThrow(
            () -> ChainEngine.boot(params, stores, snapshot).clock(clock::get).build());
        assertEquals(heightAfterFirstBoot, reopened.height());
    }

    /**
     * 008-decaying-supply-target T021 (FR-024, SC-010): a tip minted under a DIFFERENT decay
     * schedule already refuses boot -- rule 6 reaches the decay through {@code Issuance.minted}'s
     * dispatch, which now measures against the live target, so no new boot check exists to
     * write. The refusal MESSAGE must name {@code decayStartHeight} beside
     * {@code emissionCurveHeight}, so an operator whose chain was minted under a different decay
     * schedule is told which consensus constant moved (FR-024). Verified behaviourally first:
     * the test asserted the refusal fired BEFORE the message was extended.
     */
    @Test
    void aTipMintedUnderADifferentDecayScheduleRefusesBootAndNamesTheDecayStartHeight() {
        // The decay fixture: decay starts at height 10, so a chain past that height has minted
        // under decayed targets and its committed supplies pin the schedule that produced them.
        NetworkParameters params = CurveActiveNetwork.decayActiveTestnet();
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        NodeStores stores = TestNodeStores.mixing(ledger, store, new InMemoryNonceStore());
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        AtomicLong clock = new AtomicLong(1_000_000L);

        ChainEngine first = ChainEngine.boot(params, stores, snapshot).clock(clock::get).build();
        // The tip is at start + epoch + 1, i.e. ONE completed decay epoch: the decayed target is
        // visible at the tip itself (the boot check validates the tip block, not every block).
        for (int i = 0; i < 16; i++) {
            mineOnto(params, first, clock, PublicAddress.random());
        }
        assertTrue(first.height() > params.supplyTargetSchedule().startHeight()
                + params.supplyTargetSchedule().epochBlocks(),
            "sanity: the stored tip must sit in DECAYED territory (past the first epoch boundary)");

        // Reopen the SAME store under a profile whose decay ratio differs: the tip's committed
        // supplies were minted under 9/10 and cannot equal what 8/10 would have minted, so
        // rule 6 (the accounting identity) must refuse boot.
        NetworkParameters differentDecay = params.toBuilder().decayNum(8L).build();
        assertTrue(differentDecay.supplyTargetSchedule().isScheduled(),
            "sanity: the reopening profile still schedules a decay");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> ChainEngine.boot(differentDecay, stores, snapshot).clock(clock::get).build());
        String message = refused.getMessage();
        assertTrue(message.contains("emissionCurveHeight"),
            "the refusal must keep naming the curve activation height: " + message);
        assertTrue(message.contains("decayStartHeight"),
            "the refusal must name decayStartHeight beside emissionCurveHeight (FR-024): "
                + message);
    }

    @Test
    void aGenesisOnlyChainBootsNormally() {
        NetworkParameters params = CurveActiveNetwork.curveActiveTestnet();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        ChainEngine engine = assertDoesNotThrow(
            () -> ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot).build());
        assertEquals(GenesisBlock.GENESIS_ID, engine.height(), "sanity: a fresh chain is genesis-only");
    }

    @Test
    void anUnverifiableTipIsSkippedNotRefused() {
        // A store whose parent header cannot be read is architecturally incompatible with a full
        // ChainEngine boot succeeding end to end: rebuildDerivedState() unconditionally replays
        // EVERY header from genesis to tip (not a windowed lookback), so ANY missing header --
        // wherever this new check reads it too -- already fails a normal boot for reasons
        // predating this feature. What THIS check owes (FR-014, data-model.md rule 2) is narrower
        // and precise: it must not itself misfire a "different emission schedule" REFUSAL when it
        // cannot read the data -- it must SKIP and let whatever else was already going to happen,
        // happen. Proven here by asserting the failure that surfaces is the pre-existing,
        // unrelated one (the simulated store gap), never this feature's own refusal message.
        NetworkParameters params = CurveActiveNetwork.curveActiveTestnet();
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore realStore = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        AtomicLong clock = new AtomicLong(1_000_000L);

        ChainEngine seed = ChainEngine.boot(params,
                TestNodeStores.mixing(ledger, realStore, new InMemoryNonceStore()), snapshot)
            .clock(clock::get).build();
        for (int i = 0; i < 2; i++) {
            mineOnto(params, seed, clock, PublicAddress.random());
        }
        long tipHeight = seed.height();

        HeaderGapStore gapStore = new HeaderGapStore(realStore, tipHeight - 1);
        NodeStores gapStores = TestNodeStores.mixing(ledger, gapStore, new InMemoryNonceStore());
        Exception thrown = assertThrows(Exception.class,
            () -> ChainEngine.boot(params, gapStores, snapshot).clock(clock::get).build());
        assertEquals(IllegalArgumentException.class, thrown.getClass(),
            "the surfaced failure must be the pre-existing, unrelated store gap, not this "
                + "feature's own refusal: " + thrown);
        assertTrue(thrown.getMessage().contains("simulated unavailable header"),
            "sanity: the failure must originate from the simulated gap, not elsewhere: " + thrown);
    }

    @Test
    void aTipMintedUnderADifferentEmissionScheduleRefusesToBoot() {
        NetworkParameters activated = CurveActiveNetwork.curveActiveTestnet(); // activation height 1
        NetworkParameters neverActivating = activated.toBuilder().emissionCurveHeight(0L).build();

        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        NodeStores stores = TestNodeStores.mixing(ledger, store, new InMemoryNonceStore());
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, neverActivating.chainId());
        AtomicLong clock = new AtomicLong(1_000_000L);

        // Mint the whole chain under the NEVER-activating twin: every block's committed supply
        // reflects the geometric rule.
        ChainEngine neverEngine = ChainEngine.boot(neverActivating, stores, snapshot)
            .clock(clock::get).build();
        for (int i = 0; i < 3; i++) {
            mineOnto(neverActivating, neverEngine, clock, PublicAddress.random());
        }

        // Reopen the IDENTICAL store under the ACTIVATING profile: the stored tip's supply no
        // longer equals parent.supply + Issuance.minted(...) under the rule now in force.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ChainEngine.boot(activated, stores, snapshot).clock(clock::get).build());
        String message = ex.getMessage();
        assertTrue(message.contains(activated.networkName()), "message must name the network: " + message);
        assertTrue(message.contains(Long.toString(activated.emissionCurveHeight())),
            "message must name the activation height: " + message);
        assertFalse(message.contains(java.io.File.separator),
            "message must not contain a data-directory path: " + message);
    }

    @Test
    void aSupplyLessChainRefusesToBootUnderAScheduledCurve() {
        BootResult boot = bootSupplyLessChain();
        NetworkParameters scheduled = CurveActiveNetwork.curveActiveTestnet();
        assertThrows(IllegalStateException.class,
            () -> ChainEngine.boot(scheduled, boot.stores(), boot.snapshot()).build());
    }

    @Test
    void aSupplyLessChainStillBootsWhenNoCurveIsScheduled() {
        BootResult boot = bootSupplyLessChain();
        NetworkParameters neverScheduled = CurveActiveNetwork.curveActiveTestnet()
            .toBuilder().emissionCurveHeight(0L).build();
        assertDoesNotThrow(() -> ChainEngine.boot(neverScheduled, boot.stores(), boot.snapshot()).build());
    }

    @Test
    void aTipThatCommitsSupplyOverASupplyLessParentRefusesToBootEvenWithNoCurveScheduled() {
        // Rule 5 (data-model.md's boot-time verdict table): a parent that commits no supply but
        // whose child commits one anyway breaks the same FR-004 prefix-closure checkSupply
        // enforces at add-time -- this must REFUSE, never fall through into computing
        // parent.supply + Issuance.minted(...) with the SUPPLY_ABSENT sentinel (-1) standing in
        // for a real parent supply (which would silently accept a corrupted store whose tip
        // happens to equal Issuance.minted(...) - 1).
        NetworkParameters neverScheduled = CurveActiveNetwork.curveActiveTestnet()
            .toBuilder().emissionCurveHeight(0L).build();
        InMemoryChainStore store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, neverScheduled.chainId());
        Block genesis = GenesisBlock.build(neverScheduled, snapshot);
        store.append(genesis);

        PublicAddress miner = PublicAddress.random();
        Block absentAt2 = mineAbsent(neverScheduled, 2, genesis.hash(), neverScheduled.genesisDifficulty(),
            2000L, miner);
        store.append(absentAt2);
        // Chosen to match what rule 6's formula would (wrongly) compute if parentSupply's -1
        // sentinel leaked into the arithmetic -- proving the refusal is not incidentally still
        // triggered by a mismatched "expected" value for some other reason.
        long wouldPassRule6IfSentinelLeaked = neverScheduled.miningReward(3) - 1;
        Block tipAt3 = mineWithExplicitSupply(neverScheduled, 3, absentAt2.hash(),
            neverScheduled.genesisDifficulty(), 3000L, miner, wouldPassRule6IfSentinelLeaked);
        store.append(tipAt3);

        NodeStores stores = TestNodeStores.mixing(new InMemoryLedger(), store, new InMemoryNonceStore());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ChainEngine.boot(neverScheduled, stores, snapshot).build());
        assertTrue(ex.getMessage().contains(neverScheduled.networkName()),
            "message must name the network: " + ex.getMessage());
    }

    @Test
    void aTipThatDropsTheCommitmentOverACommittingParentRefusesToBoot() {
        // Rule 6 (data-model.md's boot-time verdict table): the mirror image of the test above --
        // a parent that DOES commit a real supply, whose child drops the commitment. This is the
        // exact "dropped commitment" shape SupplyCommitmentTest#supplyCommitmentIsPrefixClosed
        // proves addBlock itself rejects (INVALID_SUPPLY); this test proves the independent
        // boot-time check ALSO refuses it when the shape reaches the store some other way
        // (addBlock can never produce it, so it must be manufactured directly on the store, mirroring
        // this file's other manufactured fixtures). Before 006-emission-fork-activation's boot
        // check existed, a store in this shape booted without complaint.
        NetworkParameters params = CurveActiveNetwork.curveActiveTestnet()
            .toBuilder().emissionCurveHeight(0L).build();
        InMemoryChainStore store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        Block genesis = GenesisBlock.build(params, snapshot); // commits a real (non-absent) supply
        store.append(genesis);
        assertNotEquals(BlockImpl.SUPPLY_ABSENT, store.headerAt(1).supply(),
            "sanity: genesis commits a real supply");

        PublicAddress miner = PublicAddress.random();
        Block droppedAt2 = mineAbsent(params, 2, genesis.hash(), params.genesisDifficulty(), 2000L, miner);
        store.append(droppedAt2);
        assertEquals(BlockImpl.SUPPLY_ABSENT, store.headerAt(2).supply(), "sanity: tip drops the commitment");

        NodeStores stores = TestNodeStores.mixing(new InMemoryLedger(), store, new InMemoryNonceStore());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ChainEngine.boot(params, stores, snapshot).build());
        assertTrue(ex.getMessage().contains(params.networkName()),
            "message must name the network: " + ex.getMessage());
    }

    private record BootResult(NodeStores stores, LedgerSnapshot snapshot) {}

    /** A chain whose parent AND tip both carry {@code SUPPLY_ABSENT} (mirrors
     *  {@code SupplyCommitmentTest#supplyCommitmentIsPrefixClosed}'s manufactured mid-chain-start
     *  fixture): genesis commits normally, heights 2 and 3 are appended directly on the store
     *  with an absent commitment, bypassing {@code ChainEngine.addBlock}'s own prefix-closure
     *  refusal -- the point here is what a PRE-EXISTING store looks like at boot, not whether
     *  addBlock would have accepted it. */
    private static BootResult bootSupplyLessChain() {
        NetworkParameters params = CurveActiveNetwork.curveActiveTestnet()
            .toBuilder().emissionCurveHeight(0L).build();
        InMemoryChainStore store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        Block genesis = GenesisBlock.build(params, snapshot);
        store.append(genesis);

        PublicAddress miner = PublicAddress.random();
        Block absentAt2 = mineAbsent(params, 2, genesis.hash(), params.genesisDifficulty(), 2000L, miner);
        store.append(absentAt2);
        Block absentAt3 = mineAbsent(params, 3, absentAt2.hash(), params.genesisDifficulty(), 3000L, miner);
        store.append(absentAt3);

        assertEquals(BlockImpl.SUPPLY_ABSENT, store.headerAt(2).supply(), "sanity: parent must be absent");
        assertEquals(BlockImpl.SUPPLY_ABSENT, store.headerAt(3).supply(), "sanity: tip must be absent");

        NodeStores stores = TestNodeStores.mixing(new InMemoryLedger(), store, new InMemoryNonceStore());
        return new BootResult(stores, snapshot);
    }

    @Test
    void aTipWithAnOverflowingSupplySumRefusesCleanlyNotWithARawArithmeticException() {
        // A wire-legal extreme parent supply (near Long.MAX_VALUE, the same shape SUPPLY-04 and
        // MinerRevenueFloorAttackTest already prove reachable) makes
        // Math.addExact(parentSupply, Issuance.minted(...)) overflow INSIDE the boot check
        // itself. This must refuse cleanly (IllegalStateException), mirroring checkSupply's own
        // ArithmeticException guard -- never crash boot with a raw, uncaught exception (FR-014).
        NetworkParameters params = CurveActiveNetwork.curveActiveTestnet(); // activation height 1
        PublicAddress whale = PublicAddress.random();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(whale, new TransactionAmount(Long.MAX_VALUE - 5));

        InMemoryChainStore store = new InMemoryChainStore();
        Block genesis = GenesisBlock.build(params, snapshot);
        store.append(genesis);
        assertEquals(Long.MAX_VALUE - 5, store.headerAt(1).supply(),
            "sanity: genesis commits the near-overflow supply");

        // Manufactured directly on the store, mirroring bootSupplyLessChain's philosophy: the
        // point is what a PRE-EXISTING store looks like at boot, not whether addBlock would have
        // accepted it (addBlock's own checkSupply, already ArithmeticException-guarded, would
        // reject this the ordinary way -- this test is specifically about the boot-time path).
        Block tip = mineWithExplicitSupply(params, 2, genesis.hash(), params.genesisDifficulty(),
            2000L, PublicAddress.random(), 0L);
        store.append(tip);

        NodeStores stores = TestNodeStores.mixing(new InMemoryLedger(), store, new InMemoryNonceStore());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ChainEngine.boot(params, stores, snapshot).build());
        assertTrue(ex.getMessage().contains(params.networkName()),
            "message must name the network: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(java.io.File.separator),
            "message must not contain a data-directory path: " + ex.getMessage());
    }

    /** A mined block carrying an explicit, arbitrary committed supply (unlike {@link #mineAbsent},
     *  which always sets {@code SUPPLY_ABSENT}) -- used to manufacture a tip whose declared
     *  supply is irrelevant to what is under test. */
    private static BlockImpl mineWithExplicitSupply(NetworkParameters params, long height,
            SHA256Hash parentHash, int difficulty, long timestamp, PublicAddress miner, long supply) {
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(timestamp)
            .difficulty(difficulty).lastBlockHash(parentHash)
            .supply(supply).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    private static BlockImpl mineAbsent(NetworkParameters params, long height, SHA256Hash parentHash,
            int difficulty, long timestamp, PublicAddress miner) {
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(timestamp)
            .difficulty(difficulty).lastBlockHash(parentHash)
            .supply(BlockImpl.SUPPLY_ABSENT).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }
}
