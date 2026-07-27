package rhizome.net;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
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
     * Hard cap on the snapshot chunk count a peer may advertise, mirroring app-node's
     * {@code MAX_SNAPSHOT_CHUNKS}: {@code SnapshotBootstrap} loops and pre-sizes on this
     * peer-controlled value, so an unbounded count is a CPU/memory DoS (audit F6).
     */
    private static final int MAX_SNAPSHOT_CHUNKS = 1_000_000;
    /**
     * Hard cap on one /orphan body: a full block, with the same headroom the node's own
     * /submit body cap allows. Bounds a hostile peer's reply before the codec parses it.
     */
    private static final long ORPHAN_CAP = Constants.MAX_BLOCK_SIZE_BYTES + 1024L;

    private final String baseUrl;
    /** The ORIGINAL (pre-DNS-pin) base URL, used for the {@link PeerTokenPolicy} trust check:
     *  pinning rewrites the host to an IP literal, which would never match a hostname-configured
     *  trusted peer. */
    private final String originalUrl;
    private final HttpClient client;
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
        this(baseUrl, blockPrivateHosts, newClient());
    }

    /**
     * As above, but reusing a caller-provided {@link HttpClient}. The sync loop creates one source per
     * peer per round; each JDK {@code HttpClient} spawns a selector-manager thread and its own
     * connection pool that is never closed, so building one per round churned threads/sockets and reused
     * no keep-alive connection (audit net #1). The DNS pin is still recomputed per source, so sharing the
     * client does not weaken the anti-rebinding guarantee — it only shares the transport.
     */
    public HttpPeerSource(String baseUrl, boolean blockPrivateHosts, HttpClient client) {
        this(baseUrl, blockPrivateHosts, client, REQUEST_DEADLINE);
    }

    /**
     * As above, presenting {@code peerToken} (nullable) as a bearer token on outbound requests.
     *
     * @deprecated presents the token to WHATEVER peer URL this source is pointed at, over any
     *     scheme — on a registry fed by unauthenticated /add_peer + PEX, any gossip-learned
     *     (often http://) peer receives the shared secret in cleartext. Use the
     *     {@link PeerTokenPolicy} constructor so the token only goes to explicitly configured
     *     peers over https.
     */
    @Deprecated
    public HttpPeerSource(String baseUrl, boolean blockPrivateHosts, HttpClient client, String peerToken) {
        this(baseUrl, blockPrivateHosts, client, PeerTokenPolicy.trustAll(peerToken));
    }

    /** As above, presenting the token only where {@code tokenPolicy} allows it. */
    public HttpPeerSource(String baseUrl, boolean blockPrivateHosts, HttpClient client,
                          PeerTokenPolicy tokenPolicy) {
        this(baseUrl, blockPrivateHosts, client, REQUEST_DEADLINE, tokenPolicy);
    }

    /** As above, with an explicit whole-exchange deadline (package-private for tests). */
    HttpPeerSource(String baseUrl, boolean blockPrivateHosts, HttpClient client, Duration requestDeadline) {
        this(baseUrl, blockPrivateHosts, client, requestDeadline, PeerTokenPolicy.trustAll(null));
    }

    /** As above, with an explicit whole-exchange deadline and a token policy. */
    HttpPeerSource(String baseUrl, boolean blockPrivateHosts, HttpClient client, Duration requestDeadline,
                   PeerTokenPolicy tokenPolicy) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.originalUrl = trimmed;
        this.baseUrl = PeerHosts.pin(trimmed, blockPrivateHosts);
        this.client = client;
        this.requestDeadline = requestDeadline;
        this.tokenPolicy = tokenPolicy;
    }

    /** A default JDK client with the standard connect timeout; callers that share one build it once. */
    public static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
        HttpRequest request = PeerAuth.withToken(HttpRequest.newBuilder(URI.create(baseUrl + path)),
                tokenPolicy.tokenFor(originalUrl))
            .timeout(requestDeadline).GET().build();
        try {
            // The request timeout only covers up to the response headers; bound the exchange
            // with an IDLE deadline (audit F1 + fixed-window fix): the /sync window is
            // legitimately huge, so a fixed whole-exchange deadline never converges on a slow
            // link. Every chunk read stamps forward progress; only a stalled drip dies.
            AtomicReference<AutoCloseable> openBody = new AtomicReference<>();
            AtomicLong lastActivity = new AtomicLong(System.nanoTime());
            return BodyReadDeadline.callIdle(requestDeadline, openBody, lastActivity, () -> {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("peer " + path + " returned " + response.statusCode());
                }
                InputStream in = new ProgressInputStream(response.body(), lastActivity);
                openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
                try (in) {
                    return BlockCodec.decodeStreamed(in, Constants.BLOCKS_PER_FETCH, Constants.MAX_BLOCK_SIZE_BYTES);
                }
            });
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
        String body;
        try {
            body = new String(getBytes("/state/snapshot/info", SCALAR_CAP, true), StandardCharsets.UTF_8);
        } catch (UnsupportedOperationException noSnapshot) {
            return null; // peer has no materialised snapshot (404) — not an error
        }
        try {
            JSONObject info = PeerJson.parseObject(body); // depth-bounded (audit F11)
            long pivotHeight = info.getLong("pivotHeight");
            byte[] stateRoot = rhizome.core.common.Utils.hexStringToByteArray(info.getString("stateRoot"));
            int chunks = info.getInt("chunks");
            // The chunk count is peer-controlled and SnapshotBootstrap loops/pre-sizes on it;
            // reject absurd values here so the peer is penalised instead of indulged (audit F6).
            if (chunks <= 0 || chunks > MAX_SNAPSHOT_CHUNKS) {
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
        // A peer predating the /headers endpoint answers 404; surface it as "unsupported"
        // so the synchronizer falls back to full-block sync (D7) instead of banning.
        byte[] body = getBytes("/headers?start=" + start + "&end=" + end, HEADER_STREAM_CAP, true);
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
        return getBytes(path, maxBytes, false);
    }

    private byte[] getBytes(String path, long maxBytes, boolean notFoundMeansUnsupported) {
        return fetch(path, maxBytes, notFoundMeansUnsupported ? NotFound.UNSUPPORTED : NotFound.ERROR);
    }

    /** 404 → {@code null} ("absent"); any other non-200 is a transport failure. */
    private byte[] getBytesOrNullOn404(String path, long maxBytes) {
        return fetch(path, maxBytes, NotFound.NULL);
    }

    /** How a 404 is surfaced: transport error, endpoint-unsupported, or plain "absent". */
    private enum NotFound { ERROR, UNSUPPORTED, NULL }

    /** Fetches {@code path}, applying the {@link NotFound} mode on a 404; throws on other errors. */
    private byte[] fetch(String path, long maxBytes, NotFound notFound) {
        HttpRequest request = PeerAuth.withToken(HttpRequest.newBuilder(URI.create(baseUrl + path)),
                tokenPolicy.tokenFor(originalUrl))
            .timeout(requestDeadline)
            .GET()
            .build();
        try {
            // Same whole-exchange deadline as blocks(): the request timeout alone would let a
            // slow-drip peer hang the sync thread mid-body (audit F1).
            AtomicReference<AutoCloseable> openBody = new AtomicReference<>();
            return BodyReadDeadline.call(requestDeadline, openBody, () -> {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    if (response.statusCode() == 404 && notFound == NotFound.UNSUPPORTED) {
                        throw new UnsupportedOperationException("peer lacks " + path);
                    }
                    if (response.statusCode() == 404 && notFound == NotFound.NULL) {
                        return null;
                    }
                    throw new IOException("peer " + path + " returned " + response.statusCode());
                }
                InputStream in = response.body();
                openBody.set(in); // publish so a deadline expiry can cancel the JDK exchange
                try (in) {
                    return readBounded(in, maxBytes, path);
                }
            });
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

    /** Reads the stream, aborting if it would exceed {@code maxBytes} (never buffers past the cap). */
    private static byte[] readBounded(InputStream in, long maxBytes, String path) throws IOException {
        // One byte over the cap is fetched to distinguish "exactly at cap" from "over".
        byte[] data = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
        if (data.length > maxBytes) {
            throw new IOException("peer " + path + " response exceeds " + maxBytes + " bytes");
        }
        return data;
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

    /** Signals a transport-level failure talking to the peer (distinct from a bad chain). */
    public static final class PeerUnavailableException extends RuntimeException {
        public PeerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Signals a MALFORMED peer response: the transport worked, but the peer served junk no
     * honest node would emit (an unparseable height/work/block, an out-of-range snapshot chunk
     * count). Distinct from {@link PeerUnavailableException} so the sync round can penalise the
     * peer's ban score instead of shrugging it off as an outage (audit F9). Unchecked because
     * it must propagate through the {@link PeerSource} interface, whose methods declare no
     * checked exceptions, up to the sync-round catch site.
     */
    public static final class PeerProtocolException extends RuntimeException {
        public PeerProtocolException(String message) {
            super(message);
        }

        public PeerProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
