package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.eventloop.Eventloop;
import io.activej.http.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.BlockHeader;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.block.BlockImpl;
import rhizome.crypto.PowAlgorithm;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.persistence.rocksdb.RocksDbNodeStore;

/**
 * A pruned node served over HTTP: it advertises its prune watermark on /info, refuses
 * /sync into the discarded range with 410, yet still serves every header and its recent
 * bodies — exactly what a headers-first or snapshot-bootstrapping peer needs from it.
 */
class PrunedNodeApiTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).minDifficulty(4).build();

    @TempDir
    Path tempDir;

    private Eventloop eventloop;
    private Thread eventloopThread;
    private HttpServer server;
    private RocksDbNodeStore store;
    private int port;
    private long prunedBelow;
    private long height;

    @BeforeEach
    void setUp() throws Exception {
        int keep = 5;
        store = new RocksDbNodeStore(tempDir.resolve("db").toString(), keep);
        AtomicLong clock = new AtomicLong(0);
        ChainEngine engine = ChainEngine.init(PARAMS, store.ledger(), store.chainStore(), store.nonceStore(),
            new LedgerSnapshot("t", 0, PARAMS.chainId()), null, clock::get, null, null, null, null, null);
        PublicAddress miner = PublicAddress.random();
        for (int i = 0; i < 10; i++) {
            long h = engine.height() + 1;
            var b = (BlockImpl) BlockImpl.builder().id((int) h)
                .timestamp(clock.addAndGet(90_000)).difficulty(engine.difficulty())
                .lastBlockHash(engine.tipHash()).build();
            b.addTransaction(Transaction.of(miner, new TransactionAmount(PARAMS.miningReward(h))));
            var tree = new MerkleTree();
            tree.setItems(b.transactions());
            b.merkleRoot(tree.getRootHash());
            b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), PARAMS.powAlgorithm()));
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
        }
        height = engine.height();          // 11
        prunedBelow = engine.prunedBelow(); // height - keep + 1 = 7

        MemPool mempool = new MemPool(PARAMS, new rhizome.core.blockchain.SignatureVerifier(), engine, 1000);
        var node = new NodeService(engine, mempool);

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
        store.close();
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void infoAdvertisesThePruneWatermark() throws Exception {
        JSONObject info = new JSONObject(new String(get("/info").body()));
        assertEquals(prunedBelow, info.getLong("prunedBelow"));
        assertTrue(prunedBelow > 1, "some history was actually pruned");
    }

    @Test
    void syncIntoPrunedRangeReturns410() throws Exception {
        HttpResponse<byte[]> pruned = get("/sync?start=2&end=4");
        assertEquals(410, pruned.statusCode());
        JSONObject body = new JSONObject(new String(pruned.body()));
        assertEquals(prunedBelow, body.getLong("prunedBelow"));

        // A recent, retained range is served normally.
        assertEquals(200, get("/sync?start=" + prunedBelow + "&end=" + height).statusCode());
    }

    @Test
    void explorerReadsIntoPrunedRangeReturn410NotAGeneric400() throws Exception {
        // A single pruned block and a range dipping below the watermark answer 410 GONE with the
        // watermark, matching /sync — so a client sources the block from an archive rather than
        // treating a pruned height as a malformed request.
        HttpResponse<byte[]> single = get("/block?blockId=2");
        assertEquals(410, single.statusCode());
        assertEquals(prunedBelow, new JSONObject(new String(single.body())).getLong("prunedBelow"));

        HttpResponse<byte[]> range = get("/blocks?start=2&end=4");
        assertEquals(410, range.statusCode());
        assertEquals(prunedBelow, new JSONObject(new String(range.body())).getLong("prunedBelow"));

        // A retained block is still served normally.
        assertEquals(200, get("/block?blockId=" + height).statusCode());
    }

    @Test
    void transactionScanStaysWithinRetainedBodies() throws Exception {
        // A tip-backward scan with a depth reaching into the pruned range must not touch discarded
        // bodies (node.block would throw); it clamps its floor to the watermark and returns cleanly.
        HttpResponse<byte[]> resp = get("/transaction?depth=1000&txid=" + "0".repeat(64));
        assertEquals(404, resp.statusCode(), "not found in the retained range, not a 500 from a pruned read");
    }

    @Test
    void headersAreServedForEveryHeightIncludingPrunedOnes() throws Exception {
        HttpResponse<byte[]> resp = get("/headers?start=1&end=" + height);
        assertEquals(200, resp.statusCode());
        List<BlockHeader> headers = rhizome.core.block.HeaderCodec.decodeAll(resp.body());
        assertEquals(height, headers.size());
        // Includes headers for heights whose bodies were pruned — the point of pruning.
        assertEquals(2, headers.get(1).id());
    }
}
