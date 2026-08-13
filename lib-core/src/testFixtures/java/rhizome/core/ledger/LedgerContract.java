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
}
