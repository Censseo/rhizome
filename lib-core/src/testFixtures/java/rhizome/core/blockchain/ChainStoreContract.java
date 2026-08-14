package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rhizome.crypto.Crypto.generateKeyPairTyped;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PublicKey;
import rhizome.crypto.SHA256Hash;

/**
 * The behaviour every {@link ChainStore} owes its callers — append/pop and the transaction index
 * they maintain, plus the uncle-body round trip — run against each implementation.
 *
 * <p>Deliberately narrow: RocksDbNodeStore's header backfill, pruning and legacy-migration
 * behaviour have no in-memory equivalent (an in-memory store never persists, never prunes), so
 * those stay {@code RocksDbNodeStoreTest}-specific. Blocks here are structurally valid but
 * unmined and unvalidated — {@link ChainStore#append} itself only requires
 * {@code block.id() == height() + 1}; full chain validation is {@code ChainEngine}'s job, not
 * the store's.
 */
public interface ChainStoreContract {

    /** A fresh, empty store. */
    ChainStore newChainStore() throws Exception;

    private static Block looseBlock(int id, SHA256Hash parent, List<Transaction> txs) {
        var b = (BlockImpl) BlockImpl.builder().id(id).timestamp(1_000_000L + id).difficulty(4)
            .lastBlockHash(parent).build();
        txs.forEach(b::addTransaction);
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(SHA256Hash.random());
        return b;
    }

    @Test
    default void appendAdvancesHeightAndBlockAtRetrieves() throws Exception {
        ChainStore store = newChainStore();
        assertEquals(0, store.height());

        Block b1 = looseBlock(1, SHA256Hash.random(), List.of());
        store.append(b1);
        assertEquals(1, store.height());
        assertEquals(b1.hash(), store.blockAt(1).hash());

        Block b2 = looseBlock(2, b1.hash(), List.of());
        store.append(b2);
        assertEquals(2, store.height());
        assertEquals(b2.hash(), store.blockAt(2).hash());
    }

    @Test
    default void popRemovesTipAndDeindexesTransactions() throws Exception {
        ChainStore store = newChainStore();
        var pair = generateKeyPairTyped();
        PublicKey key = pair.publicKey();
        PublicAddress sender = PublicAddress.of(key);
        Transaction tx = Transaction.of(sender, PublicAddress.random(), new TransactionAmount(1), key);

        Block b1 = looseBlock(1, SHA256Hash.random(), List.of(tx));
        store.append(b1);
        assertTrue(store.hasTransaction(tx.hashContents()));
        assertEquals(1L, store.transactionHeight(tx.hashContents()));

        Block b2 = looseBlock(2, b1.hash(), List.of());
        store.append(b2);
        assertEquals(2, store.height());

        store.pop();
        assertEquals(1, store.height());
        assertEquals(b1.hash(), store.blockAt(1).hash());

        store.pop();
        assertEquals(0, store.height());
        assertFalse(store.hasTransaction(tx.hashContents()), "popping the block that carried it de-indexes it");
        assertNull(store.transactionHeight(tx.hashContents()));
    }

    @Test
    default void uncleBodyRoundTrips() throws Exception {
        ChainStore store = newChainStore();
        Block uncle = looseBlock(1, SHA256Hash.random(), List.of());
        assertNull(store.uncleAt(uncle.hash()), "an unregistered hash has no uncle body");

        store.putUncle(uncle.hash(), uncle);
        Block back = store.uncleAt(uncle.hash());
        assertEquals(uncle.hash(), back.hash());
        assertNull(store.uncleAt(SHA256Hash.random()), "an unrelated hash still has no uncle body");
    }
}
