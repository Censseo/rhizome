package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // Instant-mining profile. maxDifficulty is capped low so mining stays feasible even after
    // the retarget: with a 50 ms cadence far under the 90 s target, difficulty legitimately
    // rises (the genesis-timestamp fix, audit L2, no longer masks that), and an uncapped
    // testnet ceiling would let it climb until the miner starves the node's I/O.
    private static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).maxDifficulty(16).build();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** A node with the SSRF filter at its secure default (no allowPrivatePeers opt-in). */
    private RhizomeNode nodeStrict(String name, int port, List<String> seeds) {
        return new RhizomeNode(NodeConfig.defaults(FAST, tempDir.resolve(name).toString(), port)
            .withPeers(seeds).withAdvertisedUrl("http://localhost:" + port));
    }

    @Test
    void loopbackPeersAreNotDiscoveredByDefault() throws Exception {
        // Secure-by-default (audit F4): without the allowPrivatePeers opt-in the SSRF host filter is
        // on, so the loopback PEX mesh that nodesDiscoverEachOtherFromOneSeed forms WITH the opt-in
        // does not form here. Before the fix (filter derived from a loopback selfUrl → off), A would
        // have learned B; now it must not.
        int portA = freePort();
        int portB = freePort();
        String urlA = "http://localhost:" + portA;
        String urlB = "http://localhost:" + portB;
        try (RhizomeNode a = nodeStrict("as", portA, List.of());
             RhizomeNode b = nodeStrict("bs", portB, List.of(urlA))) {
            a.start();
            b.start();
            for (int i = 0; i < 3; i++) {
                b.discoverRound();
                a.discoverRound();
            }
            assertFalse(a.knownPeers().contains(urlB), "A must not learn B's loopback URL by default");
        }
    }

    private RhizomeNode node(String name, int port, List<String> seeds, boolean miner) {
        NodeConfig config = NodeConfig.defaults(FAST, tempDir.resolve(name).toString(), port)
            .withPeers(seeds)
            .withAdvertisedUrl("http://localhost:" + port)
            .withAllowPrivatePeers(true); // local dev: PEX-discover peers over loopback (audit F4)
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
