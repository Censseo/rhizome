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
    private final ColumnFamilyHandle defaultCf;
    private final ColumnFamilyHandle metaCf;
    private final ColumnFamilyHandle balanceCf;
    private final ColumnFamilyHandle minterCf;
    private final ColumnFamilyHandle holderCf;
    private final ColumnFamilyHandle journalCf;
    private final WriteOptions writeOptions = new WriteOptions();

    public RocksDbTokenStore(String path) throws IOException {
        List<ColumnFamilyDescriptor> descriptors = List.of(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor(CF_META),
            new ColumnFamilyDescriptor(CF_BALANCE),
            new ColumnFamilyDescriptor(CF_MINTER),
            new ColumnFamilyDescriptor(CF_HOLDER),
            new ColumnFamilyDescriptor(CF_JOURNAL));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try {
            DBOptions options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            this.db = RocksDB.open(options, path, descriptors, handles);
        } catch (RocksDBException e) {
            throw new IOException("Failed to open token store at " + path, e);
        }
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
            batch.put(journalCf, longToBytes(height), encodeJournal(journal));
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
            db.deleteRange(journalCf, longToBytes(0), longToBytes(minHeight));
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
            for (it.seek(prefix); it.isValid() && out.size() < limit; it.next()) {
                byte[] key = it.key();
                if (key.length != prefix.length + 32 || !startsWith(key, prefix)) {
                    break;
                }
                byte[] tokenId = Arrays.copyOfRange(key, prefix.length, key.length);
                if (afterId == null || Arrays.compareUnsigned(tokenId, afterId) > 0) {
                    out.add(tokenId);
                }
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
        List<Undo> journal = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean isMeta = buffer.get() == 0;
            byte[] tokenId = new byte[32];
            buffer.get(tokenId);
            if (isMeta) {
                byte[] priorMeta = null;
                if (buffer.get() == 1) {
                    priorMeta = new byte[buffer.getInt()];
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
        metaCf.close();
        balanceCf.close();
        minterCf.close();
        holderCf.close();
        journalCf.close();
        writeOptions.close();
        db.close();
    }
}
