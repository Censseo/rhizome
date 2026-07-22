package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.generateKeyPair;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ContractProcessor.ContractLog;
import rhizome.core.blockchain.Contracts;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

/**
 * Cross-contract calls through consensus: a router contract (contracts/router.rs)
 * drives the token contract via call_contract. Proves the composition layer —
 * nested frames whose writes are atomic with the top-level call, the callee seeing
 * the calling contract as its caller, reentrancy refusal, and log aggregation
 * across frames.
 */
class CrossContractTest {

    private static final byte[] TOKEN = load("/token.wasm");
    private static final byte[] ROUTER = load("/router.wasm");
    private static final long GAS_LIMIT = 5_000_000;

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private InMemoryChainStore store;
    private InMemoryContractStore contracts;
    private WasmContractProcessor processor;
    private ChainEngine engine;
    private AtomicLong clock;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress miner;
    private PublicAddress token;
    private PublicAddress router;

    private static byte[] load(String r) {
        try (var in = CrossContractTest.class.getResourceAsStream(r)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (v >>> (8 * i));
        }
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        ledger = new InMemoryLedger();
        store = new InMemoryChainStore();
        contracts = new InMemoryContractStore();
        clock = new AtomicLong(1_000_000L);

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(100_000_000L));

        processor = new WasmContractProcessor(new WasmVm(), contracts);
        engine = ChainEngine.init(params, ledger, store, snapshot, null, clock::get, null, processor);

        // Deploy token (nonce 0) and router (nonce 1). init is now deployer-gated (audit T1), so the
        // deployer (sender) must init the token — a non-deployer like the router can no longer seize
        // the supply. The deployer then moves the whole supply to the router so it can act as the
        // intermediary the caller-context tests below exercise.
        token = Contracts.deriveAddress(sender, 0);
        router = Contracts.deriveAddress(sender, 1);
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(0, PublicAddress.empty(), TOKEN, TransactionKind.DEPLOY),
            tx(1, PublicAddress.empty(), ROUTER, TransactionKind.DEPLOY))));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(2, token, concat(new byte[] {0}, le64(1000)), TransactionKind.CALL))));            // sender inits: supply -> sender
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(3, token, concat(new byte[] {1}, router.toBytes(), le64(1000)), TransactionKind.CALL)))); // sender -> router
    }

    private Transaction tx(long nonce, PublicAddress to, byte[] data, TransactionKind kind) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(to)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(kind).data(data).gasLimit(GAS_LIMIT).gasPrice(1)
            .build();
        t.sign(priv);
        return t;
    }

    private ExecutionStatus mineBlock(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return engine.addBlock(b);
    }

    /** Router input, selector 0 (forward): [0] || callee(25) || payload. */
    private static byte[] forward(PublicAddress callee, byte[] payload) {
        return concat(new byte[] {0}, callee.toBytes(), payload);
    }

    private byte[] balKey(PublicAddress addr) {
        return concat(new byte[] {1}, addr.toBytes());
    }

    @Test
    void routerMovesTokenBalancesAndCalleeSeesRouterAsCaller() {
        // setUp funded the router with the whole supply. Now the router forwards a transfer: the
        // token must see the ROUTER (the immediate caller), not the EOA, as `from`.
        assertArrayEquals(le64(1000), contracts.getStorage(token, balKey(router)), "router holds the supply");

        PublicAddress bob = PublicAddress.random();
        byte[] transfer = concat(new byte[] {1}, bob.toBytes(), le64(300));
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(4, router, forward(token, transfer), TransactionKind.CALL))));

        assertArrayEquals(le64(700), contracts.getStorage(token, balKey(router)), "router debited");
        assertArrayEquals(le64(300), contracts.getStorage(token, balKey(bob)), "bob credited");

        // The token's transfer log survived frame aggregation, stamped with the token's
        // address and carrying the router as `from`.
        List<ContractLog> logs = processor.logs(h);
        assertEquals(1, logs.size());
        assertEquals(token, logs.get(0).contract());
        assertArrayEquals("transfer".getBytes(), logs.get(0).topic());
        assertArrayEquals(router.toBytes(), java.util.Arrays.copyOfRange(logs.get(0).data(), 0, 25));
    }

    @Test
    void callerRevertAfterSuccessfulSubCallDiscardsSubCallWrites() {
        PublicAddress bob = PublicAddress.random();
        // Selector 1: the router performs the transfer (which succeeds), then traps.
        byte[] callThenTrap = concat(new byte[] {1}, token.toBytes(),
            concat(new byte[] {1}, bob.toBytes(), le64(100)));
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(4, router, callThenTrap, TransactionKind.CALL))));

        // The block is valid (a revert never invalidates it), but the sub-call's writes
        // died with the caller's frame: balances untouched, no logs.
        assertArrayEquals(le64(1000), contracts.getStorage(token, balKey(router)), "router balance intact");
        assertEquals(null, contracts.getStorage(token, balKey(bob)), "bob never credited");
        assertTrue(processor.logs(h).isEmpty(), "the transfer log died with the revert");
    }

    @Test
    void reentrancyIsRefusedAndTheCallerObservesTheFailure() {
        // The router calls itself: the dispatcher refuses (router is already on the
        // stack), call_contract returns -1, and the router emits its "callfail" log —
        // the outer call still succeeds.
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(4, router, forward(router, new byte[] {0}), TransactionKind.CALL))));

        List<ContractLog> logs = processor.logs(h);
        assertEquals(1, logs.size());
        assertEquals(router, logs.get(0).contract());
        assertArrayEquals("callfail".getBytes(), logs.get(0).topic());
    }
}
