package rhizome.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.HeaderSynchronizer;
import rhizome.core.blockchain.PeerProtocolException;
import rhizome.net.HttpPeerSource;
import rhizome.net.PeerExchange;
import rhizome.net.PeerRegistry;
import rhizome.net.PeerTokenPolicy;

/**
 * The node's periodic multi-peer sync round, extracted from RhizomeNode (archi-review lot L22,
 * constat 20): the round loop over all known peers, the per-round outcome accounting that feeds
 * {@code /stats}, the stall/eclipse counters and the ban scoring. The scheduler that arms it and
 * the object graph it drives stay in RhizomeNode; this class owns only the round itself, its
 * budget and its policy constants.
 *
 * <p>Single-threaded by design (the scheduler runs it on one thread); the cursor, counters and
 * report flags carry no synchronization.
 */
final class SyncDriver {

    private static final Logger log = LoggerFactory.getLogger(SyncDriver.class);

    // Ban-score costs per sync outcome. Serving an invalid chain (bad PoW, broken
    // continuity, claimed-heavy-proved-light) is a protocol violation, but the signal
    // most exposed to races — a peer mid-reorg can transiently serve a chain that reads
    // as invalid — must not ban on the first strike: PENALTY_INVALID = 34 of 100 takes
    // three strikes, and the address-wide escalation in PeerBanList keeps port-rotation
    // attacks compensated (testnet campaign S5: one transient PEER_INVALID during a
    // reorg eclipsed a healthy node). REORG_TOO_DEEP carries NO penalty at all: a branch
    // past the reorg horizon is not misbehaviour — on a forked network the losing camp
    // legitimately diverges deeper than finality — and scoring it accumulated +25/strike
    // to a 1 h ban, renewed hourly, a permanent mutual lock that prevented the natural
    // heal (testnet campaign replay: two equal-rate mining camps stayed locked for hours).
    // A genesis mismatch is usually just a misconfigured wrong-network node.
    static final int BAN_THRESHOLD = 100;
    private static final int PENALTY_INVALID = 34;
    private static final int PENALTY_INCOMPATIBLE = 10;

    /** Wall-clock budget for one sync round: past this, remaining peers are left for the next round so
     *  one slow (but not-yet-timed-out) peer, or a long tail of them, cannot starve later peers or delay
     *  the schedule (audit net #2). A single in-progress sync is never cut — the check is between peers,
     *  so a legitimate long catch-up from a good peer still completes. */
    private static final long SYNC_ROUND_BUDGET_MS = 60_000L;

    /**
     * Consecutive rounds without any sync progress (no EXTENDED/REORGED from any peer)
     * before a WARN is emitted, and the re-emission period for an ongoing stall. At the
     * default ~10 s sync period, 6 rounds ≈ 1 minute: an operator sees a stalled sync in
     * minutes, not in a 12-minute log post-mortem (testnet campaign S5).
     */
    private static final long PROGRESS_WARN_ROUNDS = 6;

    private final ChainEngine engine;
    private final PeerRegistry registry;
    private final NodeService service;
    private final boolean blockPrivatePeers;
    private final PeerExchange exchange;
    private final PeerTokenPolicy peerTokenPolicy;
    private final long syncPeriodMs;

    /** Rotates the per-round starting peer (single sync thread, so no synchronization needed). */
    private long syncRoundCursor;

    /** Height at the start of the previous round: a height advance between ROUND STARTS resets the
     *  stall counter (see syncRound). Initialised to -1 so the first round counts as progressing. */
    private long lastObservedHeight = -1;

    /** Consecutive rounds with neither sync progress nor a height advance (single sync thread). */
    private long roundsWithoutProgress;

    /** Whether the previous round found every known peer banned (single sync thread). */
    private boolean eclipsedReported;

    SyncDriver(ChainEngine engine, PeerRegistry registry, NodeService service,
               boolean blockPrivatePeers, PeerExchange exchange, PeerTokenPolicy peerTokenPolicy,
               long syncPeriodMs) {
        this.engine = engine;
        this.registry = registry;
        this.service = service;
        this.blockPrivatePeers = blockPrivatePeers;
        this.exchange = exchange;
        this.peerTokenPolicy = peerTokenPolicy;
        this.syncPeriodMs = syncPeriodMs;
    }

    /** One sync round across all known peers; peer failures are isolated. */
    void syncRound() {
        // Bound once: this runs on the single sync thread, which the scheduler only arms after
        // start() has published the graph, so the collaborators cannot change under the round.
        var synchronizer = new HeaderSynchronizer(engine);
        java.util.List<String> peers = registry.snapshot();
        int n = peers.size();
        if (n == 0) {
            // NOT a quiet round to skip: an empty registry is the DEEPEST eclipse there is, and it
            // is also the SHAPE the eclipse actually takes in production — PeerRegistry.penalize
            // evicts a peer the moment its ban lands, so "every peer banned" almost never means "a
            // registry full of banned entries" (the state peersSkippedBanned counts) and almost
            // always means "a registry emptied by evictions". Returning here without publishing
            // froze /stats on the previous round's counters and emitted no WARN in exactly the
            // state the metric exists to surface (review follow-up to the S5 fix).
            publishRoundOutcome(engine.height(), 0, 0, 0, false);
            return;
        }
        // The round's progress baseline: on a healthy gossip-fed network, sync rounds
        // legitimately do nothing (peers PUSH blocks, so heights advance without any
        // EXTENDED/REORGED). "No progress" only means something when the HEIGHT is also
        // frozen — the exact S5 shape (a wedged node keeps 9 healthy peers, extends none,
        // and no block arrives from anywhere). The height is compared BETWEEN round starts
        // (not within one round, which lasts milliseconds): blocks land between rounds, so
        // a node whose chain advances at all — by any path — never counts a stalled round.
        long heightAtRoundStart = engine.height();
        int peersTried = 0;
        int peersSkippedBanned = 0;
        boolean progressed = false;
        // Rotate the starting index each round so that if the peers visited first are slow and eat the
        // round budget, the ones skipped this round are visited first next round — every peer gets a turn.
        int start = (int) Math.floorMod(syncRoundCursor++, n);
        long deadline = System.currentTimeMillis() + SYNC_ROUND_BUDGET_MS;
        for (int i = 0; i < n; i++) {
            if (System.currentTimeMillis() >= deadline) {
                log.debug("Sync round budget reached; deferring {} of {} peers to the next round", n - i, n);
                break;
            }
            String peerUrl = peers.get((start + i) % n);
            // Seeds are trusted anchors: they can never be penalized directly (PeerRegistry.penalize
            // exempts them), so a seed seen as banned can only be a COLLATERAL ban of its address —
            // which must not blind the node to its operator-configured anchor (testnet campaign S5).
            if (!registry.isSeed(peerUrl) && registry.isBanned(peerUrl)) {
                peersSkippedBanned++;
                continue;
            }
            peersTried++;
            try {
                ChainSynchronizer.Result result = synchronizer.syncFrom(
                    new HttpPeerSource(peerUrl, blockPrivatePeers, exchange, peerTokenPolicy));
                // Any Result at all means the peer answered well-formed protocol data, so it is
                // a real Rhizome node and from here on it can earn ban score — including for the
                // PEER_INVALID case just below (a node that speaks the protocol and lies IS
                // misbehaving). Only the malformed-data path can still see an unconfirmed peer;
                // see penalize (audit B-3).
                registry.markConfirmed(peerUrl);
                switch (result) {
                    case EXTENDED, REORGED -> {
                        progressed = true;
                        log.info("Synced from {}: {} -> height {}", peerUrl, result, engine.height());
                    }
                    case PEER_INVALID -> penalize(peerUrl, PENALTY_INVALID, "served an invalid chain");
                    case REORG_TOO_DEEP ->
                        // Deliberately NO ban score: a branch past the reorg horizon is not
                        // misbehaviour (the peer cannot help how deep its fork is), and scoring
                        // it locked forked camps into a mutual 1 h ban, renewed hourly — the
                        // permanent split the replay measured (see the constant comment).
                        log.debug("Peer {} is past the reorg horizon (finality); nothing to adopt",
                            peerUrl);
                    case INCOMPATIBLE -> penalize(peerUrl, PENALTY_INCOMPATIBLE, "wrong network / genesis");
                    case PEER_PRUNED ->
                        log.debug("Peer {} pruned the bodies we need; trying another source", peerUrl);
                    case NO_CHANGE -> { /* healthy, nothing to do */ }
                }
            } catch (HttpPeerSource.PeerUnavailableException e) {
                // Transport failures are not misbehaviour; PeerDiscovery prunes the
                // persistently unreachable. Only protocol violations earn ban score.
                log.debug("Peer {} unavailable: {}", peerUrl, e.getMessage());
            } catch (PeerProtocolException e) {
                // Malformed protocol data (junk scalars, absurd snapshot chunk counts) is a
                // protocol violation like serving an invalid chain — penalize accordingly.
                // Caught as the PORT type: any transport adapter speaks this vocabulary.
                penalize(peerUrl, PENALTY_INVALID, "served malformed protocol data");
            } catch (Throwable e) {
                // Every Error is fatal-by-doctrine here: a HostFault is a LOCAL store/infra
                // failure surfaced from contract execution (see HostFault), and a JVM Error
                // (OutOfMemoryError, NoClassDefFoundError, ...) means this node is unhealthy
                // regardless of which peer happened to trigger it. Rethrow so the round aborts
                // and guarded() logs the full stack as an error — the scheduler boundary keeps
                // the sync loop alive. Only exceptions (bad peer data, per-peer handling bugs)
                // are isolated to the peer that caused them.
                if (e instanceof Error err) {
                    throw err;
                }
                log.warn("Sync from {} failed: {}", peerUrl, e.toString());
            }
        }
        publishRoundOutcome(heightAtRoundStart, n, peersTried, peersSkippedBanned, progressed);
    }

    /**
     * The round's verdict, observable before the next one starts: a healthy-but-idle round is
     * indistinguishable from a wedged one by height alone (S5: 12 min, 9 healthy peers, zero log
     * lines). Publishes the counters so /stats surfaces the difference in seconds, and says it
     * out loud when a round is doing nothing by construction. Called on EVERY round, the
     * no-peer-at-all one included — that path used to return early and publish nothing.
     */
    private void publishRoundOutcome(long heightAtRoundStart, int peersKnown, int peersTried,
                                     int peersSkippedBanned, boolean progressed) {
        if (progressed || heightAtRoundStart != lastObservedHeight) {
            roundsWithoutProgress = 0;
        } else {
            roundsWithoutProgress++;
        }
        lastObservedHeight = heightAtRoundStart;
        // Eclipsed = this round had no usable sync source at all: either the registry is empty
        // (bans evict, so this is the common shape) or every peer in it was skipped as banned.
        boolean eclipsed = peersTried == 0 && (peersKnown == 0 || peersSkippedBanned == peersKnown);
        service.recordSyncRound(peersKnown, peersTried, peersSkippedBanned,
            roundsWithoutProgress, eclipsed);
        if (eclipsed) {
            if (!eclipsedReported) {
                log.warn("sync eclipsed: no usable sync source this round ({} known peer(s), {} skipped "
                    + "as banned), so nothing can catch up until a ban expires or a peer is discovered",
                    peersKnown, peersSkippedBanned);
                eclipsedReported = true;
            } else if (roundsWithoutProgress > 0 && roundsWithoutProgress % PROGRESS_WARN_ROUNDS == 0) {
                log.warn("sync still eclipsed after {} stalled round(s): {} known peer(s), {} skipped "
                    + "as banned; nothing can catch up", roundsWithoutProgress, peersKnown, peersSkippedBanned);
            }
        } else {
            eclipsedReported = false;
        }
        if (!eclipsed && roundsWithoutProgress > 0
                && roundsWithoutProgress % PROGRESS_WARN_ROUNDS == 0) {
            log.warn("no sync progress and no height advance for {} rounds (~{} s): {} peer(s) tried "
                + "this round, {} peer(s) skipped as banned",
                roundsWithoutProgress, roundsWithoutProgress * syncPeriodMs / 1000,
                peersTried, peersSkippedBanned);
        }
    }

    /**
     * Applies ban score for misbehaviour — but only to a peer that has proven it speaks the
     * protocol. {@code /add_peer} is unauthenticated on an open node, so an attacker could point
     * us at any public host: a plain web server answering 200 to everything raises
     * PeerProtocolException, which used to be worth an immediate ban of the VICTIM's resolved IP
     * (100 points = the threshold), renewable for as long as the attacker kept re-adding it — a
     * remote blocklisting primitive that would also refuse the victim's honest node later
     * (audit B-3). An unconfirmed host is treated as what it is, a wrong address: dropped from
     * the registry, which also arms the 5-minute host re-admission cooldown.
     */
    private void penalize(String peerUrl, int points, String reason) {
        if (!registry.isConfirmed(peerUrl)) {
            registry.remove(peerUrl);
            log.debug("Dropped unconfirmed peer {} ({}) — not a protocol-speaking node, not banned",
                peerUrl, reason);
            return;
        }
        if (registry.penalize(peerUrl, points)) {
            log.warn("Banned peer {} ({})", peerUrl, reason);
        } else {
            log.debug("Penalized peer {} +{} ({})", peerUrl, points, reason);
        }
    }
}
