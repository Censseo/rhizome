package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
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
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
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
 * A constant-product AMM (contracts/amm.rs) driven through consensus: init reserves,
 * swap along the x*y=k curve with a 0.3% fee, and observe reserves/balances in storage
 * plus the "swap" log. The expected output is recomputed with the same integer formula,
 * proving the VM's DeFi math is deterministic and exact.
 */
class AmmContractTest {

    private static final byte[] AMM = load("/amm.wasm");
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

    private static byte[] load(String r) {
        try (var in = AmmContractTest.class.getResourceAsStream(r)) {
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

    /** The contract's constant-product output formula, in Java, for an exact expectation. */
    private static long amountOut(long amountIn, long reserveIn, long reserveOut) {
        BigInteger inWithFee = BigInteger.valueOf(amountIn).multiply(BigInteger.valueOf(997));
        BigInteger numerator = inWithFee.multiply(BigInteger.valueOf(reserveOut));
        BigInteger denominator = BigInteger.valueOf(reserveIn).multiply(BigInteger.valueOf(1000)).add(inWithFee);
        return numerator.divide(denominator).longValueExact();
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

    private byte[] balKey(int prefix, PublicAddress addr) {
        return concat(new byte[] {(byte) prefix}, addr.toBytes());
    }

    @Test
    void initThenSwapMovesReservesAndBalancesAlongTheCurve() {
        PublicAddress amm = Contracts.deriveAddress(sender, 0);
        long ra = 1_000_000, rb = 1_000_000, userA = 500_000, userB = 0;

        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(tx(0, PublicAddress.empty(), AMM, TransactionKind.DEPLOY))));
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(1, amm, concat(new byte[] {0}, le64(ra), le64(rb), le64(userA), le64(userB)), TransactionKind.CALL))));

        long amountIn = 100_000;
        long expectedOut = amountOut(amountIn, ra, rb);
        assertTrue(expectedOut > 0);

        long swapHeight = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(2, amm, concat(new byte[] {1}, le64(amountIn)), TransactionKind.CALL))));

        // Reserves moved along x*y=k (A in, B out); caller balances updated.
        assertArrayEquals(le64(ra + amountIn), contracts.getStorage(amm, new byte[] {0}), "reserve A grew");
        assertArrayEquals(le64(rb - expectedOut), contracts.getStorage(amm, new byte[] {1}), "reserve B shrank");
        assertArrayEquals(le64(userA - amountIn), contracts.getStorage(amm, balKey(2, sender)), "caller A debited");
        assertArrayEquals(le64(userB + expectedOut), contracts.getStorage(amm, balKey(3, sender)), "caller B credited");

        // The swap emitted a log: topic "swap", data = caller(25) || amountIn(8) || amountOut(8).
        List<ContractLog> logs = processor.logs(swapHeight);
        assertEquals(1, logs.size());
        assertArrayEquals("swap".getBytes(), logs.get(0).topic());
        byte[] data = logs.get(0).data();
        assertArrayEquals(sender.toBytes(), java.util.Arrays.copyOfRange(data, 0, 25));
        assertArrayEquals(le64(amountIn), java.util.Arrays.copyOfRange(data, 25, 33));
        assertArrayEquals(le64(expectedOut), java.util.Arrays.copyOfRange(data, 33, 41));
    }

    @Test
    void swapBeyondBalanceIsANoOp() {
        PublicAddress amm = Contracts.deriveAddress(sender, 0);
        mineBlock(List.of(tx(0, PublicAddress.empty(), AMM, TransactionKind.DEPLOY)));
        mineBlock(List.of(tx(1, amm, concat(new byte[] {0}, le64(1_000_000), le64(1_000_000), le64(1_000), le64(0)), TransactionKind.CALL)));

        // Caller has 1_000 of A but tries to swap 10_000: no-op, reserves untouched.
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(2, amm, concat(new byte[] {1}, le64(10_000)), TransactionKind.CALL))));
        assertArrayEquals(le64(1_000_000), contracts.getStorage(amm, new byte[] {0}), "reserve A untouched");
        assertArrayEquals(le64(1_000), contracts.getStorage(amm, balKey(2, sender)), "caller A untouched");
        assertTrue(processor.logs(h).isEmpty());
    }
}
