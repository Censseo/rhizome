package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The RHIZOME_PEER_TOKEN shared secret must never leak to a peer the operator did not
 * explicitly configure, and never travel in cleartext over http (audit: peer token
 * exfiltration via gossip — the registry is fed by unauthenticated /add_peer + PEX).
 */
class PeerTokenPolicyTest {

    private static final String TOKEN = "s3cret";

    @Test
    void configuredHttpsPeerReceivesTheToken() {
        var policy = new PeerTokenPolicy(TOKEN, List.of("https://peer1.example:3000", "http://peer2.example:3000"));
        assertEquals(TOKEN, policy.tokenFor("https://peer1.example:3000"));
    }

    @Test
    void gossipLearnedPeerNeverReceivesTheToken() {
        // A peer that got itself added via /add_peer or PEX is NOT in config.peers() — even over
        // https it must not see the secret.
        var policy = new PeerTokenPolicy(TOKEN, List.of("https://peer1.example:3000"));
        assertNull(policy.tokenFor("https://attacker.example:3000"));
        assertNull(policy.tokenFor("https://peer1.example:3001")); // different port: different peer
    }

    @Test
    void configuredHttpPeerNeverReceivesTheToken() {
        // The secret must never cross the wire in cleartext, even to a configured peer.
        var policy = new PeerTokenPolicy(TOKEN, List.of("http://peer2.example:3000"));
        assertNull(policy.tokenFor("http://peer2.example:3000"));
    }

    @Test
    void membershipIsCheckedOnTheCanonicalUrl() {
        var policy = new PeerTokenPolicy(TOKEN, List.of("https://Example.COM:443/"));
        assertEquals(TOKEN, policy.tokenFor("https://example.com"));
        assertEquals(TOKEN, policy.tokenFor("HTTPS://example.com.:443/"));
    }

    @Test
    void noTokenConfiguredMeansNoTokenEver() {
        var policy = new PeerTokenPolicy(null, List.of("https://peer1.example:3000"));
        assertNull(policy.tokenFor("https://peer1.example:3000"));
        assertNull(policy.tokenFor(null));
    }

    @Test
    void trustAllReproducesTheLegacyBehaviour() {
        // Tests/legacy only: the deprecated String peerToken constructors presented the token to
        // every peer over any scheme — insecure on a gossip-fed registry, preserved for
        // backward compatibility behind @Deprecated.
        var policy = PeerTokenPolicy.trustAll(TOKEN);
        assertEquals(TOKEN, policy.tokenFor("http://anything.example:3000"));
        assertEquals(TOKEN, policy.tokenFor("https://unconfigured.example"));
        assertNull(PeerTokenPolicy.trustAll(null).tokenFor("https://unconfigured.example"));
    }
}
