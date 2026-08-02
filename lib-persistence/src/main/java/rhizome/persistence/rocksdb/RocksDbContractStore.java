package rhizome.persistence.rocksdb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.ledger.PublicAddress;
import rhizome.vm.ContractStore;
import rhizome.vm.StorageChange;

/**
 * RocksDB-backed {@link ContractStore}: contract code in one column family, all
 * per-contract storage in another (keyed by {@code address || key}). Native store
 * for the full node; the in-memory store remains the light/test path.
 */
public final class RocksDbContractStore implements ContractStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RocksDbContractStore.class);

    static {
        // The WriteOptions field initializers below call native methods at construction time:
        // without this, a JVM that opens a contract store BEFORE any other store crashed with
        // UnsatisfiedLinkError — long masked in the test suite by another store loading the
        // library first into the shared test JVM (audit 17th pass, latent).
        RocksDB.loadLibrary();
    }

    private static final byte[] CF_CODE = "contract_code".getBytes();
    private static final byte[] CF_STORAGE = "contract_storage".getBytes();
    // Persisted per-block undo journal (height BE(8) -> serialized journal), so a reorg after a
    // restart can be reversed exactly instead of relying only on the processor's RAM journals.
    private static final byte[] CF_JOURNAL = "contract_journal".getBytes();
    // Persisted per-block contract receipts (height BE(8) -> encoded receipts), so the executor's
    // rollback can reverse a block's contract-tx ledger effects even after a restart (audit F3).
    private static final byte[] CF_RECEIPTS = "contract_receipts".getBytes();

    private final RocksDB db;
    private final DBOptions dbOptions;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle codeCf;
    private final ColumnFamilyHandle storageCf;
    private final ColumnFamilyHandle journalCf;
    private final ColumnFamilyHandle receiptsCf;
    // Synced: the block commit must be durable before the node reports the height applied (audit F3).
    private final WriteOptions writeOptions = new WriteOptions().setSync(true);
    // Unsynced: snapshot import seeds every contract code/storage slot through the straight-through
    // path, where a per-slot fsync made snap-sync effectively unusable (audit perf). WAL writes are
    // still process-crash-safe; the next synced batch in this database (applyBlock, journal/receipt
    // writes) fsyncs the shared WAL and covers the tail, and syncWal every BULK_SYNC_EVERY writes
    // bounds the power-loss tail in between.
    private static final long BULK_SYNC_EVERY = 4096;
    private final WriteOptions bulkWriteOptions = new WriteOptions().setSync(false);
    private long bulkWritesSinceSync;

    /** Throttles the unsynced bulk-write tail: one WAL fsync per {@link #BULK_SYNC_EVERY} writes. */
    private void noteBulkWrite() throws RocksDBException {
        if (++bulkWritesSinceSync >= BULK_SYNC_EVERY) {
            db.syncWal();
            bulkWritesSinceSync = 0;
        }
    }

    public RocksDbContractStore(String path) throws IOException {
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_CODE),
            new ColumnFamilyDescriptor(CF_STORAGE),
            new ColumnFamilyDescriptor(CF_JOURNAL),
            new ColumnFamilyDescriptor(CF_RECEIPTS));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        // DBOptions is kept and closed in close() AFTER db.close(): never while the DB is live
        // (rocksdbjni keeps referencing it — closing it live corrupts the native heap), and not
        // at all was a native-handle leak (audit F12).
        DBOptions options = new DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true);
        try {
            this.db = RocksDB.open(options, path, descriptors, handles);
        } catch (RocksDBException e) {
            options.close();
            throw new IOException("Failed to open contract store at " + path, e);
        }
        this.dbOptions = options;
        this.defaultCf = handles.get(0);
        this.codeCf = handles.get(1);
        this.storageCf = handles.get(2);
        this.journalCf = handles.get(3);
        this.receiptsCf = handles.get(4);
    }

    private static byte[] heightKey(long height) {
        return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(height).array();
    }

    @Override
    public void putJournal(long height, byte[] serialized) {
        put(journalCf, heightKey(height), serialized);
    }

    @Override
    public byte[] getJournal(long height) {
        return get(journalCf, heightKey(height));
    }

    @Override
    public void deleteJournal(long height) {
        delete(journalCf, heightKey(height));
    }

    @Override
    public void putReceipts(long height, byte[] encoded) {
        put(receiptsCf, heightKey(height), encoded);
    }

    @Override
    public byte[] getReceipts(long height) {
        return get(receiptsCf, heightKey(height));
    }

    @Override
    public void deleteReceipts(long height) {
        delete(receiptsCf, heightKey(height));
    }

    @Override
    public void pruneThrough(long maxHeight) {
        // Interval prune (deleteRange's end key is EXCLUSIVE, hence maxHeight + 1): rows left
        // by heights the processor no longer has in its RAM maps — everything committed before
        // a restart — would otherwise accumulate on disk forever. Synced, consistent with every
        // other delete in this store (audit: prune durability).
        try {
            byte[] end = heightKey(maxHeight + 1);
            db.deleteRange(journalCf, writeOptions, heightKey(0), end);
            db.deleteRange(receiptsCf, writeOptions, heightKey(0), end);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store pruneThrough failed", e);
        }
    }

    private static byte[] slot(PublicAddress contract, byte[] key) {
        // record-accessor read: the internal address array is copied straight into the composite,
        // never retained or mutated — avoiding toBytes()'s defensive 25-byte clone on this
        // per-storage-op hot path is provably alias-safe here (audit perf).
        byte[] addr = contract.address();
        byte[] out = new byte[addr.length + key.length];
        System.arraycopy(addr, 0, out, 0, addr.length);
        System.arraycopy(key, 0, out, addr.length, key.length);
        return out;
    }

    @Override
    public byte[] getCode(PublicAddress contract) {
        return get(codeCf, contract.toBytes());
    }

    // Code/storage slot writes go through the bulk path: they are the snapshot-import seeding
    // (and in-session folds, always followed by the block's synced applyBlock), never the
    // journal/receipt writes, which keep the synced put/delete below (audit F3).
    @Override
    public void putCode(PublicAddress contract, byte[] code) {
        putBulk(codeCf, contract.toBytes(), code);
    }

    @Override
    public void deleteCode(PublicAddress contract) {
        deleteBulk(codeCf, contract.toBytes());
    }

    @Override
    public byte[] getStorage(PublicAddress contract, byte[] key) {
        return get(storageCf, slot(contract, key));
    }

    @Override
    public List<byte[]> getStorageMulti(List<PublicAddress> contracts, List<byte[]> keys) {
        if (contracts.size() != keys.size()) {
            throw new IllegalArgumentException("contracts/keys length mismatch: "
                + contracts.size() + " vs " + keys.size());
        }
        // One native multi-get for the whole journal capture: the default point-read loop made
        // a K-write block cost K store round-trips at every commit (audit: journal-capture N+1).
        List<ColumnFamilyHandle> cfs = new ArrayList<>(keys.size());
        List<byte[]> composite = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            cfs.add(storageCf);
            composite.add(slot(contracts.get(i), keys.get(i)));
        }
        try {
            return db.multiGetAsList(cfs, composite);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store multi-read failed", e);
        }
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        putBulk(storageCf, slot(contract, key), value);
    }

    @Override
    public void deleteStorage(PublicAddress contract, byte[] key) {
        deleteBulk(storageCf, slot(contract, key));
    }

    @Override
    public void applyBlock(long height, List<StorageChange> changes, byte[] journal) {
        applyBlock(height, changes, journal, null);
    }

    @Override
    public void applyBlock(long height, List<StorageChange> changes, byte[] journal, byte[] encodedReceipts) {
        // All slot mutations AND the undo journal AND the receipts land in ONE synced WriteBatch:
        // a crash mid-flush can no longer leave storage half-applied with no journal to rewind it
        // (audit F1), and the receipts no longer cost a second fsync per block (audit perf).
        if (get(journalCf, heightKey(height)) != null) {
            // A double-apply would capture the already-mutated state as the journal's "prior" (audit F10).
            throw new IllegalStateException("contract store already has a journal at height " + height);
        }
        try (WriteBatch batch = new WriteBatch()) {
            for (StorageChange change : changes) {
                stage(batch, change);
            }
            if (journal != null) {
                batch.put(journalCf, heightKey(height), journal);
            }
            if (encodedReceipts != null) {
                batch.put(receiptsCf, heightKey(height), encodedReceipts);
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store applyBlock failed", e);
        }
    }

    @Override
    public void revertBlock(long height, List<StorageChange> restores) {
        // The restores, the journal drop AND the receipts drop commit as one atomic unit
        // (audit F1). The receipts must ride this batch: deleted separately and first, a crash
        // between the two writes left the journal present but the receipts gone, and the
        // rollback guard then aborted every reorg retry — the node wedged on its fork.
        try (WriteBatch batch = new WriteBatch()) {
            for (StorageChange restore : restores) {
                stage(batch, restore);
            }
            batch.delete(journalCf, heightKey(height));
            batch.delete(receiptsCf, heightKey(height));
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store revertBlock failed", e);
        }
    }

    /** Adds one mutation (set, or delete when the value is null) to {@code batch}. */
    private void stage(WriteBatch batch, StorageChange change) throws RocksDBException {
        if (change.isCode()) {
            if (change.value() == null) {
                batch.delete(codeCf, change.contract().toBytes());
            } else {
                batch.put(codeCf, change.contract().toBytes(), change.value());
            }
        } else {
            byte[] slot = slot(change.contract(), change.key());
            if (change.value() == null) {
                batch.delete(storageCf, slot);
            } else {
                batch.put(storageCf, slot, change.value());
            }
        }
    }

    private byte[] get(ColumnFamilyHandle cf, byte[] key) {
        try {
            return db.get(cf, key);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store read failed", e);
        }
    }

    private void put(ColumnFamilyHandle cf, byte[] key, byte[] value) {
        try {
            db.put(cf, writeOptions, key, value);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store write failed", e);
        }
    }

    private void delete(ColumnFamilyHandle cf, byte[] key) {
        try {
            db.delete(cf, writeOptions, key);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store delete failed", e);
        }
    }

    private void putBulk(ColumnFamilyHandle cf, byte[] key, byte[] value) {
        try {
            db.put(cf, bulkWriteOptions, key, value);
            noteBulkWrite();
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store write failed", e);
        }
    }

    private void deleteBulk(ColumnFamilyHandle cf, byte[] key) {
        try {
            db.delete(cf, bulkWriteOptions, key);
            noteBulkWrite();
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store delete failed", e);
        }
    }

    @Override
    public void forEachCode(java.util.function.BiConsumer<PublicAddress, byte[]> consumer) {
        try (org.rocksdb.RocksIterator it = db.newIterator(codeCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                consumer.accept(PublicAddress.of(it.key()), it.value());
            }
        }
    }

    @Override
    public void forEachStorage(StorageConsumer consumer) {
        // Storage keys are contract address(25) ‖ key. This export feeds the state root peers
        // download, so a truncated key must fail LOUD here — zero-padding it would fabricate a
        // state entry that never existed (audit: storage-key decode).
        try (org.rocksdb.RocksIterator it = db.newIterator(storageCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] slot = it.key();
                if (slot.length < PublicAddress.SIZE) {
                    throw new IllegalStateException("corrupt contract storage key: " + slot.length
                        + " bytes, expected at least " + PublicAddress.SIZE);
                }
                consumer.accept(PublicAddress.of(java.util.Arrays.copyOfRange(slot, 0, PublicAddress.SIZE)),
                    java.util.Arrays.copyOfRange(slot, PublicAddress.SIZE, slot.length), it.value());
            }
        }
    }

    @Override
    public void syncToDisk() {
        // fsync any bulk-seeded writes not yet covered by a synced batch (snapshot-import tail).
        try {
            db.syncWal();
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store WAL sync failed", e);
        }
        bulkWritesSinceSync = 0;
    }

    @Override
    public void close() {
        // Best-effort fsync of any bulk-seeded writes not yet covered by a synced batch. A
        // failure here must NOT abort close(): leaking native CF/DB handles on the shutdown
        // path is worse than a best-effort fsync lost on a store that is about to be closed.
        try {
            syncToDisk();
        } catch (RuntimeException e) {
            log.warn("contract store WAL sync on close failed; closing anyway", e);
        }
        defaultCf.close();
        codeCf.close();
        storageCf.close();
        journalCf.close();
        receiptsCf.close();
        writeOptions.close();
        bulkWriteOptions.close();
        db.close();
        dbOptions.close(); // after the DB: rocksdbjni references the options while the DB is live
    }
}
