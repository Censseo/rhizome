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
 * The fair launch, end to end: a launchpad (contracts/launchpad.rs) sells a token it
 * holds at a fixed rate. A buyer attaches native coin; the launchpad checks its own
 * token balance through a call_contract output round-trip, pays out via the token
 * contract, and reverts when it cannot deliver — in which case the buyer's coin never
 * moves. Native value, cross-contract calls and logs, all through consensus.
 */
class LaunchpadContractTest {

    private static final byte[] TOKEN = load("/token.wasm");
    private static final byte[] LAUNCHPAD = load("/launchpad.wasm");
    private static final long GAS_LIMIT = 5_000_000;
    private static final long SUPPLY = 1_000_000, FUNDED = 500_000, RATE = 10;

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
    private PublicAddress pad;

    private static byte[] load(String r) {
        try (var in = LaunchpadContractTest.class.getResourceAsStream(r)) {
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

        // Deploy token + launchpad; mint the supply to the creator; fund the launchpad
        // with tokens (a plain transfer to its address); set the sale up.
        token = Contracts.deriveAddress(sender, 0);
        pad = Contracts.deriveAddress(sender, 1);
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(0, PublicAddress.empty(), TOKEN, 0, TransactionKind.DEPLOY),
            tx(1, PublicAddress.empty(), LAUNCHPAD, 0, TransactionKind.DEPLOY))));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(2, token, concat(new byte[] {0}, le64(SUPPLY)), 0, TransactionKind.CALL),
            tx(3, token, concat(new byte[] {1}, pad.toBytes(), le64(FUNDED)), 0, TransactionKind.CALL),
            tx(4, pad, concat(new byte[] {0}, token.toBytes(), le64(RATE)), 0, TransactionKind.CALL))));
    }

    private Transaction tx(long nonce, PublicAddress to, byte[] data, long value, TransactionKind kind) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(to)
            .amount(new TransactionAmount(value)).fee(new TransactionAmount(0))
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

    private byte[] balKey(PublicAddress addr) {
        return concat(new byte[] {1}, addr.toBytes());
    }

    private long nativeBalance(PublicAddress a) {
        return ledger.hasWallet(a) ? ledger.getWalletValue(a).amount() : 0L;
    }

    @Test
    void buyDeliversTokensAndCollectsTheNativeValue() {
        long value = 1_000;
        long expectedTokens = value * RATE; // 10_000

        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(5, pad, new byte[] {1}, value, TransactionKind.CALL))));

        // Tokens moved from the launchpad to the buyer; the coin landed on the launchpad.
        assertArrayEquals(le64(SUPPLY - FUNDED + expectedTokens), contracts.getStorage(token, balKey(sender)));
        assertArrayEquals(le64(FUNDED - expectedTokens), contracts.getStorage(token, balKey(pad)));
        assertEquals(value, nativeBalance(pad), "the attached coin was collected by the sale");

        // Two logs at that height, inner frame first: the token's transfer, then the
        // launchpad's buy — each stamped with its emitting contract.
        List<ContractLog> logs = processor.logs(h);
        assertEquals(2, logs.size());
        assertEquals(token, logs.get(0).contract());
        assertArrayEquals("transfer".getBytes(), logs.get(0).topic());
        assertEquals(pad, logs.get(1).contract());
        assertArrayEquals("buy".getBytes(), logs.get(1).topic());
        byte[] data = logs.get(1).data();
        assertArrayEquals(sender.toBytes(), java.util.Arrays.copyOfRange(data, 0, 25));
        assertArrayEquals(le64(value), java.util.Arrays.copyOfRange(data, 25, 33));
        assertArrayEquals(le64(expectedTokens), java.util.Arrays.copyOfRange(data, 33, 41));
    }

    @Test
    void buyThatCannotBeDeliveredRevertsAndTheCoinNeverMoves() {
        // 60_000 * 10 = 600_000 tokens asked, but the launchpad only holds 500_000:
        // the balance check fails, the launchpad traps, the value transfer rolls back.
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(5, pad, new byte[] {1}, 60_000, TransactionKind.CALL))));

        assertArrayEquals(le64(FUNDED), contracts.getStorage(token, balKey(pad)), "tokens untouched");
        assertArrayEquals(le64(SUPPLY - FUNDED), contracts.getStorage(token, balKey(sender)));
        assertEquals(0, nativeBalance(pad), "the buyer's coin was never taken");
        assertTrue(processor.logs(h).isEmpty());
    }

    @Test
    void zeroValueBuyIsRefused() {
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(5, pad, new byte[] {1}, 0, TransactionKind.CALL))));
        assertArrayEquals(le64(FUNDED), contracts.getStorage(token, balKey(pad)));
        assertTrue(processor.logs(h).isEmpty());
    }
}
