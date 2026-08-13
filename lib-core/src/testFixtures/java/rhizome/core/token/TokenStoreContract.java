package rhizome.core.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.ledger.PublicAddress;

/**
 * The behaviour every {@link TokenStore} owes its callers, run against each implementation.
 *
 * <p>Includes the double-apply refusal (audit F10): {@link InMemoryTokenStore} used to overwrite
 * the journal silently where RocksDB threw — see {@link rhizome.core.box.BoxStoreContract} for
 * the same fix on the box side. Both now refuse alike.
 */
public interface TokenStoreContract {

    /**
     * A fresh, empty store. Called at most once per test method; the implementor owns the
     * lifecycle (a durable backend opens under its own {@code @TempDir} field and closes in
     * an {@code @AfterEach}).
     */
    TokenStore newTokenStore() throws Exception;

    private static TokenMeta meta(PublicAddress minter, long nonce, long supply) {
        return new TokenMeta(TokenMeta.deriveId(minter, nonce), minter, "T" + nonce, "Token " + nonce, 2, supply, 1);
    }

    @Test
    default void minterAndHolderIndexes() throws Exception {
        TokenStore store = newTokenStore();
        PublicAddress minter = PublicAddress.random();
        PublicAddress holder = PublicAddress.random();
        TokenMeta a = meta(minter, 0, 1_000);
        TokenMeta b = meta(minter, 1, 500);
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

    @Test
    default void revertRestoresBalancesAndMeta() throws Exception {
        TokenStore store = newTokenStore();
        PublicAddress minter = PublicAddress.random();
        PublicAddress holder = PublicAddress.random();
        TokenMeta m = meta(minter, 0, 1_000);
        store.applyBlock(2, List.of(
            new TokenStore.TokenOp.MetaSet(m),
            new TokenStore.TokenOp.BalanceSet(m.id(), minter.toBytes(), 1_000)));
        // Block 3: transfer 400 to holder.
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

    @Test
    default void pruneJournalsBlocksLaterRevert() throws Exception {
        TokenStore store = newTokenStore();
        PublicAddress minter = PublicAddress.random();
        TokenMeta m = meta(minter, 0, 1_000);
        store.applyBlock(2, List.of(new TokenStore.TokenOp.MetaSet(m)));
        store.pruneJournals(3);
        store.revertBlock(2); // journal gone -> no-op
        assertNotNull(store.getMeta(m.id()));
    }

    @Test
    default void applyBlockRefusesADoubleApply() throws Exception {
        TokenStore store = newTokenStore();
        PublicAddress minter = PublicAddress.random();
        TokenMeta m = meta(minter, 0, 1_000);
        store.applyBlock(2, List.of(new TokenStore.TokenOp.MetaSet(m)));
        // A second apply at the same height would journal the already-mutated state as the
        // "prior", corrupting any later revert — it must be refused (audit F10).
        List<TokenStore.TokenOp> repeat = List.of(new TokenStore.TokenOp.MetaSet(m));
        assertThrows(IllegalStateException.class, () -> store.applyBlock(2, repeat));
        // ...and the first commit is untouched.
        assertEquals(m, store.getMeta(m.id()));
    }

    @Test
    default void aMutationLessApplyRecordsNoJournalAndStaysReAppliable() throws Exception {
        // A block that touches no token has nothing to undo, so it must not persist an (empty)
        // journal — otherwise a legitimately mutation-less block would falsely trip the
        // double-apply guard the moment it, or a later empty apply at the same height, ran again.
        TokenStore store = newTokenStore();
        store.applyBlock(5, List.of());
        store.applyBlock(5, List.of()); // must not throw
        PublicAddress minter = PublicAddress.random();
        TokenMeta m = meta(minter, 0, 1_000);
        // A later real apply at that same height is still accepted — no phantom journal blocks it.
        store.applyBlock(5, List.of(new TokenStore.TokenOp.MetaSet(m)));
        assertEquals(m, store.getMeta(m.id()));
    }
}
