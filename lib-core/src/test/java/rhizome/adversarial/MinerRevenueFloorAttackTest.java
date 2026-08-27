package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainEngineTestAccess;
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.EmissionCurve;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
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
 * Attacks on the miner revenue floor's endgame regime (FLOOR family — see docs/adversarial/spec.md).
 * The floor (research.md Decision 3) turns the curve's terminal region into a flat {@code R_min}
 * subsidy: from the crossover {@code S ≈ 0.9669 × S*} onward the dispatched base reward is exactly
 * {@code R_min}, never zero, never negative. The family proves the floor holds as a consensus fact
 * across the whole reachable domain (FLOOR-01), that a validator enforces the floored schedule
 * exactly before proof-of-work (FLOOR-02), that uncle/nephew rewards floor through the base with no
 * second clamp (FLOOR-03), and that a non-positive floor refuses to build (FLOOR-04).
 */
class MinerRevenueFloorAttackTest {

    /**
     * FLOOR-01 — Across the reachable supply domain, including wire-legal extreme supplies (near
     * {@code Long.MAX_VALUE}), the dispatched reward floors at exactly {@code R_min}: never below
     * it, never negative; and when an extreme supply makes the accounting identity's own sum
     * overflow, checked arithmetic rejects loudly rather than wrapping into a value that happens
     * to match (SI-5).
     */
    @Test
    void theDispatchedRewardFloorsAtExactlyRMinAcrossTheReachableDomain() {
        NetworkParameters params = CurveActiveNetwork.curveActiveTestnet();
        long floor = params.minerRevenueFloor();
        assertTrue(floor > 0, "the floor must be strictly positive for the invariant to mean anything");
        long height = 1;
        long sStar = params.supplyTarget();
        long stepWidth = sStar / params.emissionTableSteps();
        EmissionCurve curve = EmissionCurve.build(sStar, params.emissionCoefficient(),
            params.emissionTableSteps());

        // Every table-step boundary (a unit either side catches boundary effects) and each segment
        // midpoint.
        for (int i = 1; i <= params.emissionTableSteps(); i++) {
            long s = Math.multiplyExact((long) i, stepWidth);
            assertFloored(params, curve, height, floor, s - 1);
            assertFloored(params, curve, height, floor, s);
            assertFloored(params, curve, height, floor, s + 1);
            assertFloored(params, curve, height, floor, s - stepWidth / 2);
        }

        // The crossover band where raw(S) first drops below R_min (S ≈ S*/exp(R_min/c)) — the
        // floor becomes the whole schedule there; probe a window wide enough to bracket the
        // crossing on both sides.
        long crossover = (long) (sStar / Math.exp((double) floor / params.emissionCoefficient()));
        for (long delta = -200_000; delta <= 200_000; delta += 50) {
            assertFloored(params, curve, height, floor, Math.addExact(crossover, delta));
        }

        // S* itself (raw exactly 0) and just above it.
        assertFloored(params, curve, height, floor, sStar);
        assertFloored(params, curve, height, floor, sStar + 1);

        // Deep ratio-mirror branch and wire-legal extremes: far above S* the mirror maps back
        // toward the table head, where raw is a large negative value — the floor must hold there
        // too, including supplies near Long.MAX_VALUE.
        for (long k : new long[] {2L, 10L, 100L, 1_000L}) {
            assertFloored(params, curve, height, floor, Math.multiplyExact(sStar, k));
        }
        for (long s : new long[] {Long.MAX_VALUE / 2, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
            assertFloored(params, curve, height, floor, s);
        }

        // SI-5: an extreme (wire-legal) supply makes parent.supply + Issuance.minted(...)
        // overflow signed 64-bit arithmetic. The honest floored coinbase — R_min — overflows the
        // accounting identity; Math.addExact must convert that into a loud INVALID_SUPPLY
        // rejection, never a wrap into a value that could coincidentally pass the exact-match
        // check. Mirrors SupplyLedgerAttackTest's SUPPLY-04 shape, on the curve-active profile.
        PublicAddress whale = PublicAddress.random();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(whale, new TransactionAmount(Long.MAX_VALUE - floor + 1));

        AtomicLong clock = new AtomicLong(0L);
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
        assertEquals(Long.MAX_VALUE - floor + 1, engine.headerAt(1).supply(),
            "genesis commits the whale-funded snapshot's total supply verbatim");

        PublicAddress miner = PublicAddress.random();
        long nextHeight = engine.height() + 1;
        long ts = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        BlockImpl overflowing = coinbaseBlock(params, nextHeight, engine.tipHash(), engine.difficulty(),
            ts, miner, floor, engine.headerAt(1).supply());
        long heightBefore = engine.height();
        assertEquals(ExecutionStatus.INVALID_SUPPLY, engine.addBlock(overflowing),
            "parent.supply + Issuance.minted(...) overflows signed 64-bit arithmetic; checked "
                + "arithmetic must reject loudly (INVALID_SUPPLY), never wrap into a false match");
        assertEquals(heightBefore, engine.height(), "the overflowing block must not extend the chain");
    }

    /**
     * FLOOR-02 — On a curve-active in-memory chain pushed above {@code S*}, a validator enforces
     * the floored schedule exactly (research.md Decision 6 item 5): a block paying {@code 0}
     * (feature 03's interim rule), {@code R_min - 1}, or {@code R_min + 1} is rejected
     * {@code INCORRECT_MINING_FEE}, and a block paying exactly {@code R_min} is accepted. Above
     * the target the floored base is the ONLY legal coinbase — no other value is exact, so no
     * off-floor claim can mint through the coinbase-exactness gate.
     */
    @Test
    void aValidatorEnforcesTheFlooredScheduleExactlyAboveTheSupplyTarget() {
        NetworkParameters params = CurveActiveNetwork.curveActiveTestnet();
        long floor = params.minerRevenueFloor();

        PublicAddress funded = PublicAddress.random();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(funded, new TransactionAmount(Math.multiplyExact(params.supplyTarget(), 2L)));

        AtomicLong clock = new AtomicLong(0L);
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
        long parentSupply = engine.headerAt(1).supply();
        assertTrue(parentSupply >= params.supplyTarget(),
            "the chain must start at/above S* for the floored regime to be the one under test");

        PublicAddress miner = PublicAddress.random();
        long height = engine.height() + 1;
        long ts = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        long honestSupply = SupplyStamp.next(engine, height, engine.difficulty());

        // Every off-floor claim is rejected with the exactness gate -- zero (the interim rule),
        // one below the floor, one above the floor.
        for (long offFloor : new long[] {0L, floor - 1, floor + 1}) {
            BlockImpl poison = coinbaseBlock(params, height, engine.tipHash(), engine.difficulty(),
                ts, miner, offFloor, honestSupply);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, engine.addBlock(poison),
                "a coinbase of " + offFloor + " above S* must be rejected by the coinbase-exactness "
                    + "gate -- only exactly R_min is legal");
            assertEquals(1, engine.height(), "the rejected block must not extend the chain");
        }

        // Positive control: a block paying exactly R_min is accepted at the same height.
        BlockImpl honest = coinbaseBlock(params, height, engine.tipHash(), engine.difficulty(),
            ts, miner, floor, honestSupply);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(honest),
            "a block paying exactly R_min above S* must be accepted");
        assertEquals(2, engine.height(), "the accepted floored block must extend the chain");
    }

    /**
     * FLOOR-03 — Above {@code S*}, a block referencing an uncle pays uncle/nephew rewards derived
     * from the floored base with unchanged ratios and {@code >>> d} work scaling,
     * {@code block.supply == parent.supply + minted} holds exactly, and popping the block reverses
     * both exactly (research.md Decision 4: no second clamp site — the uncle/nephew floor through
     * the base).
     */
    @Test
    void uncleAndNephewRewardsFloorThroughTheBaseAboveTheSupplyTarget() {
        NetworkParameters params = CurveActiveNetwork.curveActiveTestnet();
        long floor = params.minerRevenueFloor();

        PublicAddress funded = PublicAddress.random();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(funded, new TransactionAmount(Math.multiplyExact(params.supplyTarget(), 2L)));

        AtomicLong clock = new AtomicLong(0L);
        InMemoryLedger ledger = new InMemoryLedger();
        InMemoryChainStore store = new InMemoryChainStore();
        ChainEngine engine = ChainEngine.boot(params, TestNodeStores.mixing(ledger, store), snapshot)
            .clock(clock::get)
            .build();
        assertTrue(engine.headerAt(1).supply() >= params.supplyTarget(),
            "the chain must start at/above S* for the floored regime to be the one under test");

        PublicAddress miner = PublicAddress.random();
        int difficulty = engine.difficulty();

        // The tip must sit at height >= 2 for an orphan to have a grandparent to fork from
        // (BlockUnclesTest's idiom); mine the honest floored height-2 block first.
        long ts2 = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        long honestSupply2 = SupplyStamp.next(engine, 2, difficulty);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(
            coinbaseBlock(params, 2, engine.tipHash(), difficulty, ts2, miner, floor, honestSupply2)));
        long parentSupply = engine.headerAt(2).supply();
        assertTrue(parentSupply >= params.supplyTarget(),
            "the nephew's parent must still sit at/above S*");

        // Register a real orphan sibling of the height-2 tip (forks from the height-1 genesis) so
        // the uncle reference below passes real uncle validation. Its own coinbase is never
        // applied (it is an orphan), so only its hash/difficulty/miner matter.
        PublicAddress uncleMiner = PublicAddress.random();
        long orphanTs = clock.addAndGet(1000L);
        BlockImpl orphan = orphanBlock(params, 2, engine.blockAt(1).hash(), difficulty, orphanTs, uncleMiner);
        engine.registerOrphan(orphan);

        // The nephew at height 3 references the orphan. Its coinbase is the floored BASE only
        // (pass 1 checks coinbase == miningReward(height, parentSupply)); the uncle/nephew rewards
        // are credited separately by payUncleRewards. Its committed supply is the full identity:
        // parent.supply + Issuance.minted(base + uncle + nephew).
        List<UncleRef> uncles = List.of(
            new UncleRef(orphan.hash(), orphan.difficulty(), uncleMiner));
        long height = 3;
        long ts3 = clock.addAndGet(params.desiredBlockTimeSec() * 1000L);
        long minted = Issuance.minted(params, height, parentSupply, difficulty, uncles);
        long supply = Math.addExact(parentSupply, minted);
        long minerBefore = balanceOf(ledger, miner);
        long uncleMinerBefore = balanceOf(ledger, uncleMiner);
        BlockImpl nephew = blockWithUncles(params, height, engine.tipHash(), difficulty, ts3, miner,
            parentSupply, uncles, supply);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nephew),
            "a block paying the floored base plus floored uncle/nephew must be accepted above S*");
        assertEquals(3, engine.height());

        // The supply identity holds exactly -- block.supply == parent.supply + minted.
        assertEquals(supply, nephew.supply());

        // The uncle/nephew amounts are the FLOORED base's fractions, credited with no second clamp:
        // the uncle reference carries the nephew's own difficulty (deficit 0), so scaleRewardToWork
        // is the identity and the deltas are exactly uncleReward/miningReward + nephewReward.
        long expectedUncle = params.uncleReward(height, parentSupply);
        long expectedNephew = params.nephewReward(height, parentSupply);
        assertTrue(expectedUncle > 0 && expectedNephew > 0,
            "the floored base must derive strictly positive uncle/nephew rewards, or this proves nothing");
        assertEquals(uncleMinerBefore + expectedUncle, balanceOf(ledger, uncleMiner),
            "the uncle miner must earn the floored base's uncle fraction");
        assertEquals(minerBefore + floor + expectedNephew, balanceOf(ledger, miner),
            "the nephew miner must earn the floored base plus the floored nephew bonus");

        // Popping reverses exactly -- BOTH the committed supply and every credit the block minted.
        // Asserting only the surviving height-2 header would be vacuous: popping block 3 cannot
        // change block 2's header, so the ledger is where the reversal is actually observable.
        ChainEngineTestAccess.popBlock(engine);
        assertEquals(2, engine.height(), "popping must remove the accepted nephew");
        assertEquals(parentSupply, engine.headerAt(2).supply(),
            "after popping, the chain supply must be exactly the pre-nephew parent supply");
        assertEquals(uncleMinerBefore, balanceOf(ledger, uncleMiner),
            "popping must revert the uncle reward exactly -- no residue above S*");
        assertEquals(minerBefore, balanceOf(ledger, miner),
            "popping must revert the floored coinbase and the nephew bonus exactly");
    }

    /**
     * FLOOR-04 — {@code minerRevenueFloor <= 0} refuses {@code NetworkParameters} construction —
     * the catalogue-cited twin of the refusal test in {@code NetworkParametersTest}, living in an
     * attack suite because {@code AdversarialProtocolTest} scans only {@code rhizome.adversarial.*}
     * (research.md Decision 6 item 4 / T014).
     */
    @Test
    void aNonPositiveMinerRevenueFloorRefusesToBuild() {
        assertThrows(IllegalArgumentException.class,
            () -> CurveActiveNetwork.curveActiveTestnet().toBuilder().minerRevenueFloor(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> CurveActiveNetwork.curveActiveTestnet().toBuilder().minerRevenueFloor(-1).build());
        assertDoesNotThrow(
            () -> CurveActiveNetwork.curveActiveTestnet().toBuilder().minerRevenueFloor(1).build());
    }

    /** A ledger balance that reads 0 for an address with no wallet yet, rather than throwing. */
    private static long balanceOf(InMemoryLedger ledger, PublicAddress address) {
        return ledger.hasWallet(address) ? ledger.getWalletValue(address).amount() : 0L;
    }

    /** Asserts the floor invariant at one supply: reward >= R_min, strictly positive, == max(R_min, raw). */
    private static void assertFloored(NetworkParameters params, EmissionCurve curve,
            long height, long floor, long supply) {
        long raw = curve.raw(supply);
        long reward = params.miningReward(height, supply);
        assertTrue(reward >= floor,
            "reward " + reward + " fell below the floor " + floor + " at supply=" + supply
                + " (raw=" + raw + ")");
        assertTrue(reward > 0, "reward must be strictly positive at supply=" + supply
            + " (raw=" + raw + ")");
        assertEquals(Math.max(floor, raw), reward,
            "reward at supply=" + supply + " must be exactly max(R_min, raw)=" + Math.max(floor, raw)
                + " (raw=" + raw + ")");
    }

    /**
     * A coinbase-only block, hand-mined, with an explicit committed supply — the curve-active
     * twin of {@code SupplyLedgerAttackTest}'s own fixture idiom, except the coinbase is the
     * supply-aware floored reward (DI-12: no block mixes a curve-derived coinbase with a
     * height-only one).
     */
    private static BlockImpl coinbaseBlock(NetworkParameters params, long height, SHA256Hash parentHash,
            int difficulty, long timestamp, PublicAddress miner, long reward, long supply) {
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(timestamp)
            .difficulty(difficulty)
            .lastBlockHash(parentHash)
            .uncles(new ArrayList<>())
            .supply(supply)
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(reward)));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** A mined block extending {@code parentHash} with real PoW and a paying coinbase — orphan material. */
    private static BlockImpl orphanBlock(NetworkParameters params, long height, SHA256Hash parentHash,
            int difficulty, long timestamp, PublicAddress miner) {
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(timestamp)
            .difficulty(difficulty)
            .lastBlockHash(parentHash)
            .uncles(new ArrayList<>())
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /** A mined block carrying {@code uncles}, committing the exact supply identity. */
    private static BlockImpl blockWithUncles(NetworkParameters params, long height, SHA256Hash parentHash,
            int difficulty, long timestamp, PublicAddress miner, long parentSupply,
            List<UncleRef> uncles, long supply) {
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(timestamp)
            .difficulty(difficulty)
            .lastBlockHash(parentHash)
            .uncles(new ArrayList<>(uncles))
            .supply(supply)
            .build();
        // The coinbase is the floored BASE only -- pass 1's exactness check compares it against
        // miningReward(height, parentSupply); uncle/nephew are credited separately.
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height, parentSupply))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }
}
