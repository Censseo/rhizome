package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.ledger.PublicAddress;

/** Assembles real nodes: one mines and serves its API; a second syncs from it. */
class RhizomeNodeTest {

    @TempDir
    Path tempDir;

    private static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).build();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void awaitHeight(rhizome.node.RhizomeNode node, long target, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (node.engine().height() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void miningNodeProducesAndServesApi() throws Exception {
        int port = freePort();
        NodeConfig config = NodeConfig.defaults(FAST, tempDir.resolve("miner").toString(), port)
            .withMiner(PublicAddress.random()).withBlockIntervalMs(50);

        try (RhizomeNode node = new RhizomeNode(config)) {
            node.start();
            awaitHeight(node, 4, 10_000);
            assertTrue(node.engine().height() >= 4, "node should mine blocks");

            // Served over HTTP.
            var peer = new HttpPeerSource("http://localhost:" + port);
            assertTrue(peer.height() >= 4);
            assertEquals(node.engine().tipHash(), peer.blockHash(peer.height()));
        }
    }

    @Test
    void secondNodeSyncsFromFirst() throws Exception {
        int portA = freePort();
        NodeConfig configA = NodeConfig.defaults(FAST, tempDir.resolve("a").toString(), portA)
            .withMiner(PublicAddress.random()).withBlockIntervalMs(50);

        try (RhizomeNode nodeA = new RhizomeNode(configA)) {
            nodeA.start();
            awaitHeight(nodeA, 5, 10_000);

            int portB = freePort();
            NodeConfig configB = NodeConfig.defaults(FAST, tempDir.resolve("b").toString(), portB)
                .withPeers(java.util.List.of("http://localhost:" + portA));

            try (RhizomeNode nodeB = new RhizomeNode(configB)) {
                nodeB.start();
                assertEquals(1, nodeB.engine().height()); // only genesis

                nodeB.syncRound(); // pull from A

                assertTrue(nodeB.engine().height() >= 5, "B should catch up to A");
                assertEquals(nodeA.engine().blockAt(nodeB.engine().height()).hash(),
                    nodeB.engine().tipHash());
            }
        }
    }
}
