package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import rhizome.core.state.InMemoryRootStore;
import rhizome.core.state.InMemorySmtNodeStore;
import rhizome.core.state.SparseMerkleTree;
import rhizome.core.state.StateAccumulator;
import rhizome.core.state.StateKeys;
import rhizome.core.state.StateProof;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;
import rhizome.core.transaction.TransactionKind;

/**
 * Contract code and storage are committed in the authenticated state root: a light client
 * can prove a contract's deployed code and a storage slot against a block header, through
 * the real WASM VM driven in consensus.
 */
class ContractStateRootTest {

    private static final byte[] COUNTER = load("/counter.wasm");
    private static final long GAS = 1_000_000;

    private NetworkParameters params;
    private InMemoryLedger ledger;
    private InMemoryContractStore contracts;
    private WasmContractProcessor processor;
    private StateAccumulator accumulator;
    private ChainEngine engine;
    private AtomicLong clock;

    private PublicKey key;
    private PrivateKey priv;
    private PublicAddress sender;
    private PublicAddress miner;

    private static byte[] load(String r) {
        try (var in = ContractStateRootTest.class.getResourceAsStream(r)) {
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
        contracts = new InMemoryContractStore();
        processor = new WasmContractProcessor(new WasmVm(), contracts);
        accumulator = new StateAccumulator(new InMemorySmtNodeStore(), new InMemoryRootStore(), params.maxReorgDepth());
        clock = new AtomicLong(1_000_000L);

        var pair = generateKeyPair();
        key = PublicKey.of(pair.getPublic());
        priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        sender = PublicAddress.of(key);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("t", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(10_000_000L));

        engine = ChainEngine.init(params, ledger, new InMemoryChainStore(), snapshot, null,
            clock::get, null, processor, null, null, accumulator);
    }

    private Transaction deploy(long nonce) {
        Transaction t = TransactionImpl.builder().from(sender).to(PublicAddress.empty())
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(TransactionKind.DEPLOY).data(COUNTER).gasLimit(GAS).gasPrice(1).build();
        t.sign(priv);
        return t;
    }

    private Transaction call(long nonce, PublicAddress contract) {
        Transaction t = TransactionImpl.builder().from(sender).to(contract)
            .amount(new TransactionAmount(0)).fee(new TransactionAmount(0))
            .chainId(params.chainId()).nonce(nonce).signingKey(key)
            .kind(TransactionKind.CALL).data(new byte[0]).gasLimit(GAS).gasPrice(1).build();
        t.sign(priv);
        return t;
    }

    private void mine(List<Transaction> txs) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height).timestamp(clock.addAndGet(1000))
            .difficulty(engine.difficulty()).lastBlockHash(engine.tipHash()).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        engine.stampStateRoot(b);
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
    }

    @Test
    void contractCodeAndStorageAreProvableAgainstTheRoot() {
        PublicAddress contract = Contracts.deriveAddress(sender, 0);
        mine(List.of(deploy(0)));
        mine(List.of(call(1, contract))); // counter -> 1 at storage key {0}

        byte[] root = engine.stateRoot();
        assertNotNull(root);

        // Deployed code is provable.
        StateProof codeProof = engine.stateProof(StateKeys.CONTRACT_CODE, contract.toBytes());
        assertNotNull(codeProof);
        assertTrue(SparseMerkleTree.verify(root, StateKeys.key(StateKeys.CONTRACT_CODE, contract.toBytes()),
            StateKeys.valueHash(COUNTER), codeProof));

        // The counter's storage slot {0} == 1 is provable.
        byte[] storageKey = new byte[contract.toBytes().length + 1];
        System.arraycopy(contract.toBytes(), 0, storageKey, 0, contract.toBytes().length);
        storageKey[storageKey.length - 1] = 0; // key {0}
        StateProof storageProof = engine.stateProof(StateKeys.CONTRACT_STORAGE, storageKey);
        assertNotNull(storageProof);
        assertArrayEquals(StateKeys.valueHash(le64(1)), storageProof.valueHash());
        assertTrue(SparseMerkleTree.verify(root, StateKeys.key(StateKeys.CONTRACT_STORAGE, storageKey),
            StateKeys.valueHash(le64(1)), storageProof));

        // Popping the call rewinds the committed root; the storage proof no longer matches.
        engine.popBlock();
        byte[] rootAfterPop = engine.stateRoot();
        assertTrue(!java.util.Arrays.equals(root, rootAfterPop));
    }
}
