package rhizome.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
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
    private final HttpClient http;
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
        this(registry, selfUrl, blockPrivateHosts, FETCH_DEADLINE, PeerTokenPolicy.trustAll(null));
    }

    /**
     * As above, presenting {@code peerToken} (nullable) as a bearer token on outbound requests.
     *
     * @deprecated presents the token to EVERY registry peer, over any scheme — the registry is
     *     fed by unauthenticated /add_peer + PEX, so any gossip-learned (often http://) peer
     *     receives the shared secret in cleartext. Use the {@link PeerTokenPolicy} constructor
     *     so the token only goes to explicitly configured peers over https.
     */
    @Deprecated
    public PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts, String peerToken) {
        this(registry, selfUrl, blockPrivateHosts, PeerTokenPolicy.trustAll(peerToken));
    }

    /** As above, presenting the token only to peers {@code tokenPolicy} trusts. */
    public PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts,
                         PeerTokenPolicy tokenPolicy) {
        this(registry, selfUrl, blockPrivateHosts, FETCH_DEADLINE, tokenPolicy);
    }

    /** As above, with an explicit per-exchange deadline (package-private for tests). */
    PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts, Duration fetchDeadline) {
        this(registry, selfUrl, blockPrivateHosts, fetchDeadline, PeerTokenPolicy.trustAll(null));
    }

    /** As above, with an explicit per-exchange deadline and a token policy. */
    PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts, Duration fetchDeadline,
                  PeerTokenPolicy tokenPolicy) {
        this.registry = registry;
        this.selfUrl = selfUrl;
        this.blockPrivateHosts = blockPrivateHosts;
        this.fetchDeadline = fetchDeadline;
        this.tokenPolicy = tokenPolicy;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
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
            // Pin once and reuse for both the /peers fetch and the /add_peer announce, instead of
            // resolving the host twice per peer per round.
            String pinned = PeerHosts.pin(peer, blockPrivateHosts);
            registry.addAll(fetchPeersPinned(pinned, peer));
            announceToPinned(pinned, peer);
            failures.remove(peer);
        } catch (InterruptedException e) {
            // Cut by the round deadline (invokeAll cancelled the task) — not the peer's fault, so it is
            // not penalised; it will be retried, rotated to the front, next round.
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            int f = failures.merge(peer, 1, Integer::sum);
            if (f >= MAX_FAILURES) {
                registry.remove(peer);
                failures.remove(peer);
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
        // Pin the peer to its resolved IP (and refuse non-routable hosts on mainnet) so a DNS
        // rebind cannot point this fetch at an internal service (SSRF).
        return fetchPeersPinned(PeerHosts.pin(peer, blockPrivateHosts), peer);
    }

    private List<String> fetchPeersPinned(String pinned, String originalPeer) throws Exception {
        // Stream + bound the body: never buffer an unbounded response into memory (audit V2). The
        // MAX_PEX_PER_PEER limit below only caps how many entries we KEEP — it cannot stop an
        // attacker's giant body, which ofString() would have fully materialised before we ever parse.
        // The whole exchange (send + bounded read) runs under a wall-clock deadline (audit F2):
        // the request timeout alone only covers up to the response headers, so a slow-drip peer
        // could otherwise park a pool thread in InputStream.read until the round budget cut it.
        AtomicReference<AutoCloseable> openBody = new AtomicReference<>();
        String body = BodyReadDeadline.call(fetchDeadline, openBody, () -> {
            HttpResponse<InputStream> resp = http.send(
                PeerAuth.withToken(HttpRequest.newBuilder(URI.create(pinned + "/peers")),
                        tokenPolicy.tokenFor(originalPeer))
                    .timeout(fetchDeadline).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                resp.body().close();
                throw new IllegalStateException("/peers -> " + resp.statusCode());
            }
            InputStream in = resp.body();
            openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
            try (in) {
                return new String(readBounded(in, MAX_PEERS_BODY_BYTES), StandardCharsets.UTF_8);
            }
        });
        JSONArray arr = new JSONObject(body).getJSONArray("peers");
        // Bound how many addresses one peer can contribute per round, so a single malicious
        // peer cannot flood the registry with sybil URLs (PEX amplification / eclipse).
        return arr.toList().stream().map(Object::toString).limit(MAX_PEX_PER_PEER).toList();
    }

    /** Reads the stream, aborting if it would exceed {@code maxBytes} (never buffers past the cap). */
    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        // One byte over the cap is fetched to distinguish "exactly at cap" from "over".
        byte[] data = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
        if (data.length > maxBytes) {
            throw new IOException("/peers response exceeds " + maxBytes + " bytes");
        }
        return data;
    }

    private void announceToPinned(String pinned, String originalPeer) throws Exception {
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
            HttpResponse<InputStream> resp = http.send(
                PeerAuth.withToken(HttpRequest.newBuilder(URI.create(pinned + "/add_peer")),
                        tokenPolicy.tokenFor(originalPeer))
                    .timeout(fetchDeadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            InputStream in = resp.body();
            openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
            try (in) {
                readBounded(in, MAX_PEERS_BODY_BYTES);
            }
            return null;
        });
    }
}
