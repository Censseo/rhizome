package rhizome.node;

import rhizome.net.HttpPeerSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

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
    private DomainStateAdapter snapshotSource;
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

        var pair = generateKeyPairTyped();
        PublicKey key = pair.publicKey();
        PrivateKey priv = pair.privateKey();
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

        snapshotSource = new DomainStateAdapter(ledger, nonces, boxStore, tokenStore, null, null);
        node = new NodeService(engine, new MemPool(PARAMS, new SignatureVerifier(), engine, 1000),
            NodeSources.builder()
                .snapshots(SnapshotService.inTempDir(engine, snapshotSource))
                .build());

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
    void snapshotChunkCostIsProportionalToServedBytes() {
        // Audit F3: a flat per-request cost made the route a bandwidth amplifier (~200 MiB/s
        // per IP at the default 1000 units/s). The cost is now ceil(bytes / 64 KiB), floored —
        // so the per-IP token budget maps to a bounded egress rate.
        io.activej.http.HttpRequest chunk0 =
            io.activej.http.HttpRequest.get("http://x/state/snapshot/chunk?index=0").build();
        // No snapshot yet: the flat fallback (75) — the handler answers 404 without serving bytes.
        assertEquals(75, NodeApi.requestCost(node, chunk0));

        assertTrue(node.materializeSnapshot());
        var snap = node.materializedSnapshot();
        long expected0 = Math.max(2, (snap.chunkLength(0) + 64L * 1024 - 1) / (64L * 1024));
        assertEquals((int) expected0, NodeApi.requestCost(node, chunk0),
            "cost must track the chunk's actual size at one unit per 64 KiB (min 2)");
        // An out-of-range index serves no bytes (404): back to the flat fallback.
        assertEquals(75, NodeApi.requestCost(node,
            io.activej.http.HttpRequest.get("http://x/state/snapshot/chunk?index=9999").build()));
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
        assertEquals(snapBefore.chunkCount(), node.materializedSnapshot().chunkCount());
    }

    // The spool directory and its stale-spool sweep moved to SnapshotServiceTest with the
    // mechanism itself: they are about where the export is written, not about serving it.

    @Test
    void fileBackedSnapshotServesTheSameBytesAndReplacesItsSpool() {
        // The spool-backed materialisation must serve exactly the bytes the in-memory export
        // produces: re-run the same exporter over the same source (no blocks are being applied,
        // so the view is unchanged) and compare chunk for chunk.
        assertTrue(node.materializeSnapshot());
        var snap = node.materializedSnapshot();
        assertTrue(java.nio.file.Files.exists(snap.file()), "chunks are spooled to a file");
        var expected = rhizome.core.state.snapshot.StateSnapshotExporter.export(
            snapshotSource, SnapshotService.SNAPSHOT_CHUNK_ENTRIES);
        assertEquals(expected.size(), snap.chunkCount(), "same chunking as the in-memory export");
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i).encode(), snap.chunkBytes(i),
                "chunk " + i + " served from the spool equals the in-memory export");
        }

        // Re-materialising deletes the replaced spool; the live one stays servable.
        java.nio.file.Path replaced = snap.file();
        assertTrue(node.materializeSnapshot());
        var next = node.materializedSnapshot();
        assertTrue(java.nio.file.Files.notExists(replaced),
            "the replaced snapshot's spool must be deleted");
        assertTrue(java.nio.file.Files.exists(next.file()));
        assertEquals(snap.chunkCount(), next.chunkCount());

        // Closing the service releases the live spool.
        node.close();
        assertTrue(java.nio.file.Files.notExists(next.file()),
            "close() must delete the live snapshot spool");
        assertNull(node.materializedSnapshot());
    }
}
