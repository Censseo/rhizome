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
}
