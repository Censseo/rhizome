package rhizome.node;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.RoutingServlet;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import org.json.JSONObject;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.common.Constants;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.dto.TransactionDto;

import static io.activej.http.HttpMethod.GET;
import static io.activej.http.HttpMethod.POST;

/**
 * HTTP API of the node, over ActiveJ HTTP.
 *
 * <p>Robustness rules learned from Pandanite (§4.10 of the analysis): every
 * handler always produces a response (bad input → 400, never a crash or a hung
 * connection), range endpoints are hard-bounded before any work, and binary
 * payloads use the fixed-layout codec.
 */
public final class NodeApi {

    private NodeApi() {}

    public static AsyncServlet servlet(Reactor reactor, NodeService node) {
        return RoutingServlet.builder(reactor)
            .with(GET, "/block_count", req -> ok(text(String.valueOf(node.blockCount()))))
            .with(GET, "/total_work", req -> ok(json(new JSONObject().put("totalWork", node.totalWork().toString()))))
            .with(GET, "/difficulty", req -> ok(text(String.valueOf(node.difficulty()))))
            .with(GET, "/mempool", req -> ok(json(new JSONObject().put("size", node.mempoolSize()))))
            .with(GET, "/block", req -> guarded(() -> {
                long id = parseLong(req.getQueryParameter("blockId"));
                if (id < 1 || id > node.blockCount()) {
                    return badRequest("blockId out of range");
                }
                return json(node.block(id).toJson());
            }))
            .with(GET, "/wallet", req -> guarded(() -> {
                PublicAddress wallet = PublicAddress.of(req.getQueryParameter("address"));
                return json(new JSONObject()
                    .put("address", wallet.toHexString())
                    .put("balance", node.balance(wallet))
                    .put("nextNonce", node.nextNonce(wallet)));
            }))
            .with(GET, "/sync", req -> guarded(() -> sync(node, req)))
            .with(POST, "/add_transaction_json", req -> req.loadBody().map(body -> guardedResponse(() -> {
                Transaction t = Transaction.of(new JSONObject(body.getString(StandardCharsets.UTF_8)));
                return statusResponse(node.submitTransaction(t));
            })))
            .with(POST, "/add_transaction", req -> req.loadBody().map(body -> guardedResponse(() -> {
                Transaction t = Transaction.of(BinarySerializable.fromBuffer(body.getArray(), TransactionDto.class));
                return statusResponse(node.submitTransaction(t));
            })))
            .with(POST, "/submit", req -> req.loadBody().map(body -> guardedResponse(() -> {
                Block block = BlockCodec.decode(body.getArray());
                return statusResponse(node.submitBlock(block));
            })))
            .build();
    }

    private static HttpResponse sync(NodeService node, HttpRequest req) {
        long start = parseLong(req.getQueryParameter("start"));
        long end = parseLong(req.getQueryParameter("end"));
        if (start < 1 || end < start) {
            return badRequest("invalid range");
        }
        if (end - start + 1 > Constants.BLOCKS_PER_FETCH) {
            return badRequest("range too large (max " + Constants.BLOCKS_PER_FETCH + ")");
        }
        long cappedEnd = Math.min(end, node.blockCount());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long h = start; h <= cappedEnd; h++) {
            byte[] encoded = BlockCodec.encode(node.block(h));
            out.write(encoded, 0, encoded.length);
        }
        return HttpResponse.ok200()
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .withBody(out.toByteArray())
            .build();
    }

    // ---- helpers: every path returns a response ----

    private static Promise<HttpResponse> ok(HttpResponse response) {
        return Promise.of(response);
    }

    private static Promise<HttpResponse> guarded(Callable<HttpResponse> action) {
        return Promise.of(guardedResponse(action));
    }

    private static HttpResponse guardedResponse(Callable<HttpResponse> action) {
        try {
            return action.call();
        } catch (Exception e) {
            return badRequest(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static HttpResponse statusResponse(ExecutionStatus status) {
        int code = status == ExecutionStatus.SUCCESS ? 200 : 400;
        return HttpResponse.ofCode(code)
            .withJson(new JSONObject().put("status", status.name()).toString())
            .build();
    }

    private static long parseLong(String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing parameter");
        }
        return Long.parseLong(value.trim());
    }

    private static HttpResponse text(String body) {
        return HttpResponse.ok200().withPlainText(body).build();
    }

    private static HttpResponse json(JSONObject body) {
        return HttpResponse.ok200().withJson(body.toString()).build();
    }

    private static HttpResponse badRequest(String message) {
        return HttpResponse.ofCode(400)
            .withJson(new JSONObject().put("error", message).toString())
            .build();
    }
}
