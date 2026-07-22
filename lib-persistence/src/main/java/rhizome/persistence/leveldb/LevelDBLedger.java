package rhizome.persistence.leveldb;

import org.iq80.leveldb.DBException;

import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionAmount;

import static rhizome.core.common.Utils.bytesToLong;
import static rhizome.core.common.Utils.longToBytes;

import java.io.IOException;

/**
 * LevelDB-backed {@link Ledger}.
 *
 * Unlike the original C++ implementation, every balance mutation is checked:
 * withdrawing or reverting more than the current balance, or overflowing a
 * deposit, raises a {@link LedgerException} instead of wrapping around.
 */
public class LevelDBLedger extends LevelDBDataStore implements Ledger {

    public LevelDBLedger(String path) throws IOException {
        super.init(path);
    }

    @Override
    public boolean hasWallet(PublicAddress wallet) {
        return getWalletValueInternal(wallet) != null;
    }

    @Override
    public void createWallet(PublicAddress wallet) {
        if (this.hasWallet(wallet)) {
            throw new LedgerException("Wallet already exists");
        }
        this.setWalletValue(wallet, new TransactionAmount(0L));
    }

    @Override
    public TransactionAmount getWalletValue(PublicAddress wallet) {
        TransactionAmount amount = getWalletValueInternal(wallet);
        if (amount == null) {
            throw new LedgerException("Tried fetching wallet value for non-existent wallet");
        }
        return amount;
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

    private void add(PublicAddress wallet, TransactionAmount amt) {
        long current = getWalletValue(wallet).amount();
        long newValue;
        try {
            newValue = Math.addExact(current, amt.amount());
        } catch (ArithmeticException e) {
            throw new LedgerException("Overflow detected during balance adjustment", e);
        }
        setWalletValue(wallet, new TransactionAmount(newValue));
    }

    private void subtract(PublicAddress wallet, TransactionAmount amt, String errorMessage) {
        long current = getWalletValue(wallet).amount();
        long newValue = current - amt.amount();
        if (newValue < 0) {
            throw new LedgerException(errorMessage);
        }
        setWalletValue(wallet, new TransactionAmount(newValue));
    }

    private void setWalletValue(PublicAddress wallet, TransactionAmount amount) {
        try {
            db().put(wallet.toBytes(), longToBytes(amount.amount()));
        } catch (DBException e) {
            throw new LedgerException("Failed to set wallet value", e);
        }
    }

    private TransactionAmount getWalletValueInternal(PublicAddress wallet) {
        byte[] value = db().get(wallet.toBytes());
        if (value == null) {
            return null;
        }
        return new TransactionAmount(bytesToLong(value));
    }
}
