package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * End-to-end proofs for the emission curve's ACTIVATION HEIGHT boundary and cross-restart
 * consistency (§ integer log curve). {@code CurveActiveNetwork.curveActiveTestnet()} and this
 * suite's own {@code TestNetwork.CURVE_ACTIVE} both activate at height 1 — trivially, since
 * genesis carries no coinbase — so no existing fixture has ever exercised a real pre-activation
 * prefix. These tests build one.
 */
class E2ECurveBoundaryTest {

    @TempDir
    Path tempDir;

    /** As {@code E2EEmissionCurveTest}'s own private helper: a fully-mined block whose header
     *  {@code supply} is the honest curve-aware stamp but whose coinbase pays {@code forgedReward}. */
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
     * E2E-69 — Forge a block at {@code emissionCurveHeight - 1} claiming the curve formula instead
     * of the still-in-force geometric one, and its mirror at exactly {@code emissionCurveHeight}
     * claiming the now-stale geometric value instead of the curve — pushed at the real
     * {@code /submit} route. The activation predicate ({@code emissionCurveActiveAt}) must flip
     * EXACTLY at the scheduled height, neither one block early nor one block late.
     */
    @Test
    void theCurveActivatesAtExactlyItsScheduledHeightNeitherOneBlockEarlyNorLateOverTheSubmitRoute()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE.toBuilder().emissionCurveHeight(4).build();
            RhizomeNode node = network.node("victim").params(params).start();
            E2EFixtures.mintEmpty(node, PublicAddress.random(), 1);
            long heightBefore = node.engine().height();
            assertEquals(2, heightBefore);
            assertFalse(params.emissionCurveActiveAt(3));
            assertTrue(params.emissionCurveActiveAt(4));

            // One block EARLY (height 3 = emissionCurveHeight - 1): claim the curve's raw value at
            // this parent supply, which is not yet the rule in force. EmissionCurve.build is a pure
            // function of (supplyTarget, coefficient, steps) alone, so a throwaway profile sharing
            // those three constants but activating one block earlier computes the IDENTICAL curve
            // value -- there is no public accessor for the curve itself (deliberately
            // @Getter(AccessLevel.NONE) on NetworkParameters), so this is the honest way to ask
            // "what would the curve pay here" without duplicating EmissionCurve's own arithmetic.
            long parentSupplyAt3 = node.engine().headerAt(heightBefore).supply();
            NetworkParameters curveActiveOneBlockEarlier = params.toBuilder().emissionCurveHeight(1).build();
            long earlyCurveClaim = curveActiveOneBlockEarlier.miningReward(3, parentSupplyAt3);
            long earlyHonest = params.miningReward(3, parentSupplyAt3);
            assertNotEquals(earlyHonest, earlyCurveClaim,
                "the curve value and the still-in-force geometric value must genuinely differ");

            BlockImpl early = mineWithForgedCoinbase(node, PublicAddress.random(), earlyCurveClaim);
            ExecutionStatus earlyStatus = node.service().submitBlock(early);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, earlyStatus,
                "a curve-formula claim one block before activation must be rejected");
            assertEquals(heightBefore, node.engine().height());

            // The honest block at height 3 (still geometric) is accepted, carrying the chain to the
            // activation height's parent.
            E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(3, node.engine().height());

            // The mirror, one block LATE (height 4 = emissionCurveHeight exactly): claim the now-
            // stale geometric value instead of the curve's.
            long parentSupplyAt4 = node.engine().headerAt(3).supply();
            long lateGeometricClaim = params.miningReward(4);
            long lateHonest = params.miningReward(4, parentSupplyAt4);
            assertNotEquals(lateHonest, lateGeometricClaim,
                "the stale geometric value and the now-active curve value must genuinely differ");

            BlockImpl late = mineWithForgedCoinbase(node, PublicAddress.random(), lateGeometricClaim);
            ExecutionStatus lateStatus = node.service().submitBlock(late);
            assertEquals(ExecutionStatus.INCORRECT_MINING_FEE, lateStatus,
                "a stale geometric claim exactly at the activation height must be rejected");
            assertEquals(3, node.engine().height());
            assertFalse(node.engine().isDegraded());

            // Positive control: the honest, curve-formula block at the activation height itself.
            E2EFixtures.mint(node, PublicAddress.random());
            assertEquals(4, node.engine().height());
        }
    }

    /**
     * E2E-70 — The same activation boundary, over a real headers-only sync instead of {@code
     * /submit}: an honest pre-activation prefix, then a final header claiming the stale geometric
     * reward exactly at the activation height, served by {@link HostilePeer} over a real socket.
     */
    @Test
    void theCurveActivationBoundaryHoldsOverAHostileHeaderSyncToo() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE.toBuilder().emissionCurveHeight(4).build();
            RhizomeNode source = network.node("source").params(params).mining().blockInterval(150).start();
            TestNetwork.awaitHeight(source, 5);

            long prefixTop = 3; // honest, pre-activation prefix: heights 1..3
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (long h = 1; h <= prefixTop; h++) {
                out.writeBytes(HeaderCodec.encode(source.engine().headerAt(h)));
            }
            BlockHeader honestLast = source.engine().headerAt(prefixTop + 1); // height 4: activation
            long parentSupply = source.engine().headerAt(prefixTop).supply();
            long staleGeometricSupply = Math.addExact(parentSupply, params.miningReward(prefixTop + 1));
            assertNotEquals(honestLast.supply(), staleGeometricSupply,
                "the stale geometric commitment must genuinely differ from the honest curve one");
            BlockHeader forged = new BlockHeader(
                honestLast.id(), honestLast.timestamp(), honestLast.difficulty(),
                honestLast.numTransactions(), honestLast.lastBlockHash(), honestLast.merkleRoot(),
                honestLast.nonce(), honestLast.stateRoot(), honestLast.vote(),
                staleGeometricSupply, honestLast.uncles());
            out.writeBytes(HeaderCodec.encode(forged));
            byte[] hostileHeaders = out.toByteArray();

            RhizomeNode victim = network.node("victim").params(params).mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 2);
            long victimHeightBefore = victim.engine().height();

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(source)
                    .claimsHeight(prefixTop + 1)
                    .claimsWork(() -> new JSONObject().put("totalWork",
                        BigInteger.TWO.pow(200).toString()).toString())
                    .servesHeaders(() -> hostileHeaders)
                    .start()) {
                victim.service().addPeer(liar.url());
                TestNetwork.await(() -> victim.knownPeers().contains(liar.url()),
                    () -> "the victim never admitted the hostile peer");
                for (int round = 0; round < 4; round++) {
                    victim.syncRound();
                }

                assertTrue(victim.engine().height() >= victimHeightBefore,
                    "the victim lost chain height to a hostile peer");
                assertFalse(victim.engine().isDegraded(),
                    "the encounter left the victim degraded");

                long activationHeight = prefixTop + 1;
                TestNetwork.await(() -> victim.engine().height() >= activationHeight,
                    () -> "the victim never resumed mining up to the activation height");
                assertNotEquals(forged.hash(), victim.engine().blockAt(activationHeight).hash(),
                    "the victim must never adopt the branch carrying the stale geometric commitment "
                        + "at the activation height");
            }
        }
    }

    /**
     * E2E-71 — Stop a real mining node (same RocksDB data directory, {@code TestNetwork.reopen})
     * exactly at its curve activation height and restart it, then let it resume mining across that
     * boundary. Verified: the restarted node reloads its exact pre-restart tip and committed
     * supply, and it stays in exact consensus — same committed supply at the settled height — with
     * a peer that was never restarted at all. Mirrors the difficulty-recomputation-on-restart bug
     * class {@code CLAUDE.md} calls out explicitly for Pandanite, applied to the emission table.
     */
    @Test
    void aRestartExactlyAtTheActivationHeightReloadsTheSameHistoricalRewardsAndContinuesMiningWithTheCurveActive()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters params = TestNetwork.CURVE_ACTIVE.toBuilder().emissionCurveHeight(3).build();
            PublicAddress miner = PublicAddress.random();

            // No live producer yet: E2EFixtures.mintEmpty mines single-threaded and deterministically,
            // so the "before" state below is read with nothing else able to race it -- a live
            // producer's background thread could otherwise land an extra block between the two
            // separate (non-atomic) tipHash()/height() calls, a torn read matching the exact shape
            // BlockAssembler's own TipView comment warns about.
            RhizomeNode a = network.node("a").params(params).start();
            E2EFixtures.mintEmpty(a, miner, 1); // height 2: still pre-activation (geometric)
            assertFalse(params.emissionCurveActiveAt(2));

            SHA256Hash tipBeforeRestart = a.engine().tipHash();
            long heightBeforeRestart = a.engine().height();
            long supplyBeforeRestart = a.engine().headerAt(heightBeforeRestart).supply();
            assertEquals(2, heightBeforeRestart);

            // Only NOW does the restarted incarnation become a live miner -- it resumes production
            // for the first time exactly at the activation-adjacent boundary this proof cares about.
            RhizomeNode restarted = network.reopen("a").params(params).mining(miner)
                .blockInterval(150).start();
            // Anchored at heightBeforeRestart, never at the live tip: the producer starts inside
            // start(), so by the time these read, the restarted node may legitimately have mined
            // the activation-height block already. The invariant is that a restart LOSES nothing
            // and reloads its history byte-identically -- not that it has not yet produced. An
            // equality against height()/tipHash() here asserts the latter and fails on a fast or
            // loaded box, which is a flaky gate, not a stricter proof.
            assertTrue(restarted.engine().height() >= heightBeforeRestart,
                "a restart must not lose any already-mined height");
            assertEquals(tipBeforeRestart, restarted.engine().blockAt(heightBeforeRestart).hash(),
                "a restart must reload the exact same block at its pre-restart tip height");
            assertEquals(supplyBeforeRestart, restarted.engine().headerAt(heightBeforeRestart).supply(),
                "a restart must recompute the exact same historical committed supply");
            assertTrue(params.emissionCurveActiveAt(3),
                "the restart must land at exactly the activation height for this proof to mean anything");

            // A peer that never restarted at all -- boots fresh, syncs the restarted node's chain,
            // and must end up agreeing on committed supply across the restart AND the activation
            // boundary, not just locally recomputing the same thing by coincidence.
            RhizomeNode neverRestarted = network.node("b").params(params)
                .peers(TestNetwork.urlOf(restarted)).start();
            TestNetwork.syncUntil(neverRestarted, () -> neverRestarted.engine().height() >= heightBeforeRestart
                && neverRestarted.engine().blockAt(2).hash().equals(restarted.engine().blockAt(2).hash()));

            TestNetwork.awaitHeight(restarted, heightBeforeRestart + 3);
            TestNetwork.syncUntil(neverRestarted,
                () -> neverRestarted.engine().tipHash().equals(restarted.engine().tipHash()));

            // Read the settled height from the PEER, not from the still-mining source: the source
            // can advance again between syncUntil returning and this line, and headerAt(settled)
            // would then be null on the peer. The peer only ever holds heights the source also
            // holds, so its own height is the highest both provably carry.
            long settled = neverRestarted.engine().height();
            assertEquals(restarted.engine().headerAt(settled).supply(),
                neverRestarted.engine().headerAt(settled).supply(),
                "the restarted node and its never-restarted peer must agree exactly on committed "
                    + "supply spanning both the restart and the activation height");
            assertFalse(restarted.engine().isDegraded());
            assertFalse(neverRestarted.engine().isDegraded());
        }
    }
}
