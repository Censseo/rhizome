package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The peer source must never buffer an unbounded body from a hostile peer. */
class HttpPeerSourceTest {

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

    private void respond(String path, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @Test
    void oversizedTotalWorkIsRejectedNotParsed() {
        // A malicious peer returns a multi-megabyte "totalWork" string; without the
        // cap this would be an O(n^2) BigInteger-parse DoS. It must be refused before
        // parsing.
        byte[] huge = new byte[8 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) '9');
        respond("/total_work", huge);

        var source = new HttpPeerSource(baseUrl);
        assertThrows(HttpPeerSource.PeerUnavailableException.class, source::totalWork);
    }

    @Test
    void normalScalarResponseStillWorks() {
        respond("/block_count", "42".getBytes(StandardCharsets.UTF_8));
        var source = new HttpPeerSource(baseUrl);
        assertEquals(42L, source.height());
    }
}
