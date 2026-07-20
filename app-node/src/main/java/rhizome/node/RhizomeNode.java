package rhizome.node;

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

    private BlockProducer producer;
    private ScheduledExecutorService syncScheduler;
    private PeerBroadcaster broadcaster;
    private PeerRegistry registry;
    private PeerDiscovery discovery;
    private PeerBanList banList;

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
        LedgerSnapshot snapshot = config.snapshotPath().isPresent()
            ? SnapshotLoader.fromFile(Path.of(config.snapshotPath().get()))
            : SnapshotLoader.empty(config.params().chainId());

        store = new RocksDbNodeStore(config.dataDir(), keepBlocks(config.params()));
        contractStore = new RocksDbContractStore(config.dataDir() + "/contracts");
        boxStore = new RocksDbBoxStore(config.dataDir() + "/boxes");
        tokenStore = new RocksDbTokenStore(config.dataDir() + "/tokens");
        stateStore = new RocksDbStateStore(config.dataDir() + "/state");
        verifier = new SignatureVerifier();

        // RHIZOME_SYNC=snap on an empty data dir: adopt a peer's verified state snapshot at
        // a buried pivot instead of replaying history; falls back to full sync when no peer
        // offers a usable snapshot. The engine boot below then starts at the pivot.
        if ("snap".equalsIgnoreCase(System.getenv("RHIZOME_SYNC")) && store.chainStore().height() == 0) {
            for (String peerUrl : config.peers()) {
                try {
                    if (SnapshotBootstrap.bootstrap(config.params(), snapshot, store, boxStore, tokenStore,
                            contractStore, stateStore, new HttpPeerSource(peerUrl), System.currentTimeMillis())) {
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
        var stateAccumulator = new StateAccumulator(stateStore, stateStore, config.params().maxReorgDepth());
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
        registry = new PeerRegistry(config.selfUrl(), 128, banList);
        registry.addAll(config.peers());
        service.setPeers(registry);

        startHttp();
        startGossip();
        startProducerIfConfigured();
        startNetworkLoops();

        log.info("Rhizome node started: network={} height={} apiPort={} mining={} seedPeers={}",
            config.params().networkName(), engine.height(), config.apiPort(),
            config.miner().isPresent(), config.peers().size());
    }

    private void startHttp() throws IOException {
        eventloop = Eventloop.create();
        // Stream every applied block's logs (plus a heartbeat) to SSE subscribers,
        // whatever path the block arrived by: API submit, gossip, sync or the local
        // producer. The engine listener only enqueues onto the event loop.
        sseHub = new SseLogHub(eventloop, 256);
        engine.setOnBlockApplied(height -> sseHub.publish(height, service.logsAt(height)));
        // Per-client rate limit (fixed 1s window) with a bounded client table
        // (the table cap is the memory-leak fix; the per-window count is generous
        // so honest peers on a shared host are never throttled).
        RateLimiter limiter = new RateLimiter(1000, 1000, 65_536);
        httpServer = HttpServer.builder(eventloop, NodeApi.servlet(eventloop, service, limiter, sseHub))
            .withListenPort(config.apiPort())
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
        broadcaster = new PeerBroadcaster(registry::snapshot);
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
                producer.setVote(Integer.parseInt(vote.trim()));
            }
            producer.start();
        });
    }

    private void startNetworkLoops() {
        discovery = new PeerDiscovery(registry, config.selfUrl());
        syncScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "rhizome-net");
            t.setDaemon(true);
            return t;
        });
        syncScheduler.scheduleWithFixedDelay(this::syncRound,
            config.syncPeriodMs(), config.syncPeriodMs(), TimeUnit.MILLISECONDS);
        syncScheduler.scheduleWithFixedDelay(discovery::round,
            config.syncPeriodMs(), config.syncPeriodMs(), TimeUnit.MILLISECONDS);
        // Periodic snapshot materialisation (RHIZOME_SNAPSHOT_EVERY blocks, 0 = never):
        // recapture once the chain has advanced a full interval past the last pivot, so a
        // deep-enough snapshot is always on offer for snap-syncing peers.
        long snapshotEvery = snapshotEveryBlocks();
        if (snapshotEvery > 0) {
            syncScheduler.scheduleWithFixedDelay(() -> {
                if (engine.height() >= service.snapshotPivot() + snapshotEvery && service.materializeSnapshot()) {
                    log.info("Materialized state snapshot at height {} ({} chunks)",
                        service.snapshotPivot(), service.materializedSnapshot().chunks().size());
                }
            }, config.syncPeriodMs(), config.syncPeriodMs(), TimeUnit.MILLISECONDS);
        }
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

    /** One sync round across all known peers; peer failures are isolated. */
    public void syncRound() {
        var synchronizer = new HeaderSynchronizer(engine);
        for (String peerUrl : registry.snapshot()) {
            if (registry.isBanned(peerUrl)) {
                continue;
            }
            try {
                ChainSynchronizer.Result result = synchronizer.syncFrom(new HttpPeerSource(peerUrl));
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
            } catch (RuntimeException e) {
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

    @Override
    public synchronized void close() {
        if (producer != null) {
            producer.stop();
        }
        // Stop and DRAIN the network loops before touching the store: a syncRound()
        // in flight is mid-append into RocksDB, and closing column-family handles
        // under a live native call crashes the JVM. shutdownNow() only signals —
        // awaitTermination() is what guarantees no writer is left running.
        if (syncScheduler != null) {
            syncScheduler.shutdownNow();
            try {
                if (!syncScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Network scheduler did not terminate before store close");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (broadcaster != null) {
            broadcaster.close();
        }
        if (verifier != null) {
            verifier.shutdown();
        }
        if (eventloop != null) {
            eventloop.submit(() -> httpServer.close());
            eventloop.keepAlive(false);
            eventloop.execute(eventloop::breakEventloop);
            try {
                eventloopThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
    }

    public static void main(String[] args) throws Exception {
        NetworkParametersArg net = NetworkParametersArg.fromEnv();
        NodeConfig config = NodeConfig.defaults(net.params(),
            System.getenv().getOrDefault("RHIZOME_DATA", "./data"),
            Integer.parseInt(System.getenv().getOrDefault("RHIZOME_PORT", "3000")));

        String snapshot = System.getenv("RHIZOME_SNAPSHOT");
        if (snapshot != null && !snapshot.isBlank()) {
            config = config.withSnapshot(snapshot);
        }
        String miner = System.getenv("RHIZOME_MINER");
        if (miner != null && !miner.isBlank()) {
            config = config.withMiner(rhizome.core.ledger.PublicAddress.of(miner));
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

        RhizomeNode node = new RhizomeNode(config);
        Runtime.getRuntime().addShutdownHook(new Thread(node::close));
        node.start();
        Thread.currentThread().join(); // run until killed
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
