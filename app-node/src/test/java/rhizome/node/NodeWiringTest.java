package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.crypto.PowAlgorithm;

/**
 * Wiring proofs for {@link RhizomeNode}: the unit tests prove {@code NodeApi} and
 * {@code PeerBroadcaster} given the right collaborators; these prove the node hands them over.
 * The sync/discovery schedule is pushed out to ten minutes so only the path under test runs.
 */
class NodeWiringTest {

    @TempDir
    Path tempDir;

    private static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).maxDifficulty(16).build();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private NodeConfig quietConfig(String dir) throws Exception {
        return NodeConfig.defaults(FAST, tempDir.resolve(dir).toString(), freePort())
            .withAllowPrivatePeers(true).toBuilder().syncPeriodMs(600_000L).build();
    }

    private static boolean await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /** API-18 — RhizomeNode routes /submit to its dedicated ingest pool, not the shared one or inline. */
    @Test
    void submitRunsOnTheDedicatedIngestPool() throws Exception {
        try (RhizomeNode node = new RhizomeNode(quietConfig("n"))) {
            node.start();
            var ingest = (ThreadPoolExecutor) node.runtime().ingestWorkers();
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + node.apiPort() + "/submit"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1})).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, r.statusCode(), "junk block is decoded and refused");
            assertTrue(await(() -> ingest.getCompletedTaskCount() >= 1, 5_000),
                "the /submit handler ran on the ingest pool");
        }
    }

    /** NET-12 — RhizomeNode wires gossip outcomes into the registry: a dead peer is evicted by gossip alone. */
    @Test
    void gossipFailuresAloneEvictADeadPeer() throws Exception {
        String peer = "http://127.0.0.1:" + freePort(); // nothing listens: connection refused
        NodeConfig config = quietConfig("n").withMiner(PublicAddress.random()).withBlockIntervalMs(100);
        try (RhizomeNode node = new RhizomeNode(config)) {
            node.start();
            node.service().addPeer(peer);
            assertTrue(await(() -> node.knownPeers().contains(peer), 10_000), "peer admitted");
            // No sync or discovery round runs (ten-minute period); each mined block is gossiped.
            assertTrue(await(() -> !node.knownPeers().contains(peer), 30_000),
                "three failed gossip sends evict the peer");
        }
    }
}
