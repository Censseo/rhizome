package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * Where a real, assembled node's genesis identity actually comes from — and, just as important,
 * where it deliberately does NOT come from: the network.
 *
 * <p>{@code GenesisBlockTest}/{@code LedgerSnapshotTest} prove the pure function
 * ({@code NetworkParameters} + {@code LedgerSnapshot} -> a deterministic {@code Block}) in a
 * single JVM. None of them drive a real assembled {@link RhizomeNode} through the actual boot
 * fallback an operator meets in production (no {@code RHIZOME_SNAPSHOT} at all), none prove that
 * several independently-started real processes land on the SAME genesis before they ever talk to
 * each other, and none prove that a node joining the network via snap-sync still derives its
 * genesis locally rather than accepting whatever a peer serves for height 1. There is no
 * adversary in any of these four scenarios — the property under test holds (or fails) entirely
 * from each node's own configuration, which is exactly why an outside "observer" comparing public
 * genesis hashes is the right frame rather than a hostile-peer one (see
 * {@link E2EGenesisEclipseTest} for that half).
 */
class E2EGenesisIdentityTest {

    @TempDir
    Path tempDir;

    /**
     * Mainnet's real pinned allocation and supply pin, but with cheap PoW so a scenario can mine
     * real blocks on top of it without paying Pufferfish2's cost — the {@code TestNetwork.FAST}
     * pattern, started from {@code cleanMainnet()} instead of {@code testnet()} so the mainnet
     * genesis-supply pin (and its shipped allocation resource) survive untouched.
     */
    private static final NetworkParameters MAINNET_FAST = NetworkParameters.cleanMainnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256)
        .genesisDifficulty(3)
        .minDifficulty(3)
        .maxDifficulty(16)
        .build();

    /** A shallow finality window, purely for test speed — see {@code E2EPrunedAndSnapSyncTest}'s
     *  identical rationale: both pruning retention and a snapshot pivot are gated on it, and the
     *  production 120-block window would cost real wall-clock mining to prove nothing extra. */
    private static final NetworkParameters SHALLOW = TestNetwork.FAST.toBuilder().maxReorgDepth(8).build();

    /**
     * E2E-37 — boot a real, assembled mainnet node with no {@code RHIZOME_SNAPSHOT} configured at
     * all (no {@code .snapshot(...)} call here) — the exact shape of every deployed node until an
     * operator sets the override — hoping the classpath-resource fallback
     * ({@code SnapshotLoader.forBoot}'s resource branch) is silently skipped, yields an empty
     * ledger, or otherwise fails to reach the pinned {@code S0} in the running node's own genesis
     * header. No existing test drove a real assembled {@code RhizomeNode} through this exact
     * fallback end to end; the existing suites call {@code SnapshotLoader}/{@code LedgerSnapshot}
     * directly, in one JVM, never through {@code RhizomeNode.assemble}.
     */
    @Test
    void aRealMainnetNodeWithNoConfiguredSnapshotLoadsTheEmbeddedAllocationAndCommitsThePinnedSupply()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode solo = network.node("solo").params(NetworkParameters.cleanMainnet()).start();

            assertEquals(1, solo.engine().height(),
                "the node must boot straight to its genesis with no snapshot override configured");
            assertEquals(NetworkParameters.cleanMainnet().genesisSupply(),
                solo.engine().blockAt(1).supply(),
                "the assembled node's genesis must commit the pinned mainnet S0 (never 0/empty) "
                    + "even though no RHIZOME_SNAPSHOT was ever set");
            assertFalse(solo.engine().isDegraded());
        }
    }

    /**
     * E2E-38 — boot three independent, real mainnet nodes with no shared file and no peering yet,
     * hoping their genesis blocks disagree by even one bit before gossip gets a chance to paper
     * over the difference — which would mean the "bit-identical genesis" property actually rests
     * on gossip converging two DIFFERENT chains rather than on determinism. This is the literal
     * end-to-end proof of spec SC-002 ("two default-configured mainnet nodes derive bit-identical
     * genesis blocks"), which the feature's own plan explicitly substituted with a single-JVM
     * {@code lib-core} call; this test closes that documented substitution by driving three real
     * processes, each with its own RocksDB store and its own classpath resource load, through the
     * comparison before any socket between them is ever opened.
     */
    @Test
    void threeIndependentMainnetNodesDeriveABitIdenticalGenesisBeforeAnyPeeringHappens() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode a = network.node("a").params(MAINNET_FAST).mining().blockInterval(150).start();
            RhizomeNode b = network.node("b").params(MAINNET_FAST).start();
            RhizomeNode c = network.node("c").params(MAINNET_FAST).start();

            // Compared BEFORE any peer is ever configured or admitted: whatever agreement exists
            // here can only be the classpath resource plus deterministic construction, never gossip.
            SHA256Hash genesisHash = a.engine().blockAt(1).hash();
            assertEquals(genesisHash, b.engine().blockAt(1).hash(),
                "two independently booted mainnet nodes derived different genesis blocks "
                    + "before either ever heard of a peer");
            assertEquals(genesisHash, c.engine().blockAt(1).hash(),
                "a third independently booted mainnet node also disagreed on genesis");

            // Independently recomputed a third time, IN THE TEST JVM, from nothing any node's boot
            // path touched: read the same shipped resource by hand and rebuild the genesis with the
            // pure functions the unit suites already lock, so this assertion cannot pass merely
            // because all three nodes share one (possibly buggy) code path.
            String resourcePath = MAINNET_FAST.genesisSnapshotResource().orElseThrow();
            byte[] resourceBytes;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                assertTrue(in != null, "the shipped genesis resource is missing: " + resourcePath);
                resourceBytes = in.readAllBytes();
            }
            LedgerSnapshot expectedSnapshot = LedgerSnapshot.fromJson(
                new JSONObject(new String(resourceBytes, StandardCharsets.UTF_8)));
            SHA256Hash expectedGenesis = GenesisBlock.build(MAINNET_FAST, expectedSnapshot).hash();
            assertEquals(expectedGenesis, genesisHash,
                "none of the three nodes' genesis matches GenesisBlock.build() over the shipped "
                    + "allocation, recomputed independently in this JVM");

            // THEN, only now, let the network layer do its job: peer the three together and mine on
            // exactly one. Convergence here is a bonus property (the assembled node still behaves),
            // not what proves genesis identity -- that was already established above, unpeered.
            admit(b, TestNetwork.urlOf(a));
            admit(c, TestNetwork.urlOf(a));
            TestNetwork.awaitHeight(a, 3);
            TestNetwork.syncUntil(List.of(b, c), () -> {
                SHA256Hash tip = a.engine().tipHash();
                return tip.equals(b.engine().tipHash()) && tip.equals(c.engine().tipHash());
            });
            TestNetwork.awaitSameTip(List.of(a, b, c));
            assertEquals(genesisHash, a.engine().blockAt(1).hash(),
                "peering and mining must never rewrite the genesis the three nodes already agreed on");
        }
    }

    /**
     * E2E-41 — join a pruned node to the network purely through snap-sync against an HONEST
     * archive peer, hoping the bootstrapped node ends up trusting that PEER — not its own,
     * locally-built genesis — for chain identity, so that a later, unrelated node given a
     * different (even if merely stale or buggy) sync source would silently diverge. Proven by
     * comparing the snap-synced node's genesis not only against its own sync source but also
     * against a SECOND, fully independent honest node that the snap-synced node never talked to
     * at all: if all three agree, the snap-synced node cannot have been trusting its one peer for
     * this — the peer-less third node could not have supplied it.
     */
    @Test
    void aPrunedNodeJoiningViaSnapSyncDerivesItsGenesisLocallyNotFromItsSyncSource() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode source = network.node("source")
                .params(SHALLOW).mining().blockInterval(60).snapshotEvery(5).start();
            // Never peered with anything in this scenario -- its only role is an independent
            // witness to what "the" genesis for these params actually is.
            RhizomeNode independentWitness = network.node("witness").params(SHALLOW).start();

            assertEquals(source.engine().blockAt(1).hash(), independentWitness.engine().blockAt(1).hash(),
                "two independently booted nodes under the same params must already agree on genesis, "
                    + "or the rest of this scenario proves nothing");

            // Mine the source well past the finality window so a materialised pivot is buried.
            TestNetwork.awaitHeight(source, 30);
            assertTrue(source.service().materializeSnapshot(),
                "the source node could not materialise a state snapshot to serve");
            TestNetwork.await(() -> source.service().snapshotPivot() > 1,
                () -> "no snapshot pivot was ever advertised");
            long pivot = source.service().snapshotPivot();
            TestNetwork.awaitHeight(source, pivot + SHALLOW.maxReorgDepth() + 2);

            RhizomeNode pruned = network.node("pruned")
                .params(SHALLOW).snapSync().peers(TestNetwork.urlOf(source)).start();
            TestNetwork.syncUntil(pruned, () -> pruned.engine().height() > pivot);

            assertEquals(source.engine().blockAt(1).hash(), pruned.engine().blockAt(1).hash(),
                "the snap-synced node disagrees with its own sync source about genesis");
            // The decisive comparison: agreement with a node the snap-synced one never exchanged a
            // single byte with rules out "it just accepted whatever bytes the peer offered for
            // height 1" -- the only way all three can agree is that each derived genesis locally,
            // from params + snapshot, exactly as GenesisBlock.build defines it.
            assertEquals(independentWitness.engine().blockAt(1).hash(), pruned.engine().blockAt(1).hash(),
                "the snap-synced node's genesis is not what an uninvolved, independently-booted node "
                    + "derives from the same parameters -- chain identity leaked from the sync peer");
            assertFalse(pruned.engine().isDegraded(), "the bootstrap left the node degraded");
        }
    }

    /**
     * E2E-42 — boot an unmodified {@code NetworkParameters.testnet()} node (no
     * {@code .toBuilder().genesisSupply(...)} override — the profile exactly as the factory method
     * returns it) from a snapshot whose total has no relationship to any pinned constant, hoping
     * the real end-to-end harness turns out to silently re-pin the unpinned sentinel the way every
     * other testnet-based E2E scenario conveniently does for its own convenience. A
     * completeness-critic review flagged that every existing testnet scenario re-pins
     * {@code genesisSupply} (directly or via {@code TestNetwork.FAST}, which itself is a
     * {@code toBuilder()} off {@code testnet()}), so none of them actually exercise the unpinned
     * sentinel surviving the real harness untouched. This test is the one that does.
     */
    @Test
    void anUnmodifiedTestnetProfileAcceptsAnArbitraryGenesisTotalThroughTheRealHarness() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            NetworkParameters unmodifiedTestnet = NetworkParameters.testnet();
            assertEquals(NetworkParameters.GENESIS_SUPPLY_UNPINNED, unmodifiedTestnet.genesisSupply(),
                "this scenario is meaningless unless testnet() itself, untouched, is unpinned");

            E2EFixtures.Identity holder = E2EFixtures.Identity.generate();
            // An arbitrary total with no relationship whatsoever to any pinned mainnet constant.
            long arbitraryTotal = 123_456_789L;
            Path snapshot = E2EFixtures.premine(tempDir.resolve("arbitrary-total.json"),
                unmodifiedTestnet, Map.of(holder, arbitraryTotal));

            RhizomeNode node = network.node("t").params(unmodifiedTestnet).snapshot(snapshot).start();

            assertEquals(1, node.engine().height(), "boot must succeed regardless of the file's total");
            assertEquals(arbitraryTotal, node.engine().blockAt(1).supply(),
                "the unpinned profile must commit the snapshot's own total exactly, not refuse it "
                    + "and not silently substitute a different one");
            assertFalse(node.engine().isDegraded());
        }
    }

    /**
     * E2E-47 — a genesis-supply pin mismatch refuses boot identically whether it is discovered
     * via an ABORTED snap-sync bootstrap attempt against a real, honest, reachable peer, or via
     * direct startup with no peer configured at all -- hoping the silent WARN-and-fall-through
     * {@code RhizomeNode.assemble}'s snap-sync loop applies to a failed bootstrap attempt (so a
     * hostile or merely broken peer can never crash startup) also softens or masks the pin-
     * mismatch refusal that follows it, rather than the refusal surfacing, uncaught, from the
     * exact same {@code GenesisBlock.build} call the direct-boot path runs.
     *
     * <p>{@code SnapshotBootstrap.bootstrap} derives genesis LOCALLY from the node's own params
     * and snapshot, before it ever downloads a peer body -- so as soon as the peer's advertised
     * pivot passes the burial check, that call throws the identical {@code IllegalArgumentException}
     * {@code GenesisBlock.build} always throws for a mismatched total. {@code assemble()}'s
     * snap-sync loop catches it as an ordinary {@code RuntimeException}, logs a {@code WARN}, and
     * falls through to the normal {@code ChainEngine.boot()} path, which runs the SAME
     * {@code GenesisBlock.build} call uncaught -- and that is where the refusal must actually
     * reach the caller. The honest peer here mines and materialises a real state snapshot at a
     * genuinely buried pivot, so the bootstrap attempt is real (reaches the local genesis build)
     * rather than short-circuiting on "peer advertises no snapshot" before ever touching genesis.
     */
    @Test
    void aGenesisSupplyPinMismatchRefusesBootIdenticallyViaAnAbortedSnapSyncOrDirectBoot()
            throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            long pinnedSupply = 1_000_000L;
            long badTotal = pinnedSupply + 1;
            // A small finality window purely for test speed -- see SHALLOW's identical rationale
            // above: burying a snapshot pivot under the production 120-block window would cost
            // real wall-clock mining to prove nothing extra about the refusal itself.
            NetworkParameters pinned = TestNetwork.FAST.toBuilder()
                .genesisSupply(pinnedSupply)
                .maxReorgDepth(3)
                .build();

            E2EFixtures.Identity holder = E2EFixtures.Identity.generate();
            Path badSnapshot = E2EFixtures.premine(tempDir.resolve("mismatched-total.json"),
                pinned, Map.of(holder, badTotal));

            // A real, honest, reachable peer with a genuine materialised snapshot at a pivot
            // buried under PINNED's (the victim's) maxReorgDepth -- so the victim's snap-sync
            // loop makes a real bootstrap ATTEMPT (reaching SnapshotBootstrap.bootstrap's local
            // genesis build) instead of returning false on "peer advertises no snapshot" before
            // ever touching genesis. Its own params need no supply pin at all: it is never the
            // one refusing anything here.
            RhizomeNode source = network.node("source")
                .params(TestNetwork.FAST).mining().blockInterval(60).start();
            TestNetwork.awaitHeight(source, 6);
            assertTrue(source.service().materializeSnapshot(),
                "the honest source node could not materialise a state snapshot to serve");
            TestNetwork.await(() -> source.service().snapshotPivot() > 1,
                () -> "no snapshot pivot was ever advertised");
            long pivot = source.service().snapshotPivot();
            TestNetwork.awaitHeight(source, pivot + pinned.maxReorgDepth() + 2);

            IllegalArgumentException viaAbortedSnapSync = assertThrows(IllegalArgumentException.class,
                () -> network.node("viaSnapSync").params(pinned).snapshot(badSnapshot)
                    .snapSync().peers(TestNetwork.urlOf(source)).start(),
                "a mismatched genesis-supply snapshot must still refuse boot after an aborted "
                    + "snap-sync bootstrap attempt against an honest, reachable peer");

            IllegalArgumentException viaDirectBoot = assertThrows(IllegalArgumentException.class,
                () -> network.node("viaDirectBoot").params(pinned).snapshot(badSnapshot).start(),
                "the identical mismatched snapshot must refuse boot with no peer configured at all");

            assertEquals(viaDirectBoot.getMessage(), viaAbortedSnapSync.getMessage(),
                "an aborted snap-sync attempt's silent WARN-and-fall-through must never soften or "
                    + "mask the pin-mismatch refusal that follows it -- both paths must fail with "
                    + "the identical message naming the same two totals");
            assertTrue(viaDirectBoot.getMessage().contains(Long.toUnsignedString(badTotal)),
                "expected the snapshot's actual total in the message: " + viaDirectBoot.getMessage());
            assertTrue(viaDirectBoot.getMessage().contains(Long.toUnsignedString(pinnedSupply)),
                "expected the pinned S0 in the message: " + viaDirectBoot.getMessage());
        }
    }

    /** Adds one peer and waits for its admission to complete before the caller adds another. */
    private static void admit(RhizomeNode node, String peerUrl) throws InterruptedException {
        node.service().addPeer(peerUrl);
        TestNetwork.await(() -> node.knownPeers().contains(peerUrl),
            () -> "peer " + peerUrl + " was never admitted; known: " + node.knownPeers());
    }
}
