package rhizome.node;

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;

import rhizome.core.block.Block;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.JsonSink;
import rhizome.core.serialization.JsonSink.Key;
import rhizome.core.transaction.Transaction;
import rhizome.crypto.SHA256Hash;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.gone;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.notFound;
import static rhizome.node.ApiResponses.parseLong;

/**
 * Explorer read endpoints: block ranges and single blocks, bounded tip-backward
 * transaction scans, address history and contract inspection. These fully decode
 * blocks under the consensus lock, so their rate-limit weighting lives in
 * {@link NodeApi#requestCost}.
 *
 * <p>Bodies are written with {@link JsonSink} instead of an {@code org.json} tree — see that
 * class's Javadoc. The size-hint constants below are allocation hints only: {@link JsonSink}
 * grows its buffer automatically ({@code ensureCapacity}), so an under-estimate just costs a
 * doubling, not a correctness bug.
 */
final class ExplorerApi {

    /** Default and maximum tip-backward scan depth for /transaction and /address_txs. */
    static final int SCAN_DEPTH_DEFAULT = 250;
    static final int SCAN_DEPTH_MAX = 1000;
    /** Rate-limit units per this many blocks scanned, so a deep scan draws proportionally more
     *  of the per-window budget than a flat 1 (audit M2). */
    static final int SCAN_COST_PER_BLOCKS = 20;
    /** Result cap for /address_txs so a busy address cannot produce an unbounded body. */
    static final int ADDRESS_TXS_MAX = 100;
    /** Block-range size cap for /blocks. */
    static final int BLOCKS_RANGE_MAX = 50;

    // -- JsonSink field keys, pre-encoded once per call site (see JsonSink's class Javadoc) ----
    private static final Key K_BLOCKS = Key.of("blocks");
    private static final Key K_HEIGHT = Key.of("height");
    private static final Key K_HASH = Key.of("hash");
    private static final Key K_TIMESTAMP = Key.of("timestamp");
    private static final Key K_DIFFICULTY = Key.of("difficulty");
    private static final Key K_TX_COUNT = Key.of("txCount");
    private static final Key K_UNCLES = Key.of("uncles");
    private static final Key K_SUPPLY = Key.of("supply");
    private static final Key K_ADDRESS = Key.of("address");
    private static final Key K_BALANCE = Key.of("balance");
    private static final Key K_NEXT_NONCE = Key.of("nextNonce");
    private static final Key K_TRANSACTION = Key.of("transaction");
    private static final Key K_SCANNED_FROM = Key.of("scannedFrom");
    private static final Key K_SCANNED_TO = Key.of("scannedTo");
    private static final Key K_TRANSACTIONS = Key.of("transactions");
    private static final Key K_EXISTS = Key.of("exists");
    private static final Key K_CODE_SIZE = Key.of("codeSize");
    private static final Key K_CODE_HASH = Key.of("codeHash");

    // -- JsonSink size hints (see class javadoc) -----------------------------------------------
    private static final int BLOCKS_SIZE_HINT = 256 + BLOCKS_RANGE_MAX * 200;
    private static final int WALLET_SIZE_HINT = 128;
    private static final int TRANSACTION_SIZE_HINT = 512;
    private static final int ADDRESS_TXS_SIZE_HINT = 512 + ADDRESS_TXS_MAX * 200;
    private static final int CONTRACT_SIZE_HINT = 192;

    private ExplorerApi() {}

    /** Block summaries for an inclusive height range (newest-first friendly, bounded). */
    static HttpResponse blocks(NodeService node, HttpRequest req) {
        long start = parseLong(req.getQueryParameter("start"));
        long end = parseLong(req.getQueryParameter("end"));
        if (start < 1 || end < start) {
            return badRequest("invalid range");
        }
        if (end - start + 1 > BLOCKS_RANGE_MAX) {
            return badRequest("range too large (max " + BLOCKS_RANGE_MAX + ")");
        }
        // Pruned node: the range dips into discarded bodies. Answer 410 GONE with the watermark like
        // /sync, rather than letting node.block() throw into a generic 400.
        long prunedBelow = node.prunedBelow();
        if (prunedBelow > 0 && start < prunedBelow) {
            return gone(prunedBelow);
        }
        long cappedEnd = Math.min(end, node.blockCount());
        JsonSink sink = JsonSink.create(BLOCKS_SIZE_HINT);
        sink.beginObject();
        sink.name(K_BLOCKS);
        sink.beginArray();
        for (long h = start; h <= cappedEnd; h++) {
            var block = (rhizome.core.block.BlockImpl) node.block(h);
            sink.beginObject();
            sink.field(K_HEIGHT, h);
            sink.hexUpper(K_HASH, block.hash().toBytes());
            // Note: this per-block summary emits timestamp as a NUMBER, unlike the full
            // Transaction/Block writers which emit it as a string (values can exceed 2^53) —
            // a preexisting divergence this migration preserves rather than harmonizes.
            sink.field(K_TIMESTAMP, block.timestamp());
            sink.field(K_DIFFICULTY, block.difficulty());
            sink.field(K_TX_COUNT, block.transactions().size());
            sink.field(K_UNCLES, block.uncles().size());
            // Emitted only when committed (>= 0), with the same decimal-string encoding the
            // full-block writer (Block.Serializer.writeJsonBody) applies — so a chain that
            // commits no supply produces summaries with no supply key at all. The handler
            // already decoded the block, so this field costs no read (FR-015).
            if (block.supply() >= 0) {
                sink.fieldLongAsString(K_SUPPLY, block.supply());
            }
            sink.endObject();
        }
        sink.endArray();
        sink.field(K_HEIGHT, node.blockCount());
        sink.endObject();
        return json(sink);
    }

    /** A single full block by height: {@code GET /block?blockId=N}. */
    static HttpResponse block(NodeService node, HttpRequest req) {
        long id = parseLong(req.getQueryParameter("blockId"));
        if (id < 1 || id > node.blockCount()) {
            return badRequest("blockId out of range");
        }
        long prunedBelow = node.prunedBelow();
        if (prunedBelow > 0 && id < prunedBelow) {
            return gone(prunedBelow); // body discarded by pruning — source it from an archive
        }
        Block found = node.block(id);
        // Sized from this specific block's shape rather than the network's worst-case
        // maxBlockSizeBytes, so a typical small block doesn't pay for a multi-MB allocation
        // it will never use.
        int sizeHint = 512 + found.transactions().size() * 400 + found.uncles().size() * 128;
        JsonSink sink = JsonSink.create(sizeHint);
        found.writeJson(sink);
        return json(sink);
    }

    /** Balance and next nonce of an address: {@code GET /wallet?address=<hex50>}. */
    static HttpResponse wallet(NodeService node, HttpRequest req) {
        PublicAddress wallet = PublicAddress.of(req.getQueryParameter("address"));
        JsonSink sink = JsonSink.create(WALLET_SIZE_HINT);
        sink.beginObject();
        sink.hexUpper(K_ADDRESS, wallet.toBytes());
        sink.field(K_BALANCE, node.balance(wallet));
        sink.field(K_NEXT_NONCE, node.nextNonce(wallet));
        sink.endObject();
        return json(sink);
    }

    /**
     * Looks a transaction up by content hash (txid): the O(1) txid index first (a single store
     * read, replacing the old tip-backward full-block decode of up to {@code depth} blocks —
     * audit perf), with the bounded scan kept as a fallback for entries the index does not
     * carry (coinbase txs are not indexed) or an index-less store. {@code ?depth=} widens the
     * fallback scan up to the cap.
     */
    static HttpResponse findTransaction(NodeService node, HttpRequest req) {
        String txid = req.getQueryParameter("txid");
        if (txid == null || txid.length() != 64) {
            return badRequest("txid must be 64 hex chars");
        }
        SHA256Hash contentHash = SHA256Hash.of(rhizome.core.common.Utils.hexStringToByteArray(txid));
        Long indexed = node.transactionHeight(contentHash);
        if (indexed != null && indexed >= Math.max(1, node.prunedBelow())) {
            for (Transaction t : node.block(indexed).transactions()) {
                if (t.hashContents().equals(contentHash)) {
                    return transactionFound(indexed, t);
                }
            }
        }
        long depth = scanDepth(req);
        long tip = node.blockCount();
        // Never scan below the prune watermark: those bodies are gone and node.block() would throw.
        long floor = Math.max(Math.max(1, tip - depth + 1), node.prunedBelow());
        for (long h = tip; h >= floor; h--) {
            for (Transaction t : node.block(h).transactions()) {
                if (t.hashContents().equals(contentHash)) {
                    return transactionFound(h, t);
                }
            }
        }
        return notFound("transaction not found in scanned range (deepen with ?depth=)");
    }

    /** {@code {"height": ..., "transaction": {...}}} envelope shared by both {@link
     *  #findTransaction} return sites — the indexed lookup and the tip-backward scan fallback. */
    private static HttpResponse transactionFound(long height, Transaction t) {
        JsonSink sink = JsonSink.create(TRANSACTION_SIZE_HINT);
        sink.beginObject();
        sink.field(K_HEIGHT, height);
        sink.name(K_TRANSACTION);
        t.writeJson(sink);
        sink.endObject();
        return json(sink);
    }

    /** Transactions touching an address (as sender or recipient), bounded scan as above. */
    static HttpResponse addressTransactions(NodeService node, HttpRequest req) {
        PublicAddress address = PublicAddress.of(req.getQueryParameter("address"));
        long depth = scanDepth(req);
        long tip = node.blockCount();
        // Never scan below the prune watermark: those bodies are gone and node.block() would throw.
        long floor = Math.max(Math.max(1, tip - depth + 1), node.prunedBelow());
        JsonSink sink = JsonSink.create(ADDRESS_TXS_SIZE_HINT);
        sink.beginObject();
        sink.hexUpper(K_ADDRESS, address.toBytes());
        sink.field(K_SCANNED_FROM, floor);
        sink.field(K_SCANNED_TO, tip);
        sink.name(K_TRANSACTIONS);
        sink.beginArray();
        int matched = 0;
        for (long h = tip; h >= floor && matched < ADDRESS_TXS_MAX; h--) {
            for (Transaction t : node.block(h).transactions()) {
                if ((address.equals(t.from()) || address.equals(t.to())) && matched < ADDRESS_TXS_MAX) {
                    // height is not part of Transaction's own JSON shape, so it is written here
                    // as an extra field on this projection instead of mutating the shared
                    // toJson()/writeJsonBody() result (the old code mutated a toJson() tree to
                    // splice this in).
                    sink.beginObject();
                    t.writeJsonBody(sink);
                    sink.field(K_HEIGHT, h);
                    sink.endObject();
                    matched++;
                }
            }
        }
        sink.endArray();
        sink.endObject();
        return json(sink);
    }

    private static long scanDepth(HttpRequest req) {
        String depthParam = req.getQueryParameter("depth");
        long depth = depthParam == null ? SCAN_DEPTH_DEFAULT : Long.parseLong(depthParam.trim());
        if (depth < 1 || depth > SCAN_DEPTH_MAX) {
            throw new IllegalArgumentException("depth must be in [1, " + SCAN_DEPTH_MAX + "]");
        }
        return depth;
    }

    /** Deployed-contract inspection: code presence/size/hash plus the account state. */
    static HttpResponse contractInfo(NodeService node, HttpRequest req) {
        PublicAddress address = PublicAddress.of(req.getQueryParameter("address"));
        byte[] code = node.contractCode(address);
        JsonSink sink = JsonSink.create(CONTRACT_SIZE_HINT);
        sink.beginObject();
        sink.hexUpper(K_ADDRESS, address.toBytes());
        sink.field(K_EXISTS, code != null);
        sink.field(K_BALANCE, node.balance(address));
        if (code != null) {
            sink.field(K_CODE_SIZE, code.length);
            // Lowercase, unlike the address above: ApiResponses.hex (Character.forDigit) is the
            // legacy encoder this mirrors, and the node deliberately emits both hex cases across
            // its JSON surface — see JsonSink's class Javadoc.
            sink.hexLower(K_CODE_HASH, sha256(code));
        }
        sink.endObject();
        return json(sink);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
