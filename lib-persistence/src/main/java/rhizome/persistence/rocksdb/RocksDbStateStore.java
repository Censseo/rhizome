package rhizome.persistence.rocksdb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

import rhizome.core.state.RootStore;
import rhizome.core.state.SmtNodeStore;

import static rhizome.core.common.Utils.bytesToLong;
import static rhizome.core.common.Utils.longToBytes;

/**
 * RocksDB backing for the authenticated state: the content-addressed sparse-Merkle nodes
 * ({@link SmtNodeStore}) and the per-height state roots ({@link RootStore}). Nodes are
 * immutable and keyed by their hash, so old roots stay resolvable for reorg reversal;
 * roots are 32 bytes per height, keyed by big-endian height for ordered iteration.
 */
public final class RocksDbStateStore implements SmtNodeStore, RootStore, AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private static final byte[] CF_NODES = "smt_nodes".getBytes();
    private static final byte[] CF_ROOTS = "state_roots".getBytes();

    private final RocksDB db;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle nodesCf;
    private final ColumnFamilyHandle rootsCf;
    private final WriteOptions writeOptions = new WriteOptions();

    public RocksDbStateStore(String path) throws IOException {
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_NODES),
            new ColumnFamilyDescriptor(CF_ROOTS));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try {
            DBOptions options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            this.db = RocksDB.open(options, path, descriptors, handles);
        } catch (RocksDBException e) {
            throw new IOException("Failed to open state store at " + path, e);
        }
        this.defaultCf = handles.get(0);
        this.nodesCf = handles.get(1);
        this.rootsCf = handles.get(2);
    }

    // ---- SmtNodeStore ----

    @Override
    public byte[] get(byte[] hash) {
        return raw(nodesCf, hash);
    }

    @Override
    public void put(byte[] hash, byte[] node) {
        try {
            db.put(nodesCf, writeOptions, hash, node);
        } catch (RocksDBException e) {
            throw new IllegalStateException("state node write failed", e);
        }
    }

    // ---- RootStore ----

    @Override
    public byte[] getRoot(long height) {
        return raw(rootsCf, longToBytes(height));
    }

    @Override
    public void putRoot(long height, byte[] root) {
        try {
            db.put(rootsCf, writeOptions, longToBytes(height), root);
        } catch (RocksDBException e) {
            throw new IllegalStateException("state root write failed", e);
        }
    }

    @Override
    public void deleteRoot(long height) {
        try {
            db.delete(rootsCf, writeOptions, longToBytes(height));
        } catch (RocksDBException e) {
            throw new IllegalStateException("state root delete failed", e);
        }
    }

    @Override
    public long latestHeight() {
        try (RocksIterator it = db.newIterator(rootsCf)) {
            it.seekToLast();
            return it.isValid() ? bytesToLong(it.key()) : -1;
        }
    }

    @Override
    public void pruneBelow(long minHeight) {
        try {
            db.deleteRange(rootsCf, longToBytes(0), longToBytes(minHeight));
        } catch (RocksDBException e) {
            throw new IllegalStateException("state root prune failed", e);
        }
    }

    private byte[] raw(ColumnFamilyHandle cf, byte[] key) {
        try {
            return db.get(cf, key);
        } catch (RocksDBException e) {
            throw new IllegalStateException("state store read failed", e);
        }
    }

    @Override
    public void close() {
        defaultCf.close();
        nodesCf.close();
        rootsCf.close();
        writeOptions.close();
        db.close();
    }
}
