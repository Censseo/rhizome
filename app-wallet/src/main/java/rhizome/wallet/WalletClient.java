package rhizome.wallet;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.json.JSONObject;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;

/** Talks to a node's HTTP API on the wallet's behalf (query state, submit transfers). */
public final class WalletClient {

    private final String baseUrl;
    private final HttpClient http;

    public WalletClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public record WalletInfo(long balance, long nextNonce) {}

    public int chainId() {
        return new JSONObject(get("/info")).getInt("chainId");
    }

    public WalletInfo walletInfo(PublicAddress address) {
        JSONObject json = new JSONObject(get("/wallet?address=" + address.toHexString()));
        return new WalletInfo(json.getLong("balance"), json.getLong("nextNonce"));
    }

    /** Submits a signed transaction; returns the node's status string (SUCCESS or a rejection). */
    public String submit(Transaction transaction) {
        byte[] body = transaction.serialize().toBuffer();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/add_transaction"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        return new JSONObject(sendForString(request)).getString("status");
    }

    /** Raw JSON of a box by id (or the node's error JSON), for the box CLI to print. */
    public String box(String boxIdHex) {
        return get("/box?id=" + boxIdHex);
    }

    /** Raw JSON of the boxes owned by an address. */
    public String boxesByOwner(PublicAddress owner) {
        return get("/boxes?owner=" + owner.toHexString());
    }

    private String get(String path) {
        return sendForString(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15)).GET().build());
    }

    private String sendForString(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // The node returns JSON with a status/error on 4xx; surface it as-is.
                return response.body();
            }
            return response.body();
        } catch (IOException e) {
            throw new WalletException("node request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WalletException("interrupted: " + request.uri(), e);
        }
    }

    public static final class WalletException extends RuntimeException {
        public WalletException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
