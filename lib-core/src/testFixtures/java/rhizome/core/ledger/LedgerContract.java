package rhizome.core.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.transaction.TransactionAmount;

/**
 * The behaviour every {@link Ledger} owes its callers, run against each implementation.
 *
 * <p>Includes the per-block undo-journal protocol ({@link Ledger#applyBlock}/{@link
 * Ledger#revertBlock}/{@link Ledger#pruneJournals}): both journal-keeping backends must speak
 * it identically — the double-apply refusal (audit F10) and the op-less apply that persists no
 * journal — because a reorg after a restart runs against the durable copy, not the in-memory
 * one the suite exercises most.
 */
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

    @Test
    default void applyBlockRefusesADoubleApply() throws Exception {
        Ledger ledger = newLedger();
        PublicAddress wallet = PublicAddress.random();
        ledger.createWallet(wallet);
        ledger.deposit(wallet, new TransactionAmount(100));
        ledger.applyBlock(2, List.of(new LedgerOp(LedgerOp.Op.DEPOSIT, wallet, 100)));
        // A second apply at the same height would journal the already-mutated state as the
        // "prior", corrupting any later revert — it must be refused (audit F10), exactly like
        // the box/token/contract stores.
        List<LedgerOp> repeat = List.of(new LedgerOp(LedgerOp.Op.DEPOSIT, wallet, 50));
        assertThrows(IllegalStateException.class, () -> ledger.applyBlock(2, repeat));
        // ...and the first journal is untouched: the revert still replays IT, not the repeat.
        assertTrue(ledger.revertBlock(2));
        assertEquals(0L, ledger.getWalletValue(wallet).amount());
    }

    @Test
    default void anOpLessApplyRecordsNoJournalAndStaysReAppliable() throws Exception {
        // A block that moves no balance has nothing to undo, so it must not persist an (empty)
        // journal — otherwise a legitimately op-less block would falsely trip the double-apply
        // guard the moment it ran again (the box/token stores pin the same rule).
        Ledger ledger = newLedger();
        ledger.applyBlock(5, List.of());
        ledger.applyBlock(5, List.of()); // must not throw
        assertFalse(ledger.revertBlock(5), "no journal was kept: nothing to undo");
        PublicAddress wallet = PublicAddress.random();
        ledger.createWallet(wallet);
        ledger.deposit(wallet, new TransactionAmount(7));
        // A later real apply at that same height is still accepted — no phantom journal blocks it.
        ledger.applyBlock(5, List.of(new LedgerOp(LedgerOp.Op.DEPOSIT, wallet, 7)));
        assertTrue(ledger.revertBlock(5));
        assertEquals(0L, ledger.getWalletValue(wallet).amount());
    }

    @Test
    default void pruneJournalsBlocksLaterRevert() throws Exception {
        Ledger ledger = newLedger();
        PublicAddress wallet = PublicAddress.random();
        ledger.createWallet(wallet);
        ledger.deposit(wallet, new TransactionAmount(100));
        ledger.applyBlock(2, List.of(new LedgerOp(LedgerOp.Op.DEPOSIT, wallet, 100)));
        ledger.pruneJournals(3); // drop journals below height 3, including height 2
        assertFalse(ledger.revertBlock(2), "journal gone -> nothing to undo");
        assertEquals(100L, ledger.getWalletValue(wallet).amount(), "a pruned revert must be a no-op");
    }

    /**
     * The journal-path twin of {@link #applyThenRevertRestoresTheWholeLedgerByteForByte}: the
     * executor records each block's mutations with {@link Ledger#applyBlock} and a reorg replays
     * them through {@link Ledger#revertBlock} — WITHDRAW mapped to a credit, DEPOSIT to a debit,
     * in reverse application order. That replay is what a reorg after a restart actually runs,
     * so the whole-ledger property must hold through the journal, not only through hand-written
     * inverses.
     */
    @Test
    default void journaledApplyThenRevertRestoresTheWholeLedgerByteForByte() throws Exception {
        Ledger ledger = newLedger();
        PublicAddress alice = PublicAddress.random();
        PublicAddress bob = PublicAddress.random();
        ledger.createWallet(alice);
        ledger.createWallet(bob);
        ledger.deposit(alice, new TransactionAmount(10_000));
        String before = wholeLedgerBytes(ledger);

        // Block 2: alice sends bob 1_000, journaled in application order.
        ledger.withdraw(alice, new TransactionAmount(1_000));
        ledger.deposit(bob, new TransactionAmount(1_000));
        ledger.applyBlock(2, List.of(
            new LedgerOp(LedgerOp.Op.WITHDRAW, alice, 1_000),
            new LedgerOp(LedgerOp.Op.DEPOSIT, bob, 1_000)));

        // Block 3: bob sends alice 250.
        ledger.withdraw(bob, new TransactionAmount(250));
        ledger.deposit(alice, new TransactionAmount(250));
        ledger.applyBlock(3, List.of(
            new LedgerOp(LedgerOp.Op.WITHDRAW, bob, 250),
            new LedgerOp(LedgerOp.Op.DEPOSIT, alice, 250)));

        assertTrue(ledger.revertBlock(3));
        assertTrue(ledger.revertBlock(2));
        assertEquals(before, wholeLedgerBytes(ledger),
            "reverting journaled blocks must restore the ledger to its pre-apply state, byte for byte");
        // Both journals were consumed by their revert: a second attempt finds nothing.
        assertFalse(ledger.revertBlock(3));
        assertFalse(ledger.revertBlock(2));
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
