package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.generateKeyPair;

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
 * The token-backed AMM (contracts/pair.rs) over two REAL token contracts, driven
 * through consensus: approve → add_liquidity pulls both legs via transfer_from,
 * swap pulls one token and pays the other along x*y=k. Every movement is asserted
 * on the tokens' actual storage, and an unauthorised swap unwinds both legs — the
 * atomicity that per-frame savepoints exist for.
 */
class TokenPairTest {

    private static final byte[] TOKEN = load("/token.wasm");
    private static final byte[] PAIR = load("/pair.wasm");
    private static final long GAS_LIMIT = 5_000_000;
    private static final long SUPPLY = 1_000_000, LIQUIDITY = 500_000, APPROVED_A = 600_000;

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
    private PublicAddress tokenA;
    private PublicAddress tokenB;
    private PublicAddress pair;

    private static byte[] load(String r) {
        try (var in = TokenPairTest.class.getResourceAsStream(r)) {
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

    /** The pair contract's constant-product formula, for exact expectations. */
    private static long amountOut(long amountIn, long reserveIn, long reserveOut) {
        BigInteger inWithFee = BigInteger.valueOf(amountIn).multiply(BigInteger.valueOf(997));
        return inWithFee.multiply(BigInteger.valueOf(reserveOut))
            .divide(BigInteger.valueOf(reserveIn).multiply(BigInteger.valueOf(1000)).add(inWithFee))
            .longValueExact();
    }

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        ledger = new InMemoryLedger();
        store = new InMemoryChainStore();
        contracts = new InMemoryContractStore();
        clock = new AtomicLong(1_000_000L);

        var pairKeys = generateKeyPair();
        key = PublicKey.of(pairKeys.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pairKeys.getPrivate());
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(100_000_000L));

        processor = new WasmContractProcessor(new WasmVm(), contracts);
        engine = ChainEngine.init(params, ledger, store, snapshot, null, clock::get, null, processor);

        tokenA = Contracts.deriveAddress(sender, 0);
        tokenB = Contracts.deriveAddress(sender, 1);
        pair = Contracts.deriveAddress(sender, 2);

        // Deploy both tokens and the pair; mint both supplies to the creator; wire the
        // pair; approve it (600k on A, 500k on B); seed 500k/500k of liquidity.
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(0, PublicAddress.empty(), TOKEN, TransactionKind.DEPLOY),
            tx(1, PublicAddress.empty(), TOKEN, TransactionKind.DEPLOY),
            tx(2, PublicAddress.empty(), PAIR, TransactionKind.DEPLOY))));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(3, tokenA, concat(new byte[] {0}, le64(SUPPLY)), TransactionKind.CALL),
            tx(4, tokenB, concat(new byte[] {0}, le64(SUPPLY)), TransactionKind.CALL),
            tx(5, pair, concat(new byte[] {0}, tokenA.toBytes(), tokenB.toBytes()), TransactionKind.CALL),
            tx(6, tokenA, concat(new byte[] {3}, pair.toBytes(), le64(APPROVED_A)), TransactionKind.CALL),
            tx(7, tokenB, concat(new byte[] {3}, pair.toBytes(), le64(LIQUIDITY)), TransactionKind.CALL),
            tx(8, pair, concat(new byte[] {1}, le64(LIQUIDITY), le64(LIQUIDITY)), TransactionKind.CALL))));
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

    private byte[] balKey(PublicAddress addr) {
        return concat(new byte[] {1}, addr.toBytes());
    }

    private byte[] allowanceKey(PublicAddress owner, PublicAddress spender) {
        return concat(new byte[] {2}, owner.toBytes(), spender.toBytes());
    }

    private byte[] lpKey(PublicAddress addr) {
        return concat(new byte[] {6}, addr.toBytes());
    }

    @Test
    void addLiquidityMintsLpSharesAndRemoveLiquidityRedeemsThem() {
        // T4: the first deposit minted sqrt(500k*500k) = 500k LP shares to the provider (previously
        // the deposit was lost — no shares, no withdraw path).
        assertArrayEquals(le64(500_000), contracts.getStorage(pair, lpKey(sender)), "LP shares minted");
        assertArrayEquals(le64(500_000), contracts.getStorage(pair, new byte[] {5}), "LP total tracked");

        // Redeem every share: the proportional reserves (500k of each token) return to the provider,
        // the shares burn and the pool empties.
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(9, pair, concat(new byte[] {5}, le64(500_000)), TransactionKind.CALL))));
        assertArrayEquals(le64(0), contracts.getStorage(pair, lpKey(sender)), "shares burned");
        assertArrayEquals(le64(0), contracts.getStorage(pair, new byte[] {5}), "LP total zero");
        assertArrayEquals(le64(0), contracts.getStorage(pair, new byte[] {2}), "reserve A drained");
        assertArrayEquals(le64(0), contracts.getStorage(pair, new byte[] {3}), "reserve B drained");
        assertArrayEquals(le64(SUPPLY), contracts.getStorage(tokenA, balKey(sender)), "token A fully returned");
        assertArrayEquals(le64(SUPPLY), contracts.getStorage(tokenB, balKey(sender)), "token B fully returned");
        assertArrayEquals(le64(0), contracts.getStorage(tokenA, balKey(pair)), "pair holds no token A");
    }

    @Test
    void swapBelowMinOutRevertsLeavingReservesIntact() {
        // T3: demand a floor higher than the pool can pay — the swap traps and unwinds, so no token
        // moves and the reserves are untouched. swap input: [2] || amount_in(8) || min_out(8).
        long amountIn = 100_000;
        long out = amountOut(amountIn, LIQUIDITY, LIQUIDITY);
        byte[] swap = concat(new byte[] {2}, le64(amountIn), le64(out + 1));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(tx(9, pair, swap, TransactionKind.CALL))));
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(pair, new byte[] {2}), "reserve A untouched");
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(pair, new byte[] {3}), "reserve B untouched");
        assertArrayEquals(le64(SUPPLY - LIQUIDITY), contracts.getStorage(tokenA, balKey(sender)), "seller not debited");
    }

    @Test
    void addLiquidityPulledBothRealTokensIntoThePair() {
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(tokenA, balKey(pair)));
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(tokenB, balKey(pair)));
        assertArrayEquals(le64(SUPPLY - LIQUIDITY), contracts.getStorage(tokenA, balKey(sender)));
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(pair, new byte[] {2}), "reserve A tracked");
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(pair, new byte[] {3}), "reserve B tracked");
        // The B allowance was fully consumed by add_liquidity; 100k remains on A.
        assertArrayEquals(le64(0), contracts.getStorage(tokenB, allowanceKey(sender, pair)));
        assertArrayEquals(le64(APPROVED_A - LIQUIDITY), contracts.getStorage(tokenA, allowanceKey(sender, pair)));
    }

    @Test
    void swapMovesBothRealTokensAlongTheCurve() {
        long amountIn = 100_000;
        long expectedOut = amountOut(amountIn, LIQUIDITY, LIQUIDITY);
        assertTrue(expectedOut > 0);

        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(9, pair, concat(new byte[] {2}, le64(amountIn)), TransactionKind.CALL))));

        // Token A: 100k pulled from the seller into the pair.
        assertArrayEquals(le64(SUPPLY - LIQUIDITY - amountIn), contracts.getStorage(tokenA, balKey(sender)));
        assertArrayEquals(le64(LIQUIDITY + amountIn), contracts.getStorage(tokenA, balKey(pair)));
        // Token B: the x*y=k output paid from the pair to the seller.
        assertArrayEquals(le64(SUPPLY - LIQUIDITY + expectedOut), contracts.getStorage(tokenB, balKey(sender)));
        assertArrayEquals(le64(LIQUIDITY - expectedOut), contracts.getStorage(tokenB, balKey(pair)));
        // Reserves follow, and the A allowance is now exhausted (600k - 500k - 100k).
        assertArrayEquals(le64(LIQUIDITY + amountIn), contracts.getStorage(pair, new byte[] {2}));
        assertArrayEquals(le64(LIQUIDITY - expectedOut), contracts.getStorage(pair, new byte[] {3}));
        assertArrayEquals(le64(0), contracts.getStorage(tokenA, allowanceKey(sender, pair)));

        // Three logs, inner frames first: A's transfer (pull), B's transfer (payout),
        // then the pair's swap.
        List<ContractLog> logs = processor.logs(h);
        assertEquals(3, logs.size());
        assertEquals(tokenA, logs.get(0).contract());
        assertArrayEquals("transfer".getBytes(), logs.get(0).topic());
        assertEquals(tokenB, logs.get(1).contract());
        assertArrayEquals("transfer".getBytes(), logs.get(1).topic());
        assertEquals(pair, logs.get(2).contract());
        assertArrayEquals("swap".getBytes(), logs.get(2).topic());
    }

    @Test
    void swapBeyondTheAllowanceUnwindsBothLegs() {
        // Only 100k of allowance remains on token A after setup; asking to swap 150k
        // makes the token's transfer_from trap, the pair trap in turn, and the whole
        // call revert: no token moved on either side, reserves untouched, no logs.
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(9, pair, concat(new byte[] {2}, le64(150_000)), TransactionKind.CALL))));

        assertArrayEquals(le64(SUPPLY - LIQUIDITY), contracts.getStorage(tokenA, balKey(sender)));
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(tokenA, balKey(pair)));
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(tokenB, balKey(pair)));
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(pair, new byte[] {2}));
        assertArrayEquals(le64(LIQUIDITY), contracts.getStorage(pair, new byte[] {3}));
        assertTrue(processor.logs(h).isEmpty());
    }
}
