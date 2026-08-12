package rhizome.node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every environment variable the node reads, its default, and how it rejects nonsense.
 *
 * <p>Ten of these settings were previously pulled straight from {@code System.getenv} deep inside
 * {@code start()}, {@code startHttp()}, {@code startProducerIfConfigured()} and
 * {@code startNetworkLoops()}. They never reached {@code NodeConfig}, so no test could start a node
 * with a prune depth, snap-sync, protectReads, trustXff, a vote or a peer token — setting them
 * required mutating the process environment, which JUnit does not do portably, so nothing did.
 * {@code NodeConfigParsingTest} covered five static parsers and never built a config at all;
 * {@code keepBlocks} and {@code snapshotEveryBlocks} had no coverage whatsoever.
 *
 * <p>Parsing against an accessor rather than the process environment turns all of it into a table.
 */
class NodeConfigFromEnvTest {

    /** An environment that is exactly the given map — anything else reads as unset. */
    private static UnaryOperator<String> env(String... pairs) {
        Map<String, String> values = new java.util.HashMap<>();
        values.put("RHIZOME_NETWORK", "testnet");
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values::get;
    }

    @Test
    void anEmptyEnvironmentYieldsTheDocumentedDefaults() {
        NodeConfig c = NodeConfig.fromEnv(env());

        assertEquals("./data", c.dataDir());
        assertEquals(3000, c.apiPort());
        assertEquals("127.0.0.1", c.bindAddress(), "secure by default: loopback (audit H-2)");
        assertEquals(0, c.keepBlocks(), "0 = archive");
        assertEquals(0, c.vote(), "absent = abstain");
        assertEquals(NodeConfig.DEFAULT_SNAPSHOT_EVERY, c.snapshotEveryBlocks());
        assertTrue(c.hostAllowlistEnabled(), "the rebinding allowlist is the default control");
        assertTrue(c.extraAllowedHosts().isEmpty());
        assertFalse(c.allowOpenApi());
        assertFalse(c.allowPrivatePeers());
        assertFalse(c.snapSync());
        assertFalse(c.protectReads());
        assertFalse(c.trustXff());
        assertTrue(c.apiToken().isEmpty());
        assertTrue(c.peerToken().isEmpty());
    }

    @Test
    void theBooleanSwitchesAreOnlyTrueForTheLiteralTrue() {
        // A typo must fail closed. "1", "yes" and "TRUE " are all distinguishable mistakes, and
        // only the last should turn a dangerous switch on.
        for (String value : new String[] {"1", "yes", "on", "", " "}) {
            NodeConfig c = NodeConfig.fromEnv(env(
                "RHIZOME_TRUST_XFF", value, "RHIZOME_ALLOW_OPEN_API", value,
                "RHIZOME_PROTECT_READS", value));
            assertFalse(c.trustXff(), "trustXff must not be enabled by '" + value + "'");
            assertFalse(c.allowOpenApi(), "allowOpenApi must not be enabled by '" + value + "'");
            assertFalse(c.protectReads(), "protectReads must not be enabled by '" + value + "'");
        }
        NodeConfig on = NodeConfig.fromEnv(env("RHIZOME_TRUST_XFF", " TRUE "));
        assertTrue(on.trustXff(), "trimmed and case-insensitive 'true' enables it");
    }

    @Test
    void thePruneDepthIsRefusedBelowTheSafeFloor() {
        // A prune depth under the deepest window the engine reads would drop a body it still
        // needs — mid-reorg, where the failure is unrecoverable. It must fail at boot instead.
        assertThrows(IllegalArgumentException.class,
            () -> NodeConfig.fromEnv(env("RHIZOME_PRUNE", "10")));
        assertThrows(IllegalArgumentException.class,
            () -> NodeConfig.fromEnv(env("RHIZOME_PRUNE", "not-a-number")));
        assertEquals(0, NodeConfig.fromEnv(env("RHIZOME_PRUNE", "0")).keepBlocks(), "0 = archive");
        assertEquals(0, NodeConfig.fromEnv(env("RHIZOME_PRUNE", "-5")).keepBlocks(), "negative = archive");
        assertEquals(100_000, NodeConfig.fromEnv(env("RHIZOME_PRUNE", "100000")).keepBlocks());
    }

    @Test
    void theVoteIsBoundedToTheProtocolDomain() {
        // Out of domain, the producer would mint blocks the consensus gate rejects as INVALID_VOTE.
        for (String bad : new String[] {"3", "-3", "99", "abc"}) {
            assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.fromEnv(env("RHIZOME_VOTE", bad)), "vote " + bad + " is out of domain");
        }
        assertEquals(2, NodeConfig.fromEnv(env("RHIZOME_VOTE", "2")).vote());
        assertEquals(-1, NodeConfig.fromEnv(env("RHIZOME_VOTE", "-1")).vote());
    }

    @Test
    void theHostAllowlistDistinguishesDisabledFromEmpty() {
        // "off" disables the DNS-rebinding control entirely; a list appends to the computed
        // defaults. One Optional<List> would blur the two, and the blurred case fails open.
        NodeConfig off = NodeConfig.fromEnv(env("RHIZOME_ALLOWED_HOSTS", " OFF "));
        assertFalse(off.hostAllowlistEnabled());

        NodeConfig listed = NodeConfig.fromEnv(env("RHIZOME_ALLOWED_HOSTS", "Node.Example.COM:443, , b.test"));
        assertTrue(listed.hostAllowlistEnabled());
        assertEquals(List.of("node.example.com:443", "b.test"), listed.extraAllowedHosts(),
            "entries are trimmed, lower-cased and blank-filtered at parse time");
    }

    @Test
    void thePortAndAdvertisedUrlAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.fromEnv(env("RHIZOME_PORT", "0")));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.fromEnv(env("RHIZOME_PORT", "70000")));
        assertThrows(IllegalArgumentException.class,
            () -> NodeConfig.fromEnv(env("RHIZOME_ADVERTISE", "ftp://nope")));
        assertEquals("http://node.example.com:3000",
            NodeConfig.fromEnv(env("RHIZOME_ADVERTISE", "http://node.example.com:3000")).advertisedUrl().orElseThrow());
    }

    @Test
    void anUnknownNetworkIsAHardFailure() {
        // A typo here silently puts the node on the wrong chain, so it must not fall back.
        assertThrows(IllegalArgumentException.class,
            () -> NodeConfig.fromEnv(k -> "RHIZOME_NETWORK".equals(k) ? "mainnett" : null));
    }

    @Test
    void peersAreSplitTrimmedAndBlankFiltered() {
        NodeConfig c = NodeConfig.fromEnv(env("RHIZOME_PEERS", " http://a:1 , ,http://b:2 "));
        assertEquals(List.of("http://a:1", "http://b:2"), c.peers());
    }

    @Test
    void snapSyncIsOnlyTheLiteralSnap() {
        assertTrue(NodeConfig.fromEnv(env("RHIZOME_SYNC", "SNAP")).snapSync());
        assertFalse(NodeConfig.fromEnv(env("RHIZOME_SYNC", "full")).snapSync());
        assertFalse(NodeConfig.fromEnv(env("RHIZOME_SYNC", "snapshot")).snapSync());
    }
}
