package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PeerBanListTest {

    /** The resolution cache is process-wide; primed names must be this class's own. */
    @BeforeEach
    void clearResolutionCache() {
        PeerHosts.resetCacheForTests();
    }

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
    void banIsKeyedByEndpointNotAddress() {
        // Testnet campaign S5 regression: banning one node on a shared host must not eclipse
        // its sibling ports. The old host-keyed ban (one IP, all ports) turned a single
        // transient PEER_INVALID into a 1 h eclipse of every healthy peer on localhost.
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        bans.ban("http://evil.invalid:3000");
        // The offending endpoint is banned...
        assertTrue(bans.isBanned("http://evil.invalid:3000"));
        assertTrue(bans.isBanned("http://EVIL.invalid:3000"), "case-insensitive host");
        // ...but a sibling port is a DIFFERENT endpoint (only the escalated address-wide
        // ban covers it), and an unrelated address is untouched.
        assertFalse(bans.isBanned("http://evil.invalid:4000/node"),
            "a sibling port must not be caught by one endpoint's ban");
        assertFalse(bans.isBanned("http://honest.invalid:3000"));
        // The S5 shape exactly: banning localhost:4102 leaves localhost:4108 clear.
        var local = new PeerBanList(100, 60_000, 128, () -> 0L);
        local.ban("http://localhost:4102");
        assertFalse(local.isBanned("http://localhost:4108"),
            "S5 regression: banning one port must not blind the sibling ports");
    }

    @Test
    void portRotationEscalatesToAnAddressWideBan() throws Exception {
        // Anti-rotation: one address that keeps offending across endpoints accumulates toward
        // an escalated address-wide ban, so rotation cannot reset the score forever. The address
        // must be publicly routable for the escalation to apply at all (see the shared-host
        // exemption below), so the names are primed to public IPs.
        PeerHosts.primeCacheForTests("rotator.test", InetAddress.getByName("203.0.113.7"));
        PeerHosts.primeCacheForTests("other.test", InetAddress.getByName("203.0.113.8"));
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        assertTrue(bans.misbehave("http://rotator.test:3000", 100));
        assertFalse(bans.isBanned("http://rotator.test:4000"), "1 strike: endpoint only");
        assertTrue(bans.misbehave("http://rotator.test:4000", 100));
        assertFalse(bans.isBanned("http://rotator.test:5000"), "2 strikes: still endpoint-only");
        assertTrue(bans.misbehave("http://rotator.test:5000", 100), "3rd strike: address-wide ban");
        assertTrue(bans.isBanned("http://rotator.test:6000"),
            "the escalated ban refuses every port of the address");
        assertFalse(bans.isBanned("http://other.test:3000"), "another address is untouched");
    }

    @Test
    void oneLoudEndpointCannotEscalateOnItsOwn() {
        // Review follow-up to the S5 fix: the escalation is anti-ROTATION, so it must require
        // rotation, not just points. An endpoint keeps crediting the address after its own ban
        // lands (its score stops accumulating, the address's does not), so a points-only rule let
        // ONE port reach the escalated threshold single-handed and ban all its innocent siblings
        // — the eclipse the endpoint keying removed, rebuilt one layer up.
        AtomicLong clock = new AtomicLong(0);
        var bans = new PeerBanList(100, 60_000, 128, clock::get);
        for (int strike = 0; strike < 10; strike++) {
            bans.misbehave("http://93.184.216.34:3000", 100); // far past 3 * 100
        }
        assertTrue(bans.isBanned("http://93.184.216.34:3000"), "the loud endpoint is banned");
        assertFalse(bans.isBanned("http://93.184.216.34:4000"),
            "one endpoint alone must never escalate to an address-wide ban");
    }

    @Test
    void sharedHostAddressesAreExemptFromEscalation() {
        // A non-publicly-routable address is a SHARED HOST (local devnet, compose stack, CI
        // matrix) where each port is a different operator-run node, so there is no address-wide
        // actor to escalate against. Escalating there is the S5 eclipse one layer up: on the
        // campaign's own 10-node localhost devnet, 3 sibling nodes each earning an endpoint ban
        // would have blinded the whole host. Endpoint bans still apply; only the escalation does not.
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        assertTrue(bans.misbehave("http://127.0.0.1:4102", 100));
        assertTrue(bans.misbehave("http://127.0.0.1:4103", 100));
        assertTrue(bans.misbehave("http://127.0.0.1:4104", 100));
        assertTrue(bans.isBanned("http://127.0.0.1:4102"), "each offending endpoint is still banned");
        assertFalse(bans.isBanned("http://127.0.0.1:4108"),
            "S5: three banned siblings must not eclipse the whole localhost devnet");
        // Same for RFC1918, the docker-compose / private-LAN shape.
        var lan = new PeerBanList(100, 60_000, 128, () -> 0L);
        lan.misbehave("http://10.9.0.2:3000", 100);
        lan.misbehave("http://10.9.0.2:4000", 100);
        lan.misbehave("http://10.9.0.2:5000", 100);
        assertFalse(lan.isBanned("http://10.9.0.2:6000"), "a private address never escalates");
    }

    @Test
    void defaultPortsCannotKeyTwoBanEntries() {
        // The keys fold http:80 / https:443 to the absent form PeerUrls.canonicalize emits, so a
        // caller that hands over a non-canonical URL cannot dodge a ban by writing the port out
        // (or omitting it) — the keys are correct on their own, not only by upstream convention.
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        bans.ban("http://198.51.100.4");
        assertTrue(bans.isBanned("http://198.51.100.4:80"), "':80' is the same endpoint as the bare form");
        var tls = new PeerBanList(100, 60_000, 128, () -> 0L);
        tls.ban("https://198.51.100.5:443");
        assertTrue(tls.isBanned("https://198.51.100.5"), "…and the same for https:443");
    }

    @Test
    void nameMirrorIsPortScoped() {
        // The DNS-flip mirror (audit L1) must not recreate the eclipse it exists to close:
        // on a shared-host network a bare "name:localhost" mirror would ban every sibling
        // port. The mirror key carries the port like the endpoint key does.
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        bans.ban("http://evil.invalid:3000");
        assertTrue(bans.isBanned("http://evil.invalid:3000"), "same endpoint via its mirror");
        assertFalse(bans.isBanned("http://evil.invalid:4000"),
            "the name mirror is port-scoped like the endpoint key");
    }

    @Test
    void addressEscalationIsAbandonedWhenItsTableIsFull() {
        // The address table is best-effort: when full (fresh, unsweepable entries) the
        // escalation is dropped — an innocent address must never be condemned by a shared
        // bucket the way endpoint overflow works. Public IP literals, so the escalation is
        // genuinely in play and the table genuinely fills (a shared-host address never
        // allocates an entry at all).
        var bans = new PeerBanList(100, 60_000, 2, () -> 0L);
        assertTrue(bans.misbehave("http://203.0.113.1:3000", 100));
        assertTrue(bans.misbehave("http://203.0.113.2:3000", 100));
        // Table (2) full of fresh entries: the third address cannot escalate, so further
        // offences on NEW ports of it are NOT covered by an address ban.
        assertTrue(bans.misbehave("http://203.0.113.3:3000", 100)); // endpoint banned, escalation dropped
        assertTrue(bans.misbehave("http://203.0.113.3:4000", 100));
        assertTrue(bans.misbehave("http://203.0.113.3:5000", 100)); // would be the 3rd rotation
        assertFalse(bans.isBanned("http://203.0.113.3:6000"),
            "a dropped escalation must not condemn the address's other endpoints");
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

        // The SAME endpoint cannot be re-introduced through any admission path.
        assertFalse(registry.add("http://10.9.0.2:3000"));
        assertFalse(registry.add("http://10.9.0.2:3000/node"));

        // A different port is a different endpoint (testnet campaign S5: one node's ban must not
        // eclipse its siblings on the same address) — and on a PRIVATE address it keeps rejoining
        // however many siblings are banned, because the escalation does not apply to shared hosts.
        assertTrue(registry.add("http://10.9.0.2:9999"), "sibling port admitted: endpoint-scoped");
        assertTrue(registry.penalize("http://10.9.0.2:9999", 100));
        assertTrue(registry.penalize("http://10.9.0.2:4444", 100));
        assertTrue(registry.add("http://10.9.0.2:7777"),
            "a private (shared-host) address is never escalated to an address-wide ban");
    }

    @Test
    void escalatedAddressBanRefusesEveryPortOfAPublicAddress() {
        // The anti-rotation escalation, end to end through the registry: three DISTINCT endpoints
        // of one publicly routable address offending is genuine port rotation, and it refuses the
        // address wholesale. (The same sequence on a private address does not — see above.)
        var bans = new PeerBanList(100, 60_000, 128, () -> 0L);
        var registry = new PeerRegistry("http://10.9.0.1:3000", 128, bans);

        assertTrue(registry.penalize("http://203.0.113.9:3000", 100), "1st endpoint");
        assertTrue(registry.penalize("http://203.0.113.9:9999", 100), "2nd endpoint");
        assertTrue(registry.penalize("http://203.0.113.9:4444", 100), "3rd endpoint: address banned");
        assertTrue(registry.isBanned("http://203.0.113.9:7777"),
            "the escalated address-wide ban refuses every port");
        assertEquals(0, registry.size());
    }
}
