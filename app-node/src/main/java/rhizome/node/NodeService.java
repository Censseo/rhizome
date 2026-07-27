package rhizome.node;

import rhizome.net.PeerRegistry;
import rhizome.net.RateLimiter;

import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ContractProcessor.ContractLog;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.transaction.Transaction;

/**
 * Facade the node API serves over: the chain engine plus the mempool. Keeps the
 * HTTP layer free of consensus logic — it only marshals requests to these calls.
 */
public final class NodeService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NodeService.class);

    private final ChainEngine engine;
    private final MemPool mempool;
    private volatile java.util.function.Consumer<Block> onBlockAccepted;
    private volatile java.util.function.Consumer<Transaction> onTransactionAccepted;
    private volatile PeerRegistry peers;
    private volatile java.util.function.LongFunction<List<ContractLog>> logSource;
    private volatile java.util.function.Function<PublicAddress, byte[]> codeSource;
    private volatile java.util.function.LongFunction<List<rhizome.core.box.BoxProcessor.BoxEvent>> boxEventSource;
    private volatile java.util.function.LongFunction<List<rhizome.core.token.TokenProcessor.TokenEvent>> tokenEventSource;
    private volatile rhizome.core.blockchain.ContractProcessor contracts;
    private volatile rhizome.core.state.snapshot.StateSource snapshotSource;
    private volatile java.nio.file.Path snapshotSpoolDir;
    private volatile MaterializedSnapshot snapshot;

    /** Entry bound per snapshot chunk (bytes are bounded separately by the exporter). */
    static final int SNAPSHOT_CHUNK_ENTRIES = 4096;

    /**
     * A consistent full-state export frozen at one (pivotHeight, stateRoot) point, backed by a
     * spool file instead of the heap: the exporter's chunks are stream-encoded into the file at
     * capture time and only their {@code (offset, length)} index is retained (a few bytes per
     * chunk), so the RAM peak of a materialisation is one chunk rather than the whole state —
     * and an idle node serving snapshots holds none of it on the heap (audit: snapshot export
     * RAM peak). Chunk reads are positional ({@link java.nio.channels.FileChannel#read} with an
     * explicit offset), which is thread-safe across concurrent API requests sharing one snapshot.
     * {@link #close()} releases the file; the owner closes a snapshot when it is replaced and at
     * service shutdown.
     */
    static final class MaterializedSnapshot implements AutoCloseable {
        private final long pivotHeight;
        private final byte[] stateRoot;
        private final java.nio.file.Path file;
        private final long[] offsets;
        private final long[] lengths;
        private final java.nio.channels.FileChannel channel;

        private MaterializedSnapshot(long pivotHeight, byte[] stateRoot, java.nio.file.Path file,
                                     long[] offsets, long[] lengths,
                                     java.nio.channels.FileChannel channel) {
            this.pivotHeight = pivotHeight;
            this.stateRoot = stateRoot;
            this.file = file;
            this.offsets = offsets;
            this.lengths = lengths;
            this.channel = channel;
        }

        long pivotHeight() {
            return pivotHeight;
        }

        byte[] stateRoot() {
            return stateRoot;
        }

        /** The spool the chunks are read from (visible for the replacement/deletion tests). */
        java.nio.file.Path file() {
            return file;
        }

        int chunkCount() {
            return lengths.length;
        }

        /** On-wire length of chunk {@code index}, without reading its bytes. */
        long chunkLength(int index) {
            return lengths[index];
        }

        byte[] chunkBytes(int index) {
            if (index < 0 || index >= lengths.length) {
                throw new IndexOutOfBoundsException(index);
            }
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(Math.toIntExact(lengths[index]));
            try {
                while (buf.hasRemaining()) {
                    if (channel.read(buf, offsets[index] + buf.position()) < 0) {
                        throw new java.io.EOFException("snapshot spool truncated at chunk " + index);
                    }
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("snapshot chunk read failed", e);
            }
            return buf.array();
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (java.io.IOException e) {
                log.warn("Snapshot spool {} could not be closed", file, e);
            }
            try {
                java.nio.file.Files.deleteIfExists(file);
            } catch (java.io.IOException e) {
                log.warn("Snapshot spool {} could not be deleted", file, e);
            }
        }
    }

    /** Maximum blocks a single /logs catch-up scan spans, so agents poll in bounded chunks. */
    public static final int LOG_SCAN_WINDOW = 128;

    /** Maximum boxes a single /scan query examines, so a scan runs in bounded, pollable chunks. */
    public static final int BOX_SCAN_WINDOW = 512;

    private final ScanRegistry scans = new ScanRegistry();

    /** Bound on peer admissions queued off-loop at once; excess {@code /add_peer} calls are shed. */
    private static final int MAX_PENDING_ADMISSIONS = 256;

    /**
     * Off-loop worker for peer admission. Admitting a peer resolves DNS (ban check, routability,
     * subnet bucket); that blocking work must never run on the ActiveJ event-loop thread that
     * serves {@code /add_peer}, or a peer whose hostname resolves slowly (or times out) would
     * freeze the entire node. Daemon so it never holds the JVM open.
     *
     * <p>The queue is <b>bounded</b> (audit): the earlier single-thread executor used an unbounded
     * queue, so a flood of {@code /add_peer} — each retaining a URL and each an ~5 s blocking DNS
     * resolve — grew the queue ~1000/s and exhausted the heap while starving honest peers behind
     * it. A full queue now sheds the request ({@code AbortPolicy}, caught below).
     */
    private final java.util.concurrent.ExecutorService peerAdmission =
        new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(MAX_PENDING_ADMISSIONS),
            r -> {
                Thread t = new Thread(r, "rhizome-peer-admit");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** URLs currently queued/running for admission, so duplicate {@code /add_peer} coalesce. */
    private final java.util.Set<String> pendingAdmissions =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Hosts currently queued/running for admission, in lock-step with {@link #pendingAdmissions}.
     * The URL set alone coalesces only exact duplicates; an attacker varies the port/path of one
     * slow-resolving host to queue an unbounded number of full blocking DNS resolves for the SAME
     * host against the single admission thread (audit: add_peer coalescing bypass by port
     * variation). Coalescing by host too means one slow host occupies at most one queue slot;
     * the cost is that two honest peers on different ports of one host must wait for the first
     * admission to finish — an acceptable trade against the bounded queue.
     */
    private final java.util.Set<String> pendingAdmissionHosts =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Global (all-clients) cap on the memory-hard PoW verifications that {@code /submit} can trigger
     * per second. The per-IP HTTP limiter allows ~125 submits/s/IP with no aggregate bound, so a
     * single IP resending one PoW-free block (public parent hash, in-window id, garbage nonce) can
     * pin the single event-loop thread on ~40 memory-hard hashes/s and, via the shared consensus
     * lock, stall block production and sync (audit F1). This single-bucket limiter bounds the total
     * across every source IP, well below loop capacity; an over-budget submit is dropped WITHOUT
     * hashing. Declining to speculatively verify is safe: both verification sites already drop
     * non-verifying blocks (orphan admission is best-effort), and honest blocks still arrive via
     * sync, which calls the engine directly and is not gated here.
     */
    static final int SUBMIT_POW_MAX_PER_SEC = 25;
    private final RateLimiter submitPowGate;

    /**
     * Aggregate (all-IP) cap on mempool transaction admissions per second, bounding the Ed25519
     * verifications {@code /add_transaction} can trigger on the event-loop thread. {@code /submit}
     * has submitPowGate, but {@code /add_transaction} had no equivalent: each admission runs one
     * ~100 µs signature verify inline (MemPool.addTransaction), INVALID signatures are never cached
     * (only valid ones are remembered), so re-playing the same corrupt-signature tx re-pays the
     * crypto every time — and the per-IP limiter has no aggregate bound (audit M1). This single
     * global bucket caps total admissions/s well below loop capacity; an over-budget tx is shed at
     * the HTTP boundary (429) before the body is decoded. Sized generously for honest gossip
     * (a few tx/s network-wide) while making a distributed signature-flood uneconomic.
     */
    static final int MEMPOOL_SIG_MAX_PER_SEC = 100;
    private final RateLimiter mempoolSigGate = new RateLimiter(MEMPOOL_SIG_MAX_PER_SEC, 1000, 1);

    /**
     * Aggregate compute budget for {@code /call_readonly} dry-runs, in gas units per second, summed
     * across every source IP. A dry-run runs the VM interpreter for up to {@code MAX_READONLY_GAS}
     * (25M, clamped in ContractApi to exactly this budget) synchronously on the single event-loop
     * thread; the per-IP HTTP rate limiter bounds only one IP, so a few IPs each within their
     * per-IP budget could still pin the loop with back-to-back gas-sink runs and starve block
     * ingestion/sync (audit 5th-pass, net Finding 1 — the same aggregate-vs-per-IP gap the F1
     * submitPowGate closed for /submit). This single global bucket caps total dry-run gas/s below
     * loop capacity; an over-budget call is shed (HTTP 429) WITHOUT running the VM. Sized to admit
     * many cheap dashboard queries while throttling repeated max-gas sinks to one per second —
     * a full 25M-gas interpreted run is already a substantial slice of event-loop time (audit:
     * readonly gas calibration).
     */
    static final int READONLY_GAS_MAX_PER_SEC = 25_000_000;
    private final RateLimiter readonlyGasGate;

    /**
     * Aggregate budget for the explorer read endpoints that fully decode blocks from RocksDB <em>under
     * the consensus lock</em> ({@code /stats}, {@code /blocks}, {@code /block}, {@code /transaction},
     * {@code /address_txs}), in {@code requestCost} units per second summed across every source IP. The
     * per-IP rate limiter weights these by the blocks they read, but bounds only one IP — so a
     * distributed flood of many IPs, each within its per-IP budget, still sums to unbounded
     * lock-guarded block decodes on the single event-loop thread, contending block production and sync
     * (audit 5th-pass, net Finding 2 — the aggregate-vs-per-IP gap already closed for /submit and
     * /call_readonly). This single global bucket caps the total; an over-budget read is shed (HTTP 429)
     * before it decodes anything. Sized well above heavy multi-client dashboard use (each client is
     * already ≤ the per-IP budget) yet far below loop capacity, so it only bites a genuine flood. The
     * peer-serving heavyweights (/sync, /headers, /state/snapshot/chunk, /orphan) are charged to this
     * budget too (NodeApi.isConsensusLockRead): they run the same lock-guarded store reads per block
     * served and amplify egress by up to hundreds of blocks (or ~16 MiB) per request, so an
     * unauthenticated flood of "peers" must not sum past the aggregate bound either — the budget is
     * sized far above any plausible convergence traffic, so honest sync is unaffected.
     */
    static final int READ_DECODE_MAX_PER_SEC = 8_000;
    private final RateLimiter readGate;

    public NodeService(ChainEngine engine, MemPool mempool) {
        this(engine, mempool, new RateLimiter(SUBMIT_POW_MAX_PER_SEC, 1000, 1),
            new RateLimiter(READONLY_GAS_MAX_PER_SEC, 1000, 1),
            new RateLimiter(READ_DECODE_MAX_PER_SEC, 1000, 1));
    }

    NodeService(ChainEngine engine, MemPool mempool, RateLimiter submitPowGate) {
        this(engine, mempool, submitPowGate, new RateLimiter(READONLY_GAS_MAX_PER_SEC, 1000, 1),
            new RateLimiter(READ_DECODE_MAX_PER_SEC, 1000, 1));
    }

    NodeService(ChainEngine engine, MemPool mempool, RateLimiter submitPowGate, RateLimiter readonlyGasGate) {
        this(engine, mempool, submitPowGate, readonlyGasGate,
            new RateLimiter(READ_DECODE_MAX_PER_SEC, 1000, 1));
    }

    NodeService(ChainEngine engine, MemPool mempool, RateLimiter submitPowGate, RateLimiter readonlyGasGate,
                RateLimiter readGate) {
        this.submitPowGate = submitPowGate;
        this.readonlyGasGate = readonlyGasGate;
        this.readGate = readGate;
        this.engine = engine;
        this.mempool = mempool;
    }

    /**
     * Reserves {@code cost} units from the process-wide explorer-read budget, returning false if the
     * aggregate lock-guarded block-decode budget this second is exhausted (the caller then sheds the
     * request with 429 before touching the store). See {@link #READ_DECODE_MAX_PER_SEC}.
     */
    public boolean tryReadBudget(int cost) {
        return readGate.allow("read", cost);
    }

    /** Called when a freshly submitted block/transaction is accepted (for gossip). */
    public void setOnBlockAccepted(java.util.function.Consumer<Block> listener) {
        this.onBlockAccepted = listener;
    }

    public void setOnTransactionAccepted(java.util.function.Consumer<Transaction> listener) {
        this.onTransactionAccepted = listener;
    }

    public void setPeers(PeerRegistry registry) {
        this.peers = registry;
    }

    /** Source of contract event logs by block height (the contract processor). */
    public void setLogSource(java.util.function.LongFunction<List<ContractLog>> source) {
        this.logSource = source;
    }

    /** Source of deployed contract code by address (the contract store). */
    public void setCodeSource(java.util.function.Function<PublicAddress, byte[]> source) {
        this.codeSource = source;
    }

    /** Deployed code at {@code contract}, or {@code null} if none / no store wired. */
    public byte[] contractCode(PublicAddress contract) {
        var source = codeSource;
        return source == null ? null : source.apply(contract);
    }

    /** Source of box lifecycle events by block height (the box processor). */
    public void setBoxEventSource(java.util.function.LongFunction<List<rhizome.core.box.BoxProcessor.BoxEvent>> source) {
        this.boxEventSource = source;
    }

    /**
     * Event logs emitted by a block: contract logs plus box lifecycle events (mapped
     * to the same {@code (contract, topic, data)} shape, with the box owner as contract,
     * the event type as topic, and the box id as data), so agents watch both on one feed.
     */
    public List<ContractLog> logsAt(long height) {
        var logs = logSource;
        var boxes = boxEventSource;
        var tokens = tokenEventSource;
        List<ContractLog> out = new ArrayList<>(logs == null ? List.of() : logs.apply(height));
        if (boxes != null) {
            for (var e : boxes.apply(height)) {
                out.add(new ContractLog(e.owner(), e.type().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    e.boxId()));
            }
        }
        if (tokens != null) {
            for (var e : tokens.apply(height)) {
                out.add(new ContractLog(e.actor(), e.type().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    e.tokenId()));
            }
        }
        return out;
    }

    /** Source of token lifecycle events by block height (the token processor). */
    public void setTokenEventSource(java.util.function.LongFunction<List<rhizome.core.token.TokenProcessor.TokenEvent>> source) {
        this.tokenEventSource = source;
    }

    /** Committed metadata for {@code tokenId}, or {@code null}. */
    public rhizome.core.token.TokenMeta tokenMeta(byte[] tokenId) {
        return engine.tokenMeta(tokenId);
    }

    /** Committed balance of {@code tokenId} held by {@code address}. */
    public long tokenBalance(byte[] tokenId, byte[] address) {
        return engine.tokenBalance(tokenId, address);
    }

    /** Token ids minted by {@code minter}, paginated after {@code afterId} (null = start). */
    public List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit) {
        return engine.tokenIdsByMinter(minter, afterId, limit);
    }

    /** Token ids {@code address} holds, paginated after {@code afterId} (null = start). */
    public List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit) {
        return engine.tokenIdsByHolder(address, afterId, limit);
    }

    // ---- authenticated state ----

    /** The current miner-voted box params: {@code [storageFeeFactor, minValuePerByte]}. */
    public long[] voteableParams() {
        return engine.voteableParams();
    }

    /** The current authenticated state root, or {@code null} if the accumulator is off. */
    public byte[] stateRoot() {
        return engine.stateRoot();
    }

    /** A membership proof for a state entry at the current root, or {@code null} if absent / off. */
    public rhizome.core.state.StateProof stateProof(byte domain, byte[] rawKey) {
        return engine.stateProof(domain, rawKey);
    }

    /** The contract processor, for read-only dry-run calls. */
    public void setContracts(rhizome.core.blockchain.ContractProcessor contracts) {
        this.contracts = contracts;
    }

    /** Whether read-only contract calls are available (a contract processor is wired). */
    public boolean dryRunAvailable() {
        return contracts != null;
    }

    /**
     * Reserves {@code gasLimit} from the process-wide dry-run gas budget, returning false if the
     * aggregate {@code /call_readonly} compute this second is exhausted (the caller then sheds the
     * request with 429 instead of running the VM on the event loop). See {@link #READONLY_GAS_MAX_PER_SEC}.
     */
    public boolean tryReadonlyGasBudget(long gasLimit) {
        // gasLimit is clamped to MAX_READONLY_GAS (= the per-second budget) before it reaches here.
        return readonlyGasGate.allow("readonly", (int) Math.min(gasLimit, MAX_READONLY_GAS_CHARGE));
    }

    private static final long MAX_READONLY_GAS_CHARGE = 25_000_000L;

    /** Runs a read-only CALL against committed state, discarding writes (no ledger effect).
     *  Serialized with block application and sync through the consensus lock: the contract
     *  processor's session ({@code DefaultBoxProcessor.session}) is a single mutable view, so a
     *  dry-run racing a sync-driven block apply could read a half-updated state or corrupt the
     *  session the apply is using (audit: dryRun outside the consensus lock).
     *
     *  <p>Admission is bounded by {@link #MAX_CONCURRENT_DRY_RUNS}: only that many dry-runs may be
     *  running or parked on the consensus lock at once. The lock admits a single thread, so without
     *  a bound a dry-run flood piles up as unbounded parked worker-pool threads behind it, each a
     *  25M-gas VM run that then delays block application (the old bounded queue inside the VM never
     *  filled — at most one task was ever submitted to it, because the lock serialized admission
     *  upstream). A call that cannot take a slot returns {@link java.util.Optional#empty()}
     *  immediately (the API maps it to 503) instead of queueing. */
    static final int MAX_CONCURRENT_DRY_RUNS = 8;
    private final java.util.concurrent.Semaphore dryRunSlots =
        new java.util.concurrent.Semaphore(MAX_CONCURRENT_DRY_RUNS);

    public java.util.Optional<rhizome.core.blockchain.ContractProcessor.ContractResult> dryRun(
            PublicAddress from, PublicAddress to, byte[] input, long value, long gasLimit) {
        if (!dryRunSlots.tryAcquire()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(
                engine.withConsistentView(() -> contracts.dryRun(from, to, input, value, gasLimit)));
        } finally {
            dryRunSlots.release();
        }
    }

    /** Visible for testing: dry-run admission slots currently free (0 = new dry-runs are shed). */
    int dryRunSlotsAvailable() {
        return dryRunSlots.availablePermits();
    }

    /** The full body of a known orphan (uncle candidate) by hash, or {@code null} — served to
     *  syncing peers via {@code GET /orphan} (audit: uncle-sync blocker). */
    public Block orphanBlock(rhizome.crypto.SHA256Hash hash) {
        return engine.orphanBlock(hash);
    }

    /** Whether the data-box layer is active on this node. */
    public boolean boxesAvailable() {
        return engine.boxesEnabled();
    }

    /** Whether the native-token layer is active on this node. */
    public boolean tokensAvailable() {
        return engine.tokensEnabled();
    }

    /** A box from committed state, or {@code null} if none / boxes disabled. */
    public rhizome.core.box.Box box(byte[] id) {
        return engine.box(id);
    }

    /** Box ids owned by {@code owner}, paginated after {@code afterId} (null = start). */
    public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
        return engine.boxIdsByOwner(owner, afterId, limit);
    }

    // ---- box scans (EIP-1) ----

    /** Registers a declarative box scan for {@code clientKey}; returns its node-local id. */
    public int registerScan(String clientKey, rhizome.core.box.ScanPredicate predicate) {
        return scans.register(clientKey, predicate);
    }

    /** Removes a registered scan owned by {@code clientKey}; true if it existed (and was theirs). */
    public boolean deregisterScan(String clientKey, int scanId) {
        return scans.deregister(scanId, clientKey);
    }

    /**
     * The predicate of a scan owned by {@code clientKey}, or {@code null} — a foreign id is
     * indistinguishable from an unknown one (non-disclosing, like {@link #deregisterScan};
     * audit: /scan/boxes owner-gating). Only this owner-scoped lookup refreshes the scan's
     * idle-eviction activity, so another client polling a learned id cannot keep the victim
     * scan alive.
     */
    public rhizome.core.box.ScanPredicate scanPredicate(String clientKey, int scanId) {
        return scans.getForOwner(scanId, clientKey);
    }

    /** Scans registered by {@code clientKey}, id → predicate (other clients' scans are not
     *  disclosed — /scan/register is unauthenticated; audit F1). */
    public java.util.Map<Integer, rhizome.core.box.ScanPredicate> scansOf(String clientKey) {
        return scans.scansOf(clientKey);
    }

    /** Evaluates a predicate over committed boxes, one bounded, pollable window at a time. */
    public rhizome.core.box.BoxProcessor.ScanPage scan(
            rhizome.core.box.ScanPredicate predicate, byte[] afterId, int limit) {
        return engine.scanBoxes(predicate, afterId, limit, BOX_SCAN_WINDOW);
    }

    /**
     * A height-cursor catch-up scan: logs from {@code fromHeight} up to the tip, each
     * tagged with its block height, bounded to {@link #LOG_SCAN_WINDOW} blocks so an
     * agent streams by repeatedly polling from {@code toHeight + 1}. Returns the
     * scanned {@code toHeight} and the collected logs.
     */
    public LogPage logsFrom(long fromHeight) {
        long from = Math.max(1, fromHeight);
        long to = Math.min(engine.height(), from + LOG_SCAN_WINDOW - 1);
        List<HeightLog> out = new ArrayList<>();
        for (long h = from; h <= to; h++) {
            for (ContractLog log : logsAt(h)) {
                out.add(new HeightLog(h, log));
            }
        }
        return new LogPage(from, Math.max(from - 1, to), out);
    }

    /** A contract log tagged with the height of the block that emitted it. */
    public record HeightLog(long height, ContractLog log) {}

    /** One page of a height-cursor log scan: the range covered and the logs in it. */
    public record LogPage(long fromHeight, long toHeight, List<HeightLog> logs) {}

    /** Peer base URLs this node knows (empty if discovery is not enabled). */
    public java.util.List<String> knownPeers() {
        return peers == null ? java.util.List.of() : peers.snapshot();
    }

    /**
     * Peers safe to disclose to an unauthenticated caller: non-seed, publicly-routable only.
     * Seeds may be private operator infrastructure, so {@code GET /peers} serves this, not the
     * full {@link #knownPeers()} snapshot (audit S-6).
     */
    public java.util.List<String> publicPeers() {
        return peers == null ? java.util.List.of() : peers.publicSnapshot();
    }

    /**
     * Queues a self-announced peer ({@code /add_peer}) for admission on the off-loop worker and
     * returns immediately. Admission resolves DNS, which must not block the event-loop (see
     * {@link #peerAdmission}); the registry decides off-loop whether the peer is actually added.
     */
    public void addPeer(String url) {
        PeerRegistry registry = peers;
        if (registry == null) {
            return;
        }
        // Canonicalize BEFORE the coalescing set: case/trailing-dot/default-port/slash variants
        // of one URL must coalesce into a single in-flight admission (and a single registry
        // slot) — the registry applies the same canonical form at admission (audit: /add_peer
        // coalescing bypass).
        String canonical = rhizome.net.PeerUrls.canonicalize(url);
        if (canonical == null || canonical.isEmpty()) {
            return;
        }
        // Coalesce duplicate in-flight admissions: re-submitting the same slow hostname must not
        // enqueue another full blocking DNS resolve — neither under the same canonical URL nor
        // under a port/path variant of the same host (see pendingAdmissionHosts). The sets are
        // kept in lock-step with the queue — every queued task removes its entries on completion,
        // and a rejected enqueue removes them below — so they can never grow past the queue bound.
        if (!pendingAdmissions.add(canonical)) {
            return; // already queued or running
        }
        String host = hostOf(canonical);
        if (host != null && !pendingAdmissionHosts.add(host)) {
            pendingAdmissions.remove(canonical);
            return; // another admission for this host is already queued or running
        }
        try {
            peerAdmission.execute(() -> {
                try {
                    registry.add(canonical);
                } catch (RuntimeException e) {
                    // Best-effort: a malformed or unresolvable peer is simply not added.
                } finally {
                    pendingAdmissions.remove(canonical);
                    if (host != null) {
                        pendingAdmissionHosts.remove(host);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            pendingAdmissions.remove(canonical); // queue full: shed load, keep the sets bounded
            if (host != null) {
                pendingAdmissionHosts.remove(host);
            }
        }
    }

    /** Lower-cased host of a canonical peer URL, or {@code null} when it has none (unparseable). */
    private static String hostOf(String canonicalUrl) {
        try {
            String host = java.net.URI.create(canonicalUrl).getHost();
            return host == null || host.isEmpty() ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public NetworkParameters params() {
        return engine.params();
    }

    public int chainId() {
        return engine.params().chainId();
    }

    public String networkName() {
        return engine.params().networkName();
    }

    public long blockCount() {
        return engine.height();
    }

    /** Aggregate over the last stats window: total tx count and the first/last block timestamps. */
    public record StatsWindow(long windowStart, long height, long txCount, long firstTs, long lastTs) {}

    private volatile StatsWindow statsWindowCache;
    /** Serializes stats-window recomputes: without it, N concurrent callers on a stale cache
     *  all re-decode the same window under the read path (duplicate lock-guarded work). */
    private final Object statsWindowLock = new Object();

    /**
     * Cached aggregate over the last {@code window} blocks for {@code GET /stats}. The dashboard polls
     * stats on a timer, but this window only changes when the tip advances, so it is recomputed (and
     * the {@code window} blocks re-decoded under the read path) only when {@code blockCount()} moved
     * since the last call (audit optimization). Live scalars (mempool, peers, difficulty) are cheap and
     * stay uncached in the handler.
     */
    public StatsWindow statsWindow(int window) {
        long height = blockCount();
        StatsWindow cached = statsWindowCache;
        if (cached != null && cached.height() == height) {
            return cached;
        }
        synchronized (statsWindowLock) {
            // Re-check inside the lock: a concurrent caller may already have recomputed this
            // height — only one thread re-decodes the window per tip movement.
            cached = statsWindowCache;
            if (cached != null && cached.height() == height) {
                return cached;
            }
            long windowStart = Math.max(1, height - window + 1);
            long txCount = 0;
            long firstTs = 0;
            long lastTs = 0;
            for (long h = windowStart; h <= height; h++) {
                var b = (rhizome.core.block.BlockImpl) block(h);
                txCount += b.transactions().size();
                if (h == windowStart) {
                    firstTs = b.timestamp();
                }
                if (h == height) {
                    lastTs = b.timestamp();
                }
            }
            StatsWindow w = new StatsWindow(windowStart, height, txCount, firstTs, lastTs);
            statsWindowCache = w;
            return w;
        }
    }

    /** Exclusive upper bound of pruned block bodies (0 = archive node). */
    public long prunedBelow() {
        return engine.prunedBelow();
    }

    /** Wires the state source that snapshot materialisation exports from. */
    public void setSnapshotSource(rhizome.core.state.snapshot.StateSource source) {
        this.snapshotSource = source;
    }

    /**
     * Wires the directory snapshot spools live in (the node's data dir, alongside the stores —
     * NOT the OS temp dir, which is commonly a tmpfs and would silently put the whole state
     * back in RAM). Stale {@code rhizome-snapshot-*} spools from a previous process (SIGKILL
     * leaves them behind — {@link #close()} only runs on a clean shutdown) are swept here,
     * once, at wiring time. When unset, spools fall back to the default temp dir (tests).
     */
    public void setSnapshotSpoolDir(java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Files.createDirectories(dir);
        int swept = 0;
        try (var entries = java.nio.file.Files.list(dir)) {
            for (var stale : (Iterable<java.nio.file.Path>) entries
                    .filter(p -> p.getFileName().toString().startsWith("rhizome-snapshot-"))
                    ::iterator) {
                try {
                    java.nio.file.Files.deleteIfExists(stale);
                    swept++;
                } catch (java.io.IOException e) {
                    log.warn("Stale snapshot spool {} could not be deleted", stale, e);
                }
            }
        }
        if (swept > 0) {
            log.info("Swept {} stale snapshot spool(s) from {}", swept, dir);
        }
        this.snapshotSpoolDir = dir;
    }

    /**
     * Captures a fresh materialised snapshot of the full committed state under the engine
     * lock, so every chunk corresponds to the single {@code (height, stateRoot)} pair it
     * advertises. Chunks are spooled to a temp file as the exporter emits them (see {@link
     * MaterializedSnapshot}); only the offset index is kept on the heap. Replaces — and deletes
     * the spool of — any previous snapshot. False when the node cannot export (no source
     * wired, or no state accumulator producing roots) or when the spool I/O fails; a failure
     * cleans up the partial file and keeps the previous snapshot.
     */
    public boolean materializeSnapshot() {
        var source = snapshotSource;
        if (source == null || engine.stateRoot() == null) {
            return false;
        }
        final MaterializedSnapshot fresh;
        try {
            fresh = engine.withConsistentView(() -> {
                try {
                    return captureSnapshot(source);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            log.error("Snapshot materialisation failed; the previous snapshot is kept",
                e.getCause());
            return false;
        }
        MaterializedSnapshot previous = this.snapshot;
        this.snapshot = fresh;
        if (previous != null) {
            previous.close();
        }
        return true;
    }

    /**
     * Stream-encodes every exported chunk into a fresh temp spool and returns the file-backed
     * snapshot over it. At most one chunk is heap-resident at a time. A failure anywhere
     * (write, channel open) deletes the partial spool before propagating.
     */
    private MaterializedSnapshot captureSnapshot(rhizome.core.state.snapshot.StateSource source)
            throws java.io.IOException {
        var dir = snapshotSpoolDir;
        java.nio.file.Path file = dir != null
            ? java.nio.file.Files.createTempFile(dir, "rhizome-snapshot-", ".chunks")
            : java.nio.file.Files.createTempFile("rhizome-snapshot-", ".chunks");
        try {
            var offsets = new LongIndex();
            var lengths = new LongIndex();
            long[] position = {0};
            try (var out = java.nio.file.Files.newOutputStream(file)) {
                rhizome.core.state.snapshot.StateSnapshotExporter.export(
                        source, SNAPSHOT_CHUNK_ENTRIES,
                        rhizome.core.state.snapshot.StateSnapshotExporter.DEFAULT_CHUNK_BYTES,
                        chunk -> {
                            byte[] encoded = chunk.encode();
                            offsets.add(position[0]);
                            lengths.add(encoded.length);
                            try {
                                out.write(encoded);
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                            position[0] += encoded.length;
                        });
            }
            return new MaterializedSnapshot(engine.height(), engine.stateRoot(), file,
                offsets.toArray(), lengths.toArray(),
                java.nio.channels.FileChannel.open(file,
                    java.nio.file.StandardOpenOption.READ));
        } catch (java.io.IOException | RuntimeException e) {
            try {
                java.nio.file.Files.deleteIfExists(file);
            } catch (java.io.IOException cleanup) {
                log.warn("Partial snapshot spool {} could not be deleted", file, cleanup);
            }
            throw e;
        }
    }

    /** A minimal growable {@code long[]} for the snapshot chunk index (avoids boxed lists). */
    private static final class LongIndex {
        private long[] values = new long[64];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        long[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }

    /** The current materialised snapshot, or {@code null} if none has been captured. */
    MaterializedSnapshot materializedSnapshot() {
        return snapshot;
    }

    /** Height of the current materialised snapshot ({@code 0} when none). */
    public long snapshotPivot() {
        var snap = snapshot;
        return snap == null ? 0 : snap.pivotHeight();
    }

    /**
     * Releases the file-backed snapshot (closes its channel, deletes its spool). Called at
     * node shutdown; a fresh materialisation after close starts from no snapshot again.
     */
    public void close() {
        MaterializedSnapshot snap = this.snapshot;
        this.snapshot = null;
        if (snap != null) {
            snap.close();
        }
    }

    /** Degraded-mode reason (e.g. a failed reorg restore), or {@code null} when healthy. */
    public String degradedState() {
        return engine.degradedState();
    }

    /**
     * True while a reorg window is open (pop → body-apply → restore): the node serves the
     * pre-reorg tip and block production pauses. Surfaced on /stats so an operator can tell
     * a normal reorg pause from a degraded node.
     */
    public boolean isReorgInProgress() {
        return engine.isReorgInProgress();
    }
    public java.math.BigInteger totalWork() {
        return engine.totalWork();
    }

    public int difficulty() {
        return engine.difficulty();
    }

    public Block block(long height) {
        return engine.blockAt(height);
    }

    /** Height of the applied block containing {@code contentHash}, or {@code null} (txid index). */
    public Long transactionHeight(rhizome.crypto.SHA256Hash contentHash) {
        return engine.transactionHeight(contentHash);
    }

    /** Logical header at the given height — served without the body for headers-first sync. */
    public rhizome.core.block.BlockHeader header(long height) {
        return engine.headerAt(height);
    }

    /** Blocks in the inclusive range, already clamped by the caller. */
    public List<Block> blocks(long start, long end) {
        List<Block> out = new ArrayList<>();
        for (long h = start; h <= end; h++) {
            out.add(engine.blockAt(h));
        }
        return out;
    }

    public long balance(PublicAddress wallet) {
        return engine.confirmedBalance(wallet);
    }

    public long nextNonce(PublicAddress wallet) {
        return engine.confirmedNextNonce(wallet);
    }

    /** Admits a transaction to the mempool (signature verified once here). */
    public ExecutionStatus submitTransaction(Transaction transaction) {
        ExecutionStatus status = mempool.addTransaction(transaction);
        if (status == ExecutionStatus.SUCCESS) {
            notify(onTransactionAccepted, transaction);
        }
        return status;
    }

    /**
     * As {@link #submitTransaction(Transaction)}, additionally recording a push-abuse strike
     * against {@code clientKey} when the peer pushed provable junk (audit: gossip push
     * ban-score). See {@link #notePushFault}.
     */
    public ExecutionStatus submitTransaction(Transaction transaction, String clientKey) {
        ExecutionStatus status = submitTransaction(transaction);
        if (isPushFault(status)) {
            notePushFault(clientKey);
        }
        return status;
    }

    /**
     * The aggregate (all-IP) anti-DoS gate for {@code /submit}, consumed at the HTTP boundary
     * <em>before</em> the block body is decoded (audit F1 + S6). {@code /submit} triggers both a full
     * block decode (up to {@code maxBlockSizeBytes}, ~25 000 tx allocations) and, in {@link
     * #submitBlock}, a memory-hard Pufferfish2 hash — all on the single event-loop thread for a
     * ~0-cost attacker input, and the per-IP HTTP limiter has no aggregate cap. Gating in the servlet
     * middleware sheds an over-budget submit with 429 before the decode runs, not after it (the
     * decode-before-gate asymmetry the S6 finding closed); internal/direct callers of {@link
     * #submitBlock} (block production, tests) legitimately bypass this network-boundary shed.
     */
    public boolean trySubmitBudget() {
        return submitPowGate.allow("submit");
    }

    /**
     * The aggregate (all-IP) anti-DoS gate for {@code /add_transaction(JSON)}, consumed at the HTTP
     * boundary <em>before</em> the body is decoded — symmetric to {@link #trySubmitBudget} for
     * {@code /submit} (audit M1). Internal/direct callers of {@link #submitTransaction} (block
     * production, tests) legitimately bypass this network-boundary shed.
     */
    public boolean tryMempoolSigBudget() {
        return mempoolSigGate.allow("tx");
    }

    /** Accepts a mined block; on success the mempool is purged of its transactions. */
    public ExecutionStatus submitBlock(Block block) {
        ExecutionStatus status = engine.addBlock(block);
        if (status == ExecutionStatus.SUCCESS) {
            mempool.onBlockApplied(block);
            notify(onBlockAccepted, block);
        } else {
            // A block that didn't extend our tip may be a valid sibling that lost the
            // race; keep it (PoW-gated inside) so a later block can cite it as an uncle.
            engine.registerOrphan(block);
        }
        return status;
    }

    /**
     * As {@link #submitBlock(Block)}, additionally recording a push-abuse strike against
     * {@code clientKey} when the peer pushed provable junk (audit: gossip push ban-score).
     */
    public ExecutionStatus submitBlock(Block block, String clientKey) {
        ExecutionStatus status = submitBlock(block);
        if (isPushFault(status)) {
            notePushFault(clientKey);
        }
        return status;
    }

    // ---- push-abuse strikes (gossip ban-score for the push paths) ----

    /**
     * Push faults a client may commit before an early shed kicks in, per rolling window. A peer
     * spamming {@code /submit} with invalid blocks or {@code /add_transaction} with
     * corrupt-signature transactions otherwise accumulates no score at all: the pull-sync
     * ban list only punishes what WE fetch, never what is pushed at us (audit: gossip push
     * ban-score). The strike table is bounded exactly like the rate limiter's client table —
     * its key is attacker-influenced — and the shed only throttles the push paths; the peer
     * is NOT evicted from the registry, so honest pull-sync is unaffected.
     */
    static final int PUSH_STRIKE_LIMIT = 20;
    static final long PUSH_STRIKE_WINDOW_MS = 60_000L;
    private static final int PUSH_STRIKE_MAX_KEYS = 8_192;

    private static final class Strikes {
        volatile long windowStart;
        int count;
        Strikes(long windowStart) { this.windowStart = windowStart; }
    }

    private final java.util.Map<String, Strikes> pushStrikes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Strikes pushStrikesOverflow = new Strikes(0);

    /**
     * True when {@code clientKey} has pushed more than {@link #PUSH_STRIKE_LIMIT} faults inside
     * the current window: the HTTP boundary should shed its next push with 429 BEFORE decoding
     * the body, for the rest of the window.
     */
    public boolean isPushShed(String clientKey) {
        Strikes s = pushStrikes.get(clientKey);
        if (s == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (s) {
            if (now - s.windowStart >= PUSH_STRIKE_WINDOW_MS) {
                return false; // stale window: the next fault starts a fresh count
            }
            return s.count >= PUSH_STRIKE_LIMIT;
        }
    }

    /**
     * True for a push outcome that is PROVABLE junk — an explicit whitelist, never a prefix match.
     * Honest gossip routinely produces non-success statuses that must NOT strike: a rebroadcast
     * block we already have ({@code INVALID_BLOCK_ID} / {@code INVALID_LASTBLOCK_HASH}), an uncle
     * reference our pool has not seen ({@code INVALID_UNCLES} — normal for a node with an empty
     * orphan pool, exactly the condition the sync path now fetches around), a nonce consumed
     * meanwhile or an insufficient RBF bump ({@code INVALID_TRANSACTION_NONCE}), clock drift
     * ({@code INVALID_TRANSACTION_TIMESTAMP}, {@code BLOCK_TIMESTAMP_TOO_OLD/TOO_CLOSE}), a state
     * root our accumulator config disagrees on ({@code INVALID_STATE_ROOT}), a balance view race
     * ({@code BALANCE_TOO_LOW}), a duplicate tx ({@code ALREADY_IN_QUEUE}) or an anti-DoS shed
     * ({@code SUBMIT_THROTTLED}). Striking those would throttle honest peers (audit collateral).
     * The listed statuses are all deterministic structural violations no valid block/tx produces.
     */
    private static final java.util.Set<ExecutionStatus> PUSH_FAULT_STATUSES = java.util.Set.of(
        ExecutionStatus.INVALID_SIGNATURE,
        ExecutionStatus.INVALID_NONCE,               // failed PoW
        ExecutionStatus.INVALID_CHAIN_ID,
        ExecutionStatus.INVALID_DIFFICULTY,
        ExecutionStatus.INVALID_MERKLE_ROOT,
        ExecutionStatus.INVALID_TRANSACTION_COUNT,
        ExecutionStatus.BLOCK_TOO_LARGE,
        ExecutionStatus.BLOCK_ID_TOO_LARGE,
        ExecutionStatus.INVALID_TRANSACTION_AMOUNT,
        ExecutionStatus.TRANSACTION_FEE_TOO_LOW,
        ExecutionStatus.INVALID_VOTE,
        ExecutionStatus.HEADER_HASH_INVALID);

    private static boolean isPushFault(ExecutionStatus status) {
        return status != null && PUSH_FAULT_STATUSES.contains(status);
    }

    private void notePushFault(String clientKey) {
        long now = System.currentTimeMillis();
        Strikes s = pushStrikes.get(clientKey);
        if (s == null) {
            if (pushStrikes.size() >= PUSH_STRIKE_MAX_KEYS) {
                // Table full: sweep expired windows; if nothing is reclaimable, meter the
                // untracked client against a shared overflow bucket (fail-closed, bounded —
                // the RateLimiter overflow pattern) rather than growing without limit.
                long before = pushStrikes.size();
                pushStrikes.values().removeIf(w -> now - w.windowStart >= PUSH_STRIKE_WINDOW_MS);
                if (pushStrikes.size() >= before) {
                    s = pushStrikesOverflow;
                }
            }
            if (s == null) {
                s = pushStrikes.computeIfAbsent(clientKey, k -> new Strikes(now));
            }
        }
        synchronized (s) {
            if (now - s.windowStart >= PUSH_STRIKE_WINDOW_MS) {
                s.windowStart = now;
                s.count = 0;
            }
            s.count++;
        }
    }

    /** Visible for testing: strike count recorded for {@code clientKey} in the current window. */
    int pushStrikeCount(String clientKey) {
        Strikes s = pushStrikes.get(clientKey);
        if (s == null) {
            return 0;
        }
        synchronized (s) {
            return System.currentTimeMillis() - s.windowStart >= PUSH_STRIKE_WINDOW_MS ? 0 : s.count;
        }
    }

    private static <T> void notify(java.util.function.Consumer<T> listener, T value) {
        if (listener != null) {
            listener.accept(value);
        }
    }

    public int mempoolSize() {
        return mempool.size();
    }
}
