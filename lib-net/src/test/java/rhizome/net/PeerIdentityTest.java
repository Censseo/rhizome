package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * L26 verrou: a peer's identity is the CANONICAL form of its base URL, and every table
 * that keys on a peer keys on that identity — never on the received string. One peer must
 * occupy exactly one entry in the registry, the seed set and the confirmation set however
 * its URL was spelled at admission ({@code HTTP://Peer.Example.COM.:80/} and
 * {@code http://peer.example.com} are the same peer, audit: /add_peer coalescing &
 * self-pairing bypass), while two genuinely different endpoints stay distinct. Written
 * before the refactor that gives the peer a typed identity ({@code PeerId}) instead of a
 * raw {@code String} with five derivation sites (constat 26).
 */
class PeerIdentityTest {

    // ---- the identity type itself ----------------------------------------------------------

    @Test
    void oneIdentityPerCanonicalUrlWhateverTheReceivedSpelling() {
        // Case, trailing dot, default port and trailing slash are the same peer.
        PeerId canonical = PeerId.of("http://peer.example.com");
        assertEquals(canonical, PeerId.of("http://peer.example.com"));
        assertEquals(canonical, PeerId.of("HTTP://Peer.Example.COM.:80/"));
        assertEquals(canonical, PeerId.of("http://PEER.example.com:80//"));
        assertEquals(canonical.hashCode(), PeerId.of("HTTP://peer.example.com:80/").hashCode());
    }

    @Test
    void differentEndpointsAreDifferentIdentities() {
        assertNotEquals(PeerId.of("http://peer.example.com:3000"),
            PeerId.of("http://peer.example.com:3001"), "a different port is a different peer");
        assertNotEquals(PeerId.of("http://peer.example.com"),
            PeerId.of("https://peer.example.com"), "a different scheme is a different peer");
        assertNotEquals(PeerId.of("http://a.example.com"),
            PeerId.of("http://b.example.com"), "a different host is a different peer");
    }

    @Test
    void invalidUrlsHaveNoIdentity() {
        assertFalse(PeerId.of("not a url").isValid(), "an unparseable string is no identity");
        assertFalse(PeerId.of(null).isValid());
        assertFalse(PeerId.of("ftp://peer.example.com").isValid(), "non-http(s) schemes are refused");
        assertFalse(PeerId.of("").isValid());
    }

    // ---- the registry keys on the identity, not the received string ------------------------

    @Test
    void oneRegistryEntryPerCanonicalIdentity() {
        // The seeds set, the peer table and the confirmation set must each hold ONE entry for
        // a peer admitted under several spellings of one URL (audit: /add_peer coalescing).
        var reg = new PeerRegistry("http://self:3000", 100, null, false);
        assertTrue(reg.add("http://93.184.216.34:3000"));
        assertFalse(reg.add("HTTP://93.184.216.34.:3000/"),
            "a respelling of the same peer is not a new table slot");
        assertFalse(reg.add("http://93.184.216.34:3000//"),
            "a trailing-slash variant is not a new table slot");
        assertEquals(1, reg.size());

        reg.markConfirmed("http://93.184.216.34:3000/");
        assertTrue(reg.isConfirmed("http://93.184.216.34:3000"),
            "confirmation is tracked by identity, so any spelling reads it back");
        assertTrue(reg.isConfirmed("HTTP://93.184.216.34:3000/"));
    }

    @Test
    void seedIdentityIsTheCanonicalOne() {
        var reg = new PeerRegistry("http://self:3000", 100, null, false);
        reg.addSeeds(java.util.List.of("HTTP://seed.example.com:80/"));
        assertTrue(reg.isSeed("http://seed.example.com"), "seed membership is by identity");
        assertTrue(reg.isSeed("http://SEED.example.com./"),
            "any spelling of the seed URL is the same identity");
        assertEquals(1, reg.size(), "one seed, one entry");
    }

    @Test
    void selfIdentityIsRecognizedInAllItsSpellings() {
        // The self-pairing refusal keys on identity: the node's own URL, however it is
        // spelled by a PEX peer, is refused (audit: self-pairing bypass).
        var reg = new PeerRegistry("HTTP://Self.Example.COM.:80", 100, null, false);
        assertFalse(reg.add("http://self.example.com"), "the node must not peer with itself");
        assertFalse(reg.add("http://self.example.com:80/"));
        assertEquals(0, reg.size());
    }
}
