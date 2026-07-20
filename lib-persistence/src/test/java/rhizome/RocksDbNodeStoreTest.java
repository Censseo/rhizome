package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.core.common.Crypto.generateKeyPair;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.common.PowAlgorithm;
import rhizome.core.crypto.PrivateKey;
import rhizome.core.crypto.PublicKey;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.persistence.rocksdb.RocksDbNodeStore;

class RocksDbNodeStoreTest {

    @TempDir
    Path tempDir;

    private NetworkParameters fastParams() {
        return NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256)
            .genesisDifficulty(4)
            .build();
    }

    @Test
    void ledgerChecksArithmetic() throws IOException {
        try (RocksDbNodeStore store = new RocksDbNodeStore(tempDir.resolve("db").toString())) {
            Ledger ledger = store.ledger();
            PublicAddress wallet = PublicAddress.random();
            ledger.createWallet(wallet);
            ledger.deposit(wallet, new TransactionAmount(100));
            assertEquals(100, ledger.getWalletValue(wallet).amount());
            assertThrows(LedgerException.class, () -> ledger.withdraw(wallet, new TransactionAmount(200)));
            assertEquals(100, ledger.getWalletValue(wallet).amount());
        }
    }

    @Test
    void chainStoreAppendPopIsAtomicAndIndexed() throws IOException {
        NetworkParameters params = fastParams();
        try (RocksDbNodeStore store = new RocksDbNodeStore(tempDir.resolve("db").toString())) {
            ChainStore chain = store.chainStore();
            Ledger ledger = store.ledger();
            AtomicLong clock = new AtomicLong(0);

            LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
            var pair = generateKeyPair();
            PublicKey key = PublicKey.of(pair.getPublic());
            PrivateKey priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
            PublicAddress sender = PublicAddress.of(key);
            snapshot.put(sender, new TransactionAmount(1_000_000L));

            ChainEngine engine = ChainEngine.init(params, ledger, chain, snapshot, null, clock::get);
            PublicAddress recipient = PublicAddress.random();
            PublicAddress miner = PublicAddress.random();

            Transaction send = Transaction.of(sender, recipient, new TransactionAmount(100_000),
                key, new TransactionAmount(500), clock.get(), params.chainId(), 0);
            send.sign(priv);

            Block block = mine(engine, params, miner, List.of(send), clock);
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(block));

            assertEquals(2, chain.height());
            assertTrue(chain.hasTransaction(send.hashContents()));
            assertEquals(100_000L, ledger.getWalletValue(recipient).amount());

            engine.popBlock();
            assertEquals(1, chain.height());
            assertFalse(chain.hasTransaction(send.hashContents()));
            assertEquals(1_000_000L, ledger.getWalletValue(sender).amount());
        }
    }

    @Test
    void statePersistsAcrossReopen() throws IOException {
        NetworkParameters params = fastParams();
        String path = tempDir.resolve("db").toString();
        AtomicLong clock = new AtomicLong(0);

        var pair = generateKeyPair();
        PublicKey key = PublicKey.of(pair.getPublic());
        PrivateKey priv = new PrivateKey((Ed25519PrivateKeyParameters) pair.getPrivate());
        PublicAddress sender = PublicAddress.of(key);
        PublicAddress miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        java.math.BigInteger workAfter;
        rhizome.core.crypto.SHA256Hash tipAfter;

        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainEngine engine = ChainEngine.init(params, store.ledger(), store.chainStore(), snapshot, null, clock::get);
            Transaction send = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(100_000),
                key, new TransactionAmount(0), clock.get(), params.chainId(), 0);
            send.sign(priv);
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(send), clock)));
            workAfter = engine.totalWork();
            tipAfter = engine.tipHash();
        }

        // Reopen: derived state (height, tip, work, nonces) must rebuild from disk.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainEngine reloaded = ChainEngine.init(params, store.ledger(), store.chainStore(), snapshot, null, clock::get);
            assertEquals(2, reloaded.height());
            assertEquals(tipAfter, reloaded.tipHash());
            assertEquals(workAfter, reloaded.totalWork());
            assertEquals(1, reloaded.nextNonce(sender));
        }
    }

    private Block mine(ChainEngine engine, NetworkParameters params, PublicAddress miner,
                       List<Transaction> transactions, AtomicLong clock) {
        long height = engine.height() + 1;
        var b = BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        transactions.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        ((BlockImpl) b).merkleRoot(tree.getRootHash());
        ((BlockImpl) b).nonce(Miner.mineNonce(b.hash(), ((BlockImpl) b).difficulty(), params.powAlgorithm()));
        return b;
    }
}
