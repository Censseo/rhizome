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

    /**
     * Whether {@code sender} has a confirmed wallet on chain. A sender that has never
     * received funds cannot be executed — the block executor rejects it with
     * {@code SENDER_DOES_NOT_EXIST}. The mempool mirrors that so such a transaction is
     * never admitted or selected; otherwise a single free, signed no-op transaction from
     * a fresh keypair would be selected into every candidate block, get the whole block
     * rejected at execution, never be purged, and halt block production network-wide.
     */
    boolean senderExists(PublicAddress sender);
}
