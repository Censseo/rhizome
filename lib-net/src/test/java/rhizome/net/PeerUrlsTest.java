package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Peer-URL canonicalization (audit: /add_peer coalescing & self-pairing bypass): case,
 * trailing-dot, default-port and trailing-slash variants of one URL must collapse to a
 * single canonical form, or one peer squats several registry slots and the self-pairing
 * refusal is dodged.
 */
class PeerUrlsTest {

    @Test
    void casePortDotAndSlashVariantsCoalesce() {
        String canonical = "http://example.com:3000";
        assertEquals(canonical, PeerUrls.canonicalize("http://example.com:3000"));
        assertEquals(canonical, PeerUrls.canonicalize("HTTP://Example.COM:3000/"));
        assertEquals(canonical, PeerUrls.canonicalize("http://example.com.:3000/"));
        assertEquals(canonical, PeerUrls.canonicalize("  http://example.com:3000//  "));
    }

    @Test
    void defaultPortsAreOmitted() {
        assertEquals("http://example.com", PeerUrls.canonicalize("http://example.com:80/"));
        assertEquals("http://example.com", PeerUrls.canonicalize("http://example.com"));
        assertEquals("https://example.com", PeerUrls.canonicalize("https://example.com:443/"));
        // A non-default port is significant and retained.
        assertEquals("http://example.com:8080", PeerUrls.canonicalize("http://example.com:8080"));
        assertEquals("https://example.com:80", PeerUrls.canonicalize("https://example.com:80"));
    }

    @Test
    void ipv6HostsKeepTheirBrackets() {
        assertEquals("http://[::1]:3000", PeerUrls.canonicalize("http://[::1]:3000/"));
        assertEquals("http://[2001:db8::1]", PeerUrls.canonicalize("http://[2001:DB8::1]:80"));
    }

    @Test
    void degenerateInputsDegradeWithoutThrowing() {
        assertNull(PeerUrls.canonicalize(null));
        // Unparseable / host-less input is returned trimmed and slash-stripped; the registry's
        // isHttpUrl check rejects it downstream.
        assertEquals("not a url", PeerUrls.canonicalize("  not a url  "));
        assertEquals("httpfoo://x", PeerUrls.canonicalize("httpfoo://x/"));
    }
}
