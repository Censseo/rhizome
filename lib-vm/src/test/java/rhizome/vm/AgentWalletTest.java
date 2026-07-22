package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

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
 * Account abstraction for agents (contracts/agent_wallet.rs), through consensus:
 * a wallet contract owns the treasury, its owner drives arbitrary calls through
 * it, and an AI agent's own key — a real second EOA signing its own transactions
 * here — spends the wallet's tokens only within a granted, revocable session cap.
 */
class AgentWalletTest {

    private static final byte[] TOKEN = load("/token.wasm");
    private static final byte[] WALLET = load("/agent_wallet.wasm");
    private static final long GAS_LIMIT = 5_000_000;
    private static final long SUPPLY = 1_000_000, FUNDED = 500_000, SESSION_CAP = 50_000;

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private InMemoryChainStore store;
    private InMemoryContractStore contracts;
    private WasmContractProcessor processor;
    private ChainEngine engine;
    private AtomicLong clock;

    private PublicKey ownerKey;
    private PrivateKey ownerPriv;
    private PublicAddress owner;
    private PublicKey agentKey;
    private PrivateKey agentPriv;
    private PublicAddress agent;
    private PublicAddress miner;
    private PublicAddress token;
    private PublicAddress wallet;

    private static byte[] load(String r) {
        try (var in = AgentWalletTest.class.getResourceAsStream(r)) {
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

        var ownerPair = generateKeyPair();
        ownerKey = PublicKey.of(ownerPair.getPublic());
        ownerPriv = new PrivateKey((Ed25519PrivateKeyParameters) ownerPair.getPrivate());
        owner = PublicAddress.of(ownerKey);
        var agentPair = generateKeyPair();
        agentKey = PublicKey.of(agentPair.getPublic());
        agentPriv = new PrivateKey((Ed25519PrivateKeyParameters) agentPair.getPrivate());
        agent = PublicAddress.of(agentKey);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(owner, new TransactionAmount(100_000_000L));
        snapshot.put(agent, new TransactionAmount(50_000_000L)); // gas money of its own

        processor = new WasmContractProcessor(new WasmVm(), contracts);
        engine = ChainEngine.init(params, ledger, store, snapshot, null, clock::get, null, processor);

        token = Contracts.deriveAddress(owner, 0);
        wallet = Contracts.deriveAddress(owner, 1);

        // Deploy token + wallet; mint the supply; claim wallet ownership; fund the
        // wallet's treasury with 500k tokens.
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            ownerTx(0, PublicAddress.empty(), TOKEN, TransactionKind.DEPLOY),
            ownerTx(1, PublicAddress.empty(), WALLET, TransactionKind.DEPLOY))));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            ownerTx(2, token, concat(new byte[] {0}, le64(SUPPLY)), TransactionKind.CALL),
            ownerTx(3, wallet, new byte[] {0}, TransactionKind.CALL),
            ownerTx(4, token, concat(new byte[] {1}, wallet.toBytes(), le64(FUNDED)), TransactionKind.CALL))));
    }

    private Transaction ownerTx(long nonce, PublicAddress to, byte[] data, TransactionKind kind) {
        return signed(ownerKey, ownerPriv, owner, nonce, to, data, kind);
    }

    private Transaction ownerTx(long nonce, PublicAddress to, byte[] data) {
        return ownerTx(nonce, to, data, TransactionKind.CALL);
    }

    private Transaction agentTx(long nonce, PublicAddress to, byte[] data) {
        return signed(agentKey, agentPriv, agent, nonce, to, data, TransactionKind.CALL);
    }

    private Transaction signed(PublicKey key, PrivateKey priv, PublicAddress from, long nonce,
                               PublicAddress to, byte[] data, TransactionKind kind) {
        Transaction t = TransactionImpl.builder()
            .from(from).to(to)
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

    private byte[] sessionRecordKey(PublicAddress key) {
        return concat(new byte[] {1}, key.toBytes());
    }

    @Test
    void ownerDrivesArbitraryCallsThroughTheWalletButOthersCannot() {
        PublicAddress bob = PublicAddress.random();
        // exec(token, transfer(bob, 100k)): the wallet is the token's caller.
        byte[] exec = concat(new byte[] {1}, token.toBytes(),
            concat(new byte[] {1}, bob.toBytes(), le64(100_000)));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(ownerTx(5, wallet, exec))));
        assertArrayEquals(le64(FUNDED - 100_000), contracts.getStorage(token, balKey(wallet)));
        assertArrayEquals(le64(100_000), contracts.getStorage(token, balKey(bob)));

        // The agent (not owner, no session) attempting the same exec reverts untouched.
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(agentTx(0, wallet, exec))));
        assertArrayEquals(le64(FUNDED - 100_000), contracts.getStorage(token, balKey(wallet)),
            "non-owner exec must not move the treasury");
    }

    @Test
    void sessionKeySpendsWithinItsCapAndRevocationCutsItOff() {
        PublicAddress carol = PublicAddress.random();

        // Owner grants the agent a 50k session on the token.
        byte[] grant = concat(new byte[] {2}, agent.toBytes(), token.toBytes(), le64(SESSION_CAP));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(ownerTx(5, wallet, grant))));
        assertArrayEquals(concat(token.toBytes(), le64(SESSION_CAP)),
            contracts.getStorage(wallet, sessionRecordKey(agent)), "session record stored");

        // The agent, signing with ITS OWN key, spends 30k of the wallet's tokens.
        long h = engine.height() + 1;
        byte[] spend = concat(new byte[] {4}, carol.toBytes(), le64(30_000));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(agentTx(0, wallet, spend))));
        assertArrayEquals(le64(FUNDED - 30_000), contracts.getStorage(token, balKey(wallet)));
        assertArrayEquals(le64(30_000), contracts.getStorage(token, balKey(carol)));
        assertArrayEquals(concat(token.toBytes(), le64(SESSION_CAP - 30_000)),
            contracts.getStorage(wallet, sessionRecordKey(agent)), "budget decremented");

        // Two logs for the spend: the token's transfer (inner), then the wallet's spend.
        List<ContractLog> logs = processor.logs(h);
        assertEquals(2, logs.size());
        assertEquals(token, logs.get(0).contract());
        assertArrayEquals("transfer".getBytes(), logs.get(0).topic());
        assertEquals(wallet, logs.get(1).contract());
        assertArrayEquals("spend".getBytes(), logs.get(1).topic());

        // Over the remaining budget (20k left, asks 30k): reverts, nothing moves.
        long h2 = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(agentTx(1, wallet, spend))));
        assertArrayEquals(le64(FUNDED - 30_000), contracts.getStorage(token, balKey(wallet)));
        assertTrue(processor.logs(h2).isEmpty());

        // Owner revokes; even a tiny spend now reverts.
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(ownerTx(6, wallet, concat(new byte[] {3}, agent.toBytes())))));
        byte[] tiny = concat(new byte[] {4}, carol.toBytes(), le64(1_000));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(agentTx(2, wallet, tiny))));
        assertArrayEquals(le64(FUNDED - 30_000), contracts.getStorage(token, balKey(wallet)),
            "revoked session cannot spend");
        assertArrayEquals(le64(30_000), contracts.getStorage(token, balKey(carol)));
    }

    @Test
    void nonOwnerCannotGrantItselfASession() {
        byte[] selfGrant = concat(new byte[] {2}, agent.toBytes(), token.toBytes(), le64(SESSION_CAP));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(agentTx(0, wallet, selfGrant))));
        assertEquals(null, contracts.getStorage(wallet, sessionRecordKey(agent)),
            "grant by a non-owner reverts");
    }
}
