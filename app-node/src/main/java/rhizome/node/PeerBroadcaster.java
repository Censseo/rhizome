package rhizome.node;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final Supplier<Collection<String>> peers;
    private final HttpClient http;
    private final ExecutorService pool;

    /** {@code peers} is queried on each broadcast, so it can reflect a live peer set. */
    public PeerBroadcaster(Supplier<Collection<String>> peers) {
        this.peers = peers;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "peer-broadcast");
            t.setDaemon(true);
            return t;
        });
    }

    public void broadcastBlock(Block block) {
        byte[] body = BlockCodec.encode(block);
        post("/submit", body);
    }

    public void broadcastTransaction(Transaction transaction) {
        byte[] body = transaction.serialize().toBuffer();
        post("/add_transaction", body);
    }

    private void post(String path, byte[] body) {
        for (String peer : peers.get()) {
            pool.execute(() -> sendQuietly(peer + path, body));
        }
    }

    private void sendQuietly(String url, byte[] body) {
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
