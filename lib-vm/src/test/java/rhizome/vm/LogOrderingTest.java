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
import rhizome.core.blockchain.ContractApi.ContractLog;
import rhizome.core.blockchain.Contracts;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;

/**
 * Causal ordering of aggregated contract logs (audit: log ordering — flagged uncovered by the
 * 17th-pass review). The logtree contract (contracts/logtree.rs) emits "before", runs a nested
 * call_contract, then emits "after". With per-frame buffering appended after each execution,
 * a parent frame's two logs would bracket the child's wrongly (parent logs grouped, child
 * spliced before or between); the live sink must collect the tree in exact emission order.
 * A failed frame's logs must be dropped, never spliced into the parent's list.
 */
class LogOrderingTest {

    private static final byte[] LOGTREE = load("/logtree.wasm");
    private static final byte[] EMITTER = load("/emitter.wasm");
    private static final long GAS_LIMIT = 5_000_000;

    private NetworkParameters params;
    private InMemoryContractStore contracts;
    private WasmContractProcessor processor;
    private ChainEngine engine;
    private AtomicLong clock;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress miner;
    private PublicAddress logtree;
    private PublicAddress emitter;
    private PublicAddress innerLogtree;

    private static byte[] load(String r) {
        try (var in = LogOrderingTest.class.getResourceAsStream(r)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(),
            snapshot, null, clock::get, null, processor);

        logtree = Contracts.deriveAddress(sender, 0);
        emitter = Contracts.deriveAddress(sender, 1);
        innerLogtree = Contracts.deriveAddress(sender, 2);
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(0, PublicAddress.empty(), LOGTREE, TransactionKind.DEPLOY),
            tx(1, PublicAddress.empty(), EMITTER, TransactionKind.DEPLOY),
            tx(2, PublicAddress.empty(), LOGTREE, TransactionKind.DEPLOY))));
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

    private static void assertLog(ContractLog log, PublicAddress contract, String topic) {
        assertEquals(contract, log.contract());
        assertArrayEquals(topic.getBytes(), log.topic());
    }

    @Test
    void nestedCallLogsAggregateInExactEmissionOrder() {
        // logtree: emit "before" → emitter bumps its counter and emits "count" → logtree emits
        // "after". The aggregated list must be the causal sequence, not per-frame groupings.
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(3, logtree, concat(new byte[] {0}, emitter.toBytes()), TransactionKind.CALL))));

        List<ContractLog> logs = processor.logs(h);
        assertEquals(3, logs.size(), "parent-before, child, parent-after");
        assertLog(logs.get(0), logtree, "before");
        assertLog(logs.get(1), emitter, "count");
        assertLog(logs.get(2), logtree, "after");
    }

    @Test
    void aFailedSubCallsLogsAreNeverSplicedIntoTheParents() {
        // The inner logtree (selector 1) emits "before" then TRAPS: its log must die with its
        // frame. The outer frame continues (call_contract reports the failure) and emits its own
        // "after" — the aggregated list keeps exactly the outer frame's two logs, in order.
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(3, logtree, concat(new byte[] {0}, innerLogtree.toBytes(), new byte[] {1}),
                TransactionKind.CALL))));

        List<ContractLog> logs = processor.logs(h);
        assertEquals(2, logs.size(), "the trapped inner frame's log is dropped, not spliced");
        assertLog(logs.get(0), logtree, "before");
        assertLog(logs.get(1), logtree, "after");
    }

    @Test
    void aFailedTopLevelCallDropsTheWholeTreesLogs() {
        // Selector 1 at the top: "before", the emitter's "count", then the trap — the top-level
        // revert discards the whole tree's logs (the block stays valid: a revert never
        // invalidates it).
        long h = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(
            tx(3, logtree, concat(new byte[] {1}, emitter.toBytes()), TransactionKind.CALL))));

        assertTrue(processor.logs(h).isEmpty(), "every log died with the top-level revert");
    }
}
