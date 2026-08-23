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

import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * A third node whose genesis genuinely differs — not a {@link HostilePeer}, not malicious, just an
 * honestly misconfigured operator who premined a different distribution of the same total supply
 * — sitting in an otherwise healthy, fully-meshed network for real, repeated sync rounds.
 *
 * <p>{@link E2EGenesisBanScoreTest} proves the ban arithmetic against a lying peer in isolation.
 * This is the composition question a single-victim scenario cannot show: does the steady drumbeat
 * of {@code INCOMPATIBLE} verdicts a divergent-but-honest peer produces every round ever destabilize
 * the mesh it sits in — corrupt the two genuinely-agreeing nodes' convergence, cost either side a
 * crash, a hang, or state that keeps growing round over round? A defence that holds against one
 * hostile peer but degrades under an honest peer's mere presence would pass every scenario in that
 * file.
 */
class E2EGenesisDivergenceTest {

    @TempDir
    Path tempDir;

    /** Adds one peer and waits for its admission to complete before the caller adds another. */
    private static void admit(RhizomeNode node, String peerUrl) throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }

    /**
     * E2E-45 — two honest nodes sharing one genesis, meshed with a third honest node whose genesis
     * differs only in per-address distribution (same total supply), all three peered with each
     * other, driven through a bounded, repeated series of real sync rounds: hoping the divergent
     * peer's constant stream of {@code INCOMPATIBLE} verdicts either poisons the honest pair's
     * convergence, gets adopted by (or adopts) either honest node's chain, or accumulates some
     * unbounded per-round cost that a longer-running mesh would eventually pay for.
     */
    @Test
    void anHonestlyMisconfiguredPeerInAFullMeshNeverDestabilizesTheHonestPairOrAdoptsTheirChain()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            long total = 5_000_000L;
            E2EFixtures.Identity sharedHolder = E2EFixtures.Identity.generate();
            E2EFixtures.Identity divergentHolderA = E2EFixtures.Identity.generate();
            E2EFixtures.Identity divergentHolderB = E2EFixtures.Identity.generate();

            Path sharedSnapshot = E2EFixtures.premine(tempDir.resolve("shared.json"),
                TestNetwork.FAST, Map.of(sharedHolder, total));
            // The SAME total supply, split across two different addresses entirely -- a real,
            // honestly-different genesis under the same network parameters, not an attack.
            Path divergentSnapshot = E2EFixtures.premine(tempDir.resolve("divergent.json"),
                TestNetwork.FAST, Map.of(divergentHolderA, total / 2, divergentHolderB, total - total / 2));

            RhizomeNode alice = network.node("alice")
                .snapshot(sharedSnapshot).mining().blockInterval(150).start();
            RhizomeNode bob = network.node("bob")
                .snapshot(sharedSnapshot).mining().blockInterval(150).start();
            // Never mines: its only role is to sit in the mesh and genuinely diverge every round.
            RhizomeNode carol = network.node("carol").snapshot(divergentSnapshot).start();

            SHA256Hash sharedGenesis = alice.engine().blockAt(1).hash();
            assertEquals(sharedGenesis, bob.engine().blockAt(1).hash(),
                "alice and bob must actually share one genesis, or this scenario proves nothing");
            SHA256Hash divergentGenesis = carol.engine().blockAt(1).hash();
            assertNotEquals(sharedGenesis, divergentGenesis,
                "the same total under a different distribution must actually yield a different "
                    + "genesis, or carol is not honestly divergent at all");

            TestNetwork.awaitHeight(alice, 4);
            TestNetwork.awaitHeight(bob, 4);

            // Full mesh: every node peers with every other node, one admission at a time.
            admit(alice, TestNetwork.urlOf(bob));
            admit(alice, TestNetwork.urlOf(carol));
            admit(bob, TestNetwork.urlOf(alice));
            admit(bob, TestNetwork.urlOf(carol));
            admit(carol, TestNetwork.urlOf(alice));
            admit(carol, TestNetwork.urlOf(bob));

            List<RhizomeNode> mesh = List.of(alice, bob, carol);
            long started = System.currentTimeMillis();
            // Bounded and explicit, not a syncUntil loop: the property under test is that a fixed,
            // small amount of work stays fixed and small as the divergent peer keeps reappearing in
            // the mesh, not that convergence eventually happens.
            for (int round = 0; round < 15; round++) {
                for (RhizomeNode node : mesh) {
                    node.syncRound();
                }
            }
            long elapsed = System.currentTimeMillis() - started;
            assertTrue(elapsed < 20_000,
                "15 rounds across a 3-node mesh must stay fast (" + elapsed + " ms) -- a growing "
                    + "per-round cost would mean unbounded retry state from the repeated "
                    + "INCOMPATIBLE verdicts against the divergent peer");

            long settled = Math.max(2, Math.min(alice.engine().height(), bob.engine().height()) - 2);
            assertEquals(alice.engine().blockAt(settled).hash(), bob.engine().blockAt(settled).hash(),
                "the two genuinely-agreeing nodes failed to converge with a divergently-configured "
                    + "honest peer in their mesh");
            assertEquals(alice.engine().blockAt(2).hash(), bob.engine().blockAt(2).hash(),
                "convergence must reach the fork point");

            assertEquals(sharedGenesis, alice.engine().blockAt(1).hash(),
                "alice's genesis moved despite a divergent peer in her mesh");
            assertEquals(sharedGenesis, bob.engine().blockAt(1).hash(),
                "bob's genesis moved despite a divergent peer in his mesh");
            assertEquals(divergentGenesis, carol.engine().blockAt(1).hash(),
                "carol adopted a foreign genesis from the honest pair -- a real, non-hostile peer "
                    + "must never overwrite chain identity either");
            assertEquals(1, carol.engine().height(),
                "carol must never have adopted so much as a single block from a foreign genesis");

            assertFalse(alice.engine().isDegraded() || bob.engine().isDegraded()
                    || carol.engine().isDegraded(),
                "an honestly misconfigured peer in the mesh left some node degraded");
        }
    }
}
