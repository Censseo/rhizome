package rhizome.node;

import rhizome.net.RateLimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.bytebuf.ByteBuf;
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
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
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

class NodeApiTest {

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
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        var verifier = new SignatureVerifier();
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, clock::get, verifier);
        mempool = new MemPool(params, verifier, engine, 1000);
        var node = new NodeService(engine, mempool);
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

    /** Drives one request through the servlet on the eventloop and returns the response with its body loaded. */
    private HttpResponse call(HttpRequest request) throws Exception {
        return callWith(servlet, request);
    }

    private HttpResponse callWith(io.activej.http.AsyncServlet s, HttpRequest request) throws Exception {
        return eventloop.<HttpResponse>submit(() ->
            s.serve(request).then(resp -> resp.loadBody().map($ -> resp))
        ).get();
    }

    @Test
    void rateLimitReturns429OverTheLimit() throws Exception {
        var limited = NodeApi.servlet(eventloop, new NodeService(engine, mempool),
            new RateLimiter(2, 60_000, 100));
        assertEquals(200, callWith(limited, HttpRequest.get("http://x/block_count").build()).getCode());
        assertEquals(200, callWith(limited, HttpRequest.get("http://x/block_count").build()).getCode());
        assertEquals(429, callWith(limited, HttpRequest.get("http://x/block_count").build()).getCode());
    }

    @Test
    void oversizedBodyIsRejectedNotBuffered() throws Exception {
        byte[] huge = new byte[64 * 1024]; // well over the /add_transaction cap
        assertEquals(400, call(HttpRequest.post("http://x/add_transaction").withBody(huge).build()).getCode());
    }

    private static String body(HttpResponse r) {
        return r.getBody().getString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Transaction signedSend(long amount, long nonce) {
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(amount),
            key, new TransactionAmount(0), 1000L + nonce, params.chainId(), nonce);
        t.sign(priv);
        return t;
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

    @Test
    void blockCountAndWallet() throws Exception {
        HttpResponse count = call(HttpRequest.get("http://x/block_count").build());
        assertEquals(200, count.getCode());
        assertEquals("1", body(count));

        HttpResponse wallet = call(HttpRequest.get("http://x/wallet?address=" + sender.toHexString()).build());
        assertEquals(200, wallet.getCode());
        JSONObject json = new JSONObject(body(wallet));
        assertEquals(1_000_000L, json.getLong("balance"));
        assertEquals(0, json.getLong("nextNonce"));
    }

    @Test
    void submitPowGateShedsBlocksBeforeTheBodyIsDecoded() throws Exception {
        // A global budget of one submit per (long) window, gated at the HTTP boundary BEFORE the block
        // body is decoded (audit F1 + S6): the first /submit is accepted; the second is shed with 429
        // without decoding the body or touching the chain — the aggregate anti-DoS cap the per-IP
        // limiter lacks, now applied ahead of the decode rather than after it.
        NodeService gated = new NodeService(engine, mempool, new RateLimiter(1, 3_600_000, 1));
        var gatedServlet = NodeApi.servlet(eventloop, gated);

        HttpResponse first = callWith(gatedServlet,
            HttpRequest.post("http://x/submit").withBody(BlockCodec.encode(mineNext(List.of()))).build());
        assertEquals(200, first.getCode());
        long height = engine.height();

        HttpResponse second = callWith(gatedServlet,
            HttpRequest.post("http://x/submit").withBody(BlockCodec.encode(mineNext(List.of()))).build());
        assertEquals(429, second.getCode(), "an over-budget submit must be shed with 429");
        assertEquals(height, engine.height(), "a throttled submit must not extend the chain");
    }

    @Test
    void aggregateReadGateShedsExplorerReadsPastTheGlobalBudget() throws Exception {
        // A distributed flood can stay within every per-IP budget yet sum to unbounded lock-guarded
        // block decodes on the event loop; the process-wide read gate caps the total (audit 5th-pass,
        // net Finding 2). Here the per-IP limiter is generous but the aggregate read budget is tiny.
        var lenientPerIp = new RateLimiter(1_000_000, 60_000, 100);
        NodeService node = new NodeService(engine, mempool,
            new RateLimiter(NodeService.SUBMIT_POW_MAX_PER_SEC, 1000, 1),
            new RateLimiter(NodeService.READONLY_GAS_MAX_PER_SEC, 1000, 1),
            new RateLimiter(40, 3_600_000, 1)); // aggregate read budget: 40 units/window
        var srv = NodeApi.servlet(eventloop, node, lenientPerIp);

        // /stats costs STATS_WINDOW (32): the first is admitted (gate charges before the handler),
        // the second (64 > 40) is shed with 429 regardless of the per-IP budget.
        assertNotEquals(429, callWith(srv, HttpRequest.get("http://x/stats").build()).getCode());
        assertEquals(429, callWith(srv, HttpRequest.get("http://x/stats").build()).getCode());
        // A read that does not decode blocks under the consensus lock is never charged to this budget.
        assertEquals(200, callWith(srv, HttpRequest.get("http://x/block_count").build()).getCode());
    }

    @Test
    void explorerScanEndpointsAreWeightedByBlocksActuallyDecoded() {
        // /transaction and /address_txs decode up to `depth` FULL blocks under the consensus lock
        // (ExplorerApi.findTransaction / addressTransactions), the same cost class as /blocks and /stats.
        // They must be weighted ~1 unit per block, not the light header-scan rate (depth/20) that
        // under-charged them ~20x and let one IP drive ~20x the lock-guarded decodes the read gate admits.
        assertEquals(1000, NodeApi.requestCost(HttpRequest.get("http://x/transaction?depth=1000&txid=deadbeef").build()));
        assertEquals(1000, NodeApi.requestCost(HttpRequest.get("http://x/address_txs?depth=1000&address=ab").build()));
        assertEquals(250, NodeApi.requestCost(HttpRequest.get("http://x/transaction").build())); // SCAN_DEPTH_DEFAULT
        // Depth is clamped to the scan cap before being charged, so an over-large depth cannot be cheap.
        assertEquals(ExplorerApi.SCAN_DEPTH_MAX,
            NodeApi.requestCost(HttpRequest.get("http://x/transaction?depth=1000000&txid=x").build()));
        // A plain read that decodes no blocks stays at cost 1.
        assertEquals(1, NodeApi.requestCost(HttpRequest.get("http://x/block_count").build()));
    }

    @Test
    void readonlyGasGateShedsCallsOnceTheGlobalBudgetIsSpent() {
        // Aggregate (all-IP) dry-run gas budget: with a global budget of 100 gas/window, the first
        // call reserving 60 is admitted and the second is shed (the /call_readonly handler then
        // returns HTTP 429) WITHOUT running the VM on the event loop — the aggregate cap the per-IP
        // limiter lacks for /call_readonly (audit 5th-pass, net Finding 1).
        NodeService gated = new NodeService(engine, mempool,
            new RateLimiter(NodeService.SUBMIT_POW_MAX_PER_SEC, 1000, 1),
            new RateLimiter(100, 3_600_000, 1));
        assertTrue(gated.tryReadonlyGasBudget(60), "first call fits the budget");
        assertFalse(gated.tryReadonlyGasBudget(60), "second call is over the aggregate budget");
    }

    @Test
    void browserPostIsRefusedUnlessSameOriginWithTheCsrfHeader() throws Exception {
        // Use the well-known Origin/Host tokens (the interned ones the guard and the real HTTP parser
        // use), and set Host explicitly the way every browser does — so this exercises the guard the
        // same way a parsed network request would, independent of ActiveJ's URL-derived-Host behavior.
        var origin = io.activej.http.HttpHeaders.ORIGIN;
        var host = io.activej.http.HttpHeaders.HOST;
        var marker = io.activej.http.HttpHeaders.of("X-Rhizome-Request");
        Transaction t = signedSend(100_000, 0);

        // A browser POST (carries Origin) that is same-origin but lacks the custom header is refused
        // — this is the DNS-rebinding case the plain Origin==Host check used to let through.
        assertEquals(403, call(HttpRequest.post("http://x/add_transaction")
            .withHeader(origin, "http://x").withHeader(host, "x")
            .withBody(t.serialize().toBuffer()).build()).getCode());

        // Cross-origin is refused regardless of the header.
        assertEquals(403, call(HttpRequest.post("http://x/add_transaction")
            .withHeader(origin, "http://evil").withHeader(host, "x").withHeader(marker, "1")
            .withBody(t.serialize().toBuffer()).build()).getCode());

        // Same-origin WITH the custom header passes the guard (the dashboard's own requests).
        assertEquals(200, call(HttpRequest.post("http://x/add_transaction")
            .withHeader(origin, "http://x").withHeader(host, "x").withHeader(marker, "1")
            .withBody(t.serialize().toBuffer()).build()).getCode());

        // A non-browser client (no Origin — a peer/CLI) is never blocked.
        assertEquals(200, call(HttpRequest.post("http://x/add_transaction")
            .withBody(signedSend(100_000, 1).serialize().toBuffer()).build()).getCode());
    }

    @Test
    void submitTransactionThenBlockUpdatesState() throws Exception {
        Transaction t = signedSend(100_000, 0);
        HttpResponse add = call(HttpRequest.post("http://x/add_transaction")
            .withBody(t.serialize().toBuffer()).build());
        assertEquals(200, add.getCode());
        assertEquals("SUCCESS", new JSONObject(body(add)).getString("status"));

        HttpResponse pool = call(HttpRequest.get("http://x/mempool").build());
        assertEquals(1, new JSONObject(body(pool)).getInt("size"));

        HttpResponse submit = call(HttpRequest.post("http://x/submit")
            .withBody(BlockCodec.encode(mineNext(List.of(t)))).build());
        assertEquals(200, submit.getCode());
        assertEquals("SUCCESS", new JSONObject(body(submit)).getString("status"));

        HttpResponse count = call(HttpRequest.get("http://x/block_count").build());
        assertEquals("2", body(count));
        // Mempool purged after inclusion.
        assertEquals(0, new JSONObject(body(call(HttpRequest.get("http://x/mempool").build()))).getInt("size"));
    }

    @Test
    void syncReturnsDecodableBlocks() throws Exception {
        call(HttpRequest.post("http://x/submit").withBody(BlockCodec.encode(mineNext(List.of()))).build());
        call(HttpRequest.post("http://x/submit").withBody(BlockCodec.encode(mineNext(List.of()))).build());
        assertEquals(3, engine.height());

        HttpResponse sync = call(HttpRequest.get("http://x/sync?start=2&end=3").build());
        assertEquals(200, sync.getCode());
        byte[] bytes = sync.getBody().getArray();
        // /sync streams a window of concatenated blocks: decode it with decodeAll. (The single-object
        // BlockCodec.decode now rejects trailing bytes, so it must not be used on a multi-block body.)
        var blocks = BlockCodec.decodeAll(bytes);
        assertEquals(2, blocks.size());
        assertEquals(2, ((BlockImpl) blocks.get(0)).id());
        assertEquals(3, ((BlockImpl) blocks.get(1)).id());
    }

    @Test
    void badInputAlwaysGets400NeverCrashes() throws Exception {
        assertEquals(400, call(HttpRequest.get("http://x/block?blockId=99999").build()).getCode());
        assertEquals(400, call(HttpRequest.get("http://x/block?blockId=notanumber").build()).getCode());
        assertEquals(400, call(HttpRequest.get("http://x/block").build()).getCode()); // missing param
        assertEquals(400, call(HttpRequest.get("http://x/wallet?address=zzz").build()).getCode());
        assertEquals(400, call(HttpRequest.get("http://x/sync?start=1&end=999999").build()).getCode()); // range too large
        assertEquals(400, call(HttpRequest.post("http://x/submit").withBody(new byte[]{1, 2, 3}).build()).getCode());
    }

    @Test
    void logsEndpointReturnsContractLogsByHeightAndCursor() throws Exception {
        var contract = PublicAddress.random();
        var log = new rhizome.core.blockchain.ContractProcessor.ContractLog(
            contract, "count".getBytes(), new byte[] {1, 0, 0, 0, 0, 0, 0, 0});
        var node = new NodeService(engine, mempool);
        node.setLogSource(h -> h == 1 ? List.of(log) : List.of());
        var s = NodeApi.servlet(eventloop, node);

        // ?height=1 → that block's logs.
        JSONObject byHeight = new JSONObject(body(callWith(s, HttpRequest.get("http://x/logs?height=1").build())));
        assertEquals(1, byHeight.getJSONArray("logs").length());
        JSONObject entry = byHeight.getJSONArray("logs").getJSONObject(0);
        assertEquals(contract.toHexString(), entry.getString("contract"));
        assertEquals("636f756e74", entry.getString("topic")); // "count" in hex

        // ?fromHeight=1 → cursor scan tags each log with its height and reports toHeight.
        JSONObject page = new JSONObject(body(callWith(s, HttpRequest.get("http://x/logs?fromHeight=1").build())));
        assertEquals(1, page.getLong("toHeight"));
        assertEquals(1, page.getJSONArray("logs").getJSONObject(0).getLong("height"));

        // Out-of-range height is a clean 400.
        assertEquals(400, callWith(s, HttpRequest.get("http://x/logs?height=999").build()).getCode());
    }

    @Test
    void rejectsInvalidTransactionWith400() throws Exception {
        Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(100),
            key, new TransactionAmount(0), 1000L, params.chainId() + 5, 0); // wrong chain-id
        t.sign(priv);
        HttpResponse add = call(HttpRequest.post("http://x/add_transaction")
            .withBody(t.serialize().toBuffer()).build());
        assertEquals(400, add.getCode());
        assertEquals("INVALID_CHAIN_ID", new JSONObject(body(add)).getString("status"));
    }
}
