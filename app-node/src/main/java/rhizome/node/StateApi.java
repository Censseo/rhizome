package rhizome.node;

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;

import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.errorJson;
import static rhizome.node.ApiResponses.json;

/**
 * Authenticated-state endpoints: the current state root and SMT membership proofs.
 *
 * <p>Bodies are written with {@link JsonSink} instead of an {@code org.json} tree — see that
 * class's Javadoc. Its own 503/404 bodies go through {@link ApiResponses#errorJson}.
 */
final class StateApi {

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_STATE_ROOT = Key.of("stateRoot");
    private static final Key K_ROOT = Key.of("root");
    private static final Key K_VALUE_HASH = Key.of("valueHash");
    private static final Key K_SIBLINGS = Key.of("siblings");

    // -- JsonSink size hints (see class javadoc) -----------------------------------------------
    private static final int STATE_SIZE_HINT = 96;

    private StateApi() {}

    /** The current authenticated state root: {@code GET /state}. */
    static HttpResponse state(NodeService node) {
        byte[] root = node.stateRoot();
        JsonSink sink = JsonSink.create(STATE_SIZE_HINT);
        sink.beginObject();
        if (root == null) {
            sink.fieldNull(K_STATE_ROOT);
        } else {
            sink.hexLower(K_STATE_ROOT, root);
        }
        sink.endObject();
        return json(sink);
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
            return errorJson(503, "state root unavailable");
        }
        Byte domain = stateDomain(req.getQueryParameter("domain"));
        if (domain == null) {
            return badRequest("domain must be ledger|box|token_meta|token_balance|contract_code|contract_storage");
        }
        byte[] key = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("key"));
        rhizome.core.state.StateProof proof = node.stateProof(domain, key);
        if (proof == null) {
            return errorJson(404, "no such state entry");
        }
        JsonSink sink = JsonSink.create(STATE_SIZE_HINT + proof.siblings().size() * 70);
        sink.beginObject();
        sink.hexLower(K_ROOT, root);
        sink.hexLower(K_VALUE_HASH, proof.valueHash());
        sink.name(K_SIBLINGS);
        sink.beginArray();
        for (byte[] s : proof.siblings()) {
            sink.hexLower(s);
        }
        sink.endArray();
        sink.endObject();
        return json(sink);
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
