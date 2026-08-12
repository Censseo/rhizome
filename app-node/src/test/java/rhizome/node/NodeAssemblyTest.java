package rhizome.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembling a node without starting one.
 *
 * <p>Everything asserted here used to require a listening socket, because the whole graph was
 * built inside {@code start()}: the exposure refusal (audit H-2), the Host-allowlist discovery
 * (audit F9) and the store lifecycle could only be reached by a test willing to take a port, and
 * two postures could not be compared in one JVM at all — which is why none of them were covered.
 * Splitting {@code assemble()} out is what turns them into plain calls; the third test below is
 * the one that says so out loud, by assembling twice on the same port.
 */
class NodeAssemblyTest {

    @TempDir
    Path tempDir;

    /** Cheap PoW: nothing here mines, but genesis still has to be accepted. */
    private static final NetworkParameters FAST = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).maxDifficulty(16).build();

    private NodeConfig config(String dir) {
        return NodeConfig.defaults(FAST, tempDir.resolve(dir).toString(), 3000)
            .withAdvertisedUrl("http://localhost:3000");
    }

    @Test
    void anOpenBindWithoutATokenIsRefusedBeforeAnythingIsOpened() throws IOException {
        // The secure-by-default gate: binding a non-loopback address with no API token leaves the
        // state-changing routes open to the network. It must refuse, and it must refuse BEFORE
        // opening a store — otherwise the refusal leaves a locked data directory behind.
        NodeConfig exposed = config("exposed").toBuilder().bindAddress("0.0.0.0").build();
        assertThrows(IllegalStateException.class, () -> RhizomeNode.assemble(exposed));
        assertFalse(Files.exists(tempDir.resolve("exposed")),
            "the refusal must precede the stores, or it leaves a RocksDB LOCK behind");

        // Either explicit opt-out makes the same posture legal: a token, or the relay override.
        try (NodeComponents withToken = RhizomeNode.assemble(
                exposed.toBuilder().apiToken(java.util.Optional.of("s3cret")).build())) {
            assertNotNull(withToken.engine());
        }
        try (NodeComponents relay = RhizomeNode.assemble(
                config("relay").toBuilder().bindAddress("0.0.0.0").allowOpenApi(true).build())) {
            assertNotNull(relay.engine());
        }
    }

    @Test
    void theHostAllowlistIsComputedAtAssemblyAndCarriesTheAdvertisedName() throws IOException {
        try (NodeComponents c = RhizomeNode.assemble(
                config("hosts").toBuilder().advertisedUrl(java.util.Optional.of("http://node.example:3000"))
                    .extraAllowedHosts(java.util.List.of("proxy.example")).build())) {
            assertTrue(c.allowedHosts().contains("node.example:3000"), "the advertised authority");
            assertTrue(c.allowedHosts().contains("localhost:3000"), "the loopback names");
            assertTrue(c.allowedHosts().contains("proxy.example"), "the operator's extra authority");
        }
        // "off" is a distinct posture, not an empty list of names.
        try (NodeComponents off = RhizomeNode.assemble(
                config("hosts-off").toBuilder().hostAllowlistEnabled(false).build())) {
            assertEquals(java.util.Set.of(), off.allowedHosts());
        }
    }

    @Test
    void assemblyBindsNoPortAndReleasesItsStores() throws IOException {
        // Two graphs on the SAME api port, alive at the same time: the port is a start() concern,
        // and nothing here reaches the network. Before the split this test could not be written.
        try (NodeComponents first = RhizomeNode.assemble(config("a"));
             NodeComponents second = RhizomeNode.assemble(config("b"))) {
            assertEquals(1, first.engine().height(), "the genesis block");
            assertEquals(1, second.engine().height(), "the genesis block");
        }
        // And closing really does release the RocksDB locks: re-assembling the same data
        // directory is the only proof that works, since the lock is held by the process.
        try (NodeComponents reopened = RhizomeNode.assemble(config("a"))) {
            assertEquals(1, reopened.engine().height(), "the genesis block");
        }
    }

    @Test
    void aFailedAssemblyReleasesTheStoresItHadAlreadyOpened() throws IOException {
        // The stores open one at a time, so a failure on the third leaves two holding their
        // RocksDB LOCK for the life of the process — and the next attempt then fails for a
        // reason that has nothing to do with the actual cause. Forced here by planting a regular
        // file where the contract store's directory belongs.
        Path dir = tempDir.resolve("partial");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("contracts"), "not a directory");

        NodeConfig config = config("partial");
        assertThrows(Exception.class, () -> RhizomeNode.assemble(config),
            "a store that cannot open must fail the assembly");

        // The proof: the main store, opened before the failure, was released. Without the unwind
        // this second attempt fails on the LOCK instead of on the planted file.
        Files.delete(dir.resolve("contracts"));
        assertDoesNotThrow(() -> {
            try (NodeComponents c = RhizomeNode.assemble(config)) {
                assertEquals(1, c.engine().height(), "the genesis block");
            }
        });
    }
}
