package rhizome.core.token;

import java.util.List;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

/**
 * The token domain on a node that has no token store wired: {@link TokenProcessor#NONE}.
 *
 * <p>Stands in for a null processor so the engine carries no null checks. Absent is not
 * permissive — {@code Executor}'s first pass still rejects a token transaction with
 * {@code TOKEN_UNAVAILABLE} by testing {@link #available()}. Every read returns exactly what the
 * null guard it replaces returned.
 *
 * <p>Stateless by construction, so the singleton is GraalVM build-time-init safe.
 */
final class AbsentTokenProcessor implements TokenProcessor {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void begin() {
        // no session to open
    }

    @Override
    public void commit(long blockHeight) {
        // no state to persist
    }

    @Override
    public void discard() {
        // no session to drop
    }

    @Override
    public void revertBlock(long blockHeight) {
        // nothing was ever committed for this height
    }

    @Override
    public TokenResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                           long nonce, byte[] data, long height) {
        throw new IllegalStateException(
            "no token processor is wired: a token transaction must have been rejected with "
            + "TOKEN_UNAVAILABLE in Executor's first pass and cannot reach the second");
    }

    @Override
    public TokenMeta meta(TokenId tokenId) {
        return null;
    }

    @Override
    public long balance(TokenBalanceKey key) {
        return 0L;
    }

    @Override
    public List<TokenId> tokenIdsByMinter(byte[] minter, TokenId afterId, int limit) {
        return List.of();
    }

    @Override
    public List<TokenId> tokenIdsByHolder(byte[] address, TokenId afterId, int limit) {
        return List.of();
    }
}
