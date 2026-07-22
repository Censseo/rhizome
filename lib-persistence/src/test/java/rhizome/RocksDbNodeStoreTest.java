package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPair;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
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
        rhizome.crypto.SHA256Hash tipAfter;

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

    @Test
    void headerColumnFamilyStaysConsistentWithBodiesAcrossReopen() throws IOException {
        NetworkParameters params = fastParams();
        String path = tempDir.resolve("db").toString();
        AtomicLong clock = new AtomicLong(0);

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        PublicAddress miner = PublicAddress.random();

        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainStore chain = store.chainStore();
            ChainEngine engine = ChainEngine.init(params, store.ledger(), chain, snapshot, null, clock::get);
            for (int i = 0; i < 3; i++) {
                assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(), clock)));
            }
            for (long h = 1; h <= chain.height(); h++) {
                // The header read from the dedicated CF must hash exactly as the body's header.
                assertEquals(chain.blockAt(h).hash(), chain.headerAt(h).hash());
                assertEquals(BlockHeader.of(chain.blockAt(h)), chain.headerAt(h));
            }
        }

        // Reopen: headers were persisted in the same batch as the bodies.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainStore chain = store.chainStore();
            assertEquals(4, chain.height());
            for (long h = 1; h <= chain.height(); h++) {
                assertEquals(chain.blockAt(h).hash(), chain.headerAt(h).hash());
            }
        }
    }

    @Test
    void backfillsHeadersForLegacyDatabaseWithoutHeaderCf() throws Exception {
        org.rocksdb.RocksDB.loadLibrary();
        String path = tempDir.resolve("legacy-db").toString();

        // Build a few blocks and remember their hashes.
        Block b1 = looseBlock(1, rhizome.crypto.SHA256Hash.random());
        Block b2 = looseBlock(2, b1.hash());
        Block b3 = looseBlock(3, b2.hash());
        List<Block> blocks = List.of(b1, b2, b3);

        // Write a *legacy* database: the pre-headers column-family set (no "headers" CF).
        List<org.rocksdb.ColumnFamilyDescriptor> legacy = List.of(
            new org.rocksdb.ColumnFamilyDescriptor(org.rocksdb.RocksDB.DEFAULT_COLUMN_FAMILY),
            new org.rocksdb.ColumnFamilyDescriptor("blocks".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("txindex".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("meta".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("ledger".getBytes()));
        List<org.rocksdb.ColumnFamilyHandle> handles = new java.util.ArrayList<>();
        try (org.rocksdb.DBOptions options = new org.rocksdb.DBOptions()
                .setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
             org.rocksdb.WriteOptions wo = new org.rocksdb.WriteOptions()) {
            org.rocksdb.RocksDB raw = org.rocksdb.RocksDB.open(options, path, legacy, handles);
            try {
                org.rocksdb.ColumnFamilyHandle blocksCf = handles.get(1);
                org.rocksdb.ColumnFamilyHandle metaCf = handles.get(3);
                for (int i = 0; i < blocks.size(); i++) {
                    raw.put(blocksCf, wo, rhizome.core.common.Utils.longToBytes(i + 1L),
                        BlockCodec.encode(blocks.get(i)));
                }
                raw.put(metaCf, wo, "height".getBytes(), rhizome.core.common.Utils.longToBytes(3L));
            } finally {
                handles.forEach(org.rocksdb.ColumnFamilyHandle::close);
                raw.close();
            }
        }

        // Reopen through RocksDbNodeStore: the missing headers CF is created and backfilled.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainStore chain = store.chainStore();
            assertEquals(3, chain.height());
            for (int i = 0; i < blocks.size(); i++) {
                assertEquals(blocks.get(i).hash(), chain.headerAt(i + 1L).hash());
                assertEquals(BlockHeader.of(blocks.get(i)), chain.headerAt(i + 1L));
            }
        }
    }

    @Test
    void prunedStoreKeepsHeadersRecentBodiesAndGenesis() throws IOException {
        NetworkParameters params = fastParams();
        String path = tempDir.resolve("db").toString();
        int keep = 5;
        PublicAddress miner = PublicAddress.random();

        AtomicLong clock = new AtomicLong(0);
        try (RocksDbNodeStore store = new RocksDbNodeStore(path, keep)) {
            ChainStore chain = store.chainStore();
            // A pruned node must persist nonces (it cannot rebuild them from discarded bodies).
            ChainEngine engine = ChainEngine.init(params, store.ledger(), chain, store.nonceStore(),
                new LedgerSnapshot("test", 0, params.chainId()), null, clock::get, null, null, null, null, null);
            for (int i = 0; i < 10; i++) {
                assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(), clock)));
            }
            long height = chain.height(); // 11 (genesis + 10)
            assertEquals(11, height);

            // The last `keep` bodies are retained; older ones (except genesis) are pruned.
            assertEquals(height - keep + 1, chain.prunedBelow());
            assertTrue(chain.hasBody(1), "genesis body always kept");
            for (long h = height - keep + 1; h <= height; h++) {
                assertTrue(chain.hasBody(h), "recent body " + h + " must remain");
            }
            for (long h = 2; h < height - keep + 1; h++) {
                assertFalse(chain.hasBody(h), "old body " + h + " must be pruned");
            }
            // Headers survive for every height — including pruned ones — which is the point.
            for (long h = 1; h <= height; h++) {
                assertEquals(h, chain.headerAt(h).id());
            }
        }

        // Reopen the pruned store: derived state rebuilds header-only from the persisted
        // nonces, without touching a single (now-absent) old body.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path, keep)) {
            ChainEngine reloaded = ChainEngine.init(params, store.ledger(), store.chainStore(),
                store.nonceStore(), new LedgerSnapshot("test", 0, params.chainId()), null, clock::get,
                null, null, null, null, null);
            assertEquals(11, reloaded.height());
            assertEquals(11 - keep + 1, store.chainStore().prunedBelow());
        }
    }

    @Test
    void enablingPruningOnAnArchivePrunesOldBodiesAtBoot() throws IOException {
        NetworkParameters params = fastParams();
        String path = tempDir.resolve("db").toString();
        PublicAddress miner = PublicAddress.random();

        // Build a full archive of 8 blocks.
        AtomicLong clock = new AtomicLong(0);
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainEngine engine = ChainEngine.init(params, store.ledger(), store.chainStore(),
                new LedgerSnapshot("test", 0, params.chainId()), null, clock::get);
            for (int i = 0; i < 7; i++) {
                assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(), clock)));
            }
            assertEquals(8, store.chainStore().height());
            assertTrue(store.chainStore().hasBody(2), "archive keeps every body");
        }

        // Reopen with pruning: the boot catch-up discards the now-old bodies.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path, 3)) {
            ChainStore chain = store.chainStore();
            assertEquals(8 - 3 + 1, chain.prunedBelow());
            assertFalse(chain.hasBody(2));
            assertTrue(chain.hasBody(6));
            assertTrue(chain.hasBody(1)); // genesis
        }
    }

    @Test
    void persistedNoncesLetRestartSkipTheBodyWalk() throws IOException {
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

        // Build a chain of three blocks each carrying one account transaction (nonces 0,1,2).
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainEngine engine = ChainEngine.init(params, store.ledger(), store.chainStore(),
                store.nonceStore(), snapshot, null, clock::get, null, null, null, null, null);
            for (int n = 0; n < 3; n++) {
                Transaction send = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1_000),
                    key, new TransactionAmount(0), clock.get(), params.chainId(), n);
                send.sign(priv);
                assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(send), clock)));
            }
            assertEquals(3L, engine.nextNonce(sender));

            // pop then re-add: the persisted nonce must track exactly (3 → 2 → 3).
            engine.popBlock();
            assertEquals(2L, engine.nextNonce(sender));
            Transaction resend = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1_000),
                key, new TransactionAmount(0), clock.get(), params.chainId(), 2);
            resend.sign(priv);
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(resend), clock)));
            assertEquals(3L, engine.nextNonce(sender));
        }

        // Reopen behind a store that forbids reading any historical body (only genesis and
        // the tip may be read). Because the nonces are persisted, the boot rebuild must not
        // walk the bodies — if it did, this throws.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainStore guarded = new NoHistoricalBodyStore(store.chainStore());
            ChainEngine reloaded = ChainEngine.init(params, store.ledger(), guarded,
                store.nonceStore(), snapshot, null, clock::get, null, null, null, null, null);
            assertEquals(4, reloaded.height());
            assertEquals(3L, reloaded.nextNonce(sender));
        }
    }

    /** A {@link ChainStore} that refuses to read a body strictly between genesis and the tip. */
    private static final class NoHistoricalBodyStore implements ChainStore {
        private final ChainStore inner;
        NoHistoricalBodyStore(ChainStore inner) { this.inner = inner; }
        @Override public long height() { return inner.height(); }
        @Override public Block blockAt(long height) {
            if (height > 1 && height < inner.height()) {
                throw new AssertionError("boot rebuild read a historical body at height " + height
                    + "; persisted nonces must make it header-only");
            }
            return inner.blockAt(height);
        }
        @Override public BlockHeader headerAt(long height) { return inner.headerAt(height); }
        @Override public void append(Block block) { inner.append(block); }
        @Override public void pop() { inner.pop(); }
        @Override public boolean hasTransaction(rhizome.crypto.SHA256Hash h) { return inner.hasTransaction(h); }
    }

    /** A standalone, un-mined block (valid encoding; not chain-validated) for storage tests. */
    private Block looseBlock(int id, rhizome.crypto.SHA256Hash parent) {
        var b = (BlockImpl) BlockImpl.builder()
            .id(id).timestamp(1_000_000L + id).difficulty(4)
            .lastBlockHash(parent).build();
        b.addTransaction(Transaction.of(PublicAddress.random(), new TransactionAmount(50L)));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(rhizome.crypto.SHA256Hash.random());
        return b;
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
