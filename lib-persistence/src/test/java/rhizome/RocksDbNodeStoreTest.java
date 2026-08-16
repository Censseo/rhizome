package rhizome;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.ChainEngineTestAccess;
import rhizome.core.blockchain.ChainStore;
import rhizome.core.blockchain.ChainStoreContract;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.NonceStore;
import rhizome.core.blockchain.NonceStoreContract;
import rhizome.core.blockchain.TestNodeStores;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PrivateKey;
import rhizome.crypto.PublicKey;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerContract;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.LedgerOp;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.persistence.rocksdb.RocksDbNodeStore;

/**
 * RocksDB-specific node-store behaviour: header backfill, pruning, legacy-database migration,
 * and persistence across a reopen — none of which an in-memory store can exhibit. The baseline
 * append/pop/index and chain-store/nonce-store/ledger behaviour both backends already satisfy
 * lives in {@link ChainStoreContract}, {@link NonceStoreContract} and {@link LedgerContract},
 * which this class also implements — see {@code InMemoryChainStoreTest},
 * {@code InMemoryNonceStoreTest} and {@code InMemoryLedgerTest} for the other side.
 */
class RocksDbNodeStoreTest implements ChainStoreContract, NonceStoreContract, LedgerContract {

    @TempDir
    Path tempDir;

    private RocksDbNodeStore opened;

    private RocksDbNodeStore openStore() throws IOException {
        if (opened == null) {
            opened = new RocksDbNodeStore(tempDir.resolve("contract-db").toString());
        }
        return opened;
    }

    @Override
    public ChainStore newChainStore() throws Exception {
        return openStore().chainStore();
    }

    @Override
    public NonceStore newNonceStore() throws Exception {
        return openStore().nonceStore();
    }

    @Override
    public Ledger newLedger() throws Exception {
        return openStore().ledger();
    }

    @AfterEach
    void closeContractStore() {
        if (opened != null) {
            opened.close();
        }
    }

    private NetworkParameters fastParams() {
        return NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256)
            .genesisDifficulty(4)
            .build();
    }

    @Test
    void chainStoreLedgerAndNonceStoreAreMemoized() throws IOException {
        // Each used to build a NEW view object per call: ChainEngine.boot and DomainStateAdapter
        // could then end up talking to DIFFERENT view instances over the same database, which
        // NodeStores exists to make impossible (a crash losing nonces the committed blocks already
        // assumed). The three views being the SAME instance across calls is that guarantee, in a
        // test a future regression would actually fail.
        try (RocksDbNodeStore store = new RocksDbNodeStore(tempDir.resolve("db").toString())) {
            org.junit.jupiter.api.Assertions.assertSame(store.chainStore(), store.chainStore());
            org.junit.jupiter.api.Assertions.assertSame(store.ledger(), store.ledger());
            org.junit.jupiter.api.Assertions.assertSame(store.nonceStore(), store.nonceStore());
        }
    }

    @Test
    void straightThroughLedgerNoncesAndWatermarkSurviveReopen() throws IOException {
        // The straight-through write path (no open block commit — genesis/snapshot seeding) must
        // be durable across a reopen, exactly like the staged commit path: a ledger balance, a
        // nonce, and the nonce sync watermark are the three things boot recovery re-reads.
        String path = tempDir.resolve("db").toString();
        PublicAddress wallet = PublicAddress.random();
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            Ledger ledger = store.ledger();
            ledger.createWallet(wallet);
            ledger.deposit(wallet, new TransactionAmount(100));
            store.nonceStore().set(wallet, 3L);
            store.nonceStore().markSyncedThrough(42L);
        }
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            assertEquals(100, store.ledger().getWalletValue(wallet).amount());
            assertEquals(3L, store.nonceStore().next(wallet));
            assertEquals(42L, store.nonceStore().syncedThroughHeight());
        }
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
    void stagedLedgerWritesAreVisibleWithinTheBlockAndDroppedOnDiscard() throws IOException {
        // The ledger writes made during a block stage in an overlay so they flush atomically with the
        // block/height in append (audit S3). Within the open commit they read back (read-your-writes);
        // if the commit is discarded (a rejected/failed block) they never touch the column family.
        try (RocksDbNodeStore store = new RocksDbNodeStore(tempDir.resolve("db").toString())) {
            ChainStore chain = store.chainStore();
            Ledger ledger = store.ledger();
            PublicAddress w = PublicAddress.random();
            ledger.createWallet(w);
            ledger.deposit(w, new TransactionAmount(100)); // committed value (no open block commit)

            chain.beginBlockCommit();
            ledger.deposit(w, new TransactionAmount(50));
            assertEquals(150, ledger.getWalletValue(w).amount()); // visible within the block
            chain.discardBlockCommit();
            assertEquals(100, ledger.getWalletValue(w).amount()); // dropped: the column family is untouched
        }
    }

    @Test
    void bulkSeededLedgerAndNoncesAreReadableAfterAReopen() throws IOException {
        // The snap-sync seed writes one entry per account/nonce through the bulk window the
        // bootstrap marker opens. What this pins: those writes reach the right column families
        // and are still there after a close/reopen, and endBootstrap clears the marker.
        //
        // What it CANNOT pin, despite the barrier living in endBootstrap: the syncWal() fsync.
        // A clean close() flushes RocksDB, so removing BOTH barriers (endBootstrap's and
        // close()'s) leaves this test — and the whole class — green; verified by deliberate
        // mutation, not assumed. Observing an fsync needs a power cut, not a JVM test. The
        // barrier is held by review; what makes a lost tail DETECTABLE rather than silent is
        // the marker, and that is the test below.
        String path = tempDir.resolve("db").toString();
        PublicAddress wallet = PublicAddress.random();
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            store.beginBootstrap();
            store.ledger().createWallet(wallet);
            store.ledger().deposit(wallet, new TransactionAmount(100));
            store.nonceStore().set(wallet, 3L);
            store.endBootstrap();
        }
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            assertEquals(100, store.ledger().getWalletValue(wallet).amount());
            assertEquals(3L, store.nonceStore().next(wallet));
            assertFalse(store.bootstrapInProgress(), "a closed window leaves no marker behind");
        }
    }

    @Test
    void anUnclosedSeedingWindowKeepsItsMarkerAcrossAReopen() throws IOException {
        // The property that actually carries the durability argument (audit M8): a seed that
        // never reached endBootstrap — a crash mid-import — leaves the marker set, so the next
        // boot refuses to run on half-seeded state instead of diverging silently. This is what
        // makes the unsynced bulk window sound; the fsync barrier only bounds how much of a
        // successfully closed window a power loss can take back.
        String path = tempDir.resolve("crashed").toString();
        PublicAddress wallet = PublicAddress.random();
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            store.beginBootstrap();
            store.ledger().createWallet(wallet);
        }
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            assertTrue(store.bootstrapInProgress(), "an interrupted seed must be detected at boot");
        }
    }

    @Test
    void journalDeleteRidesTheOpenCommitBatch() throws IOException {
        // Revert inside an open commit (the popBlock reorg path) must stage the journal delete
        // with the inverses: deleting it straight through while the inverses were still staged
        // left a crash window where the journal was durably gone but the inverses never landed.
        // Observable seam: a DISCARDED commit must leave the journal — and the balance — intact.
        try (RocksDbNodeStore store = new RocksDbNodeStore(tempDir.resolve("db").toString())) {
            ChainStore chain = store.chainStore();
            Ledger ledger = store.ledger();
            PublicAddress w = PublicAddress.random();
            ledger.createWallet(w);
            ledger.deposit(w, new TransactionAmount(100));
            ledger.deposit(w, new TransactionAmount(50));
            ledger.applyBlock(2, List.of(new LedgerOp(LedgerOp.Op.DEPOSIT, w, 50))); // durable journal

            chain.beginBlockCommit();
            assertTrue(ledger.revertBlock(2));
            assertEquals(100, ledger.getWalletValue(w).amount()); // staged inverse reads back
            chain.discardBlockCommit(); // the reorg is abandoned: nothing may have leaked through

            assertEquals(150, ledger.getWalletValue(w).amount(), "the staged inverse was discarded");
            assertTrue(ledger.revertBlock(2), "the journal must survive the discarded commit");
            assertEquals(100, ledger.getWalletValue(w).amount());
            assertFalse(ledger.revertBlock(2), "the revert consumed its journal");
        }
    }

    @Test
    void aFailedRevertOutsideACommitLeavesNoResidue() throws IOException {
        // Boot reconciliation reverts with no commit open. A journal whose replay throws part-way
        // (corruption) must leave NOTHING behind: the straight-through replay applied the valid
        // prefix with one synced put per op before throwing, so the next boot's replay of the
        // still-present journal double-reverted it and wedged on the checked arithmetic.
        String path = tempDir.resolve("db").toString();
        PublicAddress wallet = PublicAddress.random();
        PublicAddress ghost = PublicAddress.random();
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            Ledger ledger = store.ledger();
            ledger.createWallet(wallet);
            ledger.deposit(wallet, new TransactionAmount(100));
            // Replayed in reverse: the wallet credit succeeds, the ghost debit then throws.
            ledger.applyBlock(2, List.of(
                new LedgerOp(LedgerOp.Op.WITHDRAW, ghost, 50),
                new LedgerOp(LedgerOp.Op.WITHDRAW, wallet, 100)));
            assertThrows(LedgerException.class, () -> ledger.revertBlock(2));
            assertEquals(100, ledger.getWalletValue(wallet).amount(),
                "a failed revert must not partially apply");
        }
        // The journal survived the failure intact: the boot sweep can retry it (and fail the
        // same clean way) rather than compounding a partial revert.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            assertEquals(100, store.ledger().getWalletValue(wallet).amount());
            assertThrows(LedgerException.class, () -> store.ledger().revertBlock(2));
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
            var pair = generateKeyPairTyped();
            PublicKey key = pair.publicKey();
            PrivateKey priv = pair.privateKey();
            PublicAddress sender = PublicAddress.of(key);
            snapshot.put(sender, new TransactionAmount(1_000_000L));

            // Via NodeStores (store implements it): ledger/chain/nonceStore are the SAME memoized
            // instances the local variables above already hold, so this is not a second database.
            ChainEngine engine = ChainEngine.boot(params, store, snapshot).clock(clock::get).build();
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

            ChainEngineTestAccess.popBlock(engine);
            assertEquals(1, chain.height());
            assertFalse(chain.hasTransaction(send.hashContents()));
            assertEquals(1_000_000L, ledger.getWalletValue(sender).amount());
        }
    }

    @Test
    void uncleBodiesRoundTripThroughTheStore() throws IOException {
        // ChainStore.putUncle/uncleAt (audit: uncle-sync blocker): an uncle body referenced by a
        // canonical block must survive the engine's bounded in-memory orphan pool — including
        // across a restart — so this node can always serve it to syncing peers.
        NetworkParameters params = fastParams();
        String path = tempDir.resolve("db").toString();
        AtomicLong clock = new AtomicLong(0);
        PublicAddress miner = PublicAddress.random();
        Block uncle;
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainEngine engine = ChainEngine.boot(
                    params,
                    store,
                    new LedgerSnapshot("test", 0, params.chainId()))
                .clock(clock::get)
                .build();
            uncle = mine(engine, params, miner, List.of(), clock);
            store.chainStore().putUncle(uncle.hash(), uncle);
            Block back = store.chainStore().uncleAt(uncle.hash());
            assertEquals(uncle.hash(), back.hash(), "stored uncle must decode to the same block");
            assertArrayEquals(BlockCodec.encode(uncle), BlockCodec.encode(back));
            org.junit.jupiter.api.Assertions.assertNull(
                store.chainStore().uncleAt(rhizome.crypto.SHA256Hash.random()),
                "an unknown hash has no uncle body");
        }
        // Persisted, not just a memory overlay: a reopened store still serves the body.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            assertEquals(uncle.hash(), store.chainStore().uncleAt(uncle.hash()).hash());
        }
    }

    @Test
    void statePersistsAcrossReopen() throws IOException {
        NetworkParameters params = fastParams();
        String path = tempDir.resolve("db").toString();
        AtomicLong clock = new AtomicLong(0);

        var pair = generateKeyPairTyped();
        PublicKey key = pair.publicKey();
        PrivateKey priv = pair.privateKey();
        PublicAddress sender = PublicAddress.of(key);
        PublicAddress miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        java.math.BigInteger workAfter;
        rhizome.crypto.SHA256Hash tipAfter;

        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainEngine engine = ChainEngine.boot(params, store, snapshot).clock(clock::get).build();
            Transaction send = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(100_000),
                key, new TransactionAmount(0), clock.get(), params.chainId(), 0);
            send.sign(priv);
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(send), clock)));
            workAfter = engine.totalWork();
            tipAfter = engine.tipHash();
        }

        // Reopen: derived state (height, tip, work, nonces) must rebuild from disk.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainEngine reloaded = ChainEngine.boot(params, store, snapshot).clock(clock::get).build();
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
            ChainEngine engine = ChainEngine.boot(params, store, snapshot).clock(clock::get).build();
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
    void headerBackfillWatermarkTracksTheTipSoARestartSkipsTheSweep() throws Exception {
        // Every append advances the "headersBackfilledThrough" watermark to the new tip (audit P12),
        // so a fully-populated chain reopens in O(1) instead of re-probing every height's header.
        NetworkParameters params = fastParams();
        String path = tempDir.resolve("db").toString();
        AtomicLong clock = new AtomicLong(0);
        PublicAddress miner = PublicAddress.random();
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainEngine engine = ChainEngine.boot(
                    params,
                    store,
                    new LedgerSnapshot("test", 0, params.chainId()))
                .clock(clock::get)
                .build();
            for (int i = 0; i < 3; i++) {
                assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(), clock)));
            }
            assertEquals(4, store.chainStore().height());
        }
        // White-box: the watermark equals the tip height, so backfillHeaders early-returns on reopen.
        assertArrayEquals(rhizome.core.common.Utils.longToBytes(4L),
            rawGet(path, 4 /*meta*/, "headersBackfilledThrough".getBytes()));
    }

    @Test
    void aReopenSkipsHeaderBackfillOnceTheWatermarkCoversTheTip() throws Exception {
        // A legacy database (bodies, no headers CF) is backfilled and its watermark recorded on the
        // first open. We then delete a header behind the store's back, leaving its body and the
        // watermark intact: a sweep, had it run, would re-derive the header from the body. Proving
        // the header stays absent after reopen proves the O(height) sweep was skipped (audit P12).
        String path = tempDir.resolve("legacy-db").toString();
        Block b1 = looseBlock(1, rhizome.crypto.SHA256Hash.random());
        Block b2 = looseBlock(2, b1.hash());
        Block b3 = looseBlock(3, b2.hash());
        writeLegacyDb(path, List.of(b1, b2, b3));

        // First open backfills the missing headers and records the watermark (= 3).
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            assertEquals(b2.hash(), store.chainStore().headerAt(2L).hash());
        }
        assertArrayEquals(rhizome.core.common.Utils.longToBytes(3L),
            rawGet(path, 4 /*meta*/, "headersBackfilledThrough".getBytes()));

        // Delete header@2 (body@2 and the watermark stay).
        rawDelete(path, 2 /*headers*/, rhizome.core.common.Utils.longToBytes(2L));

        // Reopen: watermark already covers the tip → the sweep is skipped, header@2 not re-derived.
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            assertEquals(3, store.chainStore().height());
        }
        assertFalse(rawGet(path, 2 /*headers*/, rhizome.core.common.Utils.longToBytes(2L)) != null,
            "backfill sweep must have been skipped; header@2 must remain absent");
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
            ChainEngine engine = ChainEngine.boot(
                    params,
                    store,
                    new LedgerSnapshot("test", 0, params.chainId()))
                .clock(clock::get)
                .build();
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
            ChainEngine reloaded = ChainEngine.boot(
                    params,
                    store,
                    new LedgerSnapshot("test", 0, params.chainId()))
                .clock(clock::get)
                .build();
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
            ChainEngine engine = ChainEngine.boot(
                    params,
                    store,
                    new LedgerSnapshot("test", 0, params.chainId()))
                .clock(clock::get)
                .build();
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

        var pair = generateKeyPairTyped();
        PublicKey key = pair.publicKey();
        PrivateKey priv = pair.privateKey();
        PublicAddress sender = PublicAddress.of(key);
        PublicAddress miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        snapshot.put(sender, new TransactionAmount(1_000_000L));

        // Build a chain of three blocks each carrying one account transaction (nonces 0,1,2).
        try (RocksDbNodeStore store = new RocksDbNodeStore(path)) {
            ChainEngine engine = ChainEngine.boot(params, store, snapshot).clock(clock::get).build();
            for (int n = 0; n < 3; n++) {
                Transaction send = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1_000),
                    key, new TransactionAmount(0), clock.get(), params.chainId(), n);
                send.sign(priv);
                assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mine(engine, params, miner, List.of(send), clock)));
            }
            assertEquals(3L, engine.nextNonce(sender));

            // pop then re-add: the persisted nonce must track exactly (3 → 2 → 3).
            ChainEngineTestAccess.popBlock(engine);
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
            ChainEngine reloaded = ChainEngine.boot(
                    params,
                    TestNodeStores.mixing(store.ledger(), guarded, store.nonceStore()),
                    snapshot)
                .clock(clock::get)
                .build();
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

    /** The modern node-store column families, in the store's own open order. */
    private static List<org.rocksdb.ColumnFamilyDescriptor> modernCfs() {
        return List.of(
            new org.rocksdb.ColumnFamilyDescriptor(org.rocksdb.RocksDB.DEFAULT_COLUMN_FAMILY),
            new org.rocksdb.ColumnFamilyDescriptor("blocks".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("headers".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("txindex".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("meta".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("ledger".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("ledger_journal".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("nonces".getBytes()),
            new org.rocksdb.ColumnFamilyDescriptor("uncles".getBytes()));
    }

    /** Reads one key from a column family of an existing modern database, opened directly. */
    private static byte[] rawGet(String path, int cfIndex, byte[] key) throws Exception {
        org.rocksdb.RocksDB.loadLibrary();
        List<org.rocksdb.ColumnFamilyHandle> handles = new java.util.ArrayList<>();
        try (org.rocksdb.DBOptions opts = new org.rocksdb.DBOptions()) {
            org.rocksdb.RocksDB raw = org.rocksdb.RocksDB.open(opts, path, modernCfs(), handles);
            try {
                return raw.get(handles.get(cfIndex), key);
            } finally {
                handles.forEach(org.rocksdb.ColumnFamilyHandle::close);
                raw.close();
            }
        }
    }

    /** Deletes one key from a column family of an existing modern database, opened directly. */
    private static void rawDelete(String path, int cfIndex, byte[] key) throws Exception {
        org.rocksdb.RocksDB.loadLibrary();
        List<org.rocksdb.ColumnFamilyHandle> handles = new java.util.ArrayList<>();
        try (org.rocksdb.DBOptions opts = new org.rocksdb.DBOptions();
             org.rocksdb.WriteOptions wo = new org.rocksdb.WriteOptions()) {
            org.rocksdb.RocksDB raw = org.rocksdb.RocksDB.open(opts, path, modernCfs(), handles);
            try {
                raw.delete(handles.get(cfIndex), wo, key);
            } finally {
                handles.forEach(org.rocksdb.ColumnFamilyHandle::close);
                raw.close();
            }
        }
    }

    /** Writes a pre-headers legacy database: block bodies + height, no {@code headers} CF. */
    private static void writeLegacyDb(String path, List<Block> blocks) throws Exception {
        org.rocksdb.RocksDB.loadLibrary();
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
                raw.put(metaCf, wo, "height".getBytes(),
                    rhizome.core.common.Utils.longToBytes((long) blocks.size()));
            } finally {
                handles.forEach(org.rocksdb.ColumnFamilyHandle::close);
                raw.close();
            }
        }
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
