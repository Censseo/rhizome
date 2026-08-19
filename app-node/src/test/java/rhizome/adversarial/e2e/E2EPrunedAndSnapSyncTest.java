package rhizome.adversarial.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.node.NodeConfig;
import rhizome.node.RhizomeNode;

/**
 * The two ways a node can hold less than the whole chain and still be correct: pruning old bodies,
 * and bootstrapping from a state snapshot instead of replaying history.
 *
 * <p>Both are proved at servlet level elsewhere ({@code PrunedNodeApiTest},
 * {@code SnapSyncIntegrationTest}) against a hand-built store and a servlet. Neither had a proof
 * involving an actual node, and both are exactly the kind of feature whose failure mode is an
 * assembly detail: a watermark computed at boot from a store that pruned on a different rule, or a
 * bootstrap that adopts state the node then cannot serve. What matters here is the honesty of the
 * result — a node that no longer has a body must say so, with the watermark, rather than answer as
 * if the chain were shorter than it is.
 */
class E2EPrunedAndSnapSyncTest {

    @TempDir
    Path tempDir;

    /**
     * A shallow finality window. Both features are gated on it — retention must cover the reorg
     * depth, and a snapshot pivot must be buried past it — so the production 120 blocks would make
     * these scenarios cost a minute of mining each to prove rules that 8 blocks prove identically.
     */
    private static final NetworkParameters SHALLOW =
        TestNetwork.FAST.toBuilder().maxReorgDepth(8).build();

    /**
     * E2E-31 — a node restarted with pruning enabled discards old bodies. It must then refuse a
     * request into the pruned range with the watermark rather than a generic error, keep serving
     * the headers it still has, and go on producing.
     */
    @Test
    void aPrunedNodeRefusesPrunedBodiesWithItsWatermarkAndKeepsServingHeaders() throws Exception {
        String dataDir = tempDir.resolve("pruner").toString();
        int port = TestNetwork.freePort();
        NodeConfig archive = NodeConfig.defaults(SHALLOW, dataDir, port)
            .withAllowPrivatePeers(true)
            .withMiner(PublicAddress.random())
            .withBlockIntervalMs(60);

        long minedHeight;
        try (RhizomeNode node = new RhizomeNode(archive)) {
            node.start();
            TestNetwork.awaitHeight(node, 30);
            minedHeight = node.engine().height();
        }

        // Restart the same directory as a pruned node: retention is applied at boot.
        int keep = 12;
        NodeConfig pruned = new NodeConfig(archive.params(), archive.dataDir(), archive.apiPort(),
            archive.snapshotPath(), archive.miner(), archive.peers(), archive.advertisedUrl(),
            archive.syncPeriodMs(), archive.blockIntervalMs(), archive.mempoolSize(),
            archive.allowPrivatePeers(), archive.bindAddress(), archive.apiToken(),
            keep, archive.allowOpenApi(), archive.peerToken(), archive.snapSync(),
            archive.protectReads(), archive.trustXff(), archive.vote(),
            archive.snapshotEveryBlocks(), archive.hostAllowlistEnabled(),
            archive.extraAllowedHosts());

        try (RhizomeNode node = new RhizomeNode(pruned)) {
            node.start();
            assertTrue(node.engine().height() >= minedHeight, "the restart lost chain height");

            long watermark = node.service().prunedBelow();
            assertTrue(watermark > 1,
                "pruning was configured but nothing was pruned (watermark " + watermark + ")");

            // A body the node no longer holds: 410 GONE, not a 400 and not a lie.
            var gone = RawHttp.get(port, "/sync?start=1&end=2", Map.of());
            assertEquals(410, gone.status(),
                "a request into the pruned range must be answered with GONE and the watermark");
            // The node is still mining, so the watermark advances between the read above and the
            // request below. Assert the property that actually holds on a live node — the served
            // watermark is a real one, and it only ever moves forward — rather than an equality
            // that is true only if the chain happens to stand still.
            java.util.regex.Matcher served = java.util.regex.Pattern
                .compile("\"prunedBelow\"\\s*:\\s*(\\d+)").matcher(gone.body());
            assertTrue(served.find(),
                "the refusal must carry the prune watermark; body=" + gone.body());
            long servedWatermark = Long.parseLong(served.group(1));
            assertTrue(servedWatermark >= watermark,
                "the served watermark went backwards: " + servedWatermark + " < " + watermark);
            assertTrue(servedWatermark > 1, "the served watermark is not a pruned height");

            // Headers survive pruning: they are what a peer needs to validate the chain at all.
            assertEquals(200, RawHttp.get(port, "/headers?start=1&end=3", Map.of()).status(),
                "headers must remain served for every height, pruned bodies included");
            // And the retained tail is still served in full.
            assertEquals(200, RawHttp.get(port,
                "/sync?start=" + (node.engine().height() - 2) + "&end=" + node.engine().height(),
                Map.of()).status());

            assertFalse(node.engine().isDegraded());
            long resumed = node.engine().height();
            TestNetwork.await(() -> node.engine().height() > resumed,
                () -> "a pruned node stopped producing blocks");
        }
    }

    /**
     * E2E-32 — a fresh node bootstraps from a peer's materialised state snapshot rather than
     * replaying every block, and ends up agreeing with that peer about the chain. The risk this
     * covers is adopting state that cannot then be served or extended: a node that bootstraps and
     * is immediately wrong is worse than one that syncs slowly.
     */
    @Test
    void aFreshNodeBootstrapsFromAPeersSnapshotAndAgreesWithIt() throws Exception {
        try (TestNetwork network = new TestNetwork(tempDir)) {
            RhizomeNode archive = network.node("archive")
                .params(SHALLOW).mining().blockInterval(60).snapshotEvery(5).start();

            // Mine well past the finality window so a materialised pivot is buried and acceptable.
            TestNetwork.awaitHeight(archive, 30);
            assertTrue(archive.service().materializeSnapshot(),
                "the archive node could not materialise a state snapshot to serve");
            TestNetwork.await(() -> archive.service().snapshotPivot() > 1,
                () -> "no snapshot pivot was ever advertised");
            long pivot = archive.service().snapshotPivot();
            TestNetwork.awaitHeight(archive, pivot + SHALLOW.maxReorgDepth() + 2);

            RhizomeNode fresh = network.node("fresh")
                .params(SHALLOW).snapSync().peers(TestNetwork.urlOf(archive)).start();

            TestNetwork.syncUntil(fresh, () -> fresh.engine().height() > pivot);

            assertTrue(fresh.engine().height() > pivot,
                "the fresh node never reached the snapshot pivot");
            // A bootstrapped node deliberately holds no historical BODIES below its pivot — that is
            // the point of snap-sync — so agreement is checked on the headers it does keep.
            assertEquals(archive.engine().headerAt(pivot).hash(), fresh.engine().headerAt(pivot).hash(),
                "the bootstrapped node disagrees with its source about the pivot block");
            assertFalse(fresh.engine().isDegraded(),
                "the bootstrap left the node degraded");
            assertEquals(200, RawHttp.get(fresh.apiPort(), "/block_count", Map.of()).status(),
                "a bootstrapped node must be able to serve its own chain");
        }
    }
}
