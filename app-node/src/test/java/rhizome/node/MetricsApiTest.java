package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.vm.InMemoryContractStore;
import rhizome.vm.WasmContractProcessor;
import rhizome.vm.WasmVm;

/**
 * {@code GET /metrics}: an OpenMetrics/Prometheus-scrapeable projection of the same per-tip
 * {@link StatsWindowService.StatsWindow} cache {@code /stats} reads — a pure text rendering, so
 * its figures must always agree with {@code /stats} on the same tip.
 */
class MetricsApiTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private NodeService node;
    private AsyncServlet servlet;
    private Eventloop eventloop;
    private Thread eventloopThread;
    private AtomicLong clock;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        eventloop = Eventloop.create();
        clock = new AtomicLong(0);

        var pair = generateKeyPairTyped();
        key = pair.publicKey();
        priv = pair.privateKey();
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        var verifier = new SignatureVerifier();
        var processor = new WasmContractProcessor(new WasmVm(), new InMemoryContractStore());
        var boxProcessor = new rhizome.core.box.DefaultBoxProcessor(
            new rhizome.core.box.InMemoryBoxStore(), params);
        var tokenProcessor = new rhizome.core.token.DefaultTokenProcessor(
            new rhizome.core.token.InMemoryTokenStore(), params);
        engine = ChainEngine.boot(params, TestNodeStores.inMemory(), snapshot)
            .clock(clock::get)
            .verifier(verifier)
            .contracts(processor)
            .boxes(boxProcessor)
            .tokens(tokenProcessor)
            .build();
        var mempool = new MemPool(params, verifier, engine, 1000);
        node = new NodeService(engine, mempool, NodeSources.builder()
            .logSource(processor::logs).codeSource(processor::codeAt).contracts(processor).build());
        servlet = NodeApi.servlet(eventloop, node);

        eventloop.keepAlive(true);
        eventloopThread = new Thread(eventloop, "test-eventloop");
        eventloopThread.setDaemon(true);
        eventloopThread.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        eventloop.keepAlive(false);
        eventloop.execute(eventloop::breakEventloop);
        eventloopThread.join(2000);
    }

    private HttpResponse call(HttpRequest request) throws Exception {
        return eventloop.<HttpResponse>submit(() ->
            servlet.serve(request).then(resp -> resp.loadBody().map($ -> resp))
        ).get();
    }

    private static String body(HttpResponse r) {
        return r.getBody().getString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Block mineNext(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty()))
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        ((BlockImpl) b).merkleRoot(tree.getRootHash());
        ((BlockImpl) b).nonce(rhizome.core.blockchain.Miner.mineNonce(
            b.hash(), ((BlockImpl) b).difficulty(), params.powAlgorithm()));
        return b;
    }

    private void apply(Block block) {
        assertEquals(ExecutionStatus.SUCCESS, node.submitBlock(block));
    }

    private static final java.util.regex.Pattern METRIC_LINE =
        java.util.regex.Pattern.compile("(?m)^(rhizome_[a-z_]+) ([^\\s]+)$");

    private static java.util.Map<String, String> parseMetrics(String body) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        var m = METRIC_LINE.matcher(body);
        while (m.find()) {
            values.put(m.group(1), m.group(2));
        }
        return values;
    }

    @Test
    void metricsReturns200WithOpenMetricsContentType() throws Exception {
        apply(mineNext(List.of()));

        HttpResponse response = call(HttpRequest.get("http://x/metrics").build());
        assertEquals(200, response.getCode());
        assertEquals("text/plain; charset=utf-8", response.getHeader(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void metricsExposesEveryRequiredNameWithATypeCommentAndAPlausibleValue() throws Exception {
        apply(mineNext(List.of()));
        apply(mineNext(List.of()));

        String text = body(call(HttpRequest.get("http://x/metrics").build()));

        String[] required = {
            "rhizome_height", "rhizome_difficulty", "rhizome_total_work", "rhizome_peers",
            "rhizome_mempool_size", "rhizome_avg_block_interval_ms",
            "rhizome_last_block_timestamp_seconds", "rhizome_reorg_in_progress",
            "rhizome_degraded", "rhizome_sync_rounds_without_progress",
            "rhizome_sync_peers_banned", "rhizome_sync_eclipsed", "rhizome_pruned_below",
            "rhizome_supply_base_units", "rhizome_max_reorg_depth",
        };
        var values = parseMetrics(text);
        for (String name : required) {
            assertTrue(text.contains("# TYPE " + name + " gauge"), name + " must carry a TYPE comment");
            assertTrue(values.containsKey(name), name + " must be present in the body");
        }

        // Plausible values: booleans are 0, counters are non-negative.
        assertEquals("0", values.get("rhizome_reorg_in_progress"));
        assertEquals("0", values.get("rhizome_degraded"));
        assertEquals("0", values.get("rhizome_sync_eclipsed"));
        assertTrue(Long.parseLong(values.get("rhizome_height")) > 0);
        assertTrue(Double.parseDouble(values.get("rhizome_total_work")) >= 0);
        assertTrue(Double.parseDouble(values.get("rhizome_last_block_timestamp_seconds")) > 0);
    }

    @Test
    void metricsAgreesWithStatsOnTheSameTip() throws Exception {
        apply(mineNext(List.of()));
        apply(mineNext(List.of()));
        apply(mineNext(List.of()));

        JSONObject stats = new JSONObject(body(call(HttpRequest.get("http://x/stats").build())));
        var values = parseMetrics(body(call(HttpRequest.get("http://x/metrics").build())));

        assertEquals(stats.getLong("height"), Long.parseLong(values.get("rhizome_height")));
        assertEquals(stats.getInt("difficulty"), Long.parseLong(values.get("rhizome_difficulty")));
        assertEquals(stats.getInt("peers"), Long.parseLong(values.get("rhizome_peers")));
        assertEquals(stats.getLong("mempool"), Long.parseLong(values.get("rhizome_mempool_size")));
        assertEquals(stats.getLong("avgBlockIntervalMs"),
            Long.parseLong(values.get("rhizome_avg_block_interval_ms")));
        assertEquals(stats.getLong("maxReorgDepth"), Long.parseLong(values.get("rhizome_max_reorg_depth")));
        assertEquals(stats.getLong("syncRoundsWithoutProgress"),
            Long.parseLong(values.get("rhizome_sync_rounds_without_progress")));
        assertEquals(stats.getLong("syncPeersBanned"),
            Long.parseLong(values.get("rhizome_sync_peers_banned")));
        assertEquals(stats.getLong("lastBlockTimestamp") / 1000.0,
            Double.parseDouble(values.get("rhizome_last_block_timestamp_seconds")), 0.001);
        assertEquals(new java.math.BigInteger(stats.getString("totalWork")).doubleValue(),
            Double.parseDouble(values.get("rhizome_total_work")), 1e-6);
        assertEquals(stats.getJSONObject("emission").getString("supply"),
            values.get("rhizome_supply_base_units"));
    }

    @Test
    void metricsRefusesDuringAReorgWindowLikeStats() throws Exception {
        apply(mineNext(List.of()));
        apply(mineNext(List.of()));

        assertTrue(rhizome.core.blockchain.ReorgWindowTestAccess.begin(engine), "reorg window opens");
        try {
            HttpResponse response = call(HttpRequest.get("http://x/metrics").build());
            assertEquals(503, response.getCode(),
                "an in-progress reorg must 503 /metrics, matching /stats's own guard");
        } finally {
            rhizome.core.blockchain.ReorgWindowTestAccess.end(engine);
        }
    }
}
