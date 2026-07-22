package rhizome.wallet;

import org.json.JSONObject;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.net.NodeHttpClient;

/** Talks to a node's HTTP API on the wallet's behalf (query state, submit transfers). */
public final class WalletClient {

    private final NodeHttpClient http;

    public WalletClient(String baseUrl) {
        this.http = new NodeHttpClient(baseUrl);
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
        return new JSONObject(wrap(() -> http.postBinary("/add_transaction", body))).getString("status");
    }

    /** Raw JSON of a read-only contract call (dry run) against committed state. */
    public String callReadonly(PublicAddress contract, byte[] input) {
        JSONObject body = new JSONObject().put("to", contract.toHexString());
        if (input.length > 0) {
            body.put("input", rhizome.core.common.Utils.bytesToHex(input));
        }
        return wrap(() -> http.postJson("/call_readonly", body.toString()));
    }

    /** Raw JSON of a token's metadata by id. */
    public String token(String tokenIdHex) {
        return get("/token?id=" + tokenIdHex);
    }

    /** Raw JSON of a token balance for an address. */
    public String tokenBalance(String tokenIdHex, PublicAddress address) {
        return get("/token_balance?id=" + tokenIdHex + "&address=" + address.toHexString());
    }

    /** Raw JSON of the tokens held by an address. */
    public String tokensByHolder(PublicAddress holder) {
        return get("/tokens?holder=" + holder.toHexString());
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
        return wrap(() -> http.get(path));
    }

    /** Maps transport failures onto the wallet's own exception type. */
    private static String wrap(java.util.function.Supplier<String> request) {
        try {
            return request.get();
        } catch (NodeHttpClient.NodeUnavailableException e) {
            throw new WalletException(e.getMessage(), e.getCause());
        }
    }

    public static final class WalletException extends RuntimeException {
        public WalletException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
