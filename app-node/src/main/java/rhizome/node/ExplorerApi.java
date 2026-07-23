package rhizome.node;

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.json.JSONObject;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;

import static rhizome.node.ApiResponses.badRequest;
import static rhizome.node.ApiResponses.gone;
import static rhizome.node.ApiResponses.hex;
import static rhizome.node.ApiResponses.json;
import static rhizome.node.ApiResponses.notFound;
import static rhizome.node.ApiResponses.parseLong;

/**
 * Explorer read endpoints: block ranges and single blocks, bounded tip-backward
 * transaction scans, address history and contract inspection. These fully decode
 * blocks under the consensus lock, so their rate-limit weighting lives in
 * {@link NodeApi#requestCost}.
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
        org.json.JSONArray arr = new org.json.JSONArray();
        for (long h = start; h <= cappedEnd; h++) {
            var block = (rhizome.core.block.BlockImpl) node.block(h);
            arr.put(new JSONObject()
                .put("height", h)
                .put("hash", block.hash().toHexString())
                .put("timestamp", block.timestamp())
                .put("difficulty", block.difficulty())
                .put("txCount", block.transactions().size())
                .put("uncles", block.uncles().size()));
        }
        return json(new JSONObject().put("blocks", arr).put("height", node.blockCount()));
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
        return json(node.block(id).toJson());
    }

    /** Balance and next nonce of an address: {@code GET /wallet?address=<hex50>}. */
    static HttpResponse wallet(NodeService node, HttpRequest req) {
        PublicAddress wallet = PublicAddress.of(req.getQueryParameter("address"));
        return json(new JSONObject()
            .put("address", wallet.toHexString())
            .put("balance", node.balance(wallet))
            .put("nextNonce", node.nextNonce(wallet)));
    }

    /**
     * Looks a transaction up by content hash (txid) with a bounded tip-backward
     * scan — the node keeps no txid index, so this trades depth for memory like
     * the /logs cursor does. {@code ?depth=} widens the scan up to the cap.
     */
    static HttpResponse findTransaction(NodeService node, HttpRequest req) {
        String txid = req.getQueryParameter("txid");
        if (txid == null || txid.length() != 64) {
            return badRequest("txid must be 64 hex chars");
        }
        long depth = scanDepth(req);
        long tip = node.blockCount();
        // Never scan below the prune watermark: those bodies are gone and node.block() would throw.
        long floor = Math.max(Math.max(1, tip - depth + 1), node.prunedBelow());
        for (long h = tip; h >= floor; h--) {
            for (Transaction t : node.block(h).transactions()) {
                if (t.hashContents().toHexString().equalsIgnoreCase(txid)) {
                    return json(new JSONObject()
                        .put("height", h)
                        .put("transaction", t.toJson()));
                }
            }
        }
        return notFound("transaction not found in scanned range (deepen with ?depth=)");
    }

    /** Transactions touching an address (as sender or recipient), bounded scan as above. */
    static HttpResponse addressTransactions(NodeService node, HttpRequest req) {
        PublicAddress address = PublicAddress.of(req.getQueryParameter("address"));
        long depth = scanDepth(req);
        long tip = node.blockCount();
        // Never scan below the prune watermark: those bodies are gone and node.block() would throw.
        long floor = Math.max(Math.max(1, tip - depth + 1), node.prunedBelow());
        org.json.JSONArray arr = new org.json.JSONArray();
        for (long h = tip; h >= floor && arr.length() < ADDRESS_TXS_MAX; h--) {
            for (Transaction t : node.block(h).transactions()) {
                if ((address.equals(t.from()) || address.equals(t.to())) && arr.length() < ADDRESS_TXS_MAX) {
                    arr.put(t.toJson().put("height", h));
                }
            }
        }
        return json(new JSONObject()
            .put("address", address.toHexString())
            .put("scannedFrom", floor)
            .put("scannedTo", tip)
            .put("transactions", arr));
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
        JSONObject out = new JSONObject()
            .put("address", address.toHexString())
            .put("exists", code != null)
            .put("balance", node.balance(address));
        if (code != null) {
            out.put("codeSize", code.length).put("codeHash", sha256Hex(code));
        }
        return json(out);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
