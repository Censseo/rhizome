package rhizome.persistence.rocksdb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import rhizome.persistence.PersistenceException;

import static rhizome.core.common.Utils.longToBytes;

/**
 * Common scaffolding for the five RocksDB-backed stores.
 *
 * <p>Opening and closing a database, the synced/bulk write-option pair with its WAL-sync
 * throttle, the point-access helpers, and the key/height/long mapping used to be spelled out
 * in every adaptor, three times each with a different exception vocabulary and a fourth time
 * with a different height-key encoding. The scaffolding lives here once: {@code db}, its
 * options and the column-family handles are opened in one place (with the DBOptions kept and
 * closed <em>after</em> {@code db.close()} — closing it while the DB is live corrupts the
 * native heap, and not closing it at all leaked the native handle, audit F12), every helper
 * throws the single {@link PersistenceException} of this module, and the height/bytes key
 * mapping is the one copy every store shares.
 *
 * <p>The {@link WriteOptions} pair encodes the durability split each store used to document
 * per call site: {@link #writeOptions} is synced (every write that advances or rewinds
 * committed state must be fsync-durable before the node reports the block applied, audit F3);
 * {@link #bulkWriteOptions} is unsynced for bulk seeding (snapshot import, genesis), where a
 * per-entry fsync made snap-sync effectively unusable — WAL writes are still process-crash
 * safe, the next synced batch in the database fsyncs the shared WAL and covers the tail, and
 * {@link #noteBulkWrite()} bounds the power-loss tail in between.
 */
abstract class RocksDbStore implements AutoCloseable {

    static {
        // The WriteOptions field initializers below call native methods at construction time:
        // without this, a JVM that opens any store before another loaded the library crashed
        // with UnsatisfiedLinkError — long masked in the test suite by another store loading
        // it first into the shared test JVM (audit 17th pass, latent).
        RocksDB.loadLibrary();
    }

    /** Throttles the unsynced bulk-write tail: one WAL fsync per {@link #BULK_SYNC_EVERY} writes. */
    private static final long BULK_SYNC_EVERY = 4096;

    private final String storeName;

    protected final RocksDB db;
    protected final DBOptions dbOptions;

    /** Every open column-family handle, in creation order ({@code default} first). */
    protected final List<ColumnFamilyHandle> handles;
    protected final ColumnFamilyHandle defaultCf;

    // Synced: every write that advances (or rewinds) committed state must be fsync-durable
    // before the node reports the block applied (audit F3).
    protected final WriteOptions writeOptions = new WriteOptions().setSync(true);
    // Unsynced: bulk seeding writes one entry per slot straight through, where a per-slot fsync
    // made snap-sync effectively unusable (audit perf) — see the class javadoc for how the
    // power-loss tail is bounded.
    protected final WriteOptions bulkWriteOptions = new WriteOptions().setSync(false);
    private long bulkWritesSinceSync;

    protected RocksDbStore(String path, String storeName, byte[]... cfNames) throws IOException {
        this.storeName = storeName;
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(cfNames.length + 1);
        descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
        for (byte[] name : cfNames) {
            descriptors.add(new ColumnFamilyDescriptor(name));
        }
        // DBOptions is kept and closed in close() AFTER db.close(): never while the DB is live
        // (rocksdbjni keeps referencing it — closing it live corrupts the native heap), and not
        // at all was a native-handle leak (audit F12).
        DBOptions options = new DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true);
        List<ColumnFamilyHandle> created = new ArrayList<>();
        RocksDB opened;
        try {
            opened = RocksDB.open(options, path, descriptors, created);
        } catch (RocksDBException e) {
            options.close();
            throw new IOException("Failed to open " + storeName + " at " + path, e);
        }
        this.db = opened;
        this.dbOptions = options;
        this.handles = created;
        this.defaultCf = created.get(0);
    }

    /** Throttles the unsynced bulk-write tail: one WAL fsync per {@link #BULK_SYNC_EVERY} writes. */
    protected final void noteBulkWrite() throws RocksDBException {
        if (++bulkWritesSinceSync >= BULK_SYNC_EVERY) {
            db.syncWal();
            bulkWritesSinceSync = 0;
        }
    }

    // ---- point access, one exception vocabulary (audit: three vocabularies) ----

    protected final byte[] raw(ColumnFamilyHandle cf, byte[] key) {
        try {
            return db.get(cf, key);
        } catch (RocksDBException e) {
            throw new PersistenceException(storeName + " read failed", e);
        }
    }

    protected final void put(ColumnFamilyHandle cf, byte[] key, byte[] value) {
        try {
            db.put(cf, writeOptions, key, value);
        } catch (RocksDBException e) {
            throw new PersistenceException(storeName + " write failed", e);
        }
    }

    protected final void delete(ColumnFamilyHandle cf, byte[] key) {
        try {
            db.delete(cf, writeOptions, key);
        } catch (RocksDBException e) {
            throw new PersistenceException(storeName + " delete failed", e);
        }
    }

    protected final void putBulk(ColumnFamilyHandle cf, byte[] key, byte[] value) {
        try {
            db.put(cf, bulkWriteOptions, key, value);
            noteBulkWrite();
        } catch (RocksDBException e) {
            throw new PersistenceException(storeName + " write failed", e);
        }
    }

    protected final void deleteBulk(ColumnFamilyHandle cf, byte[] key) {
        try {
            db.delete(cf, bulkWriteOptions, key);
            noteBulkWrite();
        } catch (RocksDBException e) {
            throw new PersistenceException(storeName + " delete failed", e);
        }
    }

    /** fsyncs the WAL and clears the bulk-write counter (the throttle's reset edge). */
    protected final void syncWal() {
        try {
            db.syncWal();
        } catch (RocksDBException e) {
            throw new PersistenceException(storeName + " WAL sync failed", e);
        }
        bulkWritesSinceSync = 0;
    }

    // ---- key mapping (one copy instead of one per store) ----

    /** Fixed-width height key: 8 bytes big-endian, the sortable layout every store shares. */
    protected static byte[] heightKey(long height) {
        return longToBytes(height);
    }

    /**
     * Decodes an 8-byte big-endian value at {@code offset}. Fixed-width values are written as
     * exactly 8 bytes, so a short record means store corruption — fail with a diagnosable
     * {@link PersistenceException}, not a raw {@code ArrayIndexOutOfBoundsException}
     * (audit: fixed-width decode).
     */
    protected long bytesToLong(byte[] b, int offset) {
        if (b.length < offset + 8) {
            throw new PersistenceException("corrupt " + storeName + ": expected 8-byte value at offset "
                + offset + ", array length " + b.length);
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[offset + i] & 0xFFL);
        }
        return v;
    }

    protected static boolean startsWith(byte[] array, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    protected static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Override
    public void close() {
        for (ColumnFamilyHandle cf : handles) {
            cf.close();
        }
        writeOptions.close();
        bulkWriteOptions.close();
        db.close();
        dbOptions.close(); // after the DB: rocksdbjni references the options while the DB is live
    }
}
