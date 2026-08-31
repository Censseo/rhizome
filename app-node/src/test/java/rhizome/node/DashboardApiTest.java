package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
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
import rhizome.core.blockchain.CurveActiveNetwork;
import rhizome.core.blockchain.HonestBlockMiner;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.ReorgWindowTestAccess;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.blockchain.SupplyStamp;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
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

        var pair = generateKeyPairTyped();
        key = pair.publicKey();
        priv = pair.privateKey();
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

    @Test
    void nodeServesItsOwnDocumentation() throws Exception {
        HttpResponse manifest = call(HttpRequest.get("http://x/docs/manifest.json").build());
        assertEquals(200, manifest.getCode());
        var pages = new JSONObject(body(manifest)).getJSONArray("pages");
        assertTrue(pages.length() >= 16);

        // Every page the manifest advertises is reachable over HTTP, with the security headers
        // the dashboard assets carry (the docs page renders this markdown into the same DOM).
        for (int i = 0; i < pages.length(); i++) {
            String file = pages.getJSONObject(i).getString("file");
            HttpResponse doc = call(HttpRequest.get("http://x/docs/" + file).build());
            assertEquals(200, doc.getCode(), file);
            assertTrue(body(doc).startsWith("#"), file + " should be markdown");
            assertEquals("DENY", doc.getHeader(HttpHeaders.of("X-Frame-Options")));
        }

        assertEquals(404, call(HttpRequest.get("http://x/docs/nope.md").build()).getCode());
        assertEquals(404, call(HttpRequest.get("http://x/docs/manifest.json/../../logback.xml").build()).getCode());
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

    /** A self-contained engine/node/servlet triple for a NON-default {@link NetworkParameters}
     *  profile -- the shared fixture's params/engine/node/servlet fields are testnet's default
     *  (curve-inactive), so the curve-activation tests below build their own over the same
     *  running {@link #eventloop}. */
    private record Harness(ChainEngine engine, NodeService node, AsyncServlet servlet) {}

    private Harness bootHarness(NetworkParameters p) {
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, p.chainId());
        var verifier = new SignatureVerifier();
        ChainEngine e = ChainEngine.boot(p, TestNodeStores.inMemory(), snapshot).clock(clock::get).build();
        var mempool = new MemPool(p, verifier, e, 1000);
        NodeService n = new NodeService(e, mempool, NodeSources.none());
        return new Harness(e, n, NodeApi.servlet(eventloop, n));
    }

    private HttpResponse callOn(AsyncServlet s, HttpRequest request) throws Exception {
        return eventloop.<HttpResponse>submit(() ->
            s.serve(request).then(resp -> resp.loadBody().map($ -> resp))
        ).get();
    }

    /** Mines and applies an honest next block on {@code e} under {@code p}, paying the reward
     *  {@code p} actually dispatches (geometric or curve) for the height being built. */
    private Block mineNextOn(ChainEngine e, NetworkParameters p) {
        long height = e.height() + 1;
        long parentSupply = e.headerAt(e.height()).supply();
        long ts = clock.addAndGet(1000L);
        Transaction coinbase = Transaction.of(PublicAddress.random(),
            new TransactionAmount(p.miningReward(height, parentSupply)));
        return HonestBlockMiner.mineNext(p, e, ts, coinbase);
    }

    @Test
    void theReportedSubsidyEqualsTheCoinbaseActuallyPaid() throws Exception {
        // Curve-active profile: parent supply committed, tip above activation.
        NetworkParameters curveParams = CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).emissionCurveHeight(2L).build();
        Harness curve = bootHarness(curveParams);
        Block curveTip = mineNextOn(curve.engine(), curveParams);
        assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS, curve.node().submitBlock(curveTip));
        long curveTipHeight = curve.engine().height();
        long curveParentSupply = curve.node().header(curveTipHeight - 1).supply();
        assertTrue(curveParams.emissionCurveActiveAt(curveTipHeight), "sanity: tip must be curve-active");

        JSONObject curveStats = new JSONObject(body(callOn(curve.servlet(), HttpRequest.get("http://x/stats").build())));
        long reportedCurveReward = curveStats.getLong("miningReward");
        long actualCurveCoinbase = curveTip.transactions().get(0).amount().amount();
        assertEquals(actualCurveCoinbase, reportedCurveReward,
            "the reported subsidy must equal the coinbase the tip actually paid (curve-active)");
        assertEquals(curveParams.miningReward(curveTipHeight, curveParentSupply), reportedCurveReward);

        // Geometric twin: a never-activating profile, same shape otherwise.
        NetworkParameters geometricParams = curveParams.toBuilder().emissionCurveHeight(0L).build();
        Harness geometric = bootHarness(geometricParams);
        Block geometricTip = mineNextOn(geometric.engine(), geometricParams);
        assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS, geometric.node().submitBlock(geometricTip));
        long geometricTipHeight = geometric.engine().height();
        assertFalse(geometricParams.emissionCurveActiveAt(geometricTipHeight),
            "sanity: the never-activating twin must stay curve-inactive");

        JSONObject geometricStats = new JSONObject(
            body(callOn(geometric.servlet(), HttpRequest.get("http://x/stats").build())));
        long reportedGeometricReward = geometricStats.getLong("miningReward");
        long actualGeometricCoinbase = geometricTip.transactions().get(0).amount().amount();
        assertEquals(actualGeometricCoinbase, reportedGeometricReward,
            "the reported subsidy must equal the coinbase the tip actually paid (geometric)");
        assertEquals(geometricParams.miningReward(geometricTipHeight), reportedGeometricReward);
    }

    @Test
    void statsCarriesTheIdenticalEmissionObjectAndAnUnchangedMiningReward() throws Exception {
        // /stats adds the same emission fragment /info serves — written by the same writer, so
        // the two routes cannot drift — while the pre-existing miningReward field keeps its
        // value (the subsidy the tip's coinbase paid), its JSON number type and its meaning.
        apply(mineNext(List.of()));
        apply(mineNext(List.of()));

        JSONObject stats = new JSONObject(body(call(HttpRequest.get("http://x/stats").build())));
        JSONObject info = new JSONObject(body(call(HttpRequest.get("http://x/info").build())));

        JSONObject statsEmission = stats.getJSONObject("emission");
        assertTrue(statsEmission.similar(info.getJSONObject("emission")),
            "the emission fragment must be identical on both surfaces");

        // Unchanged in value, type and meaning: still the tip's coinbase subsidy, still a NUMBER.
        assertTrue(stats.has("miningReward"));
        assertEquals(params.miningReward(stats.getLong("height")),
            stats.getLong("miningReward"),
            "miningReward keeps its pre-existing geometric meaning on this never-activating profile");
        assertFalse(stats.get("miningReward") instanceof String,
            "miningReward stays a JSON number, unlike the emission fragment's decimal strings");
    }

    @Test
    void statsEmissionMatchesTheConsensusDispatchOnACurveActiveProfile() throws Exception {
        // The /stats fragment's subsidy is the NEXT block's dispatch at the tip's committed
        // supply — served from the window cache's tip field, so a stationary poll re-takes no
        // consensus lock for it.
        NetworkParameters curveParams = CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).build();
        Harness curve = bootHarness(curveParams);
        assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS,
            curve.node().submitBlock(mineNextOn(curve.engine(), curveParams)));
        assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS,
            curve.node().submitBlock(mineNextOn(curve.engine(), curveParams)));

        JSONObject stats = new JSONObject(body(callOn(curve.servlet(),
            HttpRequest.get("http://x/stats").build())));
        JSONObject emission = stats.getJSONObject("emission");
        long tipHeight = curve.engine().height();
        long tipSupply = curve.engine().tipSupply().supply();
        assertEquals(tipHeight, stats.getLong("height"));
        assertEquals(tipSupply + "", emission.getString("supply"),
            "the fragment's supply is the tip's own committed supply (not the parent's)");
        assertEquals(curveParams.miningReward(tipHeight + 1, tipSupply) + "",
            emission.getString("subsidy"), "the fragment's subsidy is the next block's dispatch");
        assertEquals("curve", emission.getString("rule"));
        assertEquals(curveParams.supplyTarget() + "", emission.getString("target"));
    }

    @Test
    void aGenesisOnlyChainReportsADefinedSubsidy() throws Exception {
        // Curve scheduled from height 1 -- genesis itself has no parent header to read, and pays
        // no coinbase. /stats must not error and must not omit the field.
        NetworkParameters curveParams = CurveActiveNetwork.curveActiveTestnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(4).emissionCurveHeight(1L).build();
        Harness curve = bootHarness(curveParams);
        assertEquals(1, curve.engine().height(), "sanity: a fresh chain is genesis-only");

        JSONObject stats = new JSONObject(body(callOn(curve.servlet(), HttpRequest.get("http://x/stats").build())));
        assertTrue(stats.has("miningReward"), "a genesis-only chain must still report a defined subsidy");
        assertEquals(curveParams.miningReward(1L), stats.getLong("miningReward"),
            "with no parent header to read, the field must fall back to the geometric value, not error");
    }

    @Test
    void statsDoesNotObserveAMixedHeightHeaderPairDuringAReorgWindow() throws Exception {
        // 006-emission-fork-activation: /stats reads the tip height (node.blockCount()) and,
        // separately, the tip's parent header (node.header(height - 1)) through two independent
        // ChainEngine lock acquisitions -- a reorg can land between them. Guarded the same way
        // /blocks, /block, /block_count and /total_work already are (testnet campaign S5): an
        // in-progress reorg must answer 503, never let the second read observe a tip the first
        // read no longer agrees with (which would otherwise surface as a raw
        // IllegalArgumentException -> generic 400 on the whole payload).
        apply(mineNext(List.of()));
        apply(mineNext(List.of()));

        assertTrue(ReorgWindowTestAccess.begin(engine), "reorg window opens");
        try {
            HttpResponse response = call(HttpRequest.get("http://x/stats").build());
            assertEquals(503, response.getCode(),
                "an in-progress reorg must 503 /stats, not risk a mixed height/header read");
        } finally {
            ReorgWindowTestAccess.end(engine); // never leave the window open for the next test
        }
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
        // sees counter=1 -> outputs 2, and repeating it returns the same value. The calls
        // declare a modest gasLimit so repeated reads fit the 25M gas/s aggregate budget
        // (the 10M default would exhaust it on the third call — the budget's purpose).
        for (int i = 0; i < 2; i++) {
            HttpResponse q = call(HttpRequest.post("http://x/call_readonly")
                .withBody(new JSONObject()
                    .put("to", contract.toHexString())
                    .put("input", "")
                    .put("gasLimit", 5_000_000L).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
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
                .put("input", "")
                .put("gasLimit", 5_000_000L).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .build());
        assertEquals(200, bad.getCode());
        assertFalse(new JSONObject(body(bad)).getBoolean("success"));
    }
}
