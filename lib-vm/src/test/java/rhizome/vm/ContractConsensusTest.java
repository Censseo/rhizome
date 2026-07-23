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

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
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
 * Contracts executing in consensus: a ChainEngine wired with the WASM processor
 * runs a DEPLOY then a CALL through addBlock. Proves the executor dispatch, the
 * per-block state session (committed only on block acceptance), and gas paid to
 * the miner — end to end, through the real VM.
 */
class ContractConsensusTest {

    private static final byte[] COUNTER = load("/counter.wasm");
    private static final byte[] EMITTER = load("/emitter.wasm");
    private static final long GAS_LIMIT = 1_000_000;

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
        try (var in = ContractConsensusTest.class.getResourceAsStream(r)) {
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
        snapshot.put(sender, new TransactionAmount(10_000_000L));

        processor = new WasmContractProcessor(new WasmVm(), contracts);
        engine = ChainEngine.init(params, ledger, store, snapshot, null, clock::get, null, processor);
    }

    private Transaction deployTx(long nonce, byte[] code) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(PublicAddress.empty())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(TransactionKind.DEPLOY).data(code).gasLimit(GAS_LIMIT).gasPrice(1)
            .build();
        t.sign(priv);
        return t;
    }

    private Transaction callTx(long nonce, PublicAddress contract) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(contract)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(TransactionKind.CALL).data(new byte[0]).gasLimit(GAS_LIMIT).gasPrice(1)
            .build();
        t.sign(priv);
        return t;
    }

    private ExecutionStatus mineBlock(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return engine.addBlock(b);
    }

    @Test
    void deployThenCallAcrossBlocksPersistsStateAndPaysGas() {
        PublicAddress contract = Contracts.deriveAddress(sender, 0);
        long senderStart = ledger.getWalletValue(sender).amount();

        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(deployTx(0, COUNTER))));
        assertArrayEquals(COUNTER, contracts.getCode(contract), "code committed to the base store");

        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(callTx(1, contract))));
        assertArrayEquals(le64(1), contracts.getStorage(contract, new byte[] {0}), "counter incremented and persisted");

        assertTrue(ledger.getWalletValue(sender).amount() < senderStart, "sender paid gas");
        assertTrue(ledger.getWalletValue(miner).amount() > 0, "miner earned gas fees");
    }

    @Test
    void deployAndCallInSameBlockSeeEachOther() {
        PublicAddress contract = Contracts.deriveAddress(sender, 0);
        assertEquals(ExecutionStatus.SUCCESS,
            mineBlock(List.of(deployTx(0, COUNTER), callTx(1, contract))));
        // The call in the same block saw the deploy's code (intra-block session).
        assertArrayEquals(le64(1), contracts.getStorage(contract, new byte[] {0}));
    }

    @Test
    void popRevertsContractStateExactly() {
        PublicAddress contract = Contracts.deriveAddress(sender, 0);

        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(deployTx(0, COUNTER))));
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(callTx(1, contract)))); // counter -> 1
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(callTx(2, contract)))); // counter -> 2
        assertArrayEquals(le64(2), contracts.getStorage(contract, new byte[] {0}));

        // Pop the two calls: the counter rewinds 2 -> 1 -> absent, exactly.
        engine.popBlock();
        assertArrayEquals(le64(1), contracts.getStorage(contract, new byte[] {0}), "call 2 reverted");
        engine.popBlock();
        assertEquals(null, contracts.getStorage(contract, new byte[] {0}), "call 1 reverted");

        // Pop the deploy: the code is removed.
        engine.popBlock();
        assertEquals(null, contracts.getCode(contract), "deploy reverted");
    }

    private Transaction callTx(long nonce, PublicAddress contract, byte[] code, TransactionKind kind) {
        Transaction t = TransactionImpl.builder()
            .from(sender).to(contract)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(kind).data(code).gasLimit(GAS_LIMIT).gasPrice(1)
            .build();
        t.sign(priv);
        return t;
    }

    @Test
    void contractLogsAreCollectedPerBlockAndDroppedOnRevert() {
        PublicAddress contract = Contracts.deriveAddress(sender, 0);
        // Deploy the emitter (nonce 0), then call it (nonce 1) — the call emits one log.
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(callTx(0, PublicAddress.empty(), EMITTER, TransactionKind.DEPLOY))));
        long callHeight = engine.height() + 1;
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(callTx(1, contract, new byte[0], TransactionKind.CALL))));

        List<rhizome.core.blockchain.ContractProcessor.ContractLog> logs = processor.logs(callHeight);
        assertEquals(1, logs.size());
        assertEquals(contract, logs.get(0).contract());
        assertArrayEquals("count".getBytes(), logs.get(0).topic());
        assertArrayEquals(le64(1), logs.get(0).data());

        // Reverting the call block drops its logs.
        engine.popBlock();
        assertTrue(processor.logs(callHeight).isEmpty());
    }

    @Test
    void callToMissingContractIsIncludedButChangesNoState() {
        // A CALL to an address with no code reverts: the block is still valid, but no
        // contract state appears and (with no gas charged for a missing contract) the
        // sender is untouched beyond nonce.
        PublicAddress ghost = PublicAddress.random();
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(callTx(0, ghost))));
        assertEquals(null, contracts.getStorage(ghost, new byte[] {0}));
        assertEquals(1, engine.nextNonce(sender));
    }

    /** Re-init the engine over a fresh ledger with the given consensus gas caps. */
    private void withGasCaps(long maxTxGas, long maxBlockGas) {
        params = params.toBuilder().maxTxGas(maxTxGas).maxBlockGas(maxBlockGas).build();
        ledger = new InMemoryLedger();
        store = new InMemoryChainStore();
        contracts = new InMemoryContractStore();
        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));
        processor = new WasmContractProcessor(new WasmVm(), contracts);
        engine = ChainEngine.init(params, ledger, store, snapshot, null, clock::get, null, processor);
    }

    @Test
    void contractTxOverPerTxGasCapIsRejectedBeforeExecution() {
        // A single contract call whose gasLimit exceeds maxTxGas is a "poison" tx: at gasPrice 0 it is
        // free, yet the VM would run up to gasLimit instructions under the consensus lock on every node.
        // Pass 1 must reject the whole block on the cheap structural pass, before any instruction runs.
        withGasCaps(500_000L, 10_000_000L);
        PublicAddress contract = Contracts.deriveAddress(sender, 0);

        // deployTx uses GAS_LIMIT (1_000_000) > maxTxGas (500_000).
        assertEquals(ExecutionStatus.GAS_LIMIT_EXCEEDED, mineBlock(List.of(deployTx(0, COUNTER))));

        // Rejected before execution: no code deployed, sender not charged, tip unchanged.
        assertEquals(null, contracts.getCode(contract), "over-cap block never executed the deploy");
        assertEquals(10_000_000L, ledger.getWalletValue(sender).amount(), "over-cap block charged no gas");
        assertEquals(1, engine.height(), "over-cap block was not appended");
    }

    @Test
    void perTxUnderCapButBlockGasSumOverCapIsRejected() {
        // Each call is within maxTxGas, but together their declared gas exceeds the block ceiling, so the
        // block is still rejected — the bound holds against a block packed with many capped calls.
        withGasCaps(GAS_LIMIT, GAS_LIMIT + GAS_LIMIT / 2); // two GAS_LIMIT deploys sum to 2x > 1.5x cap
        assertEquals(ExecutionStatus.GAS_LIMIT_EXCEEDED,
            mineBlock(List.of(deployTx(0, COUNTER), deployTx(1, EMITTER))));
        assertEquals(1, engine.height(), "over-cap block was not appended");
    }

    @Test
    void contractTxAtExactGasCapsIsAccepted() {
        // Boundary: gasLimit == maxTxGas and the block's declared-gas total == maxBlockGas are both allowed
        // (the rejection is strictly greater-than), so the caps do not reject legitimate blocks.
        withGasCaps(GAS_LIMIT, GAS_LIMIT);
        assertEquals(ExecutionStatus.SUCCESS, mineBlock(List.of(deployTx(0, COUNTER))));
        assertArrayEquals(COUNTER, contracts.getCode(Contracts.deriveAddress(sender, 0)),
            "a block exactly at the gas caps is valid");
    }
}
