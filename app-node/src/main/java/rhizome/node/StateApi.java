package rhizome.node;

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.json.JSONObject;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.hex;
import static rhizome.node.ApiResponses.json;

/** Authenticated-state endpoints: the current state root and SMT membership proofs. */
final class StateApi {

    private StateApi() {}

    /** The current authenticated state root: {@code GET /state}. */
    static HttpResponse state(NodeService node) {
        byte[] root = node.stateRoot();
        return json(new JSONObject().put("stateRoot", root == null ? JSONObject.NULL : hex(root)));
    }

    /**
     * A light-client membership proof: {@code GET /state/proof?domain=<d>&key=<hex>}, where
     * {@code d} is {@code ledger}/{@code box}/{@code token_meta}/{@code token_balance}. Returns
     * the root, the bound value hash and the sibling hashes; the client re-derives the SMT key
     * from {@code (domain, key)} and folds the siblings to check it against the root. 404 if absent.
     */
    static HttpResponse stateProof(NodeService node, HttpRequest req) {
        byte[] root = node.stateRoot();
        if (root == null) {
            return HttpResponse.ofCode(503)
                .withJson(new JSONObject().put("error", "state root unavailable").toString()).build();
        }
        Byte domain = stateDomain(req.getQueryParameter("domain"));
        if (domain == null) {
            return badRequest("domain must be ledger|box|token_meta|token_balance|contract_code|contract_storage");
        }
        byte[] key = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("key"));
        rhizome.core.state.StateProof proof = node.stateProof(domain, key);
        if (proof == null) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "no such state entry").toString()).build();
        }
        org.json.JSONArray siblings = new org.json.JSONArray();
        for (byte[] s : proof.siblings()) {
            siblings.put(hex(s));
        }
        return json(new JSONObject()
            .put("root", hex(root))
            .put("valueHash", hex(proof.valueHash()))
            .put("siblings", siblings));
    }

    private static Byte stateDomain(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "ledger" -> rhizome.core.state.StateKeys.LEDGER;
            case "box" -> rhizome.core.state.StateKeys.BOX;
            case "token_meta" -> rhizome.core.state.StateKeys.TOKEN_META;
            case "token_balance" -> rhizome.core.state.StateKeys.TOKEN_BALANCE;
            case "contract_code" -> rhizome.core.state.StateKeys.CONTRACT_CODE;
            case "contract_storage" -> rhizome.core.state.StateKeys.CONTRACT_STORAGE;
            default -> null;
        };
    }
}
