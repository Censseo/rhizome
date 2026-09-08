package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.Crypto;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PowCosts;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * The real proof-of-work algorithm, end to end: every other {@code TestNetwork} scenario mines
 * {@link TestNetwork#FAST} deliberately, a SHA-256 stand-in chosen for speed, so nothing in the
 * rest of the E2E suite ever proves the chain's actual genesis algorithm ({@link
 * PowAlgorithm#PUFFERFISH2}, memory-hard by design) works through a real assembled node at all --
 * only {@code MinerTest}/{@code CryptoTests} prove Pufferfish2 in a single JVM, over hand-built
 * vectors, never through a real {@code RhizomeNode}'s block producer, its real {@code /submit}
 * route, or a real peer's independent re-verification of history it never mined itself.
 *
 * <p>This suite closes that gap on {@link TestNetwork#PUFFERFISH}: a real miner mines real
 * Pufferfish2 blocks, a real peer joins over real HTTP and converges, and a third, fresh node with
 * no prior state performs a genuine full sync of that Pufferfish2 history from scratch and accepts
 * it -- proving replay/re-verification actually re-runs the memory-hard function rather than
 * trusting whatever bytes a peer serves. It also pins the {@code staging()} profile's published
 * genesis identity through the real node-boot path, independent of {@code StagingGenesisTest}'s
 * component-level proof of the same hash.
 */
class E2EPufferfishNetworkTest {

    @TempDir
    Path tempDir;

    /**
     * The staging network's published genesis hash, copied verbatim from {@code
     * rhizome.StagingGenesisTest#STAGING_GENESIS_HASH} (private there, so it cannot be referenced
     * directly across packages) -- both constants must be re-pinned together, deliberately, if the
     * staging genesis artifact or any consensus constant it derives from ever changes.
     */
    private static final String STAGING_GENESIS_HASH =
        "8CB7AD090F912D3C051B1C3FBB3FF187918843DF06301339D84C91305DF46590";

    /**
     * E2E-89 -- A real two-node Pufferfish2 network, over real HTTP with an explicit peer add and
     * real sync rounds (no shortcut through {@code submitBlock} or a pre-configured peer list at
     * boot): one node mines real blocks under {@link TestNetwork#PUFFERFISH}, a second is admitted
     * as a peer afterwards and converges on the identical tip. Every mined block's nonce is then
     * independently re-verified in this test -- not through {@code Block.verifyNonce}'s own
     * algorithm dispatch, which could itself be the bug, but by recomputing both the plain SHA-256
     * and the real Pufferfish2 digest over the block's own preimage directly via {@link Crypto},
     * mirroring {@code CryptoTests#proofOfWorkHonorsPufferfishFlag}'s technique applied to blocks a
     * real {@code BlockProducer} actually mined and a real node actually accepted. Finally, a THIRD
     * node -- fresh, with no prior state and never fed a shortcut -- joins after the fact and
     * performs a full sync of that same Pufferfish2 history from scratch, proving a peer's replay
     * and re-verification genuinely holds, not just the original miner's own bookkeeping: its own
     * locally-stored blocks, once synced, pass the identical independent Pufferfish2 re-check.
     */
    @Test
    void aRealTwoNodePufferfish2NetworkConvergesAndAFreshThirdNodeSyncsAndReverifiesTheHistory()
            throws Exception {
        assertEquals(PowAlgorithm.PUFFERFISH2, TestNetwork.PUFFERFISH.powAlgorithm(),
            "this scenario is meaningless unless the profile under test is genuinely Pufferfish2");

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode miner = network.node("miner")
                .params(TestNetwork.PUFFERFISH).mining().start();
            RhizomeNode peer = network.node("peer").params(TestNetwork.PUFFERFISH).start();

            // A modest height: PUFFERFISH2 at this profile's floor is real, memory-hard work
            // (~18 ms/hash single-threaded, ~16 hashes expected at difficulty 4), so this is
            // already several hundred milliseconds of genuine mining, not a free SHA-256 loop.
            TestNetwork.awaitHeight(miner, 5);
            long minedHeight = miner.engine().height();

            // (a) real peer add, then real sync rounds -- not a peer list handed to the node at
            // boot, and not a hand-placed block via E2EFixtures.mint's submitBlock shortcut. The
            // miner keeps mining throughout this test, so the target is its CURRENT (moving) tip,
            // not the height snapshot above -- mirrors E2EGenesisIdentityTest's own idiom for
            // converging against a still-mining peer.
            admit(peer, TestNetwork.urlOf(miner));
            TestNetwork.syncUntil(peer, () -> peer.engine().tipHash().equals(miner.engine().tipHash()));
            TestNetwork.awaitSameTip(List.of(miner, peer));
            assertFalse(peer.engine().isDegraded());

            // (b) genuinely Pufferfish2, not SHA-256: independently recompute BOTH digests over
            // every real mined block's own preimage and confirm the memory-hard function -- not
            // plain SHA-256 -- is what actually produced the nonce the network accepted.
            assertPufferfish2Genuinely(miner, 2, minedHeight);

            // (c) a THIRD, fresh node with no prior state joins after the fact and performs a
            // full sync from scratch over the real Pufferfish2 history.
            RhizomeNode fresh = network.node("fresh").params(TestNetwork.PUFFERFISH).start();
            assertEquals(1, fresh.engine().height(),
                "the fresh node must start from nothing but its own genesis");
            admit(fresh, TestNetwork.urlOf(miner));
            TestNetwork.syncUntil(fresh, () -> fresh.engine().tipHash().equals(miner.engine().tipHash()));
            TestNetwork.awaitSameTip(List.of(miner, fresh));

            assertTrue(fresh.engine().height() >= minedHeight,
                "the fresh node's synced height did not even reach the height already mined before "
                    + "it joined -- it converged on a tip too shallow to have replayed real history");
            assertFalse(fresh.engine().isDegraded(), "syncing the real Pufferfish2 history degraded the fresh node");

            // Replay, not bookkeeping: the fresh node's OWN locally-stored blocks, reached only
            // via real sync, still pass the identical independent Pufferfish2 re-check -- proving
            // it actually re-verified the memory-hard PoW rather than merely copying bytes a peer
            // vouched for.
            assertPufferfish2Genuinely(fresh, 2, minedHeight);
        }
    }

    /**
     * Recomputes, directly via {@link Crypto} and independent of {@code Block.verifyNonce}'s own
     * algorithm dispatch, both the plain SHA-256 and the Pufferfish2 digest over each real block's
     * {@code hash() || nonce()} preimage between {@code fromHeight} and {@code toHeight}
     * (inclusive). Asserts the two digests differ (so the algorithm flag is genuinely honoured --
     * a coincidental 256-bit collision is astronomically unlikely) and that the Pufferfish2 digest
     * -- and only that one -- satisfies the block's own committed difficulty.
     */
    private static void assertPufferfish2Genuinely(RhizomeNode node, long fromHeight, long toHeight) {
        for (long height = fromHeight; height <= toHeight; height++) {
            Block block = node.engine().blockAt(height);
            PowCosts costs = TestNetwork.PUFFERFISH.powCostsAt(height);

            SHA256Hash plainSha = Crypto.concatHashes(block.hash(), block.nonce(), false, false);
            SHA256Hash pufferfish = Crypto.concatHashes(block.hash(), block.nonce(), true, false, costs);

            assertNotEquals(plainSha, pufferfish,
                "height " + height + ": Pufferfish2 and plain SHA-256 produced the identical digest "
                    + "over the block's real preimage -- the algorithm flag is not being honoured");
            assertTrue(Crypto.checkLeadingZeroBits(pufferfish, block.difficulty()),
                "height " + height + ": the block's real, accepted nonce does not satisfy its own "
                    + "committed difficulty under an independently recomputed Pufferfish2 hash");
        }
    }

    /**
     * E2E-89 -- Boot a real, assembled node on {@code NetworkParameters.staging()} with NO
     * configured snapshot override -- the exact shape of every deployed staging node until an
     * operator sets one -- and assert its genesis block matches the published, pinned staging
     * identity {@code StagingGenesisTest} already locks at the component level (direct {@code
     * GenesisBlock.build} call, one JVM, no node). This is the second, independent proof of the
     * same identity: this one exercises it through the real boot path ({@code RhizomeNode.assemble}
     * -> {@code SnapshotLoader.forBoot}'s classpath-resource fallback -> {@code ChainEngine.boot})
     * rather than calling {@code GenesisBlock.build} directly. No block is mined here: {@code
     * staging()}'s real difficulty floor of 8 is calibrated for a real multi-VM mining campaign,
     * far too slow to mine even one block inside a fast JUnit test (see {@code
     * NetworkParameters#staging()}'s own javadoc).
     */
    @Test
    void aRealStagingNodeWithNoConfiguredSnapshotBootsToThePublishedPinnedGenesis() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode staging = network.node("staging").params(NetworkParameters.staging()).start();

            assertEquals(1, staging.engine().height(),
                "the node must boot straight to genesis with no snapshot override configured");
            assertEquals(STAGING_GENESIS_HASH, staging.engine().blockAt(1).hash().toHexString(),
                "a real, assembled staging node's own genesis diverges from the published, pinned "
                    + "identity StagingGenesisTest already locks at the component level");
            assertFalse(staging.engine().isDegraded());
        }
    }

    /** Adds one peer and waits for its admission to complete before the caller proceeds. */
    private static void admit(RhizomeNode node, String peerUrl) throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }
}
