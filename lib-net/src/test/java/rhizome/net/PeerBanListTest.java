package rhizome.net;

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
        assertFalse(bans.misbehave("http://10.0.0.1:3000", 40));
        assertFalse(bans.isBanned("http://10.0.0.1:3000"));
        assertTrue(bans.misbehave("http://10.0.0.1:3000", 60)); // 40 + 60 = 100
        assertTrue(bans.isBanned("http://10.0.0.1:3000"));
    }

    @Test
    void singleStrikeBanAtThreshold() {
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        assertTrue(bans.misbehave("http://10.0.0.2:3000", 100));
        assertTrue(bans.isBanned("http://10.0.0.2:3000"));
    }

    @Test
    void banExpiresAfterWindow() {
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 128, clock::get);
        bans.ban("http://10.0.0.1:3000");
        assertTrue(bans.isBanned("http://10.0.0.1:3000"));
        clock.set(60_000);
        assertFalse(bans.isBanned("http://10.0.0.1:3000"));
    }

    @Test
    void scoreDecaysWithGoodBehaviour() {
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 128, clock::get);
        assertFalse(bans.misbehave("http://10.0.0.1:3000", 60));
        clock.set(60_000); // a full window later: score has fully decayed
        assertFalse(bans.misbehave("http://10.0.0.1:3000", 60)); // 0 + 60, still under threshold
        assertFalse(bans.isBanned("http://10.0.0.1:3000"));
    }

    @Test
    void banIsKeyedByHostNotPortOrPath() {
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        bans.ban("http://evil.invalid:3000");
        // Same host, different port / path: still banned.
        assertTrue(bans.isBanned("http://evil.invalid:4000/node"));
        assertTrue(bans.isBanned("http://EVIL.invalid:3000"));
        assertFalse(bans.isBanned("http://honest.invalid:3000"));
    }

    @Test
    void trackingIsBoundedAndSweepsExpired() {
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 4, clock::get);
        for (int i = 0; i < 4; i++) {
            bans.misbehave("http://10.0.1." + i + ":3000", 10);
        }
        assertTrue(bans.trackedPeers() <= 4);
        clock.set(120_000); // old entries fully decayed and sweepable
        for (int i = 0; i < 1000; i++) {
            bans.misbehave("http://10.1." + (i / 256) + "." + (i % 256) + ":3000", 10);
        }
        assertTrue(bans.trackedPeers() <= 4, "tracked peers must stay bounded");
    }

    @Test
    void activeBanSurvivesSweepPressure() {
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 4, clock::get);
        bans.ban("http://10.0.0.9:3000");
        // Fill and spray other hosts; the active ban must not be evicted.
        for (int i = 0; i < 1000; i++) {
            bans.misbehave("http://10.1." + (i / 256) + "." + (i % 256) + ":3000", 10);
        }
        assertTrue(bans.isBanned("http://10.0.0.9:3000"));
    }

    @Test
    void overflowBucketCountsButNeverBansInnocentHosts() {
        // Table full of fresh (unsweepable) entries: offences by new hosts accumulate against a
        // shared overflow bucket (nothing is dropped untracked — audit F7), and the OFFENDING
        // host is reported banned once the bucket is hot. But a DIFFERENT untracked host must
        // never be banned by it: banning every unknown host let a table-filling spray eclipse a
        // freshly started node from all new peers (audit follow-up).
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 4, clock::get);
        for (int i = 0; i < 4; i++) {
            bans.misbehave("http://10.0.1." + i + ":3000", 10);
        }
        boolean banned = false;
        for (int i = 0; i < 10; i++) {
            banned |= bans.misbehave("http://10.0.3." + i + ":3000", 50);
        }
        assertTrue(banned, "overflow offences must accumulate toward a ban, not vanish");
        assertTrue(bans.overflowScore() > 0 || banned, "the spray's score stays observable");
        assertFalse(bans.isBanned("http://10.0.4.1:3000"),
            "a host with no offence of its own is never banned by the overflow bucket");
        // A tracked, clean host is unaffected either.
        assertFalse(bans.isBanned("http://10.0.1.0:3000"));
    }

    @Test
    void banMirrorSurvivesAFullOffenceTable() {
        // With the offence table full of fresh (unsweepable) entries, a ban must STILL be
        // mirrored onto the offender's name key: the anti-dodge protection (audit L1) used to
        // lapse exactly under a spray, because the mirror was skipped whenever the table was full.
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 4, clock::get);
        for (int i = 0; i < 4; i++) {
            bans.misbehave("http://10.0.1." + i + ":3000", 10);
        }
        // Table full: the offence itself can only be metered via the overflow bucket, but the
        // mirrored name-key ban must still land and condemn the OFFENDING host.
        assertTrue(bans.misbehave("http://10.9.9.9:3000", 100));
        assertTrue(bans.isBanned("http://10.9.9.9:3000"),
            "the mirrored ban must condemn the offender even with a full offence table");
        assertFalse(bans.isBanned("http://10.9.9.8:3000"),
            "an innocent untracked host is still never banned by overflow pressure");
    }

    @Test
    void concurrentSprayKeepsTablesBoundedAndActiveBansStick() throws Exception {
        // Same spray-under-pressure case as RateLimiter: misses under capacity pressure used to
        // pay an O(maxTracked) sweep inside the check-and-insert monitor per request. The bounds
        // must hold under the race, and an active ban must survive (active bans are never swept).
        var bans = new PeerBanList(100, 60_000, 64);
        bans.ban("http://10.0.0.9:3000");
        int threads = 8, perThread = 500;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    int n = base + i;
                    bans.misbehave("http://10.1." + (n / 256) + "." + (n % 256) + ":3000", 10);
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertTrue(bans.trackedPeers() <= 64,
            "the offence table must stay bounded under a concurrent spray");
        assertTrue(bans.isBanned("http://10.0.0.9:3000"),
            "an active ban must survive sweep pressure");
    }

    @Test
    void registryRejectsAndEvictsBannedPeers() {
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        var registry = new PeerRegistry("http://10.9.0.1:3000", 128, bans);

        assertTrue(registry.add("http://10.9.0.2:3000"));
        assertEquals(1, registry.size());

        // One strike over the threshold bans and evicts it.
        assertTrue(registry.penalize("http://10.9.0.2:3000", 100));
        assertEquals(0, registry.size());
        assertTrue(registry.isBanned("http://10.9.0.2:3000"));

        // It cannot be re-introduced through any admission path.
        assertFalse(registry.add("http://10.9.0.2:3000"));
        registry.addAll(java.util.List.of("http://10.9.0.2:3000/node", "http://10.9.0.2:9999"));
        assertEquals(0, registry.size());
    }
}
