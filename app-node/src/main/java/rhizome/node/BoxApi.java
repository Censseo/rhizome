package rhizome.node;

import java.nio.charset.StandardCharsets;

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.json.JSONObject;

import rhizome.core.ledger.PublicAddress;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.hex;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.parseLong;

/**
 * Data-box endpoints: single-box lookup, owner listing and the registered
 * scan predicates with their bounded, cursor-driven scans.
 */
final class BoxApi {

    private BoxApi() {}

    /** A single data box by id: {@code GET /box?id=<hex64>}; 404 if absent. */
    static HttpResponse box(NodeService node, HttpRequest req) {
        byte[] id = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("id"));
        if (id.length != 32) {
            return badRequest("id must be 32 bytes (64 hex chars)");
        }
        rhizome.core.box.Box b = node.box(id);
        if (b == null) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "box not found").toString())
                .build();
        }
        return json(boxJson(b, node.params().storagePeriodBlocks()));
    }

    /** Boxes owned by an address: {@code GET /boxes?owner=<hex50>&limit=&after=<boxIdHex>}. */
    static HttpResponse boxes(NodeService node, HttpRequest req) {
        byte[] owner = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("owner"));
        if (owner.length != PublicAddress.SIZE) {
            return badRequest("owner must be 25 bytes (50 hex chars)");
        }
        String limitParam = req.getQueryParameter("limit");
        int limit = limitParam == null ? 50 : Math.min(100, Math.max(1, (int) parseLong(limitParam)));
        String afterParam = req.getQueryParameter("after");
        byte[] after = afterParam == null || afterParam.isEmpty()
            ? null : rhizome.core.common.Utils.hexStringToByteArray(afterParam);

        long period = node.params().storagePeriodBlocks();
        org.json.JSONArray arr = new org.json.JSONArray();
        byte[] last = null;
        for (byte[] id : node.boxIdsByOwner(owner, after, limit)) {
            rhizome.core.box.Box b = node.box(id);
            if (b != null) {
                arr.put(boxJson(b, period));
            }
            last = id;
        }
        JSONObject result = new JSONObject()
            .put("owner", rhizome.core.common.Utils.bytesToHex(owner))
            .put("boxes", arr);
        if (last != null) {
            result.put("next", rhizome.core.common.Utils.bytesToHex(last));
        }
        return json(result);
    }

    /** Registered box scans: {@code GET /scan/list}. */
    static HttpResponse scanList(NodeService node) {
        org.json.JSONArray arr = new org.json.JSONArray();
        node.scans().forEach((id, predicate) ->
            arr.put(new JSONObject().put("scanId", id).put("predicate", predicate.toJson())));
        return json(new JSONObject().put("scans", arr));
    }

    /**
     * Boxes matching a scan: {@code GET /scan/boxes?scanId=N&limit=&after=<boxIdHex>}. The
     * response's {@code next} cursor (a box id) resumes a bounded scan; absent when done.
     */
    static HttpResponse scanBoxes(NodeService node, HttpRequest req) {
        rhizome.core.box.ScanPredicate predicate = node.scanPredicate((int) parseLong(req.getQueryParameter("scanId")));
        if (predicate == null) {
            return badRequest("unknown scanId");
        }
        String limitParam = req.getQueryParameter("limit");
        int limit = limitParam == null ? 50 : Math.min(100, Math.max(1, (int) parseLong(limitParam)));
        String afterParam = req.getQueryParameter("after");
        byte[] after = afterParam == null || afterParam.isEmpty()
            ? null : rhizome.core.common.Utils.hexStringToByteArray(afterParam);

        long period = node.params().storagePeriodBlocks();
        var page = node.scan(predicate, after, limit);
        org.json.JSONArray arr = new org.json.JSONArray();
        for (rhizome.core.box.Box b : page.matches()) {
            arr.put(boxJson(b, period));
        }
        JSONObject result = new JSONObject().put("boxes", arr);
        if (page.nextCursor() != null) {
            result.put("next", hex(page.nextCursor()));
        }
        return json(result);
    }

    private static JSONObject boxJson(rhizome.core.box.Box b, long storagePeriodBlocks) {
        org.json.JSONArray registers = new org.json.JSONArray();
        for (rhizome.core.box.BoxRegister r : b.registers()) {
            JSONObject reg = new JSONObject()
                .put("type", r.type().name())
                .put("hex", hex(r.payload()));
            if (r.type() == rhizome.core.box.BoxRegisterType.STRING) {
                reg.put("string", new String(r.payload(), StandardCharsets.UTF_8));
            }
            registers.put(reg);
        }
        return new JSONObject()
            .put("id", hex(b.id()))
            .put("owner", b.owner().toHexString())
            .put("value", b.value())
            .put("createdHeight", b.createdHeight())
            .put("rentPaidHeight", b.rentPaidHeight())
            .put("expiresAtHeight", b.expiryHeight(storagePeriodBlocks))
            .put("sizeBytes", b.serializedSize())
            .put("registers", registers);
    }
}
