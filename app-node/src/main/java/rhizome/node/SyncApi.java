package rhizome.node;

import io.activej.bytebuf.ByteBuf;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.json.JSONObject;

import rhizome.core.block.BlockCodec;
import rhizome.core.common.Constants;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.parseLong;

/**
 * Peer-facing sync endpoints: streamed block and header windows plus the
 * materialised state-snapshot advertisement and chunk download (snap sync).
 */
final class SyncApi {

    private SyncApi() {}

    static HttpResponse sync(NodeService node, HttpRequest req) {
        long start = parseLong(req.getQueryParameter("start"));
        long end = parseLong(req.getQueryParameter("end"));
        if (start < 1 || end < start) {
            return badRequest("invalid range");
        }
        if (end - start + 1 > Constants.BLOCKS_PER_FETCH) {
            return badRequest("range too large (max " + Constants.BLOCKS_PER_FETCH + ")");
        }
        // Pruned node: the requested range dips into bodies we have discarded. Answer 410 GONE
        // with the watermark so the caller sources these blocks (or a snapshot) from an archive.
        long prunedBelow = node.prunedBelow();
        if (prunedBelow > 0 && start < prunedBelow) {
            return HttpResponse.ofCode(410)
                .withJson(new JSONObject().put("error", "pruned").put("prunedBelow", prunedBelow).toString())
                .build();
        }
        long cappedEnd = Math.min(end, node.blockCount());
        // Stream the window block-by-block instead of buffering it (audit M5): materialising up to
        // BLOCKS_PER_FETCH × MAX_BLOCK_SIZE in a ByteArrayOutputStream and then copying it again via
        // toByteArray() peaked at ~2× the window in memory on the event loop, so a few concurrent
        // full-window /sync requests could OOM the node. Each block is encoded lazily as the response
        // is flushed; the on-the-wire bytes are the identical self-framing concatenation the client
        // (BlockCodec.decodeStreamed) already parses. Bounded to one block in memory at a time.
        java.util.Iterator<ByteBuf> blocks = new java.util.Iterator<>() {
            private long h = start;
            @Override public boolean hasNext() {
                return h <= cappedEnd;
            }
            @Override public ByteBuf next() {
                return ByteBuf.wrapForReading(BlockCodec.encode(node.block(h++)));
            }
        };
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .withBodyStream(ChannelSuppliers.ofIterator(blocks))
            .build();
    }

    /**
     * Streams a self-framing run of block headers ({@link rhizome.core.block.HeaderCodec}),
     * the cheap path a headers-first peer validates before downloading any body. Bounded to
     * {@code BLOCK_HEADERS_PER_FETCH}.
     */
    static HttpResponse headers(NodeService node, HttpRequest req) {
        long start = parseLong(req.getQueryParameter("start"));
        long end = parseLong(req.getQueryParameter("end"));
        if (start < 1 || end < start) {
            return badRequest("invalid range");
        }
        if (end - start + 1 > Constants.BLOCK_HEADERS_PER_FETCH) {
            return badRequest("range too large (max " + Constants.BLOCK_HEADERS_PER_FETCH + ")");
        }
        long cappedEnd = Math.min(end, node.blockCount());
        // Stream header-by-header instead of buffering the whole window and copying it again via
        // toByteArray() (the same ~2×-window-on-the-event-loop pattern the M5 fix removed from
        // /sync). Headers are small, so the impact was modest, but the streaming form is bounded to
        // one header in memory at a time and matches /sync (audit net F3). The wire bytes are the
        // identical self-framing concatenation the client (HeaderCodec.decodeAll) already parses.
        java.util.Iterator<ByteBuf> headers = new java.util.Iterator<>() {
            private long h = start;
            @Override public boolean hasNext() {
                return h <= cappedEnd;
            }
            @Override public ByteBuf next() {
                return ByteBuf.wrapForReading(rhizome.core.block.HeaderCodec.encode(node.header(h++)));
            }
        };
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .withBodyStream(ChannelSuppliers.ofIterator(headers))
            .build();
    }

    /** Advertises the materialised state snapshot ({@code 404} when none has been captured). */
    static HttpResponse snapshotInfo(NodeService node) {
        var snap = node.materializedSnapshot();
        if (snap == null) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "no snapshot materialized").toString())
                .build();
        }
        return json(new JSONObject()
            .put("pivotHeight", snap.pivotHeight())
            .put("stateRoot", rhizome.core.common.Utils.bytesToHex(snap.stateRoot()))
            .put("chunks", snap.chunks().size()));
    }

    /** One binary snapshot chunk by index (bounds-checked against the current materialisation). */
    static HttpResponse snapshotChunk(NodeService node, HttpRequest req) {
        var snap = node.materializedSnapshot();
        int index = (int) parseLong(req.getQueryParameter("index"));
        if (snap == null || index < 0 || index >= snap.chunks().size()) {
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "no such snapshot chunk").toString())
                .build();
        }
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .withBody(snap.chunks().get(index))
            .build();
    }
}
