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

import rhizome.core.ledger.PublicAddress;
import rhizome.vm.ContractStore;
import rhizome.vm.StorageChange;

/**
 * RocksDB-backed {@link ContractStore}: contract code in one column family, all
 * per-contract storage in another (keyed by {@code address || key}). Native store
 * for the full node; the in-memory store remains the light/test path.
 */
public final class RocksDbContractStore implements ContractStore, AutoCloseable {

    private static final byte[] CF_CODE = "contract_code".getBytes();
    private static final byte[] CF_STORAGE = "contract_storage".getBytes();
    // Persisted per-block undo journal (height BE(8) -> serialized journal), so a reorg after a
    // restart can be reversed exactly instead of relying only on the processor's RAM journals.
    private static final byte[] CF_JOURNAL = "contract_journal".getBytes();
    // Persisted per-block contract receipts (height BE(8) -> encoded receipts), so the executor's
    // rollback can reverse a block's contract-tx ledger effects even after a restart (audit F3).
    private static final byte[] CF_RECEIPTS = "contract_receipts".getBytes();

    private final RocksDB db;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle codeCf;
    private final ColumnFamilyHandle storageCf;
    private final ColumnFamilyHandle journalCf;
    private final ColumnFamilyHandle receiptsCf;
    // Synced: the block commit must be durable before the node reports the height applied (audit F3).
    private final WriteOptions writeOptions = new WriteOptions().setSync(true);

    public RocksDbContractStore(String path) throws IOException {
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_CODE),
            new ColumnFamilyDescriptor(CF_STORAGE),
            new ColumnFamilyDescriptor(CF_JOURNAL),
            new ColumnFamilyDescriptor(CF_RECEIPTS));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        // DBOptions is NOT closed after open: closing it while the DB is live corrupts the
        // native heap (rocksdbjni keeps referencing it) — the audit F12 leak note is
        // superseded; the object is reclaimed with the process/DB.
        try {
            DBOptions options = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
            this.db = RocksDB.open(options, path, descriptors, handles);
        } catch (RocksDBException e) {
            throw new IOException("Failed to open contract store at " + path, e);
        }
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
        // a restart — would otherwise accumulate on disk forever.
        try {
            byte[] end = heightKey(maxHeight + 1);
            db.deleteRange(journalCf, heightKey(0), end);
            db.deleteRange(receiptsCf, heightKey(0), end);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store pruneThrough failed", e);
        }
    }

    private static byte[] slot(PublicAddress contract, byte[] key) {
        byte[] addr = contract.toBytes();
        byte[] out = new byte[addr.length + key.length];
        System.arraycopy(addr, 0, out, 0, addr.length);
        System.arraycopy(key, 0, out, addr.length, key.length);
        return out;
    }

    @Override
    public byte[] getCode(PublicAddress contract) {
        return get(codeCf, contract.toBytes());
    }

    @Override
    public void putCode(PublicAddress contract, byte[] code) {
        put(codeCf, contract.toBytes(), code);
    }

    @Override
    public void deleteCode(PublicAddress contract) {
        delete(codeCf, contract.toBytes());
    }

    @Override
    public byte[] getStorage(PublicAddress contract, byte[] key) {
        return get(storageCf, slot(contract, key));
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        put(storageCf, slot(contract, key), value);
    }

    @Override
    public void deleteStorage(PublicAddress contract, byte[] key) {
        delete(storageCf, slot(contract, key));
    }

    @Override
    public void applyBlock(long height, List<StorageChange> changes, byte[] journal) {
        // All slot mutations AND the undo journal land in ONE synced WriteBatch: a crash mid-flush
        // can no longer leave storage half-applied with no journal to rewind it (audit F1).
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
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store applyBlock failed", e);
        }
    }

    @Override
    public void revertBlock(long height, List<StorageChange> restores) {
        // The restores and the journal drop commit as one atomic unit (audit F1).
        try (WriteBatch batch = new WriteBatch()) {
            for (StorageChange restore : restores) {
                stage(batch, restore);
            }
            batch.delete(journalCf, heightKey(height));
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
        // Storage keys are contract address(25) ‖ key.
        try (org.rocksdb.RocksIterator it = db.newIterator(storageCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] slot = it.key();
                consumer.accept(PublicAddress.of(java.util.Arrays.copyOfRange(slot, 0, PublicAddress.SIZE)),
                    java.util.Arrays.copyOfRange(slot, PublicAddress.SIZE, slot.length), it.value());
            }
        }
    }

    @Override
    public void close() {
        defaultCf.close();
        codeCf.close();
        storageCf.close();
        journalCf.close();
        receiptsCf.close();
        writeOptions.close();
        db.close();
    }
}
