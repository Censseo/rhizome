package rhizome.persistence.rocksdb;

import java.util.ArrayList;
import java.util.List;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rhizome.core.ledger.PublicAddress;
import rhizome.vm.ContractJournalCodec;
import rhizome.vm.ContractStore;
import rhizome.vm.ContractUndo;
import rhizome.vm.StorageChange;

/**
 * RocksDB-backed {@link ContractStore}: contract code in one column family, all
 * per-contract storage in another (keyed by {@code address || key}). Native store
 * for the full node; the in-memory store remains the light/test path.
 */
public final class RocksDbContractStore extends RocksDbStore implements ContractStore {

    private static final Logger log = LoggerFactory.getLogger(RocksDbContractStore.class);

    private static final byte[] CF_CODE = "contract_code".getBytes();
    private static final byte[] CF_STORAGE = "contract_storage".getBytes();
    // Persisted per-block undo journal (height BE(8) -> serialized journal), so a reorg after a
    // restart can be reversed exactly instead of relying only on the processor's RAM journals.
    private static final byte[] CF_JOURNAL = "contract_journal".getBytes();
    // Persisted per-block contract receipts (height BE(8) -> encoded receipts), so the executor's
    // rollback can reverse a block's contract-tx ledger effects even after a restart (audit F3).
    private static final byte[] CF_RECEIPTS = "contract_receipts".getBytes();

    private final ColumnFamilyHandle codeCf;
    private final ColumnFamilyHandle storageCf;
    private final ColumnFamilyHandle journalCf;
    private final ColumnFamilyHandle receiptsCf;

    public RocksDbContractStore(String path) throws java.io.IOException {
        super(path, "contract store", CF_CODE, CF_STORAGE, CF_JOURNAL, CF_RECEIPTS);
        this.codeCf = handles.get(1);
        this.storageCf = handles.get(2);
        this.journalCf = handles.get(3);
        this.receiptsCf = handles.get(4);
    }

    @Override
    public void putJournal(long height, byte[] serialized) {
        put(journalCf, heightKey(height), serialized);
    }

    @Override
    public byte[] getJournal(long height) {
        return raw(journalCf, heightKey(height));
    }

    @Override
    public void deleteJournal(long height) {
        delete(journalCf, heightKey(height));
    }

    @Override
    public void putReceipts(long height, byte[] encoded) {
        put(receiptsCf, heightKey(height), encoded);
    }

    @Override
    public byte[] getReceipts(long height) {
        return raw(receiptsCf, heightKey(height));
    }

    @Override
    public void deleteReceipts(long height) {
        delete(receiptsCf, heightKey(height));
    }

    @Override
    public void pruneThrough(long maxHeight) {
        // Interval prune (deleteRange's end key is EXCLUSIVE, hence maxHeight + 1): rows left
        // by heights the processor no longer has in its RAM maps — everything committed before
        // a restart — would otherwise accumulate on disk forever. Synced, consistent with every
        // other delete in this store (audit: prune durability).
        try {
            byte[] end = heightKey(maxHeight + 1);
            db.deleteRange(journalCf, writeOptions, heightKey(0), end);
            db.deleteRange(receiptsCf, writeOptions, heightKey(0), end);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store pruneThrough failed", e);
        }
    }

    private static byte[] slot(PublicAddress contract, byte[] key) {
        // record-accessor read: the internal address array is copied straight into the composite,
        // never retained or mutated — avoiding toBytes()'s defensive 25-byte clone on this
        // per-storage-op hot path is provably alias-safe here (audit perf).
        byte[] addr = contract.address();
        byte[] out = new byte[addr.length + key.length];
        System.arraycopy(addr, 0, out, 0, addr.length);
        System.arraycopy(key, 0, out, addr.length, key.length);
        return out;
    }

    @Override
    public byte[] getCode(PublicAddress contract) {
        return raw(codeCf, contract.toBytes());
    }

    // Code/storage slot writes are SYNCED straight-through puts: every write path this type
    // exposes commits durably (audit: durability in the type), so a caller can never believe a
    // block-applied write survived when it did not. The single exception is the snapshot-import
    // window between beginBulkImport() and syncToDisk() — one fsync per seeded slot made
    // snap-sync effectively unusable, and the window is crash-guarded by the node store's
    // bootstrap marker (see RocksDbStore's bulk-path javadoc).
    @Override
    public void putCode(PublicAddress contract, byte[] code) {
        if (bulkImport) {
            putBulk(codeCf, contract.toBytes(), code);
        } else {
            put(codeCf, contract.toBytes(), code);
        }
    }

    @Override
    public void deleteCode(PublicAddress contract) {
        if (bulkImport) {
            deleteBulk(codeCf, contract.toBytes());
        } else {
            delete(codeCf, contract.toBytes());
        }
    }

    @Override
    public byte[] getStorage(PublicAddress contract, byte[] key) {
        return raw(storageCf, slot(contract, key));
    }

    @Override
    public List<byte[]> getStorageMulti(List<PublicAddress> contracts, List<byte[]> keys) {
        if (contracts.size() != keys.size()) {
            throw new IllegalArgumentException("contracts/keys length mismatch: "
                + contracts.size() + " vs " + keys.size());
        }
        // One native multi-get for the whole journal capture: the default point-read loop made
        // a K-write block cost K store round-trips at every commit (audit: journal-capture N+1).
        List<ColumnFamilyHandle> cfs = new ArrayList<>(keys.size());
        List<byte[]> composite = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            cfs.add(storageCf);
            composite.add(slot(contracts.get(i), keys.get(i)));
        }
        try {
            return db.multiGetAsList(cfs, composite);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store multi-read failed", e);
        }
    }

    @Override
    public void putStorage(PublicAddress contract, byte[] key, byte[] value) {
        if (bulkImport) {
            putBulk(storageCf, slot(contract, key), value);
        } else {
            put(storageCf, slot(contract, key), value);
        }
    }

    @Override
    public void deleteStorage(PublicAddress contract, byte[] key) {
        if (bulkImport) {
            deleteBulk(storageCf, slot(contract, key));
        } else {
            delete(storageCf, slot(contract, key));
        }
    }

    @Override
    public void applyBlock(long height, List<StorageChange> changes) {
        applyBlock(height, changes, null);
    }

    @Override
    public void applyBlock(long height, List<StorageChange> changes, byte[] encodedReceipts) {
        // All slot mutations AND the generated undo journal AND the receipts land in ONE synced
        // WriteBatch: a crash mid-flush can no longer leave storage half-applied with no journal
        // to rewind it (audit F1), and the receipts no longer cost a second fsync per block
        // (audit perf).
        if (raw(journalCf, heightKey(height)) != null) {
            // A double-apply would capture the already-mutated state as the journal's "prior" (audit F10).
            throw new IllegalStateException("contract store already has a journal at height " + height);
        }
        try (WriteBatch batch = new WriteBatch()) {
            // The store GENERATES the journal itself — each key's prior, in change order, exactly
            // like the box and token stores (audit: one undo protocol). The storage priors come
            // from ONE multi-get (see getStorageMulti) so a K-write block costs one round-trip,
            // preserving the audit perf of the old session-side capture.
            byte[] journal = encodeJournalFrom(changes);
            for (StorageChange change : changes) {
                stage(batch, change);
            }
            if (journal != null) {
                batch.put(journalCf, heightKey(height), journal);
            }
            if (encodedReceipts != null) {
                batch.put(receiptsCf, heightKey(height), encodedReceipts);
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store applyBlock failed", e);
        }
    }

    /** Captures each change's prior and encodes the undo journal (null when no change). */
    private byte[] encodeJournalFrom(List<StorageChange> changes) {
        if (changes.isEmpty()) {
            return null;
        }
        // Storage priors come from ONE multi-get (see getStorageMulti) so a K-write block costs
        // one round-trip; code priors are point reads (code changes are rare and the multi-get
        // would need a parallel key list for a null key).
        List<PublicAddress> storageContracts = new ArrayList<>();
        List<byte[]> storageKeys = new ArrayList<>();
        for (StorageChange change : changes) {
            if (!change.isCode()) {
                storageContracts.add(change.contract());
                storageKeys.add(change.key());
            }
        }
        List<byte[]> priors = storageContracts.isEmpty() ? List.of()
            : getStorageMulti(storageContracts, storageKeys);
        List<ContractUndo> journal = new ArrayList<>(changes.size());
        int storageIndex = 0;
        for (StorageChange change : changes) {
            byte[] prior = change.isCode()
                ? raw(codeCf, change.contract().toBytes())
                : priors.get(storageIndex++);
            journal.add(new ContractUndo(change.isCode(), change.contract(),
                change.isCode() ? null : change.key(), prior));
        }
        return ContractJournalCodec.encode(journal);
    }

    @Override
    public void revertBlock(long height) {
        // The restores (decoded from the store's OWN journal — see ContractStore.revertBlock),
        // the journal drop AND the receipts drop commit as one atomic unit (audit F1). The
        // receipts must ride this batch: deleted separately and first, a crash between the two
        // writes left the journal present but the receipts gone, and the rollback guard then
        // aborted every reorg retry — the node wedged on its fork.
        byte[] journal = raw(journalCf, heightKey(height));
        byte[] encodedReceipts = raw(receiptsCf, heightKey(height));
        if (journal == null && encodedReceipts == null) {
            // Nothing committed at this height: strict no-op, no empty synced batch. Boot
            // recovery replays reverts over heights that may have nothing (audit: empty reverts).
            return;
        }
        try (WriteBatch batch = new WriteBatch()) {
            if (journal != null) {
                for (StorageChange restore : ContractJournalCodec.restores(journal)) {
                    stage(batch, restore);
                }
            }
            batch.delete(journalCf, heightKey(height));
            batch.delete(receiptsCf, heightKey(height));
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("contract store revertBlock failed", e);
        }
    }

    /** Adds one mutation (set, or delete when the value is null) to {@code batch}. */
    private void stage(WriteBatch batch, StorageChange change) throws RocksDBException {
        if (change.isCode()) {
            if (change.value() == null) {
                batch.delete(codeCf, change.contract().toBytes());
            } else {
                batch.put(codeCf, change.contract().toBytes(), change.value());
            }
        } else {
            byte[] slot = slot(change.contract(), change.key());
            if (change.value() == null) {
                batch.delete(storageCf, slot);
            } else {
                batch.put(storageCf, slot, change.value());
            }
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
        // Storage keys are contract address(25) ‖ key. This export feeds the state root peers
        // download, so a truncated key must fail LOUD here — zero-padding it would fabricate a
        // state entry that never existed (audit: storage-key decode).
        try (org.rocksdb.RocksIterator it = db.newIterator(storageCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] slot = it.key();
                if (slot.length < PublicAddress.SIZE) {
                    throw new IllegalStateException("corrupt contract storage key: " + slot.length
                        + " bytes, expected at least " + PublicAddress.SIZE);
                }
                consumer.accept(PublicAddress.of(java.util.Arrays.copyOfRange(slot, 0, PublicAddress.SIZE)),
                    java.util.Arrays.copyOfRange(slot, PublicAddress.SIZE, slot.length), it.value());
            }
        }
    }

    /**
     * While {@code true}, the straight-through code/storage writes go unsynced (WAL-sync
     * throttled): the snapshot-import window opened by {@link #beginBulkImport()} and closed by
     * {@link #syncToDisk()}'s barrier. False everywhere else — every block-apply write path
     * stays synced.
     */
    private volatile boolean bulkImport;

    @Override
    public void beginBulkImport() {
        bulkImport = true;
    }

    @Override
    public void syncToDisk() {
        // The barrier CLOSES the bulk-import window: fsync every seeded write not yet covered
        // by the throttle, so the bootstrap marker can clear with the whole seed durable.
        bulkImport = false;
        syncWal();
    }

    @Override
    public void close() {
        // Best-effort fsync of any bulk-seeded writes not yet covered by a synced batch. A
        // failure here must NOT abort close(): leaking native CF/DB handles on the shutdown
        // path is worse than a best-effort fsync lost on a store that is about to be closed.
        try {
            syncToDisk();
        } catch (RuntimeException e) {
            log.warn("contract store WAL sync on close failed; closing anyway", e);
        }
        super.close();
    }
}
