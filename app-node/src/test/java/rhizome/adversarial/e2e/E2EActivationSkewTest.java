package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.node.RhizomeNode;

/**
 * End-to-end proof that two real, honestly-configured nodes whose {@code consensusV2Height} (the
 * transaction fee floor's own activation height) is SKEWED rather than identical do not silently
 * converge on a chain either side considers invalid, and do not corrupt each other's independent,
 * honest progress in the process.
 *
 * <p>{@code E2ECurveMisconfigurationTest} (E2E-72) proves the permanent-disagreement axis: one
 * node with the curve forever off against one with it forever on. This is the orthogonal, and
 * arguably more realistic, axis -- two nodes running the identical rule, scheduled to activate at
 * different heights, which is the ordinary state of a live network mid-rollout, before every
 * operator has restarted onto the same configuration. Below both heights the two configurations
 * are simply the same rule, so an honest block still commits identically on both. At the earlier
 * of the two heights the configurations first disagree about the exact same bytes: the node that
 * has reached its own activation height must refuse a zero-fee block for the fee floor, while its
 * still-lagging peer -- which has not reached its own -- accepts the identical block as honest.
 * That is a genuine, height-pinned consensus fork between two honest configurations, not a bug;
 * what this suite locks in is that the fork stays clean: the stricter node never silently adopts
 * the block it refused, whether offered directly (the same entry point {@code /submit} uses) or
 * pulled through a real peer sync round, and neither node ever ends up {@code isDegraded()}.
 */
class E2EActivationSkewTest {

    @TempDir
    Path tempDir;

    private static final long PREMINE = 100_000L;
    private static final long MIN_FEE = 10L;

    /**
     * E2E-91 -- Two real nodes ({@code TestNetwork.PUFFERFISH}, so the boundary is crossed under
     * genuine Pufferfish2 proof of work rather than a SHA-256 stand-in) share an identical genesis
     * and chainId but are configured with different {@code consensusV2Height} values -- an honest
     * activation-height SKEW, not a permanent on/off split. Below both heights an honest,
     * genuinely zero-fee block commits identically on both nodes. At the earlier node's own
     * activation height, an under-floor-fee block -- built through the still-lagging node, since
     * it is the one honest configuration that can validly stamp it -- is accepted by that
     * still-lagging node (honest under its own, not-yet-active rules) and refused by the node
     * whose own height has been reached -- with the precise {@code TRANSACTION_FEE_TOO_LOW}
     * status, not merely "not success" -- on both the direct submission route and a real peer
     * sync round. The stricter node's own honest, at-floor progress from the still-shared tip is
     * unaffected by the refusal, and neither node ever reports {@code isDegraded()}.
     */
    @Test
    void nodesWithSkewedConsensusV2HeightsDivergeExactlyAtTheEarlierBoundaryAndTheStricterNodeNeverAdoptsTheRefusedBlock()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            long earlyHeight = 3;
            long lateHeight = 6;
            NetworkParameters early = TestNetwork.PUFFERFISH.toBuilder()
                .consensusV2Height(earlyHeight)
                .minFee(MIN_FEE)
                .build();
            NetworkParameters late = TestNetwork.PUFFERFISH.toBuilder()
                .consensusV2Height(lateHeight)
                .minFee(MIN_FEE)
                .build();

            E2EFixtures.Identity spender = E2EFixtures.Identity.generate();
            Path premine = E2EFixtures.premine(tempDir.resolve("premine.json"), early, Map.of(spender, PREMINE));
            RhizomeNode nodeEarly = network.node("early").params(early).snapshot(premine).start();
            RhizomeNode nodeLate = network.node("late").params(late).snapshot(premine).start();

            assertEquals(nodeEarly.engine().blockAt(1).hash(), nodeLate.engine().blockAt(1).hash(),
                "consensusV2Height must not be part of genesis identity, or a skewed activation "
                    + "height would already be a fork from birth rather than the live-rollout "
                    + "scenario this suite targets");
            PublicAddress recipient = PublicAddress.random();
            long nonce = 0;

            // Height 2: below BOTH activation heights -- the two configurations agree, so a
            // genuinely zero-fee transfer commits identically on both.
            assertFalse(early.consensusV2(2));
            assertFalse(late.consensusV2(2));
            Transaction baseline = spender.send(recipient, 0L, 0L, nonce, early);
            Block honest2 = E2EFixtures.mint(nodeEarly, PublicAddress.random(), baseline);
            assertEquals(2, nodeEarly.engine().height());
            nonce++;

            // The identical block, delivered to nodeLate exactly as a real peer relay would --
            // submitBlock is the same entry point the /submit route calls on the receiving node --
            // is accepted too: below the skew there is no disagreement to find.
            assertEquals(ExecutionStatus.SUCCESS, nodeLate.service().submitBlock(honest2),
                "below both activation heights the skewed configurations must still agree");
            assertEquals(2, nodeLate.engine().height());
            assertEquals(nodeEarly.engine().tipHash(), nodeLate.engine().tipHash(),
                "both nodes must be on the identical tip below the skewed boundary");

            // Height 3: nodeEarly's own activation height is reached; nodeLate's (6) is not.
            assertTrue(early.consensusV2(earlyHeight));
            assertFalse(late.consensusV2(earlyHeight));

            // A block that is honest under the LAGGING configuration -- an under-floor-fee
            // transfer, still legal because nodeLate has not reached its own activation height --
            // built THROUGH nodeLate: the block-building fixture itself dry-runs the block against
            // the building node's own rules to stamp the state root (see E2EFixtures.build), so
            // building it through nodeEarly -- which already refuses this content -- would leave
            // the state root uncommitted rather than produce a genuinely honest block. The amount
            // is deliberately NON-zero: consensusV2 also changes whether a zero-AMOUNT deposit
            // creates the recipient wallet (NetworkParameters.consensusV2Height's second bullet),
            // which would make the two nodes' independently re-executed state roots diverge for a
            // reason unrelated to the fee floor this scenario isolates. A non-zero amount keeps
            // that other V2 gate a no-op on both sides, so the ONLY thing in play is the fee floor.
            // NOT incrementing `nonce` here: this transaction is only ever accepted on nodeLate's
            // branch. nodeEarly refuses it below, so spender's next-expected nonce on nodeEarly's
            // OWN chain (the one the rest of this scenario continues building on) is still 1, the
            // same value this transaction used.
            Transaction underFloor3 = spender.send(recipient, 1L, 0L, nonce, early);
            Block lenientBlock3 = E2EFixtures.build(nodeLate, PublicAddress.random(), underFloor3);

            assertEquals(ExecutionStatus.SUCCESS, nodeLate.service().submitBlock(lenientBlock3),
                "the still-lagging node must accept its own honestly-built block, under its own, "
                    + "not-yet-activated rules");
            assertEquals(3, nodeLate.engine().height());

            assertEquals(ExecutionStatus.TRANSACTION_FEE_TOO_LOW, nodeEarly.service().submitBlock(lenientBlock3),
                "the node whose own consensusV2Height has been reached must refuse the exact same "
                    + "block for the fee floor -- vice versa from nodeLate's verdict on the "
                    + "identical bytes");
            assertEquals(2, nodeEarly.engine().height(),
                "nodeEarly must not silently move onto a block it refuses");
            assertFalse(nodeEarly.engine().isDegraded());
            assertFalse(nodeLate.engine().isDegraded());

            // A real peer sync round must reach the identical verdict as the direct submission --
            // the lagging peer's now-longer, honest-to-it chain must not be silently adopted by
            // the stricter node either.
            nodeEarly.service().addPeer(TestNetwork.urlOf(nodeLate));
            TestNetwork.await(() -> nodeEarly.knownPeers().contains(TestNetwork.urlOf(nodeLate)),
                () -> "nodeEarly never admitted its skewed peer");
            for (int round = 0; round < 6; round++) {
                nodeEarly.syncRound();
            }
            assertEquals(2, nodeEarly.engine().height(),
                "syncing from a peer still on the old activation height must not let the stricter "
                    + "node silently adopt a block it refuses");
            assertFalse(nodeEarly.engine().isDegraded());
            assertFalse(nodeLate.engine().isDegraded());

            // The disagreement does not brick nodeEarly: it keeps making its own honest, at-floor
            // progress from the shared tip both nodes still agree on.
            Transaction atFloor3 = spender.send(recipient, 0L, MIN_FEE, nonce, early);
            Block honestEarly3 = E2EFixtures.mint(nodeEarly, PublicAddress.random(), atFloor3);
            assertEquals(3, nodeEarly.engine().height());
            assertEquals(MIN_FEE, honestEarly3.transactions().get(1).fee().amount());
            assertFalse(nodeEarly.engine().isDegraded());
            assertFalse(nodeLate.engine().isDegraded());
        }
    }
}
