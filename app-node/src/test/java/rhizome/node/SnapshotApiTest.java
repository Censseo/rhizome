package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.generateKeyPair;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.eventloop.Eventloop;
import io.activej.http.HttpServer;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.InMemoryNonceStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.PeerSource;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.StateAccumulator;
import rhizome.core.state.snapshot.DomainStateAdapter;
import rhizome.core.state.snapshot.SnapshotChunk;
import rhizome.core.state.snapshot.StateSnapshotImporter;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

/**
 * The snapshot HTTP surface: a node materialises its state under the engine lock, advertises
 * it on /state/snapshot/info, serves chunks by index — and a client that fetches them over
 * real HTTP rebuilds exactly the state root committed in the pivot header.
 */
class SnapshotApiTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4).build();

    private Eventloop eventloop;
    private Thread eventloopThread;
    private HttpServer server;
    private int port;
    private ChainEngine engine;
    private NodeService node;
    private InMemoryLedger ledger;
    private PublicAddress sender;

    @BeforeEach
    void setUp() throws Exception {
        ledger = new InMemoryLedger();
        var nonces = new InMemoryNonceStore();
        var boxStore = new InMemoryBoxStore();
        var tokenStore = new InMemoryTokenStore();
        var accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(),
            PARAMS.maxReorgDepth());
        AtomicLong clock = new AtomicLong(1_000_000L);

        var pair = generateKeyPair();
        PublicKey key = PublicKey.of(pair.getPublic());
        PrivateKey priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        LedgerSnapshot genesis = new LedgerSnapshot("t", 0, PARAMS.chainId());
        genesis.put(sender, new TransactionAmount(5_000_000L));

        engine = ChainEngine.init(PARAMS, ledger, new InMemoryChainStore(), nonces, genesis, null,
            clock::get, null, null, new DefaultBoxProcessor(boxStore, PARAMS),
            new DefaultTokenProcessor(tokenStore, PARAMS), accumulator);

        // A few blocks with a transfer each, so the state is non-trivial.
        PublicAddress miner = PublicAddress.random();
        for (int i = 0; i < 3; i++) {
            long h = engine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder().id((int) h).timestamp(clock.addAndGet(90_000))
                .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
            b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(h))));
            Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1_000),
                key, new TransactionAmount(0), clock.get(), PARAMS.chainId(), i);
            t.sign(priv);
            b.addTransaction(t);
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            engine.stampStateRoot(b);
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        }

        node = new NodeService(engine, new MemPool(PARAMS, new SignatureVerifier(), engine, 1000));
        node.setSnapshotSource(new DomainStateAdapter(ledger, nonces, boxStore, tokenStore, null, null));

        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        eventloop = Eventloop.create();
        server = HttpServer.builder(eventloop, NodeApi.servlet(eventloop, node)).withListenPort(port).build();
        eventloop.keepAlive(true);
        eventloopThread = new Thread(eventloop, "test-http");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
        eventloop.submit(() -> server.listen()).get();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        eventloop.submit(() -> server.close());
        eventloop.keepAlive(false);
        eventloop.execute(eventloop::breakEventloop);
        eventloopThread.join(2000);
    }

    @Test
    void snapshotIsAdvertisedServedAndVerifiableOverHttp() {
        var peer = new HttpPeerSource("http://localhost:" + port);
        assertNull(peer.snapshotInfo(), "no snapshot before materialisation");

        assertTrue(node.materializeSnapshot());
        PeerSource.SnapshotInfo info = peer.snapshotInfo();
        assertNotNull(info);
        assertEquals(engine.height(), info.pivotHeight());
        assertTrue(info.chunkCount() > 0);

        // Fetch every chunk over HTTP and rebuild: the root must equal the one the pivot
        // header commits to — the same value /state/snapshot/info advertised.
        List<SnapshotChunk> chunks = new ArrayList<>();
        for (int i = 0; i < info.chunkCount(); i++) {
            chunks.add(SnapshotChunk.decode(peer.snapshotChunk(i)));
        }
        byte[] rebuilt = StateSnapshotImporter.verify(chunks, new InMemorySmtNodeStore(), info.stateRoot());
        assertArrayEquals(engine.stateRoot(), rebuilt);
    }

    @Test
    void materialisationIsAConsistentPointInTimeCapture() {
        assertTrue(node.materializeSnapshot());
        long pivot = node.snapshotPivot();
        var snapBefore = node.materializedSnapshot();

        // The stored snapshot does not change until re-materialised.
        assertEquals(pivot, node.snapshotPivot());
        assertTrue(node.materializeSnapshot());
        assertEquals(pivot, node.snapshotPivot(), "same height, re-captured at same pivot");
        assertEquals(snapBefore.chunks().size(), node.materializedSnapshot().chunks().size());
    }
}
