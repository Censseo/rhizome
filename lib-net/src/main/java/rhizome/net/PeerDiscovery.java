package rhizome.net;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Peer exchange (PEX): periodically asks each known peer for its peer list
 * (merging new ones) and announces this node to them. Peers that fail repeatedly
 * are pruned. This lets the network self-organise from a few seed peers instead
 * of a hard-coded list.
 */
public final class PeerDiscovery {

    private static final Logger log = LoggerFactory.getLogger(PeerDiscovery.class);
    private static final int MAX_FAILURES = 3;
    /** Cap on peers ingested from a single peer's PEX response per round (anti gossip-amplification/eclipse). */
    private static final int MAX_PEX_PER_PEER = 16;
    /**
     * Hard cap on the {@code /peers} response body (bytes). A peer list is at most
     * {@link #MAX_PEX_PER_PEER} URLs, a few KiB; 64 KiB is generous. Without it, a hostile peer could
     * answer with a multi-GB body and OOM this node — {@code fetchPeers} runs automatically against
     * every known peer each round, so it must bound the read exactly like {@code HttpPeerSource} does,
     * rather than buffering the whole body into a String first (audit V2).
     */
    private static final long MAX_PEERS_BODY_BYTES = 64 * 1024;
    /** Peers contacted concurrently per round: the PEX fetches are independent blocking I/O, so a
     *  small pool removes the sequential per-peer stall without opening a connection to every peer at
     *  once (audit net #2). */
    private static final int ROUND_CONCURRENCY = 8;
    /** Wall-clock budget for one round; tasks not finished by then are cancelled and retried next round
     *  (rotation keeps that fair), so one slow peer cannot delay the whole PEX round. */
    private static final long ROUND_BUDGET_MS = 30_000L;
    /** Wall-clock deadline for one whole PEX exchange (send + bounded body read); mirrors the
     *  previous 10s request timeout but also covers the body, so a slow-drip peer cannot park a
     *  pool thread in InputStream.read (audit F2). */
    private static final Duration FETCH_DEADLINE = Duration.ofSeconds(10);

    private final PeerRegistry registry;
    private final String selfUrl;
    private final boolean blockPrivateHosts;
    private final PeerExchange exchange;
    private final Duration fetchDeadline;
    /** Decides whether the RHIZOME_PEER_TOKEN secret may be presented to a given peer
     *  (configured + https only); never logged (audit: peer token exfiltration via gossip). */
    private final PeerTokenPolicy tokenPolicy;
    /** Per-peer consecutive failure counts. Package-private so a test can assert the stale-entry
     *  pruning in {@link #round()} (audit F8). */
    final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final ExecutorService pool;
    /** Rotates the round's peer order so a tail cut by the budget is visited first next round. */
    private long roundCursor;

    public PeerDiscovery(PeerRegistry registry, String selfUrl) {
        this(registry, selfUrl, false);
    }

    public PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts) {
        this(registry, selfUrl, blockPrivateHosts, FETCH_DEADLINE, PeerTokenPolicy.none());
    }

    /** As above, presenting the token only to peers {@code tokenPolicy} trusts. */
    public PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts,
                         PeerTokenPolicy tokenPolicy) {
        this(registry, selfUrl, blockPrivateHosts, FETCH_DEADLINE, tokenPolicy);
    }

    /** As above, sharing the node-wide exchange (one client for sync, PEX and gossip). */
    public PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts,
                         PeerTokenPolicy tokenPolicy, PeerExchange exchange) {
        this(registry, selfUrl, blockPrivateHosts, FETCH_DEADLINE, tokenPolicy, exchange);
    }

    /** As above, with an explicit per-exchange deadline (package-private for tests). */
    PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts, Duration fetchDeadline) {
        this(registry, selfUrl, blockPrivateHosts, fetchDeadline, PeerTokenPolicy.none());
    }

    /** As above, with an explicit per-exchange deadline and a token policy. */
    PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts, Duration fetchDeadline,
                  PeerTokenPolicy tokenPolicy) {
        this(registry, selfUrl, blockPrivateHosts, fetchDeadline, tokenPolicy, new PeerExchange());
    }

    /** As above, with an explicit per-exchange deadline, a token policy and the shared exchange. */
    PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts, Duration fetchDeadline,
                  PeerTokenPolicy tokenPolicy, PeerExchange exchange) {
        this.registry = registry;
        this.selfUrl = selfUrl;
        this.blockPrivateHosts = blockPrivateHosts;
        this.fetchDeadline = fetchDeadline;
        this.tokenPolicy = tokenPolicy;
        this.exchange = exchange;
        // Bounded queue + discard-oldest (the PeerBroadcaster pattern): a fixed pool's default
        // unbounded LinkedBlockingQueue would let one round's tasks accumulate without limit if
        // the workers stall (audit F2). Dropped tasks' futures never complete, so invokeAll
        // simply cancels them at the round budget; rotation visits them first next round.
        this.pool = new ThreadPoolExecutor(ROUND_CONCURRENCY, ROUND_CONCURRENCY, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, registry.maxPeers())),
            r -> {
                Thread t = new Thread(r, "rhizome-pex");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /**
     * One discovery round across all known peers, fanned out over a bounded pool with an overall
     * deadline. Each peer is pinned once and its PEX fetch + self-announce run together; a peer cut by
     * the round deadline is not counted as a failure (it is retried next round, rotated to the front).
     */
    public void round() {
        List<String> peers = registry.snapshot();
        // Drop failure bookkeeping for peers no longer in the registry, so the map cannot leak
        // stale entries across rounds (audit F8).
        failures.keySet().retainAll(new HashSet<>(peers));
        int n = peers.size();
        if (n == 0) {
            return;
        }
        int start = (int) Math.floorMod(roundCursor++, n);
        List<Callable<Void>> tasks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String peer = peers.get((start + i) % n);
            tasks.add(() -> {
                contactPeer(peer);
                return null;
            });
        }
        try {
            pool.invokeAll(tasks, ROUND_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** PEX fetch + self-announce against one peer, with failure bookkeeping. */
    private void contactPeer(String peer) {
        try {
            registry.addAll(fetchPeers(peer));
            announceTo(peer);
            failures.remove(peer);
        } catch (InterruptedException e) {
            // Cut by the round deadline (invokeAll cancelled the task) — not the peer's fault, so it is
            // not penalised; it will be retried, rotated to the front, next round.
            Thread.currentThread().interrupt();
        } catch (BodyReadSaturatedException e) {
            // LOCAL body-read pool saturation: the exchange was rejected before any I/O reached the
            // peer, so it says nothing about the peer — no failure count, no eviction. It is plain
            // backpressure: the peer is retried on a later round.
            log.debug("PEX with {} skipped: local body-read pool saturated", peer);
        } catch (Exception e) {
            int f = failures.merge(peer, 1, Integer::sum);
            if (f >= MAX_FAILURES) {
                failures.remove(peer);
                if (registry.isSeed(peer)) {
                    // A seed is an operator-configured connectivity anchor: it may accumulate
                    // failure counts (observability) but is NEVER evicted by the PEX failure
                    // path — otherwise an attacker that can briefly DoS the seeds could
                    // unanchor the node (mirrors penalize()'s seed exemption, audit M4).
                    log.debug("seed {} unreachable; keeping the trusted anchor", peer);
                    return;
                }
                registry.remove(peer);
                log.debug("dropped unreachable peer {}", peer);
            }
        }
    }

    /** Shuts the round pool down (daemon threads, so this is best-effort cleanup on node close). */
    public void close() {
        pool.shutdownNow();
    }

    // Package-private (not private) so a regression test can assert the body cap directly.
    List<String> fetchPeers(String peer) throws Exception {
        return fetchPeersPinned(peer);
    }

    private List<String> fetchPeersPinned(String peer) throws Exception {
        // Pin the peer to its resolved IP (and refuse non-routable hosts on mainnet) so a DNS
        // rebind cannot point this fetch at an internal service (SSRF) — PeerExchange
        // .pinnedRequest applies the same pin+token sequence every peer exchange uses, and the
        // resolution is cached, so the fetch and the announce below cost one resolver round-trip
        // per minute, not two per contact.
        // Stream + bound the body: never buffer an unbounded response into memory (audit V2). The
        // MAX_PEX_PER_PEER limit below only caps how many entries we KEEP — it cannot stop an
        // attacker's giant body, which ofString() would have fully materialised before we ever parse.
        // The whole exchange (send + bounded read) runs under a wall-clock deadline (audit F2):
        // the request timeout alone only covers up to the response headers, so a slow-drip peer
        // could otherwise park a pool thread in InputStream.read until the round budget cut it.
        AtomicReference<AutoCloseable> openBody = new AtomicReference<>();
        String body = BodyReadDeadline.call(fetchDeadline, openBody, () -> {
            HttpResponse<InputStream> resp = exchange.client().send(
                PeerExchange.pinnedRequest(peer, "/peers", blockPrivateHosts, tokenPolicy, peer)
                    .timeout(fetchDeadline).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                resp.body().close();
                throw new IllegalStateException("/peers -> " + resp.statusCode());
            }
            InputStream in = resp.body();
            openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
            try (in) {
                return new String(PeerExchange.readBounded(in, MAX_PEERS_BODY_BYTES, "/peers response"),
                    StandardCharsets.UTF_8);
            }
        });
        // Depth-bounded parse (audit F11): an over-deep /peers body fails the round as an
        // ordinary peer error instead of killing the pool thread with a StackOverflowError.
        JSONArray arr = PeerJson.parseObject(body).getJSONArray("peers");
        // Bound how many addresses one peer can contribute per round, so a single malicious
        // peer cannot flood the registry with sybil URLs (PEX amplification / eclipse).
        return arr.toList().stream().map(Object::toString).limit(MAX_PEX_PER_PEER).toList();
    }

    private void announceTo(String peer) throws Exception {
        if (selfUrl == null || selfUrl.isEmpty()) {
            return;
        }
        String body = new JSONObject().put("url", selfUrl).toString();
        // Same whole-exchange deadline as the PEX fetch above (audit F2/F6): the announce POST's
        // response body is discarded, but HttpRequest.timeout alone only covers up to the response
        // headers — a slow-drip peer answering /add_peer could otherwise park a pool thread inside
        // the body read until the round budget cut it. The body is also bounded: the endpoint's
        // reply is a tiny JSON status, so anything large is a hostile drip, not a peer to keep.
        AtomicReference<AutoCloseable> openBody = new AtomicReference<>();
        BodyReadDeadline.call(fetchDeadline, openBody, () -> {
            HttpResponse<InputStream> resp = exchange.client().send(
                PeerExchange.pinnedRequest(peer, "/add_peer", blockPrivateHosts, tokenPolicy, peer)
                    .timeout(fetchDeadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            InputStream in = resp.body();
            openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
            try (in) {
                PeerExchange.readBounded(in, MAX_PEERS_BODY_BYTES, "/add_peer response");
            }
            return null;
        });
    }
}
