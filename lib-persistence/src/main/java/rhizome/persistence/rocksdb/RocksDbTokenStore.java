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

import rhizome.core.token.TokenBalanceKey;
import rhizome.core.token.TokenId;
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
 *
 * <p>Keys are the typed {@link TokenId}/{@link TokenBalanceKey} encoded by
 * {@link TokenBalanceKey#toBytes()} — the same committed layout the state root uses, so
 * the store can never transpose the halves relative to consensus.
 */
public final class RocksDbTokenStore extends RocksDbStore implements TokenStore {

    private static final byte[] CF_META = "token_meta".getBytes();
    private static final byte[] CF_BALANCE = "token_balance".getBytes();
    private static final byte[] CF_MINTER = "token_minter".getBytes();
    private static final byte[] CF_HOLDER = "token_holder".getBytes();
    private static final byte[] CF_JOURNAL = "token_journal".getBytes();
    private static final byte[] EMPTY = new byte[0];
    private static final int ADDR = 25;

    private final ColumnFamilyHandle metaCf;
    private final ColumnFamilyHandle balanceCf;
    private final ColumnFamilyHandle minterCf;
    private final ColumnFamilyHandle holderCf;
    private final ColumnFamilyHandle journalCf;

    public RocksDbTokenStore(String path) throws java.io.IOException {
        super(path, "token store", CF_META, CF_BALANCE, CF_MINTER, CF_HOLDER, CF_JOURNAL);
        this.metaCf = handles.get(1);
        this.balanceCf = handles.get(2);
        this.minterCf = handles.get(3);
        this.holderCf = handles.get(4);
        this.journalCf = handles.get(5);
    }

    @Override
    public TokenMeta getMeta(TokenId tokenId) {
        byte[] bytes = raw(metaCf, tokenId.toBytes());
        return bytes == null ? null : TokenMeta.deserialize(bytes);
    }

    @Override
    public long getBalance(TokenBalanceKey key) {
        byte[] bytes = raw(balanceCf, key.toBytes());
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
                    TokenId id = m.meta().id();
                    journal.add(Undo.meta(id, raw(metaCf, id.toBytes())));
                    batch.put(metaCf, id.toBytes(), m.meta().serialize());
                    batch.put(minterCf, concat(m.meta().minter().toBytes(), id.toBytes()), EMPTY);
                } else if (op instanceof TokenOp.BalanceSet b) {
                    byte[] key = b.key().toBytes();
                    byte[] prior = raw(balanceCf, key);
                    journal.add(Undo.balance(b.key(), prior == null ? 0 : bytesToLong(prior, 0)));
                    setBalance(batch, b.key(), b.amount());
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
                        byte[] cur = raw(metaCf, u.tokenId().toBytes());
                        if (cur != null) {
                            batch.delete(minterCf,
                                concat(TokenMeta.deserialize(cur).minter().toBytes(), u.tokenId().toBytes()));
                        }
                        batch.delete(metaCf, u.tokenId().toBytes());
                    } else {
                        batch.put(metaCf, u.tokenId().toBytes(), u.priorMeta());
                    }
                } else {
                    setBalance(batch, u.key(), u.priorAmount());
                }
            }
            batch.delete(journalCf, longToBytes(height));
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("token store revertBlock failed", e);
        }
    }

    /** Sets a balance and keeps the holder index consistent (present iff amount &gt; 0). */
    private void setBalance(WriteBatch batch, TokenBalanceKey key, long amount)
            throws RocksDBException {
        // The store is the last line of defence: a negative balance persisted here would read
        // back as a real (debt) balance on every later lookup. Every producer path validates
        // upstream (audit: negative balance guard), so a negative here means corruption or a
        // bug — fail loud rather than persist it.
        if (amount < 0) {
            throw new IllegalStateException("negative token balance refused: " + amount);
        }
        byte[] keyBytes = key.toBytes();
        byte[] holderKey = concat(key.address().toBytes(), key.tokenId().toBytes());
        if (amount == 0) {
            batch.delete(balanceCf, keyBytes);
            batch.delete(holderCf, holderKey);
        } else {
            batch.put(balanceCf, keyBytes, longToBytes(amount));
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
    public List<TokenId> tokenIdsByMinter(byte[] minter, TokenId afterId, int limit) {
        return indexScan(minterCf, minter, afterId, limit);
    }

    @Override
    public List<TokenId> tokenIdsByHolder(byte[] address, TokenId afterId, int limit) {
        return indexScan(holderCf, address, afterId, limit);
    }

    /** Scans an {@code owner ‖ tokenId} index for tokenIds under {@code prefix}, after {@code afterId}. */
    private List<TokenId> indexScan(ColumnFamilyHandle cf, byte[] prefix, TokenId afterId, int limit) {
        List<TokenId> out = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cf)) {
            // Seek straight to the prefix ‖ afterId composite: keys sort lexicographically, so
            // every subsequent key under the prefix is strictly past the cursor. The old
            // seek(prefix) + Java-side filter re-scanned the prefix's whole history per page —
            // O(n) per page, O(n^2) to enumerate (audit: index pagination).
            if (afterId == null) {
                it.seek(prefix);
            } else {
                byte[] cursor = concat(prefix, afterId.toBytes());
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
                if (key.length != prefix.length + TokenId.SIZE) {
                    continue; // foreign record under the prefix: skip it, don't truncate the page
                }
                out.add(TokenId.of(Arrays.copyOfRange(key, prefix.length, key.length)));
            }
        }
        return out;
    }

    // ---- journal codec ----

    private record Undo(TokenId tokenId, byte[] priorMeta, TokenBalanceKey key, long priorAmount,
                        boolean isMeta) {
        static Undo meta(TokenId tokenId, byte[] priorMeta) {
            return new Undo(tokenId, priorMeta, null, 0, true);
        }

        static Undo balance(TokenBalanceKey key, long priorAmount) {
            return new Undo(key.tokenId(), null, key, priorAmount, false);
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
            buffer.put(u.tokenId().toBytes());
            if (u.isMeta()) {
                if (u.priorMeta() == null) {
                    buffer.put((byte) 0);
                } else {
                    buffer.put((byte) 1);
                    buffer.putInt(u.priorMeta().length);
                    buffer.put(u.priorMeta());
                }
            } else {
                buffer.put(u.key().address().toBytes());
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
            byte[] tokenIdBytes = new byte[TokenId.SIZE];
            buffer.get(tokenIdBytes);
            TokenId tokenId = TokenId.of(tokenIdBytes);
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
                journal.add(Undo.balance(
                    TokenBalanceKey.of(tokenId, rhizome.core.ledger.PublicAddress.of(address)),
                    priorAmount));
            }
        }
        return journal;
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
                consumer.accept(TokenBalanceKey.of(
                    TokenId.of(Arrays.copyOfRange(key, 0, TokenId.SIZE)),
                    rhizome.core.ledger.PublicAddress.of(Arrays.copyOfRange(key, TokenId.SIZE, key.length))),
                    bytesToLong(it.value(), 0));
            }
        }
    }
}
