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

/**
 * Active gossip: a mining node pushes its blocks to a peer, so the peer keeps up
 * without ever pulling. Distinct from periodic sync (which the receiver here does
 * not run).
 */
class GossipPropagationTest {

    @TempDir
    Path tempDir;

    private static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).build();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void minerPushesBlocksToPeer() throws Exception {
        int portReceiver = freePort();

        // Receiver: no miner, no peers -> it never pulls; it can only advance via
        // blocks pushed to its /submit endpoint.
        NodeConfig receiverConfig = NodeConfig.defaults(FAST, tempDir.resolve("recv").toString(), portReceiver);

        try (RhizomeNode receiver = new RhizomeNode(receiverConfig)) {
            receiver.start();
            assertEquals(1, receiver.engine().height());

            int portMiner = freePort();
            NodeConfig minerConfig = NodeConfig.defaults(FAST, tempDir.resolve("miner").toString(), portMiner)
                .withMiner(PublicAddress.random())
                .withBlockIntervalMs(50)
                .withPeers(java.util.List.of("http://localhost:" + portReceiver));

            try (RhizomeNode miner = new RhizomeNode(minerConfig)) {
                miner.start();

                long deadline = System.currentTimeMillis() + 25_000;
                while (receiver.engine().height() < 5 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(30);
                }

                assertTrue(receiver.engine().height() >= 5, "receiver should get pushed blocks");
                // The receiver's chain matches the miner's up to the height it received.
                long h = receiver.engine().height();
                assertEquals(miner.engine().blockAt(h).hash(), receiver.engine().tipHash());
            }
        }
    }
}
