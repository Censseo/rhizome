package rhizome.node;

import rhizome.net.HttpPeerSource;
import rhizome.net.PeerBroadcaster;
import rhizome.net.PeerDiscovery;
import rhizome.net.PeerRegistry;
import rhizome.net.PeerBanList;
import rhizome.net.PeerTokenPolicy;
import rhizome.net.PeerUrls;
import rhizome.net.RateLimiter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.activej.eventloop.Eventloop;
import io.activej.http.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.blockchain.BlockProducer;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.HeaderSynchronizer;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.SnapshotLoader;
import rhizome.core.mempool.MemPool;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.state.StateAccumulator;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.persistence.rocksdb.RocksDbBoxStore;
import rhizome.persistence.rocksdb.RocksDbContractStore;
import rhizome.persistence.rocksdb.RocksDbNodeStore;
import rhizome.persistence.rocksdb.RocksDbStateStore;
import rhizome.persistence.rocksdb.RocksDbTokenStore;
import rhizome.vm.WasmContractProcessor;
import rhizome.vm.WasmVm;

/**
 * A fully assembled node: RocksDB storage, chain engine, mempool, HTTP API, an
 * optional block producer and a periodic multi-peer synchronizer.
 *
 * <p>Wired with plain constructors rather than a reflection-based DI container,
 * so the assembly stays explicit and GraalVM-native friendly (per the
 * performance-stack analysis).
 */
public final class RhizomeNode implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RhizomeNode.class);

    private final NodeConfig config;

    private RocksDbNodeStore store;
    private RocksDbContractStore contractStore;
    private RocksDbBoxStore boxStore;
    private RocksDbTokenStore tokenStore;
    private RocksDbStateStore stateStore;
    private ChainEngine engine;
    private MemPool mempool;
    private NodeService service;
    private SignatureVerifier verifier;

    private Eventloop eventloop;
    private SseLogHub sseHub;
    private Thread eventloopThread;
    private HttpServer httpServer;
    private java.util.concurrent.ExecutorService apiWorkers;

    private BlockProducer producer;
    private ScheduledExecutorService syncScheduler;
    private PeerBroadcaster broadcaster;
    private PeerRegistry registry;
    private PeerDiscovery discovery;
    private PeerBanList banList;
    /** One shared HTTP client for all sync rounds, so a fresh client (and its selector thread +
     *  connection pool) is not built per peer per round, and keep-alive is reused (audit net #1). */
    private final java.net.http.HttpClient syncHttpClient = HttpPeerSource.newClient();
    /** Whether to refuse/ pin private peer hosts (SSRF): on for internet-exposed mainnet. */
    private boolean blockPrivatePeers;
    /**
     * Optional bearer token (env {@code RHIZOME_PEER_TOKEN}) for OUTBOUND peer-to-peer requests,
     * gated by {@link #peerTokenPolicy}: the registry is fed by UNAUTHENTICATED /add_peer and
     * PEX, so attaching the token to every request (the historical behaviour) leaked the shared
     * deployment secret — in cleartext over http — to any gossip-learned peer (audit: peer token
     * exfiltration via gossip). The token now only goes to explicitly configured peers
     * ({@code config.peers()}) over https; gossip/sync to any other peer is unauthenticated.
     * When an operator gates the ingest routes behind {@code RHIZOME_API_TOKEN}, configured
     * peers must therefore be reached over https or their 401-refused pushes simply do not
     * converge. Never logged.
     */
    private String peerToken;
    private PeerTokenPolicy peerTokenPolicy;

    // Ban-score costs per sync outcome. Serving an invalid chain (bad PoW, broken
    // continuity, claimed-heavy-proved-light) is an unambiguous protocol violation
    // and bans on the first strike; a too-deep reorg attempt is suspicious but can
    // be a legitimately forked peer; a genesis mismatch is usually just a
    // misconfigured wrong-network node.
    private static final int BAN_THRESHOLD = 100;
    private static final int PENALTY_INVALID = 100;
    private static final int PENALTY_REORG_TOO_DEEP = 25;
    private static final int PENALTY_INCOMPATIBLE = 10;
    /** Safety headroom above the deepest history the engine reads, when pruning. */
    private static final int PRUNE_MARGIN = 128;

    /**
     * Retention (in blocks) for this node, from {@code RHIZOME_PRUNE}: absent/0 = archive
     * (keep every body). A positive value must be at least the deepest history the engine may
     * read — the reorg window, uncle depth, and the difficulty/median timestamp windows —
     * plus a safety margin, or the node would prune a body it still needs. Enforced here so a
     * misconfiguration fails fast at boot rather than mid-reorg.
     */
    private static int keepBlocks(NetworkParameters params) {
        String env = System.getenv("RHIZOME_PRUNE");
        if (env == null || env.isBlank()) {
            return 0;
        }
        int requested;
        try {
            requested = Integer.parseInt(env.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_PRUNE must be an integer, was: " + env, e);
        }
        if (requested <= 0) {
            return 0; // archive
        }
        int floor = Math.max(Math.max(params.maxReorgDepth(), params.uncleMaxDepth()),
            Math.max(params.difficultyLookback(), params.medianTimeWindow())) + PRUNE_MARGIN;
        if (requested < floor) {
            throw new IllegalArgumentException("RHIZOME_PRUNE=" + requested
                + " is below the safe floor of " + floor + " blocks (reorg/uncle/difficulty/median windows)");
        }
        return requested;
    }

    public RhizomeNode(NodeConfig config) {
        this.config = config;
    }

    public synchronized void start() throws IOException {
        // Secure-by-default exposure gate (audit H-2): binding a non-loopback address without
        // an API token leaves /add_peer, /scan/register, /submit, /add_transaction and
        // /call_readonly open to the whole network. Refuse to start in that posture unless the
        // operator explicitly opts in with RHIZOME_ALLOW_OPEN_API=true (pure P2P relay nodes).
        if (config.apiToken().isEmpty() && !isLoopbackBind(config.bindAddress())
            && !"true".equalsIgnoreCase(System.getenv("RHIZOME_ALLOW_OPEN_API"))) {
            throw new IllegalStateException("refusing to start: the API binds " + config.bindAddress()
                + " without RHIZOME_API_TOKEN, leaving state-changing/operator routes open to the "
                + "network. Set RHIZOME_API_TOKEN, bind 127.0.0.1, or set RHIZOME_ALLOW_OPEN_API=true "
                + "for a pure P2P relay.");
        }
        // Block SSRF-prone (loopback / private / metadata) peer hosts by DEFAULT on every node.
        // The previous heuristic keyed this off whether the *advertised* self URL was loopback, but
        // a node may bind a public address while /add_peer stays unauthenticated, so an exposed
        // testnet or custom-net node left at the default (loopback) advertise URL ran with the
        // SSRF filter and DNS-pin rejection OFF — any network-reachable party could add a
        // 169.254.169.254 / RFC1918 peer that syncRound then fetches (audit F4). Secure-by-default
        // instead: only an explicit
        // RHIZOME_ALLOW_PRIVATE_PEERS=true opts out (for local dev/devnets peering over 127.0.0.1 or
        // private IPs via pure PEX — configured RHIZOME_PEERS seeds already bypass the filter).
        boolean allowPrivate = config.allowPrivatePeers()
            || "true".equalsIgnoreCase(System.getenv("RHIZOME_ALLOW_PRIVATE_PEERS"));
        this.blockPrivatePeers = !allowPrivate;
        String envPeerToken = System.getenv("RHIZOME_PEER_TOKEN");
        this.peerToken = envPeerToken == null || envPeerToken.isBlank() ? null : envPeerToken.trim();
        this.peerTokenPolicy = new PeerTokenPolicy(peerToken, config.peers());
        if (peerToken != null) {
            for (String peer : config.peers()) {
                String canonical = PeerUrls.canonicalize(peer);
                if (canonical != null && canonical.startsWith("http://")) {
                    log.warn("RHIZOME_PEER_TOKEN is set but configured peer {} is plain http:// — "
                        + "the token will NOT be sent to it (gossip/sync to this peer is "
                        + "unauthenticated); use an https:// peer URL to authenticate", peer);
                }
            }
        }

        LedgerSnapshot snapshot = config.snapshotPath().isPresent()
            ? SnapshotLoader.fromFile(Path.of(config.snapshotPath().get()))
            : SnapshotLoader.empty(config.params().chainId());

        store = new RocksDbNodeStore(config.dataDir(), keepBlocks(config.params()));
        contractStore = new RocksDbContractStore(config.dataDir() + "/contracts");
        boxStore = new RocksDbBoxStore(config.dataDir() + "/boxes");
        tokenStore = new RocksDbTokenStore(config.dataDir() + "/tokens");
        stateStore = new RocksDbStateStore(config.dataDir() + "/state");
        verifier = new SignatureVerifier();

        // A snap-sync bootstrap seeds several independent stores that commit separately; if a
        // prior run was interrupted mid-seed, the on-disk state is inconsistent. Fail fast with
        // a clear instruction rather than running on it (audit M8).
        if (store.bootstrapInProgress()) {
            throw new IOException("a previous snap-sync bootstrap did not complete; the data directory ("
                + config.dataDir() + ") is inconsistent — delete it and restart to re-bootstrap");
        }

        // RHIZOME_SYNC=snap on an empty data dir: adopt a peer's verified state snapshot at
        // a buried pivot instead of replaying history; falls back to full sync when no peer
        // offers a usable snapshot. The engine boot below then starts at the pivot.
        if ("snap".equalsIgnoreCase(System.getenv("RHIZOME_SYNC")) && store.chainStore().height() == 0) {
            for (String peerUrl : config.peers()) {
                try {
                    if (SnapshotBootstrap.bootstrap(config.params(), snapshot, store, boxStore, tokenStore,
                            contractStore, stateStore, new HttpPeerSource(peerUrl, blockPrivatePeers,
                                syncHttpClient, peerTokenPolicy),
                            System.currentTimeMillis(), Path.of(config.dataDir()))) {
                        break;
                    }
                } catch (RuntimeException e) {
                    log.warn("Snap bootstrap from {} failed: {}", peerUrl, e.toString());
                }
            }
        }

        var contractProcessor = new WasmContractProcessor(new WasmVm(), contractStore,
            config.params().maxReorgDepth());
        var boxProcessor = new DefaultBoxProcessor(boxStore, config.params());
        var tokenProcessor = new DefaultTokenProcessor(tokenStore, config.params());
        // Authenticated state root over ledger + boxes + tokens (committed in each header).
        int stateRetainDepth = config.params().maxReorgDepth();
        checkStateRetention(stateRetainDepth, config.params().maxReorgDepth());
        var stateAccumulator = new StateAccumulator(stateStore, stateStore, stateRetainDepth);
        // Contracts read data boxes (Ergo-style data inputs) through the box processor's
        // session-aware view, so a box written earlier in the block is visible.
        contractProcessor.setBoxReader(boxProcessor::get);
        engine = ChainEngine.init(config.params(), store.ledger(), store.chainStore(),
            store.nonceStore(), snapshot, null, System::currentTimeMillis, verifier, contractProcessor,
            boxProcessor, tokenProcessor, stateAccumulator);
        mempool = new MemPool(config.params(), verifier, engine, config.mempoolSize());
        service = new NodeService(engine, mempool);
        // Expose contract event logs and box lifecycle events (by block height) so agents
        // can watch on-chain state on one feed.
        service.setLogSource(contractProcessor::logs);
        // Dashboard introspection: deployed code lookup for GET /contract.
        service.setCodeSource(contractProcessor::codeAt);
        service.setBoxEventSource(boxProcessor::events);
        service.setTokenEventSource(tokenProcessor::events);
        // Read-only dry-run calls (query contract state without a transaction).
        service.setContracts(contractProcessor);
        // Snap-sync source: this node can materialise and serve full-state snapshots,
        // verifiable by peers against the state root committed in the pivot header.
        service.setSnapshotSource(new rhizome.core.state.snapshot.DomainStateAdapter(
            store.ledger(), store.nonceStore(), boxStore, tokenStore,
            new rhizome.vm.ContractStateAdapter(contractStore), null));

        // Every node keeps a live peer set (seeded from config), serves /peers and
        // accepts announcements, so the network can self-organise from a few seeds.
        banList = new PeerBanList(BAN_THRESHOLD, 60 * 60 * 1000L, 4096);
        // Block SSRF-prone (loopback / private / metadata) discovered peers on mainnet, where
        // the node is internet-exposed. Testnet/devnets peer over localhost, so they stay
        // permissive; an operator running mainnet over private infra can opt back in.
        registry = new PeerRegistry(config.selfUrl(), 128, banList, blockPrivatePeers);
        // Config peers are trusted seeds: protected from eclipse eviction and SSRF filtering.
        registry.addSeeds(config.peers());
        service.setPeers(registry);

        startHttp();
        startGossip();
        startProducerIfConfigured();
        startNetworkLoops();

        log.info("Rhizome node started: network={} height={} apiPort={} mining={} seedPeers={}",
            config.params().networkName(), engine.height(), config.apiPort(),
            config.miner().isPresent(), config.peers().size());
        // The open-bind posture is only reachable through the RHIZOME_ALLOW_OPEN_API override
        // (start() refuses it otherwise) — surface it loudly (audit H-2).
        if (config.apiToken().isEmpty() && !isLoopbackBind(config.bindAddress())) {
            log.warn("RHIZOME_ALLOW_OPEN_API override active: the API binds {} without "
                + "RHIZOME_API_TOKEN — state-changing/operator routes are open to the network.",
                config.bindAddress());
        }
    }

    /**
     * Fail-fast wiring guard: the state accumulator must retain roots at least as deep as the
     * deepest reorg the engine may perform ({@code maxReorgDepth}), or a reorg past the retained
     * window could not rebuild the state roots it rolls back over — a boot-time configuration
     * error, not a mid-reorg surprise (audit: retainDepth below maxReorgDepth).
     */
    static void checkStateRetention(int retainDepth, int maxReorgDepth) {
        if (retainDepth < maxReorgDepth) {
            throw new IllegalStateException("state accumulator retainDepth=" + retainDepth
                + " is below maxReorgDepth=" + maxReorgDepth
                + "; a reorg that deep could not rebuild state roots — raise the retention");
        }
    }

    /** True when the configured bind address resolves to a loopback interface only. */
    private static boolean isLoopbackBind(String bindAddress) {
        try {
            return java.net.InetAddress.getByName(bindAddress).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false; // unresolvable: startHttp will fail loudly; warn on the safe side
        }
    }

    private void startHttp() throws IOException {
        eventloop = Eventloop.create();
        // Consensus-heavy request handlers (block/tx ingest, VM dry-run, lock-guarded explorer
        // reads) run on this bounded pool, NOT on the event-loop thread: a single valid-but-heavy
        // block or one max-gas dry-run would otherwise freeze every route the loop serves —
        // /peers, SSE, heartbeats — for its whole duration (audit: eventloop blocked by consensus
        // work). Bounded queue + AbortPolicy: a saturated pool sheds with 429 at the servlet
        // boundary instead of queueing unbounded latency. Daemon threads; drained in close()
        // before the stores close.
        int workerCount = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        apiWorkers = new java.util.concurrent.ThreadPoolExecutor(workerCount, workerCount,
            60L, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "rhizome-api-worker");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        // Stream every applied block's logs (plus a heartbeat) to SSE subscribers,
        // whatever path the block arrived by: API submit, gossip, sync or the local
        // producer. The engine listener only enqueues onto the event loop.
        sseHub = new SseLogHub(eventloop, 256);
        engine.setOnBlockApplied(height -> sseHub.publish(height, () -> service.logsAt(height)));
        // Per-client rate limit (fixed 1s window) with a bounded client table
        // (the table cap is the memory-leak fix; the per-window count is generous
        // so honest peers on a shared host are never throttled).
        RateLimiter limiter = new RateLimiter(1000, 1000, 65_536);
        java.util.Set<String> allowedHosts = allowedHosts(config);
        // Surface the effective DNS-rebinding allowlist at startup: it now also covers the
        // host's LAN interface addresses (audit F9), which are otherwise invisible to the operator.
        log.info("API allowed Host authorities (DNS-rebinding guard): {}", allowedHosts);
        httpServer = HttpServer.builder(eventloop,
                NodeApi.servlet(eventloop, service, limiter, sseHub, allowedHosts,
                    config.apiToken().orElse(null), apiWorkers))
            .withListenAddress(new java.net.InetSocketAddress(config.bindAddress(), config.apiPort()))
            // Bound how long a connection may stall a read or write: body sizes are capped, but
            // without an inactivity deadline a client can trickle a POST body one byte at a time
            // (slow-loris), pinning a connection slot and its buffers indefinitely — the rate
            // limiter only sees fully-received requests (audit M2). 30 s is generous for any
            // honest client on the bounded bodies this API serves; active SSE streams and large
            // snapshot chunks update the connection's activity timestamp, so they are unaffected.
            .withReadWriteTimeout(java.time.Duration.ofSeconds(30))
            .build();
        eventloop.keepAlive(true);
        eventloopThread = new Thread(eventloop, "rhizome-http");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
        try {
            eventloop.submit(() -> httpServer.listen()).get();
        } catch (Exception e) {
            throw new IOException("Failed to start HTTP server on port " + config.apiPort(), e);
        }
    }

    private void startGossip() {
        broadcaster = new PeerBroadcaster(registry::snapshot, blockPrivatePeers, peerTokenPolicy);
        // Re-broadcast blocks/transactions accepted from RPC (flood; loops terminate
        // because a peer that already has an item rejects it and won't gossip on).
        service.setOnBlockAccepted(broadcaster::broadcastBlock);
        service.setOnTransactionAccepted(broadcaster::broadcastTransaction);
    }

    private void startProducerIfConfigured() {
        config.miner().ifPresent(miner -> {
            producer = new BlockProducer(engine, mempool, miner, System::currentTimeMillis,
                config.blockIntervalMs());
            producer.setOnProduced(broadcaster::broadcastBlock);
            // Optional parameter vote this miner casts on each block (RHIZOME_VOTE):
            // ±1 storageFeeFactor, ±2 minValuePerByte, 0/absent = abstain.
            String vote = System.getenv("RHIZOME_VOTE");
            if (vote != null && !vote.isBlank()) {
                producer.setVote(parseVote(vote));
            }
            producer.start();
        });
    }

    /**
     * Parses {@code RHIZOME_VOTE} with a clear error and bounds it to the protocol's vote domain
     * (0 abstain, ±1 storageFeeFactor, ±2 minValuePerByte). An out-of-domain value would either
     * crash the producer thread with a raw {@link NumberFormatException} or mint blocks the
     * consensus gate rejects as {@code INVALID_VOTE} (audit: unvalidated config).
     */
    static int parseVote(String raw) {
        final int vote;
        try {
            vote = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_VOTE must be an integer, was: " + raw, e);
        }
        if (vote < -2 || vote > 2) {
            throw new IllegalArgumentException("RHIZOME_VOTE must be in [-2, 2] "
                + "(0 abstain, ±1 storageFeeFactor, ±2 minValuePerByte), was: " + vote);
        }
        return vote;
    }

    private void startNetworkLoops() {
        discovery = new PeerDiscovery(registry, config.selfUrl(), blockPrivatePeers, peerTokenPolicy);
        syncScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "rhizome-net");
            t.setDaemon(true);
            return t;
        });
        // Every scheduled task is wrapped in guarded(): a task whose run() lets ANY Throwable
        // escape is silently unscheduled by ScheduledThreadPoolExecutor — one stray Error would
        // stop that loop forever, with no log line (audit: scheduler task suppression).
        syncScheduler.scheduleWithFixedDelay(guarded(this::syncRound, "sync round"),
            config.syncPeriodMs(), config.syncPeriodMs(), TimeUnit.MILLISECONDS);
        syncScheduler.scheduleWithFixedDelay(guarded(discovery::round, "peer discovery"),
            config.syncPeriodMs(), config.syncPeriodMs(), TimeUnit.MILLISECONDS);
        // Periodic snapshot materialisation (RHIZOME_SNAPSHOT_EVERY blocks, 0 = never):
        // recapture once the chain has advanced a full interval past the last pivot, so a
        // deep-enough snapshot is always on offer for snap-syncing peers.
        long snapshotEvery = snapshotEveryBlocks();
        if (snapshotEvery > 0) {
            syncScheduler.scheduleWithFixedDelay(guarded(() -> {
                if (engine.height() >= service.snapshotPivot() + snapshotEvery && service.materializeSnapshot()) {
                    log.info("Materialized state snapshot at height {} ({} chunks)",
                        service.snapshotPivot(), service.materializedSnapshot().chunks().size());
                }
            }, "snapshot materialisation"), config.syncPeriodMs(), config.syncPeriodMs(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Wraps a scheduled task so NO Throwable reaches the scheduler (which would swallow it and
     * cancel all future runs without logging). Everything is caught and logged here instead.
     */
    private static Runnable guarded(Runnable task, String name) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("{} failed; the schedule continues", name, t);
            }
        };
    }

    /** Blocks between snapshot materialisations, from {@code RHIZOME_SNAPSHOT_EVERY} (default ~1 day). */
    private static long snapshotEveryBlocks() {
        String env = System.getenv("RHIZOME_SNAPSHOT_EVERY");
        if (env == null || env.isBlank()) {
            return 17_280;
        }
        try {
            return Long.parseLong(env.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_SNAPSHOT_EVERY must be an integer, was: " + env, e);
        }
    }

    /** Wall-clock budget for one sync round: past this, remaining peers are left for the next round so
     *  one slow (but not-yet-timed-out) peer, or a long tail of them, cannot starve later peers or delay
     *  the schedule (audit net #2). A single in-progress sync is never cut — the check is between peers,
     *  so a legitimate long catch-up from a good peer still completes. */
    private static final long SYNC_ROUND_BUDGET_MS = 60_000L;

    /** Rotates the per-round starting peer (single sync thread, so no synchronization needed). */
    private long syncRoundCursor;

    /** One sync round across all known peers; peer failures are isolated. */
    public void syncRound() {
        var synchronizer = new HeaderSynchronizer(engine);
        java.util.List<String> peers = registry.snapshot();
        int n = peers.size();
        if (n == 0) {
            return;
        }
        // Rotate the starting index each round so that if the peers visited first are slow and eat the
        // round budget, the ones skipped this round are visited first next round — every peer gets a turn.
        int start = (int) Math.floorMod(syncRoundCursor++, n);
        long deadline = System.currentTimeMillis() + SYNC_ROUND_BUDGET_MS;
        for (int i = 0; i < n; i++) {
            if (System.currentTimeMillis() >= deadline) {
                log.debug("Sync round budget reached; deferring {} of {} peers to the next round", n - i, n);
                break;
            }
            String peerUrl = peers.get((start + i) % n);
            if (registry.isBanned(peerUrl)) {
                continue;
            }
            try {
                ChainSynchronizer.Result result = synchronizer.syncFrom(
                    new HttpPeerSource(peerUrl, blockPrivatePeers, syncHttpClient, peerTokenPolicy));
                switch (result) {
                    case EXTENDED, REORGED ->
                        log.info("Synced from {}: {} -> height {}", peerUrl, result, engine.height());
                    case PEER_INVALID -> penalize(peerUrl, PENALTY_INVALID, "served an invalid chain");
                    case REORG_TOO_DEEP -> penalize(peerUrl, PENALTY_REORG_TOO_DEEP, "reorg past finality");
                    case INCOMPATIBLE -> penalize(peerUrl, PENALTY_INCOMPATIBLE, "wrong network / genesis");
                    case PEER_PRUNED ->
                        log.debug("Peer {} pruned the bodies we need; trying another source", peerUrl);
                    case NO_CHANGE -> { /* healthy, nothing to do */ }
                }
            } catch (HttpPeerSource.PeerUnavailableException e) {
                // Transport failures are not misbehaviour; PeerDiscovery prunes the
                // persistently unreachable. Only protocol violations earn ban score.
                log.debug("Peer {} unavailable: {}", peerUrl, e.getMessage());
            } catch (HttpPeerSource.PeerProtocolException e) {
                // Malformed protocol data (junk scalars, absurd snapshot chunk counts) is a
                // protocol violation like serving an invalid chain — penalize accordingly.
                penalize(peerUrl, PENALTY_INVALID, "served malformed protocol data");
            } catch (Throwable e) {
                // Every Error is fatal-by-doctrine here: a HostFault is a LOCAL store/infra
                // failure surfaced from contract execution (see HostFault), and a JVM Error
                // (OutOfMemoryError, NoClassDefFoundError, ...) means this node is unhealthy
                // regardless of which peer happened to trigger it. Rethrow so the round aborts
                // and guarded() logs the full stack as an error — the scheduler boundary keeps
                // the sync loop alive. Only exceptions (bad peer data, per-peer handling bugs)
                // are isolated to the peer that caused them.
                if (e instanceof Error err) {
                    throw err;
                }
                log.warn("Sync from {} failed: {}", peerUrl, e.toString());
            }
        }
    }

    private void penalize(String peerUrl, int points, String reason) {
        if (registry.penalize(peerUrl, points)) {
            log.warn("Banned peer {} ({})", peerUrl, reason);
        } else {
            log.debug("Penalized peer {} +{} ({})", peerUrl, points, reason);
        }
    }

    /** Runs one peer-discovery round now (otherwise it runs on the network schedule). */
    public void discoverRound() {
        discovery.round();
    }

    public java.util.List<String> knownPeers() {
        return registry.snapshot();
    }

    public PeerBanList banList() {
        return banList;
    }

    public NodeService service() {
        return service;
    }

    public ChainEngine engine() {
        return engine;
    }

    public int apiPort() {
        return config.apiPort();
    }

    /**
     * The legitimate {@code Host} authorities for the DNS-rebinding defense (audit S-2): the node's
     * advertised host and the loopback names, each with and without the API port. A browser POST whose
     * Host is not in this set is refused — a rebound page carries the attacker's hostname, not one of
     * these. Lower-cased for case-insensitive matching.
     */
    private static java.util.Set<String> allowedHosts(NodeConfig config) {
        java.util.Set<String> hosts = new java.util.HashSet<>();
        int port = config.apiPort();
        java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of(
            "localhost", "127.0.0.1", "[::1]"));
        try {
            String advertisedHost = java.net.URI.create(config.selfUrl()).getHost();
            if (advertisedHost != null && !advertisedHost.isEmpty()) {
                names.add(advertisedHost);
            }
        } catch (RuntimeException ignored) {
            // malformed advertised URL: fall back to the loopback names only
        }
        // Best-effort: the host's own non-loopback interface addresses, so the dashboard reached
        // over the LAN (http://192.168.x.y:port/) is not 403'd by the rebinding guard under the
        // default config (audit F9). Enumeration failures are ignored — the loopback and
        // advertised names still apply.
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface iface = ifs.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    String host = addr.getHostAddress();
                    int zone = host.indexOf('%'); // strip any IPv6 zone id (%eth0)
                    if (zone >= 0) {
                        host = host.substring(0, zone);
                    }
                    names.add(addr instanceof java.net.Inet6Address ? "[" + host + "]" : host);
                }
            }
        } catch (RuntimeException | java.net.SocketException ignored) {
            // interface enumeration unavailable: loopback + advertised names still apply
        }
        for (String name : names) {
            hosts.add(name.toLowerCase(java.util.Locale.ROOT));
            hosts.add((name + ":" + port).toLowerCase(java.util.Locale.ROOT));
        }
        return hosts;
    }

    @Override
    public synchronized void close() {
        // Stop NEW work first: close the HTTP server and drain the eventloop before touching
        // anything else, so no request handler can be inside a native store read/write while
        // shutdown proceeds (a late /sync read racing the column-family close aborts the JVM).
        // The join budget covers the worst in-flight request: peer body reads are deadline-bound
        // (BodyReadDeadline, 30 s), so a drained eventloop always dies within this window.
        if (eventloop != null) {
            eventloop.submit(() -> httpServer.close());
            eventloop.keepAlive(false);
            eventloop.execute(eventloop::breakEventloop);
            try {
                eventloopThread.join(35_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (producer != null) {
            producer.stop();
        }
        // Drain in-flight API workers (offloaded block/tx ingest, dry-runs, explorer reads)
        // before the stores close: a worker mid-validation may hold the engine lock or sit
        // inside a native RocksDB call, so an undrained pool makes the store close below unsafe
        // in exactly the two ways the syncScheduler branch describes — skip it the same way.
        boolean workersStuck = false;
        if (apiWorkers != null) {
            apiWorkers.shutdownNow();
            try {
                if (!apiWorkers.awaitTermination(30, TimeUnit.SECONDS)) {
                    workersStuck = true;
                    log.error("API worker pool still busy after 30 s; the store close will be "
                        + "skipped (native use-after-free / lock-hang risk)");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Stop and DRAIN the network loops before touching the store: a syncRound()
        // in flight is mid-append into RocksDB, and closing column-family handles
        // under a live native call crashes the JVM. shutdownNow() only signals —
        // awaitTermination() is what guarantees no writer is left running.
        boolean syncStuck = false;
        if (syncScheduler != null) {
            syncScheduler.shutdownNow();
            try {
                // The await budget must exceed the worst in-flight syncRound: peer body reads
                // are deadline-bound at 30 s (BodyReadDeadline), so a stuck sync always unwinds
                // within ~45 s.
                if (!syncScheduler.awaitTermination(45, TimeUnit.SECONDS)) {
                    // A truly stuck sync thread makes the store close below UNSAFE on two
                    // counts: if it is inside a native RocksDB call, closing the handles under
                    // it is a use-after-free (JVM-level SIGSEGV, not a catchable exception);
                    // if it holds the engine lock while stuck (e.g. in a peer HTTP read inside
                    // a consistent-view section), acquiring that lock here hangs shutdown
                    // forever. Neither is recoverable in-process, so the stores are left open:
                    // the thread is a daemon that dies with the process, the OS releases the
                    // flock on exit, and RocksDB's WAL recovers a torn write on next open.
                    syncStuck = true;
                    log.error("Network scheduler still busy after 45 s; skipping the store "
                        + "close (native use-after-free / lock-hang risk) — WAL recovery will "
                        + "cover a torn write on next open");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (discovery != null) {
                discovery.close(); // stop the PEX fan-out pool (daemon threads, best-effort)
            }
            if (broadcaster != null) {
                broadcaster.close();
            }
            if (verifier != null) {
                verifier.shutdown();
            }
        } finally {
            // Close the stores under the engine lock: producer/sync/eventloop are stopped above,
            // but a straggler (late gossip task, timed-out eventloop job) could still be queued.
            // Holding the lock guarantees no thread is inside a native write while the handles
            // close; a late writer afterwards gets a clean "database is closed" Java exception
            // instead of corrupting the native heap. Runs even if a step above threw, so the
            // data directory is never left locked open (audit: incomplete shutdown). SKIPPED
            // when the sync scheduler never drained: the stuck thread may be inside a native
            // call (close = SIGSEGV) or holding this very lock (close = shutdown hang) — see
            // the scheduler branch above.
            if (engine != null && !syncStuck && !workersStuck) {
                engine.runExclusive(() -> {
                    if (store != null) {
                        store.close();
                    }
                    if (contractStore != null) {
                        contractStore.close();
                    }
                    if (boxStore != null) {
                        boxStore.close();
                    }
                    if (tokenStore != null) {
                        tokenStore.close();
                    }
                    if (stateStore != null) {
                        stateStore.close();
                    }
                });
            }
        }
    }

    public static void main(String[] args) throws Exception {
        NetworkParametersArg net = NetworkParametersArg.fromEnv();
        NodeConfig config = NodeConfig.defaults(net.params(),
            System.getenv().getOrDefault("RHIZOME_DATA", "./data"),
            parsePort(System.getenv().getOrDefault("RHIZOME_PORT", "3000")));

        String snapshot = System.getenv("RHIZOME_SNAPSHOT");
        if (snapshot != null && !snapshot.isBlank()) {
            config = config.withSnapshot(snapshot);
        }
        String miner = System.getenv("RHIZOME_MINER");
        if (miner != null && !miner.isBlank()) {
            config = config.withMiner(rhizome.core.ledger.PublicAddress.of(miner));
        }
        // Producer pacing override, mainly for local devnets (fast blocks behind the
        // dashboard); consensus rules still bound what other nodes accept.
        String interval = System.getenv("RHIZOME_BLOCK_INTERVAL_MS");
        if (interval != null && !interval.isBlank()) {
            config = config.withBlockIntervalMs(parseBlockIntervalMs(interval));
        }
        String peers = System.getenv("RHIZOME_PEERS");
        if (peers != null && !peers.isBlank()) {
            config = config.withPeers(java.util.Arrays.stream(peers.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        String advertise = System.getenv("RHIZOME_ADVERTISE");
        if (advertise != null && !advertise.isBlank()) {
            config = config.withAdvertisedUrl(advertise.trim());
        }
        // Bind address for the HTTP API (default 127.0.0.1 — secure by default, audit H-2);
        // a public-facing node binds 0.0.0.0 explicitly and must then also set
        // RHIZOME_API_TOKEN (or RHIZOME_ALLOW_OPEN_API=true for a pure relay).
        String bind = System.getenv("RHIZOME_BIND_ADDRESS");
        if (bind != null && !bind.isBlank()) {
            config = config.withBindAddress(bind.trim());
        }
        // Optional bearer token gating the state-changing/operator routes (audit F4).
        String token = System.getenv("RHIZOME_API_TOKEN");
        if (token != null && !token.isBlank()) {
            config = config.withApiToken(token.trim());
        }

        RhizomeNode node = new RhizomeNode(config);
        Runtime.getRuntime().addShutdownHook(new Thread(node::close));
        node.start();
        Thread.currentThread().join(); // run until killed
    }

    /**
     * Parses {@code RHIZOME_PORT} with a clear error and a range check (audit: unvalidated
     * config — a typo'd port previously died with a raw NumberFormatException stack, or bound
     * a nonsense port).
     */
    static int parsePort(String raw) {
        final int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_PORT must be an integer, was: " + raw, e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("RHIZOME_PORT must be in [1, 65535], was: " + port);
        }
        return port;
    }

    /** Parses {@code RHIZOME_BLOCK_INTERVAL_MS} with a clear error and a positivity check. */
    static long parseBlockIntervalMs(String raw) {
        final long interval;
        try {
            interval = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("RHIZOME_BLOCK_INTERVAL_MS must be an integer, was: " + raw, e);
        }
        if (interval <= 0) {
            throw new IllegalArgumentException("RHIZOME_BLOCK_INTERVAL_MS must be positive, was: " + interval);
        }
        return interval;
    }

    /** Selects the network from RHIZOME_NETWORK (mainnet|testnet). */
    private record NetworkParametersArg(rhizome.core.blockchain.NetworkParameters params) {
        static NetworkParametersArg fromEnv() {
            String name = System.getenv().getOrDefault("RHIZOME_NETWORK", "mainnet");
            return new NetworkParametersArg("testnet".equalsIgnoreCase(name)
                ? rhizome.core.blockchain.NetworkParameters.testnet()
                : rhizome.core.blockchain.NetworkParameters.cleanMainnet());
        }
    }
}
