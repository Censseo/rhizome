package rhizome.net;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import rhizome.core.common.Constants;

import org.json.JSONObject;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.HeaderCodec;
import rhizome.core.blockchain.LocalSaturationException;
import rhizome.core.blockchain.PeerSource;
import rhizome.crypto.SHA256Hash;

/**
 * {@link PeerSource} over a peer's HTTP node API, using the blocking JDK client
 * (the synchronizer runs on its own thread, so blocking I/O is fine and keeps
 * this free of the eventloop — and native-image friendly).
 */
public final class HttpPeerSource implements PeerSource {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HttpPeerSource.class);

    // Per-endpoint response caps: a hostile peer must never be able to hand us an
    // unbounded body. The scalar endpoints are tiny (a height, a ~78-digit work
    // value) — capping them small also kills the O(n^2) BigInteger-parse DoS on a
    // giant /total_work string. The block stream is bounded by what a well-behaved
    // peer can return for one fetch window.
    private static final int SCALAR_CAP = 4 * 1024;
    // A full block's JSON is hex-expanded binary, so it can be several times MAX_BLOCK_SIZE_BYTES; the
    // old 1 MiB cap rejected the fork-detection /block fetch on any large legitimate block (audit net
    // S-5). Bound it against the real block-size limit with headroom for the hex+JSON overhead instead.
    private static final long JSON_BLOCK_CAP = 4L * Constants.MAX_BLOCK_SIZE_BYTES;
    // The /sync body is now stream-decoded block-by-block (see blocks()), so there is no
    // single-shot window buffer to cap here.
    // Headers are tiny and fixed-ish (≈156 B + a few uncle records); 1 KiB each is a
    // generous ceiling that still bounds a hostile /headers response hard.
    private static final long HEADER_STREAM_CAP =
        (long) Constants.BLOCK_HEADERS_PER_FETCH * 1024 + 64 * 1024;
    /**
     * Wall-clock deadline for one WHOLE peer exchange (send + full response-body read).
     * {@code HttpRequest.timeout} alone only covers up to the response headers, so a slow-drip
     * peer could otherwise stall the single sync thread in {@code InputStream.read} forever;
     * the deadline is enforced by {@link BodyReadDeadline} (audit F1). The /sync window is the
     * one exception: it is legitimately huge (up to BLOCKS_PER_FETCH full blocks), so it runs
     * under this value as an IDLE bound instead of a total one — the deadline slips while the
     * peer keeps delivering and a stalled drip still dies (audit: fixed /sync deadline).
     */
    private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(30);
    /**
     * Hard cap on one /orphan body: a full block, with the same headroom the node's own
     * /submit body cap allows. Bounds a hostile peer's reply before the codec parses it.
     */
    private static final long ORPHAN_CAP = Constants.MAX_BLOCK_SIZE_BYTES + 1024L;
    /**
     * Extra sends allowed after a 429 before the exchange gives up. Two is enough to ride out a
     * peer's one-second budget window without letting a peer that refuses everything hold the
     * sync thread: worst case is {@link #THROTTLE_BACKOFF_MILLIS} summed, well inside one
     * {@link #REQUEST_DEADLINE}.
     */
    private static final int MAX_THROTTLE_RETRIES = 2;
    /** Waits before each retry. Sized against the node's 1 s sliding rate-limit window. */
    private static final long[] THROTTLE_BACKOFF_MILLIS = {250L, 500L};
    /**
     * Ceiling on an honoured {@code Retry-After}. The header is peer-controlled, so an
     * unclamped value would let a peer park our sync thread for as long as it likes.
     */
    private static final long MAX_THROTTLE_WAIT_MILLIS = 1000L;

    /** The ORIGINAL (trimmed) base URL. Pinning happens per request via
     *  {@link PeerExchange#pinnedRequest} (resolution is cached); keeping the original also feeds
     *  the {@link PeerTokenPolicy} trust check, which would never match a pinned IP literal. */
    private final String originalUrl;
    private final boolean blockPrivateHosts;
    private final PeerExchange exchange;
    private final Duration requestDeadline;
    /** Decides whether the RHIZOME_PEER_TOKEN secret may be presented to this peer (configured
     *  + https only); never logged (audit: peer token exfiltration via gossip). */
    private final PeerTokenPolicy tokenPolicy;

    public HttpPeerSource(String baseUrl) {
        this(baseUrl, false);
    }

    /**
     * @param blockPrivateHosts when true (production mainnet), the peer host is resolved and
     *        refused if it maps to a non-routable address, and the connection is pinned to the
     *        resolved IP so a DNS rebind cannot redirect the fetch to an internal service (SSRF).
     */
    public HttpPeerSource(String baseUrl, boolean blockPrivateHosts) {
        this(baseUrl, blockPrivateHosts, new PeerExchange());
    }

    /**
     * As above, but sharing a caller-provided {@link PeerExchange}. The sync loop creates one source per
     * peer per round; each JDK {@code HttpClient} spawns a selector-manager thread and its own
     * connection pool that is never closed, so building one per round churned threads/sockets and reused
     * no keep-alive connection (audit net #1). The DNS pin runs per request
     * ({@link PeerExchange#pinnedRequest}), so sharing the exchange does not weaken the
     * anti-rebinding guarantee — it only shares the transport.
     */
    public HttpPeerSource(String baseUrl, boolean blockPrivateHosts, PeerExchange exchange) {
        this(baseUrl, blockPrivateHosts, exchange, REQUEST_DEADLINE);
    }

    /** As above, presenting the token only where {@code tokenPolicy} allows it. */
    public HttpPeerSource(String baseUrl, boolean blockPrivateHosts, PeerExchange exchange,
                          PeerTokenPolicy tokenPolicy) {
        this(baseUrl, blockPrivateHosts, exchange, REQUEST_DEADLINE, tokenPolicy);
    }

    /** As above, with an explicit whole-exchange deadline (package-private for tests). */
    HttpPeerSource(String baseUrl, boolean blockPrivateHosts, PeerExchange exchange, Duration requestDeadline) {
        this(baseUrl, blockPrivateHosts, exchange, requestDeadline, PeerTokenPolicy.none());
    }

    /** As above, with an explicit whole-exchange deadline and a token policy. */
    HttpPeerSource(String baseUrl, boolean blockPrivateHosts, PeerExchange exchange, Duration requestDeadline,
                   PeerTokenPolicy tokenPolicy) {
        this.originalUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.blockPrivateHosts = blockPrivateHosts;
        this.exchange = exchange;
        this.requestDeadline = requestDeadline;
        this.tokenPolicy = tokenPolicy;
    }

    @Override
    public long height() {
        String body = getString("/block_count", SCALAR_CAP);
        try {
            return Long.parseLong(body.trim());
        } catch (NumberFormatException e) {
            throw new PeerProtocolException("peer /block_count is not a number", e);
        }
    }

    @Override
    public BigInteger totalWork() {
        String body = getString("/total_work", SCALAR_CAP);
        try {
            // Depth-bounded: a peer-controlled body must never reach the recursive parser
            // unbounded (StackOverflowError → fatal, audit F11).
            return new BigInteger(PeerJson.parseObject(body).getString("totalWork"));
        } catch (RuntimeException e) {
            throw new PeerProtocolException("peer /total_work is malformed", e);
        }
    }

    @Override
    public SHA256Hash blockHash(long height) {
        String body = getString("/block?blockId=" + height, JSON_BLOCK_CAP);
        try {
            // Depth-bounded parse (audit F11): an over-deep block JSON is a protocol error,
            // not a StackOverflowError.
            return Block.of(PeerJson.parseObject(body)).hash();
        } catch (RuntimeException e) {
            throw new PeerProtocolException("peer /block is malformed", e);
        }
    }

    @Override
    public List<Block> blocks(long start, long end) {
        // Stream-decode block-by-block so peak memory is ~one block, not the whole ~800 MiB
        // window a hostile peer could otherwise force us to buffer (audit M5).
        String path = "/sync?start=" + start + "&end=" + end;
        HttpRequest request = PeerExchange.pinnedRequest(originalUrl, path, blockPrivateHosts,
                tokenPolicy, originalUrl)
            .timeout(requestDeadline).GET().build();
        // Retry OUTSIDE the deadline wrapper (see backoffBeforeRetry): each attempt gets its own
        // whole-exchange budget, and the wait never eats into the previous one.
        for (int attempt = 0; ; attempt++) {
            try {
                // The request timeout only covers up to the response headers; bound the exchange
                // with an IDLE deadline (audit F1 + fixed-window fix): the /sync window is
                // legitimately huge, so a fixed whole-exchange deadline never converges on a slow
                // link. Every chunk read stamps forward progress; only a stalled drip dies.
                AtomicReference<AutoCloseable> openBody = new AtomicReference<>();
                AtomicLong lastActivity = new AtomicLong(System.nanoTime());
                return BodyReadDeadline.callIdle(requestDeadline, openBody, lastActivity, () -> {
                    HttpResponse<InputStream> response = exchange.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() != 200) {
                        response.body().close();
                        throw statusFailure(path, response);
                    }
                    InputStream in = new ProgressInputStream(response.body(), lastActivity);
                    openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
                    try (in) {
                        return BlockCodec.decodeStreamed(in, Constants.BLOCKS_PER_FETCH, Constants.MAX_BLOCK_SIZE_BYTES);
                    }
                });
            } catch (ThrottledException throttled) {
                if (!backoffBeforeRetry(path, attempt, throttled)) {
                    throw new PeerUnavailableException("peer request failed: " + path, throttled);
                }
            } catch (BodyReadSaturatedException e) {
                // LOCAL backpressure, before any I/O reached the peer: distinct from
                // PeerUnavailableException so the sync round cannot read it as a peer failure
                // and penalise an honest peer for our own load.
                throw new LocalSaturationException("local body-read pool saturated: " + path, e);
            } catch (IOException e) {
                throw new PeerUnavailableException("peer request failed: " + path, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PeerUnavailableException("interrupted: " + path, e);
            }
        }
    }

    @Override
    public long prunedBelow() {
        String body = getString("/info", SCALAR_CAP);
        try {
            return PeerJson.parseObject(body).optLong("prunedBelow", 0); // depth-bounded (audit F11)
        } catch (RuntimeException e) {
            throw new PeerProtocolException("peer /info is malformed", e);
        }
    }

    @Override
    public SnapshotInfo snapshotInfo() {
        byte[] bytes = getBytesOrNullOn404("/state/snapshot/info", SCALAR_CAP);
        if (bytes == null) {
            return null; // peer has no materialised snapshot (404) — not an error
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        try {
            JSONObject info = PeerJson.parseObject(body); // depth-bounded (audit F11)
            long pivotHeight = info.getLong("pivotHeight");
            byte[] stateRoot = rhizome.core.common.Utils.hexStringToByteArray(info.getString("stateRoot"));
            int chunks = info.getInt("chunks");
            // The chunk count is peer-controlled and SnapshotBootstrap loops/pre-sizes on it;
            // reject absurd values here so the peer is penalised instead of indulged (audit F6).
            if (chunks <= 0 || chunks > Constants.MAX_SNAPSHOT_CHUNKS) {
                throw new PeerProtocolException(
                    "peer advertised out-of-range snapshot chunk count: " + chunks);
            }
            return new SnapshotInfo(pivotHeight, stateRoot, chunks);
        } catch (PeerProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PeerProtocolException("peer /state/snapshot/info is malformed", e);
        }
    }

    @Override
    public byte[] snapshotChunk(int index) {
        // Chunks are ~1 MiB by construction; the cap only bounds a hostile response
        // (one oversized entry — big contract code — may push a chunk past the target).
        return getBytes("/state/snapshot/chunk?index=" + index, 16L * 1024 * 1024);
    }

    @Override
    public List<BlockHeader> headers(long start, long end) {
        // A peer predating the /headers endpoint answers 404; surface it as null so the
        // synchronizer falls back to full-block sync (D7) instead of banning.
        byte[] body = getBytesOrNullOn404("/headers?start=" + start + "&end=" + end, HEADER_STREAM_CAP);
        if (body == null) {
            return null;
        }
        return HeaderCodec.decodeAll(body);
    }

    @Override
    public Block orphan(SHA256Hash hash) {
        // A 404 means the peer does not hold this orphan; a legacy peer predating the /orphan
        // route answers 404 on the route itself, indistinguishable from "orphan absent" — so
        // 404 maps to null (the synchronizer then keeps the block unverifiable and retries
        // elsewhere) rather than to a ban-score-earning protocol violation.
        byte[] body = getBytesOrNullOn404("/orphan?hash=" + hash.toHexString(), ORPHAN_CAP);
        if (body == null) {
            return null;
        }
        try {
            return BlockCodec.decode(body);
        } catch (RuntimeException e) {
            // Junk no honest node would serve — misbehaviour, ban-score eligible (audit F9).
            throw new PeerProtocolException("peer /orphan body is malformed", e);
        }
    }

    private String getString(String path, long maxBytes) {
        return new String(getBytes(path, maxBytes), StandardCharsets.UTF_8);
    }

    private byte[] getBytes(String path, long maxBytes) {
        return fetch(path, maxBytes, false);
    }

    /** 404 → {@code null} ("absent"); any other non-200 is a transport failure. */
    private byte[] getBytesOrNullOn404(String path, long maxBytes) {
        return fetch(path, maxBytes, true);
    }

    /** Fetches {@code path}, with a 404 mapping to {@code null} when {@code nullOn404}; throws on other errors. */
    private byte[] fetch(String path, long maxBytes, boolean nullOn404) {
        HttpRequest request = PeerExchange.pinnedRequest(originalUrl, path, blockPrivateHosts,
                tokenPolicy, originalUrl)
            .timeout(requestDeadline)
            .GET()
            .build();
        for (int attempt = 0; ; attempt++) {
            try {
                // Same whole-exchange deadline as blocks(): the request timeout alone would let a
                // slow-drip peer hang the sync thread mid-body (audit F1).
                AtomicReference<AutoCloseable> openBody = new AtomicReference<>();
                return BodyReadDeadline.call(requestDeadline, openBody, () -> {
                    HttpResponse<InputStream> response = exchange.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() != 200) {
                        response.body().close();
                        if (response.statusCode() == 404 && nullOn404) {
                            return null;
                        }
                        throw statusFailure(path, response);
                    }
                    InputStream in = response.body();
                    openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
                    try (in) {
                        return PeerExchange.readBounded(in, maxBytes, "peer " + path + " response");
                    }
                });
            } catch (ThrottledException throttled) {
                if (!backoffBeforeRetry(path, attempt, throttled)) {
                    throw new PeerUnavailableException("peer request failed: " + path, throttled);
                }
            } catch (BodyReadSaturatedException e) {
                // LOCAL backpressure (see blocks()): never a peer fault.
                throw new LocalSaturationException("local body-read pool saturated: " + path, e);
            } catch (IOException e) {
                throw new PeerUnavailableException("peer request failed: " + path, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PeerUnavailableException("interrupted: " + path, e);
            }
        }
    }

    /**
     * Maps a non-200 to the exception the caller's retry policy keys on: {@link ThrottledException}
     * for 429 (pacing — retryable), a plain {@link IOException} for everything else (including the
     * 503 a peer serves while it is itself reorging, which must stay a one-shot "unavailable" so
     * the round moves on rather than waiting on a peer that is busy for seconds).
     */
    private static IOException statusFailure(String path, HttpResponse<InputStream> response) {
        String message = "peer " + path + " returned " + response.statusCode();
        if (response.statusCode() != 429) {
            return new IOException(message);
        }
        return new ThrottledException(message, retryAfterMillis(response));
    }

    /**
     * Parses {@code Retry-After} as delta-seconds, clamped to {@link #MAX_THROTTLE_WAIT_MILLIS};
     * {@code -1} when absent or unparseable. The HTTP-date form is not honoured: it needs a clock
     * comparison against a peer-supplied timestamp, and Rhizome's own nodes never send it.
     */
    private static long retryAfterMillis(HttpResponse<InputStream> response) {
        return response.headers().firstValue("Retry-After").map(raw -> {
            try {
                long seconds = Long.parseLong(raw.trim());
                return seconds < 0 ? -1L : Math.min(seconds * 1000L, MAX_THROTTLE_WAIT_MILLIS);
            } catch (NumberFormatException e) {
                return -1L;
            }
        }).orElse(-1L);
    }

    /**
     * Waits out a peer's rate-limit window, returning false once the retry budget is spent (the
     * caller then fails the exchange as unavailable, exactly as it did before backoff existed —
     * so a 429 still never earns ban score).
     *
     * <p>Called OUTSIDE {@link BodyReadDeadline}: the wait must not consume the whole-exchange
     * deadline of either the attempt that was refused or the one that follows. The refused
     * response carried no body, so nothing is left open across the wait.
     */
    private static boolean backoffBeforeRetry(String path, int attempt, ThrottledException throttled) {
        if (attempt >= MAX_THROTTLE_RETRIES) {
            log.debug("peer kept throttling {} after {} retries; treating as unavailable",
                path, MAX_THROTTLE_RETRIES);
            return false;
        }
        long wait = throttled.retryAfterMillis >= 0
            ? throttled.retryAfterMillis
            : THROTTLE_BACKOFF_MILLIS[attempt];
        log.debug("peer throttled {}; waiting {} ms before retry {}", path, wait, attempt + 1);
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false; // shutting down: give up rather than swallow the interrupt
        }
        return true;
    }

    /**
     * Filter stream stamping {@code lastActivityNanos} on every successful read, so the idle
     * deadline over the /sync body ({@link BodyReadDeadline#callIdle}) sees a slow-but-alive
     * peer as progressing and only a stalled drip expires.
     */
    private static final class ProgressInputStream extends FilterInputStream {
        private final AtomicLong lastActivityNanos;

        ProgressInputStream(InputStream in, AtomicLong lastActivityNanos) {
            super(in);
            this.lastActivityNanos = lastActivityNanos;
        }

        @Override
        public int read() throws IOException {
            int r = super.read();
            if (r >= 0) {
                lastActivityNanos.set(System.nanoTime());
            }
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                lastActivityNanos.set(System.nanoTime());
            }
            return n;
        }
    }

    /**
     * A peer answered 429: it is PACING us, not failing. The node weights {@code /sync} at one
     * unit per block requested against a per-IP budget (1000 units per sliding second), so a
     * syncing node that applies blocks faster than ~1000/s out-runs its peer's budget and gets
     * refused — the peer is healthy and the range is valid. Surfacing that as
     * {@link PeerUnavailableException} aborts the whole round for that peer and forfeits ~10 s
     * until the next one, where waiting out the peer's window costs a fraction of a second.
     * Package-private and never escapes: {@link #backoffBeforeRetry} either consumes it or the
     * exchange ends as {@code PeerUnavailableException}, exactly as before.
     */
    static final class ThrottledException extends IOException {
        /** Peer-requested wait, or -1 when it sent no (parseable) {@code Retry-After}. */
        private final transient long retryAfterMillis;

        ThrottledException(String message, long retryAfterMillis) {
            super(message);
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    /**
     * Signals a transport-level failure talking to the peer (distinct from a bad chain).
     * Extends the lib-core type so the synchronizers — which must not depend on the HTTP
     * transport — can re-throw it out of their phases as "unavailable, retry later"
     * instead of mapping it to a ban-earning PEER_INVALID.
     */
    public static final class PeerUnavailableException extends rhizome.core.blockchain.PeerUnavailableException {
        public PeerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Signals a MALFORMED peer response: the transport worked, but the peer served junk no
     * honest node would emit (an unparseable height/work/block, an out-of-range snapshot chunk
     * count). Distinct from {@link PeerUnavailableException} so the sync round can penalise the
     * peer's ban score instead of shrugging it off as an outage (audit F9). Extends the
     * lib-core type so the ban signal is part of the port's vocabulary, not this adapter's;
     * the nested name keeps the net-layer call sites unchanged.
     */
    public static final class PeerProtocolException extends rhizome.core.blockchain.PeerProtocolException {
        public PeerProtocolException(String message) {
            super(message);
        }

        public PeerProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
