package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.ledger.SnapshotLoader;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * A real hostile peer, over a real socket, targeting genesis identity specifically rather than
 * fork choice among already-agreeing chains.
 *
 * <p>{@code E2EHostilePeerTest} proves a victim's SETTLED history survives a lying peer. These two
 * scenarios ask the question one layer earlier: can a hostile peer make a victim adopt a
 * DIFFERENT chain identity altogether — either a real, equal-total-but-differently-distributed
 * genesis served by a second (otherwise honest) node, or a fabricated header stream not rooted in
 * anything real — by pairing the lie with an outsized claimed height/work? Both scenarios assert
 * the same "refusal is free" shape the rest of the {@code E2E} family uses: no exception escapes,
 * the victim's own state does not move, and the victim is not left degraded.
 */
class E2EGenesisEclipseTest {

    @TempDir
    Path tempDir;

    /** Adds one peer and waits for its admission to complete. */
    private static void admit(RhizomeNode node, String peerUrl) throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }

    /**
     * E2E-39 — serve a victim a hostile peer's REAL genesis block, but for a different, second
     * (otherwise honest) chain that happens to share the same total supply as the victim's own
     * under a different per-address distribution, while claiming a thousand-block chain and
     * astronomical cumulative work — hoping the sheer size of the claimed advantage gets the
     * work/height comparison evaluated before the cheap genesis-identity check, so the victim ends
     * up chasing a branch of a network it never agreed to join.
     */
    @Test
    void aHostileGenesisWithMatchingTotalButADifferentDistributionNeverDisplacesTheVictims() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            long total = 4_000_000L;
            E2EFixtures.Identity victimHolder = E2EFixtures.Identity.generate();
            E2EFixtures.Identity otherHolderA = E2EFixtures.Identity.generate();
            E2EFixtures.Identity otherHolderB = E2EFixtures.Identity.generate();

            Path victimSnapshot = E2EFixtures.premine(tempDir.resolve("victim.json"),
                TestNetwork.FAST, Map.of(victimHolder, total));
            // Same total S0 as the victim's distribution, but split across two different addresses
            // entirely -- a real, different genesis under the same network parameters.
            Path otherSnapshot = E2EFixtures.premine(tempDir.resolve("other.json"),
                TestNetwork.FAST, Map.of(otherHolderA, total / 2, otherHolderB, total - total / 2));

            RhizomeNode victim = network.node("victim")
                .snapshot(victimSnapshot).mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);
            // A second, entirely honest node -- never itself hostile -- whose only role is to hold
            // the real bytes of a DIFFERENT genesis the hostile peer can truthfully serve.
            RhizomeNode distinctHonestNode = network.node("distinct").snapshot(otherSnapshot).start();

            assertNotEquals(victim.engine().blockAt(1).hash(), distinctHonestNode.engine().blockAt(1).hash(),
                "the two distributions must actually produce different genesis blocks, or this "
                    + "scenario proves nothing");

            SHA256Hash genesisBefore = victim.engine().blockAt(1).hash();
            SHA256Hash secondBefore = victim.engine().blockAt(2).hash();
            long heightBefore = victim.engine().height();
            BigInteger workBefore = victim.engine().totalWork();

            try (HostilePeer liar = HostilePeer.builder()
                    .sharesGenesisWith(distinctHonestNode)
                    .claimsHeight(1_000)
                    .claimsWork(() -> BigInteger.TWO.pow(220).toString())
                    .start()) {
                admit(victim, liar.url());
                for (int round = 0; round < 4; round++) {
                    victim.syncRound();
                }

                assertEquals(genesisBefore, victim.engine().blockAt(1).hash(),
                    "the victim's genesis must never change no matter how much work a peer claims");
                assertEquals(secondBefore, victim.engine().blockAt(2).hash(),
                    "the victim's history was rewritten by a peer offering a foreign chain");
                assertTrue(victim.engine().height() >= heightBefore,
                    "the victim lost chain height to a peer from a different genesis");
                assertTrue(victim.engine().totalWork().compareTo(workBefore) >= 0,
                    "the victim lost accumulated work");
                assertFalse(victim.engine().isDegraded(),
                    "the encounter left the victim degraded");

                long resumed = victim.engine().height();
                TestNetwork.await(() -> victim.engine().height() > resumed,
                    () -> "the victim stopped producing blocks after the encounter");
            }
        }
    }

    /**
     * E2E-40 — eclipse a freshly-joining, snap-syncing node with a single hostile peer that is its
     * ONLY configured peer: the peer claims a million-block chain and, when the node's sync loop
     * asks for headers, answers with a fabricated stream not rooted in anything real (random
     * preimage fields, not even a plausible parent of the node's true local genesis), hoping the
     * node either crashes outright or ends up borrowing the attacker's chain identity because it
     * had nothing else to compare against.
     */
    @Test
    void aFreshlyJoiningSnapSyncingNodeEclipsedByOneHostilePeerNeverCrashesAndKeepsItsOwnGenesis()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            // A "height 1" header rooted in nothing: every preimage field is random, so its hash
            // cannot coincide with any network's real genesis.
            BlockHeader fabricated = new BlockHeader(
                GenesisBlock.GENESIS_ID, 0L, 0, 0,
                SHA256Hash.random(), SHA256Hash.random(), SHA256Hash.random(),
                SHA256Hash.empty(), 0, -1, List.of());
            byte[] fabricatedHeaders = HeaderCodec.encode(fabricated);

            try (HostilePeer eclipse = HostilePeer.builder()
                    .claimsHeight(1_000_000)
                    .servesHeaders(() -> fabricatedHeaders)
                    .start()) {

                RhizomeNode joiner = assertDoesNotThrow(
                    () -> network.node("joiner").snapSync().peers(eclipse.url()).start(),
                    "an eclipse attempt during snap-sync must never crash node startup");

                // The only peer it has is this hostile one -- give the sync loop several real
                // chances to (fail to) act on the fabricated headers.
                for (int round = 0; round < 4; round++) {
                    joiner.syncRound();
                }

                assertEquals(1, joiner.engine().height(),
                    "the eclipsed node must not have borrowed a height from its attacker");
                SHA256Hash expectedGenesis = GenesisBlock.build(TestNetwork.FAST,
                    SnapshotLoader.empty(TestNetwork.FAST.chainId())).hash();
                assertEquals(expectedGenesis, joiner.engine().blockAt(1).hash(),
                    "the eclipsed node's genesis must be its own, locally-derived block -- never "
                        + "anything borrowed from the peer it happened to be pointed at");
                assertFalse(joiner.engine().isDegraded(),
                    "an eclipse attempt left the node degraded");
            }
        }
    }

    /**
     * E2E-46 — a hostile peer serves a near-perfect forgery of the victim's own real genesis:
     * byte-for-byte identical except one field, tampered by exactly one unit, hoping a forgery
     * this close is special-cased as "near enough" rather than refused by the same flat
     * hash-divergence rule that refuses a wildly different genesis. Tried against two independent
     * fields to show the property is general, not specific to one: the committed {@code supply}
     * (the field § supply header commitment, feature 002, folds into the header hash) and the
     * {@code timestamp}. Either single-bit-of-difference forgery must fail the fork probe exactly
     * as completely as {@link #aFreshlyJoiningSnapSyncingNodeEclipsedByOneHostilePeerNeverCrashesAndKeepsItsOwnGenesis}'s
     * wholly-random header does -- there is no partial credit for a near match.
     */
    @Test
    void aNearPerfectGenesisForgeryWithExactlyOneFieldAlteredIsRejectedLikeAnyOtherMismatch()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode victim = network.node("victim").mining().blockInterval(150).start();
            TestNetwork.awaitHeight(victim, 5);

            SHA256Hash genesisBefore = victim.engine().blockAt(1).hash();
            SHA256Hash secondBefore = victim.engine().blockAt(2).hash();
            long heightBefore = victim.engine().height();
            BigInteger workBefore = victim.engine().totalWork();

            BlockImpl supplyTampered = (BlockImpl) Block.of(victim.engine().blockAt(1));
            supplyTampered.supply(supplyTampered.supply() + 1);
            assertNotEquals(genesisBefore, supplyTampered.hash(),
                "incrementing the committed supply by one must actually change the header hash, "
                    + "or this scenario proves nothing about supply commitment");

            BlockImpl timestampTampered = (BlockImpl) Block.of(victim.engine().blockAt(1));
            timestampTampered.timestamp(timestampTampered.timestamp() + 1);
            assertNotEquals(genesisBefore, timestampTampered.hash(),
                "incrementing the timestamp by one must actually change the header hash, or this "
                    + "second case proves nothing either");

            AtomicReference<Block> forged = new AtomicReference<>();
            try (HostilePeer forger = HostilePeer.builder()
                    .claimsGenesis(forged::get)
                    .claimsHeight(1_000)
                    .claimsWork(() -> BigInteger.TWO.pow(220).toString())
                    .start()) {
                admit(victim, forger.url());

                forged.set(supplyTampered);
                for (int round = 0; round < 4; round++) {
                    victim.syncRound();
                }
                assertEquals(genesisBefore, victim.engine().blockAt(1).hash(),
                    "a one-field genesis forgery (supply off by one) must never displace the "
                        + "victim's real genesis");
                assertEquals(secondBefore, victim.engine().blockAt(2).hash(),
                    "the victim's history was rewritten by a near-perfect genesis forgery");
                assertTrue(victim.engine().height() >= heightBefore,
                    "the victim lost chain height to a near-perfect genesis forgery");
                assertTrue(victim.engine().totalWork().compareTo(workBefore) >= 0,
                    "the victim lost accumulated work to a near-perfect genesis forgery");
                assertFalse(victim.engine().isDegraded(),
                    "a one-field genesis forgery left the victim degraded");

                forged.set(timestampTampered);
                for (int round = 0; round < 4; round++) {
                    victim.syncRound();
                }
                assertEquals(genesisBefore, victim.engine().blockAt(1).hash(),
                    "a one-field genesis forgery (timestamp off by one) must never displace the "
                        + "victim's real genesis -- the refusal must be general, not supply-specific");
                assertEquals(secondBefore, victim.engine().blockAt(2).hash());
                assertFalse(victim.engine().isDegraded(),
                    "the second one-field forgery left the victim degraded");
            }
        }
    }
}
