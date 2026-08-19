package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.crypto.SHA256Hash;
import rhizome.net.PeerId;
import rhizome.node.RhizomeNode;

/**
 * A real node meeting a real hostile peer over a real socket.
 *
 * <p>The lie is free and the proof is not — that asymmetry is the whole design of the sync path,
 * and the property under test is that a peer's <em>claims</em> can never cost the victim local
 * state. What the network level adds over the component suites is everything between the claim and
 * the judgement: the response is parsed by the real client, bounded by the real caps, timed by the
 * real deadlines, and the victim is meanwhile mining on its own thread. A defence that holds in
 * isolation and drops the chain under that concurrency is the failure this layer exists to catch.
 *
 * <p>Every scenario asserts the same three things after the encounter: the victim's history is
 * untouched at a settled height, the victim is still producing blocks, and the victim is not
 * degraded. A refusal that costs the node its chain, its liveness or its integrity is not a
 * successful defence.
 */
class E2EHostilePeerTest {

    @TempDir
    Path tempDir;

    private record Untouched(SHA256Hash secondBlock, long height, BigInteger work) {
        static Untouched of(RhizomeNode node) {
            return new Untouched(node.engine().blockAt(2).hash(), node.engine().height(),
                node.engine().totalWork());
        }
    }

    private static void assertSurvivedIntact(RhizomeNode victim, Untouched before)
            throws InterruptedException {
        assertEquals(before.secondBlock(), victim.engine().blockAt(2).hash(),
            "the victim's history was rewritten by a peer that proved nothing");
        assertTrue(victim.engine().height() >= before.height(),
            "the victim lost chain height to a hostile peer");
        assertTrue(victim.engine().totalWork().compareTo(before.work()) >= 0,
            "the victim lost accumulated work");
        assertFalse(victim.engine().isDegraded(),
            "the encounter left the victim degraded, which halts every new-tip write");

        long resumed = victim.engine().height();
        TestNetwork.await(() -> victim.engine().height() > resumed,
            () -> "the victim stopped producing blocks after the encounter");
    }

    /** Adds one peer and waits for its admission to complete before the caller adds another. */
    private static void admit(RhizomeNode node, String peerUrl) throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }

    private static void meet(RhizomeNode victim, String peerUrl) throws InterruptedException {
        admit(victim, peerUrl);
        for (int round = 0; round < 4; round++) {
            victim.syncRound();
        }
    }

    /**
     * E2E-11 — the canonical lying peer: it claims a chain thousands of blocks long carrying
     * astronomical work, shares the victim's genesis so the fork probe engages, and then serves a
     * branch that has paid for nothing. The victim must not move a single block.
     */
    @Test
    void aPeerClaimingHugeWorkAndProvingNoneChangesNothing() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode victim = network.node("victim").mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(victim)
                    .claimsHeight(5_000)
                    .claimsWork(() -> BigInteger.TWO.pow(220).toString())
                    .serves(() -> HostilePeer.unprovenWindow(2, 33, 30))
                    .start()) {
                meet(victim, liar.url());
                assertSurvivedIntact(victim, before);
            }
        }
    }

    /**
     * E2E-12 — the same lie with a malformed branch instead of an unproven one. A window the
     * decoder cannot parse must be contained inside the sync pass rather than escaping as a
     * failure that takes the node with it.
     */
    @Test
    void aPeerServingAnUndecodableBranchIsContainedInsideTheSyncPass() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode victim = network.node("victim").mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            byte[] garbage = new byte[4096];
            new java.util.Random(42).nextBytes(garbage);
            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(victim)
                    .claimsHeight(900)
                    .serves(() -> garbage)
                    .start()) {
                meet(victim, liar.url());
                assertSurvivedIntact(victim, before);
            }
        }
    }

    /**
     * E2E-13 — an oversized scalar. {@code /total_work} is a number the victim must parse before it
     * can rank the peer, so an unbounded body there is a parse-then-OOM primitive. It has to be
     * refused on size, before the parse.
     */
    @Test
    void aPeerAnsweringWithAnEnormousScalarIsRejectedNotParsed() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode victim = network.node("victim").mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            String enormous = "9".repeat(4 * 1024 * 1024);
            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(victim)
                    .claimsWork(() -> enormous)
                    .start()) {
                meet(victim, liar.url());
                assertSurvivedIntact(victim, before);
            }
        }
    }

    /**
     * E2E-14 — a peer that is merely broken, answering 500 to everything. This must read as a
     * transport failure: the victim keeps working and, crucially, does <em>not</em> ban it. Banning
     * on transport errors is how two honest nodes lock each other out of a network.
     */
    @Test
    void aPeerFailingEveryRequestIsNotTreatedAsMisbehaviour() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode victim = network.node("victim").mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            try (HostilePeer broken = HostilePeer.builder().failsEverythingWith(500).start()) {
                meet(victim, broken.url());
                assertSurvivedIntact(victim, before);
                assertFalse(victim.banList().isBanned(PeerId.of(broken.url())),
                    "a peer that only fails to answer has not misbehaved and must not be banned");
            }
        }
    }

    /**
     * E2E-15 — a peer that trickles its response forever, one byte every half second. Each byte is
     * progress, so an idle-only timeout would never fire; the whole-exchange deadline
     * ({@code HttpPeerSource.REQUEST_DEADLINE}, 30 s) is what reclaims the round.
     *
     * <p>The bound alone is not the interesting property, though — a node that merely survives by
     * stopping is no better off. What matters is that the parked round is confined to the sync
     * thread: block production runs on its own and must be entirely unaffected while the exchange
     * hangs. That is asserted directly, by counting the blocks mined during the park.
     */
    @Test
    void aPeerThatDripsForeverParksOnlyItsOwnSyncRoundAndNeverProduction() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode victim = network.node("victim").mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            Untouched before = Untouched.of(victim);

            try (HostilePeer slow = HostilePeer.builder().dripsForever().start()) {
                victim.service().addPeer(slow.url());
                TestNetwork.await(() -> victim.knownPeers().contains(slow.url()),
                    () -> "the dripping peer was never admitted");

                long heightAtStart = victim.engine().height();
                long started = System.currentTimeMillis();
                victim.syncRound();
                long elapsed = System.currentTimeMillis() - started;

                assertTrue(elapsed < 2 * PEER_REQUEST_DEADLINE_MS,
                    "the exchange was never reclaimed: the round ran for " + elapsed + " ms");
                assertTrue(victim.engine().height() - heightAtStart >= 10,
                    "block production stalled while a peer exchange hung — the drip reached past "
                        + "the sync thread (mined only "
                        + (victim.engine().height() - heightAtStart) + " blocks in " + elapsed + " ms)");
                assertSurvivedIntact(victim, before);
            }
        }
    }

    /** {@code HttpPeerSource.REQUEST_DEADLINE}: the whole-exchange bound on one peer request. */
    private static final long PEER_REQUEST_DEADLINE_MS = 30_000;

    /**
     * E2E-28 — the realistic topology: honest nodes do not meet a liar alone, they meet one while
     * also talking to each other. The liar sits in both their peer sets, claiming a chain that
     * dwarfs theirs, and the property is that it changes nothing — the honest pair still converges
     * on the history they mined between them.
     *
     * <p>This is the composition the single-victim scenarios cannot show. A defence that holds
     * against one hostile peer but lets it poison the fork choice between two honest ones would
     * pass every test above.
     */
    @Test
    void twoHonestNodesConvergeWithEachOtherDespiteALiarInBothPeerSets() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode alice = network.node("alice").mining().blockInterval(250).start();
            RhizomeNode bob = network.node("bob").mining().blockInterval(250).start();

            TestNetwork.awaitHeight(alice, 6);
            TestNetwork.awaitHeight(bob, 6);

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(alice)
                    .claimsHeight(10_000)
                    .claimsWork(() -> BigInteger.TWO.pow(230).toString())
                    .serves(() -> HostilePeer.unprovenWindow(2, 33, 30))
                    .start()) {

                // One admission at a time, each awaited. PeerAdmissionQueue coalesces by HOSTNAME
                // (not by URL) so that an attacker cannot queue unbounded DNS resolutions for one
                // slow host by varying the port — which means that on a single machine, where
                // every node is a port on 127.0.0.1, a second concurrent /add_peer is dropped
                // outright rather than queued. Firing them together silently admits one peer and
                // leaves the scenario testing a topology it never built.
                admit(alice, TestNetwork.urlOf(bob));
                admit(bob, TestNetwork.urlOf(alice));
                admit(alice, liar.url());
                admit(bob, liar.url());

                TestNetwork.syncUntil(java.util.List.of(alice, bob), () -> {
                    long settled = Math.max(2,
                        Math.min(alice.engine().height(), bob.engine().height()) - 2);
                    return alice.engine().blockAt(settled).hash()
                        .equals(bob.engine().blockAt(settled).hash());
                });

                long settled = Math.max(2,
                    Math.min(alice.engine().height(), bob.engine().height()) - 2);
                assertEquals(alice.engine().blockAt(settled).hash(),
                    bob.engine().blockAt(settled).hash(),
                    "the honest pair failed to agree while a liar was in the peer set");
                assertEquals(alice.engine().blockAt(2).hash(), bob.engine().blockAt(2).hash(),
                    "convergence must reach the fork point");
                assertFalse(alice.engine().isDegraded() || bob.engine().isDegraded(),
                    "the liar left an honest node degraded");
            }
        }
    }
}
