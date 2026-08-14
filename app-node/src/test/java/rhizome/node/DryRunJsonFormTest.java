package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
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
import rhizome.vm.GasSchedule;
import rhizome.vm.InMemoryContractStore;
import rhizome.vm.WasmContractProcessor;
import rhizome.vm.WasmVm;

/**
 * Freezes the JSON form of the dry-run endpoint ({@code POST /call_readonly}) across the
 * three VM outcomes — OK, REVERTED, OUT_OF_GAS — the L23 verrou. Written before the
 * refactor that gives the contract outcome back its three states
 * ({@code ContractResult.Status} instead of a boolean + error String): the exact key set,
 * per-key value type, and the state-defining values are asserted through the real servlet,
 * so the refactor provably leaves the wire form untouched. Modeled on
 * {@link ExplorerJsonEquivalenceTest} (same harness, same legacy-oracle comparison).
 */
class DryRunJsonFormTest {

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
        return r.getBody().getString(StandardCharsets.UTF_8);
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
        try (var in = DryRunJsonFormTest.class.getResourceAsStream("/counter.wasm")) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private HttpResponse dryRun(PublicAddress to, long gasLimit) throws Exception {
        return call(HttpRequest.post("http://x/call_readonly")
            .withBody(new JSONObject()
                .put("to", to.toHexString())
                .put("input", "")
                .put("gasLimit", gasLimit).toString().getBytes(StandardCharsets.UTF_8))
            .build());
    }

    // ---- frozen-form assertions -----------------------------------------------------------

    /** The frozen key set: adding or renaming a field breaks the dashboard. */
    private static final Set<String> FROZEN_KEYS = Set.of("success", "output", "gasUsed", "error", "logs");

    private static void assertJsonHeaders(HttpResponse response) {
        assertEquals("application/json; charset=utf-8", response.getHeader(HttpHeaders.CONTENT_TYPE),
            "Content-Type must be application/json with a utf-8 charset");
        assertEquals("nosniff", response.getHeader(ApiResponses.H_XCTO),
            "X-Content-Type-Options must be nosniff");
    }

    private static void assertFrozenShape(JSONObject body) {
        assertEquals(FROZEN_KEYS, body.keySet(),
            "the dry-run body's key set is part of the frozen form");
        assertTrue(body.get("success") instanceof Boolean, "success must be a boolean");
        assertTrue(body.get("output") instanceof String, "output must be a hex string");
        assertTrue(body.get("gasUsed") instanceof Number, "gasUsed must be a number");
        assertTrue(body.isNull("error") || body.get("error") instanceof String,
            "error must be a string or null");
        assertTrue(body.get("logs") instanceof org.json.JSONArray, "logs must be an array");
    }

    // ---- the three outcomes -----------------------------------------------------------------

    @Test
    void okOutcomeServesTheFrozenForm() throws Exception {
        byte[] code = counterWasm();
        PublicAddress contract = Contracts.deriveAddress(sender, 0);
        apply(mineNext(List.of(signedContract(TransactionKind.DEPLOY, PublicAddress.empty(), code, 0))));
        // One real CALL so the counter is at 1 in committed state; the dry run then sees 2.
        apply(mineNext(List.of(signedContract(TransactionKind.CALL, contract, new byte[0], 1))));

        HttpResponse r = dryRun(contract, 5_000_000L);
        assertEquals(200, r.getCode());
        assertJsonHeaders(r);
        JSONObject body = new JSONObject(body(r));
        assertFrozenShape(body);
        assertTrue(body.getBoolean("success"), "a live counter call must succeed");
        assertEquals("0200000000000000", body.getString("output")); // 2, u64 LE
        assertTrue(body.getLong("gasUsed") > 0);
        assertTrue(body.isNull("error"), "a successful call carries no error");
        assertEquals(0, body.getJSONArray("logs").length());
    }

    @Test
    void revertedOutcomeServesTheFrozenForm() throws Exception {
        HttpResponse r = dryRun(PublicAddress.random(), 5_000_000L);
        assertEquals(200, r.getCode());
        assertJsonHeaders(r);
        JSONObject body = new JSONObject(body(r));
        assertFrozenShape(body);
        assertFalse(body.getBoolean("success"), "a call to an empty address must fail");
        assertEquals("", body.getString("output"));
        assertEquals(GasSchedule.CALL_BASE, body.getLong("gasUsed"),
            "the intrinsic CALL cost is charged on a reverted call");
        assertEquals("no contract at address", body.getString("error"));
        assertEquals(0, body.getJSONArray("logs").length());
    }

    @Test
    void outOfGasOutcomeServesTheFrozenForm() throws Exception {
        // A gasLimit below the intrinsic CALL charge saturates the meter and reports it.
        long limit = 100;
        HttpResponse r = dryRun(PublicAddress.random(), limit);
        assertEquals(200, r.getCode());
        assertJsonHeaders(r);
        JSONObject body = new JSONObject(body(r));
        assertFrozenShape(body);
        assertFalse(body.getBoolean("success"), "a call below the intrinsic charge must fail");
        assertEquals("", body.getString("output"));
        assertEquals(limit, body.getLong("gasUsed"), "the saturated meter reports the limit");
        assertEquals("out of gas for call", body.getString("error"));
        assertEquals(0, body.getJSONArray("logs").length());
    }
}
