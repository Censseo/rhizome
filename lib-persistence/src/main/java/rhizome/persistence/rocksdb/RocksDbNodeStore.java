package rhizome.persistence.rocksdb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import rhizome.core.block.Block;
import rhizome.core.block.BlockCodec;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.HeaderCodec;
import rhizome.core.blockchain.ChainStore;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.LedgerJournalCodec;
import rhizome.persistence.PersistenceException;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;

import static rhizome.core.common.Utils.longToBytes;

/**
 * RocksDB-backed node storage: a single database with column families for
 * blocks, the transaction index, chain metadata, and the ledger. This is the
 * only durable store; tests and the native-image path run against the in-memory
 * implementations in lib-core.
 *
 * <p>Sharing one database lets block application commit atomically via a single
 * {@link WriteBatch} — the fix for Pandanite's independent LevelDB directories
 * that could disagree after a crash (issue #54). {@link #chainStore()} already
 * commits a block and its transaction-index entries in one batch; a future
 * refactor can extend the same batch across the ledger.
 */
public final class RocksDbNodeStore extends RocksDbStore implements rhizome.core.blockchain.NodeStores {

    private static final byte[] CF_BLOCKS = "blocks".getBytes();
    private static final byte[] CF_HEADERS = "headers".getBytes();
    private static final byte[] CF_TXINDEX = "txindex".getBytes();
    private static final byte[] CF_META = "meta".getBytes();
    private static final byte[] CF_LEDGER = "ledger".getBytes();
    /**
     * Persisted per-block ledger undo journal (height BE(8) -> serialized LedgerOp list), so a
     * reorg can reverse a block's ledger mutations after a restart without re-deriving each
     * inverse from the transaction (audit: one undo protocol, and a journal for the ledger).
     * Written by the executor's executeBlock alongside the block commit; pruned on the same
     * height schedule as the box/token/contract journals.
     */
    private static final byte[] CF_LEDGER_JOURNAL = "ledger_journal".getBytes();
    private static final byte[] CF_NONCES = "nonces".getBytes();
    /**
     * Bodies of uncles referenced by canonical blocks, keyed by block hash. The engine's orphan
     * pool is a bounded in-memory LRU, so without persistence a restart (or pool churn) would
     * leave this node — and any peer syncing an uncle-bearing chain from it — unable to serve a
     * referenced orphan body (audit: uncle-sync blocker). Only uncles a canonical block cites are
     * written (the engine calls {@code putUncle} at accept time), so growth is bounded by the
     * chain's own uncle rate, never by the unauthenticated orphan-ingest path.
     */
    private static final byte[] CF_UNCLES = "uncles".getBytes();
    private static final byte[] HEIGHT_KEY = "height".getBytes();
    private static final byte[] PRUNED_BELOW_KEY = "prunedBelow".getBytes();
    private static final byte[] NONCE_HEIGHT_KEY = "nonceHeight".getBytes();
    /** Set while a snap-sync bootstrap is seeding the several stores; cleared only on success. */
    private static final byte[] BOOTSTRAP_KEY = "bootstrapInProgress".getBytes();
    /**
     * Highest height through which the one-time header backfill migration is known complete. Advanced
     * in every {@link RocksChainStore#append} batch (each append writes its header in the same batch)
     * so a fully-migrated chain skips the O(height) boot sweep in {@link #backfillHeaders} in O(1),
     * instead of re-probing every height on every restart (audit P12).
     */
    private static final byte[] HEADERS_BACKFILLED_KEY = "headersBackfilledThrough".getBytes();

    private static final long GENESIS_HEIGHT = 1L;

    /** Bodies for the most recent {@code keepBlocks} heights are retained (0 = archive, keep all). */
    private final int keepBlocks;

    private final ColumnFamilyHandle blocksCf;
    private final ColumnFamilyHandle headersCf;
    private final ColumnFamilyHandle txIndexCf;
    private final ColumnFamilyHandle metaCf;
    private final ColumnFamilyHandle ledgerCf;
    private final ColumnFamilyHandle ledgerJournalCf;
    private final ColumnFamilyHandle noncesCf;
    private final ColumnFamilyHandle unclesCf;

    /** Headers per WriteBatch in {@link #bootstrapHeaders} (audit: unbounded bootstrap batch). */
    private static final int BOOTSTRAP_BATCH_CHUNK = 10_000;

    /**
     * Ledger writes staged for the current block, so they commit in the SAME atomic {@link WriteBatch}
     * as the block body and chain height (audit S3). The ledger lives in this DB but was written
     * per-wallet during executeBlock, before the separate height append — a crash between the two left
     * the ledger a block ahead of the height with no journal to rewind it, an unrecoverable tear. While
     * a block commit is open ({@code pendingLedger != null}, set under the engine's single write lock)
     * ledger writes buffer here and reads see them (read-your-writes within the block); {@code append}/
     * {@code pop} flush them into their batch. Null outside a block commit, so genesis/snapshot seeding
     * and every read fall straight through to the column family, unchanged. Concurrent so a reader that
     * (like today) observes mid-block ledger state never corrupts the map.
     */
    private volatile java.util.concurrent.ConcurrentHashMap<rhizome.core.ledger.PublicAddress, Long> pendingLedger;
    /**
     * The current block's ledger undo journal, staged so it flushes in the SAME atomic batch as
     * the block, height and ledger writes (audit: one undo protocol — the journal must never be
     * a block ahead of the mutations it undoes). Set with the block commit; cleared with it.
     */
    private volatile java.util.Map<Long, byte[]> pendingLedgerJournal;

    /**
     * Account-nonce writes staged for the current block, flushed in the SAME batch as the block
     * and height — same pattern and rationale as {@link #pendingLedger} above. Without staging,
     * {@code commitAccountNonces} paid one synced {@code db.put} PER SENDER after the append
     * (a 200-sender block = ~200 extra fsyncs, audit perf), and a crash between the append and
     * those puts left the nonce store a block behind (healed by the boot re-sync, but torn).
     * A staged value {@code <= 0} flushes as a delete (the {@code set(_, 0)} clear convention).
     * {@link #pendingNonceHeight} stages the {@code markSyncedThrough} watermark the same way.
     */
    private volatile java.util.concurrent.ConcurrentHashMap<rhizome.core.ledger.PublicAddress, Long> pendingNonces;
    private volatile Long pendingNonceHeight;

    // Memoized: chainStore()/ledger()/nonceStore() used to build a NEW view object on every call —
    // harmless while these views are stateless, and a bug the day a caller compares instances or
    // relies on identity. ChainEngine.boot and DomainStateAdapter now see the SAME three objects
    // (see NodeStores), which is what makes "these three views are one atomic database" a type-level
    // fact instead of a convention. Assigned at the end of the constructor, after every field these
    // views read is already set.
    private final ChainStore chainStoreView;
    private final Ledger ledgerView;
    private final rhizome.core.blockchain.NonceStore nonceStoreView;

    public RocksDbNodeStore(String path) throws IOException {
        this(path, 0);
    }

    /**
     * @param keepBlocks number of most-recent block bodies to retain (0 = archive node,
     *                   keep every body). Headers, the transaction index and genesis are
     *                   always retained. The caller is responsible for enforcing a safe
     *                   floor (≥ the deepest history the engine may read: reorg depth,
     *                   uncle depth, difficulty/median windows).
     */
    public RocksDbNodeStore(String path, int keepBlocks) throws IOException {
        super(path, "node store", CF_BLOCKS, CF_HEADERS, CF_TXINDEX, CF_META, CF_LEDGER, CF_LEDGER_JOURNAL,
            CF_NONCES, CF_UNCLES);
        this.keepBlocks = keepBlocks;
        this.blocksCf = handles.get(1);
        this.headersCf = handles.get(2);
        this.txIndexCf = handles.get(3);
        this.metaCf = handles.get(4);
        this.ledgerCf = handles.get(5);
        this.ledgerJournalCf = handles.get(6);
        this.noncesCf = handles.get(7);
        this.unclesCf = handles.get(8);
        backfillHeaders();
        catchUpPruning();
        // Assigned last: each view's constructor only captures RocksDbNodeStore.this, but placing
        // this after every other field keeps that safety visible rather than deduced.
        this.chainStoreView = new RocksChainStore();
        this.ledgerView = new RocksLedger();
        this.nonceStoreView = new RocksNonceStore();
    }

    /**
     * In-memory chain height, authoritative with the HEIGHT_KEY meta row: updated by the only
     * three writers of that row (append, pop, header bootstrap) after their batch lands, and
     * seeded at open. {@code height()} was previously a {@code db.get} per call — several times
     * per added block and per API scalar, all under the engine lock (audit perf).
     */
    private final java.util.concurrent.atomic.AtomicLong heightCache = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Boot catch-up: if this node runs pruned but holds bodies older than the retention
     * window (e.g. pruning was just enabled on an archive, or {@code keepBlocks} shrank),
     * discard them in one pass so the on-disk state matches the configured retention.
     */
    private void catchUpPruning() {
        if (keepBlocks <= 0) {
            return;
        }
        long height = new RocksChainStore().height();
        long firstToKeep = height - keepBlocks + 1;
        if (firstToKeep > GENESIS_HEIGHT + 1) {
            new RocksChainStore().pruneBodiesBelow(firstToKeep);
        }
    }

    /**
     * Boot migration: an older database has block bodies but no {@code headers}
     * column family. Derive every header from its stored block in one pass so
     * the engine can run header-only afterwards. Idempotent — a header already
     * present is left untouched — so a partially-backfilled database (crash
     * mid-migration) is completed on the next boot rather than restarted.
     */
    private void backfillHeaders() throws IOException {
        long height;
        try {
            byte[] value = db.get(metaCf, HEIGHT_KEY);
            height = value == null ? 0 : checkedLong(value);
        } catch (RocksDBException e) {
            throw new IOException("Failed to read chain height during header backfill", e);
        }
        heightCache.set(height); // seed the in-memory height (see heightCache)
        if (height == 0) {
            return; // fresh database: nothing to migrate
        }
        try {
            byte[] done = db.get(metaCf, HEADERS_BACKFILLED_KEY);
            if (done != null && checkedLong(done) >= height) {
                return; // already backfilled through the tip; appends since kept the header CF complete
            }
        } catch (RocksDBException e) {
            throw new IOException("Failed to read header backfill watermark", e);
        }
        try (WriteBatch batch = new WriteBatch()) {
            long migrated = 0;
            int inBatch = 0;
            for (long h = 1; h <= height; h++) {
                byte[] key = heightKey(h);
                if (db.get(headersCf, key) != null) {
                    continue; // already backfilled
                }
                byte[] body = db.get(blocksCf, key);
                if (body == null) {
                    continue; // pruned body with no header: nothing to derive from
                }
                Block block = BlockCodec.decode(body);
                // One synced batch per chunk instead of one synced put per height — the per-header
                // fsync made migrating a long chain cost O(height) fsyncs at boot (audit perf). A
                // crash between chunks is harmless: the sweep is idempotent and the watermark below
                // is written only after the last chunk.
                batch.put(headersCf, key, HeaderCodec.encode(BlockHeader.of(block)));
                migrated++;
                if (++inBatch >= 1024) {
                    db.write(writeOptions, batch);
                    batch.clear();
                    inBatch = 0;
                }
            }
            if (migrated > 0) {
                System.out.println("[RocksDbNodeStore] backfilled " + migrated + " block header(s)");
            }
            // Record that the header CF is now complete through the tip, so the next restart skips this
            // sweep in O(1). Appends past this point keep it current (see HEADERS_BACKFILLED_KEY).
            batch.put(metaCf, HEADERS_BACKFILLED_KEY, longToBytes(height));
            db.write(writeOptions, batch); // final chunk and the watermark commit atomically
        } catch (RocksDBException e) {
            throw new IOException("Failed to backfill block headers", e);
        }
    }

    @Override
    public ChainStore chainStore() {
        return chainStoreView;
    }

    /**
     * Snap-sync bootstrap: adopts a run of already-validated headers as chain history
     * without their bodies. The store must hold exactly genesis (height 1) and the headers
     * must be contiguous from height 2. Afterwards {@code height()} is the last header's
     * height, and the body-less range is marked pruned so peers and the local engine treat
     * it exactly like a pruned node's discarded history.
     */
    public void bootstrapHeaders(List<BlockHeader> headers) {
        long height = new RocksChainStore().height();
        if (height != 1) {
            throw new IllegalStateException("bootstrap requires a store holding exactly genesis, height was " + height);
        }
        if (headers.isEmpty()) {
            return;
        }
        // Chunked batches: a single unbounded WriteBatch materialised every header at once —
        // a long snap-sync range pinned hundreds of MB in the batch's native buffer (audit:
        // unbounded bootstrap batch). A crash between chunks simply re-runs the bootstrap: the
        // store still holds exactly genesis (the meta rows land only with the last chunk).
        try (WriteBatch batch = new WriteBatch()) {
            long expected = 2;
            int inBatch = 0;
            for (BlockHeader header : headers) {
                if (header.id() != expected) {
                    throw new IllegalArgumentException("non-contiguous bootstrap header at " + header.id()
                        + ", expected " + expected);
                }
                batch.put(headersCf, heightKey(expected), HeaderCodec.encode(header));
                expected++;
                if (++inBatch >= BOOTSTRAP_BATCH_CHUNK) {
                    db.write(writeOptions, batch);
                    batch.clear();
                    inBatch = 0;
                }
            }
            long tip = expected - 1;
            batch.put(metaCf, HEIGHT_KEY, heightKey(tip));
            batch.put(metaCf, PRUNED_BELOW_KEY, heightKey(tip + 1));
            db.write(writeOptions, batch);
            heightCache.set(tip);
        } catch (RocksDBException e) {
            throw new LedgerException("Failed to bootstrap headers", e);
        }
    }

    @Override
    public Ledger ledger() {
        return ledgerView;
    }

    /** Adds the block commit's staged ledger writes (if any) to {@code batch}, for an atomic flush. */
    private void stagePendingLedgerInto(WriteBatch batch) throws RocksDBException {
        var pending = pendingLedger;
        if (pending != null) {
            for (var e : pending.entrySet()) {
                batch.put(ledgerCf, e.getKey().toBytes(),
                    ByteBuffer.allocate(8).putLong(e.getValue()).array());
            }
        }
        var journal = pendingLedgerJournal;
        if (journal != null) {
            for (var e : journal.entrySet()) {
                batch.put(ledgerJournalCf, longToBytes(e.getKey()), e.getValue());
            }
        }
    }

    /** Adds the block commit's staged nonce writes and watermark (if any) to {@code batch}. */
    private void stagePendingNoncesInto(WriteBatch batch) throws RocksDBException {
        var pending = pendingNonces;
        if (pending != null) {
            for (var e : pending.entrySet()) {
                if (e.getValue() <= 0) {
                    batch.delete(noncesCf, e.getKey().toBytes());
                } else {
                    batch.put(noncesCf, e.getKey().toBytes(), longToBytes(e.getValue()));
                }
            }
        }
        Long nonceHeight = pendingNonceHeight;
        if (nonceHeight != null) {
            batch.put(metaCf, NONCE_HEIGHT_KEY, longToBytes(nonceHeight));
        }
    }

    /** Persisted next-nonce-per-sender, so the engine need not replay bodies at boot. */
    @Override
    public rhizome.core.blockchain.NonceStore nonceStore() {
        return nonceStoreView;
    }

    /**
     * Marks that a snap-sync bootstrap has begun seeding the several independent stores
     * (ledger, boxes, tokens, state, contracts, chain). Because those commit separately, a
     * crash between them leaves the node inconsistent; the marker lets boot detect that and
     * refuse to run on half-seeded data rather than silently diverging (audit M8).
     */
    public void beginBootstrap() {
        put(metaCf, BOOTSTRAP_KEY, new byte[] {1});
    }

    /** Clears the bootstrap marker after every store has been seeded and committed. */
    public void endBootstrap() {
        delete(metaCf, BOOTSTRAP_KEY);
    }

    /** True if a previous bootstrap did not finish — the on-disk state must be treated as inconsistent. */
    public boolean bootstrapInProgress() {
        return raw(metaCf, BOOTSTRAP_KEY) != null;
    }

    @Override
    public void close() {
        // Best-effort fsync of any bulk-seeded writes not yet covered by a synced batch.
        syncWal();
        super.close();
    }

    // ---- ChainStore view ----

    private final class RocksChainStore implements ChainStore {

        @Override
        public long height() {
            return heightCache.get();
        }

        @Override
        public Block blockAt(long height) {
            byte[] value = raw(blocksCf, heightKey(height));
            if (value == null) {
                throw new IllegalArgumentException("No block at height " + height);
            }
            return BlockCodec.decode(value);
        }

        @Override
        public BlockHeader headerAt(long height) {
            byte[] value = raw(headersCf, heightKey(height));
            if (value != null) {
                return HeaderCodec.decode(value);
            }
            // No stored header (should not happen post-backfill); fall back to the body.
            return BlockHeader.of(blockAt(height));
        }

        @Override
        public void append(Block block) {
            long expected = height() + 1;
            if (block.id() != expected) {
                throw new IllegalArgumentException(
                    "Expected block " + expected + " but got " + block.id());
            }
            try (WriteBatch batch = new WriteBatch()) {
                byte[] key = heightKey(expected);
                batch.put(blocksCf, key, BlockCodec.encode(block));
                // The header is committed in the same batch as the body, so the two
                // column families can never disagree after a crash.
                batch.put(headersCf, key, HeaderCodec.encode(BlockHeader.of(block)));
                for (Transaction t : block.transactions()) {
                    if (!t.isTransactionFee()) {
                        // raw(): WriteBatch.put copies the key into the native batch buffer
                        // before returning, so no clone is needed for the JNI hand-off.
                        batch.put(txIndexCf, t.hashContents().raw(), key);
                    }
                }
                batch.put(metaCf, HEIGHT_KEY, key);
                // Keep the header-backfill watermark at the tip: this append wrote its own header
                // above, so the header CF stays complete and a restart skips the migration sweep (P12).
                batch.put(metaCf, HEADERS_BACKFILLED_KEY, key);
                // This block's ledger writes ride the SAME batch as the height, so the ledger can
                // never be a block ahead of (or behind) the chain height after a crash (audit S3).
                stagePendingLedgerInto(batch);
                // Same atomicity for the account nonces (audit perf: per-sender fsync).
                stagePendingNoncesInto(batch);
                // Incremental pruning (amortised O(1)): the body that just fell out of the
                // retention window is discarded in the same batch. Genesis is never pruned.
                if (keepBlocks > 0) {
                    long fallsOut = expected - keepBlocks;
                    if (fallsOut > GENESIS_HEIGHT) {
                        batch.delete(blocksCf, heightKey(fallsOut));
                        batch.put(metaCf, PRUNED_BELOW_KEY, heightKey(fallsOut + 1));
                    }
                }
                db.write(writeOptions, batch);
                heightCache.set(expected);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to append block " + expected, e);
            } finally {
                // Clear the staged ledger writes whether the batch landed or not: leaving them in
                // place after a failure would silently merge them into the NEXT block's commit
                // (audit F9).
                pendingLedger = null;
                pendingLedgerJournal = null;
                pendingNonces = null;
                pendingNonceHeight = null;
            }
        }

        @Override
        public void beginBlockCommit() {
            if (pendingLedger != null) {
                throw new IllegalStateException("a block commit is already open"); // audit F9
            }
            pendingLedger = new java.util.concurrent.ConcurrentHashMap<>();
            pendingLedgerJournal = new java.util.concurrent.ConcurrentHashMap<>();
            pendingNonces = new java.util.concurrent.ConcurrentHashMap<>();
            pendingNonceHeight = null;
        }

        @Override
        public void discardBlockCommit() {
            pendingLedger = null;
            pendingLedgerJournal = null;
            pendingNonces = null;
            pendingNonceHeight = null;
        }

        @Override
        public boolean hasBody(long height) {
            if (height == GENESIS_HEIGHT) {
                return true;
            }
            return raw(blocksCf, heightKey(height)) != null;
        }

        @Override
        public long prunedBelow() {
            byte[] value = raw(metaCf, PRUNED_BELOW_KEY);
            return value == null ? 0 : checkedLong(value);
        }

        @Override
        public void pruneBodiesBelow(long height) {
            long from = Math.max(GENESIS_HEIGHT + 1, prunedBelow());
            if (height <= from) {
                return;
            }
            // The blocks CF is keyed by the 8-byte big-endian height ONLY, so the pruned bodies
            // form one contiguous key range [from, height): a single deleteRange tombstone
            // replaces an unbounded WriteBatch holding one delete per body (audit: giant prune
            // batch). The range tombstone and the watermark ride the SAME WriteBatch, so a
            // crash cannot leave bodies deleted with a stale watermark. Headers + txindex are
            // retained, as before.
            try (WriteBatch batch = new WriteBatch()) {
                batch.deleteRange(blocksCf, heightKey(from), heightKey(height));
                batch.put(metaCf, PRUNED_BELOW_KEY, heightKey(height));
                db.write(writeOptions, batch);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to prune bodies below " + height, e);
            }
        }

        @Override
        public void pop() {
            long height = height();
            if (height == 0) {
                throw new IllegalStateException("Cannot pop an empty chain");
            }
            Block tip = blockAt(height);
            try (WriteBatch batch = new WriteBatch()) {
                byte[] key = heightKey(height);
                batch.delete(blocksCf, key);
                batch.delete(headersCf, key);
                for (Transaction t : tip.transactions()) {
                    if (!t.isTransactionFee()) {
                        batch.delete(txIndexCf, t.hashContents().raw()); // copied natively — see append
                    }
                }
                batch.put(metaCf, HEIGHT_KEY, heightKey(height - 1));
                // The block's ledger reversals (staged during rollbackBlock) ride the same batch as
                // the height decrement, so the pop is atomic for the ledger too (audit S3).
                stagePendingLedgerInto(batch);
                stagePendingNoncesInto(batch);
                db.write(writeOptions, batch);
                heightCache.set(height - 1);
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to pop block " + height, e);
            } finally {
                pendingLedger = null; // same failed-commit rule as append (audit F9)
                pendingLedgerJournal = null;
                pendingNonces = null;
                pendingNonceHeight = null;
            }
        }

        @Override
        public void putUncle(SHA256Hash hash, Block uncle) {
            try {
                // raw(): db.put copies the key into a native Slice for the duration of the call.
                db.put(unclesCf, writeOptions, hash.raw(), BlockCodec.encode(uncle));
            } catch (RocksDBException e) {
                throw new LedgerException("Failed to store uncle " + hash.toHexString(), e);
            }
        }

        @Override
        public Block uncleAt(SHA256Hash hash) {
            byte[] value = raw(unclesCf, hash.raw());
            return value == null ? null : BlockCodec.decode(value);
        }

        @Override
        public boolean hasTransaction(SHA256Hash contentHash) {
            return raw(txIndexCf, contentHash.raw()) != null;
        }

        @Override
        public Long transactionHeight(SHA256Hash contentHash) {
            byte[] value = raw(txIndexCf, contentHash.raw());
            return value == null ? null : checkedLong(value);
        }
    }

    // ---- Ledger view (checked arithmetic: every mutation is range-checked, so withdrawing or
    // reverting below zero raises rather than wrapping around as Pandanite's C++ ledger did) ----

    private final class RocksLedger implements Ledger {

        @Override
        public boolean hasWallet(PublicAddress wallet) {
            return rawValue(wallet) != null;
        }

        @Override
        public void createWallet(PublicAddress wallet) {
            if (hasWallet(wallet)) {
                throw new LedgerException("Wallet already exists");
            }
            setValue(wallet, 0L);
        }

        @Override
        public TransactionAmount getWalletValue(PublicAddress wallet) {
            byte[] value = rawValue(wallet);
            if (value == null) {
                throw new LedgerException("Tried fetching wallet value for non-existent wallet");
            }
            return new TransactionAmount(checkedLong(value));
        }

        @Override
        public long balanceOrZero(PublicAddress wallet) {
            // Single point-get instead of the probe+get pair of the default (audit perf).
            byte[] value = rawValue(wallet);
            return value == null ? 0L : checkedLong(value);
        }

        @Override
        public void withdraw(PublicAddress wallet, TransactionAmount amt) {
            subtract(wallet, amt, "Insufficient funds for withdrawal");
        }

        @Override
        public void revertSend(PublicAddress wallet, TransactionAmount amt) {
            add(wallet, amt);
        }

        @Override
        public void deposit(PublicAddress wallet, TransactionAmount amt) {
            add(wallet, amt);
        }

        @Override
        public void revertDeposit(PublicAddress wallet, TransactionAmount amt) {
            subtract(wallet, amt, "Cannot revert deposit below zero");
        }

        private void add(PublicAddress wallet, TransactionAmount amt) {
            long current = getWalletValue(wallet).amount();
            long next;
            try {
                next = Math.addExact(current, amt.amount());
            } catch (ArithmeticException e) {
                throw new LedgerException("Overflow detected during balance adjustment", e);
            }
            setValue(wallet, next);
        }

        private void subtract(PublicAddress wallet, TransactionAmount amt, String message) {
            long next = getWalletValue(wallet).amount() - amt.amount();
            if (next < 0) {
                throw new LedgerException(message);
            }
            setValue(wallet, next);
        }

        private byte[] rawValue(PublicAddress wallet) {
            // Read-your-writes: within an open block commit, a wallet already touched this block is
            // read back from the staging overlay, not the not-yet-flushed column family (audit S3).
            var pending = pendingLedger;
            if (pending != null) {
                Long staged = pending.get(wallet);
                if (staged != null) {
                    return ByteBuffer.allocate(8).putLong(staged).array();
                }
            }
            return raw(ledgerCf, wallet.toBytes());
        }

        private void setValue(PublicAddress wallet, long amount) {
            // Inside a block commit, buffer the write so it flushes atomically with the height in
            // append/pop; otherwise (genesis/snapshot seeding) write straight through (audit S3) —
            // durably: every write path this type exposes commits with fsync (audit: durability in
            // the type).
            var pending = pendingLedger;
            if (pending != null) {
                pending.put(wallet, amount);
                return;
            }
            put(ledgerCf, wallet.toBytes(), ByteBuffer.allocate(8).putLong(amount).array());
        }

        @Override
        public void forEachBalance(java.util.function.ObjLongConsumer<PublicAddress> consumer) {
            try (org.rocksdb.RocksIterator it = db.newIterator(ledgerCf)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    consumer.accept(PublicAddress.of(it.key()), checkedLong(it.value()));
                }
            }
        }

        @Override
        public void applyBlock(long height, java.util.List<rhizome.core.ledger.LedgerOp> ops) {
            // Refuse a double-apply: re-applying a block would journal its own already-mutated
            // state as the "prior", so a later revert would restore the wrong values (audit F10)
            // — the same guard the box/token/contract stores enforce, checked against both the
            // durable copy and the journal staged for the open block commit.
            var pending = pendingLedgerJournal;
            if (raw(ledgerJournalCf, longToBytes(height)) != null
                    || (pending != null && pending.containsKey(height))) {
                throw new IllegalStateException("ledger already has a journal at height " + height);
            }
            if (ops.isEmpty()) {
                return; // no journal to keep — same empty-journal rule as the box/token/contract stores
            }
            byte[] encoded = LedgerJournalCodec.encode(ops);
            if (pending != null) {
                // Inside a block commit: stage so the journal flushes atomically with the block,
                // the height and the ledger writes themselves (audit: one undo protocol).
                pending.put(height, encoded);
                return;
            }
            put(ledgerJournalCf, longToBytes(height), encoded);
        }

        @Override
        public boolean revertBlock(long height) {
            byte[] encoded = raw(ledgerJournalCf, longToBytes(height));
            if (encoded == null) {
                return false;
            }
            java.util.List<rhizome.core.ledger.LedgerOp> journal = LedgerJournalCodec.decode(encoded);
            for (int i = journal.size() - 1; i >= 0; i--) {
                // Replay through the store's own checked inverses — the exact undo the in-memory
                // ledger runs, not a second arithmetic to keep in sync. A journal naming an
                // absent wallet or one that over/underflows is corrupt and must throw here,
                // never be written back as a balance the consensus path then reads.
                journal.get(i).revert(this);
            }
            delete(ledgerJournalCf, longToBytes(height));
            return true;
        }

        @Override
        public void pruneJournals(long minHeight) {
            try {
                // Synced, consistent with every other delete in this store (audit: prune durability).
                db.deleteRange(ledgerJournalCf, writeOptions, longToBytes(0), longToBytes(minHeight));
            } catch (RocksDBException e) {
                throw new LedgerException("failed to prune ledger journals", e);
            }
        }
    }

    // ---- NonceStore view (next expected account nonce per sender) ----

    private final class RocksNonceStore implements rhizome.core.blockchain.NonceStore {

        @Override
        public long next(PublicAddress sender) {
            // Read-your-writes: nonces committed earlier in this open block commit are read from
            // the staging overlay, not the not-yet-flushed column family (same rule as the ledger).
            var pending = pendingNonces;
            if (pending != null) {
                Long staged = pending.get(sender);
                if (staged != null) {
                    return Math.max(0L, staged);
                }
            }
            byte[] value = raw(noncesCf, sender.toBytes());
            return value == null ? 0L : checkedLong(value);
        }

        @Override
        public void set(PublicAddress sender, long next) {
            // Inside a block commit, buffer the write so it flushes atomically with the height in
            // append/pop (audit perf: per-sender fsync); otherwise write straight through — durably
            // (bulk seeding / boot re-sync: every write path this type exposes commits with fsync,
            // audit: durability in the type).
            var pending = pendingNonces;
            if (pending != null) {
                pending.put(sender, next);
                return;
            }
            if (next <= 0) {
                delete(noncesCf, sender.toBytes());
            } else {
                put(noncesCf, sender.toBytes(), longToBytes(next));
            }
        }

        @Override
        public long syncedThroughHeight() {
            byte[] value = raw(metaCf, NONCE_HEIGHT_KEY);
            return value == null ? 0 : checkedLong(value);
        }

        @Override
        public void markSyncedThrough(long height) {
            // Staged with the open block commit so the watermark flushes atomically with the
            // nonce writes it describes; straight through otherwise (boot re-sync path).
            if (pendingNonces != null) {
                pendingNonceHeight = height;
                return;
            }
            put(metaCf, NONCE_HEIGHT_KEY, longToBytes(height));
        }

        @Override
        public void forEach(java.util.function.ObjLongConsumer<PublicAddress> consumer) {
            try (org.rocksdb.RocksIterator it = db.newIterator(noncesCf)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    consumer.accept(PublicAddress.of(it.key()), checkedLong(it.value()));
                }
            }
        }
    }

    /**
     * Reads a fixed-width 8-byte big-endian value written by this store (heights, balances,
     * nonces, works). A short record means store corruption — fail with a diagnosable
     * exception instead of the raw BufferUnderflowException the ByteBuffer decode would throw
     * (audit: fixed-width decode).
     */
    private long checkedLong(byte[] value) {
        if (value.length != Long.BYTES) {
            throw new PersistenceException("corrupt node store: expected 8-byte value, got "
                + value.length);
        }
        return rhizome.core.common.Utils.bytesToLong(value);
    }
}
