package rhizome.persistence.rocksdb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import rhizome.core.box.Box;
import rhizome.core.box.BoxStore;

import static rhizome.core.common.Utils.longToBytes;

/**
 * RocksDB-backed {@link BoxStore}: boxes plus the owner and rent-expiry secondary
 * indexes, and a persisted per-block undo journal. The journal lives on disk (unlike
 * the contract store's in-memory journals), so a reorg that follows a restart can
 * still restore box state exactly.
 *
 * <p>Column families:
 * <ul>
 *   <li>{@code boxes}: {@code boxId(32)} -> serialized box</li>
 *   <li>{@code box_owner}: {@code owner(25) || boxId(32)} -> empty (scan by owner)</li>
 *   <li>{@code box_expiry}: {@code rentPaidHeight(8, BE) || boxId(32)} -> empty
 *       (lowest rent-clock first; a box is collectable once
 *       {@code rentPaidHeight <= height - storagePeriod})</li>
 *   <li>{@code box_journal}: {@code height(8, BE)} -> serialized undo journal</li>
 * </ul>
 */
public final class RocksDbBoxStore implements BoxStore, AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private static final byte[] CF_BOXES = "boxes".getBytes();
    private static final byte[] CF_OWNER = "box_owner".getBytes();
    private static final byte[] CF_EXPIRY = "box_expiry".getBytes();
    private static final byte[] CF_JOURNAL = "box_journal".getBytes();
    private static final byte[] EMPTY = new byte[0];

    private final RocksDB db;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle boxesCf;
    private final ColumnFamilyHandle ownerCf;
    private final ColumnFamilyHandle expiryCf;
    private final ColumnFamilyHandle journalCf;
    private final WriteOptions writeOptions = new WriteOptions();

    public RocksDbBoxStore(String path) throws IOException {
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_BOXES),
            new ColumnFamilyDescriptor(CF_OWNER),
            new ColumnFamilyDescriptor(CF_EXPIRY),
            new ColumnFamilyDescriptor(CF_JOURNAL));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try {
            DBOptions options = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
            this.db = RocksDB.open(options, path, descriptors, handles);
        } catch (RocksDBException e) {
            throw new IOException("Failed to open box store at " + path, e);
        }
        this.defaultCf = handles.get(0);
        this.boxesCf = handles.get(1);
        this.ownerCf = handles.get(2);
        this.expiryCf = handles.get(3);
        this.journalCf = handles.get(4);
    }

    @Override
    public Box get(byte[] id) {
        byte[] bytes = raw(boxesCf, id);
        return bytes == null ? null : Box.deserialize(bytes);
    }

    @Override
    public void applyBlock(long height, List<BoxMutation> mutations) {
        try (WriteBatch batch = new WriteBatch()) {
            List<JournalEntry> journal = new ArrayList<>(mutations.size());
            for (BoxMutation m : mutations) {
                byte[] priorBytes = raw(boxesCf, m.id());
                journal.add(new JournalEntry(m.id(), priorBytes));
                if (priorBytes != null) {
                    dropIndexes(batch, Box.deserialize(priorBytes));
                }
                if (m.box() == null) {
                    batch.delete(boxesCf, m.id());
                } else {
                    writeBox(batch, m.box());
                }
            }
            batch.put(journalCf, longToBytes(height), encodeJournal(journal));
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("box store applyBlock failed", e);
        }
    }

    @Override
    public void revertBlock(long height) {
        byte[] journalBytes = raw(journalCf, longToBytes(height));
        if (journalBytes == null) {
            return;
        }
        try (WriteBatch batch = new WriteBatch()) {
            List<JournalEntry> journal = decodeJournal(journalBytes);
            for (int i = journal.size() - 1; i >= 0; i--) {
                JournalEntry entry = journal.get(i);
                byte[] currentBytes = raw(boxesCf, entry.id());
                if (currentBytes != null) {
                    dropIndexes(batch, Box.deserialize(currentBytes));
                    batch.delete(boxesCf, entry.id());
                }
                if (entry.prior() != null) {
                    writeBox(batch, Box.deserialize(entry.prior()));
                }
            }
            batch.delete(journalCf, longToBytes(height));
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("box store revertBlock failed", e);
        }
    }

    @Override
    public void pruneJournals(long minHeight) {
        try {
            db.deleteRange(journalCf, longToBytes(0), longToBytes(minHeight));
        } catch (RocksDBException e) {
            throw new IllegalStateException("box store pruneJournals failed", e);
        }
    }

    @Override
    public List<byte[]> collectableBoxIds(long height, long storagePeriodBlocks, int limit) {
        long threshold = height - storagePeriodBlocks; // collectable if rentPaidHeight <= threshold
        List<byte[]> out = new ArrayList<>();
        if (threshold < 0) {
            return out;
        }
        try (RocksIterator it = db.newIterator(expiryCf)) {
            for (it.seekToFirst(); it.isValid() && out.size() < limit; it.next()) {
                byte[] key = it.key();
                long rentPaidHeight = bytesToLong(key, 0);
                if (rentPaidHeight > threshold) {
                    break; // keys are sorted ascending by rentPaidHeight
                }
                out.add(Arrays.copyOfRange(key, 8, key.length));
            }
        }
        return out;
    }

    @Override
    public List<byte[]> boxIdsByOwner(byte[] owner, byte[] afterId, int limit) {
        List<byte[]> out = new ArrayList<>();
        try (RocksIterator it = db.newIterator(ownerCf)) {
            for (it.seek(owner); it.isValid() && out.size() < limit; it.next()) {
                byte[] key = it.key();
                if (key.length < owner.length || !startsWith(key, owner)) {
                    break;
                }
                byte[] boxId = Arrays.copyOfRange(key, owner.length, key.length);
                if (afterId == null || Arrays.compareUnsigned(boxId, afterId) > 0) {
                    out.add(boxId);
                }
            }
        }
        return out;
    }

    // ---- index maintenance ----

    private void writeBox(WriteBatch batch, Box box) throws RocksDBException {
        batch.put(boxesCf, box.id(), box.serialize());
        batch.put(ownerCf, ownerKey(box), EMPTY);
        batch.put(expiryCf, expiryKey(box), EMPTY);
    }

    private void dropIndexes(WriteBatch batch, Box box) throws RocksDBException {
        batch.delete(ownerCf, ownerKey(box));
        batch.delete(expiryCf, expiryKey(box));
    }

    private static byte[] ownerKey(Box box) {
        return concat(box.owner().toBytes(), box.id());
    }

    private static byte[] expiryKey(Box box) {
        return concat(longToBytes(box.rentPaidHeight()), box.id());
    }

    // ---- journal codec ----

    private record JournalEntry(byte[] id, byte[] prior) {}

    private static byte[] encodeJournal(List<JournalEntry> journal) {
        int size = 4;
        for (JournalEntry e : journal) {
            size += 32 + 1 + (e.prior() == null ? 0 : 4 + e.prior().length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putInt(journal.size());
        for (JournalEntry e : journal) {
            buffer.put(e.id());
            if (e.prior() == null) {
                buffer.put((byte) 0);
            } else {
                buffer.put((byte) 1);
                buffer.putInt(e.prior().length);
                buffer.put(e.prior());
            }
        }
        return buffer.array();
    }

    private static List<JournalEntry> decodeJournal(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int count = buffer.getInt();
        List<JournalEntry> journal = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] id = new byte[32];
            buffer.get(id);
            byte[] prior = null;
            if (buffer.get() == 1) {
                prior = new byte[buffer.getInt()];
                buffer.get(prior);
            }
            journal.add(new JournalEntry(id, prior));
        }
        return journal;
    }

    // ---- helpers ----

    private byte[] raw(ColumnFamilyHandle cf, byte[] key) {
        try {
            return db.get(cf, key);
        } catch (RocksDBException e) {
            throw new IllegalStateException("box store read failed", e);
        }
    }

    private static boolean startsWith(byte[] array, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static long bytesToLong(byte[] b, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[offset + i] & 0xFFL);
        }
        return v;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Override
    public void close() {
        defaultCf.close();
        boxesCf.close();
        ownerCf.close();
        expiryCf.close();
        journalCf.close();
        writeOptions.close();
        db.close();
    }
}
