package rhizome.core.token;

import java.util.List;

/**
 * Persistent home of native tokens: per-token metadata (including current supply) and
 * per-(token, address) balances, plus a per-block undo journal for exact reorg reversal.
 * Implemented in-memory and on RocksDB; the consensus core depends only on this interface.
 */
public interface TokenStore {

    /** Metadata for {@code tokenId}, or {@code null} if no such token. */
    TokenMeta getMeta(byte[] tokenId);

    /** Balance of {@code tokenId} held by {@code address} (0 if none). */
    long getBalance(byte[] tokenId, byte[] address);

    /** Applies one block's token changes atomically and records an undo journal for {@code height}. */
    void applyBlock(long height, List<TokenOp> ops);

    /** Reverts the token changes committed for {@code height} using the persisted journal. */
    void revertBlock(long height);

    /** Drops journals for heights strictly below {@code minHeight}. */
    void pruneJournals(long minHeight);

    /** Token ids minted by {@code minter}, paginated after {@code afterId} (null = start). */
    List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit);

    /** Token ids {@code address} holds a positive balance of, paginated after {@code afterId}. */
    List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit);

    /** One token change in a block. */
    sealed interface TokenOp {
        /** Writes token metadata (mint, or a supply update on burn). */
        record MetaSet(TokenMeta meta) implements TokenOp {}

        /** Sets a holder's balance to {@code amount} (0 clears it). */
        record BalanceSet(byte[] tokenId, byte[] address, long amount) implements TokenOp {}
    }
}
