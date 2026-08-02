package rhizome.persistence.leveldb;

import org.iq80.leveldb.DBException;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;

import io.activej.bytebuf.ByteBuf;
import rhizome.core.block.Block;
import rhizome.core.block.dto.BlockDto;
import rhizome.crypto.SHA256Hash;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.serialization.BinarySerializable;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.dto.TransactionDto;
import rhizome.persistence.BlockPersistence;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LevelDBBlockPersistence extends LevelDBDataStore implements BlockPersistence {

    static final String BLOCK_COUNT_KEY = "BLOCK_COUNT";
    static final String TOTAL_WORK_KEY = "TOTAL_WORK";

    public LevelDBBlockPersistence(String path) throws IOException {
        super.init(path);
    }

    public void setBlockCount(long count) {
        set(BLOCK_COUNT_KEY, count);
    }

    public long getBlockCount() {
        return (long) get(BLOCK_COUNT_KEY, Long.class);
    }

    public void setTotalWork(BigInteger count) {
        set(TOTAL_WORK_KEY, count);
    }

    public BigInteger getTotalWork() {
        return (BigInteger) get(TOTAL_WORK_KEY, BigInteger.class);
    }

    public boolean hasBlockCount() {
        return hasKey(BLOCK_COUNT_KEY);        
    }

    public boolean hasBlock(int blockId) {
        return hasKey(blockId);
    }

    public BlockDto  getBlockHeader(int blockId) {       
        return BinarySerializable.fromBuffer((byte[])get(blockId, byte[].class), BlockDto.class);
    }

    public List<TransactionDto> getBlockTransactions(BlockDto block) {
        // One ordered prefix scan instead of a point-get per transaction (audit perf: N+1 gets per
        // block reconstruction). Transaction records key as blockId(4) ‖ index(4) big-endian, so a
        // block's rows sort contiguously by index; longer keys sharing the 4-byte blockId prefix
        // (wallet-index rows) are not transaction records and are skipped.
        int count = block.numTransactions();
        var transactions = new ArrayList<TransactionDto>(count);
        if (count == 0) {
            return transactions;
        }
        byte[] prefix = rhizome.core.common.Utils.intToBytes(block.id());
        TransactionDto[] byIndex = new TransactionDto[count];
        int found = 0;
        try (DBIterator iterator = db().iterator(new ReadOptions())) {
            for (iterator.seek(composeKey(block.id(), 0)); iterator.hasNext(); iterator.next()) {
                byte[] key = iterator.peekNext().getKey();
                if (key.length < prefix.length
                        || !Arrays.equals(Arrays.copyOfRange(key, 0, prefix.length), prefix)) {
                    break; // past this block's prefix
                }
                if (key.length != 2 * Integer.BYTES) {
                    continue; // a foreign record under the same prefix, not a transaction row
                }
                int index = ByteBuffer.wrap(key, Integer.BYTES, Integer.BYTES).getInt();
                if (index >= 0 && index < count) {
                    byIndex[index] = BinarySerializable.fromBuffer(
                        iterator.peekNext().getValue(), TransactionDto.class);
                    found++;
                }
            }
        } catch (IOException e) {
            throw new LevelDBException("Could not read block transactions", e);
        }
        if (found != count) {
            // The per-index point-gets this replaces threw on a missing record; fail just as loud.
            throw new LevelDBException("Block " + block.id() + " is missing transaction records ("
                + found + " of " + count + ")");
        }
        transactions.addAll(Arrays.asList(byIndex));
        return transactions;
    }

    public ByteBuf getRawData(int blockId) {
        // Emit the canonical block-codec format (header || txs || uncle section) so it
        // round-trips through fromRawData; this legacy store keeps no uncles, so the
        // reconstructed block carries none.
        return ByteBuf.wrapForReading(rhizome.core.block.BlockCodec.encode(getBlock(blockId)));
    }

    public Block fromRawData(byte[] rawData) {
        return rhizome.core.block.BlockCodec.decode(rawData);
    }

    public Block getBlock(int blockId) {
        BlockDto block = getBlockHeader(blockId);
        List<Transaction> transactions = new ArrayList<>();
        getBlockTransactions(block).forEach(transaction -> transactions.add(Transaction.of(transaction)));
        return Block.of(block, transactions);
    }

    /**
     * Hard cap on wallet-history results. The scan materialises every txid of a wallet in RAM;
     * without a bound, a wallet with a long history (or a crafted address prefix) made one API
     * call allocate unbounded memory (audit: unbounded wallet-history scan). This legacy store
     * serves light/test nodes (the full node is RocksDB), whose API consumers always page well
     * below this; use {@link #getTransactionsForWallet(PublicAddress, int)} for an explicit page.
     */
    static final int MAX_WALLET_HISTORY = 10_000;

    public List<SHA256Hash> getTransactionsForWallet(PublicAddress wallet) {
        return getTransactionsForWallet(wallet, MAX_WALLET_HISTORY);
    }

    public List<SHA256Hash> getTransactionsForWallet(PublicAddress wallet, int limit) {
        var address = wallet.toBytes();
        List<SHA256Hash> transactions = new ArrayList<>();

        try (DBIterator iterator = db().iterator(new ReadOptions())) {
            for(iterator.seek(address); iterator.hasNext() && transactions.size() < limit; iterator.next()) {
                byte[] key = iterator.peekNext().getKey();
                // Wallet-index keys sort by address prefix: once the prefix no longer matches the
                // scan is done — continuing to end-of-DB was O(DB size) per query (audit F4).
                if (key.length < address.length
                        || !Arrays.equals(Arrays.copyOfRange(key, 0, address.length), address)) {
                    break;
                }
                // Only a full wallet(25) ‖ txid(32) entry is emitted; anything shorter/longer is
                // another record under the same prefix, and slicing it would fabricate a
                // zero-padded garbage txid (audit F4).
                if (key.length != PublicAddress.SIZE + SHA256Hash.SIZE) {
                    continue;
                }
                byte[] txidBytes = Arrays.copyOfRange(key, PublicAddress.SIZE, PublicAddress.SIZE + SHA256Hash.SIZE);
                SHA256Hash txid = SHA256Hash.of(txidBytes);
                transactions.add(txid);
            }
        } catch (IOException e) {
            throw new LevelDBException("Failed to iterate over the database", e);
        }

        return transactions;
    }

    public void removeBlockWalletTransactions(Block block) {
        // All of the block's wallet-index removals commit in ONE synced batch — a crash can no
        // longer leave half of a block's index entries behind (audit F2).
        try (WriteBatch batch = db().createWriteBatch()) {
            for(Transaction t : block.transactions()) {
                SHA256Hash txid = t.hashContents();

                var w1Key = new WalletTransactionKey(t.from(), txid, false);
                var w2Key = new WalletTransactionKey(t.to(), txid, false);

                batch.delete(w1Key.toByteArray());
                batch.delete(w2Key.toByteArray());
            }
            db().write(batch, new WriteOptions().sync(true));
        } catch (DBException | IOException e) { // IOException from WriteBatch.close()
            throw new LevelDBException("Could not remove transaction from wallet in blockstore db: " + e.getMessage(), e);
        }
    }


    public void addBlock(Block block) throws LevelDBException {
        // The whole block — header, every transaction record and both wallet-index entries per
        // transaction — commits in ONE synced WriteBatch: a crash can never leave a partially
        // indexed block, and 1 + 3N individual fsyncs become one (audit F2).
        try (WriteBatch batch = db().createWriteBatch()) {
            stageBlock(batch, block);
            db().write(batch, new WriteOptions().sync(true));
        } catch (DBException | IOException e) { // IOException from WriteBatch.close()
            throw new LevelDBException("Could not add block to blockstore db: " + e.getMessage(), e);
        }
    }

    /** Stages the block record, its transaction records and the wallet-index entries. */
    private void stageBlock(WriteBatch batch, Block block) throws IOException {
        batch.put(rhizome.core.common.Utils.intToBytes(block.id()), block.serialize().toBuffer());
        for (int i = 0; i < block.transactions().size(); i++) {
            var transaction = block.transactions().get(i);
            var transactionDto = transaction.serialize();
            batch.put(composeKey(block.id(), i), transactionDto.toBuffer());
            batch.put(composeKey(PublicAddress.of(transactionDto.signingKey).toBytes(), transaction.hashContents().toBytes()), new byte[0]);
            batch.put(composeKey(transactionDto.to.toBytes(), transaction.hashContents().toBytes()), new byte[0]);
        }
    }

    private static class WalletTransactionKey {
        PublicAddress addr;
        SHA256Hash txId;
        
        public WalletTransactionKey(PublicAddress address, SHA256Hash txId, boolean isStartKey) {
            this.addr = address;
            var buf = new byte[SHA256Hash.SIZE];

            if(isStartKey) {
                Arrays.fill(buf, (byte) -128);
            } else {
                Arrays.fill(buf, (byte) 127);
            }

            if(txId != null) {
                this.txId = txId;
            } else {
                this.txId = SHA256Hash.of(buf);
            }
        }
                
        byte[] toByteArray() {
            return key(addr, txId);
        }

        static byte[] key(PublicAddress address, SHA256Hash sha256) {
            return composeKey(address.toBytes(), sha256.toBytes());
        }
    }
}
