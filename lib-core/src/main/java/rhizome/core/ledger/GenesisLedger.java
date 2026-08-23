package rhizome.core.ledger;

import java.util.Map;

import rhizome.core.transaction.TransactionAmount;

/**
 * Seeds a {@link Ledger} with the genesis allocations carried by a {@link LedgerSnapshot} —
 * the network's shipped default allocation, an operator-supplied file, or an empty snapshot.
 * Not Pandanite-specific: the snapshot may be an explicit, authored allocation (mainnet's
 * genesis today) as readily as an imported one.
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
     *
     * <p>Runs inside a {@link Ledger#beginBulkLoad()} window so a durable ledger can batch its
     * writes instead of paying one synchronous, synced write per wallet — a large allocation file
     * turned that into a multi-hour startup DoS (measured ~3.3-3.6 ms/wallet against RocksDB). The
     * window is purely a batching optimization: every call below is the exact same
     * hasWallet/createWallet/deposit sequence as before, so the resulting balances are unchanged.
     *
     * <p>The top-up semantics are exactly why the genesis path seeds only an EMPTY ledger
     * ({@code GenesisBlock.initChain} refuses anything else): the bulk-load window is not
     * crash-atomic, and re-running this method over the durable prefix of a torn seed would
     * deposit those wallets' amounts a second time while the genesis header commits the
     * snapshot's own total.
     */
    public static int seed(Ledger ledger, LedgerSnapshot snapshot) {
        int seeded = 0;
        ledger.beginBulkLoad();
        try {
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
        } finally {
            ledger.endBulkLoad();
        }
        return seeded;
    }
}
