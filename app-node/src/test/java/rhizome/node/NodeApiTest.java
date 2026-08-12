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
import rhizome.core.mempool.ExecutionStatus;
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
        NodeService gated = new NodeService(engine, mempool, AdmissionControl.builder()
            .submitPow(new RateLimiter(1, 3_600_000, 1)).build());
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
    void aggregateMempoolSignatureGateShedsTransactionsBeforeVerifying() throws Exception {
        // The fourth aggregate budget (audit M1): /add_transaction runs one inline Ed25519 verify
        // per admission and INVALID signatures are never cached, so replaying corrupt-signature
        // transactions re-pays the crypto every time. It is the only one of the four that had no
        // seam — the constructor ladder stopped at three limiters — so until AdmissionControl made
        // it injectable this budget had no test at all.
        NodeService gated = new NodeService(engine, mempool, AdmissionControl.builder()
            .mempoolSig(new RateLimiter(1, 3_600_000, 1)).build());
        var gatedServlet = NodeApi.servlet(eventloop, gated);

        assertEquals(200, callWith(gatedServlet, HttpRequest.post("http://x/add_transaction")
            .withBody(signedSend(100_000, 0).serialize().toBuffer()).build()).getCode());
        assertEquals(429, callWith(gatedServlet, HttpRequest.post("http://x/add_transaction")
            .withBody(signedSend(100_000, 1).serialize().toBuffer()).build()).getCode(),
            "an over-budget transaction must be shed before the signature is verified");
        assertEquals(1, gated.mempoolSize(), "the shed transaction never reached the pool");
    }

    @Test
    void aggregateReadGateShedsExplorerReadsPastTheGlobalBudget() throws Exception {
        // A distributed flood can stay within every per-IP budget yet sum to unbounded lock-guarded
        // block decodes on the event loop; the process-wide read gate caps the total (audit 5th-pass,
        // net Finding 2). Here the per-IP limiter is generous but the aggregate read budget is tiny.
        var lenientPerIp = new RateLimiter(1_000_000, 60_000, 100);
        NodeService node = new NodeService(engine, mempool, AdmissionControl.builder()
            .read(new RateLimiter(40, 3_600_000, 1)).build()); // aggregate read budget: 40 units/window
        var srv = NodeApi.servlet(eventloop, node, lenientPerIp);

        // /stats costs STATS_WINDOW (32): the first is admitted (gate charges before the handler),
        // the second (64 > 40) is shed with 429 regardless of the per-IP budget.
        assertNotEquals(429, callWith(srv, HttpRequest.get("http://x/stats").build()).getCode());
        assertEquals(429, callWith(srv, HttpRequest.get("http://x/stats").build()).getCode());
        // A read that does not decode blocks under the consensus lock is never charged to this budget.
        assertEquals(200, callWith(srv, HttpRequest.get("http://x/block_count").build()).getCode());
    }

    @Test
    void boxAndTokenListingsAreWeightedAndReadGatedLikeTheirLockAcquisitions() throws Exception {
        // /boxes fans out into one owner-index scan plus up to 100 per-id box reads, and
        // /tokens?holder= into one scan plus 100 tokenMeta plus 100 tokenBalance — ~101 and ~201
        // acquisitions of the consensus lock, against exactly one for /block. Both sat at the
        // default cost of 1 and outside the aggregate read gate, so they escaped both bounds.
        String owner = "AB".repeat(PublicAddress.SIZE);
        assertEquals(25, NodeApi.requestCost(HttpRequest.get("http://x/boxes?owner=" + owner).build()));
        assertEquals(50, NodeApi.requestCost(HttpRequest.get("http://x/tokens?holder=" + owner).build()));

        // …and they are charged to the process-wide gate, which is what bounds a distributed flood.
        NodeService node = new NodeService(engine, mempool, AdmissionControl.builder()
            .read(new RateLimiter(60, 3_600_000, 1)).build()); // aggregate read budget: 60 units/window
        var srv = NodeApi.servlet(eventloop, node, new RateLimiter(1_000_000, 60_000, 100));
        assertNotEquals(429, callWith(srv, HttpRequest.get("http://x/tokens?holder=" + owner).build()).getCode());
        assertEquals(429, callWith(srv, HttpRequest.get("http://x/tokens?holder=" + owner).build()).getCode());

        // /scan/boxes stays weighted but UNGATED on purpose: ChainEngine.scanBoxes runs outside the
        // consensus lock behind the stamp seqlock, so it contends nothing this gate protects.
        assertEquals(NodeService.BOX_SCAN_WINDOW / 4,
            NodeApi.requestCost(HttpRequest.get("http://x/scan/boxes?scanId=x").build()));
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
    void addPeerIsWeightedForItsBlockingDnsWork() {
        // /add_peer queues a blocking DNS resolve on a single off-loop thread behind a bounded
        // queue — the flat cost of 1 let one IP enqueue ~1000 resolves/s (audit: add_peer cost
        // vs blocking DNS). It is weighted like /submit (8).
        assertEquals(8, NodeApi.requestCost(HttpRequest.post("http://x/add_peer").build()));
        // /state/snapshot/chunk falls back to the flat 75 when the chunk is not resolvable at the
        // gate (no snapshot materialised here) — the handler answers 404 without serving bytes.
        assertEquals(75, NodeApi.requestCost(HttpRequest.get("http://x/state/snapshot/chunk?index=0").build()));
    }

    @Test
    void readonlyGasGateShedsCallsOnceTheGlobalBudgetIsSpent() {
        // Aggregate (all-IP) dry-run gas budget: with a global budget of 100 gas/window, the first
        // call reserving 60 is admitted and the second is shed (the /call_readonly handler then
        // returns HTTP 429) WITHOUT running the VM on the event loop — the aggregate cap the per-IP
        // limiter lacks for /call_readonly (audit 5th-pass, net Finding 1).
        NodeService gated = new NodeService(engine, mempool, AdmissionControl.builder()
            .readonlyGas(new RateLimiter(100, 3_600_000, 1)).build());
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

        // Default-port normalization (audit: CSRF default-port false positive): a browser omits
        // the scheme default port in Origin while a proxy/explicit client may include it in Host
        // — these are still the SAME origin and must not be refused as cross-site. (Each passing
        // case submits a fresh transaction: a duplicate body would be a 400 ALREADY_IN_QUEUE.)
        assertEquals(200, call(HttpRequest.post("http://x/add_transaction")
            .withHeader(origin, "http://x").withHeader(host, "x:80").withHeader(marker, "1")
            .withBody(signedSend(100_000, 1).serialize().toBuffer()).build()).getCode());
        assertEquals(200, call(HttpRequest.post("http://x/add_transaction")
            .withHeader(origin, "https://x").withHeader(host, "x:443").withHeader(marker, "1")
            .withBody(signedSend(100_000, 2).serialize().toBuffer()).build()).getCode());
        assertEquals(200, call(HttpRequest.post("http://x/add_transaction")
            .withHeader(origin, "http://x:3000").withHeader(host, "x:3000").withHeader(marker, "1")
            .withBody(signedSend(100_000, 3).serialize().toBuffer()).build()).getCode());
        // A genuinely different port is still cross-site (fail-closed on mismatch).
        assertEquals(403, call(HttpRequest.post("http://x/add_transaction")
            .withHeader(origin, "http://x").withHeader(host, "x:3000").withHeader(marker, "1")
            .withBody(signedSend(100_000, 4).serialize().toBuffer()).build()).getCode());

        // A non-browser client (no Origin — a peer/CLI) is never blocked.
        assertEquals(200, call(HttpRequest.post("http://x/add_transaction")
            .withBody(signedSend(100_000, 5).serialize().toBuffer()).build()).getCode());
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
        var node = new NodeService(engine, mempool, NodeSources.builder()
            .logSource(h -> h == 1 ? List.of(log) : List.of()).build());
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

    @Test
    void orphanEndpointServesStoredUncleBodies() throws Exception {
        // GET /orphan?hash=<hex64> serves the binary body of a known orphan (uncle) — the piece
        // a syncing peer fetches when a block references an uncle its own pool lacks (audit:
        // uncle-sync blocker). 404 when unknown, 400 on a malformed hash.
        var store = new InMemoryChainStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        var localEngine = ChainEngine.init(params, new InMemoryLedger(), store,
            snapshot, null, clock::get, new SignatureVerifier());
        var node = new NodeService(localEngine, mempool);
        var s = NodeApi.servlet(eventloop, node);

        Block orphan = mineNext(List.of()); // valid block, never applied: just bytes for the test
        store.putUncle(orphan.hash(), orphan);

        HttpResponse found = callWith(s,
            HttpRequest.get("http://x/orphan?hash=" + orphan.hash().toHexString()).build());
        assertEquals(200, found.getCode());
        assertEquals(orphan.hash(), BlockCodec.decode(found.getBody().getArray()).hash());

        assertEquals(404, callWith(s, HttpRequest.get("http://x/orphan?hash="
            + rhizome.crypto.SHA256Hash.random().toHexString()).build()).getCode());
        assertEquals(400, callWith(s, HttpRequest.get("http://x/orphan?hash=zz").build()).getCode());
    }

    @Test
    void tokenIsCheckedBeforeTheAggregateBudgetsAreConsumed() throws Exception {
        // Auth BEFORE the global gates (audit: auth after budgets): unauthenticated requests must
        // get a cheap 401 WITHOUT burning the shared submit budget that gated peers depend on —
        // otherwise an unauthenticated flood starves the authenticated ones.
        NodeService gated = new NodeService(engine, mempool, AdmissionControl.builder()
            .submitPow(new RateLimiter(1, 3_600_000, 1)).build());
        var s = NodeApi.servlet(eventloop, gated, new RateLimiter(1_000_000, 60_000, 100),
            null, null, "s3cret");
        var auth = io.activej.http.HttpHeaders.AUTHORIZATION;

        assertEquals(401, callWith(s, HttpRequest.post("http://x/submit")
            .withBody(BlockCodec.encode(mineNext(List.of()))).build()).getCode());
        assertEquals(401, callWith(s, HttpRequest.post("http://x/submit")
            .withBody(BlockCodec.encode(mineNext(List.of()))).build()).getCode(),
            "repeated 401s: the budget was never consumed by unauthenticated requests");

        // The budget (1 per window) is still intact for the authenticated peer.
        assertEquals(200, callWith(s, HttpRequest.post("http://x/submit")
            .withHeader(auth, "Bearer s3cret")
            .withBody(BlockCodec.encode(mineNext(List.of()))).build()).getCode());
        assertEquals(429, callWith(s, HttpRequest.post("http://x/submit")
            .withHeader(auth, "Bearer s3cret")
            .withBody(BlockCodec.encode(mineNext(List.of()))).build()).getCode(),
            "only now is the aggregate budget spent");
    }

    @Test
    void syncAndSnapshotChunkAreChargedToTheAggregateReadGate() throws Exception {
        // /sync, /headers and /state/snapshot/chunk join the aggregate read budget (audit:
        // aggregate bound on the sync/snapshot serving paths): a distributed flood of "peers"
        // must not sum to unbounded lock-guarded reads and egress at cost ~1.
        var lenientPerIp = new RateLimiter(1_000_000, 60_000, 100);
        NodeService node = new NodeService(engine, mempool, AdmissionControl.builder()
            .read(new RateLimiter(1, 3_600_000, 1)).build()); // aggregate read budget: a single unit
        var s = NodeApi.servlet(eventloop, node, lenientPerIp);

        // /sync?start=1&end=1 costs 1: admitted once, then the aggregate gate sheds.
        assertEquals(200, callWith(s, HttpRequest.get("http://x/sync?start=1&end=1").build()).getCode());
        assertEquals(429, callWith(s, HttpRequest.get("http://x/sync?start=1&end=1").build()).getCode());
        // A snapshot chunk costs 75 — far over the spent budget: shed at the gate, not 404.
        assertEquals(429, callWith(s,
            HttpRequest.get("http://x/state/snapshot/chunk?index=0").build()).getCode());
    }

    @Test
    void pushAbuseAccumulatesStrikesAndGetsShedEarly() throws Exception {
        // Gossip push ban-score (audit): a client spamming /add_transaction with provable junk
        // accumulates strikes and is shed with 429 BEFORE the body is decoded, for the window.
        var node = new NodeService(engine, mempool);
        var s = NodeApi.servlet(eventloop, node);
        for (int i = 0; i < PushStrikeTable.STRIKE_LIMIT; i++) {
            Transaction t = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(100),
                key, new TransactionAmount(0), 1000L, params.chainId() + 5, 0); // wrong chain-id
            t.sign(priv);
            assertEquals(400, callWith(s, HttpRequest.post("http://x/add_transaction")
                .withBody(t.serialize().toBuffer()).build()).getCode(), "fault " + i);
        }
        // Over the threshold: shed before decode — the status would have been another 400.
        HttpResponse shed = callWith(s, HttpRequest.post("http://x/add_transaction")
            .withBody(signedSend(100, 99).serialize().toBuffer()).build());
        assertEquals(429, shed.getCode(), "a push-abuser is shed before the body is decoded");

        // Race-benign outcomes never strike: a duplicate tx (ALREADY_IN_QUEUE) is honest gossip.
        var node2 = new NodeService(engine, mempool);
        Transaction t = signedSend(100_000, 0);
        assertEquals(ExecutionStatus.SUCCESS, node2.submitTransaction(t, "peer-a"));
        assertEquals(ExecutionStatus.ALREADY_IN_QUEUE, node2.submitTransaction(t, "peer-a"));
        assertEquals(0, node2.pushStrikeCount("peer-a"), "duplicates must not strike");
        assertFalse(node2.isPushShed("peer-a"));
    }

    @Test
    void aFullStrikeTableStillTracksTheClientOffendingNow() {
        // The strike table is bounded, and once it was full every NEW client's faults went to a
        // shared overflow bucket that isPushShed never read — so the shed silently stopped
        // applying to every fresh IP (audit I-2, fail-open). Filling the table must not buy an
        // attacker immunity: the newcomer is tracked (the stalest window is evicted to make
        // room) and sheds on schedule.
        var node = new NodeService(engine, mempool);
        Transaction junk = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(100),
            key, new TransactionAmount(0), 1000L, params.chainId() + 5, 0); // wrong chain-id
        junk.sign(priv);
        for (int i = 0; i < PushStrikeTable.MAX_KEYS; i++) {
            assertEquals(ExecutionStatus.INVALID_CHAIN_ID, node.submitTransaction(junk, "filler-" + i));
        }
        assertEquals(1, node.pushStrikeCount("filler-0"), "the table is full and every filler tracked");

        for (int i = 0; i <= PushStrikeTable.STRIKE_LIMIT; i++) {
            assertEquals(ExecutionStatus.INVALID_CHAIN_ID, node.submitTransaction(junk, "latecomer"));
        }
        assertEquals(PushStrikeTable.STRIKE_LIMIT + 1, node.pushStrikeCount("latecomer"),
            "a client arriving after the table filled must still accumulate its own strikes");
        assertTrue(node.isPushShed("latecomer"),
            "filling the strike table with 8192 keys must not exempt the next abuser from the shed");
    }

    @Test
    void outOfIntRangeIndexesAreRejectedBeforeTheCast() throws Exception {
        // long→int casts on request indexes must be bounds-checked first (audit: unchecked
        // index cast): an over-range value used to wrap into a valid-looking int.
        assertEquals(400, call(
            HttpRequest.get("http://x/state/snapshot/chunk?index=9999999999").build()).getCode());
        assertEquals(400, call(
            HttpRequest.get("http://x/state/snapshot/chunk?index=-5").build()).getCode());
        assertEquals(400, call(
            HttpRequest.get("http://x/scan/boxes?scanId=9999999999").build()).getCode());
    }

    @Test
    void callReadonlyValidatesValueAndSenderBeforeAnyWork() throws Exception {
        // /call_readonly input validation (audit: readonly input validation): a negative or
        // absurd value and a malformed sender are cheap 400s — even with no VM wired (503).
        String to = "00".repeat(25);
        assertEquals(400, call(HttpRequest.post("http://x/call_readonly")
            .withBody(("{\"to\":\"" + to + "\",\"value\":-5}").getBytes()).build()).getCode());
        assertEquals(400, call(HttpRequest.post("http://x/call_readonly")
            .withBody(("{\"to\":\"" + to + "\",\"value\":" + ((1L << 62) + 1) + "}")
                .getBytes()).build()).getCode());
        assertEquals(400, call(HttpRequest.post("http://x/call_readonly")
            .withBody(("{\"to\":\"" + to + "\",\"from\":\"zz\"}").getBytes()).build()).getCode());
        // Well-formed input reaches the availability check (no contracts wired in this harness).
        assertEquals(503, call(HttpRequest.post("http://x/call_readonly")
            .withBody(("{\"to\":\"" + to + "\"}").getBytes()).build()).getCode());
    }

    @Test
    void dryRunIsShedWith503WhenTheAdmissionSlotsAreFull() throws Exception {
        // The dry-run backlog is bounded at ADMISSION (AdmissionControl.MAX_CONCURRENT_DRY_RUNS
        // permits taken before the consensus lock): once every slot is running or parked on
        // the lock, the next call must be shed immediately — Optional.empty here, 503 at the
        // API — instead of queueing another worker behind a 25M-gas VM run (audit).
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocking = new rhizome.core.blockchain.ContractProcessor() {
            @Override public void begin() {}
            @Override public ContractResult run(PublicAddress from, rhizome.core.transaction.TransactionKind kind,
                                                PublicAddress to, byte[] data, long value, long gasLimit, long nonce) {
                throw new UnsupportedOperationException();
            }
            @Override public void commit(long blockHeight) {}
            @Override public void discard() {}
            @Override public void revertBlock(long blockHeight) {}
            @Override public List<ContractReceipt> receipts(long blockHeight) {
                return List.of();
            }
            @Override public ContractResult dryRun(PublicAddress from, PublicAddress to, byte[] input,
                                                   long value, long gasLimit) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ContractResult.ok(1, new byte[0], null);
            }
        };
        var node = new NodeService(engine, mempool, NodeSources.builder().contracts(blocking).build());

        // Occupy every admission slot: the first dry-run blocks inside the consensus lock, the
        // rest park on it holding their permits.
        var results = new java.util.concurrent.ConcurrentLinkedQueue<
            java.util.Optional<rhizome.core.blockchain.ContractProcessor.ContractResult>>();
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < AdmissionControl.MAX_CONCURRENT_DRY_RUNS; i++) {
            Thread t = new Thread(() -> results.add(node.dryRun(PublicAddress.empty(),
                PublicAddress.empty(), new byte[0], 0, 1_000_000L)));
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS),
            "the first dry-run must be running inside the consensus lock");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (node.dryRunSlotsAvailable() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, node.dryRunSlotsAvailable(), "all dry-run admission slots are taken");

        // The next dry-run sheds immediately, and the API maps the shed to 503 (retryable).
        assertTrue(node.dryRun(PublicAddress.empty(), PublicAddress.empty(), new byte[0], 0,
            1_000_000L).isEmpty(), "a dry-run past the bound must not queue");
        String to = "00".repeat(25);
        assertEquals(503, ContractApi.callReadonly(node, new JSONObject()
            .put("to", to).put("gasLimit", 1_000_000L)).getCode());

        // Draining releases every slot: the parked dry-runs complete and new ones are admitted.
        release.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertEquals(AdmissionControl.MAX_CONCURRENT_DRY_RUNS, results.size());
        for (var r : results) {
            assertTrue(r.isPresent(), "an admitted dry-run completes once the lock frees up");
        }
        assertEquals(AdmissionControl.MAX_CONCURRENT_DRY_RUNS, node.dryRunSlotsAvailable());
        assertTrue(node.dryRun(PublicAddress.empty(), PublicAddress.empty(), new byte[0], 0,
            1_000_000L).isPresent());
    }
}
