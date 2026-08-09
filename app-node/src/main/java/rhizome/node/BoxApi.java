package rhizome.node;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.notFound;
import static rhizome.node.ApiResponses.parseLong;

/**
 * Data-box endpoints: single-box lookup, owner listing and the registered
 * scan predicates with their bounded, cursor-driven scans.
 *
 * <p>Bodies are written with {@link JsonSink} instead of an {@code org.json} tree — see that
 * class's Javadoc. This is the file where the node's mixed hex-case convention is most visible:
 * {@code id} and register {@code hex} are lowercase (mirroring the legacy {@code ApiResponses#hex}
 * helper), {@code owner} is uppercase (mirroring {@link PublicAddress#toHexString()}), and the
 * {@code next} pagination cursor is uppercase on {@code /boxes} but lowercase on
 * {@code /scan/boxes} — two different call sites that happened to build the same *kind* of value
 * with different legacy encoders. Preserved exactly rather than unified (out of scope for this
 * migration — see the design doc).
 */
final class BoxApi {

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_ID = Key.of("id");
    private static final Key K_OWNER = Key.of("owner");
    private static final Key K_VALUE = Key.of("value");
    private static final Key K_CREATED_HEIGHT = Key.of("createdHeight");
    private static final Key K_RENT_PAID_HEIGHT = Key.of("rentPaidHeight");
    private static final Key K_EXPIRES_AT_HEIGHT = Key.of("expiresAtHeight");
    private static final Key K_SIZE_BYTES = Key.of("sizeBytes");
    private static final Key K_REGISTERS = Key.of("registers");
    private static final Key K_TYPE = Key.of("type");
    private static final Key K_HEX = Key.of("hex");
    private static final Key K_STRING = Key.of("string");
    private static final Key K_BOXES = Key.of("boxes");
    private static final Key K_NEXT = Key.of("next");
    private static final Key K_SCANS = Key.of("scans");
    private static final Key K_SCAN_ID = Key.of("scanId");
    private static final Key K_PREDICATE = Key.of("predicate");

    // -- JsonSink size hints (see class javadoc) -----------------------------------------------
    private static final int BOX_SIZE_HINT = 256;
    private static final int BOXES_BASE_SIZE_HINT = 128;
    private static final int SCAN_LIST_BASE_SIZE_HINT = 64;
    private static final int SCAN_ENTRY_SIZE_HINT = 96;

    private BoxApi() {}

    /** A single data box by id: {@code GET /box?id=<hex64>}; 404 if absent. */
    static HttpResponse box(NodeService node, HttpRequest req) {
        byte[] id = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("id"));
        if (id.length != 32) {
            return badRequest("id must be 32 bytes (64 hex chars)");
        }
        rhizome.core.box.Box b = node.box(id);
        if (b == null) {
            return notFound("box not found");
        }
        JsonSink sink = JsonSink.create(BOX_SIZE_HINT);
        writeBoxJson(sink, b, node.params().storagePeriodBlocks());
        return json(sink);
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
        List<byte[]> ids = node.boxIdsByOwner(owner, after, limit);
        JsonSink sink = JsonSink.create(BOXES_BASE_SIZE_HINT + ids.size() * BOX_SIZE_HINT);
        sink.beginObject();
        // Utils.bytesToHex is uppercase — mirrors PublicAddress.toHexString()'s case, unlike the
        // lowercase id/register hex below (see class javadoc).
        sink.hexUpper(K_OWNER, owner);
        sink.name(K_BOXES);
        sink.beginArray();
        byte[] last = null;
        for (byte[] id : ids) {
            rhizome.core.box.Box b = node.box(id);
            if (b != null) {
                writeBoxJson(sink, b, period);
            }
            last = id;
        }
        sink.endArray();
        if (last != null) {
            // Uppercase, matching the legacy Utils.bytesToHex(last) this cursor mirrored — the
            // opposite case from /scan/boxes's cursor below (see class javadoc).
            sink.hexUpper(K_NEXT, last);
        }
        sink.endObject();
        return json(sink);
    }

    /** Registered box scans owned by {@code clientKey}: {@code GET /scan/list}. Restricted to
     *  the caller's own registrations — an unauthenticated listing of every predicate on the
     *  node leaks what all apps are watching (audit F1). */
    static HttpResponse scanList(NodeService node, ScanRegistry.Owner owner) {
        var scans = node.scansOf(owner);
        JsonSink sink = JsonSink.create(SCAN_LIST_BASE_SIZE_HINT + scans.size() * SCAN_ENTRY_SIZE_HINT);
        sink.beginObject();
        sink.name(K_SCANS);
        sink.beginArray();
        scans.forEach((id, predicate) -> {
            sink.beginObject();
            sink.field(K_SCAN_ID, id);
            sink.name(K_PREDICATE);
            predicate.writeJson(sink);
            sink.endObject();
        });
        sink.endArray();
        sink.endObject();
        return json(sink);
    }

    /**
     * Boxes matching a scan: {@code GET /scan/boxes?scanId=N&limit=&after=<boxIdHex>}. The
     * response's {@code next} cursor (a box id) resumes a bounded scan; absent when done.
     * Restricted to the scan's owner: {@code /scan/register} is unauthenticated, so an
     * un-gated query let any caller drive the bounded scan loop (and read the matches) of
     * every registered scan whose id it learned or ground out (audit: scan query gating) —
     * like {@code /scan/list} and {@code /scan/deregister}, a foreign id is answered
     * indistinguishably from an unknown one.
     */
    static HttpResponse scanBoxes(NodeService node, ScanRegistry.Owner owner, HttpRequest req) {
        // Bounds-check BEFORE the long→int cast: an out-of-int-range scanId would silently wrap
        // into another client's valid id (audit: unchecked index cast).
        long rawScanId = parseLong(req.getQueryParameter("scanId"));
        if (rawScanId < 0 || rawScanId > Integer.MAX_VALUE) {
            return badRequest("scanId out of range");
        }
        rhizome.core.box.ScanPredicate predicate = node.scanPredicate(owner, (int) rawScanId);
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
        JsonSink sink = JsonSink.create(BOXES_BASE_SIZE_HINT + page.matches().size() * BOX_SIZE_HINT);
        sink.beginObject();
        sink.name(K_BOXES);
        sink.beginArray();
        for (rhizome.core.box.Box b : page.matches()) {
            writeBoxJson(sink, b, period);
        }
        sink.endArray();
        if (page.nextCursor() != null) {
            // Lowercase, matching the legacy ApiResponses#hex(page.nextCursor()) this cursor
            // mirrored — the opposite case from /boxes's cursor above (see class javadoc).
            sink.hexLower(K_NEXT, page.nextCursor());
        }
        sink.endObject();
        return json(sink);
    }

    /**
     * Writes one box's JSON — {@code id} and register {@code hex} lowercase, {@code owner}
     * uppercase (see class javadoc for why the case differs within one object). The register
     * {@code string} field carries raw on-chain bytes decoded with {@code UTF_8} — attacker- or
     * contract-controlled content — but by the time it reaches this method it is already a
     * {@code String}: {@code new String(bytes, UTF_8)} replaces any invalid sequence with U+FFFD,
     * so this method needs no special-cased escaping beyond {@link JsonSink#field(Key, String)}'s
     * normal path (audit: register string is attacker/contract controlled, no fast-path bypass).
     */
    private static void writeBoxJson(JsonSink sink, rhizome.core.box.Box b, long storagePeriodBlocks) {
        sink.beginObject();
        sink.hexLower(K_ID, b.id());
        sink.hexUpper(K_OWNER, b.owner().toBytes());
        sink.field(K_VALUE, b.value());
        sink.field(K_CREATED_HEIGHT, b.createdHeight());
        sink.field(K_RENT_PAID_HEIGHT, b.rentPaidHeight());
        sink.field(K_EXPIRES_AT_HEIGHT, b.expiryHeight(storagePeriodBlocks));
        sink.field(K_SIZE_BYTES, b.serializedSize());
        sink.name(K_REGISTERS);
        sink.beginArray();
        for (rhizome.core.box.BoxRegister r : b.registers()) {
            sink.beginObject();
            sink.field(K_TYPE, r.type().name());
            sink.hexLower(K_HEX, r.payload());
            if (r.type() == rhizome.core.box.BoxRegisterType.STRING) {
                sink.field(K_STRING, new String(r.payload(), StandardCharsets.UTF_8));
            }
            sink.endObject();
        }
        sink.endArray();
        sink.endObject();
    }
}
