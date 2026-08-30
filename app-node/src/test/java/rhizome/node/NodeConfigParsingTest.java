package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Environment config parsing guards (audit: unvalidated config): numeric env vars must fail
 * fast with a clear message, and the miner vote must be bounded to the protocol's vote domain,
 * instead of dying with a raw NumberFormatException or minting INVALID_VOTE blocks.
 */
class NodeConfigParsingTest {

    @Test
    void voteParsesInsideTheProtocolDomain() {
        assertEquals(0, NodeConfig.parseVote("0"));
        assertEquals(1, NodeConfig.parseVote(" 1 "));
        assertEquals(-2, NodeConfig.parseVote("-2"));
        assertEquals(2, NodeConfig.parseVote("2"));
    }

    @Test
    void voteRejectsJunkAndOutOfDomainValues() {
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseVote("yes"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseVote("3"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseVote("-3"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseVote("99999999999"));
    }

    @Test
    void portParsesAndIsRangeChecked() {
        assertEquals(3000, NodeConfig.parsePort("3000"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parsePort("abc"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parsePort("0"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parsePort("70000"));
    }

    @Test
    void blockIntervalParsesAndMustBePositive() {
        assertEquals(1000L, NodeConfig.parseBlockIntervalMs("1000"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseBlockIntervalMs("fast"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseBlockIntervalMs("0"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseBlockIntervalMs("-100"));
    }

    @Test
    void networkNamesResolveAndATypoIsRefused() {
        // A typo used to fall through to MAINNET in silence: RHIZOME_NETWORK=testnett mined the
        // wrong chain and dialled mainnet peers (audit B-4). Absent/blank still means mainnet —
        // that is a documented default, not a misread value.
        assertEquals(rhizome.core.blockchain.NetworkParameters.cleanMainnet().chainId(),
            NodeConfig.parseNetwork(null).chainId());
        assertEquals(rhizome.core.blockchain.NetworkParameters.cleanMainnet().chainId(),
            NodeConfig.parseNetwork("  ").chainId());
        assertEquals(rhizome.core.blockchain.NetworkParameters.testnet().chainId(),
            NodeConfig.parseNetwork(" TestNet ").chainId());
        assertEquals(rhizome.core.blockchain.NetworkParameters.devnet().chainId(),
            NodeConfig.parseNetwork("devnet").chainId());
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseNetwork("testnett"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseNetwork("main"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseNetwork("regtest"));
    }

    @Test
    void advertisedUrlMustBeAnHttpUrlWithAHost() {
        // A malformed RHIZOME_ADVERTISE was accepted verbatim and then silently degraded the
        // self-pairing refusal, PEX and the Host allowlist (audit I-6).
        assertEquals("https://node.example:3000", NodeConfig.parseAdvertisedUrl(" https://node.example:3000 "));
        assertEquals("http://10.0.0.5:3000", NodeConfig.parseAdvertisedUrl("http://10.0.0.5:3000"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseAdvertisedUrl("node.example:3000"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseAdvertisedUrl("ftp://node.example"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseAdvertisedUrl("http://"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parseAdvertisedUrl("http://[oops"));
    }

    @Test
    void stateRetentionMustCoverTheReorgDepth() {
        // retainDepth < maxReorgDepth: a reorg past the retained roots could not rebuild the
        // state tree — fail fast at the wiring site (audit: retainDepth below maxReorgDepth).
        RhizomeNode.checkStateRetention(64, 64);
        RhizomeNode.checkStateRetention(128, 64);
        assertThrows(IllegalStateException.class, () -> RhizomeNode.checkStateRetention(63, 64));
    }

    @Test
    void theActivationHeightIsNotReachableFromConfiguration() {
        // 006-emission-fork-activation FR-001, §Security "configuration as an attack surface":
        // emissionCurveHeight is a consensus constant per network, never environment-configurable.
        // Sweep every RHIZOME_* variable NodeConfig.fromEnv reads and confirm none of them moves it.
        String minerAddress = "00" + "DD".repeat(24); // 25-byte PublicAddress literal, test-only

        java.util.Map<String, String> everyOtherVar = new java.util.HashMap<>();
        everyOtherVar.put("RHIZOME_DATA", "/tmp/rhizome-config-sweep");
        everyOtherVar.put("RHIZOME_PORT", "4000");
        everyOtherVar.put("RHIZOME_SNAPSHOT", "/tmp/does-not-need-to-exist-for-parsing.json");
        everyOtherVar.put("RHIZOME_MINER", minerAddress);
        everyOtherVar.put("RHIZOME_BLOCK_INTERVAL_MS", "2000");
        everyOtherVar.put("RHIZOME_PEERS", "https://peer-a.example,https://peer-b.example");
        everyOtherVar.put("RHIZOME_ADVERTISE", "https://self.example:4000");
        everyOtherVar.put("RHIZOME_BIND_ADDRESS", "0.0.0.0");
        everyOtherVar.put("RHIZOME_API_TOKEN", "sweep-token");
        everyOtherVar.put("RHIZOME_PEER_TOKEN", "sweep-peer-token");
        everyOtherVar.put("RHIZOME_PRUNE", "1000");
        everyOtherVar.put("RHIZOME_ALLOW_OPEN_API", "true");
        everyOtherVar.put("RHIZOME_ALLOW_PRIVATE_PEERS", "true");
        everyOtherVar.put("RHIZOME_SYNC", "snap");
        everyOtherVar.put("RHIZOME_PROTECT_READS", "true");
        everyOtherVar.put("RHIZOME_TRUST_XFF", "true");
        everyOtherVar.put("RHIZOME_VOTE", "1");
        everyOtherVar.put("RHIZOME_SNAPSHOT_EVERY", "500");
        everyOtherVar.put("RHIZOME_ALLOWED_HOSTS", "self.example");

        java.util.Map<String, rhizome.core.blockchain.NetworkParameters> networks = java.util.Map.of(
            "", rhizome.core.blockchain.NetworkParameters.cleanMainnet(),
            "testnet", rhizome.core.blockchain.NetworkParameters.testnet(),
            "devnet", rhizome.core.blockchain.NetworkParameters.devnet());

        for (var entry : networks.entrySet()) {
            String networkName = entry.getKey();
            long expectedHeight = entry.getValue().emissionCurveHeight();

            java.util.Map<String, String> minimal = new java.util.HashMap<>();
            minimal.put("RHIZOME_NETWORK", networkName);
            long minimalHeight = NodeConfig.fromEnv(minimal::get).params().emissionCurveHeight();

            java.util.Map<String, String> everything = new java.util.HashMap<>(everyOtherVar);
            everything.put("RHIZOME_NETWORK", networkName);
            long fullHeight = NodeConfig.fromEnv(everything::get).params().emissionCurveHeight();

            assertEquals(expectedHeight, minimalHeight,
                "network=" + networkName + " must ship its own emissionCurveHeight unmodified");
            assertEquals(expectedHeight, fullHeight,
                "network=" + networkName + ": setting every other RHIZOME_* variable must not move "
                    + "emissionCurveHeight");
        }
    }
}
