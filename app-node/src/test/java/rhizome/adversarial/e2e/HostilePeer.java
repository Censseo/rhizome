package rhizome.adversarial.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.crypto.SHA256Hash;
import rhizome.node.RhizomeNode;

/**
 * A real HTTP server that speaks the peer protocol and lies.
 *
 * <p>The in-process lying peer in {@code lib-core} proves the synchronizer's decisions. It cannot
 * prove that those decisions survive the transport: the claims here arrive as bytes through the
 * same {@code HttpPeerSource} a real peer would use, with real parsing, real size caps and real
 * deadlines between the lie and the code that judges it. Historically that gap is where the
 * damage was — an unbounded response body, a claim parsed before it was bounded, a malformed
 * window that escaped the sync pass.
 *
 * <p>The default posture is a peer that claims an enormous chain and serves nothing that proves
 * it. Each builder method replaces one answer, so a scenario changes exactly one thing about an
 * otherwise coherent peer and can attribute the outcome to it.
 */
final class HostilePeer implements AutoCloseable {

    private final HttpServer server;
    private final String url;

    private HostilePeer(HttpServer server) {
        this.server = server;
        this.url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String url() {
        return url;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static Builder builder() {
        return new Builder();
    }

    /** Fabricates a structurally plausible block that has paid no proof of work at all. */
    static Block unprovenBlock(long height, int claimedDifficulty) {
        return BlockImpl.builder()
            .id((int) height)
            .timestamp(System.currentTimeMillis())
            .difficulty(claimedDifficulty)
            .lastBlockHash(SHA256Hash.random())
            .merkleRoot(SHA256Hash.random())
            .nonce(SHA256Hash.random())
            .build();
    }

    /** The self-framing concatenation {@code /sync} serves, filled with unproven blocks. */
    static byte[] unprovenWindow(long start, long end, int claimedDifficulty) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long height = start; height <= end; height++) {
            out.writeBytes(BlockCodec.encode(unprovenBlock(height, claimedDifficulty)));
        }
        return out.toByteArray();
    }

    static final class Builder {

        private long blockCount = 5_000;
        private Supplier<String> totalWork = () -> BigInteger.TWO.pow(200).toString();
        private RhizomeNode sharesGenesisWith;
        private Supplier<byte[]> syncBody = () -> unprovenWindow(2, 33, 30);
        private int failEverythingWith;
        private boolean dripForever;

        /** The chain length this peer claims to have. */
        Builder claimsHeight(long height) {
            this.blockCount = height;
            return this;
        }

        /** The cumulative work this peer claims — free to say, expensive for the victim to check. */
        Builder claimsWork(Supplier<String> work) {
            this.totalWork = work;
            return this;
        }

        /**
         * Answers the fork-detection probe at height 1 with {@code node}'s real genesis, so the
         * victim sees a common ancestor and proceeds to ask for the branch. Without this the peer
         * reads as a different network and never reaches the interesting code.
         */
        Builder sharesGenesisWith(RhizomeNode node) {
            this.sharesGenesisWith = node;
            return this;
        }

        Builder serves(Supplier<byte[]> syncBody) {
            this.syncBody = syncBody;
            return this;
        }

        /** Answers every request with {@code status} — the broken-peer, not lying-peer, shape. */
        Builder failsEverythingWith(int status) {
            this.failEverythingWith = status;
            return this;
        }

        /** Sends headers, then a byte every few seconds, forever. */
        Builder dripsForever() {
            this.dripForever = true;
            return this;
        }

        HostilePeer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> handle(exchange));
                server.start();
                return new HostilePeer(server);
            } catch (IOException e) {
                throw new UncheckedIOException("could not start the hostile peer", e);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            try {
                if (failEverythingWith != 0) {
                    respond(exchange, failEverythingWith, "no".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (dripForever) {
                    drip(exchange);
                    return;
                }
                switch (path) {
                    case "/block_count" -> respond(exchange, 200,
                        String.valueOf(blockCount).getBytes(StandardCharsets.UTF_8));
                    case "/total_work" -> respond(exchange, 200,
                        totalWork.get().getBytes(StandardCharsets.UTF_8));
                    case "/block" -> respond(exchange, 200, blockJson(exchange));
                    case "/sync" -> respond(exchange, 200, syncBody.get());
                    case "/peers" -> respond(exchange, 200, "[]".getBytes(StandardCharsets.UTF_8));
                    default -> respond(exchange, 404, new byte[0]);
                }
            } finally {
                exchange.close();
            }
        }

        private byte[] blockJson(HttpExchange exchange) {
            String query = exchange.getRequestURI().getQuery();
            long height = 1;
            if (query != null && query.startsWith("blockId=")) {
                try {
                    height = Long.parseLong(query.substring("blockId=".length()));
                } catch (NumberFormatException ignored) {
                    // the victim asked for something unparseable; answer as if for height 1
                }
            }
            if (height == 1 && sharesGenesisWith != null) {
                return sharesGenesisWith.engine().blockAt(1).toJson().toString()
                    .getBytes(StandardCharsets.UTF_8);
            }
            return unprovenBlock(height, 30).toJson().toString().getBytes(StandardCharsets.UTF_8);
        }

        private static void drip(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < 200; i++) {
                out.write('0');
                out.flush();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
