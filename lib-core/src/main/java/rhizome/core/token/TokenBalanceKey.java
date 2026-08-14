package rhizome.core.token;

import java.util.Arrays;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.state.StateKeys;

/**
 * The composite key of a token balance: {@code tokenId ‖ address} — the layout the
 * state root ({@link StateKeys#TOKEN_BALANCE}), the RocksDB {@code token_balance}
 * column family and the in-memory stores all key balances on.
 *
 * <p>Carries the two halves as typed values instead of a hex-concatenated {@code
 * String} that later code must {@code substring} back apart (the L25 constat: composite
 * keys hand-split by offset). {@link #toBytes()} delegates to {@link
 * StateKeys#tokenBalanceKey} so the committed byte layout has exactly one definition,
 * and {@link #of(byte[])} reconstructs the halves via the same single source's inverse.
 */
public record TokenBalanceKey(TokenId tokenId, PublicAddress address) {

    public static TokenBalanceKey of(TokenId tokenId, PublicAddress address) {
        return new TokenBalanceKey(tokenId, address);
    }

    /** The committed storage/state-root key: {@code tokenId(32) ‖ address(25)}. */
    public byte[] toBytes() {
        return StateKeys.tokenBalanceKey(tokenId.toBytes(), address.toBytes());
    }

    /**
     * The committed key {@code tokenId ‖ address} back into its typed halves — the exact
     * inverse of {@link #toBytes()}, so encode and decode cannot drift independently.
     */
    public static TokenBalanceKey of(byte[] key) {
        byte[][] split = StateKeys.splitTokenBalanceKey(key);
        return new TokenBalanceKey(TokenId.of(split[0]), PublicAddress.of(split[1]));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TokenBalanceKey o)) {
            return false;
        }
        return tokenId.equals(o.tokenId) && address.equals(o.address);
    }

    @Override
    public int hashCode() {
        return 31 * tokenId.hashCode() + address.hashCode();
    }

    /** Defensive-copy sanity, mirroring the record's intent: equals by content, not identity. */
    @Override
    public String toString() {
        return "TokenBalanceKey[" + tokenId + " / " + address.toHexString() + "]";
    }
}
