package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The memecoin base: a real Rust-compiled fungible-token contract (contracts/token.rs)
 * driven through consensus — deploy, init the supply, transfer, and observe balances in
 * storage plus the "transfer" event log agents would watch.
 */
class TokenContractTest {

    private static final byte[] TOKEN = load("/token.wasm");
    private static final long GAS_LIMIT = 2_000_000;

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
        try (var in = TokenContractTest.class.getResourceAsStream(r)) {
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
        snapshot.put(sender, new TransactionAmount(50_000_000L));

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

    private byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private byte[] balanceKey(PublicAddress addr) {
        return concat(new byte[] {1}, addr.toBytes());
    }

    @Test
    void initCannotBeFrontRunByANonDeployer() {
        // T1: the token is deployed by `sender`. A mempool observer (attacker) races an init to mint
        // the whole supply to themselves. Under the deployer-bound init it is a no-op: nothing is
        // minted, the init flag stays unset, and the real deployer can still init afterwards.
        PublicAddress token = Contracts.deriveAddress(sender, 0);
        var apair = generateKeyPair();
        PublicKey aKey = PublicKey.of(apair.getPublic());
        PrivateKey aPriv = new PrivateKey((Ed25519PrivateKeyParameters) apair.getPrivate());
        PublicAddress attacker = PublicAddress.of(aKey);

        // Deploy the token and fund the attacker so it can pay gas for its init attempt.
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(0, PublicAddress.empty(), TOKEN, TransactionKind.DEPLOY),
            transferTx(sender, key, priv, 1, attacker, 10_000_000))));

        // Attacker (not the deployer) tries to init: no-op, no supply seized.
        Transaction attackerInit = TransactionImpl.builder()
            .from(attacker).to(token).amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(0).signingKey(aKey)
            .kind(TransactionKind.CALL).data(concat(new byte[] {0}, le64(1000)))
            .gasLimit(GAS_LIMIT).gasPrice(1).build();
        attackerInit.sign(aPriv);
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(attackerInit)));
        assertEquals(null, contracts.getStorage(token, balanceKey(attacker)), "attacker minted nothing");

        // The real deployer can still init: the front-run did not consume the one-time init.
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(2, token, concat(new byte[] {0}, le64(1000)), TransactionKind.CALL))));
        assertArrayEquals(le64(1000), contracts.getStorage(token, balanceKey(sender)), "deployer minted the supply");
        assertEquals(null, contracts.getStorage(token, balanceKey(attacker)), "attacker still holds nothing");
    }

    /** A plain PDN transfer from a given signer (funds a second identity in a test). */
    private Transaction transferTx(PublicAddress from, PublicKey fromKey, PrivateKey fromPriv,
                                   long nonce, PublicAddress to, long amount) {
        Transaction t = TransactionImpl.builder()
            .from(from).to(to).amount(new TransactionAmount(amount)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(fromKey)
            .kind(TransactionKind.TRANSFER).gasLimit(0).gasPrice(0).build();
        t.sign(fromPriv);
        return t;
    }

    @Test
    void deployInitTransferMovesBalancesAndEmitsTransferLog() {
        PublicAddress token = Contracts.deriveAddress(sender, 0);
        PublicAddress bob = PublicAddress.random();

        // Deploy (nonce 0), init supply 1000 to sender (nonce 1).
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(tx(0, PublicAddress.empty(), TOKEN, TransactionKind.DEPLOY))));
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(tx(1, token, concat(new byte[] {0}, le64(1000)), TransactionKind.CALL))));
        assertArrayEquals(le64(1000), contracts.getStorage(token, balanceKey(sender)), "supply minted to deployer");

        // Transfer 300 sender -> bob (nonce 2).
        byte[] transfer = concat(concat(new byte[] {1}, bob.toBytes()), le64(300));
        long transferHeight = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(tx(2, token, transfer, TransactionKind.CALL))));

        assertArrayEquals(le64(700), contracts.getStorage(token, balanceKey(sender)), "sender debited");
        assertArrayEquals(le64(300), contracts.getStorage(token, balanceKey(bob)), "bob credited");

        // The transfer emitted a log: topic "transfer", data = from(25)||to(25)||amount(8).
        List<ContractLog> logs = processor.logs(transferHeight);
        assertEquals(1, logs.size());
        assertArrayEquals("transfer".getBytes(), logs.get(0).topic());
        byte[] data = logs.get(0).data();
        assertEquals(25 + 25 + 8, data.length);
        assertArrayEquals(sender.toBytes(), java.util.Arrays.copyOfRange(data, 0, 25));
        assertArrayEquals(bob.toBytes(), java.util.Arrays.copyOfRange(data, 25, 50));
        assertArrayEquals(le64(300), java.util.Arrays.copyOfRange(data, 50, 58));
    }

    @Test
    void transferBeyondBalanceIsANoOp() {
        PublicAddress token = Contracts.deriveAddress(sender, 0);
        PublicAddress bob = PublicAddress.random();

        mineBlock(List.of(tx(0, PublicAddress.empty(), TOKEN, TransactionKind.DEPLOY)));
        mineBlock(List.of(tx(1, token, concat(new byte[] {0}, le64(100)), TransactionKind.CALL)));

        // Try to send 500 with only 100: the contract returns early, balances unchanged.
        byte[] transfer = concat(concat(new byte[] {1}, bob.toBytes()), le64(500));
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(tx(2, token, transfer, TransactionKind.CALL))));

        assertArrayEquals(le64(100), contracts.getStorage(token, balanceKey(sender)), "sender untouched");
        assertEquals(null, contracts.getStorage(token, balanceKey(bob)), "bob never credited");
        // A rejected transfer emits no log.
        org.junit.jupiter.api.Assertions.assertTrue(processor.logs(h).isEmpty());
    }
}
