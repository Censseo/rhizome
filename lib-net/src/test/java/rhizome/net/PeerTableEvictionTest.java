package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** A full peer table is held by whoever filled it first unless newcomers can displace the unverified. */
class PeerTableEvictionTest {

    /**
     * NET-13 — Fill the peer table first with unverified identities and hold it forever: a full
     * table makes room for a newcomer by evicting a random unconfirmed peer, never a confirmed
     * peer or a seed, and the table never exceeds its bound.
     */
    @Test
    void aFullTableEvictsAnUnconfirmedPeerButNeverAConfirmedOneOrASeed() {
        PeerRegistry registry = new PeerRegistry(null, 8);
        String seed = "http://127.0.0.1:5000";
        registry.addSeeds(List.of(seed));
        String verified = "http://127.0.0.1:5001";
        assertTrue(registry.add(verified));
        registry.markConfirmed(verified);
        for (int p = 5002; registry.size() < 8; p++) {
            assertTrue(registry.add("http://127.0.0.1:" + p));
        }
        assertEquals(8, registry.size());

        for (int i = 0; i < 50; i++) {
            assertTrue(registry.add("http://127.0.0.1:" + (6000 + i)), "newcomer " + i + " must get in");
            assertEquals(8, registry.size(), "the table bound holds");
            assertTrue(registry.snapshot().contains(seed));
            assertTrue(registry.snapshot().contains(verified));
        }
    }

    /** NET-13 — a table made entirely of seeds and verified peers refuses an unverified newcomer. */
    @Test
    void aTableOfVerifiedPeersIsNotDisplacedByAnUnverifiedNewcomer() {
        PeerRegistry registry = new PeerRegistry(null, 4);
        for (int p = 5001; p <= 5004; p++) {
            String url = "http://127.0.0.1:" + p;
            assertTrue(registry.add(url));
            registry.markConfirmed(url);
        }
        assertFalse(registry.add("http://127.0.0.1:5099"));
        assertEquals(4, registry.size());
    }

    /**
     * NET-13 — the unconfirmed share is capped (a quarter of the table is reserved for outbound-
     * verified peers), so unverified identities alone can never fill the whole table.
     */
    @Test
    void unconfirmedPeersCannotTakeTheReservedShare() {
        PeerRegistry registry = new PeerRegistry(null, 8);
        for (int p = 5001; p <= 5100; p++) {
            registry.add("http://127.0.0.1:" + p);
        }
        assertEquals(6, registry.size(), "8 slots, 2 reserved for confirmed peers");
    }

    /** NET-13 — IPv6 peers are bucketed per /32, not per /48. */
    @Test
    void ipv6SubnetBucketIsAThirtyTwoBitPrefix() {
        assertEquals(PeerHosts.subnetKey("[2001:db8:1::1]"), PeerHosts.subnetKey("[2001:db8:2::1]"));
    }
}
