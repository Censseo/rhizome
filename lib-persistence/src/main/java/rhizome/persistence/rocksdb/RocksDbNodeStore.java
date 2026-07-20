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
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
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
    private static final byte[] HEIGHT_KEY = "height".getBytes();

    private final RocksDB db;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle blocksCf;
    private final ColumnFamilyHandle headersCf;
    private final ColumnFamilyHandle txIndexCf;
    private final ColumnFamilyHandle metaCf;
    private final ColumnFamilyHandle ledgerCf;
    private final WriteOptions writeOptions = new WriteOptions();

    public RocksDbNodeStore(String path) throws IOException {
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_BLOCKS),
            new ColumnFamilyDescriptor(CF_HEADERS),
            new ColumnFamilyDescriptor(CF_TXINDEX),
            new ColumnFamilyDescriptor(CF_META),
            new ColumnFamilyDescriptor(CF_LEDGER));
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
        backfillHeaders();
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

    public Ledger ledger() {
        return new RocksLedger();
    }

    @Override
    public void close() {
        defaultCf.close();
        blocksCf.close();
        headersCf.close();
        txIndexCf.close();
        metaCf.close();
        ledgerCf.close();
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
                db.write(writeOptions, batch);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to append block " + expected, e);
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
    }
}
