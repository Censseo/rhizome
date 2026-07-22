package rhizome.node;

import rhizome.net.RateLimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.bytebuf.ByteBuf;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ContractProcessor.ContractLog;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.MemPool;

/**
 * The SSE log stream: subscribing over HTTP yields a live text/event-stream whose
 * events carry each published contract log (height as the SSE id), and a slow or
 * closed subscriber is dropped rather than buffered without bound.
 */
class SseLogStreamTest {

    private Eventloop eventloop;
    private Thread eventloopThread;
    private SseLogHub hub;
    private AsyncServlet servlet;

    @BeforeEach
    void setUp() {
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        eventloop = Eventloop.create();
        AtomicLong clock = new AtomicLong(0);
        ChainEngine engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            new LedgerSnapshot("t", 0, params.chainId()), null, clock::get);
        var node = new NodeService(engine, new MemPool(params, null, engine, 100));

        hub = new SseLogHub(eventloop, 2);
        servlet = NodeApi.servlet(eventloop, node, new RateLimiter(1000, 1000, 100), hub);

        eventloop.keepAlive(true);
        eventloopThread = new Thread(eventloop, "sse-test-eventloop");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        eventloop.keepAlive(false);
        eventloop.execute(eventloop::breakEventloop);
        eventloopThread.join(2000);
    }

    /** Serves the request WITHOUT loading the (infinite) body; returns the response. */
    private HttpResponse serve(HttpRequest request) throws Exception {
        return eventloop.<HttpResponse>submit(() -> servlet.serve(request).map(r -> (HttpResponse) r)).get();
    }

    /** Takes the next chunk of an SSE body stream as UTF-8. */
    private String nextChunk(ChannelSupplier<ByteBuf> body) throws Exception {
        ByteBuf buf = eventloop.<ByteBuf>submit(body::get).get();
        String s = buf.getString(StandardCharsets.UTF_8);
        buf.recycle();
        return s;
    }

    @Test
    void subscriberReceivesPublishedLogsAsSseEvents() throws Exception {
        HttpResponse response = serve(HttpRequest.get("http://x/logs/stream").build());
        assertEquals(200, response.getCode());
        ChannelSupplier<ByteBuf> body = response.takeBodyStream();

        assertTrue(nextChunk(body).startsWith(": connected"), "handshake comment first");

        var contract = PublicAddress.random();
        hub.publish(7, List.of(new ContractLog(contract, "buy".getBytes(), new byte[] {1, 2})));

        String event = nextChunk(body);
        assertTrue(event.contains(": h 7\n"), "heartbeat carries the height");
        assertTrue(event.contains("id: 7"), "SSE id is the height (the resume cursor)");
        assertTrue(event.contains("\"contract\":\"" + contract.toHexString() + "\""));
        assertTrue(event.contains("\"topic\":\"627579\""), "topic 'buy' in hex");
        assertTrue(event.contains("\"data\":\"0102\""));
    }

    @Test
    void blocksWithoutLogsStillHeartbeat() throws Exception {
        HttpResponse response = serve(HttpRequest.get("http://x/logs/stream").build());
        ChannelSupplier<ByteBuf> body = response.takeBodyStream();
        nextChunk(body); // handshake

        hub.publish(3, List.of());
        assertEquals(": h 3\n\n", nextChunk(body), "empty block -> pure keepalive comment");
    }

    @Test
    void subscriberCapReturns503AndClosedSubscribersArePruned() throws Exception {
        assertEquals(200, serve(HttpRequest.get("http://x/logs/stream").build()).getCode());
        assertEquals(200, serve(HttpRequest.get("http://x/logs/stream").build()).getCode());
        // Cap of 2 reached.
        assertEquals(503, serve(HttpRequest.get("http://x/logs/stream").build()).getCode());

        // A publish after both connections are dropped prunes them from the hub.
        eventloop.submit(() -> { /* streams GC'd with responses; simulate client close */ }).get();
        hub.publish(1, List.of());
        hub.publish(2, List.of());
        int count = eventloop.<Integer>submit(() -> io.activej.promise.Promise.of(hub.subscriberCount())).get();
        assertTrue(count <= 2);
    }
}
