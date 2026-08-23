package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * What a real restart does to a genesis identity that is supposed to be fixed: not "the process
 * came back up", but "the on-disk chain it comes back up ON is bit-for-bit what it was".
 *
 * <p>{@code E2ENodeResilienceTest#aRestartOnTheSameDataDirectoryRestoresChainBalancesAndNonces}
 * proves a restart on an UNCHANGED configuration restores balances and nonces. This suite asks the
 * adversarial twin of that question: what happens when the restart's configuration has silently
 * changed underneath an existing store — a redistributed allocation artifact carrying the same
 * pinned total, or a flipped network profile entirely. {@code ChainEngine.Boot.build()}'s stored-
 * genesis check (the {@code else if} branch guarding a non-empty store — see
 * {@code ChainEngine.java}) is the single choke point every scenario here drives a real,
 * assembled {@link RhizomeNode} through via {@link TestNetwork#reopen}, which generalises
 * {@code E2ENodeResilienceTest}'s single-node "close, then rebuild over the same data directory"
 * pattern into a network-level convenience.
 */
class E2EGenesisRestartTest {

    @TempDir
    Path tempDir;

    /**
     * Mainnet's real pinned allocation and supply pin, but with cheap PoW so a scenario can mine
     * real blocks on top of it without paying Pufferfish2's cost. Mirrors
     * {@code E2EGenesisIdentityTest#MAINNET_FAST} exactly (that field is private to its own class,
     * so this is a deliberate mirror, not a third variant of the profile).
     */
    private static final NetworkParameters MAINNET_FAST = NetworkParameters.cleanMainnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256)
        .genesisDifficulty(3)
        .minDifficulty(3)
        .maxDifficulty(16)
        .build();

    /**
     * A testnet-derived profile with a genuinely pinned genesis supply, re-pinned via
     * {@code toBuilder().genesisSupply(...)} purely for these tests: {@code testnet()} itself
     * ships unpinned (see {@code NetworkParameters#testnet()}'s "Decision 7" comment), so proving
     * a pin-carrying restart's refusal needs a profile that actually carries one.
     */
    private static final long PINNED_TOTAL = 1_000_000L;
    private static final NetworkParameters PINNED = TestNetwork.FAST.toBuilder()
        .genesisSupply(PINNED_TOTAL)
        .build();

    /**
     * E2E-51 — restart a node whose on-disk chain committed a pinned total under one balance
     * distribution, but hand the restart a DIFFERENT distribution of that exact same pinned total
     * — the shape of a future governance revision that reallocates the same {@code S0} without
     * touching the pinned constant itself — hoping the boot-time pin check (which only compares
     * totals) is the only gate standing between the operator and silently rewriting who owns the
     * genesis coins on an already-running chain. It is not: {@code GenesisBlock.build}'s pin check
     * passes (the totals agree), but {@code ChainEngine.Boot.build()}'s stored-genesis
     * re-verification then compares the freshly-built genesis's hash — which commits the full
     * distribution via {@code LedgerSnapshot#commitmentHash}, not merely the total — against what
     * is already on disk, and refuses the mismatch. The proof does not stop at "it threw": a THIRD
     * reopen with the ORIGINAL distribution must still succeed and land on exactly the chain the
     * node had before the refused attempt, showing the refused attempt in the middle never touched
     * the on-disk store at all.
     */
    @Test
    void redistributingThePinnedTotalOnARealRestartIsRefusedByCommitmentReVerification() throws Exception {
        E2EFixtures.Identity holderA1 = E2EFixtures.Identity.generate();
        E2EFixtures.Identity holderA2 = E2EFixtures.Identity.generate();
        E2EFixtures.Identity holderB1 = E2EFixtures.Identity.generate();
        E2EFixtures.Identity holderB2 = E2EFixtures.Identity.generate();
        Path distributionA = E2EFixtures.premine(tempDir.resolve("distribution-a.json"), PINNED,
            Map.of(holderA1, 700_000L, holderA2, 300_000L));
        Path distributionB = E2EFixtures.premine(tempDir.resolve("distribution-b.json"), PINNED,
            Map.of(holderB1, 250_000L, holderB2, 750_000L));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("restart")
                .params(PINNED).snapshot(distributionA).mining().blockInterval(60).start();
            TestNetwork.awaitHeight(node, 3);
            // Read BEFORE the refused reopen closes this node — the reference the third reopen
            // must reproduce exactly. A live producer may bury one more block between this read
            // and the close a moment later; blockAt(a fixed past height), not tipHash(), is the
            // comparison that stays valid regardless (same discipline as
            // E2ENodeResilienceTest's restart assertions).
            long heightBeforeRefusal = node.engine().height();
            SHA256Hash hashAtThatHeight = node.engine().blockAt(heightBeforeRefusal).hash();

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> network.reopen("restart").params(PINNED).snapshot(distributionB).start(),
                "a same-total, different-distribution restart must be refused by stored-genesis "
                    + "re-verification, not silently accepted because the pin check alone passed");
            // Empirically confirmed (not assumed): since the two distributions share the pinned
            // total, GenesisBlock.build's OWN pin check (which only compares totals) passes for
            // both, so the refusal comes from ChainEngine.Boot.build()'s generic stored-genesis
            // comparison instead -- it names neither total (unlike the genuinely-mismatched-total
            // case E2EGenesisIdentityTest#aGenesisSupplyPinMismatchRefusesBootIdenticallyViaAn...
            // asserts on), but it does unambiguously identify a genesis/commitment disagreement.
            assertEquals("Stored genesis does not match network parameters and snapshot",
                refused.getMessage(),
                "expected ChainEngine.Boot.build()'s stored-genesis re-verification message");

            RhizomeNode restored = network.reopen("restart")
                .params(PINNED).snapshot(distributionA).start();
            assertTrue(restored.engine().height() >= heightBeforeRefusal,
                "restoring the ORIGINAL distribution must come back at least as far as before the "
                    + "refused attempt, not truncated");
            assertEquals(hashAtThatHeight, restored.engine().blockAt(heightBeforeRefusal).hash(),
                "the block at the pre-refusal height must be byte-identical after the refused "
                    + "middle attempt -- proving that attempt never touched the on-disk store");
            assertFalse(restored.engine().isDegraded(),
                "the restart came back degraded, which means the refused attempt left torn state");
        }
    }

    /**
     * E2E-52 — the multi-block twin of E2E-51: a genuinely non-trivial chain (9 real blocks, not
     * 3) must survive a refused restart with EVERY one of those blocks byte-identical, in order,
     * with none silently re-derived, truncated, or partially overwritten by the aborted middle
     * attempt with the "updated" (same-total, different-distribution) artifact.
     */
    @Test
    void aNineBlockChainSurvivesARefusedRestartCompletelyUntouched() throws Exception {
        E2EFixtures.Identity holderA1 = E2EFixtures.Identity.generate();
        E2EFixtures.Identity holderA2 = E2EFixtures.Identity.generate();
        E2EFixtures.Identity holderB1 = E2EFixtures.Identity.generate();
        E2EFixtures.Identity holderB2 = E2EFixtures.Identity.generate();
        Path distributionA = E2EFixtures.premine(tempDir.resolve("nine-a.json"), PINNED,
            Map.of(holderA1, 400_000L, holderA2, 600_000L));
        Path distributionB = E2EFixtures.premine(tempDir.resolve("nine-b.json"), PINNED,
            Map.of(holderB1, 600_000L, holderB2, 400_000L));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode node = network.node("nine")
                .params(PINNED).snapshot(distributionA).mining().blockInterval(60).start();
            TestNetwork.awaitHeight(node, 9);

            long heightBeforeRefusal = node.engine().height();
            List<SHA256Hash> hashesBeforeRefusal = new ArrayList<>();
            for (long h = 1; h <= heightBeforeRefusal; h++) {
                hashesBeforeRefusal.add(node.engine().blockAt(h).hash());
            }

            assertThrows(IllegalStateException.class,
                () -> network.reopen("nine").params(PINNED).snapshot(distributionB).start(),
                "a same-total, different-distribution restart on a 9-block chain must be refused "
                    + "exactly like the 3-block case -- this is a matter of degree, not kind");

            RhizomeNode restored = network.reopen("nine")
                .params(PINNED).snapshot(distributionA).start();
            assertTrue(restored.engine().height() >= heightBeforeRefusal,
                "the restored chain must not be shorter than it was before the refused attempt");
            for (long h = 1; h <= heightBeforeRefusal; h++) {
                assertEquals(hashesBeforeRefusal.get((int) (h - 1)), restored.engine().blockAt(h).hash(),
                    "block " + h + " changed across the refused restart -- no truncation, no "
                        + "silent re-derivation and no partial write is acceptable here");
            }
            assertFalse(restored.engine().isDegraded());
        }
    }

    /**
     * E2E-53 — flip the network profile on an existing data directory (the shape of an operator
     * changing {@code RHIZOME_NETWORK} without realising the directory already holds a different
     * network's chain) and confirm the restart is refused rather than silently reinterpreted as a
     * brand-new, empty chain for the new network. The decisive assertion is not merely that
     * {@code .start()} throws: a THIRD reopen with the ORIGINAL mainnet profile must still come
     * back at its original height, proving the refused testnet attempt never got far enough to
     * reset or partially overwrite the store as though it were height 0.
     */
    @Test
    void flippingTheNetworkProfileOnAnExistingDataDirectoryIsRefusedNotReinterpreted() throws Exception {
        E2EFixtures.Identity testnetHolder = E2EFixtures.Identity.generate();
        Path testnetSnapshot = E2EFixtures.premine(tempDir.resolve("testnet-appropriate.json"),
            TestNetwork.FAST, Map.of(testnetHolder, 42_000L));

        try (TestNetwork network = new TestNetwork(tempDir)) {
            // No .snapshot(...) override: exactly the no-RHIZOME_SNAPSHOT shape E2E-37 already
            // drives, so this node's genesis is the real shipped mainnet allocation.
            RhizomeNode node = network.node("flip")
                .params(MAINNET_FAST).mining().blockInterval(60).start();
            TestNetwork.awaitHeight(node, 2);
            long heightBeforeFlip = node.engine().height();
            SHA256Hash genesisHash = node.engine().blockAt(1).hash();
            SHA256Hash hashAtThatHeight = node.engine().blockAt(heightBeforeFlip).hash();

            // testnet and mainnet have different chainIds (2 vs 1); MAINNET_FAST/TestNetwork.FAST
            // also differ in genesisSupply (pinned vs unpinned) and PoW parameters, so whichever
            // guard fires first, this combination cannot be mistaken for the mainnet chain already
            // on disk.
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> network.reopen("flip")
                    .params(TestNetwork.FAST).snapshot(testnetSnapshot).start(),
                "flipping the network profile on an existing mainnet data directory must be "
                    + "refused, not silently accepted as a fresh empty testnet chain");
            // Empirically confirmed (not assumed): GenesisBlock.build's own chainId-mismatch guard
            // compares the GIVEN params against the GIVEN snapshot, and testnetSnapshot's chainId
            // was built to match TestNetwork.FAST (both testnet) -- so that guard does not fire
            // here. The refusal instead comes from the same generic stored-genesis comparison as
            // E2E-51/E2E-52, comparing the freshly-built testnet genesis's hash against the
            // mainnet genesis already on disk. A DIFFERENT setup (reusing a mainnet-chainId
            // snapshot file under testnet params) would instead hit GenesisBlock.build's own
            // IllegalArgumentException before matches() is ever reached -- both are real, refused
            // paths; this scenario's realistic "flip RHIZOME_NETWORK, and its snapshot along with
            // it" shape happens to exercise the second one.
            assertEquals("Stored genesis does not match network parameters and snapshot",
                refused.getMessage(),
                "expected ChainEngine.Boot.build()'s stored-genesis re-verification message");

            RhizomeNode restored = network.reopen("flip").params(MAINNET_FAST).start();
            assertTrue(restored.engine().height() >= heightBeforeFlip,
                "the mainnet chain must come back at least as far as it was before the refused "
                    + "testnet attempt");
            assertEquals(genesisHash, restored.engine().blockAt(1).hash(),
                "genesis must be unchanged -- the refused testnet attempt must never have reset "
                    + "this directory to height 0 under a different chain identity");
            assertEquals(hashAtThatHeight, restored.engine().blockAt(heightBeforeFlip).hash(),
                "the block at the pre-flip height must be byte-identical after the refused attempt");
            assertFalse(restored.engine().isDegraded());
        }
    }
}
