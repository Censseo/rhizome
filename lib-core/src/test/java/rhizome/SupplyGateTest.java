package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.HeaderChain;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyGate;
import rhizome.core.blockchain.SupplyGate.Verdict;
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
 * The ONE supply rule (009-native-coin-burn, data-model.md §6): the three former byte-for-byte
 * duplicates — {@code ChainEngine.checkSupply}, {@code HeaderChain.checkSupply} and the boot
 * check's rule 6 (registry OI-4, open since 002) — now all enforce the rule
 * {@link SupplyGate} states. This is the regression that makes OI-4 unrepeatable: whatever the
 * rule ever becomes, the three gates must accept and reject the SAME inputs, each through its
 * own failure surface.
 */
class SupplyGateTest {

    private final NetworkParameters params = NetworkParameters.testnet();
    private final AtomicLong clock = new AtomicLong(0L);
    private final PublicAddress miner = PublicAddress.random();

    /** A coinbase-only block, hand-mined, with an explicit committed supply. */
    private static Block mineOnto(NetworkParameters params, long height, SHA256Hash parentHash,
            int difficulty, long timestamp, PublicAddress miner, long supply) {
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(timestamp)
            .difficulty(difficulty)
            .lastBlockHash(parentHash)
            .uncles(new ArrayList<>())
            .supply(supply)
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    private long honestSupplyForHeight2() {
        return 0L + params.miningReward(2); // genesis supply 0 (empty snapshot), no uncles
    }

    private long nextTimestamp() {
        return clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
    }

    private ChainEngine bootEngine(InMemoryLedger ledger, InMemoryChainStore store,
            LedgerSnapshot snapshot) {
        return ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
    }

    @Test
    void theThreeSupplyGatesEnforceTheIdenticalRule() {
        long honest = honestSupplyForHeight2();
        long over = honest + 1;
        long under = honest - 1;

        // ---- Gate 1: the boot check (rule 6). ----
        // A store whose tip is the honest block boots; the same tip over- or under-committed by
        // one base unit refuses, with the schedule-mismatch refusal either way.
        for (long supply : new long[] {honest, over, under}) {
            InMemoryLedger ledger = new InMemoryLedger();
            InMemoryChainStore store = new InMemoryChainStore();
            LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
            Block genesis = GenesisBlock.build(params, snapshot);
            store.append(genesis);
            store.append(mineOnto(params, 2, genesis.hash(), params.genesisDifficulty(),
                genesis.timestamp() + params.desiredBlockTimeSec() * 1000L, miner, supply));
            if (supply == honest) {
                assertDoesNotThrowBoot(ledger, store, snapshot);
            } else {
                IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> bootEngine(ledger, store, snapshot));
                assertTrue(refused.getMessage().contains("expected supply " + honest),
                    "rule 6 must keep its expected/found refusal message: "
                        + refused.getMessage());
            }
        }

        // ---- Gate 2: the engine's addBlock. ----
        InMemoryLedger engineLedger = new InMemoryLedger();
        InMemoryChainStore engineStore = new InMemoryChainStore();
        ChainEngine engine = bootEngine(engineLedger, engineStore,
            new LedgerSnapshot("t", 0, params.chainId()));
        long ts = nextTimestamp();
        assertEquals(ExecutionStatus.INVALID_SUPPLY,
            engine.addBlock(mineOnto(params, 2, engine.tipHash(), engine.difficulty(), ts, miner, over)));
        assertEquals(ExecutionStatus.INVALID_SUPPLY,
            engine.addBlock(mineOnto(params, 2, engine.tipHash(), engine.difficulty(), ts, miner, under)));
        assertEquals(1, engine.height(), "neither forgery may extend the chain");
        Block honestBlock = mineOnto(params, 2, engine.tipHash(), engine.difficulty(), ts, miner, honest);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(honestBlock));
        assertEquals(2, engine.height());

        // ---- Gate 3: header-only sync. ----
        // The honest header validates; the same header over-/under-committed by one base unit is
        // rejected INVALID_SUPPLY at exactly that height.
        BlockHeader parent = engine.headerAt(1);
        BlockHeader good = engine.headerAt(2);
        assertEquals(Verdict.OK, SupplyGate.check(params, good.id(), parent.supply(),
            good.supply(), good.difficulty(), good.uncles()));
        List<BlockHeader> rejected = new ArrayList<>();
        for (long supply : new long[] {over, under}) {
            BlockHeader forged;
            do {
                var b = (BlockImpl) BlockImpl.builder().id(2).timestamp(good.timestamp())
                    .difficulty(good.difficulty()).lastBlockHash(parent.hash())
                    .merkleRoot(good.merkleRoot()).nonce(SHA256Hash.random())
                    .supply(supply).uncles(new ArrayList<>()).build();
                forged = BlockHeader.of(b);
            } while (forged.verifyNonce(params.powAlgorithm()));
            rejected.add(forged);
        }
        for (BlockHeader forged : rejected) {
            HeaderChain.Result r = HeaderChain.validate(params, engine::headerAt, 1,
                List.of(forged), clock.get());
            assertEquals(HeaderChain.Rejection.INVALID_SUPPLY, r.rejection(),
                "the header gate must reject the same forgery the engine rejects");
            assertEquals(2, r.rejectedHeight());
        }
        HeaderChain.Result honestResult = HeaderChain.validate(params, engine::headerAt, 1,
            List.of(good), clock.get());
        assertTrue(honestResult.valid(),
            "the honest header validates: " + honestResult.rejection());
    }

    private void assertDoesNotThrowBoot(InMemoryLedger ledger, InMemoryChainStore store,
            LedgerSnapshot snapshot) {
        ChainEngine engine = bootEngine(ledger, store, snapshot);
        assertEquals(2, engine.height());
        assertEquals(honestSupplyForHeight2(), engine.headerAt(2).supply());
    }

    // ---- Verdict-level checks on the shared gate itself ----

    @Test
    void theGateIsHeaderOnlyAndPrefixClosed() {
        long supply = params.miningReward(2);
        int difficulty = params.genesisDifficulty();
        assertEquals(Verdict.OK,
            SupplyGate.check(params, 2, BlockImpl.SUPPLY_ABSENT, BlockImpl.SUPPLY_ABSENT,
                difficulty, List.of()),
            "an all-absent chain satisfies prefix closure");
        assertEquals(Verdict.ABSENT_MISMATCH,
            SupplyGate.check(params, 2, BlockImpl.SUPPLY_ABSENT, 0, difficulty, List.of()),
            "a mid-chain start is refused");
        assertEquals(Verdict.NEGATIVE,
            SupplyGate.check(params, 2, supply, BlockImpl.SUPPLY_ABSENT, difficulty, List.of()),
            "a dropped commitment under a committed parent is refused");
        assertEquals(Verdict.OUT_OF_BOUND,
            SupplyGate.check(params, 2, 0, supply + 1, difficulty, List.of()),
            "an over-mint is outside the bound");
        assertEquals(Verdict.OUT_OF_BOUND,
            SupplyGate.check(params, 2, 0, supply - 1, difficulty, List.of()),
            "an under-mint is outside the bound");
        // Overflow: a parent supply so large that parent + minted cannot be represented is a
        // rejection verdict, never a wrapped comparison.
        assertEquals(Verdict.OVERFLOW,
            SupplyGate.check(params, 2, Long.MAX_VALUE, Long.MAX_VALUE, difficulty, List.of()));
    }

    @Test
    void theBoundCollapsesToExactEqualityWhereNoBurnIsPossible() {
        // SC-006 / S-3: wherever debt(h) == 0 the bound `0 <= burned <= debt` admits exactly one
        // supply — the exact pre-burn value — so today's rule survives verbatim in every regime
        // where nothing can burn: pre-activation heights, profiles that never schedule the
        // curve, and curve-active heights at or below the live target.
        //
        // (a) A profile that never schedules the curve.
        long geometric = params.miningReward(2);
        int difficulty = params.genesisDifficulty();
        assertEquals(Verdict.OK, SupplyGate.check(params, 2, 0, geometric, difficulty, List.of()));
        assertEquals(Verdict.OUT_OF_BOUND,
            SupplyGate.check(params, 2, 0, geometric + 1, difficulty, List.of()));
        assertEquals(Verdict.OUT_OF_BOUND,
            SupplyGate.check(params, 2, 0, geometric - 1, difficulty, List.of()));

        // (b) A curve-active profile, parent supply at or below the live target: debt 0.
        NetworkParameters active = rhizome.core.blockchain.CurveActiveNetwork.curveActiveTestnet();
        long belowTarget = 500_000L;
        long minted = Issuance.minted(active, 2, belowTarget, difficulty, List.of());
        assertEquals(0, rhizome.core.blockchain.Burn.debt(active, 2, belowTarget, minted),
            "sanity: at/below the live target nothing is owed");
        assertEquals(Verdict.OK,
            SupplyGate.check(active, 2, belowTarget, belowTarget + minted, difficulty, List.of()),
            "the exact pre-burn supply is the ONLY accepted value where debt == 0");
        assertEquals(Verdict.OUT_OF_BOUND,
            SupplyGate.check(active, 2, belowTarget, belowTarget + minted + 1, difficulty, List.of()));
        assertEquals(Verdict.OUT_OF_BOUND,
            SupplyGate.check(active, 2, belowTarget, belowTarget + minted - 1, difficulty, List.of()));
    }

    @Test
    void aTipThatBurnedBootsWhileATipMintedUnderOtherConstantsRefuses() {
        // FR-033 / SC-010: the boot check (rule 6) shares the bound, so a tip that legitimately
        // burned BOOTS — the brick-every-burning-node defect cannot exist — while a tip minted
        // under a DIFFERENT reward schedule still refuses, exactly as before: the relaxation
        // must not open the boot door to schedule mismatches.
        //
        // (a) A burning tip boots.
        NetworkParameters active = rhizome.core.blockchain.CurveActiveNetwork.curveActiveTestnet();
        PublicAddress burningMiner = PublicAddress.random();
        InMemoryLedger ledgerA = new InMemoryLedger();
        InMemoryChainStore storeA = new InMemoryChainStore();
        AtomicLong clockA = new AtomicLong(1_000_000L);
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, active.chainId());
        var pair = rhizome.crypto.Crypto.generateKeyPairTyped();
        PublicAddress sender = PublicAddress.of(pair.publicKey());
        snapshot.put(sender, new TransactionAmount(20_000_000L));
        ChainEngine engineA = ChainEngine.boot(active, TestNodeStores.mixing(ledgerA, storeA), snapshot)
            .clock(clockA::get)
            .build();
        long parent = engineA.headerAt(1).supply();
        long minted = active.miningReward(2, parent);
        long debt = rhizome.core.blockchain.Burn.debt(active, 2, parent, minted);
        assertTrue(debt > 0, "sanity: the fixture sits above the live target");
        // One real fee so the pool is live: burned = min(⌊pool/2⌋, debt) = 10_000 — inside the
        // bound, far below the debt cap, which is the common case an honestly-burning tip is in.
        long fee = 20_000;
        long burned = fee / 2;
        var b = (BlockImpl) BlockImpl.builder()
            .id(2)
            .timestamp(clockA.addAndGet(active.desiredBlockTimeSec() * 1000L))
            .difficulty(engineA.difficulty())
            .lastBlockHash(engineA.tipHash())
            .supply(parent + minted - burned)
            .build();
        b.addTransaction(Transaction.of(burningMiner, new TransactionAmount(minted)));
        Transaction feeTx = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(0),
            pair.publicKey(), new TransactionAmount(fee), clockA.get(), active.chainId(), 0);
        feeTx.sign(pair.privateKey());
        b.addTransaction(feeTx);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), active.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engineA.addBlock(b),
            "the burning block must be accepted by the engine first");
        assertTrue(engineA.headerAt(2).supply() < parent + minted,
            "sanity: the tip really burned");

        // Reboot over the same store: rule 6 now enforces the BOUND — the burned tip is inside
        // it, so the node boots. Under the old exact-equality rule 6 this boot REFUSED, which
        // is precisely the defect the bound exists to remove.
        ChainEngine reboot = ChainEngine.boot(active, TestNodeStores.mixing(new InMemoryLedger(), storeA),
                snapshot)
            .clock(clockA::get)
            .build();
        assertEquals(parent + minted - burned, reboot.headerAt(2).supply(),
            "the burned tip boots and reads back exactly what it committed");

        // (b) A tip minted under other constants refuses. Mine one honest geometric block, then
        // boot the same store under a halved reward schedule: the tip now over-commits relative
        // to the constants in force (burned would be NEGATIVE), and the refusal fires.
        InMemoryLedger ledgerB = new InMemoryLedger();
        InMemoryChainStore storeB = new InMemoryChainStore();
        LedgerSnapshot snapshotB = new LedgerSnapshot("t", 0, params.chainId());
        ChainEngine engineB = ChainEngine.boot(params, TestNodeStores.mixing(ledgerB, storeB), snapshotB)
            .clock(clockA::get)
            .build();
        long honest = params.miningReward(2);
        Block honestBlock = mineOnto(params, 2, engineB.tipHash(), engineB.difficulty(),
            clockA.addAndGet(params.desiredBlockTimeSec() * 1000L), miner, honest);
        assertEquals(ExecutionStatus.SUCCESS, engineB.addBlock(honestBlock));

        NetworkParameters otherSchedule = params.toBuilder()
            .initialReward(params.initialReward() / 2)
            .build();
        long expectedUnderOtherSchedule = otherSchedule.miningReward(2); // genesis supply 0, no uncles
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> ChainEngine.boot(otherSchedule, TestNodeStores.mixing(new InMemoryLedger(), storeB),
                    snapshotB)
                .clock(clockA::get)
                .build());
        assertTrue(refused.getMessage().contains("expected supply " + expectedUnderOtherSchedule),
            "the schedule-mismatch refusal keeps its expected/found message: "
                + refused.getMessage());
    }
}
