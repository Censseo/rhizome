package rhizome.persistence.rocksdb;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.rocksdb.ColumnFamilyHandle;
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
 *   <li>{@code box_receipts}: {@code height(8, BE)} -> serialized box receipts
 *       (what {@code Executor.rollbackBlock} needs to reverse soft-reverts after a restart)</li>
 * </ul>
 */
public final class RocksDbBoxStore extends RocksDbStore implements BoxStore {

    private static final byte[] CF_BOXES = "boxes".getBytes();
    private static final byte[] CF_OWNER = "box_owner".getBytes();
    private static final byte[] CF_EXPIRY = "box_expiry".getBytes();
    private static final byte[] CF_JOURNAL = "box_journal".getBytes();
    private static final byte[] CF_RECEIPTS = "box_receipts".getBytes();
    private static final byte[] EMPTY = new byte[0];

    private final ColumnFamilyHandle boxesCf;
    private final ColumnFamilyHandle ownerCf;
    private final ColumnFamilyHandle expiryCf;
    private final ColumnFamilyHandle journalCf;
    private final ColumnFamilyHandle receiptsCf;

    public RocksDbBoxStore(String path) throws java.io.IOException {
        super(path, "box store", CF_BOXES, CF_OWNER, CF_EXPIRY, CF_JOURNAL, CF_RECEIPTS);
        this.boxesCf = handles.get(1);
        this.ownerCf = handles.get(2);
        this.expiryCf = handles.get(3);
        this.journalCf = handles.get(4);
        this.receiptsCf = handles.get(5);
    }

    @Override
    public Box get(byte[] id) {
        byte[] bytes = raw(boxesCf, id);
        return bytes == null ? null : Box.deserialize(bytes);
    }

    @Override
    public void applyBlock(long height, List<BoxMutation> mutations) {
        applyBlock(height, mutations, null);
    }

    @Override
    public void applyBlock(long height, List<BoxMutation> mutations, byte[] encodedReceipts) {
        // Refuse a double-apply: re-applying a block would journal its own already-mutated state
        // as the "prior", so a later revert would restore the wrong values (audit F10).
        if (raw(journalCf, longToBytes(height)) != null) {
            throw new IllegalStateException("box store already has a journal at height " + height);
        }
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
            // A mutation-less apply persists no journal: revertBlock maps a missing journal to
            // "nothing to undo", so the 4-byte empty row was a pure cost (audit: empty journals).
            if (!journal.isEmpty()) {
                batch.put(journalCf, longToBytes(height), encodeJournal(journal));
            }
            // Receipts ride the same synced batch (previously a second fsync per block, audit perf).
            if (encodedReceipts != null) {
                batch.put(receiptsCf, longToBytes(height), encodedReceipts);
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("box store applyBlock failed", e);
        }
    }

    @Override
    public void revertBlock(long height) {
        byte[] journalBytes = raw(journalCf, longToBytes(height));
        if (journalBytes == null) {
            // Nothing to restore — but receipts can exist without a journal (they persist on
            // their own schedule). Drop them too, or skip the synced write entirely when this
            // height committed nothing (the common boot-recovery sweep case).
            if (raw(receiptsCf, longToBytes(height)) != null) {
                deleteReceipts(height);
            }
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
            // The receipts ride the same atomic unit (audit: revert-path tear): deleted
            // separately and first, a crash stranded the journal without the receipts and the
            // rollback guard wedged every later reorg attempt.
            batch.delete(receiptsCf, longToBytes(height));
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("box store revertBlock failed", e);
        }
    }

    @Override
    public void putReceipts(long height, byte[] encodedReceipts) {
        try {
            db.put(receiptsCf, writeOptions, longToBytes(height), encodedReceipts);
        } catch (RocksDBException e) {
            throw new IllegalStateException("box store putReceipts failed", e);
        }
    }

    @Override
    public byte[] getReceipts(long height) {
        return raw(receiptsCf, longToBytes(height));
    }

    @Override
    public void deleteReceipts(long height) {
        try {
            db.delete(receiptsCf, writeOptions, longToBytes(height));
        } catch (RocksDBException e) {
            throw new IllegalStateException("box store deleteReceipts failed", e);
        }
    }

    @Override
    public void pruneJournals(long minHeight) {
        try {
            // Synced, consistent with every other delete in this store (audit: prune durability).
            db.deleteRange(journalCf, writeOptions, longToBytes(0), longToBytes(minHeight));
            db.deleteRange(receiptsCf, writeOptions, longToBytes(0), longToBytes(minHeight));
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
            // Seek straight to the owner ‖ afterId composite: keys sort lexicographically, so
            // every subsequent key under the owner prefix is strictly past the cursor. The old
            // seek(owner) + Java-side filter re-scanned the owner's whole history per page —
            // O(n) per page, O(n^2) to enumerate (audit: owner-index pagination).
            if (afterId == null) {
                it.seek(owner);
            } else {
                byte[] cursor = concat(owner, afterId);
                it.seek(cursor);
                if (it.isValid() && Arrays.equals(it.key(), cursor)) {
                    it.next(); // exclusive of the cursor
                }
            }
            for (; it.isValid() && out.size() < limit; it.next()) {
                byte[] key = it.key();
                if (key.length < owner.length || !startsWith(key, owner)) {
                    break; // past the owner prefix
                }
                if (key.length != owner.length + 32) {
                    continue; // foreign record under the prefix: skip it, don't truncate the page
                }
                out.add(Arrays.copyOfRange(key, owner.length, key.length));
            }
        }
        return out;
    }

    @Override
    public List<byte[]> boxIdsFrom(byte[] afterId, int limit) {
        List<byte[]> out = new ArrayList<>();
        try (RocksIterator it = db.newIterator(boxesCf)) {
            if (afterId == null) {
                it.seekToFirst();
            } else {
                it.seek(afterId);
                if (it.isValid() && Arrays.equals(it.key(), afterId)) {
                    it.next(); // exclusive of the cursor
                }
            }
            for (; it.isValid() && out.size() < limit; it.next()) {
                out.add(it.key());
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

    /** Smallest possible serialized journal entry: boxId(32) + prior-present flag(1). */
    private static final int MIN_JOURNAL_RECORD_BYTES = 32 + 1;

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
        // Defense-in-depth on a self-written blob: bound count/length before allocating so a corrupt or
        // truncated journal throws a clean error rather than new ArrayList<>(negative) / new byte[huge].
        // The count is bounded by the SMALLEST possible record (id 32 + flag 1), not by the raw
        // remaining byte count — a huge count in a short buffer must fail even when count <= remaining.
        if (count < 0 || count > buffer.remaining() / MIN_JOURNAL_RECORD_BYTES) {
            throw new IllegalStateException("box journal count out of range: " + count);
        }
        List<JournalEntry> journal = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] id = new byte[32];
            buffer.get(id);
            byte[] prior = null;
            if (buffer.get() == 1) {
                int len = buffer.getInt();
                if (len < 0 || len > buffer.remaining()) {
                    throw new IllegalStateException("box journal prior length out of range: " + len);
                }
                prior = new byte[len];
                buffer.get(prior);
            }
            journal.add(new JournalEntry(id, prior));
        }
        return journal;
    }

    @Override
    public void forEachBox(java.util.function.Consumer<Box> consumer) {
        try (RocksIterator it = db.newIterator(boxesCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                consumer.accept(Box.deserialize(it.value()));
            }
        }
    }
}
