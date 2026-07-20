package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.ledger.PublicAddress;

/**
 * Peer exchange: nodes that share a single seed discover each other and form a
 * full mesh, then a block mined by one reaches a node it only learned about
 * through discovery.
 */
class PeerDiscoveryTest {

    @TempDir
    Path tempDir;

    private static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).build();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private RhizomeNode node(String name, int port, List<String> seeds, boolean miner) {
        NodeConfig config = NodeConfig.defaults(FAST, tempDir.resolve(name).toString(), port)
            .withPeers(seeds)
            .withAdvertisedUrl("http://localhost:" + port);
        if (miner) {
            config = config.withMiner(PublicAddress.random()).withBlockIntervalMs(50);
        }
        return new RhizomeNode(config);
    }

    @Test
    void nodesDiscoverEachOtherFromOneSeed() throws Exception {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        String urlA = "http://localhost:" + portA;
        String urlB = "http://localhost:" + portB;
        String urlC = "http://localhost:" + portC;

        // A is the miner seed; B and C only know A.
        try (RhizomeNode a = node("a", portA, List.of(), true);
             RhizomeNode b = node("b", portB, List.of(urlA), false);
             RhizomeNode c = node("c", portC, List.of(urlA), false)) {
            a.start();
            b.start();
            c.start();

            // A few PEX rounds propagate the membership through the seed.
            for (int i = 0; i < 3; i++) {
                b.discoverRound();
                c.discoverRound();
                a.discoverRound();
            }

            assertTrue(a.knownPeers().containsAll(List.of(urlB, urlC)), "A should learn B and C");
            assertTrue(b.knownPeers().containsAll(List.of(urlA, urlC)), "B should learn A and C");
            assertTrue(c.knownPeers().containsAll(List.of(urlA, urlB)), "C should learn A and B");

            // A now knows C (learned via discovery) and gossips its blocks there.
            long deadline = System.currentTimeMillis() + 25_000;
            while (c.engine().height() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(30);
            }
            assertTrue(c.engine().height() >= 4, "mined blocks should reach the discovered node C");
        }
    }
}
