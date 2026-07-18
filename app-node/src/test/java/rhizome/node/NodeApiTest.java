package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.generateKeyPair;

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
import rhizome.core.common.PowAlgorithm;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
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
        var buffer = java.nio.ByteBuffer.wrap(bytes);
        Block b2 = BlockCodec.decode(java.util.Arrays.copyOfRange(bytes, 0, bytes.length));
        assertEquals(2, ((BlockImpl) b2).id());
        assertTrue(bytes.length > 0);
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
