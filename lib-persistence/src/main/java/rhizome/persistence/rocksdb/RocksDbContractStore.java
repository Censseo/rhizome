package rhizome.persistence.rocksdb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import rhizome.core.ledger.PublicAddress;
import rhizome.vm.ContractStore;

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

    private final RocksDB db;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle codeCf;
    private final ColumnFamilyHandle storageCf;
    private final ColumnFamilyHandle journalCf;

    public RocksDbContractStore(String path) throws IOException {
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_CODE),
            new ColumnFamilyDescriptor(CF_STORAGE),
            new ColumnFamilyDescriptor(CF_JOURNAL));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
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

    private byte[] get(ColumnFamilyHandle cf, byte[] key) {
        try {
            return db.get(cf, key);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store read failed", e);
        }
    }

    private void put(ColumnFamilyHandle cf, byte[] key, byte[] value) {
        try {
            db.put(cf, key, value);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store write failed", e);
        }
    }

    private void delete(ColumnFamilyHandle cf, byte[] key) {
        try {
            db.delete(cf, key);
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
        db.close();
    }
}
