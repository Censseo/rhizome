package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.token.TokenMeta;
import rhizome.core.token.TokenStore;
import rhizome.core.token.TokenStoreContract;
import rhizome.persistence.rocksdb.RocksDbTokenStore;

/**
 * RocksDB-specific behaviour beyond {@link TokenStoreContract}: persistence across a reopen,
 * which an in-memory store has no equivalent of — see {@code InMemoryTokenStoreTest} and {@code
 * TokenStoreContract} for the properties (including the double-apply refusal) both backends
 * share.
 */
class RocksDbTokenStoreTest implements TokenStoreContract {

    @TempDir
    Path dir;

    private RocksDbTokenStore opened;

    @Override
    public TokenStore newTokenStore() throws Exception {
        opened = new RocksDbTokenStore(dir.toString());
        return opened;
    }

    @AfterEach
    void tearDown() {
        if (opened != null) {
            opened.close();
        }
    }

    private static TokenMeta meta(PublicAddress minter, long nonce, long supply) {
        return new TokenMeta(TokenMeta.deriveId(minter, nonce), minter, "T" + nonce, "Token " + nonce, 2, supply, 1);
    }

    @Test
    void persistsMetaAndBalanceAcrossReopen() throws Exception {
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
}
