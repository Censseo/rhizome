package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import rhizome.net.PeerBanList;
import rhizome.net.PeerDiscovery;
import rhizome.net.PeerRegistry;

/**
 * Ban scoring and peer discovery composed over a horizon long enough for the ban score to decay
 * and the discovery failure counter to reset, not just for one strike to land (NET family — see
 * docs/adversarial/spec.md).
 *
 * <p>{@code PeerBanList}'s score decays linearly over one ban window (an offence is a punishment
 * that must eventually be forgiven), while {@code PeerDiscovery}'s consecutive-failure counter
 * resets to zero on every successful contact (it measures the length of the CURRENT outage, not
 * accumulated culpability) — two different forgiveness rules for two different quantities, not a
 * drift between them. Neither, on inspection, composes into a long-horizon eviction primitive
 * against a peer that is honest: three scattered failures over days are each wiped by the next
 * successful round long before a fourth could land, and a ban's score resets to zero the moment it
 * fires, so a single post-expiry strike cannot renew it. What genuinely limits a node's resistance
 * to partition is narrower and structural rather than temporal: the loopback-scale {@code
 * MAX_PER_SUBNET} bucket cap, and the fact that {@code PeerRegistry.penalize} and {@code
 * PeerDiscovery}'s PEX eviction path both exempt seeds outright (audit M4) — so the defence this
 * class actually proves is conditional on an operator running at least one honest, reachable seed.
 * An unanchored, purely PEX-bootstrapped node is not protected by anything proven here.
 *
 * <p>The two clock-injecting constructors this class needs ({@link PeerBanList#PeerBanList(int,
 * long, int, java.util.function.LongSupplier)} and {@link PeerRegistry#PeerRegistry(String, int,
 * PeerBanList, boolean, java.util.function.LongSupplier)}) were widened from package-private to
 * public for this suite, mirroring the already-public {@code ChainEngine.Boot#clock} seam in
 * lib-core — a 48-simulated-hour horizon cannot be driven on the real wall clock. Only the
 * discovery-failure test below drives real sockets; every other test here calls {@code
 * PeerBanList}/{@code PeerRegistry} directly with an advancing virtual clock, which is what keeps
 * a 576-round horizon fast — this suite runs under the ordinary {@code test} task (JUnit Platform
 * discovers every {@code @Test}-bearing class regardless of name), not only under {@code
 * ./gradlew adversarial}.
 */
class BanDiscoveryPartitionAttackTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
    }

    /** Production value, {@code RhizomeNode.java}: {@code new PeerBanList(BAN_THRESHOLD, 60*60*1000L, 4096)}. */
    private static final long BAN_MILLIS = 60 * 60 * 1000L;
    /** {@code SyncDriver.BAN_THRESHOLD}. */
    private static final int BAN_THRESHOLD = 100;
    /** {@code SyncDriver.PENALTY_INVALID} — the heavier of the two real per-outcome costs. */
    private static final int PENALTY_INVALID = 34;
    /** Simulated spacing between rounds; the horizon below is 48 simulated hours at this spacing. */
    private static final long ROUND_MS = 5 * 60_000L;
    private static final int HORIZON_ROUNDS = 576;

    /**
     * Mirrors {@code SyncDriver.penalize} verbatim (app-node, not reachable from this module): only
     * a peer that has answered at least one well-formed exchange can earn ban score; an unconfirmed
     * one is dropped instead of banned (audit B-3, ban by proxy).
     */
    private static boolean syncPenalize(PeerRegistry registry, String peerUrl, int points) {
        if (!registry.isConfirmed(peerUrl)) {
            registry.remove(peerUrl);
            return false;
        }
        return registry.penalize(peerUrl, points);
    }

    /** A loopback peer whose {@code /peers} health is toggled by the caller between rounds. */
    private int startToggleablePeer(AtomicBoolean healthy) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/peers", exchange -> {
            if (healthy.get()) {
                byte[] body = "{\"peers\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.close();
        });
        server.createContext("/add_peer", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return server.getAddress().getPort();
    }

    /**
     * NET-11 — the positive control. Without an operator-configured seed, sixteen sybils occupying
     * every slot of the loopback {@code v4:127.0} subnet bucket keep a genuinely honest peer locked
     * out of the table for the entire simulated horizon, not just briefly — nothing in this class
     * ever empties the bucket on its own. This is what proves the harness can observe a real,
     * permanent partition rather than a broken model silently measuring nothing; every other test
     * below shows the ban/discovery mechanisms do NOT extend this reach to an anchored node.
     */
    @Test
    void sixteenSybilsSaturatingTheLoopbackSubnetLockOutAnHonestPeerForTheWholeHorizon() {
        PeerRegistry registry = new PeerRegistry(null, 128);
        for (int i = 0; i < 16; i++) {
            assertTrue(registry.add("http://127.0.0.1:" + (20_000 + i)),
                "sybil " + i + " must be admitted to fill the v4:127.0 bucket (MAX_PER_SUBNET=16)");
        }
        String honest = "http://127.0.0.1:29999";
        for (int round = 0; round < HORIZON_ROUNDS; round++) {
            assertFalse(registry.add(honest),
                "round " + round + ": the honest peer must still be refused — the bucket the "
                    + "sybils fill never empties on its own");
        }
    }

    /**
     * NET-11 — liveness with an anchor. A seed survives a table repeatedly churned by misbehaving
     * sybils across the same 48-hour horizon: {@code PeerRegistry.penalize}'s seed exemption is
     * pinned elsewhere as a single call (audit M4); this pins it as a property that holds at every
     * point across a long horizon, not just once.
     */
    @Test
    void aSeedSurvivesFortyEightHoursOfMisbehavingSybilsFillingTheTable() {
        AtomicLong clock = new AtomicLong(0);
        PeerBanList banList = new PeerBanList(BAN_THRESHOLD, BAN_MILLIS, 4096, clock::get);
        PeerRegistry registry = new PeerRegistry(null, 128, banList, false, clock::get);
        String seed = "http://127.0.0.1:19999";
        registry.addSeeds(List.of(seed));

        for (int round = 0; round < HORIZON_ROUNDS; round++) {
            clock.addAndGet(ROUND_MS);
            String sybil = "http://127.0.0.1:" + (30_000 + (round % 16));
            registry.add(sybil);
            registry.markConfirmed(sybil);
            syncPenalize(registry, sybil, PENALTY_INVALID);

            assertTrue(registry.snapshot().contains(seed),
                "round " + round + ": the seed vanished from the peer table");
            assertFalse(syncPenalize(registry, seed, BAN_THRESHOLD),
                "round " + round + ": a seed must never accumulate ban score");
            assertFalse(registry.isBanned(seed), "round " + round + ": a seed must never read as banned");
        }

        // A five-round outage on top: the seed is never re-confirmed here, and it is still never
        // evicted, because the exemption in penalize() does not depend on confirmation reaching it.
        for (int i = 0; i < 5; i++) {
            clock.addAndGet(ROUND_MS);
        }
        assertTrue(registry.snapshot().contains(seed), "the seed did not survive the simulated outage");
    }

    /**
     * NET-11 — the asymmetry, over real {@code PeerDiscovery.round()} calls on real loopback
     * sockets: five failures scattered across successful rounds never evict a non-seed peer,
     * because {@code contactPeer} resets the failure count to zero on every success — it is a
     * losing-streak detector, not an accumulator. {@code PeerDiscovery} holds no wall-clock read at
     * all, so "scattered over days" and "scattered over five rounds" are the same claim to this
     * code; the sequence below is the faithful worst case. Three CONSECUTIVE failures do evict it.
     */
    @Test
    void fiveScatteredFailuresNeverEvictButThreeConsecutiveDo() throws IOException {
        AtomicBoolean healthy = new AtomicBoolean(true);
        int port = startToggleablePeer(healthy);
        String peerUrl = "http://127.0.0.1:" + port;

        PeerRegistry registry = new PeerRegistry(null, 128);
        registry.add(peerUrl);
        PeerDiscovery discovery = new PeerDiscovery(registry, null, false);

        for (int i = 0; i < 5; i++) {
            healthy.set(false);
            discovery.round();
            assertTrue(registry.snapshot().contains(peerUrl),
                "scattered failure " + i + " alone must never evict a peer that keeps recovering");
            healthy.set(true);
            discovery.round();
            assertTrue(registry.snapshot().contains(peerUrl),
                "the peer must still be registered after recovering from failure " + i);
        }

        healthy.set(false);
        discovery.round();
        assertTrue(registry.snapshot().contains(peerUrl), "one failure alone must never evict");
        discovery.round();
        assertTrue(registry.snapshot().contains(peerUrl), "two consecutive failures must not evict yet");
        discovery.round();
        assertFalse(registry.snapshot().contains(peerUrl),
            "three CONSECUTIVE failures must evict a non-seed peer");
    }

    /**
     * NET-11 — healing. A peer evicted for misbehaviour is refused re-admission only for the
     * removal cooldown window ({@code PeerRegistry.REMOVAL_COOLDOWN_MILLIS}, 5 minutes), then
     * rejoins on the very next attempt: eviction here is a timeout, not a lasting exile.
     */
    @Test
    void anEvictedPeerRejoinsExactlyWhenTheRemovalCooldownExpires() {
        AtomicLong clock = new AtomicLong(0);
        PeerRegistry registry = new PeerRegistry(null, 128, null, false, clock::get);
        String peer = "http://127.0.0.1:21000";
        registry.add(peer);
        registry.remove(peer); // the eviction path PeerDiscovery's failure branch also takes

        assertFalse(registry.add(peer), "immediately after eviction the cooldown must still hold");
        clock.addAndGet(5 * 60_000L - 1);
        assertFalse(registry.add(peer), "one millisecond short of the cooldown must still refuse");
        clock.addAndGet(1);
        assertTrue(registry.add(peer), "exactly at the cooldown boundary the peer must be re-admissible");
    }

    /**
     * NET-11 — the ban cap holds for exactly its configured window and does not renew on a single
     * strike landing just after expiry: the score resets to zero the moment a ban fires, so one
     * post-expiry {@code PENALTY_INVALID} (34 of a 100 threshold) comes nowhere close to re-banning.
     */
    @Test
    void aBanHoldsForExactlyItsWindowAndDoesNotRenewOnASingleLateStrike() {
        AtomicLong clock = new AtomicLong(0);
        PeerBanList banList = new PeerBanList(BAN_THRESHOLD, BAN_MILLIS, 4096, clock::get);
        PeerRegistry registry = new PeerRegistry(null, 128, banList, false, clock::get);
        String peer = "http://127.0.0.1:22000";
        registry.add(peer);
        registry.markConfirmed(peer);

        // Three strikes of the real PENALTY_INVALID cost: 3 * 34 = 102 >= BAN_THRESHOLD (100).
        assertFalse(syncPenalize(registry, peer, PENALTY_INVALID));
        assertFalse(syncPenalize(registry, peer, PENALTY_INVALID));
        assertTrue(syncPenalize(registry, peer, PENALTY_INVALID),
            "the third strike must cross the threshold and ban");
        assertTrue(registry.isBanned(peer));

        clock.addAndGet(BAN_MILLIS - 1);
        assertTrue(registry.isBanned(peer), "one millisecond short of the window the ban must still hold");

        clock.addAndGet(1);
        assertFalse(registry.isBanned(peer), "exactly at the window the ban must have lifted");

        // penalize() removes the peer (and arms the removal cooldown) the moment it bans it; by
        // BAN_MILLIS later the 5-minute cooldown is long past, so re-admission here tests only the
        // ban-renewal question, not the unrelated cooldown one.
        assertTrue(registry.add(peer), "the peer must be re-admissible once its ban has lifted");
        registry.markConfirmed(peer);
        assertFalse(syncPenalize(registry, peer, PENALTY_INVALID),
            "a single post-expiry strike must not re-ban — the score reset to zero when the ban fired");
        assertFalse(registry.isBanned(peer));
    }
}
