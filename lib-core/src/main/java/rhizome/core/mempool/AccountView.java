package rhizome.core.mempool;

import rhizome.core.ledger.PublicAddress;

/**
 * Read-only view of confirmed account state that the mempool needs to admit
 * transactions: the next expected account nonce and the spendable balance.
 * Implemented by the chain engine over its confirmed ledger and nonce state.
 */
public interface AccountView {

    /** Next account nonce the chain expects from {@code sender} (0 for a fresh account). */
    long confirmedNextNonce(PublicAddress sender);

    /** Confirmed balance of {@code sender} (0 if the wallet does not exist yet). */
    long confirmedBalance(PublicAddress sender);
}
