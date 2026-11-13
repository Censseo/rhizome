package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;

/**
 * NET-12 wiring: the pull-sync loop must feed transport failures into the registry's shared
 * consecutive-failure counter, so a peer that never answers sync is evicted after three rounds
 * instead of costing a round slot forever. {@code PeerFailureCounterTest} proves the counter; this
 * proves {@code SyncDriver} reports to it.
 */
class SyncDriverFailureEvictionTest {

    @TempDir
    Path tempDir;

    private static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).maxDifficulty(16).build();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void aPeerThatNeverAnswersSyncIsEvictedAfterThreeFailedRounds() throws Exception {
        int deadPort = freePort(); // nothing listens: connection refused
        String peer = "http://127.0.0.1:" + deadPort;
        NodeConfig config = NodeConfig.defaults(FAST, tempDir.resolve("n").toString(), freePort())
            .withAllowPrivatePeers(true);
        try (RhizomeNode node = new RhizomeNode(config)) {
            node.start();
            node.service().addPeer(peer); // admission runs off-loop
            long deadline = System.currentTimeMillis() + 10_000;
            while (!node.knownPeers().contains(peer) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(node.knownPeers().contains(peer), "peer admitted");

            node.syncRound();
            node.syncRound();
            assertTrue(node.knownPeers().contains(peer), "two consecutive failures do not evict");

            node.syncRound();
            assertFalse(node.knownPeers().contains(peer), "the third consecutive failure evicts the peer");
        }
    }
}
