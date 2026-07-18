package rhizome;

import java.util.HashMap;
import java.util.Map;

import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionAmount;

/** In-memory {@link Ledger} with the same checked semantics as LevelDBLedger. */
final class InMemoryLedger implements Ledger {

    private final Map<PublicAddress, Long> map = new HashMap<>();

    public boolean hasWallet(PublicAddress wallet) {
        return map.containsKey(wallet);
    }

    public void createWallet(PublicAddress wallet) {
        if (map.putIfAbsent(wallet, 0L) != null) {
            throw new LedgerException("Wallet already exists");
        }
    }

    public TransactionAmount getWalletValue(PublicAddress wallet) {
        Long value = map.get(wallet);
        if (value == null) {
            throw new LedgerException("Tried fetching wallet value for non-existent wallet");
        }
        return new TransactionAmount(value);
    }

    public void withdraw(PublicAddress wallet, TransactionAmount amt) {
        subtract(wallet, amt, "Insufficient funds for withdrawal");
    }

    public void revertSend(PublicAddress wallet, TransactionAmount amt) {
        add(wallet, amt);
    }

    public void deposit(PublicAddress wallet, TransactionAmount amt) {
        add(wallet, amt);
    }

    public void revertDeposit(PublicAddress wallet, TransactionAmount amt) {
        subtract(wallet, amt, "Cannot revert deposit below zero");
    }

    private void add(PublicAddress wallet, TransactionAmount amt) {
        long current = getWalletValue(wallet).amount();
        map.put(wallet, Math.addExact(current, amt.amount()));
    }

    private void subtract(PublicAddress wallet, TransactionAmount amt, String message) {
        long current = getWalletValue(wallet).amount();
        long next = current - amt.amount();
        if (next < 0) {
            throw new LedgerException(message);
        }
        map.put(wallet, next);
    }
}
