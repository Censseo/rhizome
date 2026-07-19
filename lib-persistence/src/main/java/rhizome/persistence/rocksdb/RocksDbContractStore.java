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

    private final RocksDB db;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle codeCf;
    private final ColumnFamilyHandle storageCf;

    public RocksDbContractStore(String path) throws IOException {
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_CODE),
            new ColumnFamilyDescriptor(CF_STORAGE));
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
    public byte[] getStorage(PublicAddress contract, byte[] key) {
        return get(storageCf, slot(contract, key));
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        put(storageCf, slot(contract, key), value);
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

    @Override
    public void close() {
        defaultCf.close();
        codeCf.close();
        storageCf.close();
        db.close();
    }
}
