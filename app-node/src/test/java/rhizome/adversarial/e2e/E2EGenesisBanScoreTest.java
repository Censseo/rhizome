package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.net.PeerId;
import rhizome.node.RhizomeNode;

/**
 * {@code SyncDriver}'s ban score, over a real socket, for the two failure modes that must never
 * be confused with each other: a peer on a different network ({@code INCOMPATIBLE}, 10 points)
 * and a peer that speaks the protocol but lies ({@code PEER_INVALID}, 34 points). Both suites in
 * this file drive the exact arithmetic — never a hand-built {@code ChainSynchronizer.Result} —
 * through real JSON parsing, real HTTP, and the real {@code PeerRegistry}/{@code PeerBanList}
 * eviction path, so a refactor that quietly changed either penalty or the ban threshold would fail
 * one of these at the exact strike it moved.
 */
class E2EGenesisBanScoreTest {

    @TempDir
    Path tempDir;

    /** {@code SyncDriver.BAN_THRESHOLD}: mirrored here (package-private, not visible across modules'
     *  test sources) so the arithmetic in the javadocs below is checked, not merely asserted. */
    private static final int BAN_THRESHOLD = 100;
    /** {@code SyncDriver}'s per-strike cost for a genesis/network mismatch. */
    private static final int PENALTY_INCOMPATIBLE = 10;
    /** {@code SyncDriver}'s per-strike cost for a structurally invalid/malformed response. */
    private static final int PENALTY_INVALID = 34;

    /**
     * A well-formed {@code /total_work} body: {@code HostilePeer}'s own default (and any bare
     * numeric string passed to {@code claimsWork}) is NOT what {@code HttpPeerSource.totalWork()}
     * expects on the wire ({@code {"totalWork": "..."}}, mirroring the real {@code NodeApi} route)
     * -- passing a bare string there makes the very first probe of every round fail as malformed
     * before the sync pass ever reaches the fork check this file's scenarios are about (see
     * {@code E2ESupplyCommitmentTest}'s identical workaround). Both scenarios below need the real
     * classification under test, not an accidental one from an earlier, unrelated parse failure.
     */
    private static Supplier<String> wellFormedTotalWork(String work) {
        return () -> new JSONObject().put("totalWork", work).toString();
    }

    /** Adds one peer and waits for its admission to complete. */
    private static void admit(RhizomeNode node, String peerUrl) throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }

    /**
     * E2E-43 — a peer with an incompatible genesis is banned at exactly the strike the arithmetic
     * predicts, and stops being contacted at all the instant it is.
     *
     * <p>A default {@code HostilePeer} carries no {@code .sharesGenesisWith}/{@code .claimsGenesis}
     * configuration, so its stock {@code /block?blockId=1} answer (a fresh random block every
     * request) can never match the victim's real genesis: every sync round classifies it
     * {@code INCOMPATIBLE} and costs it {@link #PENALTY_INCOMPATIBLE} points. {@code 10 x 10 =
     * BAN_THRESHOLD} exactly, so the peer must survive nine rounds and be evicted on the tenth —
     * not the ninth, not the eleventh. The eleventh round then proves the other half of the
     * contract: {@code SyncDriver} must not issue a banned, evicted peer even one more request,
     * which the new request counter — plumbed through {@code HostilePeer.handle} — makes directly
     * observable rather than inferred from the absence of a side effect.
     */
    @Test
    void anIncompatibleGenesisPeerIsBannedAtExactlyTheTenthStrikeAndThenReceivesNoFurtherRequests()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            // No .mining(): the victim's height (and therefore its fork-probe target) stays fixed
            // at genesis for the whole scenario, so every round classifies the stranger identically.
            RhizomeNode victim = network.node("victim").start();

            try (HostilePeer stranger = HostilePeer.builder()
                    .claimsWork(wellFormedTotalWork(BigInteger.TWO.pow(200).toString()))
                    .start()) {
                admit(victim, stranger.url());

                for (int round = 1; round <= 9; round++) {
                    victim.syncRound();
                    assertTrue(victim.knownPeers().contains(stranger.url()),
                        "round " + round + " (" + (round * PENALTY_INCOMPATIBLE) + "/" + BAN_THRESHOLD
                            + " points): an incompatible-genesis peer must not be banned before the "
                            + "threshold is actually reached");
                    assertFalse(victim.banList().isBanned(PeerId.of(stranger.url())));
                }

                victim.syncRound(); // the 10th strike: 10 x 10 == BAN_THRESHOLD exactly
                assertTrue(victim.banList().isBanned(PeerId.of(stranger.url())),
                    "the 10th INCOMPATIBLE strike (10 x PENALTY_INCOMPATIBLE == BAN_THRESHOLD) "
                        + "must ban the peer");
                assertFalse(victim.knownPeers().contains(stranger.url()),
                    "a banned peer must be evicted from the registry, not merely marked");

                int requestsAtBan = stranger.requestCount();
                assertTrue(requestsAtBan > 0, "the peer must have actually been contacted to earn its bans");

                victim.syncRound(); // the 11th round: the peer no longer exists to the sync driver
                assertEquals(requestsAtBan, stranger.requestCount(),
                    "once banned and evicted, the sync driver must never issue this peer another "
                        + "request -- not even one -- for a round it no longer appears in");
            }
        }
    }

    /**
     * E2E-44 — a malformed, non-JSON {@code /block} response is classified {@code PEER_INVALID}
     * and costs {@link #PENALTY_INVALID} (34) points per strike, never confused with the cheaper
     * {@code INCOMPATIBLE} (10) path that {@link #anIncompatibleGenesisPeerIsBannedAtExactlyTheTenthStrikeAndThenReceivesNoFurtherRequests}
     * exercises. {@code 34} alone must not reach {@link #BAN_THRESHOLD} (round 1 must NOT evict —
     * "must not ban on the first offense" per the constant's own rationale, protecting an honest
     * peer transiently caught mid-reorg), but {@code 34 x 3 = 102 >= 100} must evict on the third.
     * {@code /block_count} and {@code /total_work} answer normally throughout ({@code claimsHeight}
     * and a properly JSON-shaped {@code claimsWork} keep them sane, so the sync pass reaches the
     * fork probe instead of short-circuiting on the base-work prefilter or an unrelated parse
     * failure); only {@code /block} lies, via the new {@code servesBlock} override, with bytes the
     * real JSON parser cannot read.
     */
    @Test
    void aMalformedBlockResponseIsPeerInvalidNeverConfusedWithIncompatibleAndBansOnlyAtTheThirdStrike()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode victim = network.node("victim").start();

            byte[] garbage = "this is not json at all -- just garbage bytes".getBytes(StandardCharsets.UTF_8);
            try (HostilePeer liar = HostilePeer.builder()
                    .claimsHeight(500)
                    .claimsWork(wellFormedTotalWork(BigInteger.TWO.pow(200).toString()))
                    .servesBlock(() -> garbage)
                    .start()) {
                admit(victim, liar.url());

                victim.syncRound(); // 1st strike: 34 points
                assertTrue(victim.knownPeers().contains(liar.url()),
                    "one PEER_INVALID strike (34 points) must not reach the 100-point threshold "
                        + "-- banning on the first offense would evict an honest peer transiently "
                        + "caught mid-reorg");
                assertFalse(victim.banList().isBanned(PeerId.of(liar.url())));

                victim.syncRound(); // 2nd strike: 68 points
                assertTrue(victim.knownPeers().contains(liar.url()),
                    "two PEER_INVALID strikes (68 points) must still be below the ban threshold");
                assertFalse(victim.banList().isBanned(PeerId.of(liar.url())));

                victim.syncRound(); // 3rd strike: 102 points -- crosses the threshold
                assertTrue(victim.banList().isBanned(PeerId.of(liar.url())),
                    "the 3rd PEER_INVALID strike (34 x 3 == 102 >= BAN_THRESHOLD) must ban the peer");
                assertFalse(victim.knownPeers().contains(liar.url()),
                    "a banned peer must be evicted from the registry");
            }
        }
    }
}
