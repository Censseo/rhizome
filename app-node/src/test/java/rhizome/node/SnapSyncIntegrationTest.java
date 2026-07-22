package rhizome.node;

import rhizome.net.HttpPeerSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.eventloop.Eventloop;
import io.activej.http.HttpServer;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainSynchronizer;
import rhizome.core.blockchain.HeaderSynchronizer;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.InMemoryNonceStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.SparseMerkleTree;
import rhizome.core.state.StateAccumulator;
import rhizome.core.state.StateKeys;
import rhizome.core.state.snapshot.DomainStateAdapter;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.persistence.rocksdb.RocksDbBoxStore;
import rhizome.persistence.rocksdb.RocksDbContractStore;
import rhizome.persistence.rocksdb.RocksDbNodeStore;
import rhizome.persistence.rocksdb.RocksDbStateStore;
import rhizome.persistence.rocksdb.RocksDbTokenStore;

/**
 * Snap-sync end to end over real HTTP: a mining node materialises a snapshot; a fresh node
 * bootstraps from it — locally-built genesis, full header validation, root-verified state
 * import — then body-syncs only the suffix above the pivot and converges to the miner's tip
 * with the historical bodies never downloaded.
 */
class SnapSyncIntegrationTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4)
        .maxReorgDepth(3).build();
    private static final long NOW = 100_000_000_000L;

    @TempDir
    Path tempDir;

    private Eventloop eventloop;
    private Thread eventloopThread;
    private HttpServer server;
    private int port;

    private ChainEngine minerEngine;
    private NodeService minerNode;
    private InMemoryLedger minerLedger;
    private AtomicLong clock;
    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private LedgerSnapshot genesisSnapshot;
    private long pivot;

    @BeforeEach
    void setUp() throws Exception {
        clock = new AtomicLong(1_000_000L);
        minerLedger = new InMemoryLedger();
        var nonces = new InMemoryNonceStore();
        var boxStore = new InMemoryBoxStore();
        var tokenStore = new InMemoryTokenStore();
        var accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(),
            PARAMS.maxReorgDepth());

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        genesisSnapshot = new LedgerSnapshot("t", 0, PARAMS.chainId());
        genesisSnapshot.put(sender, new TransactionAmount(50_000_000L));

        minerEngine = ChainEngine.init(PARAMS, minerLedger, new InMemoryChainStore(), nonces,
            genesisSnapshot, null, clock::get, null, null,
            new DefaultBoxProcessor(boxStore, PARAMS), new DefaultTokenProcessor(tokenStore, PARAMS), accumulator);

        minerNode = new NodeService(minerEngine, new MemPool(PARAMS, new rhizome.core.blockchain.SignatureVerifier(), minerEngine, 1000));
        minerNode.setSnapshotSource(new DomainStateAdapter(minerLedger, nonces, boxStore, tokenStore, null, null));

        // 12 blocks with one transfer each, snapshot, then keep mining past the burial window.
        mine(12);
        assertTrue(minerNode.materializeSnapshot());
        pivot = minerNode.snapshotPivot(); // 13
        mine(4); // peer tip 17: pivot buried by 4 > maxReorgDepth 3

        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        eventloop = Eventloop.create();
        server = HttpServer.builder(eventloop, NodeApi.servlet(eventloop, minerNode)).withListenPort(port).build();
        eventloop.keepAlive(true);
        eventloopThread = new Thread(eventloop, "test-http");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
        eventloop.submit(() -> server.listen()).get();
    }

    private void mine(int count) {
        for (int i = 0; i < count; i++) {
            long h = minerEngine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(clock.addAndGet(90_000))
                .difficulty(minerEngine.difficulty()).lastBlockHash(minerEngine.tipHash()).build();
            b.addTransaction(Transaction.of(PublicAddress.random(),
                new TransactionAmount(PARAMS.miningReward(h))));
            Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1_000),
                key, new TransactionAmount(0), clock.get(), PARAMS.chainId(), minerEngine.nextNonce(sender));
            t.sign(priv);
            b.addTransaction(t);
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            minerEngine.stampStateRoot(b);
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, minerEngine.addBlock(b));
        }
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        eventloop.submit(() -> server.close());
        eventloop.keepAlive(false);
        eventloop.execute(eventloop::breakEventloop);
        eventloopThread.join(2000);
    }

    @Test
    void freshNodeBootstrapsFromSnapshotAndConvergesWithoutHistoricalBodies() throws Exception {
        var peer = new HttpPeerSource("http://localhost:" + port);

        try (var store = new RocksDbNodeStore(tempDir.resolve("db").toString());
             var boxStore = new RocksDbBoxStore(tempDir.resolve("boxes").toString());
             var tokenStore = new RocksDbTokenStore(tempDir.resolve("tokens").toString());
             var contractStore = new RocksDbContractStore(tempDir.resolve("contracts").toString());
             var stateStore = new RocksDbStateStore(tempDir.resolve("state").toString())) {

            // --- Bootstrap: headers validated from locally-built genesis, state root-verified ---
            assertTrue(SnapshotBootstrap.bootstrap(PARAMS, genesisSnapshot, store, boxStore, tokenStore,
                contractStore, stateStore, peer, NOW));

            // --- Normal engine boot on the seeded stores: starts at the pivot, header-only below ---
            var accumulator = new StateAccumulator(stateStore, stateStore, PARAMS.maxReorgDepth());
            ChainEngine local = ChainEngine.init(PARAMS, store.ledger(), store.chainStore(),
                store.nonceStore(), genesisSnapshot, null, () -> NOW, null, null,
                new DefaultBoxProcessor(boxStore, PARAMS), new DefaultTokenProcessor(tokenStore, PARAMS),
                accumulator);

            assertEquals(pivot, local.height());
            assertArrayEquals(minerNode.materializedSnapshot().stateRoot(), local.stateRoot());
            assertEquals(minerLedgerBalanceAtPivotOf(sender), local.confirmedBalance(sender));
            assertEquals(pivot - 1, local.nextNonce(sender)); // one transfer per block, nonces 0..pivot-2
            assertFalse(store.chainStore().hasBody(5), "historical bodies were never downloaded");
            assertEquals(pivot + 1, store.chainStore().prunedBelow());

            // --- Body sync of the suffix only: converge to the miner's tip ---
            assertEquals(ChainSynchronizer.Result.EXTENDED, new HeaderSynchronizer(local).syncFrom(peer));
            assertEquals(minerEngine.height(), local.height());
            assertTrue(local.tipHash().equals(minerEngine.tipHash()));
            assertArrayEquals(minerEngine.stateRoot(), local.stateRoot());
            assertEquals(minerEngine.nextNonce(sender), local.nextNonce(sender));
            assertEquals(minerEngine.confirmedBalance(sender), local.confirmedBalance(sender));
            assertFalse(store.chainStore().hasBody(5), "still no historical body after convergence");

            // The snap-synced node is a first-class citizen: it proves state entries.
            var proof = local.stateProof(StateKeys.LEDGER, sender.toBytes());
            assertNotNull(proof);
            assertTrue(SparseMerkleTree.verify(local.stateRoot(),
                StateKeys.key(StateKeys.LEDGER, sender.toBytes()), proof.valueHash(), proof));
        }
    }

    @Test
    void bootstrapRefusesAnUnburiedPivot() throws Exception {
        // Fresh snapshot at the very tip: burial 0 < maxReorgDepth → refused, nothing seeded.
        assertTrue(minerNode.materializeSnapshot());
        var peer = new HttpPeerSource("http://localhost:" + port);
        try (var store = new RocksDbNodeStore(tempDir.resolve("db2").toString());
             var boxStore = new RocksDbBoxStore(tempDir.resolve("boxes2").toString());
             var tokenStore = new RocksDbTokenStore(tempDir.resolve("tokens2").toString());
             var contractStore = new RocksDbContractStore(tempDir.resolve("contracts2").toString());
             var stateStore = new RocksDbStateStore(tempDir.resolve("state2").toString())) {
            assertFalse(SnapshotBootstrap.bootstrap(PARAMS, genesisSnapshot, store, boxStore, tokenStore,
                contractStore, stateStore, peer, NOW));
            assertEquals(0, store.chainStore().height());
        }
    }

    /** The miner's balance for {@code a} as of the pivot snapshot (recomputed from current state). */
    private long minerLedgerBalanceAtPivotOf(PublicAddress a) {
        // The miner kept extending after the pivot; recompute what the snapshot carried:
        // current balance plus the transfers and fees spent after the pivot.
        long current = minerLedger.getWalletValue(a).amount();
        long spentAfterPivot = (minerEngine.height() - pivot) * 1_000L; // one 1000-transfer per block, no fees
        return current + spentAfterPivot;
    }
}
