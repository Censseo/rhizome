package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The per-peer transport-failure counter shared by PEX, pull sync and gossip. */
class PeerFailureCounterTest {

    /**
     * NET-12 — Tarpit a peer on sync or gossip while it keeps answering PEX: transport failures
     * from any channel feed the one counter that evicts, and a seed is never evicted by it.
     */
    @Test
    void transportFailuresFromAnyChannelEvictAfterThreeButNeverASeed() {
        PeerRegistry registry = new PeerRegistry(null, 16);
        String peer = "http://127.0.0.1:4001";
        String seed = "http://127.0.0.1:4002";
        registry.add(peer);
        registry.addSeeds(List.of(seed));

        assertFalse(registry.recordFailure(peer));
        assertFalse(registry.recordFailure(peer));
        registry.recordSuccess(peer); // a success resets the CONSECUTIVE count
        assertFalse(registry.recordFailure(peer));
        assertFalse(registry.recordFailure(peer));
        assertTrue(registry.recordFailure(peer), "third consecutive failure evicts");
        assertFalse(registry.snapshot().contains(peer));

        for (int i = 0; i < 10; i++) {
            assertFalse(registry.recordFailure(seed), "a seed accumulates the count but is never evicted");
        }
        assertTrue(registry.snapshot().contains(seed));
        assertFalse(registry.recordFailure("http://127.0.0.1:4999"), "an unknown peer is ignored");
    }

    /** NET-12 — gossip send failures reach the sink and evict the peer. */
    @Test
    void gossipOutcomesFeedTheSharedCounter() {
        PeerRegistry registry = new PeerRegistry(null, 16);
        String dead = "http://127.0.0.1:1"; // nothing listens: connection refused
        registry.add(dead);
        try (PeerBroadcaster b = new PeerBroadcaster(registry::snapshot, false)
                .withOutcomeSinks(registry::recordFailure, registry::recordSuccess)) {
            for (int i = 0; i < PeerRegistry.MAX_FAILURES && registry.snapshot().contains(dead); i++) {
                b.sendQuietlyForTest(dead, "/submit", new byte[] {1});
            }
        }
        assertFalse(registry.snapshot().contains(dead), "repeated gossip failures evict the peer");
    }

    /**
     * NET-14 — Flip a peer's DNS after admission so its ban lands on (and is checked against) a
     * different address than the one that misbehaved: bans key on the IP dialled at admission,
     * supplied by the registry, and never resolve a name again.
     */
    @Test
    void banKeysOnTheDialledIpNotOnAFreshResolution() {
        PeerBanList bans = new PeerBanList(100, 60_000L, 64);
        PeerId a = PeerId.of("http://never-resolves-a.invalid:3000");
        PeerId b = PeerId.of("http://never-resolves-b.invalid:3000");
        assertTrue(bans.misbehave(a, 100, "203.0.113.7"));
        assertTrue(bans.isBanned(a, "203.0.113.7"));
        assertTrue(bans.isBanned(b, "203.0.113.7"), "same dialled endpoint, different name: still banned");
        assertFalse(bans.isBanned(b, "203.0.113.8"), "a different dialled address is a different endpoint");
    }
}
