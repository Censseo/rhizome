package rhizome.core.token;

import java.util.List;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.TransactionKind;

/**
 * Runs native-token transactions (MINT/TRANSFER/BURN) for the
 * {@link rhizome.core.blockchain.Executor}. Token balances live in the token store, not
 * the native ledger, so a token op moves no PDN — only the transaction fee does, which
 * the executor applies. Token state is staged in a per-block session that commits
 * atomically with the block, exactly like boxes and contracts.
 */
public interface TokenProcessor {

    void begin();

    /** Validates and applies one token transaction against the open session (no ledger effect). */
    TokenResult run(TransactionKind kind, PublicAddress from, PublicAddress to,
                    long nonce, byte[] data, long height);

    void commit(long blockHeight);

    void discard();

    void revertBlock(long blockHeight);

    /** Token lifecycle events emitted by {@code blockHeight}'s transactions (agent event feed). */
    default List<TokenEvent> events(long blockHeight) {
        return List.of();
    }

    /** Committed metadata for {@code tokenId}, or {@code null}. */
    TokenMeta meta(byte[] tokenId);

    /** Committed balance of {@code tokenId} held by {@code address}. */
    long balance(byte[] tokenId, byte[] address);

    /** Token ids minted by {@code minter}, paginated after {@code afterId}. */
    List<byte[]> tokenIdsByMinter(byte[] minter, byte[] afterId, int limit);

    /** Token ids {@code address} holds, paginated after {@code afterId}. */
    List<byte[]> tokenIdsByHolder(byte[] address, byte[] afterId, int limit);

    /** Outcome of one token op: a status and the affected token id. */
    record TokenResult(ExecutionStatus status, byte[] tokenId) {
        public boolean success() {
            return status == ExecutionStatus.SUCCESS;
        }

        public static TokenResult fail(ExecutionStatus status) {
            return new TokenResult(status, null);
        }
    }

    /** A token lifecycle event: the acting account, an event type, and the token id. */
    record TokenEvent(PublicAddress actor, String type, byte[] tokenId) {}
}
