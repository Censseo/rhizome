package rhizome.persistence.rocksdb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.block.HeaderCodec;
import rhizome.core.blockchain.ChainStore;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
import rhizome.persistence.PersistenceException;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

import static rhizome.core.common.Utils.bytesToLong;
import static rhizome.core.common.Utils.longToBytes;

/**
 * RocksDB-backed node storage: a single database with column families for
 * blocks, the transaction index, chain metadata, and the ledger. This is the
 * full-node performance path (the pure-Java LevelDB store remains for
 * native-image / tests / light nodes).
 *
 * <p>Sharing one database lets block application commit atomically via a single
 * {@link WriteBatch} — the fix for Pandanite's independent LevelDB directories
 * that could disagree after a crash (issue #54). {@link #chainStore()} already
 * commits a block and its transaction-index entries in one batch; a future
 * refactor can extend the same batch across the ledger.
 */
public final class RocksDbNodeStore implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private static final byte[] CF_BLOCKS = "blocks".getBytes();
    private static final byte[] CF_HEADERS = "headers".getBytes();
    private static final byte[] CF_TXINDEX = "txindex".getBytes();
    private static final byte[] CF_META = "meta".getBytes();
    private static final byte[] CF_LEDGER = "ledger".getBytes();
    private static final byte[] CF_NONCES = "nonces".getBytes();
    private static final byte[] HEIGHT_KEY = "height".getBytes();
    private static final byte[] PRUNED_BELOW_KEY = "prunedBelow".getBytes();
    private static final byte[] NONCE_HEIGHT_KEY = "nonceHeight".getBytes();
    /** Set while a snap-sync bootstrap is seeding the several stores; cleared only on success. */
    private static final byte[] BOOTSTRAP_KEY = "bootstrapInProgress".getBytes();

    private static final long GENESIS_HEIGHT = 1L;

    /** Bodies for the most recent {@code keepBlocks} heights are retained (0 = archive, keep all). */
    private final int keepBlocks;

    private final RocksDB db;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle blocksCf;
    private final ColumnFamilyHandle headersCf;
    private final ColumnFamilyHandle txIndexCf;
    private final ColumnFamilyHandle metaCf;
    private final ColumnFamilyHandle ledgerCf;
    private final ColumnFamilyHandle noncesCf;
    private final WriteOptions writeOptions = new WriteOptions();

    public RocksDbNodeStore(String path) throws IOException {
        this(path, 0);
    }

    /**
     * @param keepBlocks number of most-recent block bodies to retain (0 = archive node,
     *                   keep every body). Headers, the transaction index and genesis are
     *                   always retained. The caller is responsible for enforcing a safe
     *                   floor (≥ the deepest history the engine may read: reorg depth,
     *                   uncle depth, difficulty/median windows).
     */
    public RocksDbNodeStore(String path, int keepBlocks) throws IOException {
        this.keepBlocks = keepBlocks;
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_BLOCKS),
            new ColumnFamilyDescriptor(CF_HEADERS),
            new ColumnFamilyDescriptor(CF_TXINDEX),
            new ColumnFamilyDescriptor(CF_META),
            new ColumnFamilyDescriptor(CF_LEDGER),
            new ColumnFamilyDescriptor(CF_NONCES));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try {
            DBOptions options = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
            this.db = RocksDB.open(options, path, descriptors, handles);
        } catch (RocksDBException e) {
            throw new IOException("Failed to open RocksDB at " + path, e);
        }
        this.defaultCf = handles.get(0);
        this.blocksCf = handles.get(1);
        this.headersCf = handles.get(2);
        this.txIndexCf = handles.get(3);
        this.metaCf = handles.get(4);
        this.ledgerCf = handles.get(5);
        this.noncesCf = handles.get(6);
        backfillHeaders();
        catchUpPruning();
    }

    /**
     * Boot catch-up: if this node runs pruned but holds bodies older than the retention
     * window (e.g. pruning was just enabled on an archive, or {@code keepBlocks} shrank),
     * discard them in one pass so the on-disk state matches the configured retention.
     */
    private void catchUpPruning() {
        if (keepBlocks <= 0) {
            return;
        }
        long height = new RocksChainStore().height();
        long firstToKeep = height - keepBlocks + 1;
        if (firstToKeep > GENESIS_HEIGHT + 1) {
            new RocksChainStore().pruneBodiesBelow(firstToKeep);
        }
    }

    /**
     * Boot migration: an older database has block bodies but no {@code headers}
     * column family. Derive every header from its stored block in one pass so
     * the engine can run header-only afterwards. Idempotent — a header already
     * present is left untouched — so a partially-backfilled database (crash
     * mid-migration) is completed on the next boot rather than restarted.
     */
    private void backfillHeaders() throws IOException {
        long height;
        try {
            byte[] value = db.get(metaCf, HEIGHT_KEY);
            height = value == null ? 0 : bytesToLong(value);
        } catch (RocksDBException e) {
            throw new IOException("Failed to read chain height during header backfill", e);
        }
        if (height == 0) {
            return; // fresh database: nothing to migrate
        }
        try {
            long migrated = 0;
            for (long h = 1; h <= height; h++) {
                byte[] key = heightKey(h);
                if (db.get(headersCf, key) != null) {
                    continue; // already backfilled
                }
                byte[] body = db.get(blocksCf, key);
                if (body == null) {
                    continue; // pruned body with no header: nothing to derive from
                }
                Block block = BlockCodec.decode(body);
                db.put(headersCf, writeOptions, key, HeaderCodec.encode(BlockHeader.of(block)));
                migrated++;
            }
            if (migrated > 0) {
                System.out.println("[RocksDbNodeStore] backfilled " + migrated + " block header(s)");
            }
        } catch (RocksDBException e) {
            throw new IOException("Failed to backfill block headers", e);
        }
    }

    public ChainStore chainStore() {
        return new RocksChainStore();
    }

    /**
     * Snap-sync bootstrap: adopts a run of already-validated headers as chain history
     * without their bodies. The store must hold exactly genesis (height 1) and the headers
     * must be contiguous from height 2. Afterwards {@code height()} is the last header's
     * height, and the body-less range is marked pruned so peers and the local engine treat
     * it exactly like a pruned node's discarded history.
     */
    public void bootstrapHeaders(List<BlockHeader> headers) {
        long height = new RocksChainStore().height();
        if (height != 1) {
            throw new IllegalStateException("bootstrap requires a store holding exactly genesis, height was " + height);
        }
        if (headers.isEmpty()) {
            return;
        }
        try (WriteBatch batch = new WriteBatch()) {
            long expected = 2;
            for (BlockHeader header : headers) {
                if (header.id() != expected) {
                    throw new IllegalArgumentException("non-contiguous bootstrap header at " + header.id()
                        + ", expected " + expected);
                }
                batch.put(headersCf, heightKey(expected), HeaderCodec.encode(header));
                expected++;
            }
            long tip = expected - 1;
            batch.put(metaCf, HEIGHT_KEY, heightKey(tip));
            batch.put(metaCf, PRUNED_BELOW_KEY, heightKey(tip + 1));
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new LedgerException("Failed to bootstrap headers", e);
        }
    }

    public Ledger ledger() {
        return new RocksLedger();
    }

    /** Persisted next-nonce-per-sender, so the engine need not replay bodies at boot. */
    public rhizome.core.blockchain.NonceStore nonceStore() {
        return new RocksNonceStore();
    }

    /**
     * Marks that a snap-sync bootstrap has begun seeding the several independent stores
     * (ledger, boxes, tokens, state, contracts, chain). Because those commit separately, a
     * crash between them leaves the node inconsistent; the marker lets boot detect that and
     * refuse to run on half-seeded data rather than silently diverging (audit M8).
     */
    public void beginBootstrap() {
        try {
            db.put(metaCf, writeOptions, BOOTSTRAP_KEY, new byte[] {1});
        } catch (RocksDBException e) {
            throw new PersistenceException("failed to set bootstrap marker", e);
        }
    }

    /** Clears the bootstrap marker after every store has been seeded and committed. */
    public void endBootstrap() {
        try {
            db.delete(metaCf, writeOptions, BOOTSTRAP_KEY);
        } catch (RocksDBException e) {
            throw new PersistenceException("failed to clear bootstrap marker", e);
        }
    }

    /** True if a previous bootstrap did not finish — the on-disk state must be treated as inconsistent. */
    public boolean bootstrapInProgress() {
        try {
            return db.get(metaCf, BOOTSTRAP_KEY) != null;
        } catch (RocksDBException e) {
            throw new PersistenceException("failed to read bootstrap marker", e);
        }
    }

    @Override
    public void close() {
        defaultCf.close();
        blocksCf.close();
        headersCf.close();
        txIndexCf.close();
        metaCf.close();
        ledgerCf.close();
        noncesCf.close();
        writeOptions.close();
        db.close();
    }

    private static byte[] heightKey(long height) {
        return longToBytes(height);
    }

    // ---- ChainStore view ----

    private final class RocksChainStore implements ChainStore {

        @Override
        public long height() {
            try {
                byte[] value = db.get(metaCf, HEIGHT_KEY);
                return value == null ? 0 : bytesToLong(value);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to read chain height", e);
            }
        }

        @Override
        public Block blockAt(long height) {
            try {
                byte[] value = db.get(blocksCf, heightKey(height));
                if (value == null) {
                    throw new IllegalArgumentException("No block at height " + height);
                }
                return BlockCodec.decode(value);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to read block " + height, e);
            }
        }

        @Override
        public BlockHeader headerAt(long height) {
            try {
                byte[] value = db.get(headersCf, heightKey(height));
                if (value != null) {
                    return HeaderCodec.decode(value);
                }
                // No stored header (should not happen post-backfill); fall back to the body.
                return BlockHeader.of(blockAt(height));
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to read header " + height, e);
            }
        }

        @Override
        public void append(Block block) {
            long expected = height() + 1;
            if (((BlockImpl) block).id() != expected) {
                throw new IllegalArgumentException(
                    "Expected block " + expected + " but got " + ((BlockImpl) block).id());
            }
            try (WriteBatch batch = new WriteBatch()) {
                byte[] key = heightKey(expected);
                batch.put(blocksCf, key, BlockCodec.encode(block));
                // The header is committed in the same batch as the body, so the two
                // column families can never disagree after a crash.
                batch.put(headersCf, key, HeaderCodec.encode(BlockHeader.of(block)));
                for (Transaction t : block.transactions()) {
                    if (!((TransactionImpl) t).isTransactionFee()) {
                        batch.put(txIndexCf, t.hashContents().toBytes(), key);
                    }
                }
                batch.put(metaCf, HEIGHT_KEY, key);
                // Incremental pruning (amortised O(1)): the body that just fell out of the
                // retention window is discarded in the same batch. Genesis is never pruned.
                if (keepBlocks > 0) {
                    long fallsOut = expected - keepBlocks;
                    if (fallsOut > GENESIS_HEIGHT) {
                        batch.delete(blocksCf, heightKey(fallsOut));
                        batch.put(metaCf, PRUNED_BELOW_KEY, heightKey(fallsOut + 1));
                    }
                }
                db.write(writeOptions, batch);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to append block " + expected, e);
            }
        }

        @Override
        public boolean hasBody(long height) {
            if (height == GENESIS_HEIGHT) {
                return true;
            }
            try {
                return db.get(blocksCf, heightKey(height)) != null;
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to probe body " + height, e);
            }
        }

        @Override
        public long prunedBelow() {
            try {
                byte[] value = db.get(metaCf, PRUNED_BELOW_KEY);
                return value == null ? 0 : bytesToLong(value);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to read prune watermark", e);
            }
        }

        @Override
        public void pruneBodiesBelow(long height) {
            long from = Math.max(GENESIS_HEIGHT + 1, prunedBelow());
            if (height <= from) {
                return;
            }
            try (WriteBatch batch = new WriteBatch()) {
                for (long h = from; h < height; h++) {
                    batch.delete(blocksCf, heightKey(h)); // headers + txindex retained
                }
                batch.put(metaCf, PRUNED_BELOW_KEY, heightKey(height));
                db.write(writeOptions, batch);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to prune bodies below " + height, e);
            }
        }

        @Override
        public void pop() {
            long height = height();
            if (height == 0) {
                throw new IllegalStateException("Cannot pop an empty chain");
            }
            Block tip = blockAt(height);
            try (WriteBatch batch = new WriteBatch()) {
                byte[] key = heightKey(height);
                batch.delete(blocksCf, key);
                batch.delete(headersCf, key);
                for (Transaction t : tip.transactions()) {
                    if (!((TransactionImpl) t).isTransactionFee()) {
                        batch.delete(txIndexCf, t.hashContents().toBytes());
                    }
                }
                batch.put(metaCf, HEIGHT_KEY, heightKey(height - 1));
                db.write(writeOptions, batch);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to pop block " + height, e);
            }
        }

        @Override
        public boolean hasTransaction(SHA256Hash contentHash) {
            try {
                return db.get(txIndexCf, contentHash.toBytes()) != null;
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to read tx index", e);
            }
        }
    }

    // ---- Ledger view (checked arithmetic, same semantics as LevelDBLedger) ----

    private final class RocksLedger implements Ledger {

        @Override
        public boolean hasWallet(PublicAddress wallet) {
            return rawValue(wallet) != null;
        }

        @Override
        public void createWallet(PublicAddress wallet) {
            if (hasWallet(wallet)) {
                throw new LedgerException("Wallet already exists");
            }
            setValue(wallet, 0L);
        }

        @Override
        public TransactionAmount getWalletValue(PublicAddress wallet) {
            byte[] value = rawValue(wallet);
            if (value == null) {
                throw new LedgerException("Tried fetching wallet value for non-existent wallet");
            }
            return new TransactionAmount(bytesToLong(value));
        }

        @Override
        public void withdraw(PublicAddress wallet, TransactionAmount amt) {
            subtract(wallet, amt, "Insufficient funds for withdrawal");
        }

        @Override
        public void revertSend(PublicAddress wallet, TransactionAmount amt) {
            add(wallet, amt);
        }

        @Override
        public void deposit(PublicAddress wallet, TransactionAmount amt) {
            add(wallet, amt);
        }

        @Override
        public void revertDeposit(PublicAddress wallet, TransactionAmount amt) {
            subtract(wallet, amt, "Cannot revert deposit below zero");
        }

        private void add(PublicAddress wallet, TransactionAmount amt) {
            long current = getWalletValue(wallet).amount();
            long next;
            try {
                next = Math.addExact(current, amt.amount());
            } catch (ArithmeticException e) {
                throw new LedgerException("Overflow detected during balance adjustment", e);
            }
            setValue(wallet, next);
        }

        private void subtract(PublicAddress wallet, TransactionAmount amt, String message) {
            long next = getWalletValue(wallet).amount() - amt.amount();
            if (next < 0) {
                throw new LedgerException(message);
            }
            setValue(wallet, next);
        }

        private byte[] rawValue(PublicAddress wallet) {
            try {
                return db.get(ledgerCf, wallet.toBytes());
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to read wallet", e);
            }
        }

        private void setValue(PublicAddress wallet, long amount) {
            try {
                db.put(ledgerCf, writeOptions, wallet.toBytes(), ByteBuffer.allocate(8).putLong(amount).array());
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to write wallet value", e);
            }
        }

        @Override
        public void forEachBalance(java.util.function.ObjLongConsumer<PublicAddress> consumer) {
            try (RocksIterator it = db.newIterator(ledgerCf)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    consumer.accept(PublicAddress.of(it.key()), bytesToLong(it.value()));
                }
            }
        }
    }

    // ---- NonceStore view (next expected account nonce per sender) ----

    private final class RocksNonceStore implements rhizome.core.blockchain.NonceStore {

        @Override
        public long next(PublicAddress sender) {
            try {
                byte[] value = db.get(noncesCf, sender.toBytes());
                return value == null ? 0L : bytesToLong(value);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to read account nonce", e);
            }
        }

        @Override
        public void set(PublicAddress sender, long next) {
            try {
                if (next <= 0) {
                    db.delete(noncesCf, writeOptions, sender.toBytes());
                } else {
                    db.put(noncesCf, writeOptions, sender.toBytes(), longToBytes(next));
                }
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to write account nonce", e);
            }
        }

        @Override
        public long syncedThroughHeight() {
            try {
                byte[] value = db.get(metaCf, NONCE_HEIGHT_KEY);
                return value == null ? 0 : bytesToLong(value);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to read nonce sync height", e);
            }
        }

        @Override
        public void markSyncedThrough(long height) {
            try {
                db.put(metaCf, writeOptions, NONCE_HEIGHT_KEY, longToBytes(height));
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to write nonce sync height", e);
            }
        }

        @Override
        public void forEach(java.util.function.ObjLongConsumer<PublicAddress> consumer) {
            try (RocksIterator it = db.newIterator(noncesCf)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    consumer.accept(PublicAddress.of(it.key()), bytesToLong(it.value()));
                }
            }
        }
    }
}
