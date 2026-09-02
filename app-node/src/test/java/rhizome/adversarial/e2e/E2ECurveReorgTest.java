package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.EmissionCurve;
import rhizome.core.blockchain.Issuance;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.node.RhizomeNode;

/**
 * End-to-end reorg proofs for the supply-driven logarithmic emission curve (§ integer log curve),
 * covering the boundaries a fork/reorg can cross that a single-branch proof never can: the point
 * where a real chain's parent supply moves from the curve's positive (interpolated) branch to its
 * negative (mirrored) one, repeated crossings of that same frontier, the curve's own activation
 * height, and the real mainnet-calibrated 256-step table under a real reorg.
 */
class E2ECurveReorgTest {

    @TempDir
    Path tempDir;

    /**
     * A profile tuned so a real chain CAN cross {@code supplyTarget} through ordinary honest
     * mining within a couple of blocks — {@code TestNetwork.CURVE_ACTIVE}'s real (16-step,
     * moderate-coefficient) table crosses it too now (under the revenue floor honest mining pays
     * {@code R_min} per block from the crossover on and keeps going past the target), but this
     * coarse 2-step table with a large-enough coefficient makes the crossing happen
     * deterministically within two blocks. Tuned so the FIRST block (from supply 0) pays a genuine,
     * interpolated curve value that stays below the target (leaving room for a real, un-crossed
     * 1-block branch to fork against), while a SECOND block from there crosses it.
     *
     * <p>The coefficient must keep {@code table[1] = floor(c * ln 2)} STRICTLY ABOVE
     * {@link NetworkParameters#minerRevenueFloor} (800 base units, inherited from mainnet through
     * {@code testnet()}), or the whole two-entry table sits under the floor and every block on this
     * profile pays a constant {@code R_min} — which would make this file's crossing and
     * repeated-reorg proofs vacuous: with a flat reward, reusing the losing block's own coinbase
     * and re-deriving {@code miningReward} are indistinguishable, so the very drift E2E-74 exists
     * to catch could not show up. {@code c = 1300} gives {@code table[1] = 901}: above the floor,
     * and below {@code supplyTarget} so the first block still leaves the branch un-crossed.
     */
    private static NetworkParameters crossableProfile() {
        return TestNetwork.FAST.toBuilder()
            .supplyTarget(1000L)
            .emissionCoefficient(1300L)
            .emissionTableSteps(2)
            .emissionCurveHeight(1)
            .build();
    }

    /**
     * E2E-74 — Force a real fork-then-reorg whose boundary is exactly the block whose parent
     * supply crosses {@code supplyTarget}: the losing branch (one honestly-mined block) stays on
     * the curve's positive side; the winning, heavier branch (two honestly-mined blocks) crosses
     * into the negative/mirrored side on its second block. Verifies {@code Executor.rollbackBlock}
     * reuses the losing block's own already-validated coinbase amount (never re-derives
     * {@code miningReward}, which is not a pure function of height alone under the curve —
     * {@code Executor.java}'s own comment on this) even at this exact crossing boundary, and that
     * the reorg-losing node ends up in EXACT agreement with the node that mined the winning branch
     * natively.
     */
    @Test
    void aReorgThatSwapsBranchesAcrossTheSupplyTargetCrossingReversesAndReappliesRewardsExactly()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = crossableProfile();
            assertCurveNotSwallowedByTheFloor(params);

            // No live producers: both branches are built deterministically and single-threaded, so
            // this test controls exactly how many blocks each side has before they ever meet --
            // the asymmetry the crossing depends on.
            RhizomeNode shortBranch = network.node("short").params(params).start();
            E2EFixtures.mintEmpty(shortBranch, PublicAddress.random(), 1);
            assertEquals(2, shortBranch.engine().height());
            long shortSupply = shortBranch.engine().headerAt(2).supply();
            assertTrue(shortSupply >= 0 && shortSupply < params.supplyTarget(),
                "the losing branch must stay on the curve's positive side: supply=" + shortSupply
                    + ", target=" + params.supplyTarget());

            RhizomeNode longBranch = network.node("long").params(params).start();
            E2EFixtures.mintEmpty(longBranch, PublicAddress.random(), 2);
            assertEquals(3, longBranch.engine().height());
            long longSupplyBeforeCrossing = longBranch.engine().headerAt(2).supply();
            long longSupplyAfterCrossing = longBranch.engine().headerAt(3).supply();
            assertTrue(longSupplyBeforeCrossing < params.supplyTarget(),
                "the winning branch's own height-2 parent must still be pre-crossing: "
                    + longSupplyBeforeCrossing);
            assertTrue(longSupplyAfterCrossing >= params.supplyTarget(),
                "the winning branch must have genuinely crossed supplyTarget by its own height 3: "
                    + longSupplyAfterCrossing + ", target=" + params.supplyTarget());

            // Peer the shorter, still-positive branch to the heavier, crossed one, and let it reorg.
            shortBranch.service().addPeer(TestNetwork.urlOf(longBranch));
            TestNetwork.await(() -> shortBranch.knownPeers().contains(TestNetwork.urlOf(longBranch)),
                () -> "the short branch never admitted its heavier peer");
            TestNetwork.syncUntil(shortBranch,
                () -> shortBranch.engine().tipHash().equals(longBranch.engine().tipHash()));

            assertEquals(3, shortBranch.engine().height(),
                "the reorg must adopt the full heavier branch, not stop short of the crossing block");
            assertEquals(longBranch.engine().headerAt(3).supply(), shortBranch.engine().headerAt(3).supply(),
                "the reorg-losing node must end up in EXACT supply agreement with the node that mined "
                    + "the crossing branch natively");
            assertEquals(longBranch.engine().headerAt(2).supply(), shortBranch.engine().headerAt(2).supply());
            assertFalse(shortBranch.engine().isDegraded());

            // Liveness: the reorg-losing node can still extend the now-canonical, crossed branch.
            E2EFixtures.mintEmpty(shortBranch, PublicAddress.random(), 1);
            assertEquals(4, shortBranch.engine().height());
        }
    }

    /**
     * Guards the premise every crossing proof in this file rests on: the profile's curve must
     * still pay a genuine, above-floor value on the pre-crossing side. If the miner revenue floor
     * ever rises past {@code table[1]} (or the coefficient falls below it), every block on this
     * profile pays a constant {@code R_min} and the crossing/reorg assertions below all still
     * pass while proving nothing — the silent kind of coverage loss this must fail loudly on.
     */
    private static void assertCurveNotSwallowedByTheFloor(NetworkParameters params) {
        long preCrossingReward = params.miningReward(2, 0L);
        assertTrue(preCrossingReward > params.minerRevenueFloor(),
            "the pre-crossing reward " + preCrossingReward + " is not above the revenue floor "
                + params.minerRevenueFloor() + " -- this profile's whole table sits under the floor, "
                + "so every block pays a constant R_min and the crossing proofs are vacuous");
        assertTrue(preCrossingReward < params.supplyTarget(),
            "the first block must leave the branch un-crossed: reward " + preCrossingReward
                + " >= supplyTarget " + params.supplyTarget());
    }

    /**
     * As {@code E2EEmissionCurveTest}/{@code E2ECurveBoundaryTest}'s own private helper: a
     * fully-mined block whose header {@code supply} is the honest curve-aware stamp but whose
     * coinbase pays {@code forgedReward}.
     */
    private static BlockImpl mineWithForgedCoinbase(RhizomeNode node, PublicAddress miner, long forgedReward) {
        NetworkParameters params = node.engine().params();
        long height = node.engine().height() + 1;
        BlockImpl block = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(node.engine().nextBlockTimestamp(System.currentTimeMillis()))
            .difficulty(node.engine().difficulty())
            .lastBlockHash(node.engine().tipHash())
            .supply(SupplyStamp.next(node.engine(), height, node.engine().difficulty()))
            .build();
        block.addTransaction(Transaction.of(miner, new TransactionAmount(forgedReward)));
        MerkleTree tree = new MerkleTree();
        tree.setItems(block.transactions());
        block.merkleRoot(tree.getRootHash());
        node.engine().stampStateRoot(block);
        block.nonce(Miner.mineNonce(block.hash(), block.difficulty(),
            params.powAlgorithm(), params.powCostsAt(height)));
        return block;
    }

    /**
     * E2E-80 — Repeats a real fork-and-reorg at the {@code supplyTarget} frontier (the same
     * {@link #crossableProfile} shape as this file's own crossing proof) fifteen times in a row on
     * the same live chain: each round {@code shortBranch} mines one block that is discarded,
     * {@code longBranch} mines two blocks honestly extending the shared tip and is never itself
     * reorged, and {@code shortBranch} then reorgs onto {@code longBranch}. Verified in the code:
     * {@code EmissionCurve.raw} — including the {@code +1} ceiling correction its own javadoc
     * documents for the mirrored branch above {@code supplyTarget} — is a stateless function of
     * {@code supply} alone, recomputed fresh on every call with no memory of how many times a
     * position has previously been reorged past. So {@code longBranch} (the "supply recomputed
     * from zero", never itself reorged) and {@code shortBranch} (the "supply committed after N
     * repeated reorgs") must agree EXACTLY at every stabilized height — not merely within a bound
     * that could grow with N — which this test checks at every single height, not just the final
     * one, after genuinely crossing {@code supplyTarget} during the loop.
     */
    @Test
    void repeatedReorgsAcrossTheSupplyTargetFrontierDoNotCompoundTheBoundaryDiscontinuity() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = crossableProfile();
            assertCurveNotSwallowedByTheFloor(params);

            RhizomeNode shortBranch = network.node("short").params(params).start();
            RhizomeNode longBranch = network.node("long").params(params).start();
            shortBranch.service().addPeer(TestNetwork.urlOf(longBranch));
            TestNetwork.await(() -> shortBranch.knownPeers().contains(TestNetwork.urlOf(longBranch)),
                () -> "the short branch never admitted its heavier peer");

            int rounds = 15;
            boolean crossedDuringLoop = false;
            for (int round = 0; round < rounds; round++) {
                long beforeSupply = longBranch.engine().headerAt(longBranch.engine().height()).supply();

                // Discarded every round: shortBranch's own single-block extension of the shared tip.
                E2EFixtures.mintEmpty(shortBranch, PublicAddress.random(), 1);
                // Never itself reorged: longBranch's honest, two-block extension of the SAME shared
                // tip -- this is the "recomputed from zero" reference the whole proof hinges on.
                E2EFixtures.mintEmpty(longBranch, PublicAddress.random(), 2);

                long afterSupply = longBranch.engine().headerAt(longBranch.engine().height()).supply();
                if (beforeSupply < params.supplyTarget() && afterSupply >= params.supplyTarget()) {
                    crossedDuringLoop = true;
                }

                TestNetwork.syncUntil(shortBranch,
                    () -> shortBranch.engine().tipHash().equals(longBranch.engine().tipHash()));
                assertEquals(longBranch.engine().height(), shortBranch.engine().height(),
                    "round " + round + ": the reorg must adopt the full heavier branch");
                assertFalse(shortBranch.engine().isDegraded(),
                    "round " + round + " left the repeatedly-reorged node degraded");
            }
            assertTrue(crossedDuringLoop,
                "the loop must genuinely cross supplyTarget at least once for this proof to mean anything");

            // The core assertion: zero drift at EVERY height, not just the final one -- proving the
            // gap between "recomputed from zero" and "committed after N repeated reorgs" stays
            // exactly 0 (trivially bounded, never O(N)) across fifteen real reorgs at this frontier.
            long finalHeight = longBranch.engine().height();
            for (long h = 1; h <= finalHeight; h++) {
                assertEquals(longBranch.engine().headerAt(h).supply(), shortBranch.engine().headerAt(h).supply(),
                    "height " + h + ": fifteen repeated reorgs must not accumulate any drift from the "
                        + "never-reorged reference chain");
            }

            // Liveness: the repeatedly-reorged node can still extend the now-canonical chain.
            E2EFixtures.mintEmpty(shortBranch, PublicAddress.random(), 1);
            assertEquals(finalHeight + 1, shortBranch.engine().height());
            assertFalse(shortBranch.engine().isDegraded());
        }
    }

    /**
     * A profile whose curve activates at height 3 rather than height 1 — like
     * {@code E2ECurveBoundaryTest}'s own boundary profiles — so a fork can share a genuinely
     * pre-activation height-2 prefix and diverge exactly at the activation height itself.
     */
    private static NetworkParameters activationBoundaryProfile() {
        return TestNetwork.CURVE_ACTIVE.toBuilder().emissionCurveHeight(3).build();
    }

    /**
     * E2E-81 — Forces a real reorg whose boundary lands exactly on the curve's own activation
     * height: {@code longBranch} mines an honest pre-activation height-2 block, then the genuine
     * height-3 block where activation lands; {@code shortBranch} only ever reaches height 2 on its
     * own and learns about height 3 purely through the reorg. A block's own height is fixed at
     * construction ({@code BlockImpl.id}/{@code lastBlockHash} pin its position), so this codebase
     * has no literal way to "reassign" one already-mined block to a different height after the
     * fact — the scenario is instead built as the plan's own underlying concern spelled out
     * concretely: an attacker recycling a reward amount computed under the STALE, pre-activation
     * (geometric, height-only) assumption onto a block honestly claiming the REAL position where
     * activation lands. Verified twice, at both real heights (3 and 4) where this matters: once
     * against {@code longBranch} — the branch about to WIN — before it ever mines the honest block
     * there (so even the winner cannot recycle a stale claim at its own soon-to-be-canonical
     * position), and once against {@code shortBranch} strictly AFTER the reorg, at one further
     * height it only ever learned about via sync — so the rejection is provably judged by the
     * block's REAL height on the now-canonical branch, not by any assumption cached from an
     * earlier, now-superseded local view.
     */
    @Test
    void aReorgThatCrossesTheEmissionCurveActivationHeightPaysTheCurveRateAtTheRealPositionNativelyAndAfterReorg()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = activationBoundaryProfile();
            assertFalse(params.emissionCurveActiveAt(2));
            assertTrue(params.emissionCurveActiveAt(3));

            RhizomeNode longBranch = network.node("long").params(params).start();
            E2EFixtures.mintEmpty(longBranch, PublicAddress.random(), 1); // height 2: still pre-activation
            assertEquals(2, longBranch.engine().height());

            long parentSupplyAtActivation = longBranch.engine().headerAt(2).supply();
            long curveRewardAt3 = params.miningReward(3, parentSupplyAtActivation);
            long staleGeometricClaimAt3 = params.miningReward(3); // height-only: the stale pre-activation formula
            assertNotEquals(curveRewardAt3, staleGeometricClaimAt3,
                "the curve and stale geometric rewards at the activation height must genuinely differ "
                    + "for this proof to mean anything");

            // Even the branch about to WIN must not be allowed to recycle a stale claim at the real
            // position where activation lands.
            BlockImpl nativeForgery = mineWithForgedCoinbase(longBranch, PublicAddress.random(), staleGeometricClaimAt3);
            ExecutionStatus nativeForgeryStatus = longBranch.service().submitBlock(nativeForgery);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, nativeForgeryStatus,
                "a stale, pre-activation-shaped reward claim must be rejected even on the winning branch");
            assertEquals(2, longBranch.engine().height());
            assertFalse(longBranch.engine().isDegraded());

            Block honestActivationBlock = E2EFixtures.mint(longBranch, PublicAddress.random());
            assertEquals(3, longBranch.engine().height());
            assertEquals(curveRewardAt3, honestActivationBlock.transactions().get(0).amount().amount());

            // shortBranch never reaches the activation height on its own -- it only ever learns
            // about it through the reorg below.
            RhizomeNode shortBranch = network.node("short").params(params).start();
            E2EFixtures.mintEmpty(shortBranch, PublicAddress.random(), 1); // its own pre-activation height 2
            assertEquals(2, shortBranch.engine().height());
            assertEquals(parentSupplyAtActivation, shortBranch.engine().headerAt(2).supply(),
                "the pre-activation reward is purely height-based, so both branches' height-2 "
                    + "committed supply must agree exactly regardless of which node mined it");

            shortBranch.service().addPeer(TestNetwork.urlOf(longBranch));
            TestNetwork.await(() -> shortBranch.knownPeers().contains(TestNetwork.urlOf(longBranch)),
                () -> "the short branch never admitted its heavier peer");
            TestNetwork.syncUntil(shortBranch,
                () -> shortBranch.engine().tipHash().equals(longBranch.engine().tipHash()));

            assertEquals(3, shortBranch.engine().height(),
                "the reorg must adopt the block that genuinely occupies the activation height");
            assertEquals(honestActivationBlock.hash(), shortBranch.engine().blockAt(3).hash());
            assertEquals(curveRewardAt3, shortBranch.engine().blockAt(3).transactions().get(0).amount().amount(),
                "the reorg-adopting node must read back exactly the curve rate at the real activation "
                    + "height it just adopted, not whatever its own prior pre-activation local state assumed");
            assertFalse(shortBranch.engine().isDegraded());

            // The same forged, stale-reward recycling attempt, one height further, submitted this
            // time to the node that only ever learned this branch via reorg -- the rejection must
            // be judged the same way regardless of how the validating node arrived at this history.
            long parentSupplyAtHeight3 = shortBranch.engine().headerAt(3).supply();
            long curveRewardAt4 = params.miningReward(4, parentSupplyAtHeight3);
            long staleGeometricClaimAt4 = params.miningReward(4);
            assertNotEquals(curveRewardAt4, staleGeometricClaimAt4,
                "the curve and stale geometric rewards one block past activation must genuinely differ too");
            BlockImpl postReorgForgery = mineWithForgedCoinbase(shortBranch, PublicAddress.random(), staleGeometricClaimAt4);
            ExecutionStatus postReorgStatus = shortBranch.service().submitBlock(postReorgForgery);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, postReorgStatus,
                "the reorg-adopting node must reject the same stale recycling attempt just as the "
                    + "originating node did");
            assertEquals(3, shortBranch.engine().height());
            assertFalse(shortBranch.engine().isDegraded());

            // Positive control: liveness continues correctly on the reorg-adopting node too.
            Block honestNext = E2EFixtures.mint(shortBranch, PublicAddress.random());
            assertEquals(4, shortBranch.engine().height());
            assertEquals(curveRewardAt4, honestNext.transactions().get(0).amount().amount());
            assertFalse(shortBranch.engine().isDegraded());
        }
    }

    /**
     * {@link TestNetwork#FAST}'s instant PoW/difficulty combined with {@code cleanMainnet()}'s REAL
     * shipped emission constants ({@code supplyTarget}, {@code emissionCoefficient},
     * {@code emissionTableSteps} — the 256-step table, not the toy 16/2-step tables the rest of this
     * file and {@code E2ECurveEmissionTest} use), activated from height 1. Combines the two rather
     * than using {@code cleanMainnet()} wholesale because its real Pufferfish2 PoW at real
     * difficulty would make a multi-node E2E reorg impractically slow — the fine 256-step table
     * calibration is the one property this test needs from mainnet, not its PoW cost.
     */
    private static NetworkParameters mainnetCurveOnFastPow() {
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        return TestNetwork.FAST.toBuilder()
            .supplyTarget(mainnet.supplyTarget())
            .emissionCoefficient(mainnet.emissionCoefficient())
            .emissionTableSteps(mainnet.emissionTableSteps())
            .emissionCurveHeight(1)
            .build();
    }

    private static void admitEachOther(RhizomeNode a, RhizomeNode b) throws InterruptedException {
        a.service().addPeer(TestNetwork.urlOf(b));
        TestNetwork.await(() -> a.knownPeers().contains(TestNetwork.urlOf(b)),
            () -> "peer " + TestNetwork.urlOf(b) + " was never admitted; known: " + a.knownPeers());
    }

    private static long settledHeight(RhizomeNode a, RhizomeNode b) {
        return Math.max(2, Math.min(a.engine().height(), b.engine().height()) - 2);
    }

    /**
     * E2E-82 — Repeats {@code E2ECurveEmissionTest}'s fork-and-converge proof (two real,
     * independently-mining nodes, a genuine partition, real HTTP reorg) but under the REAL shipped
     * mainnet emission calibration at its full 256-step resolution ({@link #mainnetCurveOnFastPow})
     * instead of the toy 16-step table {@code TestNetwork.CURVE_ACTIVE} uses everywhere else in
     * this suite — boosted with a premined genesis so the starting parent supply lands strictly
     * inside the interpolated region of the table, not the flat below-first-step band. The
     * per-position precision bound itself ("≤1 unit, sometimes exactly 1" versus the true,
     * irrational logarithm) is proven at the unit level ({@code EmissionCurveTest}, the 327-vector
     * artifact) and is deliberately out of scope here, exactly as {@code E2EEmissionCurveTest}'s own
     * class javadoc scopes out that layer; what this test adds that no other E2E proof does is
     * confirming that a REAL reorg over the fine, production-scale grid introduces no MORE
     * disagreement between two independently-mining nodes than the toy table already proves in
     * {@code E2ECurveEmissionTest} — i.e. the converged committed supply and, independently, the
     * standalone {@link EmissionCurve} value at the same parent supply (there is no public
     * {@code NetworkParameters.emissionCurve()} accessor to compare against directly) both agree
     * EXACTLY, never merely within the documented ≤1 bound.
     */
    @Test
    void theForkAndConvergeSupplyProofHoldsUnderTheRealMainnetCalibratedTableAtItsFullResolution()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = mainnetCurveOnFastPow();
            long stepWidth = params.supplyTarget() / params.emissionTableSteps();
            // Solidly mid-table: genuinely exercises EmissionCurve's interpolation across the fine
            // 256-step grid, not the flat "below the first step" band curveValue also handles.
            long fundedTotal = Math.multiplyExact(stepWidth, 64L);
            assertTrue(fundedTotal > stepWidth && fundedTotal < params.supplyTarget(),
                "the premined starting supply must land strictly inside the interpolated region "
                    + "for this proof to mean anything");

            E2EFixtures.Identity funded = E2EFixtures.Identity.generate();
            Path snapshot = E2EFixtures.premine(tempDir.resolve("genesis-mainnet-curve.json"), params,
                Map.of(funded, fundedTotal));

            RhizomeNode left = network.node("left").params(params).snapshot(snapshot)
                .mining().blockInterval(150).start();
            RhizomeNode right = network.node("right").params(params).snapshot(snapshot)
                .mining().blockInterval(150).start();

            TestNetwork.awaitHeight(left, 8);
            TestNetwork.awaitHeight(right, 8);
            assertNotEquals(left.engine().blockAt(2).hash(), right.engine().blockAt(2).hash(),
                "the partition must have produced two real, divergent histories");

            admitEachOther(left, right);
            admitEachOther(right, left);

            TestNetwork.syncUntil(List.of(left, right), () -> {
                long settled = settledHeight(left, right);
                return left.engine().blockAt(settled).hash().equals(right.engine().blockAt(settled).hash());
            });

            long settled = settledHeight(left, right);
            assertEquals(left.engine().blockAt(settled).hash(), right.engine().blockAt(settled).hash());
            assertEquals(left.engine().blockAt(2).hash(), right.engine().blockAt(2).hash(),
                "convergence must reach the fork point, not just the recent tail");

            long leftSupply = left.engine().headerAt(settled).supply();
            long rightSupply = right.engine().headerAt(settled).supply();
            assertEquals(leftSupply, rightSupply,
                "two nodes that converged on the same history must read back the same supply, "
                    + "even at the real 256-step table's resolution");

            // Recomputed independently from the converged chain's own headers -- every block's
            // ACTUAL difficulty and ACTUAL (curve-scheduled) issuance, exactly as E2ECurveEmissionTest
            // proves for the toy table.
            long recomputed = left.engine().headerAt(1).supply();
            assertEquals(fundedTotal, recomputed, "genesis must commit the premined total exactly");
            for (long h = 2; h <= settled; h++) {
                var header = left.engine().headerAt(h);
                recomputed = Math.addExact(recomputed, Issuance.minted(
                    left.engine().params(), header.id(), recomputed, header.difficulty(), header.uncles()));
            }
            assertEquals(recomputed, leftSupply,
                "the committed supply must equal the sum of every block's own curve-scheduled "
                    + "issuance since genesis, at the real 256-step resolution too");

            // No uncles are possible yet at height 2 (no prior sibling history exists), so this
            // isolates the base curve value exactly -- cross-checked against a STANDALONE
            // EmissionCurve built from the same three constants, since NetworkParameters exposes no
            // accessor for the one it holds internally.
            long height2Reward = params.miningReward(2, fundedTotal);
            EmissionCurve standalone = EmissionCurve.build(
                params.supplyTarget(), params.emissionCoefficient(), params.emissionTableSteps());
            assertEquals(Math.max(params.minerRevenueFloor(), standalone.raw(fundedTotal, params.supplyTarget())), height2Reward,
                "the NetworkParameters dispatch must pay exactly what a standalone EmissionCurve "
                    + "instance built from the same three constants computes, floored at R_min");
            assertEquals(height2Reward, left.engine().blockAt(2).transactions().get(0).amount().amount(),
                "the real height-2 block must have paid exactly the standalone curve value, with zero "
                    + "drift -- well within, not merely bounded by, the documented ≤1-unit tolerance");

            E2EFixtures.mintEmpty(left, PublicAddress.random(), 1);
            assertTrue(left.engine().height() > settled, "the converged node must still be able to mine");
            assertFalse(left.engine().isDegraded());
            assertFalse(right.engine().isDegraded());
        }
    }
}
