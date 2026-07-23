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
    private volatile MaterializedSnapshot snapshot;

    /** Entry bound per snapshot chunk (bytes are bounded separately by the exporter). */
    static final int SNAPSHOT_CHUNK_ENTRIES = 4096;

    /** A consistent full-state export frozen at one (pivotHeight, stateRoot) point. */
    record MaterializedSnapshot(long pivotHeight, byte[] stateRoot, List<byte[]> chunks) {}

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
     * Aggregate compute budget for {@code /call_readonly} dry-runs, in gas units per second, summed
     * across every source IP. A dry-run runs the VM interpreter for up to {@code MAX_READONLY_GAS}
     * (50M) instructions synchronously on the single event-loop thread; the per-IP HTTP rate limiter
     * bounds only one IP, so a few IPs each within their per-IP budget could still pin the loop with
     * back-to-back gas-sink runs and starve block ingestion/sync (audit 5th-pass, net Finding 1 —
     * the same aggregate-vs-per-IP gap the F1 submitPowGate closed for /submit). This single global
     * bucket caps total dry-run gas/s below loop capacity; an over-budget call is shed (HTTP 429)
     * WITHOUT running the VM. Sized to admit many cheap dashboard queries while throttling repeated
     * max-gas sinks to a couple per second. Charged the (clamped) gasLimit up-front — the actual run
     * cannot exceed it — so the gate always sheds before the work happens.
     */
    static final int READONLY_GAS_MAX_PER_SEC = 100_000_000;
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
     * peer-sync reads (/sync, /headers) are deliberately NOT gated here — like submitPowGate leaving
     * sync ungated, honest chain progress must never be throttled by this browser-facing cap.
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
        // gasLimit is clamped to MAX_READONLY_GAS (50M) < Integer.MAX_VALUE before it reaches here.
        return readonlyGasGate.allow("readonly", (int) Math.min(gasLimit, MAX_READONLY_GAS_CHARGE));
    }

    private static final long MAX_READONLY_GAS_CHARGE = 50_000_000L;

    /** Runs a read-only CALL against committed state, discarding writes (no ledger effect). */
    public rhizome.core.blockchain.ContractProcessor.ContractResult dryRun(
            PublicAddress from, PublicAddress to, byte[] input, long value, long gasLimit) {
        return contracts.dryRun(from, to, input, value, gasLimit);
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

    /** Registers a declarative box scan; returns its node-local id. */
    public int registerScan(rhizome.core.box.ScanPredicate predicate) {
        return scans.register(predicate);
    }

    /** Removes a registered scan; true if it existed. */
    public boolean deregisterScan(int scanId) {
        return scans.deregister(scanId);
    }

    /** The predicate of a registered scan, or {@code null} if the id is unknown. */
    public rhizome.core.box.ScanPredicate scanPredicate(int scanId) {
        return scans.get(scanId);
    }

    /** All registered scans, id → predicate. */
    public java.util.Map<Integer, rhizome.core.box.ScanPredicate> scans() {
        return scans.all();
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
     * Queues a self-announced peer ({@code /add_peer}) for admission on the off-loop worker and
     * returns immediately. Admission resolves DNS, which must not block the event-loop (see
     * {@link #peerAdmission}); the registry decides off-loop whether the peer is actually added.
     */
    public void addPeer(String url) {
        PeerRegistry registry = peers;
        if (registry == null) {
            return;
        }
        // Coalesce duplicate in-flight admissions: re-submitting the same slow hostname must not
        // enqueue another full blocking DNS resolve. The set is kept in lock-step with the queue —
        // every queued task removes its URL on completion, and a rejected enqueue removes it below —
        // so it can never grow past the queue bound.
        if (!pendingAdmissions.add(url)) {
            return; // already queued or running
        }
        try {
            peerAdmission.execute(() -> {
                try {
                    registry.add(url);
                } catch (RuntimeException e) {
                    // Best-effort: a malformed or unresolvable peer is simply not added.
                } finally {
                    pendingAdmissions.remove(url);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            pendingAdmissions.remove(url); // queue full: shed load, keep the set bounded
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

    /** Exclusive upper bound of pruned block bodies (0 = archive node). */
    public long prunedBelow() {
        return engine.prunedBelow();
    }

    /** Wires the state source that snapshot materialisation exports from. */
    public void setSnapshotSource(rhizome.core.state.snapshot.StateSource source) {
        this.snapshotSource = source;
    }

    /**
     * Captures a fresh materialised snapshot of the full committed state under the engine
     * lock, so every chunk corresponds to the single {@code (height, stateRoot)} pair it
     * advertises. Replaces any previous snapshot. False when the node cannot export
     * (no source wired, or no state accumulator producing roots).
     */
    public boolean materializeSnapshot() {
        var source = snapshotSource;
        if (source == null || engine.stateRoot() == null) {
            return false;
        }
        this.snapshot = engine.withConsistentView(() -> {
            List<byte[]> encoded = new ArrayList<>();
            for (var chunk : rhizome.core.state.snapshot.StateSnapshotExporter.export(source, SNAPSHOT_CHUNK_ENTRIES)) {
                encoded.add(chunk.encode());
            }
            return new MaterializedSnapshot(engine.height(), engine.stateRoot(), encoded);
        });
        return true;
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

    public java.math.BigInteger totalWork() {
        return engine.totalWork();
    }

    public int difficulty() {
        return engine.difficulty();
    }

    public Block block(long height) {
        return engine.blockAt(height);
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

    private static <T> void notify(java.util.function.Consumer<T> listener, T value) {
        if (listener != null) {
            listener.accept(value);
        }
    }

    public int mempoolSize() {
        return mempool.size();
    }
}
