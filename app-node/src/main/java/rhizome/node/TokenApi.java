package rhizome.node;

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.json.JSONObject;

import rhizome.core.ledger.PublicAddress;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.hex;
import static rhizome.node.ApiResponses.json;

/** Native-token endpoints: metadata, balances and per-minter/per-holder listings. */
final class TokenApi {

    private TokenApi() {}

    /** Native token metadata: {@code GET /token?id=<hex64>}; 404 if absent. */
    static HttpResponse token(NodeService node, HttpRequest req) {
        byte[] id = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("id"));
        if (id.length != 32) {
            return badRequest("id must be 32 bytes (64 hex chars)");
        }
        rhizome.core.token.TokenMeta meta = node.tokenMeta(id);
        if (meta == null) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "token not found").toString()).build();
        }
        return json(tokenJson(meta));
    }

    /** Token balance: {@code GET /token_balance?id=<hex64>&address=<hex50>}. */
    static HttpResponse tokenBalance(NodeService node, HttpRequest req) {
        byte[] id = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("id"));
        byte[] address = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("address"));
        if (id.length != 32 || address.length != PublicAddress.SIZE) {
            return badRequest("id must be 32 bytes and address 25 bytes");
        }
        return json(new JSONObject()
            .put("token", hex(id))
            .put("address", hex(address))
            .put("balance", node.tokenBalance(id, address)));
    }

    /** Tokens by minter or holder: {@code GET /tokens?minter=<hex50>} or {@code ?holder=<hex50>}. */
    static HttpResponse tokens(NodeService node, HttpRequest req) {
        String minter = req.getQueryParameter("minter");
        String holder = req.getQueryParameter("holder");
        byte[] key;
        java.util.List<byte[]> ids;
        if (minter != null) {
            key = rhizome.core.common.Utils.hexStringToByteArray(minter);
            ids = node.tokenIdsByMinter(key, null, 100);
        } else if (holder != null) {
            key = rhizome.core.common.Utils.hexStringToByteArray(holder);
            ids = node.tokenIdsByHolder(key, null, 100);
        } else {
            return badRequest("provide minter= or holder=");
        }
        if (key.length != PublicAddress.SIZE) {
            return badRequest("address must be 25 bytes (50 hex chars)");
        }
        org.json.JSONArray arr = new org.json.JSONArray();
        for (byte[] id : ids) {
            rhizome.core.token.TokenMeta meta = node.tokenMeta(id);
            if (meta != null) {
                JSONObject entry = tokenJson(meta);
                if (holder != null) {
                    entry.put("balance", node.tokenBalance(id, key));
                }
                arr.put(entry);
            }
        }
        return json(new JSONObject().put("tokens", arr));
    }

    private static JSONObject tokenJson(rhizome.core.token.TokenMeta meta) {
        return new JSONObject()
            .put("id", hex(meta.id()))
            .put("minter", meta.minter().toHexString())
            .put("symbol", meta.symbol())
            .put("name", meta.name())
            .put("decimals", meta.decimals())
            .put("totalSupply", meta.totalSupply())
            .put("createdHeight", meta.createdHeight());
    }
}
