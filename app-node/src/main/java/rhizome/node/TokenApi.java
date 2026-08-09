package rhizome.node;

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.notFound;

/**
 * Native-token endpoints: metadata, balances and per-minter/per-holder listings.
 *
 * <p>Bodies are written with {@link JsonSink} instead of an {@code org.json} tree — see that
 * class's Javadoc. {@code id}/{@code token}/{@code address} are lowercase hex ({@link
 * ApiResponses#hex}'s convention); {@code minter} is uppercase ({@link
 * PublicAddress#toHexString()}'s convention) — the node's usual mixed hex case within one object.
 * {@code symbol}/{@code name} are arbitrary chain-supplied strings, routed through {@link
 * JsonSink}'s normal escaping like any other string field.
 */
final class TokenApi {

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_ID = Key.of("id");
    private static final Key K_MINTER = Key.of("minter");
    private static final Key K_SYMBOL = Key.of("symbol");
    private static final Key K_NAME = Key.of("name");
    private static final Key K_DECIMALS = Key.of("decimals");
    private static final Key K_TOTAL_SUPPLY = Key.of("totalSupply");
    private static final Key K_CREATED_HEIGHT = Key.of("createdHeight");
    private static final Key K_TOKEN = Key.of("token");
    private static final Key K_ADDRESS = Key.of("address");
    private static final Key K_BALANCE = Key.of("balance");
    private static final Key K_TOKENS = Key.of("tokens");

    // -- JsonSink size hints (see class javadoc) -----------------------------------------------
    private static final int TOKEN_SIZE_HINT = 192;
    private static final int TOKEN_BALANCE_SIZE_HINT = 160;
    private static final int TOKENS_BASE_SIZE_HINT = 64;

    private TokenApi() {}

    /** Native token metadata: {@code GET /token?id=<hex64>}; 404 if absent. */
    static HttpResponse token(NodeService node, HttpRequest req) {
        byte[] id = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("id"));
        if (id.length != 32) {
            return badRequest("id must be 32 bytes (64 hex chars)");
        }
        rhizome.core.token.TokenMeta meta = node.tokenMeta(id);
        if (meta == null) {
            return notFound("token not found");
        }
        JsonSink sink = JsonSink.create(TOKEN_SIZE_HINT + meta.symbol().length() * 2 + meta.name().length() * 2);
        writeTokenJson(sink, meta);
        return json(sink);
    }

    /** Token balance: {@code GET /token_balance?id=<hex64>&address=<hex50>}. */
    static HttpResponse tokenBalance(NodeService node, HttpRequest req) {
        byte[] id = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("id"));
        byte[] address = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("address"));
        if (id.length != 32 || address.length != PublicAddress.SIZE) {
            return badRequest("id must be 32 bytes and address 25 bytes");
        }
        JsonSink sink = JsonSink.create(TOKEN_BALANCE_SIZE_HINT);
        sink.beginObject();
        sink.hexLower(K_TOKEN, id);
        sink.hexLower(K_ADDRESS, address);
        sink.field(K_BALANCE, node.tokenBalance(id, address));
        sink.endObject();
        return json(sink);
    }

    /** Tokens by minter or holder: {@code GET /tokens?minter=<hex50>} or {@code ?holder=<hex50>}. */
    static HttpResponse tokens(NodeService node, HttpRequest req) {
        String minter = req.getQueryParameter("minter");
        String holder = req.getQueryParameter("holder");
        byte[] key;
        java.util.List<byte[]> ids;
        if (minter != null) {
            key = rhizome.core.common.Utils.hexStringToByteArray(minter);
            // Validate the decoded length BEFORE any store call: a short/overlong hex key must be a
            // cheap 400, not a store lookup with a malformed key (audit F12).
            if (key.length != PublicAddress.SIZE) {
                return badRequest("address must be 25 bytes (50 hex chars)");
            }
            ids = node.tokenIdsByMinter(key, null, 100);
        } else if (holder != null) {
            key = rhizome.core.common.Utils.hexStringToByteArray(holder);
            if (key.length != PublicAddress.SIZE) {
                return badRequest("address must be 25 bytes (50 hex chars)");
            }
            ids = node.tokenIdsByHolder(key, null, 100);
        } else {
            return badRequest("provide minter= or holder=");
        }
        JsonSink sink = JsonSink.create(TOKENS_BASE_SIZE_HINT + ids.size() * TOKEN_SIZE_HINT);
        sink.beginObject();
        sink.name(K_TOKENS);
        sink.beginArray();
        for (byte[] id : ids) {
            rhizome.core.token.TokenMeta meta = node.tokenMeta(id);
            if (meta != null) {
                sink.beginObject();
                writeTokenBody(sink, meta);
                if (holder != null) {
                    sink.field(K_BALANCE, node.tokenBalance(id, key));
                }
                sink.endObject();
            }
        }
        sink.endArray();
        sink.endObject();
        return json(sink);
    }

    /** Full {@code {...}} object for one token — see class javadoc for the hex-case convention
     *  and the symbol/name escaping note. */
    private static void writeTokenJson(JsonSink sink, rhizome.core.token.TokenMeta meta) {
        sink.beginObject();
        writeTokenBody(sink, meta);
        sink.endObject();
    }

    /** Key/value pairs only — no surrounding braces — so {@link #tokens} can splice in the
     *  holder-only {@code balance} field without a second object (mirrors lib-core's
     *  {@code writeJsonBody}/{@code writeJson} split). */
    private static void writeTokenBody(JsonSink sink, rhizome.core.token.TokenMeta meta) {
        sink.hexLower(K_ID, meta.id());
        sink.hexUpper(K_MINTER, meta.minter().toBytes());
        sink.field(K_SYMBOL, meta.symbol());
        sink.field(K_NAME, meta.name());
        sink.field(K_DECIMALS, meta.decimals());
        sink.field(K_TOTAL_SUPPLY, meta.totalSupply());
        sink.field(K_CREATED_HEIGHT, meta.createdHeight());
    }
}
