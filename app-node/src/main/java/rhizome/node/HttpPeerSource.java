package rhizome.node;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

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

    private final String baseUrl;
    private final HttpClient client;

    public HttpPeerSource(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public long height() {
        return Long.parseLong(getString("/block_count").trim());
    }

    @Override
    public BigInteger totalWork() {
        return new BigInteger(new JSONObject(getString("/total_work")).getString("totalWork"));
    }

    @Override
    public SHA256Hash blockHash(long height) {
        JSONObject json = new JSONObject(getString("/block?blockId=" + height));
        return Block.of(json).hash();
    }

    @Override
    public List<Block> blocks(long start, long end) {
        return BlockCodec.decodeAll(getBytes("/sync?start=" + start + "&end=" + end));
    }

    private String getString(String path) {
        return send(path, HttpResponse.BodyHandlers.ofString());
    }

    private byte[] getBytes(String path) {
        return send(path, HttpResponse.BodyHandlers.ofByteArray());
    }

    private <T> T send(String path, HttpResponse.BodyHandler<T> handler) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        try {
            HttpResponse<T> response = client.send(request, handler);
            if (response.statusCode() != 200) {
                throw new IOException("peer " + path + " returned " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new PeerUnavailableException("peer request failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeerUnavailableException("interrupted: " + path, e);
        }
    }

    /** Signals a transport-level failure talking to the peer (distinct from a bad chain). */
    public static final class PeerUnavailableException extends RuntimeException {
        public PeerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
