package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenStore;
import rhizome.persistence.rocksdb.RocksDbTokenStore;

class RocksDbTokenStoreTest {

    private static TokenMeta meta(PublicAddress minter, long nonce, long supply) {
        return new TokenMeta(TokenMeta.deriveId(minter, nonce), minter, "T" + nonce, "Token " + nonce, 2, supply, 1);
    }

    @Test
    void persistsMetaAndBalanceAcrossReopen(@TempDir Path dir) throws Exception {
        PublicAddress minter = PublicAddress.random();
        PublicAddress holder = PublicAddress.random();
        TokenMeta m = meta(minter, 0, 1_000);
        try (var store = new RocksDbTokenStore(dir.toString())) {
            store.applyBlock(2, List.of(
                new TokenStore.TokenOp.MetaSet(m),
                new TokenStore.TokenOp.BalanceSet(m.id(), holder.toBytes(), 1_000)));
            assertEquals(m, store.getMeta(m.id()));
            assertEquals(1_000, store.getBalance(m.id(), holder.toBytes()));
            assertEquals(0, store.getBalance(m.id(), PublicAddress.random().toBytes()));
        }
        try (var store = new RocksDbTokenStore(dir.toString())) {
            assertEquals(m, store.getMeta(m.id())); // survived on disk
            assertEquals(1_000, store.getBalance(m.id(), holder.toBytes()));
        }
    }

    @Test
    void minterAndHolderIndexes(@TempDir Path dir) throws Exception {
        PublicAddress minter = PublicAddress.random();
        PublicAddress holder = PublicAddress.random();
        TokenMeta a = meta(minter, 0, 1_000);
        TokenMeta b = meta(minter, 1, 500);
        try (var store = new RocksDbTokenStore(dir.toString())) {
            store.applyBlock(2, List.of(
                new TokenStore.TokenOp.MetaSet(a),
                new TokenStore.TokenOp.MetaSet(b),
                new TokenStore.TokenOp.BalanceSet(a.id(), holder.toBytes(), 1_000)));
            assertEquals(2, store.tokenIdsByMinter(minter.toBytes(), null, 10).size());
            assertEquals(1, store.tokenIdsByHolder(holder.toBytes(), null, 10).size());
            // Emptying the balance drops the holder index entry.
            store.applyBlock(3, List.of(new TokenStore.TokenOp.BalanceSet(a.id(), holder.toBytes(), 0)));
            assertTrue(store.tokenIdsByHolder(holder.toBytes(), null, 10).isEmpty());
        }
    }

    @Test
    void revertRestoresBalancesAndMeta(@TempDir Path dir) throws Exception {
        PublicAddress minter = PublicAddress.random();
        PublicAddress holder = PublicAddress.random();
        TokenMeta m = meta(minter, 0, 1_000);
        try (var store = new RocksDbTokenStore(dir.toString())) {
            store.applyBlock(2, List.of(
                new TokenStore.TokenOp.MetaSet(m),
                new TokenStore.TokenOp.BalanceSet(m.id(), minter.toBytes(), 1_000)));
            // Block 3: transfer 400 to holder and reduce supply via a re-set meta (burn-like).
            store.applyBlock(3, List.of(
                new TokenStore.TokenOp.BalanceSet(m.id(), minter.toBytes(), 600),
                new TokenStore.TokenOp.BalanceSet(m.id(), holder.toBytes(), 400)));
            assertEquals(400, store.getBalance(m.id(), holder.toBytes()));

            store.revertBlock(3);
            assertEquals(1_000, store.getBalance(m.id(), minter.toBytes()));
            assertEquals(0, store.getBalance(m.id(), holder.toBytes()));
            assertTrue(store.tokenIdsByHolder(holder.toBytes(), null, 10).isEmpty());

            store.revertBlock(2);
            assertNull(store.getMeta(m.id())); // mint undone
            assertTrue(store.tokenIdsByMinter(minter.toBytes(), null, 10).isEmpty());
        }
    }

    @Test
    void pruneJournalsBlocksLaterRevert(@TempDir Path dir) throws Exception {
        PublicAddress minter = PublicAddress.random();
        TokenMeta m = meta(minter, 0, 1_000);
        try (var store = new RocksDbTokenStore(dir.toString())) {
            store.applyBlock(2, List.of(new TokenStore.TokenOp.MetaSet(m)));
            store.pruneJournals(3);
            store.revertBlock(2); // journal gone -> no-op
            assertNotNull(store.getMeta(m.id()));
        }
    }
}
