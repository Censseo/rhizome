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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    private final PeerRegistry registry;
    private final String selfUrl;
    private final boolean blockPrivateHosts;
    private final HttpClient http;
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(ROUND_CONCURRENCY, r -> {
        Thread t = new Thread(r, "rhizome-pex");
        t.setDaemon(true);
        return t;
    });
    /** Rotates the round's peer order so a tail cut by the budget is visited first next round. */
    private long roundCursor;

    public PeerDiscovery(PeerRegistry registry, String selfUrl) {
        this(registry, selfUrl, false);
    }

    public PeerDiscovery(PeerRegistry registry, String selfUrl, boolean blockPrivateHosts) {
        this.registry = registry;
        this.selfUrl = selfUrl;
        this.blockPrivateHosts = blockPrivateHosts;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /**
     * One discovery round across all known peers, fanned out over a bounded pool with an overall
     * deadline. Each peer is pinned once and its PEX fetch + self-announce run together; a peer cut by
     * the round deadline is not counted as a failure (it is retried next round, rotated to the front).
     */
    public void round() {
        List<String> peers = registry.snapshot();
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
            registry.addAll(fetchPeersPinned(pinned));
            announceToPinned(pinned);
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
        return fetchPeersPinned(PeerHosts.pin(peer, blockPrivateHosts));
    }

    private List<String> fetchPeersPinned(String pinned) throws Exception {
        // Stream + bound the body: never buffer an unbounded response into memory (audit V2). The
        // MAX_PEX_PER_PEER limit below only caps how many entries we KEEP — it cannot stop an
        // attacker's giant body, which ofString() would have fully materialised before we ever parse.
        HttpResponse<InputStream> resp = http.send(
            HttpRequest.newBuilder(URI.create(pinned + "/peers")).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IllegalStateException("/peers -> " + resp.statusCode());
        }
        String body;
        try (InputStream in = resp.body()) {
            body = new String(readBounded(in, MAX_PEERS_BODY_BYTES), StandardCharsets.UTF_8);
        }
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

    private void announceToPinned(String pinned) throws Exception {
        if (selfUrl == null || selfUrl.isEmpty()) {
            return;
        }
        String body = new JSONObject().put("url", selfUrl).toString();
        http.send(
            HttpRequest.newBuilder(URI.create(pinned + "/add_peer"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.discarding());
    }
}
