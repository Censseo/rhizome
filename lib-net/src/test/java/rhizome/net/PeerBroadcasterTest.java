package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.crypto.SHA256Hash;

/**
 * The gossip backlog is bounded in BYTES as well as in task count: a queue of 256 tasks each
 * retaining a full block body (up to 4 MiB) would pin ~1 GiB behind a slow drain (audit M3
 * follow-up). Over-budget sends are dropped — gossip is best-effort — and every charged send
 * releases its budget exactly once (on completion or on queue rejection).
 *
 * <p>Determinism: the server runs on a multi-threaded executor and every accepted send stalls
 * inside the handler until {@link #release} fires in tearDown, so an admitted send holds its
 * byte charge for the whole assertion window and no worker can drain the queue mid-test. The
 * exact-count assertions additionally gate on {@link #arrived} — proof that all 4 pool workers
 * are occupied — so they never depend on task-uptake timing.
 */
class PeerBroadcasterTest {

    private HttpServer server;
    private java.util.concurrent.ExecutorService serverExecutor;
    private String peer;
    private final CountDownLatch release = new CountDownLatch(1);
    /** Counted down by the handler per in-flight send: proves the 4 pool workers are occupied. */
    private final CountDownLatch arrived = new CountDownLatch(4);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The default server executor is single-threaded: only ONE stalled send would occupy the
        // handler while the other workers queued on connect — fine for blocking, but the arrived
        // latch needs every worker's send to actually ENTER the handler.
        serverExecutor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "broadcaster-test-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(serverExecutor);
        // Accepts but stalls until released, so admitted sends stay charged against the budget.
        server.createContext("/submit", exchange -> {
            arrived.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
        peer = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private static rhizome.core.block.Block block(int id) {
        return BlockImpl.builder()
            .id(id).timestamp(123456789L).difficulty(4)
            .merkleRoot(SHA256Hash.random())
            .lastBlockHash(SHA256Hash.random())
            .nonce(SHA256Hash.random())
            .build();
    }

    @Test
    void gossipBacklogIsBoundedInBytes() {
        var block = block(7);
        long bodySize = BlockCodec.encode(block).length;
        long budget = bodySize * 2 + bodySize / 2; // admits exactly 2 sends of this body
        try (var broadcaster = new PeerBroadcaster(() -> Collections.nCopies(10, peer), false,
                PeerTokenPolicy.none(), budget)) {
            broadcaster.broadcastBlock(block); // the fan-out loop runs on the caller
            // Both counters are charged/dropped on the CALLER thread inside post(), so the
            // exact counts are scheduling-independent; admitted sends cannot release early
            // because the server stalls every send until tearDown.
            assertEquals(8, broadcaster.droppedSends(), "over-budget sends must be dropped");
            assertEquals(2 * bodySize, broadcaster.queuedBytes(),
                "only the admitted sends may hold budget");
        }
    }

    @Test
    void countFullQueueDropsNewestAndReclaimsItsBytes() throws Exception {
        // 4 pool threads (blocked on the stalled peer) + 256 queue slots = 260 admitted sends;
        // the rest are rejected by the handler, which must reclaim their byte charge.
        var block = block(8);
        long bodySize = BlockCodec.encode(block).length;
        try (var broadcaster = new PeerBroadcaster(() -> Collections.nCopies(300, peer), false,
                PeerTokenPolicy.none(), Long.MAX_VALUE)) {
            broadcaster.broadcastBlock(block);
            // Gate on all 4 workers being occupied by a stalled send: from here on no worker can
            // take a queued task, so the queue contents — and thus the byte charge — are frozen.
            assertTrue(arrived.await(10, TimeUnit.SECONDS), "all 4 pool workers occupied");
            assertEquals(40, broadcaster.droppedSends(), "past 4+256 slots the new send is dropped");
            assertEquals(260 * bodySize, broadcaster.queuedBytes(),
                "exactly the admitted 4 running + 256 queued sends hold budget; "
                    + "rejected sends must not leak their byte charge");
        }
    }
}
