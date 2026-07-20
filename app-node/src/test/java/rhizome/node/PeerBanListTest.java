package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class PeerBanListTest {

    @Test
    void bansOnceScoreCrossesThreshold() {
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 128, clock::get);
        assertFalse(bans.misbehave("http://p:3000", 40));
        assertFalse(bans.isBanned("http://p:3000"));
        assertTrue(bans.misbehave("http://p:3000", 60)); // 40 + 60 = 100
        assertTrue(bans.isBanned("http://p:3000"));
    }

    @Test
    void singleStrikeBanAtThreshold() {
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        assertTrue(bans.misbehave("http://evil:3000", 100));
        assertTrue(bans.isBanned("http://evil:3000"));
    }

    @Test
    void banExpiresAfterWindow() {
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 128, clock::get);
        bans.ban("http://p:3000");
        assertTrue(bans.isBanned("http://p:3000"));
        clock.set(60_000);
        assertFalse(bans.isBanned("http://p:3000"));
    }

    @Test
    void scoreDecaysWithGoodBehaviour() {
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 128, clock::get);
        assertFalse(bans.misbehave("http://p:3000", 60));
        clock.set(60_000); // a full window later: score has fully decayed
        assertFalse(bans.misbehave("http://p:3000", 60)); // 0 + 60, still under threshold
        assertFalse(bans.isBanned("http://p:3000"));
    }

    @Test
    void banIsKeyedByHostNotPortOrPath() {
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        bans.ban("http://evil:3000");
        // Same host, different port / path: still banned.
        assertTrue(bans.isBanned("http://evil:4000/node"));
        assertTrue(bans.isBanned("http://EVIL:3000"));
        assertFalse(bans.isBanned("http://honest:3000"));
    }

    @Test
    void trackingIsBoundedAndSweepsExpired() {
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 4, clock::get);
        for (int i = 0; i < 4; i++) {
            bans.misbehave("http://h" + i + ":3000", 10);
        }
        assertTrue(bans.trackedPeers() <= 4);
        clock.set(120_000); // old entries fully decayed and sweepable
        for (int i = 0; i < 1000; i++) {
            bans.misbehave("http://spray" + i + ":3000", 10);
        }
        assertTrue(bans.trackedPeers() <= 4, "tracked peers must stay bounded");
    }

    @Test
    void activeBanSurvivesSweepPressure() {
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 4, clock::get);
        bans.ban("http://victim:3000");
        // Fill and spray other hosts; the active ban must not be evicted.
        for (int i = 0; i < 1000; i++) {
            bans.misbehave("http://spray" + i + ":3000", 10);
        }
        assertTrue(bans.isBanned("http://victim:3000"));
    }

    @Test
    void registryRejectsAndEvictsBannedPeers() {
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        var registry = new PeerRegistry("http://self:3000", 128, bans);

        assertTrue(registry.add("http://peer:3000"));
        assertEquals(1, registry.size());

        // One strike over the threshold bans and evicts it.
        assertTrue(registry.penalize("http://peer:3000", 100));
        assertEquals(0, registry.size());
        assertTrue(registry.isBanned("http://peer:3000"));

        // It cannot be re-introduced through any admission path.
        assertFalse(registry.add("http://peer:3000"));
        registry.addAll(java.util.List.of("http://peer:3000/node", "http://peer:9999"));
        assertEquals(0, registry.size());
    }
}
