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
 * it identically — the double-apply refusal (audit F10), the op-less apply that persists no
 * journal, and a revert that replays the journal through the store's checked inverses rather
 * than a second arithmetic — because a reorg after a restart runs against the durable copy,
 * not the in-memory one the suite exercises most.
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

    @Test
    default void revertOfAJournalNamingAnAbsentWalletFailsInsteadOfInventingABalance() throws Exception {
        // Every legitimately journaled op touched an existing wallet on the way in, so a journal
        // naming an absent wallet means store corruption. Reading the absent wallet as zero and
        // writing the inverse onto it (the old RocksDB replay) silently MINTED a balance the
        // consensus path then read as real; both backends must throw instead.
        Ledger ledger = newLedger();
        PublicAddress ghost = PublicAddress.random();
        ledger.applyBlock(2, List.of(new LedgerOp(LedgerOp.Op.WITHDRAW, ghost, 50)));
        assertThrows(LedgerException.class, () -> ledger.revertBlock(2));
        assertFalse(ledger.hasWallet(ghost), "a refused revert must not materialise the wallet");
    }

    @Test
    default void revertOfADepositBelowZeroFailsInsteadOfWritingANegativeBalance() throws Exception {
        // The underflow twin: reverting a deposit larger than the wallet's balance means the
        // journal disagrees with the committed state (corruption). The old RocksDB replay wrote
        // the negative result, readable as a real balance; both backends must refuse, like any
        // other underflow.
        Ledger ledger = newLedger();
        PublicAddress wallet = PublicAddress.random();
        ledger.createWallet(wallet);
        ledger.deposit(wallet, new TransactionAmount(30));
        ledger.applyBlock(2, List.of(new LedgerOp(LedgerOp.Op.DEPOSIT, wallet, 50)));
        assertThrows(LedgerException.class, () -> ledger.revertBlock(2));
        assertEquals(30L, ledger.getWalletValue(wallet).amount(), "a refused revert must not partially apply");
    }

    /**
     * {@link Ledger#beginBulkLoad()}/{@link Ledger#endBulkLoad()} is a pure batching
     * optimization ({@link GenesisLedger#seed} is the only caller): reads inside the window see
     * every write made so far (read-your-writes), and the state after {@code endBulkLoad()} must
     * be byte-identical to running the same calls with no window open at all — BOTH halves of
     * that claim are asserted below, against two fresh ledgers.
     */
    @Test
    default void bulkLoadWindowIsReadYourWritesAndMatchesUnbatchedWrites() throws Exception {
        PublicAddress alice = PublicAddress.random();
        PublicAddress bob = PublicAddress.random();

        Ledger batched = newLedger();
        batched.beginBulkLoad();
        try {
            batched.createWallet(alice);
            batched.deposit(alice, new TransactionAmount(1_000));
            batched.createWallet(bob);
            batched.deposit(bob, new TransactionAmount(500));
            // Read-your-writes: staged entries are visible before the window closes.
            assertEquals(1_000L, batched.getWalletValue(alice).amount());
            assertEquals(500L, batched.getWalletValue(bob).amount());
        } finally {
            batched.endBulkLoad();
        }

        assertEquals(1_000L, batched.getWalletValue(alice).amount());
        assertEquals(500L, batched.getWalletValue(bob).amount());
        assertTrue(batched.hasWallet(alice));
        assertTrue(batched.hasWallet(bob));

        // The "matches unbatched writes" half: the identical call sequence with no window open
        // must produce the identical WHOLE-ledger state — the window may change only the
        // durability batching, never the result.
        Ledger unbatched = newLedger();
        unbatched.createWallet(alice);
        unbatched.deposit(alice, new TransactionAmount(1_000));
        unbatched.createWallet(bob);
        unbatched.deposit(bob, new TransactionAmount(500));
        assertEquals(wholeLedgerBytes(unbatched), wholeLedgerBytes(batched),
            "a bulk-load window must leave the same state as the same calls unbatched");
    }

    /**
     * The same contract at a scale that crosses the durable backend's internal flush chunking:
     * {@code RocksDbNodeStore.RocksLedger} flushes a bulk window in chunks of 10,000 wallets per
     * {@code WriteBatch}, so 10,001 entries force the chunk-rollover path (write, {@code clear()},
     * continue, final partial write) that the small test above never reaches — previously only
     * the periodic 50k/200k-wallet E2E-58 startup test crossed it, outside {@code ./gradlew
     * build}. Addresses are derived, not randomly generated, so the cost here is the store's,
     * not key generation's. (The in-memory backend has no chunks; for it this is simply the
     * contract at scale.)
     */
    @Test
    default void bulkLoadWindowSurvivesCrossingTheFlushChunkBoundary() throws Exception {
        int walletCount = 10_001; // one past RocksDbNodeStore's 10,000-wallet flush chunk
        java.util.List<PublicAddress> addresses = new java.util.ArrayList<>(walletCount);
        Ledger ledger = newLedger();
        ledger.beginBulkLoad();
        try {
            for (int i = 0; i < walletCount; i++) {
                byte[] bytes = new byte[PublicAddress.SIZE];
                java.nio.ByteBuffer.wrap(bytes).putLong(1, i);
                PublicAddress address = PublicAddress.of(bytes);
                addresses.add(address);
                ledger.createWallet(address);
                ledger.deposit(address, new TransactionAmount(i + 1L));
            }
        } finally {
            ledger.endBulkLoad();
        }
        for (int i = 0; i < walletCount; i++) {
            assertEquals(i + 1L, ledger.getWalletValue(addresses.get(i)).amount(),
                "wallet " + i + " must survive the chunked flush with its exact balance");
        }
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
