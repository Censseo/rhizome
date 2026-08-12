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
 *
 * <p>The lifecycle has two distinct halves, and keeping them apart is what makes the node
 * testable. {@link #assemble} builds the whole graph — {@link NodeComponents} — and binds nothing;
 * {@link #start()} takes that graph and opens the socket, starts the threads and arms the
 * schedules — {@link NodeRuntime}. Only the second half needs a free port, which is why every
 * deployment posture the first half decides (an exposed bind, a prune depth, snap-sync, the Host
 * allowlist) is now reachable from a plain unit test.
 */
public final class RhizomeNode implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RhizomeNode.class);

    private final NodeConfig config;

    /**
     * The assembled object graph, or null before {@link #start()} has built it.
     *
     * <p>This field and {@link #runtime} replace twenty-three individually-assigned ones. The
     * count was the problem: they were written across four lifecycle methods, so reading any one
     * of them meant knowing which methods had already run, and {@code close()} answered that
     * question twenty-three separate times — with null checks that a start failing halfway could
     * satisfy in incoherent combinations.
     */
    private NodeComponents components;

    /** The sockets, threads and schedules, or null until {@link #start()} has opened them. */
    private NodeRuntime runtime;

    // Ban-score costs per sync outcome. Serving an invalid chain (bad PoW, broken
    // continuity, claimed-heavy-proved-light) is a protocol violation, but the signal
    // most exposed to races — a peer mid-reorg can transiently serve a chain that reads
    // as invalid — must not ban on the first strike: PENALTY_INVALID = 34 of 100 takes
    // three strikes, and the address-wide escalation in PeerBanList keeps port-rotation
    // attacks compensated (testnet campaign S5: one transient PEER_INVALID during a
    // reorg eclipsed a healthy node). REORG_TOO_DEEP carries NO penalty at all: a branch
    // past the reorg horizon is not misbehaviour — on a forked network the losing camp
    // legitimately diverges deeper than finality — and scoring it accumulated +25/strike
    // to a 1 h ban, renewed hourly, a permanent mutual lock that prevented the natural
    // heal (testnet campaign replay: two equal-rate mining camps stayed locked for hours).
    // A genesis mismatch is usually just a misconfigured wrong-network node.
    private static final int BAN_THRESHOLD = 100;
    private static final int PENALTY_INVALID = 34;
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
    static int parseKeepBlocks(String env, NetworkParameters params) {
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

    /**
     * Builds the node's whole object graph — stores, engine, mempool, services, peer set — and
     * returns it. Opens no socket, schedules no loop, mines nothing.
     *
     * <p>Everything here used to be the first hundred lines of {@code start()}, which is why none
     * of it was reachable without binding a port: the exposure refusal, the snap-sync bootstrap,
     * the retention check and the {@code Host} allowlist discovery could only be exercised by a
     * test willing to take a port, and two postures could not be compared in one JVM. Splitting
     * the construction from the listening is what makes them plain function calls.
     *
     * <p>Failure releases what it opened. A store that opens and is then abandoned keeps its
     * RocksDB {@code LOCK} until the process exits, so the next attempt on the same data
     * directory would fail for a reason unrelated to the actual cause.
     */
    static NodeComponents assemble(NodeConfig config) throws IOException {
        // Secure-by-default exposure gate (audit H-2): binding a non-loopback address without
        // an API token leaves /add_peer, /scan/register, /submit, /add_transaction and
        // /call_readonly open to the whole network. Refuse to start in that posture unless the
        // operator explicitly opts in with RHIZOME_ALLOW_OPEN_API=true (pure P2P relay nodes).
        if (config.apiToken().isEmpty() && !isLoopbackBind(config.bindAddress())
            && !config.allowOpenApi()) {
            throw new IllegalStateException("refusing to start: the API binds " + config.bindAddress()
                + " without RHIZOME_API_TOKEN, leaving state-changing/operator routes open to the "
                + "network. Set RHIZOME_API_TOKEN, bind 127.0.0.1, or set RHIZOME_ALLOW_OPEN_API=true "
                + "for a pure P2P relay.");
        }
        // The opt-in is legitimate for a relay, but it is worth saying out loud what it buys the
        // network: anyone can POST /add_peer, so anyone chooses hosts this node will periodically
        // fetch (SSRF-filtered, bounded, evicted on failure — audit B-3).
        if (config.apiToken().isEmpty() && !isLoopbackBind(config.bindAddress())) {
            log.warn("RHIZOME_ALLOW_OPEN_API=true: /add_peer and the other operator routes are "
                + "unauthenticated on {}. Any caller can enqueue a host for this node to fetch; "
                + "set RHIZOME_API_TOKEN unless this is a public relay.", config.bindAddress());
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
        boolean blockPrivatePeers = !config.allowPrivatePeers();
        // Optional bearer token (env RHIZOME_PEER_TOKEN) for OUTBOUND peer-to-peer requests, gated
        // by the PeerTokenPolicy: the registry is fed by UNAUTHENTICATED /add_peer and PEX, so
        // attaching the token to every request (the historical behaviour) leaked the shared
        // deployment secret — in cleartext over http — to any gossip-learned peer (audit: peer
        // token exfiltration via gossip). The token now only goes to explicitly configured peers
        // (config.peers()) over https; gossip/sync to any other peer is unauthenticated. When an
        // operator gates the ingest routes behind RHIZOME_API_TOKEN, configured peers must
        // therefore be reached over https or their 401-refused pushes simply do not converge.
        // Never logged.
        String peerToken = config.peerToken().orElse(null);
        PeerTokenPolicy peerTokenPolicy = new PeerTokenPolicy(peerToken, config.peers());
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

        // Opened one at a time and unwound in reverse on any failure below (see the javadoc).
        java.util.Deque<AutoCloseable> opened = new java.util.ArrayDeque<>();
        try {
            var store = opened(opened, new RocksDbNodeStore(config.dataDir(), config.keepBlocks()));
            var contractStore = opened(opened, new RocksDbContractStore(config.dataDir() + "/contracts"));
            var boxStore = opened(opened, new RocksDbBoxStore(config.dataDir() + "/boxes"));
            var tokenStore = opened(opened, new RocksDbTokenStore(config.dataDir() + "/tokens"));
            var stateStore = opened(opened, new RocksDbStateStore(config.dataDir() + "/state"));
            var verifier = new SignatureVerifier();
            opened.push(verifier::shutdown);

            // A snap-sync bootstrap seeds several independent stores that commit separately; if a
            // prior run was interrupted mid-seed, the on-disk state is inconsistent. Fail fast with
            // a clear instruction rather than running on it (audit M8).
            if (store.bootstrapInProgress()) {
                throw new IOException("a previous snap-sync bootstrap did not complete; the data directory ("
                    + config.dataDir() + ") is inconsistent — delete it and restart to re-bootstrap");
            }

            // One shared HTTP client for all sync rounds, so a fresh client (and its selector
            // thread + connection pool) is not built per peer per round (audit net #1). Built here
            // because the snap bootstrap just below is already a peer fetch.
            java.net.http.HttpClient syncHttpClient = HttpPeerSource.newClient();

            // RHIZOME_SYNC=snap on an empty data dir: adopt a peer's verified state snapshot at
            // a buried pivot instead of replaying history; falls back to full sync when no peer
            // offers a usable snapshot. The engine boot below then starts at the pivot.
            if (config.snapSync() && store.chainStore().height() == 0) {
                for (String peerUrl : config.peers()) {
                    try {
                        if (SnapshotBootstrap.bootstrap(config.params(), snapshot,
                                RocksBootstrapTarget.of(store, boxStore, tokenStore, stateStore),
                                contractStore, new HttpPeerSource(peerUrl, blockPrivatePeers,
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
            var engine = ChainEngine.init(config.params(), store.ledger(), store.chainStore(),
                store.nonceStore(), snapshot, null, System::currentTimeMillis, verifier, contractProcessor,
                boxProcessor, tokenProcessor, stateAccumulator);
            var mempool = new MemPool(config.params(), verifier, engine, config.mempoolSize());
            // Every node keeps a live peer set (seeded from config), serves /peers and
            // accepts announcements, so the network can self-organise from a few seeds.
            var banList = new PeerBanList(BAN_THRESHOLD, 60 * 60 * 1000L, 4096);
            // Block SSRF-prone (loopback / private / metadata) discovered peers on mainnet, where
            // the node is internet-exposed. Testnet/devnets peer over localhost, so they stay
            // permissive; an operator running mainnet over private infra can opt back in.
            var registry = new PeerRegistry(config.selfUrl(), 128, banList, blockPrivatePeers);
            // Config peers are trusted seeds: protected from eclipse eviction and SSRF filtering.
            registry.addSeeds(config.peers());

            // Re-broadcast blocks/transactions accepted from RPC (flood; loops terminate because a
            // peer that already has an item rejects it and won't gossip on). Assembled here rather
            // than after the socket opens — as startGossip() did — because the broadcaster takes
            // nothing but the peer set and the config, and no request can arrive before start().
            var broadcaster = new PeerBroadcaster(registry::snapshot, blockPrivatePeers, peerTokenPolicy);
            opened.push(broadcaster::close);

            var service = new NodeService(engine, mempool, AdmissionControl.defaults(),
                NodeSources.builder()
                    .peers(registry)
                    // Contract event logs and box/token lifecycle events by height, so agents can
                    // watch on-chain state on one feed.
                    .logSource(contractProcessor::logs)
                    .boxEventSource(boxProcessor::events)
                    .tokenEventSource(tokenProcessor::events)
                    // Dashboard introspection: deployed code lookup for GET /contract.
                    .codeSource(contractProcessor::codeAt)
                    // Read-only dry-run calls (query contract state without a transaction).
                    .contracts(contractProcessor)
                    // Snap-sync source: this node can materialise and serve full-state snapshots,
                    // verifiable by peers against the state root committed in the pivot header.
                    .snapshotSource(new rhizome.core.state.snapshot.DomainStateAdapter(
                        store.ledger(), store.nonceStore(), boxStore, tokenStore,
                        new rhizome.vm.ContractStateAdapter(contractStore), null))
                    .build(),
                new NodeListeners(broadcaster::broadcastBlock, broadcaster::broadcastTransaction));
            opened.push(service::close);
            // Snapshot spools live with the stores, not the OS temp dir (often a tmpfs → the whole
            // state would silently be back in RAM); the setter also sweeps SIGKILL leftovers.
            try {
                service.setSnapshotSpoolDir(Path.of(config.dataDir(), "snapshots"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(
                    "cannot create snapshot spool dir under " + config.dataDir(), e);
            }

            java.util.Set<String> allowedHosts = allowedHosts(config);
            // Surface the effective DNS-rebinding allowlist at startup: it now also covers the
            // host's LAN interface addresses (audit F9), which are otherwise invisible to the operator.
            log.info("API allowed Host authorities (DNS-rebinding guard): {}", allowedHosts);

            return new NodeComponents(store, contractStore, boxStore, tokenStore, stateStore,
                verifier, engine, mempool, service, banList, registry, broadcaster, peerTokenPolicy,
                syncHttpClient, blockPrivatePeers, allowedHosts);
        } catch (Throwable t) {
            releaseQuietly(opened);
            throw t;
        }
    }

    /** Records {@code resource} as owned by the in-progress assembly, then returns it. */
    private static <T extends AutoCloseable> T opened(java.util.Deque<AutoCloseable> owned, T resource) {
        owned.push(resource);
        return resource;
    }

    /**
     * Unwinds a failed assembly, newest first, so a store opened before the failure does not keep
     * its RocksDB {@code LOCK} for the life of the process. Best-effort by design: the failure
     * being unwound is the one worth reporting, so a secondary close failure is logged, never
     * thrown.
     */
    private static void releaseQuietly(java.util.Deque<AutoCloseable> owned) {
        while (!owned.isEmpty()) {
            try {
                owned.pop().close();
            } catch (Exception e) {
                log.warn("failed to release a partially assembled component: {}", e.toString());
            }
        }
    }

    /**
     * Opens the socket, starts the threads and arms the schedules over an already-assembled graph.
     *
     * <p>Everything before this point is {@link #assemble}: this method is only the moment the
     * node becomes reachable and starts doing work on its own.
     */
    public synchronized void start() throws IOException {
        // Assigned before the runtime is built so that a failure below — a port already in use is
        // the common one — still leaves close() able to release the stores.
        components = assemble(config);
        runtime = startServing(components);

        log.info("Rhizome node started: network={} height={} apiPort={} mining={} seedPeers={}",
            config.params().networkName(), components.engine().height(), config.apiPort(),
            config.miner().isPresent(), config.peers().size());
        // The open-bind posture is only reachable through the RHIZOME_ALLOW_OPEN_API override
        // (assemble() refuses it otherwise) — surface it loudly (audit H-2).
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

    /**
     * The whole "become reachable" step: HTTP socket, worker pool, optional producer, network
     * loops. Every piece is a local until the {@link NodeRuntime} at the end, so a failure part of
     * the way through cannot leave the node in a half-started state that {@code close()} would
     * then have to guess at.
     */
    private NodeRuntime startServing(NodeComponents c) throws IOException {
        Eventloop eventloop = Eventloop.create();
        // Consensus-heavy request handlers (block/tx ingest, VM dry-run, lock-guarded explorer
        // reads) run on this bounded pool, NOT on the event-loop thread: a single valid-but-heavy
        // block or one max-gas dry-run would otherwise freeze every route the loop serves —
        // /peers, SSE, heartbeats — for its whole duration (audit: eventloop blocked by consensus
        // work). Bounded queue + AbortPolicy: a saturated pool sheds with 429 at the servlet
        // boundary instead of queueing unbounded latency. Daemon threads; drained in close()
        // before the stores close.
        int workerCount = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        var apiWorkers = new java.util.concurrent.ThreadPoolExecutor(workerCount, workerCount,
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
        // The one genuine cycle in the assembly, and the reason the SSE hub cannot move into
        // assemble(): engine → hub → service → engine, closed here because the hub needs the
        // event loop this method just created.
        SseLogHub sseHub = new SseLogHub(eventloop, 256);
        c.engine().setOnBlockApplied(height -> sseHub.publish(height, () -> c.service().logsAt(height)));
        // Per-client rate limit (fixed 1s window) with a bounded client table
        // (the table cap is the memory-leak fix; the per-window count is generous
        // so honest peers on a shared host are never throttled).
        RateLimiter limiter = new RateLimiter(1000, 1000, 65_536);
        // Opt-in hardening switches (both default off — see NodeApi for the full semantics):
        // RHIZOME_PROTECT_READS gates EVERY route (not just POSTs) behind RHIZOME_API_TOKEN,
        // for private nodes/explorers — except the static SPA/docs shell, which a plain browser
        // navigation cannot bear a token for; RHIZOME_TRUST_XFF keys rate limits and scan
        // ownership on the first X-Forwarded-For hop, for nodes only reachable through a
        // trusted proxy.
        boolean protectReads = config.protectReads();
        boolean trustXff = config.trustXff();
        if (protectReads && config.apiToken().isEmpty()) {
            log.warn("RHIZOME_PROTECT_READS=true without RHIZOME_API_TOKEN has no effect:"
                + " there is no token to gate reads behind.");
        }
        if (trustXff) {
            log.warn("RHIZOME_TRUST_XFF=true: rate limits and scan ownership key on the"
                + " X-Forwarded-For header. If this port is reachable WITHOUT passing through"
                + " the trusted proxy, clients can spoof it and evade per-IP limits.");
        }
        HttpServer httpServer = HttpServer.builder(eventloop,
                NodeApi.servlet(eventloop, c.service(), limiter, sseHub, c.allowedHosts(),
                    config.apiToken().orElse(null), apiWorkers, protectReads, trustXff))
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
        Thread eventloopThread = new Thread(eventloop, "rhizome-http");
        eventloopThread.setDaemon(true);
        eventloopThread.start();

        // From here the event loop is LIVE, and keepAlive(true) means it spins until told to
        // stop. A failure past this point — a port already in use is the everyday one — used to
        // be cleaned up by close(), which could see the half-set fields; now that the runtime is
        // returned whole, close() will see no runtime at all, so this method unwinds its own.
        BlockProducer producer = null;
        try {
            try {
                eventloop.submit(() -> httpServer.listen()).get();
            } catch (Exception e) {
                throw new IOException("Failed to start HTTP server on port " + config.apiPort(), e);
            }

            if (config.miner().isPresent()) {
                producer = new BlockProducer(c.engine(), c.mempool(), config.miner().get(),
                    System::currentTimeMillis, config.blockIntervalMs());
                producer.setOnProduced(c.broadcaster()::broadcastBlock);
                // Optional parameter vote this miner casts on each block (RHIZOME_VOTE):
                // ±1 storageFeeFactor, ±2 minValuePerByte, 0/absent = abstain.
                if (config.vote() != 0) {
                    producer.setVote(config.vote());
                }
                producer.start();
            }

            var discovery = new PeerDiscovery(c.registry(), config.selfUrl(), c.blockPrivatePeers(),
                c.peerTokenPolicy());
            ScheduledExecutorService syncScheduler = Executors.newScheduledThreadPool(2, r -> {
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
            long snapshotEvery = config.snapshotEveryBlocks();
            if (snapshotEvery > 0) {
                syncScheduler.scheduleWithFixedDelay(guarded(() -> {
                    if (c.engine().height() >= c.service().snapshotPivot() + snapshotEvery
                            && c.service().materializeSnapshot()) {
                        log.info("Materialized state snapshot at height {} ({} chunks)",
                            c.service().snapshotPivot(), c.service().materializedSnapshot().chunkCount());
                    }
                }, "snapshot materialisation"), config.syncPeriodMs(), config.syncPeriodMs(), TimeUnit.MILLISECONDS);
            }

            return new NodeRuntime(eventloop, eventloopThread, httpServer, apiWorkers, producer,
                syncScheduler, discovery);
        } catch (Throwable t) {
            if (producer != null) {
                producer.stop();
            }
            stopServing(eventloop, eventloopThread, httpServer, apiWorkers);
            throw t;
        }
    }

    /**
     * Unwinds a failed {@link #startServing}: the same drain-the-loop-then-the-workers order
     * {@code close()} uses, on a shorter join budget because nothing has served a request yet, so
     * there is no in-flight body read to wait out.
     */
    private static void stopServing(Eventloop eventloop, Thread eventloopThread,
                                    HttpServer httpServer, java.util.concurrent.ExecutorService apiWorkers) {
        try {
            eventloop.submit(() -> httpServer.close());
            eventloop.keepAlive(false);
            eventloop.execute(eventloop::breakEventloop);
            eventloopThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("failed to unwind the event loop after a failed start: {}", e.toString());
        } finally {
            apiWorkers.shutdownNow();
        }
    }

    /**
     * Parses {@code RHIZOME_VOTE} with a clear error and bounds it to the protocol's vote domain
     * (0 abstain, ±1 storageFeeFactor, ±2 minValuePerByte). An out-of-domain value would either
     * crash the producer thread with a raw {@link NumberFormatException} or mint blocks the
     * consensus gate rejects as {@code INVALID_VOTE} (audit: unvalidated config).
     */
    static int parseVote(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0; // abstain
        }
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
    static long parseSnapshotEvery(String env) {
        if (env == null || env.isBlank()) {
            return NodeConfig.DEFAULT_SNAPSHOT_EVERY;
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

    /**
     * Consecutive rounds without any sync progress (no EXTENDED/REORGED from any peer)
     * before a WARN is emitted, and the re-emission period for an ongoing stall. At the
     * default ~10 s sync period, 6 rounds ≈ 1 minute: an operator sees a stalled sync in
     * minutes, not in a 12-minute log post-mortem (testnet campaign S5).
     */
    private static final long PROGRESS_WARN_ROUNDS = 6;

    /** Rotates the per-round starting peer (single sync thread, so no synchronization needed). */
    private long syncRoundCursor;

    /** Height at the start of the previous round: a height advance between ROUND STARTS resets the
     *  stall counter (see syncRound). Initialised to -1 so the first round counts as progressing. */
    private long lastObservedHeight = -1;

    /** Consecutive rounds with neither sync progress nor a height advance (single sync thread). */
    private long roundsWithoutProgress;

    /** Whether the previous round found every known peer banned (single sync thread). */
    private boolean eclipsedReported;

    /** One sync round across all known peers; peer failures are isolated. */
    public void syncRound() {
        // Bound once: this runs on the single sync thread, which the scheduler only arms after
        // start() has published the graph, so the reference cannot change under the round.
        final NodeComponents c = components;
        final ChainEngine engine = c.engine();
        final PeerRegistry registry = c.registry();
        var synchronizer = new HeaderSynchronizer(engine);
        java.util.List<String> peers = registry.snapshot();
        int n = peers.size();
        if (n == 0) {
            // NOT a quiet round to skip: an empty registry is the DEEPEST eclipse there is, and it
            // is also the SHAPE the eclipse actually takes in production — PeerRegistry.penalize
            // evicts a peer the moment its ban lands, so "every peer banned" almost never means "a
            // registry full of banned entries" (the state peersSkippedBanned counts) and almost
            // always means "a registry emptied by evictions". Returning here without publishing
            // froze /stats on the previous round's counters and emitted no WARN in exactly the
            // state the metric exists to surface (review follow-up to the S5 fix).
            publishRoundOutcome(engine.height(), 0, 0, 0, false);
            return;
        }
        // The round's progress baseline: on a healthy gossip-fed network, sync rounds
        // legitimately do nothing (peers PUSH blocks, so heights advance without any
        // EXTENDED/REORGED). "No progress" only means something when the HEIGHT is also
        // frozen — the exact S5 shape (a wedged node keeps 9 healthy peers, extends none,
        // and no block arrives from anywhere). The height is compared BETWEEN round starts
        // (not within one round, which lasts milliseconds): blocks land between rounds, so
        // a node whose chain advances at all — by any path — never counts a stalled round.
        long heightAtRoundStart = engine.height();
        int peersTried = 0;
        int peersSkippedBanned = 0;
        boolean progressed = false;
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
            // Seeds are trusted anchors: they can never be penalized directly (PeerRegistry.penalize
            // exempts them), so a seed seen as banned can only be a COLLATERAL ban of its address —
            // which must not blind the node to its operator-configured anchor (testnet campaign S5).
            if (!registry.isSeed(peerUrl) && registry.isBanned(peerUrl)) {
                peersSkippedBanned++;
                continue;
            }
            peersTried++;
            try {
                ChainSynchronizer.Result result = synchronizer.syncFrom(
                    new HttpPeerSource(peerUrl, c.blockPrivatePeers(), c.syncHttpClient(),
                        c.peerTokenPolicy()));
                // Any Result at all means the peer answered well-formed protocol data, so it is
                // a real Rhizome node and from here on it can earn ban score — including for the
                // PEER_INVALID case just below (a node that speaks the protocol and lies IS
                // misbehaving). Only the malformed-data path can still see an unconfirmed peer;
                // see penalize (audit B-3).
                registry.markConfirmed(peerUrl);
                switch (result) {
                    case EXTENDED, REORGED -> {
                        progressed = true;
                        log.info("Synced from {}: {} -> height {}", peerUrl, result, engine.height());
                    }
                    case PEER_INVALID -> penalize(peerUrl, PENALTY_INVALID, "served an invalid chain");
                    case REORG_TOO_DEEP ->
                        // Deliberately NO ban score: a branch past the reorg horizon is not
                        // misbehaviour (the peer cannot help how deep its fork is), and scoring
                        // it locked forked camps into a mutual 1 h ban, renewed hourly — the
                        // permanent split the replay measured (see the constant comment).
                        log.debug("Peer {} is past the reorg horizon (finality); nothing to adopt",
                            peerUrl);
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
        publishRoundOutcome(heightAtRoundStart, n, peersTried, peersSkippedBanned, progressed);
    }

    /**
     * The round's verdict, observable before the next one starts: a healthy-but-idle round is
     * indistinguishable from a wedged one by height alone (S5: 12 min, 9 healthy peers, zero log
     * lines). Publishes the counters so /stats surfaces the difference in seconds, and says it
     * out loud when a round is doing nothing by construction. Called on EVERY round, the
     * no-peer-at-all one included — that path used to return early and publish nothing.
     */
    private void publishRoundOutcome(long heightAtRoundStart, int peersKnown, int peersTried,
                                     int peersSkippedBanned, boolean progressed) {
        if (progressed || heightAtRoundStart != lastObservedHeight) {
            roundsWithoutProgress = 0;
        } else {
            roundsWithoutProgress++;
        }
        lastObservedHeight = heightAtRoundStart;
        // Eclipsed = this round had no usable sync source at all: either the registry is empty
        // (bans evict, so this is the common shape) or every peer in it was skipped as banned.
        boolean eclipsed = peersTried == 0 && (peersKnown == 0 || peersSkippedBanned == peersKnown);
        components.service().recordSyncRound(peersKnown, peersTried, peersSkippedBanned,
            roundsWithoutProgress, eclipsed);
        if (eclipsed) {
            if (!eclipsedReported) {
                log.warn("sync eclipsed: no usable sync source this round ({} known peer(s), {} skipped "
                    + "as banned), so nothing can catch up until a ban expires or a peer is discovered",
                    peersKnown, peersSkippedBanned);
                eclipsedReported = true;
            } else if (roundsWithoutProgress > 0 && roundsWithoutProgress % PROGRESS_WARN_ROUNDS == 0) {
                log.warn("sync still eclipsed after {} stalled round(s): {} known peer(s), {} skipped "
                    + "as banned; nothing can catch up", roundsWithoutProgress, peersKnown, peersSkippedBanned);
            }
        } else {
            eclipsedReported = false;
        }
        if (!eclipsed && roundsWithoutProgress > 0
                && roundsWithoutProgress % PROGRESS_WARN_ROUNDS == 0) {
            log.warn("no sync progress and no height advance for {} rounds (~{} s): {} peer(s) tried "
                + "this round, {} peer(s) skipped as banned",
                roundsWithoutProgress, roundsWithoutProgress * config.syncPeriodMs() / 1000,
                peersTried, peersSkippedBanned);
        }
    }

    /**
     * Applies ban score for misbehaviour — but only to a peer that has proven it speaks the
     * protocol. {@code /add_peer} is unauthenticated on an open node, so an attacker could point
     * us at any public host: a plain web server answering 200 to everything raises
     * PeerProtocolException, which used to be worth an immediate ban of the VICTIM's resolved IP
     * (100 points = the threshold), renewable for as long as the attacker kept re-adding it — a
     * remote blocklisting primitive that would also refuse the victim's honest node later
     * (audit B-3). An unconfirmed host is treated as what it is, a wrong address: dropped from
     * the registry, which also arms the 5-minute host re-admission cooldown.
     */
    private void penalize(String peerUrl, int points, String reason) {
        PeerRegistry registry = components.registry();
        if (!registry.isConfirmed(peerUrl)) {
            registry.remove(peerUrl);
            log.debug("Dropped unconfirmed peer {} ({}) — not a protocol-speaking node, not banned",
                peerUrl, reason);
            return;
        }
        if (registry.penalize(peerUrl, points)) {
            log.warn("Banned peer {} ({})", peerUrl, reason);
        } else {
            log.debug("Penalized peer {} +{} ({})", peerUrl, points, reason);
        }
    }

    /** Runs one peer-discovery round now (otherwise it runs on the network schedule). */
    public void discoverRound() {
        runtime.discovery().round();
    }

    public java.util.List<String> knownPeers() {
        return components.registry().snapshot();
    }

    public PeerBanList banList() {
        return components.banList();
    }

    public NodeService service() {
        return components.service();
    }

    public ChainEngine engine() {
        return components.engine();
    }

    public int apiPort() {
        return config.apiPort();
    }

    /**
     * The legitimate {@code Host} authorities for the DNS-rebinding defense (audit S-2): the node's
     * advertised host and the loopback names, each with and without the API port. A browser POST whose
     * Host is not in this set is refused — a rebound page carries the attacker's hostname, not one of
     * these. Lower-cased for case-insensitive matching.
     *
     * <p>{@code RHIZOME_ALLOWED_HOSTS} (comma-separated) appends extra authorities for deployments
     * where clients legitimately arrive through a host this node cannot enumerate — a reverse
     * proxy's public name, a Docker/NAT address. The literal value {@code off} disables the
     * allowlist entirely (the Origin/marker CSRF guard remains): a documented escape hatch, NOT
     * the default — the computed default set is the anti-rebinding control.
     */
    private static java.util.Set<String> allowedHosts(NodeConfig config) {
        if (!config.hostAllowlistEnabled()) {
            log.warn("RHIZOME_ALLOWED_HOSTS=off: the DNS-rebinding Host allowlist is disabled — "
                + "only the Origin/marker CSRF guard remains. Prefer listing the deployment's "
                + "public hostnames instead.");
            return java.util.Set.of();
        }
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
        // Operator-supplied extra authorities (reverse proxy, Docker/NAT): taken verbatim,
        // lower-cased — the operator knows the public name:port clients actually send.
        hosts.addAll(config.extraAllowedHosts()); // already trimmed and lower-cased by fromEnv
        return hosts;
    }

    @Override
    public synchronized void close() {
        // The two holders answer, once each, the question the old twenty-three null checks
        // answered independently: did this node assemble, and did it start? Every guard below is
        // one of those two — the sequence, its budgets and its skip conditions are unchanged.
        final NodeRuntime rt = runtime;
        final NodeComponents c = components;
        // Stop NEW work first: close the HTTP server and drain the eventloop before touching
        // anything else, so no request handler can be inside a native store read/write while
        // shutdown proceeds (a late /sync read racing the column-family close aborts the JVM).
        // The join budget covers the worst in-flight request: peer body reads are deadline-bound
        // (BodyReadDeadline, 30 s), so a drained eventloop always dies within this window.
        if (rt != null) {
            rt.eventloop().submit(() -> rt.httpServer().close());
            rt.eventloop().keepAlive(false);
            rt.eventloop().execute(rt.eventloop()::breakEventloop);
            try {
                rt.eventloopThread().join(35_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (rt != null && rt.producer() != null) {
            rt.producer().stop();
        }
        // Drain in-flight API workers (offloaded block/tx ingest, dry-runs, explorer reads)
        // before the stores close: a worker mid-validation may hold the engine lock or sit
        // inside a native RocksDB call, so an undrained pool makes the store close below unsafe
        // in exactly the two ways the syncScheduler branch describes — skip it the same way.
        boolean workersStuck = false;
        if (rt != null) {
            rt.apiWorkers().shutdownNow();
            try {
                if (!rt.apiWorkers().awaitTermination(30, TimeUnit.SECONDS)) {
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
        if (rt != null) {
            rt.syncScheduler().shutdownNow();
            try {
                // The await budget must exceed the worst in-flight syncRound: peer body reads
                // are deadline-bound at 30 s (BodyReadDeadline), so a stuck sync always unwinds
                // within ~45 s.
                if (!rt.syncScheduler().awaitTermination(45, TimeUnit.SECONDS)) {
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
            if (rt != null) {
                rt.discovery().close(); // stop the PEX fan-out pool (daemon threads, best-effort)
            }
        } finally {
            // The rest of the sequence — gossip fan-out, verifier pool, the file-backed snapshot
            // spool, then the stores under the engine lock — belongs to the graph, so it lives
            // with the graph. The spool release is independent of the store-close guard: it
            // touches only a temp file, and the eventloop (its only reader) is drained above.
            //
            // Closing the stores under the engine lock matters because a straggler (late gossip
            // task, timed-out eventloop job) could still be queued: holding the lock guarantees
            // no thread is inside a native write while the handles close, and a late writer
            // afterwards gets a clean "database is closed" Java exception instead of corrupting
            // the native heap. SKIPPED when either pool never drained — see the two branches
            // above for why that is the safer answer.
            if (c != null) {
                c.release(!syncStuck && !workersStuck);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        NodeConfig config = NodeConfig.fromEnv();
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

    /**
     * Validates {@code RHIZOME_ADVERTISE}: it must be an http(s) URL with a host. A malformed
     * value used to be accepted verbatim and then degrade three things at once, silently
     * (audit I-6): {@link PeerRegistry}'s self URL (canonicalized to something no peer URL can
     * equal, so the self-pairing refusal stops firing and the node syncs from itself), the
     * address handed to peers by PEX, and the {@code Host} allowlist, which drops it and falls
     * back to the loopback names — locking browsers out of the dashboard over the real hostname.
     */
    static String parseAdvertisedUrl(String raw) {
        String url = raw.trim();
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (http && uri.getHost() != null && !uri.getHost().isEmpty()) {
                return url;
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("RHIZOME_ADVERTISE must be an http(s) URL with a host "
                + "(e.g. https://node.example:3000), was: " + raw, e);
        }
        throw new IllegalArgumentException("RHIZOME_ADVERTISE must be an http(s) URL with a host "
            + "(e.g. https://node.example:3000), was: " + raw);
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

    /**
     * Resolves {@code RHIZOME_NETWORK} (absent/blank → mainnet). An unrecognised value is a hard
     * failure, not a fall-through to mainnet: {@code RHIZOME_NETWORK=testnett} used to start the
     * node on MAINNET without a word — wrong chain, wasted mining, mainnet peers dialled — while
     * every other config variable already fails fast on a typo (audit B-4). Fail-unsafe defaults
     * are the one kind of default this node does not get to have.
     */
    static rhizome.core.blockchain.NetworkParameters parseNetwork(String raw) {
        String name = raw == null || raw.isBlank() ? "mainnet" : raw.trim();
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "mainnet" -> rhizome.core.blockchain.NetworkParameters.cleanMainnet();
            case "testnet" -> rhizome.core.blockchain.NetworkParameters.testnet();
            case "devnet" -> rhizome.core.blockchain.NetworkParameters.devnet();
            default -> throw new IllegalArgumentException(
                "RHIZOME_NETWORK must be one of mainnet, testnet, devnet — was: " + raw);
        };
    }
}
