package rhizome.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Peer discovery must never buffer an unbounded {@code /peers} body from a hostile peer (audit V2).
 * {@code fetchPeers} runs automatically against every known peer each round, so a multi-GB response
 * would OOM the node; the read must be size-capped exactly like {@link HttpPeerSource}.
 */
class PeerDiscoveryBodyBoundTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(byte[] body) {
        server.createContext("/peers", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @Test
    void oversizedPeersBodyIsRejectedNotBuffered() {
        // 1 MiB of junk — far over the 64 KiB cap. Before the fix this went through
        // BodyHandlers.ofString(), which buffers the whole body (here, and a multi-GB body all the
        // same) before MAX_PEX_PER_PEER ever applies. It must now abort on the size cap.
        byte[] huge = new byte[1024 * 1024];
        java.util.Arrays.fill(huge, (byte) '9');
        respond(huge);

        var discovery = new PeerDiscovery(new PeerRegistry(null, 128), baseUrl);
        var ex = assertThrows(Exception.class, () -> discovery.fetchPeers(baseUrl));
        assertTrue(String.valueOf(ex.getMessage()).contains("exceeds"),
            "expected a size-cap rejection, got: " + ex.getMessage());
    }

    @Test
    void normalPeersBodyStillParses() {
        respond("{\"peers\":[\"http://10.1.2.3:3000\",\"http://10.1.2.4:3000\"]}"
            .getBytes(StandardCharsets.UTF_8));
        var discovery = new PeerDiscovery(new PeerRegistry(null, 128), baseUrl);
        List<String> peers = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> discovery.fetchPeers(baseUrl));
        assertEquals(2, peers.size());
        assertTrue(peers.contains("http://10.1.2.3:3000"));
    }
}
