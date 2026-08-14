package rhizome.core.token;

import java.util.List;

/**
 * Persistent home of native tokens: per-token metadata (including current supply) and
 * per-(token, address) balances, plus a per-block undo journal for exact reorg reversal.
 * Implemented in-memory and on RocksDB; the consensus core depends only on this interface.
 *
 * <p>Identifiers are typed ({@link TokenId}) and the balance key is the typed composite
 * {@link TokenBalanceKey}, whose byte layout is {@link
 * rhizome.core.state.StateKeys#tokenBalanceKey}'s — the single committed encoding.
 */
public interface TokenStore {

    /** Metadata for {@code tokenId}, or {@code null} if no such token. */
    TokenMeta getMeta(TokenId tokenId);

    /** Balance of {@code key.tokenId()} held by {@code key.address()} (0 if none). */
    long getBalance(TokenBalanceKey key);

    /**
     * Applies one block's token changes atomically and records an undo journal for {@code height}.
     *
     * <p>A height that already carries a NON-EMPTY journal MUST be refused with
     * {@link IllegalStateException}: a double-apply would journal the already-mutated state as
     * the "prior", so a later {@link #revertBlock} would restore the wrong values (audit F10).
     * An apply whose {@code ops} is empty persists no journal (there is nothing to undo), so it
     * does not itself trigger this refusal on a later call at the same height.
     */
    void applyBlock(long height, List<TokenOp> ops);

    /** Reverts the token changes committed for {@code height} using the persisted journal. */
    void revertBlock(long height);

    /** Drops journals for heights strictly below {@code minHeight}. */
    void pruneJournals(long minHeight);

    /** Token ids minted by {@code minter}, paginated after {@code afterId} (null = start). */
    List<TokenId> tokenIdsByMinter(byte[] minter, TokenId afterId, int limit);

    /** Token ids {@code address} holds a positive balance of, paginated after {@code afterId}. */
    List<TokenId> tokenIdsByHolder(byte[] address, TokenId afterId, int limit);

    /**
     * Visits every token's metadata — the state-snapshot export path. Optional: stores that
     * never serve snapshots may leave the unsupported default.
     */
    default void forEachMeta(java.util.function.Consumer<TokenMeta> consumer) {
        throw new UnsupportedOperationException("this token store does not support enumeration");
    }

    /** Visits every stored balance — the snapshot export path. */
    default void forEachBalance(BalanceConsumer consumer) {
        throw new UnsupportedOperationException("this token store does not support enumeration");
    }

    @FunctionalInterface
    interface BalanceConsumer {
        void accept(TokenBalanceKey key, long amount);
    }

    /** One token change in a block. */
    sealed interface TokenOp {
        /** Writes token metadata (mint, or a supply update on burn). */
        record MetaSet(TokenMeta meta) implements TokenOp {}

        /** Sets a holder's balance to {@code amount} (0 clears it). */
        record BalanceSet(TokenBalanceKey key, long amount) implements TokenOp {}
    }
}
