package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theNoTokenPolicyNeverAuthenticatesAnything() {
        var policy = PeerTokenPolicy.none();
        assertNull(policy.tokenFor("https://peer1.example:3000"));
        assertNull(policy.tokenFor("http://peer1.example:3000"));
        assertNull(policy.tokenFor(null));
    }

    @Test
    void thePublicApiOffersNoTrustAllPolicy() {
        // The deprecated String-peerToken constructors of HttpPeerSource/PeerBroadcaster/
        // PeerDiscovery delegated to a trust-all policy that handed the shared secret to every
        // gossip-learned peer in cleartext. They are gone, and so is the public factory behind
        // them: this fails the moment either shape reappears (audit B-2).
        for (java.lang.reflect.Method m : PeerTokenPolicy.class.getMethods()) {
            assertFalse(m.getName().toLowerCase(java.util.Locale.ROOT).contains("trustall"),
                "public trust-all factory reintroduced: " + m);
        }
        for (Class<?> wired : java.util.List.of(
                HttpPeerSource.class, PeerBroadcaster.class, PeerDiscovery.class)) {
            for (var c : wired.getConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                // The removed shape: a trailing String peerToken tacked onto an existing
                // constructor. (The 2-arg PeerDiscovery(registry, selfUrl) is not it.)
                assertFalse(params.length >= 3 && params[params.length - 1] == String.class,
                    "a trailing String token parameter is the removed trust-all shape: " + c);
            }
        }
    }
}
