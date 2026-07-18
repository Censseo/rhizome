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
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.SnapshotLoader;
import rhizome.core.mempool.MemPool;
import rhizome.persistence.rocksdb.RocksDbNodeStore;

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
    private ChainEngine engine;
    private MemPool mempool;
    private NodeService service;
    private SignatureVerifier verifier;

    private Eventloop eventloop;
    private Thread eventloopThread;
    private HttpServer httpServer;

    private BlockProducer producer;
    private ScheduledExecutorService syncScheduler;

    public RhizomeNode(NodeConfig config) {
        this.config = config;
    }

    public synchronized void start() throws IOException {
        LedgerSnapshot snapshot = config.snapshotPath().isPresent()
            ? SnapshotLoader.fromFile(Path.of(config.snapshotPath().get()))
            : SnapshotLoader.empty(config.params().chainId());

        store = new RocksDbNodeStore(config.dataDir());
        verifier = new SignatureVerifier();
        engine = ChainEngine.init(config.params(), store.ledger(), store.chainStore(),
            snapshot, null, System::currentTimeMillis, verifier);
        mempool = new MemPool(config.params(), verifier, engine, config.mempoolSize());
        service = new NodeService(engine, mempool);

        startHttp();
        startProducerIfConfigured();
        startSyncIfConfigured();

        log.info("Rhizome node started: network={} height={} apiPort={} mining={} peers={}",
            config.params().networkName(), engine.height(), config.apiPort(),
            config.miner().isPresent(), config.peers().size());
    }

    private void startHttp() throws IOException {
        eventloop = Eventloop.create();
        httpServer = HttpServer.builder(eventloop, NodeApi.servlet(eventloop, service))
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

    private void startProducerIfConfigured() {
        config.miner().ifPresent(miner -> {
            producer = new BlockProducer(engine, mempool, miner, System::currentTimeMillis,
                config.blockIntervalMs());
            producer.start();
        });
    }

    private void startSyncIfConfigured() {
        if (config.peers().isEmpty()) {
            return;
        }
        syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rhizome-sync");
            t.setDaemon(true);
            return t;
        });
        syncScheduler.scheduleWithFixedDelay(this::syncRound,
            config.syncPeriodMs(), config.syncPeriodMs(), TimeUnit.MILLISECONDS);
    }

    /** One sync round across all configured peers; peer failures are isolated. */
    public void syncRound() {
        var synchronizer = new ChainSynchronizer(engine);
        for (String peerUrl : config.peers()) {
            try {
                ChainSynchronizer.Result result = synchronizer.syncFrom(new HttpPeerSource(peerUrl));
                if (result == ChainSynchronizer.Result.EXTENDED || result == ChainSynchronizer.Result.REORGED) {
                    log.info("Synced from {}: {} -> height {}", peerUrl, result, engine.height());
                }
            } catch (HttpPeerSource.PeerUnavailableException e) {
                log.debug("Peer {} unavailable: {}", peerUrl, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Sync from {} failed: {}", peerUrl, e.toString());
            }
        }
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
        if (syncScheduler != null) {
            syncScheduler.shutdownNow();
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
