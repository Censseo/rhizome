package rhizome.node;

import io.activej.bytebuf.ByteBuf;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.http.ContentTypes;
import io.activej.http.HttpHeaderValue;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;

import rhizome.core.block.BlockCodec;
import rhizome.core.common.Constants;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.errorJson;
import static rhizome.node.ApiResponses.gone;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.parseLong;

/**
 * Peer-facing sync endpoints: streamed block and header windows plus the
 * materialised state-snapshot advertisement and chunk download (snap sync).
 *
 * <p><b>Reorg window semantics:</b> while the node's canonical chain sits truncated at a
 * fork height (non-atomic reorg, pop → body-apply → restore), the chain-serving endpoints
 * answer 503 + Retry-After instead of serving a truncated view. A peer that read a height
 * just before the pop would otherwise fetch an empty / gapped window after it and read the
 * transient truncation as a protocol violation (PEER_INVALID → ban, testnet campaign S5).
 * A non-200 response is a transport failure on the caller's side — retried, never penalized —
 * and mirrors the write-side doctrine: beginReorgWindow already refuses new-tip addBlock
 * (IS_SYNCING) for the same window, because a node mid-reorg must not act authoritative.
 *
 * <p>Bodies are written with {@link JsonSink} instead of an {@code org.json} tree — see that
 * class's Javadoc. This file's own 404 bodies go through {@link ApiResponses#errorJson};
 * {@link #busyDuringReorg()} keeps its own inline body since it needs an extra
 * {@code Retry-After} header {@code errorJson} does not take. {@code /state/snapshot/info} is
 * the highest-caution body here: it is read by other nodes with typed accessors
 * ({@code HttpPeerSource.snapshotInfo()} — {@code getLong}/{@code getString}/{@code getInt}),
 * preserved exactly below.
 */
final class SyncApi {

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_ERROR = Key.of("error");
    private static final Key K_PIVOT_HEIGHT = Key.of("pivotHeight");
    private static final Key K_STATE_ROOT = Key.of("stateRoot");
    private static final Key K_CHUNKS = Key.of("chunks");

    // -- JsonSink size hints (see class javadoc on JsonSink) -------------------------------------
    private static final int ERROR_SIZE_HINT = 96;
    private static final int SNAPSHOT_INFO_SIZE_HINT = 128;

    private SyncApi() {}

    /** 503 for the reorg window: the node has nothing coherent to serve right now. */
    static HttpResponse busyDuringReorg() {
        JsonSink sink = JsonSink.create(ERROR_SIZE_HINT);
        sink.beginObject();
        sink.field(K_ERROR, "reorg in progress; retry shortly");
        sink.endObject();
        return HttpResponse.ofCode(503)
            .withHeader(HttpHeaders.RETRY_AFTER, "5")
            .withHeader(HttpHeaders.CONTENT_TYPE, HttpHeaderValue.ofContentType(ContentTypes.JSON_UTF_8))
            .withHeader(ApiResponses.H_XCTO, "nosniff")
            .withBody(ByteBuf.wrap(sink.array(), 0, sink.length()))
            .build();
    }

    static HttpResponse sync(NodeService node, HttpRequest req) {
        if (node.isReorgInProgress()) {
            return busyDuringReorg();
        }
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
            return gone(prunedBelow);
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
        if (node.isReorgInProgress()) {
            return busyDuringReorg();
        }
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

    /**
     * Advertises the materialised state snapshot ({@code 404} when none has been captured).
     * {@code pivotHeight} (long), {@code stateRoot} (uppercase hex string — mirrors
     * {@code Utils.bytesToHex}'s case) and {@code chunks} (int) are read with typed accessors by
     * {@code HttpPeerSource.snapshotInfo()} ({@code getLong}/{@code getString}/{@code getInt}):
     * the exact type of each field is preserved here.
     */
    static HttpResponse snapshotInfo(NodeService node) {
        var snap = node.materializedSnapshot();
        if (snap == null) {
            return errorJson(404, "no snapshot materialized");
        }
        JsonSink sink = JsonSink.create(SNAPSHOT_INFO_SIZE_HINT);
        sink.beginObject();
        sink.field(K_PIVOT_HEIGHT, snap.pivotHeight());
        sink.hexUpper(K_STATE_ROOT, snap.stateRoot());
        sink.field(K_CHUNKS, snap.chunkCount());
        sink.endObject();
        return json(sink);
    }

    /** One binary snapshot chunk by index (bounds-checked against the current materialisation). */
    static HttpResponse snapshotChunk(NodeService node, HttpRequest req) {
        var snap = node.materializedSnapshot();
        // Bounds-check BEFORE the long→int cast: an out-of-int-range index would silently wrap
        // to a valid-looking negative/positive int (audit: unchecked index cast).
        long rawIndex = parseLong(req.getQueryParameter("index"));
        if (rawIndex < 0 || rawIndex > Integer.MAX_VALUE) {
            return badRequest("index out of range");
        }
        int index = (int) rawIndex;
        if (snap == null || index >= snap.chunkCount()) {
            return errorJson(404, "no such snapshot chunk");
        }
        try {
            return HttpResponse.ok200()
                .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .withBody(snap.chunkBytes(index))
                .build();
        } catch (java.io.UncheckedIOException e) {
            // Rotation race: materializeSnapshot() closed this snapshot's channel between our
            // materializedSnapshot() read and the chunk read. It is not a client fault (→ not
            // the global mapper's 400): tell the peer to re-read /state/snapshot/info.
            if (e.getCause() instanceof java.nio.channels.ClosedChannelException) {
                return errorJson(404, "snapshot rotated");
            }
            throw e;
        }
    }

    /**
     * The orphan (uncle candidate) body behind a hash: {@code GET /orphan?hash=<hex64>}, binary
     * ({@link BlockCodec}), 404 when unknown. A syncing peer whose chain references an uncle its
     * own orphan pool cannot supply fetches the body here (audit: uncle-sync blocker). Binary
     * like /sync, never JSON — the block may be up to maxBlockSizeBytes.
     */
    static HttpResponse orphan(NodeService node, HttpRequest req) {
        byte[] hash = rhizome.core.common.Utils.hexStringToByteArray(req.getQueryParameter("hash"));
        if (hash.length != 32) {
            return badRequest("hash must be 32 bytes (64 hex chars)");
        }
        var block = node.orphanBlock(rhizome.crypto.SHA256Hash.of(hash));
        if (block == null) {
            return errorJson(404, "no such orphan");
        }
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .withBody(BlockCodec.encode(block))
            .build();
    }
}
