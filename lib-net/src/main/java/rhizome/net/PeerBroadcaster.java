package rhizome.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.transaction.Transaction;

/**
 * Pushes newly accepted blocks and transactions to peers (active gossip), so the
 * network converges immediately instead of only via periodic pull sync.
 *
 * <p>Fire-and-forget and best-effort: each peer send is independent and its
 * failure is isolated. Re-broadcast loops terminate naturally because a peer
 * that already has an item rejects it (non-SUCCESS) and therefore does not
 * gossip it onward.
 */
public final class PeerBroadcaster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PeerBroadcaster.class);

    /** Bounded send backlog: past this, the oldest queued send is dropped (gossip is best-effort,
     *  and unbounded accumulation behind slow peers is a memory-exhaustion vector — audit M3). */
    private static final int MAX_QUEUED_SENDS = 256;
    /** Recently broadcast item ids, so an item arriving via several paths is not re-fanned repeatedly. */
    private static final int DEDUP_WINDOW = 2048;

    private final Supplier<Collection<String>> peers;
    private final boolean blockPrivateHosts;
    private final HttpClient http;
    private final ExecutorService pool;
    private final Set<String> recentlySent = Collections.newSetFromMap(
        Collections.synchronizedMap(new LinkedHashMap<>(DEDUP_WINDOW, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > DEDUP_WINDOW;
            }
        }));

    /** {@code peers} is queried on each broadcast, so it can reflect a live peer set. */
    public PeerBroadcaster(Supplier<Collection<String>> peers, boolean blockPrivateHosts) {
        this.peers = peers;
        this.blockPrivateHosts = blockPrivateHosts;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        // Bounded queue + discard-oldest: newest blocks/txs win, memory stays capped even if
        // several peers are slow/unresponsive. Each task holds one item body reference.
        this.pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_SENDS),
            r -> {
                Thread t = new Thread(r, "peer-broadcast");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    public void broadcastBlock(Block block) {
        if (!firstSeen("b:" + block.hash().toHexString())) {
            return;
        }
        byte[] body = BlockCodec.encode(block);
        post("/submit", body);
    }

    public void broadcastTransaction(Transaction transaction) {
        if (!firstSeen("t:" + transaction.hashContents().toHexString())) {
            return;
        }
        byte[] body = transaction.serialize().toBuffer();
        post("/add_transaction", body);
    }

    /** True the first time an item id is seen within the dedup window (adds it as a side effect). */
    private boolean firstSeen(String id) {
        return recentlySent.add(id);
    }

    private void post(String path, byte[] body) {
        for (String peer : peers.get()) {
            pool.execute(() -> sendQuietly(peer, path, body));
        }
    }

    private void sendQuietly(String peer, String path, byte[] body) {
        // Pin the peer to a freshly-resolved, validated IP before every send, exactly as the
        // sync/PEX paths do (PeerHosts.pin). Without this, gossip re-resolved the hostname at
        // send time, so a peer admitted with a public IP could flip DNS to 127.0.0.1 /
        // 169.254.169.254 / an RFC1918 host and receive our POSTs — a blind SSRF (audit M1).
        String url;
        try {
            url = PeerHosts.pin(peer, blockPrivateHosts) + path;
        } catch (SecurityException e) {
            log.debug("broadcast to {} refused (non-routable / rebind): {}", peer, e.toString());
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        try {
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("broadcast to {} failed: {}", url, e.toString());
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
