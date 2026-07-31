package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression guards for the P2P hardening: SSRF host classification (H3), IP-subnet
 * bucketing and seed protection against eclipse (H4).
 */
class PeerRegistrySecurityTest {

    @Test
    void ssrfClassifierRejectsInternalAndMetadataHosts() {
        // Not routable: loopback, RFC1918, link-local + cloud metadata, CGNAT, ULA, IPv6 loopback.
        assertFalse(PeerRegistry.isPubliclyRoutable("127.0.0.1"));
        assertFalse(PeerRegistry.isPubliclyRoutable("10.1.2.3"));
        assertFalse(PeerRegistry.isPubliclyRoutable("192.168.0.1"));
        assertFalse(PeerRegistry.isPubliclyRoutable("172.16.5.5"));
        assertFalse(PeerRegistry.isPubliclyRoutable("169.254.169.254"));
        assertFalse(PeerRegistry.isPubliclyRoutable("100.64.0.1"));
        assertFalse(PeerRegistry.isPubliclyRoutable("::1"));
        assertFalse(PeerRegistry.isPubliclyRoutable("fc00::1"));
        assertFalse(PeerRegistry.isPubliclyRoutable(""));
        // Routable public unicast.
        assertTrue(PeerRegistry.isPubliclyRoutable("93.184.216.34"));
        assertTrue(PeerRegistry.isPubliclyRoutable("2001:4860:4860::8888"));
    }

    @Test
    void ipv6TransitionTunnelsAreRejected() {
        // 6to4 (2002::/16) and Teredo (2001:0000::/32) embed an inner IPv4 address that would
        // bypass the v4-private SSRF filter (audit F10); both are refused outright.
        assertFalse(PeerRegistry.isPubliclyRoutable("2002:0808:0808::1"));      // 6to4 of 8.8.8.8
        assertFalse(PeerRegistry.isPubliclyRoutable("2002:0a00:0001::1"));      // 6to4 of 10.0.0.1
        assertFalse(PeerRegistry.isPubliclyRoutable("2001:0:0808:0808::1"));    // Teredo
        // Ordinary global v6 just outside those prefixes is unaffected.
        assertTrue(PeerRegistry.isPubliclyRoutable("2001:4860:4860::8888"));
        assertTrue(PeerRegistry.isPubliclyRoutable("2003:dead:beef::1"));
    }

    @Test
    void removalCooldownBlocksImmediateReadd() {
        // A peer removed as failed/evicted cannot flap straight back into the table via PEX;
        // after the cooldown window it is eligible again (audit F5).
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0);
        var reg = new PeerRegistry("http://self:3000", 500, null, false, clock::get);
        assertTrue(reg.add("http://93.184.216.34:3000"));
        reg.remove("http://93.184.216.34:3000");
        assertEquals(0, reg.size());
        assertFalse(reg.add("http://93.184.216.34:3000"), "just-removed peer must cool down");
        clock.set(6 * 60_000L); // past the 5-minute cooldown
        assertTrue(reg.add("http://93.184.216.34:3000"), "cooldown lapsed: eligible again");
    }

    @Test
    void removalCooldownIsKeyedByHostNotFullUrl() {
        // The cooldown used to key on the full canonical URL, so a removed peer dodged it by
        // rotating its port or path. It is now keyed by host, like the ban list (audit follow-up).
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0);
        var reg = new PeerRegistry("http://self:3000", 500, null, false, clock::get);
        assertTrue(reg.add("http://93.184.216.34:3000"));
        reg.remove("http://93.184.216.34:3000");
        assertFalse(reg.add("http://93.184.216.34:4000"), "port rotation must not dodge the cooldown");
        assertFalse(reg.add("http://93.184.216.34:3000/node"), "path rotation must not dodge the cooldown");
        clock.set(6 * 60_000L); // past the 5-minute cooldown
        assertTrue(reg.add("http://93.184.216.34:4000"), "cooldown lapsed: eligible again");
    }

    @Test
    void removalFreesTheAdmissionRecordedSubnetSlot() {
        // The bucket decrement at removal uses the key recorded at admission (never a fresh DNS
        // lookup), so the slot is reliably freed and a replacement can be admitted (audit F5).
        var reg = new PeerRegistry("http://self:3000", 500, null, false);
        for (int i = 0; i < 16; i++) {
            assertTrue(reg.add("http://93.184.216.34:" + (4000 + i)));
        }
        assertFalse(reg.add("http://93.184.216.34:5000"), "bucket full");
        reg.remove("http://93.184.216.34:4000");
        // The freed slot goes to a DIFFERENT host in the same /16: the removed host itself is
        // under the (now host-keyed) removal cooldown — port rotation no longer dodges it.
        assertFalse(reg.add("http://93.184.216.34:5000"), "same host cools down (host-keyed)");
        assertTrue(reg.add("http://93.184.0.1:5000"), "removal must free the bucket slot");
    }

    @Test
    void publicSnapshotServesTheAdmissionVerdict() {
        // On mainnet (blockPrivateHosts) publicSnapshot must serve the routability verdict
        // snapshotted at admission — and never resolve DNS on the request path (audit F3).
        var reg = new PeerRegistry("http://self:3000", 100, null, true);
        assertTrue(reg.add("http://93.184.216.34:3000"));
        assertEquals(java.util.List.of("http://93.184.216.34:3000"), reg.publicSnapshot());
    }

    @Test
    void blockPrivateHostsRefusesInternalPeersWhenEnabled() {
        var strict = new PeerRegistry("http://self:3000", 100, null, true);
        assertFalse(strict.add("http://169.254.169.254/latest/meta-data"));
        assertFalse(strict.add("http://127.0.0.1:8080"));
        assertFalse(strict.add("http://10.0.0.5:3000"));
        assertTrue(strict.add("http://93.184.216.34:3000"));
    }

    @Test
    void subnetBucketCapsDiscoveredPeersFromOneSubnet() {
        var reg = new PeerRegistry("http://self:3000", 500, null, false);
        // 40 distinct ports, all in the same /16 (93.184.x): the per-subnet cap must limit them.
        for (int i = 0; i < 40; i++) {
            reg.add("http://93.184.216.34:" + (4000 + i));
        }
        assertEquals(16, reg.size(), "one /16 subnet must not fill the table");
    }

    @Test
    void seedsAreExemptFromSubnetCapAndProtected() {
        var reg = new PeerRegistry("http://self:3000", 500, null, false);
        java.util.List<String> seeds = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            seeds.add("http://10.0.0.1:" + (5000 + i)); // private + same subnet: fine for trusted seeds
        }
        reg.addSeeds(seeds);
        assertEquals(40, reg.size(), "seeds are trusted: exempt from SSRF and subnet caps");
    }

    @Test
    void rejectsMalformedSchemes() {
        var reg = new PeerRegistry("http://self:3000", 100, null, false);
        assertFalse(reg.add("httpfoo://8.8.8.8"));
        assertFalse(reg.add("ftp://8.8.8.8"));
        assertFalse(reg.add("not a url"));
    }

    @Test
    void urlVariantsCoalesceIntoOneEntry() {
        // Case / trailing-dot / default-port / trailing-slash variants of ONE peer URL must
        // dedup to a single registry slot, or a peer squats several (audit: /add_peer
        // coalescing bypass).
        var reg = new PeerRegistry("http://self:3000", 100, null, false);
        assertTrue(reg.add("http://93.184.216.34:3000"));
        assertFalse(reg.add("http://93.184.216.34:3000/"));
        assertEquals(1, reg.size());
        var named = new PeerRegistry("http://self:3000", 100, null, false);
        assertTrue(named.add("http://peer.example.com:80"));
        assertFalse(named.add("HTTP://PEER.EXAMPLE.COM./"));
        assertEquals(1, named.size());
    }

    @Test
    void confirmationTracksOnlyPeersThatSpokeTheProtocol() {
        // A peer only becomes ban-eligible once it has answered a well-formed exchange: on an
        // open node anyone can /add_peer an arbitrary public host, and junk from a host that
        // never spoke the protocol must not blocklist its IP (audit B-3).
        var reg = new PeerRegistry("http://self:3000", 100, null, false);
        reg.addSeeds(java.util.List.of("http://seed.example:3000"));
        assertTrue(reg.add("http://93.184.216.34:3000"));

        assertFalse(reg.isConfirmed("http://93.184.216.34:3000"), "freshly added, never talked to");
        assertTrue(reg.isConfirmed("http://seed.example:3000"), "the operator vouched for a seed");

        reg.markConfirmed("http://93.184.216.34:3000/"); // canonicalized, like every other lookup
        assertTrue(reg.isConfirmed("http://93.184.216.34:3000"));

        // An unknown URL cannot be confirmed into existence, and removal clears the flag so a
        // re-admitted host starts unconfirmed again.
        reg.markConfirmed("http://198.51.100.7:3000");
        assertFalse(reg.isConfirmed("http://198.51.100.7:3000"));
        reg.remove("http://93.184.216.34:3000");
        assertFalse(reg.isConfirmed("http://93.184.216.34:3000"));
    }

    @Test
    void selfUrlIsRecognizedInAllItsForms() {
        // The self-pairing refusal must see through the same variants, or PEX hands us back
        // our own advertised URL and we peer with ourselves (audit: self-pairing bypass).
        var reg = new PeerRegistry("http://93.184.216.34:3000/", 100, null, false);
        assertFalse(reg.add("http://93.184.216.34:3000"));
        assertFalse(reg.add("http://93.184.216.34:3000/"));
        assertEquals(0, reg.size());
        var named = new PeerRegistry("HTTP://Peer.Example.COM.:80", 100, null, false);
        assertFalse(named.add("http://peer.example.com"));
        assertFalse(named.add("http://peer.example.com:80/"));
        assertEquals(0, named.size());
    }
}
