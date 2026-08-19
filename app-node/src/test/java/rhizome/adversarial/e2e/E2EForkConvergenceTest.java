package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.node.RhizomeNode;

/**
 * End-to-end fork behaviour: real nodes, real HTTP, real proof of work.
 *
 * <p>The component-level suites in {@code lib-core} prove that {@code ChainSynchronizer} makes the
 * right decision when handed a branch. They cannot prove that a node <em>assembled</em> — HTTP
 * transport, RocksDB stores, a producer thread racing the sync thread, undo journals on disk —
 * carries that decision through. Every historical consensus split has been an assembly failure of
 * that shape rather than a wrong comparison, so the network-level question is asked here
 * separately: does a real node, having genuinely mined a branch it believed in, end up on the same
 * history as its peers?
 *
 * <p>Assertions avoid the moving tip on purpose. A producer keeps mining while the scenario runs,
 * so "both nodes report the same tip hash" is a race the network wins only by accident. What
 * matters, and what is asserted, is agreement on <em>settled history</em>: at a height both nodes
 * have passed, they must hold the same block.
 */
class E2EForkConvergenceTest {

    @TempDir
    Path tempDir;

    /**
     * Block cadence for these scenarios. Proof of work is instant at 3 bits, so the producer's
     * pacing IS the block rate: at the 30 ms other node tests use, two miners emit ~30 blocks a
     * second each and diverge past the 120-block finality window before the first sync round can
     * fire — the scenario would then be measuring a permanent split of its own making rather than
     * the network's convergence. A quarter-second keeps propagation faster than production, which
     * is the regime a real chain is designed for.
     */
    private static final long BLOCK_MS = 250;

    /** The deepest height both nodes have, backed off two blocks so neither tip is in flight. */
    private static long settledHeight(RhizomeNode a, RhizomeNode b) {
        return Math.max(2, Math.min(a.engine().height(), b.engine().height()) - 2);
    }

    private static void addPeerAndAwaitAdmission(RhizomeNode node, String peerUrl)
            throws InterruptedException {
        node.service().addPeer(peerUrl); // the real /add_peer path: admission runs off the loop
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }

    /**
     * E2E-01 — a node that mined its own branch in isolation abandons it for the heavier one it
     * learns about over HTTP, and lands exactly on it rather than somewhere near it.
     */
    @Test
    void aNodeOnALighterBranchAdoptsTheHeavierOneItLearnsOverHttp() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode heavy = network.node("heavy").mining().blockInterval(BLOCK_MS).start();
            RhizomeNode light = network.node("light").start(); // no producer: its branch is minted

            // The light node builds a short branch of its own. Same genesis, different history.
            PublicAddress lightMiner = PublicAddress.random();
            E2EFixtures.mintEmpty(light, lightMiner, 4);
            assertEquals(5, light.engine().height());
            var lightOwnSecond = light.engine().blockAt(2).hash();

            TestNetwork.awaitHeight(heavy, 12);
            assertNotEquals(heavy.engine().blockAt(2).hash(), lightOwnSecond,
                "the two nodes must genuinely disagree, or this proves nothing");

            addPeerAndAwaitAdmission(light, TestNetwork.urlOf(heavy));
            TestNetwork.syncUntil(light, () -> light.engine().height() > 5);

            long settled = settledHeight(heavy, light);
            assertEquals(heavy.engine().blockAt(settled).hash(), light.engine().blockAt(settled).hash(),
                "the light node must land on the heavy node's history, block for block");
            assertEquals(heavy.engine().blockAt(2).hash(), light.engine().blockAt(2).hash(),
                "including at the fork point — its own branch is gone, not merged");
        }
    }

    /**
     * E2E-02 — two nodes that both mined in isolation and then meet converge on one history. This
     * is the shape a network partition actually takes: neither side is "the peer", both believe
     * their own branch, and both keep producing throughout the heal.
     */
    @Test
    void twoIsolatedMiningNodesThatMeetAgreeOnHistory() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode left = network.node("left").mining().blockInterval(BLOCK_MS).start();
            RhizomeNode right = network.node("right").mining().blockInterval(BLOCK_MS).start();

            TestNetwork.awaitHeight(left, 8);
            TestNetwork.awaitHeight(right, 8);
            assertNotEquals(left.engine().blockAt(2).hash(), right.engine().blockAt(2).hash(),
                "the partition must have produced two real histories");

            // The partition heals: each side learns of the other, as it would through discovery.
            addPeerAndAwaitAdmission(left, TestNetwork.urlOf(right));
            addPeerAndAwaitAdmission(right, TestNetwork.urlOf(left));

            TestNetwork.syncUntil(java.util.List.of(left, right), () -> {
                long settled = settledHeight(left, right);
                return left.engine().blockAt(settled).hash().equals(right.engine().blockAt(settled).hash());
            });

            long settled = settledHeight(left, right);
            assertEquals(left.engine().blockAt(settled).hash(), right.engine().blockAt(settled).hash());
            assertEquals(left.engine().blockAt(2).hash(), right.engine().blockAt(2).hash(),
                "convergence must reach the fork point, not just the recent tail");
            assertTrue(left.engine().height() >= 8 && right.engine().height() >= 8,
                "neither node lost its chain in the heal");
        }
    }
}
