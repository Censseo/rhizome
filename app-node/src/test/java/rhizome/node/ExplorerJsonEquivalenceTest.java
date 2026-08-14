package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.Contracts;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SignatureVerifier;
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
 * Equivalence between {@link ExplorerApi}'s {@link rhizome.core.serialization.JsonSink}-based
 * writers and the legacy {@code org.json}-tree construction they replaced, driven through the
 * real servlet exactly the way {@link DashboardApiTest} does. Each test builds a "legacy" oracle
 * {@link JSONObject} with the pre-migration {@code toJson()}-based logic (copied verbatim from
 * the pre-migration {@code ExplorerApi} source), calls the live endpoint, and asserts the two are
 * {@link JSONObject#similar} — never byte-identical, since {@code org.json}'s {@code HashMap}
 * backing means field order was never a contract (see {@code JsonWriterEquivalenceTest} in
 * lib-core for the same reasoning).
 *
 * <p>Every assertion also checks the response headers: {@code Content-Type} must be
 * {@code application/json; charset=utf-8} and {@code X-Content-Type-Options} must be
 * {@code nosniff} — the {@link ApiResponses#json(rhizome.core.serialization.JsonSink)} overload
 * builds the response with a different ActiveJ builder call shape than
 * {@link ApiResponses#json(JSONObject)} did, so this is not a given.
 */
class ExplorerJsonEquivalenceTest {

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
        try (var in = ExplorerJsonEquivalenceTest.class.getResourceAsStream("/counter.wasm")) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---- shared assertion helpers -------------------------------------------------------------

    private static void assertJsonHeaders(HttpResponse response) {
        assertEquals("application/json; charset=utf-8", response.getHeader(HttpHeaders.CONTENT_TYPE),
            "Content-Type must be application/json with a utf-8 charset");
        assertEquals("nosniff", response.getHeader(ApiResponses.H_XCTO),
            "X-Content-Type-Options must be nosniff");
    }

    /** Structural equivalence, not byte equality: same key set, same type per shared key (the
     *  dangerous mismatch is String vs Number), then a value-level {@code similar} check with an
     *  actual diagnostic on failure ({@code similar} alone swallows exceptions silently). */
    private static void assertSameJson(JSONObject legacy, HttpResponse response) {
        assertJsonHeaders(response);
        JSONObject actual = new JSONObject(body(response));

        assertEquals(legacy.keySet(), actual.keySet(),
            () -> "key set mismatch: legacy=" + legacy.keySet() + " actual=" + actual.keySet());

        for (String k : legacy.keySet()) {
            Object legacyValue = legacy.get(k);
            Object actualValue = actual.get(k);
            String legacyType = typeCategory(legacyValue);
            String actualType = typeCategory(actualValue);
            assertEquals(legacyType, actualType, () -> "type mismatch for key '" + k + "': legacy="
                + legacyValue + " (" + legacyValue.getClass().getSimpleName() + "), actual="
                + actualValue + " (" + actualValue.getClass().getSimpleName() + ")");
        }

        assertTrue(legacy.similar(actual), () -> "json bodies differ: " + firstDiff(legacy, actual));
    }

    private static String typeCategory(Object value) {
        if (JSONObject.NULL.equals(value)) {
            return "null";
        }
        if (value instanceof String) {
            return "String";
        }
        if (value instanceof Boolean) {
            return "Boolean";
        }
        if (value instanceof Number) {
            return "Number";
        }
        return value.getClass().getName();
    }

    private static String firstDiff(JSONObject legacy, JSONObject actual) {
        for (String key : legacy.keySet()) {
            Object l = legacy.get(key);
            Object a = actual.opt(key);
            if (!Objects.equals(l, a)) {
                return "key '" + key + "': legacy=" + l + " actual=" + a;
            }
        }
        return "no single differing top-level key found (nested structure differs)";
    }

    // ---- legacy oracles: the pre-migration toJson()-based construction, copied verbatim -------

    private static JSONObject legacyBlocks(NodeService node, long start, long end) {
        long cappedEnd = Math.min(end, node.blockCount());
        JSONArray arr = new JSONArray();
        for (long h = start; h <= cappedEnd; h++) {
            var block = (BlockImpl) node.block(h);
            arr.put(new JSONObject()
                .put("height", h)
                .put("hash", block.hash().toHexString())
                .put("timestamp", block.timestamp())
                .put("difficulty", block.difficulty())
                .put("txCount", block.transactions().size())
                .put("uncles", block.uncles().size()));
        }
        return new JSONObject().put("blocks", arr).put("height", node.blockCount());
    }

    private static JSONObject legacyWallet(NodeService node, PublicAddress wallet) {
        return new JSONObject()
            .put("address", wallet.toHexString())
            .put("balance", node.balance(wallet))
            .put("nextNonce", node.nextNonce(wallet));
    }

    private static JSONObject legacyTransaction(long height, Transaction t) {
        return new JSONObject()
            .put("height", height)
            .put("transaction", t.toJson());
    }

    private static JSONObject legacyAddressTxs(NodeService node, PublicAddress address, long floor, long tip) {
        JSONArray arr = new JSONArray();
        for (long h = tip; h >= floor && arr.length() < ExplorerApi.ADDRESS_TXS_MAX; h--) {
            for (Transaction t : node.block(h).transactions()) {
                if ((address.equals(t.from()) || address.equals(t.to())) && arr.length() < ExplorerApi.ADDRESS_TXS_MAX) {
                    arr.put(t.toJson().put("height", h));
                }
            }
        }
        return new JSONObject()
            .put("address", address.toHexString())
            .put("scannedFrom", floor)
            .put("scannedTo", tip)
            .put("transactions", arr);
    }

    private static JSONObject legacyContract(PublicAddress address, byte[] code, long balance) {
        JSONObject out = new JSONObject()
            .put("address", address.toHexString())
            .put("exists", code != null)
            .put("balance", balance);
        if (code != null) {
            out.put("codeSize", code.length).put("codeHash", legacySha256Hex(code));
        }
        return out;
    }

    /** {@code ApiResponses.hex}'s legacy encoder (lowercase, {@code Character.forDigit}), copied
     *  here rather than reused so the oracle does not depend on the migrated production code. */
    private static String legacySha256Hex(byte[] bytes) {
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ---- /blocks --------------------------------------------------------------------------------

    @Test
    void blocksEndpointMatchesLegacy() throws Exception {
        apply(mineNext(List.of(signedSend(1000, 0))));
        apply(mineNext(List.of()));

        JSONObject legacy = legacyBlocks(node, 1, 2);
        HttpResponse response = call(HttpRequest.get("http://x/blocks?start=1&end=2").build());
        assertEquals(200, response.getCode());
        assertSameJson(legacy, response);
    }

    // ---- /block (hash round-trip protected) --------------------------------------------------

    @Test
    void blockEndpointMatchesLegacyAndHashRoundTrips() throws Exception {
        apply(mineNext(List.of(signedSend(1000, 0))));
        long id = engine.height();

        JSONObject legacy = node.block(id).toJson();
        HttpResponse response = call(HttpRequest.get("http://x/block?blockId=" + id).build());
        assertEquals(200, response.getCode());
        assertSameJson(legacy, response);

        // ChainSynchronizer's fork-point bisection re-derives a header hash from exactly these
        // bytes (HttpPeerSource.blockHash -> Block.of(PeerJson.parseObject(body)).hash()) — a
        // written block that round-trips to a different hash would be a consensus bug.
        Block roundTripped = Block.of(new JSONObject(body(response)));
        assertEquals(node.block(id).hash(), roundTripped.hash());
    }

    // ---- /wallet --------------------------------------------------------------------------------

    @Test
    void walletEndpointMatchesLegacy() throws Exception {
        apply(mineNext(List.of(signedSend(1000, 0))));

        JSONObject legacy = legacyWallet(node, sender);
        HttpResponse response = call(HttpRequest.get("http://x/wallet?address=" + sender.toHexString()).build());
        assertEquals(200, response.getCode());
        assertSameJson(legacy, response);
    }

    // ---- /transaction (both return sites) ----------------------------------------------------

    @Test
    void transactionEndpointMatchesLegacyViaIndexedLookup() throws Exception {
        Transaction t = signedSend(1234, 0);
        apply(mineNext(List.of(t)));
        apply(mineNext(List.of()));

        Long indexed = node.transactionHeight(t.hashContents());
        assertEquals(2L, indexed, "expected the indexed-lookup path to be exercised");

        JSONObject legacy = legacyTransaction(indexed, t);
        String txid = t.hashContents().toHexString();
        HttpResponse response = call(HttpRequest.get("http://x/transaction?txid=" + txid).build());
        assertEquals(200, response.getCode());
        assertSameJson(legacy, response);
    }

    @Test
    void transactionEndpointMatchesLegacyViaFallbackScan() throws Exception {
        // Coinbase transactions are not indexed, so looking one up always falls through to the
        // tip-backward scan fallback — the second return site in ExplorerApi.findTransaction.
        apply(mineNext(List.of()));
        long height = engine.height();
        Block minedBlock = node.block(height);
        Transaction coinbase = minedBlock.transactions().get(0);
        assertNull(node.transactionHeight(coinbase.hashContents()),
            "coinbase transactions must not be indexed, to actually exercise the fallback scan");

        JSONObject legacy = legacyTransaction(height, coinbase);
        String txid = coinbase.hashContents().toHexString();
        HttpResponse response = call(HttpRequest.get("http://x/transaction?txid=" + txid).build());
        assertEquals(200, response.getCode());
        assertSameJson(legacy, response);
    }

    // ---- /address_txs (the biggest measured win) ---------------------------------------------

    @Test
    void addressTxsEndpointMatchesLegacy() throws Exception {
        Transaction t = signedSend(1234, 0);
        apply(mineNext(List.of(t)));
        apply(mineNext(List.of()));

        long tip = node.blockCount();
        long floor = Math.max(1, tip - ExplorerApi.SCAN_DEPTH_DEFAULT + 1);
        JSONObject legacy = legacyAddressTxs(node, sender, floor, tip);
        HttpResponse response = call(HttpRequest.get(
            "http://x/address_txs?address=" + sender.toHexString()).build());
        assertEquals(200, response.getCode());
        assertSameJson(legacy, response);

        // Same address as recipient too, so a second, differently-shaped transaction list is
        // covered by the same equivalence check.
        String to = t.to().toHexString();
        JSONObject legacyTo = legacyAddressTxs(node, t.to(), floor, tip);
        HttpResponse responseTo = call(HttpRequest.get("http://x/address_txs?address=" + to).build());
        assertEquals(200, responseTo.getCode());
        assertSameJson(legacyTo, responseTo);
    }

    // ---- /contract ------------------------------------------------------------------------------

    @Test
    void contractEndpointMatchesLegacyWhenDeployed() throws Exception {
        byte[] code = counterWasm();
        PublicAddress contract = Contracts.deriveAddress(sender, 0);
        apply(mineNext(List.of(signedContract(TransactionKind.DEPLOY, PublicAddress.empty(), code, 0))));
        apply(mineNext(List.of(signedContract(TransactionKind.CALL, contract, new byte[0], 1))));

        JSONObject legacy = legacyContract(contract, node.contractCode(contract), node.balance(contract));
        HttpResponse response = call(HttpRequest.get(
            "http://x/contract?address=" + contract.toHexString()).build());
        assertEquals(200, response.getCode());
        assertSameJson(legacy, response);
    }

    @Test
    void contractEndpointMatchesLegacyWhenAbsent() throws Exception {
        PublicAddress missing = PublicAddress.random();

        JSONObject legacy = legacyContract(missing, node.contractCode(missing), node.balance(missing));
        HttpResponse response = call(HttpRequest.get(
            "http://x/contract?address=" + missing.toHexString()).build());
        assertEquals(200, response.getCode());
        assertSameJson(legacy, response);
    }
}
