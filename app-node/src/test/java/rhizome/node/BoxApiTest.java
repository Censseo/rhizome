package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
import rhizome.core.box.Box;
import rhizome.core.box.BoxPayload;
import rhizome.core.box.BoxRegister;
import rhizome.core.box.DefaultBoxProcessor;
import rhizome.core.box.InMemoryBoxStore;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.common.Utils;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenPayload;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.vm.InMemoryContractStore;
import rhizome.vm.WasmContractProcessor;
import rhizome.vm.WasmVm;

/**
 * The data-box and dry-run HTTP surface end to end through the servlet, on a fully
 * box- and contract-enabled engine: GET /box, /boxes, the /scan endpoints, and
 * POST /call_readonly.
 */
class BoxApiTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private MemPool mempool;
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
        snapshot.put(sender, new TransactionAmount(10_000_000L));

        var verifier = new SignatureVerifier();
        var contracts = new WasmContractProcessor(new WasmVm(), new InMemoryContractStore());
        var boxes = new DefaultBoxProcessor(new InMemoryBoxStore(), params);
        var tokens = new DefaultTokenProcessor(new InMemoryTokenStore(), params);
        var accumulator = new rhizome.core.state.StateAccumulator(
            new rhizome.core.state.InMemorySmtNodeStore(),
            new rhizome.core.state.InMemoryRootStore(), params.maxReorgDepth());
        contracts.setBoxReader(boxes::get);
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, clock::get, verifier, contracts, boxes, tokens, accumulator);
        mempool = new MemPool(params, verifier, engine, 1000);
        var node = new NodeService(engine, mempool);
        node.setContracts(contracts);
        node.setBoxEventSource(boxes::events);
        node.setTokenEventSource(tokens::events);
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

    private static org.bouncycastle.crypto.AsymmetricCipherKeyPair generateKeyPair() {
        return rhizome.core.common.Crypto.generateKeyPair();
    }

    private HttpResponse call(HttpRequest request) throws Exception {
        return eventloop.<HttpResponse>submit(() ->
            servlet.serve(request).then(resp -> resp.loadBody().map($ -> resp))).get();
    }

    private static JSONObject json(HttpResponse r) {
        return new JSONObject(r.getBody().getString(StandardCharsets.UTF_8));
    }

    private Transaction boxCreate(long value, long nonce, BoxRegister... regs) {
        var tx = TransactionImpl.builder()
            .from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).timestamp(1000L + nonce)
            .kind(TransactionKind.BOX_CREATE).data(BoxPayload.encodeCreate(List.of(regs)))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(priv);
        return tx;
    }

    private void mine(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        ((BlockImpl) b).merkleRoot(tree.getRootHash());
        engine.stampStateRoot(b); // the accumulator is wired, so the header must commit the root
        ((BlockImpl) b).nonce(Miner.mineNonce(b.hash(), ((BlockImpl) b).difficulty(), params.powAlgorithm()));
        assertEquals(rhizome.core.mempool.ExecutionStatus.SUCCESS, engine.addBlock(b));
    }

    @Test
    void boxEndpointReturnsBoxAnd404() throws Exception {
        mine(List.of(boxCreate(5000, 0, BoxRegister.string("agent-memory"))));
        String id = Utils.bytesToHex(Box.deriveId(sender, 0));

        HttpResponse ok = call(HttpRequest.get("http://x/box?id=" + id).build());
        assertEquals(200, ok.getCode());
        JSONObject box = json(ok);
        assertTrue(id.equalsIgnoreCase(box.getString("id")));
        assertEquals(5000, box.getLong("value"));
        assertEquals("STRING", box.getJSONArray("registers").getJSONObject(0).getString("type"));
        assertEquals("agent-memory", box.getJSONArray("registers").getJSONObject(0).getString("string"));

        HttpResponse missing = call(HttpRequest.get(
            "http://x/box?id=" + Utils.bytesToHex(new byte[32])).build());
        assertEquals(404, missing.getCode());
    }

    @Test
    void boxesByOwnerLists() throws Exception {
        mine(List.of(boxCreate(2000, 0), boxCreate(2000, 1)));
        HttpResponse r = call(HttpRequest.get("http://x/boxes?owner=" + sender.toHexString()).build());
        assertEquals(200, r.getCode());
        assertEquals(2, json(r).getJSONArray("boxes").length());
    }

    @Test
    void scanRegisterAndQuery() throws Exception {
        mine(List.of(
            boxCreate(2000, 0, BoxRegister.string("price-oracle")),
            boxCreate(2000, 1, BoxRegister.string("unrelated"))));

        // Register a "register 0 contains 'oracle'" scan.
        JSONObject predicate = new JSONObject()
            .put("type", "registerContains").put("index", 0)
            .put("value", Utils.bytesToHex("oracle".getBytes(StandardCharsets.UTF_8)));
        HttpResponse reg = call(HttpRequest.post("http://x/scan/register")
            .withBody(predicate.toString().getBytes(StandardCharsets.UTF_8)).build());
        assertEquals(200, reg.getCode());
        int scanId = json(reg).getInt("scanId");

        HttpResponse boxes = call(HttpRequest.get("http://x/scan/boxes?scanId=" + scanId).build());
        assertEquals(200, boxes.getCode());
        assertEquals(1, json(boxes).getJSONArray("boxes").length());

        assertEquals(1, json(call(HttpRequest.get("http://x/scan/list").build())).getJSONArray("scans").length());

        HttpResponse dereg = call(HttpRequest.post("http://x/scan/deregister")
            .withBody(new JSONObject().put("scanId", scanId).toString().getBytes(StandardCharsets.UTF_8)).build());
        assertTrue(json(dereg).getBoolean("removed"));
        assertEquals(400, call(HttpRequest.get("http://x/scan/boxes?scanId=" + scanId).build()).getCode());
    }

    private Transaction tokenMint(long amount, int decimals, String symbol, String name, long nonce) {
        var tx = TransactionImpl.builder()
            .from(sender).to(sender).signingKey(key)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).timestamp(1000L + nonce)
            .kind(TransactionKind.TOKEN_MINT).data(TokenPayload.encodeMint(amount, decimals, symbol, name))
            .gasLimit(0).gasPrice(0).build();
        tx.sign(priv);
        return tx;
    }

    @Test
    void tokenEndpointsReturnMetaBalanceAndList() throws Exception {
        mine(List.of(tokenMint(1_000_000, 8, "PNDA", "Panda Coin", 0)));
        String id = Utils.bytesToHex(TokenMeta.deriveId(sender, 0));

        HttpResponse meta = call(HttpRequest.get("http://x/token?id=" + id).build());
        assertEquals(200, meta.getCode());
        JSONObject m = json(meta);
        assertEquals("PNDA", m.getString("symbol"));
        assertEquals("Panda Coin", m.getString("name"));
        assertEquals(8, m.getInt("decimals"));
        assertEquals(1_000_000, m.getLong("totalSupply"));

        HttpResponse bal = call(HttpRequest.get(
            "http://x/token_balance?id=" + id + "&address=" + sender.toHexString()).build());
        assertEquals(1_000_000, json(bal).getLong("balance"));

        HttpResponse held = call(HttpRequest.get("http://x/tokens?holder=" + sender.toHexString()).build());
        assertEquals(1, json(held).getJSONArray("tokens").length());

        assertEquals(404, call(HttpRequest.get(
            "http://x/token?id=" + Utils.bytesToHex(new byte[32])).build()).getCode());
    }

    @Test
    void stateEndpointsExposeRootAndProof() throws Exception {
        mine(List.of(tokenMint(1_000_000, 8, "PNDA", "Panda Coin", 0)));

        HttpResponse st = call(HttpRequest.get("http://x/state").build());
        assertEquals(200, st.getCode());
        String root = json(st).getString("stateRoot");
        assertFalse(root.isEmpty());

        // A ledger proof for the miner (who earned the block reward) verifies against the root.
        HttpResponse pr = call(HttpRequest.get(
            "http://x/state/proof?domain=ledger&key=" + miner.toHexString()).build());
        assertEquals(200, pr.getCode());
        JSONObject proof = json(pr);
        assertEquals(root, proof.getString("root"));
        assertFalse(proof.getString("valueHash").isEmpty());

        // An unknown ledger entry has no proof.
        assertEquals(404, call(HttpRequest.get(
            "http://x/state/proof?domain=ledger&key=" + PublicAddress.random().toHexString()).build()).getCode());
    }

    @Test
    void callReadonlyRevertsOnUnknownContract() throws Exception {
        // Contracts are wired; a call to a non-existent contract dry-runs to a clean revert.
        JSONObject body = new JSONObject().put("to", PublicAddress.random().toHexString());
        HttpResponse r = call(HttpRequest.post("http://x/call_readonly")
            .withBody(body.toString().getBytes(StandardCharsets.UTF_8)).build());
        assertEquals(200, r.getCode());
        assertFalse(json(r).getBoolean("success"));
    }
}
