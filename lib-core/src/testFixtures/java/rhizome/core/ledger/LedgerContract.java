package rhizome.core.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rhizome.core.transaction.TransactionAmount;

/** The behaviour every {@link Ledger} owes its callers, run against each implementation. */
public interface LedgerContract {

    /** A fresh, empty ledger. */
    Ledger newLedger() throws Exception;

    @Test
    default void hasWalletReflectsCreationAndBalanceOrZeroDefaultsForAbsentWallets() throws Exception {
        Ledger ledger = newLedger();
        PublicAddress wallet = PublicAddress.random();
        assertFalse(ledger.hasWallet(wallet));
        assertEquals(0L, ledger.balanceOrZero(wallet));

        ledger.createWallet(wallet);
        assertTrue(ledger.hasWallet(wallet));
        assertEquals(0L, ledger.getWalletValue(wallet).amount());
    }

    @Test
    default void createWalletTwiceThrows() throws Exception {
        Ledger ledger = newLedger();
        PublicAddress wallet = PublicAddress.random();
        ledger.createWallet(wallet);
        assertThrows(LedgerException.class, () -> ledger.createWallet(wallet));
    }

    @Test
    default void depositThenWithdrawRoundTripsAndRefusesToUnderflow() throws Exception {
        Ledger ledger = newLedger();
        PublicAddress wallet = PublicAddress.random();
        ledger.createWallet(wallet);

        ledger.deposit(wallet, new TransactionAmount(100));
        assertEquals(100L, ledger.getWalletValue(wallet).amount());

        assertThrows(LedgerException.class, () -> ledger.withdraw(wallet, new TransactionAmount(200)));
        assertEquals(100L, ledger.getWalletValue(wallet).amount(), "a refused withdrawal must not partially apply");

        ledger.withdraw(wallet, new TransactionAmount(40));
        assertEquals(60L, ledger.getWalletValue(wallet).amount());
    }

    @Test
    default void revertSendAndRevertDepositAreExactInverses() throws Exception {
        Ledger ledger = newLedger();
        PublicAddress wallet = PublicAddress.random();
        ledger.createWallet(wallet);
        ledger.deposit(wallet, new TransactionAmount(100));

        // revertSend undoes a withdrawal (gives the amount back).
        ledger.withdraw(wallet, new TransactionAmount(30));
        ledger.revertSend(wallet, new TransactionAmount(30));
        assertEquals(100L, ledger.getWalletValue(wallet).amount());

        // revertDeposit undoes a deposit (takes the amount back).
        ledger.deposit(wallet, new TransactionAmount(50));
        ledger.revertDeposit(wallet, new TransactionAmount(50));
        assertEquals(100L, ledger.getWalletValue(wallet).amount());
    }

    /**
     * The exact-inverse property that makes a reorg safe: a sequence of ledger operations
     * followed by their exact inverses, in reverse order, must restore the WHOLE ledger —
     * not just the few wallets a test author thought to check. A reorg path that re-derives
     * an inverse arithmetically (the four hand-written mirrors documented in Executor)
     * passes a hand-picked assertion and diverges every node's balances after a reorg.
     *
     * <p>Zero balances are omitted from the comparison, deliberately: a reverted credit
     * leaves the wallet key behind at zero, documented as fork-safe because block validity is
     * a pure function of balance, never of key presence (audit consensus Finding 1).
     */
    @Test
    default void applyThenRevertRestoresTheWholeLedgerByteForByte() throws Exception {
        Ledger ledger = newLedger();
        PublicAddress alice = PublicAddress.random();
        PublicAddress bob = PublicAddress.random();
        PublicAddress carol = PublicAddress.random();
        ledger.createWallet(alice);
        ledger.deposit(alice, new TransactionAmount(10_000));
        ledger.createWallet(bob);
        ledger.deposit(bob, new TransactionAmount(5_000));
        String before = wholeLedgerBytes(ledger);

        // Apply: transfers, fees, a fresh wallet.
        ledger.withdraw(alice, new TransactionAmount(1_000));
        ledger.deposit(bob, new TransactionAmount(1_000));
        ledger.withdraw(bob, new TransactionAmount(250));
        ledger.createWallet(carol);
        ledger.deposit(carol, new TransactionAmount(250));

        // Revert in exact reverse order with the exact inverse operations.
        ledger.revertDeposit(carol, new TransactionAmount(250));
        ledger.revertSend(bob, new TransactionAmount(250));
        ledger.revertDeposit(bob, new TransactionAmount(1_000));
        ledger.revertSend(alice, new TransactionAmount(1_000));

        assertEquals(before, wholeLedgerBytes(ledger),
            "applying then reverting must restore the whole ledger, byte for byte");
    }

    /** The ledger's entire content as a deterministic, order-independent byte string. */
    private static String wholeLedgerBytes(Ledger ledger) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        ledger.forEachBalance((address, amount) -> {
            if (amount != 0) {
                parts.add(address.toHexString() + Long.toHexString(amount));
            }
        });
        parts.sort(String::compareTo);
        return String.join("|", parts);
    }
}
