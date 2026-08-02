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

import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenStore;

import static rhizome.core.common.Utils.longToBytes;

/**
 * RocksDB-backed {@link TokenStore}: token metadata, per-(token, holder) balances, the
 * minter and holder secondary indexes, and a persisted per-block undo journal so token
 * state is exactly restorable on a reorg (including one after a restart).
 *
 * <p>Column families: {@code token_meta} (tokenId -> meta), {@code token_balance}
 * ({@code tokenId ‖ address} -> amount), {@code token_minter} ({@code minter ‖ tokenId}),
 * {@code token_holder} ({@code address ‖ tokenId}, present iff balance &gt; 0),
 * {@code token_journal} (height -> undo journal).
 */
public final class RocksDbTokenStore implements TokenStore, AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private static final byte[] CF_META = "token_meta".getBytes();
    private static final byte[] CF_BALANCE = "token_balance".getBytes();
    private static final byte[] CF_MINTER = "token_minter".getBytes();
    private static final byte[] CF_HOLDER = "token_holder".getBytes();
    private static final byte[] CF_JOURNAL = "token_journal".getBytes();
    private static final byte[] EMPTY = new byte[0];
    private static final int ADDR = 25;

    private final RocksDB db;
    private final DBOptions dbOptions;
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle metaCf;
    private final ColumnFamilyHandle balanceCf;
    private final ColumnFamilyHandle minterCf;
    private final ColumnFamilyHandle holderCf;
    private final ColumnFamilyHandle journalCf;
    // Synced: apply/revert batches move token state across a height boundary (audit F3).
    private final WriteOptions writeOptions = new WriteOptions().setSync(true);

    public RocksDbTokenStore(String path) throws IOException {
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_META),
            new ColumnFamilyDescriptor(CF_BALANCE),
            new ColumnFamilyDescriptor(CF_MINTER),
            new ColumnFamilyDescriptor(CF_HOLDER),
            new ColumnFamilyDescriptor(CF_JOURNAL));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        // DBOptions is kept and closed in close() AFTER db.close(): never while the DB is live
        // (rocksdbjni keeps referencing it — closing it live corrupts the native heap), and not
        // at all was a native-handle leak (audit F12).
        DBOptions options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        try {
            this.db = RocksDB.open(options, path, descriptors, handles);
        } catch (RocksDBException e) {
            options.close();
            throw new IOException("Failed to open token store at " + path, e);
        }
        this.dbOptions = options;
        this.defaultCf = handles.get(0);
        this.metaCf = handles.get(1);
        this.balanceCf = handles.get(2);
        this.minterCf = handles.get(3);
        this.holderCf = handles.get(4);
        this.journalCf = handles.get(5);
    }

    @Override
    public TokenMeta getMeta(byte[] tokenId) {
        byte[] bytes = raw(metaCf, tokenId);
        return bytes == null ? null : TokenMeta.deserialize(bytes);
    }

    @Override
    public long getBalance(byte[] tokenId, byte[] address) {
        byte[] bytes = raw(balanceCf, concat(tokenId, address));
        return bytes == null ? 0L : bytesToLong(bytes, 0);
    }

    @Override
    public void applyBlock(long height, List<TokenOp> ops) {
        // Refuse a double-apply: re-applying a block would journal its own already-mutated state
        // as the "prior", so a later revert would restore the wrong values (audit F10).
        if (raw(journalCf, longToBytes(height)) != null) {
            throw new IllegalStateException("token store already has a journal at height " + height);
        }
        try (WriteBatch batch = new WriteBatch()) {
            List<Undo> journal = new ArrayList<>(ops.size());
            for (TokenOp op : ops) {
                if (op instanceof TokenOp.MetaSet m) {
                    byte[] id = m.meta().id();
                    journal.add(Undo.meta(id, raw(metaCf, id)));
                    batch.put(metaCf, id, m.meta().serialize());
                    batch.put(minterCf, concat(m.meta().minter().toBytes(), id), EMPTY);
                } else if (op instanceof TokenOp.BalanceSet b) {
                    byte[] key = concat(b.tokenId(), b.address());
                    byte[] prior = raw(balanceCf, key);
                    journal.add(Undo.balance(b.tokenId(), b.address(), prior == null ? 0 : bytesToLong(prior, 0)));
                    setBalance(batch, b.tokenId(), b.address(), b.amount());
                }
            }
            // A op-less apply persists no journal: revertBlock maps a missing journal to
            // "nothing to undo", so the 4-byte empty row was a pure cost (audit: empty journals).
            if (!journal.isEmpty()) {
                batch.put(journalCf, longToBytes(height), encodeJournal(journal));
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("token store applyBlock failed", e);
        }
    }

    @Override
    public void revertBlock(long height) {
        byte[] journalBytes = raw(journalCf, longToBytes(height));
        if (journalBytes == null) {
            return;
        }
        try (WriteBatch batch = new WriteBatch()) {
            List<Undo> journal = decodeJournal(journalBytes);
            for (int i = journal.size() - 1; i >= 0; i--) {
                Undo u = journal.get(i);
                if (u.isMeta()) {
                    if (u.priorMeta() == null) {
                        // Was a fresh mint: drop the meta and its minter index (minter from current meta).
                        byte[] cur = raw(metaCf, u.tokenId());
                        if (cur != null) {
                            batch.delete(minterCf, concat(TokenMeta.deserialize(cur).minter().toBytes(), u.tokenId()));
                        }
                        batch.delete(metaCf, u.tokenId());
                    } else {
                        batch.put(metaCf, u.tokenId(), u.priorMeta());
                    }
                } else {
                    setBalance(batch, u.tokenId(), u.address(), u.priorAmount());
                }
            }
            batch.delete(journalCf, longToBytes(height));
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("token store revertBlock failed", e);
        }
    }

    /** Sets a balance and keeps the holder index consistent (present iff amount &gt; 0). */
    private void setBalance(WriteBatch batch, byte[] tokenId, byte[] address, long amount)
            throws RocksDBException {
        // The store is the last line of defence: a negative balance persisted here would read
        // back as a real (debt) balance on every later lookup. Every producer path validates
        // upstream (audit: negative balance guard), so a negative here means corruption or a
        // bug — fail loud rather than persist it.
        if (amount < 0) {
            throw new IllegalStateException("negative token balance refused: " + amount);
        }
        byte[] key = concat(tokenId, address);
        byte[] holderKey = concat(address, tokenId);
        if (amount == 0) {
            batch.delete(balanceCf, key);
            batch.delete(holderCf, holderKey);
        } else {
            batch.put(balanceCf, key, longToBytes(amount));
            batch.put(holderCf, holderKey, EMPTY);
        }
    }

    @Override
    public void pruneJournals(long minHeight) {
        try {
            // Synced, consistent with every other delete in this store (audit: prune durability).
            db.deleteRange(journalCf, writeOptions, longToBytes(0), longToBytes(minHeight));
        } catch (RocksDBException e) {
            throw new IllegalStateException("token store pruneJournals failed", e);
        }
    }

    @Override
    public List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit) {
        return indexScan(minterCf, minter, afterId, limit);
    }

    @Override
    public List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit) {
        return indexScan(holderCf, address, afterId, limit);
    }

    /** Scans an {@code owner ‖ tokenId} index for tokenIds under {@code prefix}, after {@code afterId}. */
    private List<byte[]> indexScan(ColumnFamilyHandle cf, byte[] prefix, byte[] afterId, int limit) {
        List<byte[]> out = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cf)) {
            // Seek straight to the prefix ‖ afterId composite: keys sort lexicographically, so
            // every subsequent key under the prefix is strictly past the cursor. The old
            // seek(prefix) + Java-side filter re-scanned the prefix's whole history per page —
            // O(n) per page, O(n^2) to enumerate (audit: index pagination).
            if (afterId == null) {
                it.seek(prefix);
            } else {
                byte[] cursor = concat(prefix, afterId);
                it.seek(cursor);
                if (it.isValid() && Arrays.equals(it.key(), cursor)) {
                    it.next(); // exclusive of the cursor
                }
            }
            for (; it.isValid() && out.size() < limit; it.next()) {
                byte[] key = it.key();
                if (key.length < prefix.length || !startsWith(key, prefix)) {
                    break; // past the prefix
                }
                if (key.length != prefix.length + 32) {
                    continue; // foreign record under the prefix: skip it, don't truncate the page
                }
                out.add(Arrays.copyOfRange(key, prefix.length, key.length));
            }
        }
        return out;
    }

    // ---- journal codec ----

    private record Undo(byte[] tokenId, byte[] priorMeta, byte[] address, long priorAmount, boolean isMeta) {
        static Undo meta(byte[] tokenId, byte[] priorMeta) {
            return new Undo(tokenId, priorMeta, null, 0, true);
        }

        static Undo balance(byte[] tokenId, byte[] address, long priorAmount) {
            return new Undo(tokenId, null, address, priorAmount, false);
        }
    }

    /** Smallest possible serialized journal entry: tag(1) + tokenId(32) + priorMeta flag(1). */
    private static final int MIN_JOURNAL_RECORD_BYTES = 1 + 32 + 1;

    private static byte[] encodeJournal(List<Undo> journal) {
        int size = 4;
        for (Undo u : journal) {
            size += 1 + 32; // tag + tokenId
            if (u.isMeta()) {
                size += 1 + (u.priorMeta() == null ? 0 : 4 + u.priorMeta().length);
            } else {
                size += ADDR + 8;
            }
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putInt(journal.size());
        for (Undo u : journal) {
            buffer.put((byte) (u.isMeta() ? 0 : 1));
            buffer.put(u.tokenId());
            if (u.isMeta()) {
                if (u.priorMeta() == null) {
                    buffer.put((byte) 0);
                } else {
                    buffer.put((byte) 1);
                    buffer.putInt(u.priorMeta().length);
                    buffer.put(u.priorMeta());
                }
            } else {
                buffer.put(u.address());
                buffer.putLong(u.priorAmount());
            }
        }
        return buffer.array();
    }

    private static List<Undo> decodeJournal(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int count = buffer.getInt();
        // Journals are written by this node from its own mutations, so this is defense-in-depth, not a
        // remote vector — but bound count/length before allocating so a corrupt/truncated blob throws a
        // clean error instead of new ArrayList<>(negative) or new byte[huge] (mirrors every wire decoder).
        // The count is bounded by the SMALLEST possible record (tag 1 + tokenId 32 + meta flag 1),
        // not by the raw remaining byte count.
        if (count < 0 || count > buffer.remaining() / MIN_JOURNAL_RECORD_BYTES) {
            throw new IllegalStateException("token journal count out of range: " + count);
        }
        List<Undo> journal = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean isMeta = buffer.get() == 0;
            byte[] tokenId = new byte[32];
            buffer.get(tokenId);
            if (isMeta) {
                byte[] priorMeta = null;
                if (buffer.get() == 1) {
                    int len = buffer.getInt();
                    if (len < 0 || len > buffer.remaining()) {
                        throw new IllegalStateException("token journal meta length out of range: " + len);
                    }
                    priorMeta = new byte[len];
                    buffer.get(priorMeta);
                }
                journal.add(Undo.meta(tokenId, priorMeta));
            } else {
                byte[] address = new byte[ADDR];
                buffer.get(address);
                long priorAmount = buffer.getLong();
                journal.add(Undo.balance(tokenId, address, priorAmount));
            }
        }
        return journal;
    }

    // ---- helpers ----

    private byte[] raw(ColumnFamilyHandle cf, byte[] key) {
        try {
            return db.get(cf, key);
        } catch (RocksDBException e) {
            throw new IllegalStateException("token store read failed", e);
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
        // Fixed-width values are auto-written (8 bytes): a short record means store corruption —
        // fail with a diagnosable IllegalStateException, not a raw ArrayIndexOutOfBoundsException
        // (audit: fixed-width decode).
        if (b.length < offset + 8) {
            throw new IllegalStateException("corrupt token store: expected 8-byte value at offset "
                + offset + ", array length " + b.length);
        }
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
    public void forEachMeta(java.util.function.Consumer<TokenMeta> consumer) {
        try (RocksIterator it = db.newIterator(metaCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                consumer.accept(TokenMeta.deserialize(it.value()));
            }
        }
    }

    @Override
    public void forEachBalance(BalanceConsumer consumer) {
        // Balance keys are tokenId(32) ‖ address(25).
        try (RocksIterator it = db.newIterator(balanceCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] key = it.key();
                consumer.accept(Arrays.copyOfRange(key, 0, 32), Arrays.copyOfRange(key, 32, key.length),
                    bytesToLong(it.value(), 0));
            }
        }
    }

    @Override
    public void close() {
        defaultCf.close();
        metaCf.close();
        balanceCf.close();
        minterCf.close();
        holderCf.close();
        journalCf.close();
        writeOptions.close();
        db.close();
        dbOptions.close(); // after the DB: rocksdbjni references the options while the DB is live
    }
}
