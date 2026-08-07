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
 *
 * <p><b>Reorg window semantics:</b> while the node's canonical chain sits truncated at a
 * fork height (non-atomic reorg, pop → body-apply → restore), the chain-serving endpoints
 * answer 503 + Retry-After instead of serving a truncated view. A peer that read a height
 * just before the pop would otherwise fetch an empty / gapped window after it and read the
 * transient truncation as a protocol violation (PEER_INVALID → ban, testnet campaign S5).
 * A non-200 response is a transport failure on the caller's side — retried, never penalized —
 * and mirrors the write-side doctrine: beginReorgWindow already refuses new-tip addBlock
 * (IS_SYNCING) for the same window, because a node mid-reorg must not act authoritative.
 */
final class SyncApi {

    private SyncApi() {}

    /** 503 for the reorg window: the node has nothing coherent to serve right now. */
    static HttpResponse busyDuringReorg() {
        return HttpResponse.ofCode(503)
            .withHeader(HttpHeaders.RETRY_AFTER, "5")
            .withJson(new JSONObject().put("error", "reorg in progress; retry shortly").toString())
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
            .put("chunks", snap.chunkCount()));
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
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "no such snapshot chunk").toString())
                .build();
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
                return HttpResponse.ofCode(404)
                    .withJson(new JSONObject().put("error", "snapshot rotated").toString())
                    .build();
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
            return HttpResponse.ofCode(404)
                .withJson(new JSONObject().put("error", "no such orphan").toString())
                .build();
        }
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .withBody(BlockCodec.encode(block))
            .build();
    }
}
