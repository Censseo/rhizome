package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.blockchain.GenesisBlock;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.SnapshotLoader;

/**
 * Pins the staging network's published genesis identity (chantier 0, phase 2) the same way
 * {@code GenesisBlockTest#thePublishedAllocationRecomputesToTheGenesisIdentity} pins mainnet's:
 * once a network's genesis is published, {@code GenesisBlock.build} over its shipped snapshot
 * MUST always reproduce byte-identical output, forever -- a change here means the profile
 * silently re-genesis'd, which would orphan every existing staging node's data directory.
 */
class StagingGenesisTest {

    /**
     * The staging network's published genesis hash, computed once (see class javadoc) from
     * {@code NetworkParameters.staging()} and the shipped {@code genesis/rhizome-staging.json}
     * allocation, then pinned here as a constant. LOAD-BEARING: changing the staging genesis
     * artifact or any consensus constant staging() derives from cleanMainnet() (before this
     * profile's real public launch) changes this hash and requires re-pinning it deliberately,
     * not accidentally.
     */
    private static final String STAGING_GENESIS_HASH =
        "8CB7AD090F912D3C051B1C3FBB3FF187918843DF06301339D84C91305DF46590";

    @Test
    void stagingGenesisHashIsPinned() throws IOException {
        NetworkParameters staging = NetworkParameters.staging();
        LedgerSnapshot snapshot = SnapshotLoader.forBoot(Optional.empty(), staging);

        Block genesis = GenesisBlock.build(staging, snapshot);

        assertEquals(STAGING_GENESIS_HASH, genesis.hash().toHexString());
    }

    @Test
    void stagingGenesisHashDiffersFromMainnets() throws IOException {
        NetworkParameters mainnet = NetworkParameters.cleanMainnet();
        LedgerSnapshot mainnetSnapshot = SnapshotLoader.fromResource("genesis/rhizome-mainnet.json");
        Block mainnetGenesis = GenesisBlock.build(mainnet, mainnetSnapshot);

        NetworkParameters staging = NetworkParameters.staging();
        LedgerSnapshot stagingSnapshot = SnapshotLoader.forBoot(Optional.empty(), staging);
        Block stagingGenesis = GenesisBlock.build(staging, stagingSnapshot);

        assertNotEquals(mainnetGenesis.hash(), stagingGenesis.hash(),
            "staging's own chainId, difficulty floor and allocation must yield a distinct "
                + "genesis identity from mainnet's, even though both share the same pinned "
                + "genesisSupply");
    }

    /**
     * GraalVM native-image build regression guard: the reachability metadata's glob covering
     * {@code genesis/*.json} is what lets {@code SnapshotLoader.fromResource} find ANY shipped
     * network's allocation artifact -- including staging's -- inside a native binary. A future
     * edit that tightens or removes that glob would silently drop staging's (and any other
     * profile's) genesis resource from the native image rather than failing the build, so this
     * test reads the checked-in metadata file directly and asserts the glob is still there.
     */
    @Test
    void nativeImageReachabilityMetadataStillCoversGenesisResources() throws IOException {
        Path metadataPath = repoRoot().resolve(
            "app-node/src/main/resources/META-INF/native-image/rhizome/rhizome-node/"
                + "reachability-metadata.json");
        assertTrue(Files.exists(metadataPath),
            "reachability-metadata.json not found at " + metadataPath);

        String content = Files.readString(metadataPath);
        assertTrue(content.contains("genesis/*.json"),
            "expected the reachability metadata to still declare the \"genesis/*.json\" glob, "
                + "so every shipped network's genesis allocation (including staging's) remains "
                + "reachable from a GraalVM native image");
    }

    /**
     * Resolves the repository root by walking up from the working directory looking for
     * {@code settings.gradle} -- the same technique
     * {@code rhizome.adversarial.AdversarialProtocolTest#repoRoot} uses to reach a sibling
     * module's files (app-node's, here) without depending on a build-generated absolute path.
     */
    private static Path repoRoot() {
        Path directory = Path.of("").toAbsolutePath();
        for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no settings.gradle above " + directory);
    }
}
