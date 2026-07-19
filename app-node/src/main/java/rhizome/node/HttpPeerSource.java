package rhizome.node;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import rhizome.core.common.Constants;

import org.json.JSONObject;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.crypto.SHA256Hash;

/**
 * {@link PeerSource} over a peer's HTTP node API, using the blocking JDK client
 * (the synchronizer runs on its own thread, so blocking I/O is fine and keeps
 * this free of the eventloop — and native-image friendly).
 */
public final class HttpPeerSource implements PeerSource {

    // Per-endpoint response caps: a hostile peer must never be able to hand us an
    // unbounded body. The scalar endpoints are tiny (a height, a ~78-digit work
    // value) — capping them small also kills the O(n^2) BigInteger-parse DoS on a
    // giant /total_work string. The block stream is bounded by what a well-behaved
    // peer can return for one fetch window.
    private static final int SCALAR_CAP = 4 * 1024;
    private static final int JSON_BLOCK_CAP = 1024 * 1024;
    // Upper bound for one /sync window: the consensus maxima (BLOCKS_PER_FETCH full
    // blocks, each a header plus MAX_TRANSACTIONS_PER_BLOCK transactions). Derived
    // from the fixed DTO layouts so a valid response is never rejected, yet the
    // buffered size is finite instead of whatever a hostile peer chooses to send.
    private static final long BLOCK_STREAM_CAP =
        (long) Constants.BLOCKS_PER_FETCH * Constants.MAX_BLOCK_SIZE_BYTES + 64 * 1024;

    private final String baseUrl;
    private final HttpClient client;

    public HttpPeerSource(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public long height() {
        return Long.parseLong(getString("/block_count", SCALAR_CAP).trim());
    }

    @Override
    public BigInteger totalWork() {
        return new BigInteger(new JSONObject(getString("/total_work", SCALAR_CAP)).getString("totalWork"));
    }

    @Override
    public SHA256Hash blockHash(long height) {
        JSONObject json = new JSONObject(getString("/block?blockId=" + height, JSON_BLOCK_CAP));
        return Block.of(json).hash();
    }

    @Override
    public List<Block> blocks(long start, long end) {
        return BlockCodec.decodeAll(getBytes("/sync?start=" + start + "&end=" + end, BLOCK_STREAM_CAP));
    }

    private String getString(String path, long maxBytes) {
        return new String(getBytes(path, maxBytes), StandardCharsets.UTF_8);
    }

    private byte[] getBytes(String path, long maxBytes) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("peer " + path + " returned " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                return readBounded(in, maxBytes, path);
            }
        } catch (IOException e) {
            throw new PeerUnavailableException("peer request failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeerUnavailableException("interrupted: " + path, e);
        }
    }

    /** Reads the stream, aborting if it would exceed {@code maxBytes} (never buffers past the cap). */
    private static byte[] readBounded(InputStream in, long maxBytes, String path) throws IOException {
        // One byte over the cap is fetched to distinguish "exactly at cap" from "over".
        byte[] data = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
        if (data.length > maxBytes) {
            throw new IOException("peer " + path + " response exceeds " + maxBytes + " bytes");
        }
        return data;
    }

    /** Signals a transport-level failure talking to the peer (distinct from a bad chain). */
    public static final class PeerUnavailableException extends RuntimeException {
        public PeerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
