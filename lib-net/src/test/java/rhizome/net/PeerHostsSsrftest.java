package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

/**
 * SSRF range coverage of {@link PeerHosts#isRoutable} (audit: SSRF range gaps): every
 * non-public range a peer URL could point at must be refused, and v4-encoded-in-v6 forms
 * must not bypass the v4 rules.
 */
class PeerHostsSsrftest {

    private static boolean routable(String literal) throws Exception {
        return PeerHosts.isRoutable(InetAddress.getByName(literal));
    }

    private static boolean routableBytes(byte[] raw) throws Exception {
        return PeerHosts.isRoutable(InetAddress.getByAddress(raw));
    }

    @Test
    void nat64WellKnownPrefixIsRejected() throws Exception {
        assertFalse(routable("64:ff9b::8.8.8.8"), "64:ff9b::/96 NAT64 embeds a v4 address");
        assertFalse(routable("64:ff9b::a00:1"), "64:ff9b::/96 NAT64 of 10.0.0.1");
        // Just outside the prefix stays routable.
        assertTrue(routable("64:ff9c::1"));
    }

    @Test
    void deprecatedV4CompatiblePrefixIsRejected() throws Exception {
        // ::/96 with a non-mapped tail (bytes 10-11 NOT ff ff): the deprecated v4-compatible form.
        byte[] compat = new byte[16];
        compat[15] = 1; // ::0.0.0.1
        assertFalse(routableBytes(compat), "::/96 v4-compatible is deprecated and embeds v4");
        compat[12] = 8;
        compat[13] = 8;
        compat[14] = 8;
        compat[15] = 8; // ::8.8.8.8
        assertFalse(routableBytes(compat));
    }

    @Test
    void v4MappedV6IsClassifiedByItsEmbeddedV4Address() throws Exception {
        // A raw 16-byte v4-mapped address (::ffff:a.b.c.d) must obey the v4 rules — otherwise
        // the private/loopback filters are dodged by encoding (the JDK hands these back as
        // Inet4Address only when parsed from text, so build the bytes directly).
        assertFalse(routableBytes(mapped((byte) 127, (byte) 0, (byte) 0, (byte) 1)), "mapped loopback");
        assertFalse(routableBytes(mapped((byte) 10, (byte) 0, (byte) 0, (byte) 1)), "mapped RFC1918");
        assertFalse(routableBytes(mapped((byte) 100, (byte) 64, (byte) 0, (byte) 1)), "mapped CGNAT");
        assertFalse(routableBytes(mapped((byte) 169, (byte) 254, (byte) 169, (byte) 254)), "mapped metadata");
        assertTrue(routableBytes(mapped((byte) 8, (byte) 8, (byte) 8, (byte) 8)), "mapped public is fine");
    }

    private static byte[] mapped(byte a, byte b, byte c, byte d) {
        byte[] raw = new byte[16];
        raw[10] = (byte) 0xFF;
        raw[11] = (byte) 0xFF;
        raw[12] = a;
        raw[13] = b;
        raw[14] = c;
        raw[15] = d;
        return raw;
    }

    @Test
    void benchmarkReservedAndIetfRangesAreRejected() throws Exception {
        assertFalse(routable("198.18.0.1"), "198.18.0.0/15 benchmarking");
        assertFalse(routable("198.19.255.254"), "198.18.0.0/15 upper half");
        assertFalse(routable("240.0.0.1"), "240.0.0.0/4 reserved");
        assertFalse(routable("255.255.255.255"), "240.0.0.0/4 includes the limited broadcast");
        assertFalse(routable("192.0.0.1"), "192.0.0.0/24 IETF protocol assignments");
        assertFalse(routable("100.64.0.1"), "100.64.0.0/10 CGNAT");
        assertFalse(routable("100.127.255.255"), "100.64.0.0/10 upper half");
        // Adjacent, legitimate public ranges stay routable.
        assertTrue(routable("198.17.255.1"));
        assertTrue(routable("198.20.0.1"));
        assertFalse(routable("239.255.255.255"), "239/8 is multicast (sanity)");
        assertTrue(routable("192.0.1.1"), "only the /24 is IETF-reserved");
        assertTrue(routable("100.63.255.1"));
        assertTrue(routable("100.128.0.1"));
        assertTrue(routable("8.8.8.8"));
    }
}
