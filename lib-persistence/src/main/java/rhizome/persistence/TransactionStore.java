package rhizome.persistence;

import org.iq80.leveldb.*;
import rhizome.core.transaction.Transaction;
import rhizome.persistence.leveldb.LevelDBDataStore;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.LedgerException;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.OptionalInt;


/**
 * Legacy LevelDB transaction-id -> block-height index (txid -> 4-byte big-endian height).
 *
 * <p><b>Legacy path:</b> like {@code LevelDBLedger}, this store is test/light-node oriented: it
 * commits in its own database with no journal shared with the block store, so it has no
 * cross-store crash atomicity (audit F7). Writes are fsynced ({@code sync(true)}); the production
 * full-node path is the transaction index inside {@code RocksDbNodeStore}.
 */
public class TransactionStore extends LevelDBDataStore {

    public TransactionStore(String path) throws IOException {
        super.init(path);
    }

    public boolean hasTransaction(Transaction t) {
        SHA256Hash txHash = t.hashContents();
        byte[] key = txHash.toBytes();
        try {
            byte[] value = db().get(key);
            return value != null;
        } catch (DBException e) {
            throw new LedgerException("Failed to check transaction existence", e);
        }
    }

    /**
     * The height of the block containing {@code t}, or empty if the transaction is unknown.
     * (Previously returned 0 for "not found", which was indistinguishable from a real height —
     * audit F13.)
     */
    public OptionalInt blockForTransaction(Transaction t) {
        SHA256Hash txHash = t.hashContents();
        return blockForTransactionId(txHash);
    }

    /**
     * The height of the block containing the transaction with id {@code txHash}, or empty if
     * unknown (audit F13).
     */
    public OptionalInt blockForTransactionId(SHA256Hash txHash) {
        byte[] key = txHash.toBytes();
        try {
            byte[] value = db().get(key);
            if (value == null) {
                return OptionalInt.empty();
            }
            ByteBuffer buffer = ByteBuffer.wrap(value);
            return OptionalInt.of(buffer.getInt());
        } catch (DBException e) {
            throw new LedgerException("Could not find block for specified transaction ID", e);
        }
    }

    public void insertTransaction(Transaction t, int blockId) {
        SHA256Hash txHash = t.hashContents();
        byte[] key = txHash.toBytes();
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
        buffer.putInt(blockId);
        try {
            // fsync the index write (audit F7); cross-store atomicity is NOT provided — see the
            // class-level note.
            db().put(key, buffer.array(), new WriteOptions().sync(true));
        } catch (DBException e) {
            throw new LedgerException("Could not write transaction to DB", e);
        }
    }

    public void removeTransaction(Transaction t) {
        SHA256Hash txHash = t.hashContents();
        byte[] key = txHash.toBytes();
        try {
            // fsync like insertTransaction (audit F7): an unsynced delete could resurrect an
            // index entry after a crash, pointing at a height the node no longer holds.
            db().delete(key, new WriteOptions().sync(true));
        } catch (DBException e) {
            throw new LedgerException("Could not remove transaction from DB", e);
        }
    }
}