package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.node.RhizomeNode;

/**
 * End-to-end proof for an honest-but-misconfigured pair of real nodes disagreeing about whether
 * the emission curve is active (§ integer log curve). Same pattern as the GENESIS family's
 * honestly-misconfigured-peer scenario (E2E-45), applied to an axis GENESIS never had:
 * {@code emissionCurveHeight} is not part of the genesis-identity check, so two nodes sharing an
 * identical genesis and chainId can silently disagree about it forever — never flagged as
 * incompatible at admission, since nothing about the mismatch is visible at the transport layer.
 */
class E2ECurveMisconfigurationTest {

    @TempDir
    Path tempDir;

    /**
     * E2E-72 — Pair a curve-INACTIVE real node with a curve-ACTIVE real node sharing the exact
     * same genesis and chainId (both derive from {@code TestNetwork.FAST}, so only the emission
     * constants differ). The curve-active node mines and gossips to the curve-inactive one; since
     * each validates a gossiped block against its OWN {@code NetworkParameters}, they disagree
     * about the expected reward from the very first mined block and must fork cleanly rather than
     * either one silently adopting the other's chain.
     */
    @Test
    void aCurveActivePeerAndACurveInactivePeerWithIdenticalGenesisForkCleanlyAndNeitherAdoptsTheOther()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode inactive = network.node("inactive").params(TestNetwork.FAST).start();
            RhizomeNode active = network.node("active").params(TestNetwork.CURVE_ACTIVE)
                .mining().blockInterval(150).start();

            assertEquals(inactive.engine().blockAt(1).hash(), active.engine().blockAt(1).hash(),
                "both profiles must share an identical genesis for this to be an invisible misconfiguration");

            TestNetwork.awaitHeight(active, 4);
            long activeHeightBeforeMeeting = active.engine().height();
            long inactiveHeightBefore = inactive.engine().height();

            inactive.service().addPeer(TestNetwork.urlOf(active));
            TestNetwork.await(() -> inactive.knownPeers().contains(TestNetwork.urlOf(active)),
                () -> "the curve-inactive node never admitted its curve-active peer");
            for (int round = 0; round < 6; round++) {
                inactive.syncRound();
            }

            assertEquals(inactiveHeightBefore, inactive.engine().height(),
                "the curve-inactive node must never adopt a block whose reward it computes "
                    + "differently under its own network parameters");
            assertFalse(inactive.engine().isDegraded(),
                "an unresolvable, honest reward disagreement must not degrade the node");
            assertFalse(active.engine().isDegraded());

            // The curve-active node is entirely unaffected by its misconfigured peer and keeps
            // producing on its own chain.
            TestNetwork.awaitHeight(active, activeHeightBeforeMeeting + 2);
            assertFalse(active.engine().isDegraded());

            E2EFixtures.mintEmpty(inactive, PublicAddress.random(), 1);
            assertEquals(inactiveHeightBefore + 1, inactive.engine().height(),
                "the curve-inactive node must still be able to mine its own honest blocks");
        }
    }
}
