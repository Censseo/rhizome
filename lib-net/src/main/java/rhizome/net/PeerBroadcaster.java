package rhizome.net;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.atomic.AtomicReference;
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
    /** Wall-clock deadline for one whole gossip send (send + full reply-body read): the request
     *  timeout alone ends at the response headers, so a slow-drip peer could otherwise park a
     *  broadcast pool thread in {@code InputStream.read} until the fixed gossip pool starves
     *  (audit F1/F2 pattern). */
    private static final Duration SEND_DEADLINE = Duration.ofSeconds(10);
    /** Cap on the reply body: /submit and /add_transaction answer a tiny JSON status, so
     *  anything larger is a hostile drip, not a peer response worth reading. */
    private static final long MAX_REPLY_BYTES = 64 * 1024;

    private final Supplier<Collection<String>> peers;
    private final boolean blockPrivateHosts;
    private final HttpClient http;
    private final ExecutorService pool;
    /** Decides whether the RHIZOME_PEER_TOKEN secret may be presented to a given peer
     *  (configured + https only); never logged (audit: peer token exfiltration via gossip). */
    private final PeerTokenPolicy tokenPolicy;
    private final Set<String> recentlySent = Collections.newSetFromMap(
        Collections.synchronizedMap(new LinkedHashMap<>(DEDUP_WINDOW, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > DEDUP_WINDOW;
            }
        }));

    /** {@code peers} is queried on each broadcast, so it can reflect a live peer set. */
    public PeerBroadcaster(Supplier<Collection<String>> peers, boolean blockPrivateHosts) {
        this(peers, blockPrivateHosts, (String) null);
    }

    /**
     * As above, presenting {@code peerToken} (nullable) as a bearer token on outbound POSTs.
     *
     * @deprecated presents the token to EVERY peer in the (unauthenticated, gossip-fed)
     *     registry, over any scheme — any peer that got itself added, often over cleartext
     *     http://, receives the shared secret. Use the {@link PeerTokenPolicy} constructor so
     *     the token only goes to explicitly configured peers over https.
     */
    @Deprecated
    public PeerBroadcaster(Supplier<Collection<String>> peers, boolean blockPrivateHosts, String peerToken) {
        this(peers, blockPrivateHosts, PeerTokenPolicy.trustAll(peerToken));
    }

    /** As above, presenting the token only to peers {@code tokenPolicy} trusts. */
    public PeerBroadcaster(Supplier<Collection<String>> peers, boolean blockPrivateHosts,
                           PeerTokenPolicy tokenPolicy) {
        this.peers = peers;
        this.blockPrivateHosts = blockPrivateHosts;
        this.tokenPolicy = tokenPolicy;
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
        // Encode on the caller, BEFORE anything is queued. Two reasons (audit review):
        //  - the bytes gossiped must be exactly those of the accepted block: BlockImpl is
        //    mutable, so deferring the encode to a pooled task would only be correct by the
        //    convention that no caller mutates afterwards;
        //  - the pool's DiscardOldestPolicy may drop a queued task: if the encode+fan-out were
        //    one pooled task, dropping it would lose the block's propagation to EVERY peer,
        //    and with the dedup id already recorded above it would never be re-gossiped within
        //    the window. With the encode done here, a discard only ever costs ONE peer send.
        post("/submit", BlockCodec.encode(block));
    }

    public void broadcastTransaction(Transaction transaction) {
        if (!firstSeen("t:" + transaction.hashContents().toHexString())) {
            return;
        }
        // Same reasoning as broadcastBlock: encode on the caller so a discarded task only ever
        // costs one peer send, never the whole fan-out.
        post("/add_transaction", transaction.serialize().toBuffer());
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
        HttpRequest request = PeerAuth.withToken(HttpRequest.newBuilder(URI.create(url)),
                tokenPolicy.tokenFor(peer))
            .timeout(SEND_DEADLINE)
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        try {
            // Whole-exchange deadline (BodyReadDeadline, audit F1/F2): with discarding() the
            // reply body is still read AFTER the request timeout stops applying, so a slow-drip
            // peer could hold a broadcast pool thread indefinitely.
            AtomicReference<AutoCloseable> openBody = new AtomicReference<>();
            BodyReadDeadline.call(SEND_DEADLINE, openBody, () -> {
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                InputStream in = response.body();
                openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
                try (in) {
                    readBounded(in, MAX_REPLY_BYTES);
                }
                return null;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("broadcast to {} failed: {}", url, e.toString());
        }
    }

    /** Reads the stream, aborting if it would exceed {@code maxBytes} (never buffers past the cap). */
    private static void readBounded(InputStream in, long maxBytes) throws IOException {
        // One byte over the cap is fetched to distinguish "exactly at cap" from "over".
        byte[] data = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
        if (data.length > maxBytes) {
            throw new IOException("broadcast reply exceeds " + maxBytes + " bytes");
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
