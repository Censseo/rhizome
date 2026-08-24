package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.BlockHeader;
import rhizome.core.blockchain.Issuance;
import rhizome.core.ledger.PublicAddress;
import rhizome.node.RhizomeNode;

/**
 * End-to-end fork/convergence proof for the supply-driven logarithmic emission curve (§ integer
 * log curve). Direct twin of the pre-existing E2E-36 ({@code E2ESupplyCommitmentTest}), which
 * never exercises the curve dispatch inside {@code Issuance.minted} — this is the most
 * fundamental multi-node proof this feature was still missing: do two independently-mining real
 * nodes, forked and reorged over real HTTP sync, actually agree on committed supply once
 * converged?
 */
class E2ECurveEmissionTest {

    @TempDir
    Path tempDir;

    /** Block cadence fast enough that propagation beats production, so the fork heals before finality. */
    private static final long BLOCK_MS = 250;

    private static void admit(RhizomeNode node, String peerUrl) throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }

    private static long settledHeight(RhizomeNode a, RhizomeNode b) {
        return Math.max(2, Math.min(a.engine().height(), b.engine().height()) - 2);
    }

    /**
     * E2E-73 — Fork two real, independently-mining nodes under a curve-active profile (no lies: a
     * genuine partition, each mining its own branch from divergent real parent supplies), let them
     * reorg to the heavier branch over real HTTP sync, and verify the two nodes' independently-read
     * committed supplies agree exactly at the settled height, recomputed from nothing but the
     * converged chain's own headers.
     */
    @Test
    void twoForkedMiningNodesUnderACurveActiveProfileThatConvergeAgreeOnSupplyAndRewardAtTheSettledHeight()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode left = network.node("left").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(BLOCK_MS).start();
            RhizomeNode right = network.node("right").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(BLOCK_MS).start();

            TestNetwork.awaitHeight(left, 8);
            TestNetwork.awaitHeight(right, 8);
            assertNotEquals(left.engine().blockAt(2).hash(), right.engine().blockAt(2).hash(),
                "the partition must have produced two real, divergent histories");

            admit(left, TestNetwork.urlOf(right));
            admit(right, TestNetwork.urlOf(left));

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
            assertTrue(leftSupply >= 0, "the left node's committed supply must be genuinely committed");
            assertTrue(rightSupply >= 0, "the right node's committed supply must be genuinely committed");
            assertEquals(leftSupply, rightSupply,
                "two nodes that converged on the same history must read back the same supply");

            // Recomputed independently from the converged chain's own headers -- every block's
            // ACTUAL difficulty and ACTUAL (curve-scheduled) issuance, never a hardcoded number.
            long recomputed = left.engine().headerAt(1).supply();
            for (long h = 2; h <= settled; h++) {
                BlockHeader header = left.engine().headerAt(h);
                recomputed = Math.addExact(recomputed, Issuance.minted(
                    left.engine().params(), header.id(), recomputed, header.difficulty(), header.uncles()));
            }
            assertEquals(recomputed, leftSupply,
                "the committed supply must equal the sum of every block's own curve-scheduled issuance since genesis");

            E2EFixtures.mintEmpty(left, PublicAddress.random(), 1);
            assertTrue(left.engine().height() > settled, "the converged node must still be able to mine");
        }
    }
}
