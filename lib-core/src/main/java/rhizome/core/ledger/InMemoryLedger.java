package rhizome.core.ledger;

import java.util.HashMap;
import java.util.Map;

import rhizome.core.transaction.TransactionAmount;

/**
 * In-memory {@link Ledger} reference implementation, with the same checked
 * arithmetic as the persistent stores (underflow/overflow throw). Used by the
 * light node, tooling and tests; the durable path is LevelDB/RocksDB.
 */
public final class InMemoryLedger implements Ledger {

    private final Map<PublicAddress, Long> balances = new HashMap<>();

    @Override
    public boolean hasWallet(PublicAddress wallet) {
        return balances.containsKey(wallet);
    }

    @Override
    public void createWallet(PublicAddress wallet) {
        if (balances.putIfAbsent(wallet, 0L) != null) {
            throw new LedgerException("Wallet already exists");
        }
    }

    @Override
    public TransactionAmount getWalletValue(PublicAddress wallet) {
        Long value = balances.get(wallet);
        if (value == null) {
            throw new LedgerException("Tried fetching wallet value for non-existent wallet");
        }
        return new TransactionAmount(value);
    }

    @Override
    public void withdraw(PublicAddress wallet, TransactionAmount amt) {
        subtract(wallet, amt, "Insufficient funds for withdrawal");
    }

    @Override
    public void revertSend(PublicAddress wallet, TransactionAmount amt) {
        add(wallet, amt);
    }

    @Override
    public void deposit(PublicAddress wallet, TransactionAmount amt) {
        add(wallet, amt);
    }

    @Override
    public void revertDeposit(PublicAddress wallet, TransactionAmount amt) {
        subtract(wallet, amt, "Cannot revert deposit below zero");
    }

    @Override
    public void forEachBalance(java.util.function.ObjLongConsumer<PublicAddress> consumer) {
        balances.forEach(consumer::accept);
    }

    private void add(PublicAddress wallet, TransactionAmount amt) {
        long current = getWalletValue(wallet).amount();
        balances.put(wallet, Math.addExact(current, amt.amount()));
    }

    private void subtract(PublicAddress wallet, TransactionAmount amt, String message) {
        long next = getWalletValue(wallet).amount() - amt.amount();
        if (next < 0) {
            throw new LedgerException(message);
        }
        balances.put(wallet, next);
    }
}
