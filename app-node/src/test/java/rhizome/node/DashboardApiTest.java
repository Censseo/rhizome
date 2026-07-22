package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.Contracts;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.vm.InMemoryContractStore;
import rhizome.vm.WasmContractProcessor;
import rhizome.vm.WasmVm;

/**
 * The dashboard surface of the node API: embedded SPA assets, the aggregated
 * /stats + /features endpoints, explorer scans (/blocks, /transaction,
 * /address_txs) and contract introspection (/contract, /contract/query).
 */
class DashboardApiTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private WasmContractProcessor processor;
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

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        var verifier = new SignatureVerifier();
        processor = new WasmContractProcessor(new WasmVm(), new InMemoryContractStore());
        var boxProcessor = new rhizome.core.box.DefaultBoxProcessor(
            new rhizome.core.box.InMemoryBoxStore(), params);
        var tokenProcessor = new rhizome.core.token.DefaultTokenProcessor(
            new rhizome.core.token.InMemoryTokenStore(), params);
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, clock::get, verifier, processor, boxProcessor, tokenProcessor);
        var mempool = new MemPool(params, verifier, engine, 1000);
        node = new NodeService(engine, mempool);
        node.setLogSource(processor::logs);
        node.setCodeSource(processor::codeAt);
        node.setContracts(processor);
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
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        ((BlockImpl) b).merkleRoot(tree.getRootHash());
        ((BlockImpl) b).nonce(Miner.mineNonce(b.hash(), ((BlockImpl) b).difficulty(), params.powAlgorithm()));
        return b;
    }

    private void apply(Block block) {
        assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS, node.submitBlock(block));
    }

    private Transaction signedSend(long amount, long nonce) {
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(amount),
            key, new TransactionAmount(0), 1000L + nonce, params.chainId(), nonce);
        t.sign(priv);
        return t;
    }

    private Transaction signedContract(TransactionKind kind, PublicAddress to, byte[] data, long nonce) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(to)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .timestamp(2000L + nonce).chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(kind).data(data).gasLimit(200_000).gasPrice(0)
            .build();
        t.sign(priv);
        return t;
    }

    private static byte[] counterWasm() {
        try (var in = DashboardApiTest.class.getResourceAsStream("/counter.wasm")) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---- embedded SPA ----

    @Test
    void dashboardIsServedAtRootAndUnderPrefix() throws Exception {
        HttpResponse root = call(HttpRequest.get("http://x/").build());
        assertEquals(200, root.getCode());
        assertTrue(body(root).contains("Rhizome"));

        HttpResponse js = call(HttpRequest.get("http://x/dashboard/app.js").build());
        assertEquals(200, js.getCode());

        HttpResponse manifest = call(HttpRequest.get("http://x/dashboard/templates/manifest.json").build());
        assertEquals(200, manifest.getCode());
        assertTrue(new JSONObject(body(manifest)).getJSONArray("templates").length() >= 5);

        HttpResponse wasm = call(HttpRequest.get("http://x/dashboard/templates/agent_wallet.wasm").build());
        assertEquals(200, wasm.getCode());
        assertTrue(wasm.getBody().readRemaining() > 0);

        assertEquals(404, call(HttpRequest.get("http://x/dashboard/nope.js").build()).getCode());
    }

    // ---- stats & features ----

    @Test
    void statsAggregatesTipAndWindow() throws Exception {
        apply(mineNext(List.of(signedSend(1000, 0))));
        apply(mineNext(List.of()));

        JSONObject stats = new JSONObject(body(call(HttpRequest.get("http://x/stats").build())));
        assertEquals(3, stats.getLong("height"));
        assertEquals(params.chainId(), stats.getInt("chainId"));
        assertEquals(params.decimalScaleFactor(), stats.getLong("decimalScaleFactor"));
        // 2 mined coinbases + 1 transfer across the window (the genesis block carries none).
        assertEquals(3, stats.getLong("windowTxCount"));
        assertTrue(stats.getLong("avgBlockIntervalMs") > 0);
        assertNotNull(stats.getString("totalWork"));
    }

    @Test
    void featuresReflectWiring() throws Exception {
        JSONObject features = new JSONObject(body(call(HttpRequest.get("http://x/features").build())));
        assertTrue(features.getBoolean("dashboard"));
        assertTrue(features.getBoolean("contracts"));
        assertTrue(features.getBoolean("boxes"));
        assertTrue(features.getBoolean("tokens"));
    }

    // ---- explorer scans ----

    @Test
    void blocksReturnsBoundedSummaries() throws Exception {
        apply(mineNext(List.of(signedSend(1000, 0))));

        JSONObject res = new JSONObject(body(call(HttpRequest.get("http://x/blocks?start=1&end=2").build())));
        assertEquals(2, res.getJSONArray("blocks").length());
        JSONObject tip = res.getJSONArray("blocks").getJSONObject(1);
        assertEquals(2, tip.getLong("height"));
        assertEquals(2, tip.getInt("txCount")); // coinbase + transfer
        assertEquals(64, tip.getString("hash").length());

        assertEquals(400, call(HttpRequest.get("http://x/blocks?start=0&end=1").build()).getCode());
        assertEquals(400, call(HttpRequest.get("http://x/blocks?start=1&end=999").build()).getCode());
    }

    @Test
    void transactionLookupScansBackFromTip() throws Exception {
        Transaction t = signedSend(1234, 0);
        apply(mineNext(List.of(t)));
        apply(mineNext(List.of()));

        String txid = t.hashContents().toHexString();
        JSONObject found = new JSONObject(body(call(
            HttpRequest.get("http://x/transaction?txid=" + txid).build())));
        assertEquals(2, found.getLong("height"));
        assertEquals(txid, found.getJSONObject("transaction").getString("txid"));

        assertEquals(404, call(HttpRequest.get(
            "http://x/transaction?txid=" + "0".repeat(64)).build()).getCode());
        assertEquals(400, call(HttpRequest.get("http://x/transaction?txid=xyz").build()).getCode());
    }

    @Test
    void addressHistoryFindsBothDirections() throws Exception {
        Transaction t = signedSend(1234, 0);
        apply(mineNext(List.of(t)));

        JSONObject res = new JSONObject(body(call(HttpRequest.get(
            "http://x/address_txs?address=" + sender.toHexString()).build())));
        assertEquals(1, res.getJSONArray("transactions").length());
        assertEquals(2, res.getJSONArray("transactions").getJSONObject(0).getLong("height"));

        // The recipient sees the same transaction.
        String to = t.to().toHexString();
        JSONObject forTo = new JSONObject(body(call(HttpRequest.get(
            "http://x/address_txs?address=" + to).build())));
        assertEquals(1, forTo.getJSONArray("transactions").length());
    }

    // ---- contract introspection ----

    @Test
    void contractInfoAndReadOnlyQuery() throws Exception {
        byte[] code = counterWasm();
        PublicAddress contract = Contracts.deriveAddress(sender, 0);
        apply(mineNext(List.of(signedContract(TransactionKind.DEPLOY, PublicAddress.empty(), code, 0))));
        // One real CALL so the counter is at 1 in committed state.
        apply(mineNext(List.of(signedContract(TransactionKind.CALL, contract, new byte[0], 1))));

        JSONObject info = new JSONObject(body(call(HttpRequest.get(
            "http://x/contract?address=" + contract.toHexString()).build())));
        assertTrue(info.getBoolean("exists"));
        assertEquals(code.length, info.getInt("codeSize"));
        assertEquals(64, info.getString("codeHash").length());

        JSONObject missing = new JSONObject(body(call(HttpRequest.get(
            "http://x/contract?address=" + PublicAddress.random().toHexString()).build())));
        assertFalse(missing.getBoolean("exists"));

        // The dashboard reads via /call_readonly: a dry run against a throwaway overlay
        // sees counter=1 -> outputs 2, and repeating it returns the same value.
        for (int i = 0; i < 2; i++) {
            HttpResponse q = call(HttpRequest.post("http://x/call_readonly")
                .withBody(new JSONObject()
                    .put("to", contract.toHexString())
                    .put("input", "").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build());
            assertEquals(200, q.getCode());
            JSONObject res = new JSONObject(body(q));
            assertTrue(res.getBoolean("success"));
            assertEquals("0200000000000000", res.getString("output")); // 2, u64 LE
            assertTrue(res.getLong("gasUsed") > 0);
        }

        // Dry run against an empty address fails cleanly.
        HttpResponse bad = call(HttpRequest.post("http://x/call_readonly")
            .withBody(new JSONObject()
                .put("to", PublicAddress.random().toHexString())
                .put("input", "").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .build());
        assertEquals(200, bad.getCode());
        assertFalse(new JSONObject(body(bad)).getBoolean("success"));
    }
}
