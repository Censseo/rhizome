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
     * Height of the confirmed chain tip. The mempool uses {@code confirmedHeight() + 1} — the height
     * the next block would carry — to refuse box/token transactions that are not yet valid at that
     * height (their activation gate), so a pre-activation box/token tx is never admitted, selected into
     * a candidate block, and then rejected by the executor as {@code BOX_UNAVAILABLE}/{@code
     * TOKEN_UNAVAILABLE} — which would invalidate every block the producer builds and halt production.
     * On the shipped networks box/token activation is 0, so this only bites a custom delayed-activation
     * network; the default assumes activation gating is not needed.
     */
    default long confirmedHeight() {
        return Long.MAX_VALUE;
    }

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
