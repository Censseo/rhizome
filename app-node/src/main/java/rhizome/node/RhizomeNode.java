package rhizome.node;

import rhizome.net.HttpPeerSource;
import rhizome.net.PeerBroadcaster;
import rhizome.net.PeerDiscovery;
import rhizome.net.PeerExchange;
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

            // One shared HTTP exchange for sync rounds, PEX and gossip, so a fresh client (and its
            // selector thread + connection pool) is not built per peer per round or per component
            // (audit net #1). Built here because the snap bootstrap just below is already a peer fetch.
            PeerExchange exchange = new PeerExchange();

            // RHIZOME_SYNC=snap on an empty data dir: adopt a peer's verified state snapshot at
            // a buried pivot instead of replaying history; falls back to full sync when no peer
            // offers a usable snapshot. The engine boot below then starts at the pivot.
            if (config.snapSync() && store.chainStore().height() == 0) {
                for (String peerUrl : config.peers()) {
                    try {
                        if (SnapshotBootstrap.bootstrap(config.params(), snapshot,
                                new RocksBootstrapTarget(store, boxStore, tokenStore, stateStore),
                                contractStore, new HttpPeerSource(peerUrl, blockPrivatePeers,
                                    exchange, peerTokenPolicy),
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
            var engine = ChainEngine.boot(config.params(), store, snapshot)
                .clock(System::currentTimeMillis)
                .verifier(verifier)
                .contracts(contractProcessor)
                .boxes(boxProcessor)
                .tokens(tokenProcessor)
                .stateAccumulator(stateAccumulator)
                .build();
            var mempool = new MemPool(config.params(), verifier, engine, config.mempoolSize());
            // Every node keeps a live peer set (seeded from config), serves /peers and
            // accepts announcements, so the network can self-organise from a few seeds.
            var banList = new PeerBanList(SyncDriver.BAN_THRESHOLD, 60 * 60 * 1000L, 4096);
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
            var broadcaster = new PeerBroadcaster(registry::snapshot, blockPrivatePeers,
                peerTokenPolicy, exchange);
            opened.push(broadcaster::close);

            // Snapshot spools live with the stores, not the OS temp dir (often a tmpfs → the whole
            // state would silently be back in RAM); opening the service also sweeps the spools a
            // SIGKILLed predecessor left behind.
            final SnapshotService snapshots;
            try {
                snapshots = opened(opened, SnapshotService.open(engine,
                    new rhizome.core.state.snapshot.DomainStateAdapter(
                        store.ledger(), store.nonceStore(), boxStore, tokenStore,
                        new rhizome.vm.ContractStateAdapter(contractStore)),
                    Path.of(config.dataDir(), "snapshots")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(
                    "cannot create snapshot spool dir under " + config.dataDir(), e);
            }

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
                    .snapshots(snapshots)
                    .build(),
                new NodeListeners(broadcaster::broadcastBlock, broadcaster::broadcastTransaction));
            opened.push(service::close);

            java.util.Set<String> allowedHosts = allowedHosts(config);
            // Surface the effective DNS-rebinding allowlist at startup: it now also covers the
            // host's LAN interface addresses (audit F9), which are otherwise invisible to the operator.
            log.info("API allowed Host authorities (DNS-rebinding guard): {}", allowedHosts);

            return new NodeComponents(store, contractStore, boxStore, tokenStore, stateStore,
                verifier, engine, mempool, service, banList, registry, broadcaster, peerTokenPolicy,
                exchange, blockPrivatePeers, allowedHosts);
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
                c.peerTokenPolicy(), c.exchange());
            // The sync round lives in its own driver once the graph exists; the scheduler only
            // arms below, so the driver is visible to any direct syncRound() call (tests) from
            // here on.
            sync = new SyncDriver(c.engine(), c.registry(), c.service(), c.blockPrivatePeers(),
                c.exchange(), c.peerTokenPolicy(), config.syncPeriodMs());
            ScheduledExecutorService syncScheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "rhizome-net");
                t.setDaemon(true);
                return t;
            });
            // Every scheduled task is wrapped in guarded(): a task whose run() lets ANY Throwable
            // escape is silently unscheduled by ScheduledThreadPoolExecutor — one stray Error would
            // stop that loop forever, with no log line (audit: scheduler task suppression).
            syncScheduler.scheduleWithFixedDelay(guarded(sync::syncRound, "sync round"),
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

    /**
     * The sync driver (round loop, stall counters, ban scoring) — built when the graph is
     * assembled in {@link #startServing}; null before then, like the graph itself.
     */
    private SyncDriver sync;

    /** Runs one sync round now (otherwise it runs on the network schedule). */
    public void syncRound() {
        sync.syncRound();
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

}
