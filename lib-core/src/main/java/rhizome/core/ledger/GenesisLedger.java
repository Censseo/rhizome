package rhizome.core.ledger;

import java.util.Map;

import rhizome.core.transaction.TransactionAmount;

/**
 * Seeds a {@link Ledger} with the genesis allocations of the clean chain, taken
 * from a {@link LedgerSnapshot} of the existing Pandanite chain.
 *
 * <p>Storage-agnostic: it drives any {@link Ledger} implementation through the
 * public {@code createWallet}/{@code deposit} API.
 */
public final class GenesisLedger {

    private GenesisLedger() {}

    /**
     * Applies every allocation in {@code snapshot} to {@code ledger}. Existing
     * wallets are topped up; missing ones are created first. Returns the number
     * of wallets seeded.
     */
    public static int seed(Ledger ledger, LedgerSnapshot snapshot) {
        int seeded = 0;
        for (Map.Entry<PublicAddress, TransactionAmount> entry : snapshot.balances().entrySet()) {
            PublicAddress address = entry.getKey();
            // Defense in depth (audit F3): a balance must be a non-negative signed 64-bit value.
            // LedgerSnapshot.fromJson already rejects high-bit amounts, but a snapshot built
            // programmatically could still carry one — a negative deposit would corrupt the
            // ledger's checked arithmetic from the very first block.
            if (entry.getValue().amount() < 0) {
                throw new IllegalArgumentException("negative genesis balance for " + address);
            }
            if (!ledger.hasWallet(address)) {
                ledger.createWallet(address);
            }
            ledger.deposit(address, entry.getValue());
            seeded++;
        }
        return seeded;
    }
}
