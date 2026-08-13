package rhizome.node;

import rhizome.net.PeerRegistry;

import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ContractApi.ContractLog;
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
    /** The read-side collaborators, fixed at assembly; see {@link NodeSources}. */
    private final NodeSources sources;
    /** The gossip fan-out, fixed at assembly; see {@link NodeListeners}. */
    private final NodeListeners listeners;
    /**
     * The snap-sync export — the spool dir, the capture and the one live materialisation; see
     * {@link SnapshotService}. Never null: a {@link NodeSources} without one gets
     * {@link SnapshotService#none}. Closed by {@link #close()}, so ownership transfers here at
     * construction.
     */
    private final SnapshotService snapshots;

    /** Maximum blocks a single /logs catch-up scan spans, so agents poll in bounded chunks. */
    public static final int LOG_SCAN_WINDOW = 128;

    /** Maximum boxes a single /scan query examines, so a scan runs in bounded, pollable chunks. */
    public static final int BOX_SCAN_WINDOW = 512;

    private final ScanRegistry scans = new ScanRegistry();

    /**
     * Off-loop, bounded, coalescing admission of self-announced peers. Owns the worker thread,
     * the queue bound and the two coalescing sets; see {@link PeerAdmissionQueue}.
     */
    private final PeerAdmissionQueue admissions = new PeerAdmissionQueue();

    /**
     * The four aggregate budgets and the dry-run concurrency slots; see {@link AdmissionControl}.
     * One object rather than four fields and four chained constructors: a test that shrinks one
     * budget no longer restates the other two positionally, and mempoolSig — previously the only
     * gate with no seam — is now injectable like the rest.
     */
    private final AdmissionControl admission;

    public NodeService(ChainEngine engine, MemPool mempool) {
        this(engine, mempool, AdmissionControl.defaults(), NodeSources.none(), NodeListeners.none());
    }

    public NodeService(ChainEngine engine, MemPool mempool, NodeSources sources) {
        this(engine, mempool, AdmissionControl.defaults(), sources, NodeListeners.none());
    }

    NodeService(ChainEngine engine, MemPool mempool, AdmissionControl admission) {
        this(engine, mempool, admission, NodeSources.none(), NodeListeners.none());
    }

    public NodeService(ChainEngine engine, MemPool mempool, AdmissionControl admission,
                       NodeSources sources, NodeListeners listeners) {
        this.admission = admission;
        this.sources = sources;
        this.listeners = listeners;
        this.engine = engine;
        this.mempool = mempool;
        this.snapshots = sources.snapshots() != null
            ? sources.snapshots() : SnapshotService.none(engine);
    }

    /**
     * Reserves {@code cost} units from the process-wide explorer-read budget, returning false if the
     * aggregate lock-guarded block-decode budget this second is exhausted (the caller then sheds the
     * request with 429 before touching the store). See {@link AdmissionControl}.
     */
    public boolean tryReadBudget(int cost) {
        return admission.tryRead(cost);
    }






    /** Deployed code at {@code contract}, or {@code null} if none / no store wired. */
    public byte[] contractCode(PublicAddress contract) {
        var source = sources.codeSource();
        return source == null ? null : source.apply(contract);
    }


    /**
     * Event logs emitted by a block: contract logs plus box lifecycle events (mapped
     * to the same {@code (contract, topic, data)} shape, with the box owner as contract,
     * the event type as topic, and the box id as data), so agents watch both on one feed.
     */
    public List<ContractLog> logsAt(long height) {
        var logs = sources.logSource();
        var boxes = sources.boxEventSource();
        var tokens = sources.tokenEventSource();
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

    /**
     * Hash of the current chain tip — the branch a node is actually on. Surfaced on /stats
     * because height alone cannot tell a united network from a SPLIT one: two camps mining at
     * the same rate hold equal heights, equal difficulty and (the metastable case) equal total
     * work while sitting on different branches, and nothing else in /stats differs. That split
     * ran undetected for hours in the local-testnet campaign; one tip per height makes it a
     * one-line check for an operator or a monitoring script.
     */
    public rhizome.crypto.SHA256Hash tipHash() {
        return engine.tipHash();
    }

    /** A membership proof for a state entry at the current root, or {@code null} if absent / off. */
    public rhizome.core.state.StateProof stateProof(byte domain, byte[] rawKey) {
        return engine.stateProof(domain, rawKey);
    }


    /** Whether read-only contract calls are available (a contract processor is wired). */
    public boolean dryRunAvailable() {
        return sources.contracts() != null;
    }

    /**
     * Reserves {@code gasLimit} from the process-wide dry-run gas budget, returning false if the
     * aggregate {@code /call_readonly} compute this second is exhausted (the caller then sheds the
     * request with 429 instead of running the VM on the event loop). See {@link #READONLY_GAS_MAX_PER_SEC}.
     */
    public boolean tryReadonlyGasBudget(long gasLimit) {
        // gasLimit is clamped to MAX_READONLY_GAS (= the per-second budget) before it reaches here.
        return admission.tryReadonlyGas(gasLimit);
    }

    /** Runs a read-only CALL against committed state, discarding writes (no ledger effect).
     *  Serialized with block application and sync through the consensus lock: the contract
     *  processor's session ({@code DefaultBoxProcessor.session}) is a single mutable view, so a
     *  dry-run racing a sync-driven block apply could read a half-updated state or corrupt the
     *  session the apply is using (audit: dryRun outside the consensus lock).
     *
     *  <p>Admission is bounded by {@link AdmissionControl#MAX_CONCURRENT_DRY_RUNS}: only that many dry-runs may be
     *  running or parked on the consensus lock at once. The lock admits a single thread, so without
     *  a bound a dry-run flood piles up as unbounded parked worker-pool threads behind it, each a
     *  25M-gas VM run that then delays block application (the old bounded queue inside the VM never
     *  filled — at most one task was ever submitted to it, because the lock serialized admission
     *  upstream). A call that cannot take a slot returns {@link java.util.Optional#empty()}
     *  immediately (the API maps it to 503) instead of queueing. */
    public java.util.Optional<rhizome.core.blockchain.ContractProcessor.ContractResult> dryRun(
            PublicAddress from, PublicAddress to, byte[] input, long value, long gasLimit) {
        if (!admission.tryDryRunSlot()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(
                engine.withConsistentView(() -> sources.contracts().dryRun(from, to, input, value, gasLimit)));
        } finally {
            admission.releaseDryRunSlot();
        }
    }

    /** Visible for testing: dry-run admission slots currently free (0 = new dry-runs are shed). */
    int dryRunSlotsAvailable() {
        return admission.dryRunSlotsAvailable();
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

    /** Registers a declarative box scan for {@code owner}; returns its node-local id. */
    public int registerScan(ScanRegistry.Owner owner, rhizome.core.box.ScanPredicate predicate) {
        return scans.register(owner, predicate);
    }

    /** Removes a registered scan owned by {@code owner}; true if it existed (and was theirs). */
    public boolean deregisterScan(ScanRegistry.Owner owner, int scanId) {
        return scans.deregister(scanId, owner);
    }

    /**
     * The predicate of a scan owned by {@code owner}, or {@code null} — a foreign id is
     * indistinguishable from an unknown one (non-disclosing, like {@link #deregisterScan};
     * audit: /scan/boxes owner-gating). Only this owner-scoped lookup refreshes the scan's
     * idle-eviction activity, so another client polling a learned id cannot keep the victim
     * scan alive.
     */
    public rhizome.core.box.ScanPredicate scanPredicate(ScanRegistry.Owner owner, int scanId) {
        return scans.getForOwner(scanId, owner);
    }

    /** Scans registered by {@code owner}, id → predicate (other clients' scans are not
     *  disclosed — /scan/register is unauthenticated; audit F1). */
    public java.util.Map<Integer, rhizome.core.box.ScanPredicate> scansOf(ScanRegistry.Owner owner) {
        return scans.scansOf(owner);
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
        PeerRegistry registry = sources.peers();
        return registry == null ? java.util.List.of() : registry.snapshot();
    }

    /**
     * Peers safe to disclose to an unauthenticated caller: non-seed, publicly-routable only.
     * Seeds may be private operator infrastructure, so {@code GET /peers} serves this, not the
     * full {@link #knownPeers()} snapshot (audit S-6).
     */
    public java.util.List<String> publicPeers() {
        PeerRegistry registry = sources.peers();
        return registry == null ? java.util.List.of() : registry.publicSnapshot();
    }

    /**
     * Queues a self-announced peer ({@code /add_peer}) for admission on the off-loop worker and
     * returns immediately. Admission resolves DNS, which must not block the event-loop (see
     * {@link #peerAdmission}); the registry decides off-loop whether the peer is actually added.
     */
    public void addPeer(String url) {
        PeerRegistry registry = sources.peers();
        if (registry == null) {
            return;
        }
        admissions.enqueue(url, registry::add);
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


    /**
     * Captures a fresh full-state export at the current tip, replacing any previous one; false when
     * this node cannot export or the spool I/O fails. See {@link SnapshotService#materialize()}.
     */
    public boolean materializeSnapshot() {
        return snapshots.materialize();
    }

    /** The current materialised snapshot, or {@code null} if none has been captured. */
    public SnapshotService.MaterializedSnapshot materializedSnapshot() {
        return snapshots.current();
    }

    /** Height of the current materialised snapshot ({@code 0} when none). */
    public long snapshotPivot() {
        return snapshots.pivotHeight();
    }

    /**
     * Releases the file-backed snapshot (closes its channel, deletes its spool) and stops the peer
     * admission worker. Called at node shutdown; a fresh materialisation after close starts from
     * no snapshot again.
     *
     * <p>The admission worker was never stopped: its executor was a field with no shutdown at all,
     * so every node ever built leaked a {@code rhizome-peer-admit} thread — invisible in
     * production (daemon), one per node across a test run.
     */
    public void close() {
        snapshots.close();
        admissions.close();
    }

    /** Degraded-mode reason (e.g. a failed reorg restore), or {@code null} when healthy. */
    public String degradedState() {
        return engine.degradedState();
    }

    /**
     * True while a reorg window is open (pop → body-apply → restore): the node's canonical
     * chain sits truncated at the fork height, so it must not serve the chain to peers as
     * authoritative — the peer sync endpoints answer 503 (Retry-After) for the window — and
     * block production pauses. Surfaced on /stats so an operator can tell a normal reorg
     * pause from a degraded node.
     */
    public boolean isReorgInProgress() {
        return engine.isReorgInProgress();
    }
    public java.math.BigInteger totalWork() {
        return engine.totalWork();
    }

    /**
     * Per-round sync observability: how many peers the round knew of, how many it actually
     * tried, how many it skipped because they are banned, how many consecutive rounds made no
     * progress AT ALL — no sync extension AND no height advance (a gossip-fed healthy node
     * legitimately idle-syncs its rounds, so only a node whose chain is frozen AND whose sync
     * does nothing climbs this counter) — and whether the round had no usable sync source at
     * all. Surfaced on /stats so a wedged or eclipsed node is visible in seconds instead of
     * reading logs (testnet campaign S5: a node kept 9 healthy peers while syncing from none,
     * with no log line and no degraded marker).
     *
     * <p>{@code eclipsed} is a first-class field rather than something to infer from the two
     * peer counts: bans EVICT (PeerRegistry.penalize), so the real eclipse is usually
     * {@code peersKnown == 0}, which no combination of "tried vs skipped" distinguishes from a
     * node that simply has not discovered anyone yet on its first round.
     */
    public record SyncHealth(int peersKnown, int peersTried, int peersSkippedBanned,
                             long roundsWithoutProgress, boolean eclipsed) {}

    private volatile SyncHealth syncHealth = new SyncHealth(0, 0, 0, 0, false);

    /** Records the counters of one just-finished sync round (written by the sync loop). */
    public void recordSyncRound(int peersKnown, int peersTried, int peersSkippedBanned,
                                long roundsWithoutProgress, boolean eclipsed) {
        syncHealth = new SyncHealth(peersKnown, peersTried, peersSkippedBanned,
            roundsWithoutProgress, eclipsed);
    }

    /** The last sync round's counters (never null; zeros before the first round). */
    public SyncHealth syncHealth() {
        return syncHealth;
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
            notify(listeners.onTransactionAccepted(), transaction);
        }
        return status;
    }

    /**
     * As {@link #submitTransaction(Transaction)}, additionally recording a push-abuse strike
     * against {@code clientKey} when the peer pushed provable junk (audit: gossip push
     * ban-score). See {@link PushStrikeTable}.
     */
    public ExecutionStatus submitTransaction(Transaction transaction, String clientKey) {
        ExecutionStatus status = submitTransaction(transaction);
        if (PushStrikeTable.isFault(status)) {
            pushStrikes.note(clientKey);
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
        return admission.trySubmit();
    }

    /**
     * The aggregate (all-IP) anti-DoS gate for {@code /add_transaction(JSON)}, consumed at the HTTP
     * boundary <em>before</em> the body is decoded — symmetric to {@link #trySubmitBudget} for
     * {@code /submit} (audit M1). Internal/direct callers of {@link #submitTransaction} (block
     * production, tests) legitimately bypass this network-boundary shed.
     */
    public boolean tryMempoolSigBudget() {
        return admission.tryMempoolSig();
    }

    /** Accepts a mined block; on success the mempool is purged of its transactions. */
    public ExecutionStatus submitBlock(Block block) {
        ExecutionStatus status = engine.addBlock(block);
        if (status == ExecutionStatus.SUCCESS) {
            mempool.onBlockApplied(block);
            notify(listeners.onBlockAccepted(), block);
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
        if (PushStrikeTable.isFault(status)) {
            pushStrikes.note(clientKey);
        }
        return status;
    }

    // ---- push-abuse strikes (gossip ban-score for the push paths) ----

    /** Bounded, windowed push-fault score per client; see {@link PushStrikeTable}. */
    private final PushStrikeTable pushStrikes = new PushStrikeTable();

    /**
     * True when {@code clientKey} has pushed more faults than the window allows: the HTTP
     * boundary sheds its next push with 429 before decoding the body.
     */
    public boolean isPushShed(String clientKey) {
        return pushStrikes.isShed(clientKey);
    }

    /** Visible for testing: strike count recorded for {@code clientKey} in the current window. */
    int pushStrikeCount(String clientKey) {
        return pushStrikes.count(clientKey);
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
