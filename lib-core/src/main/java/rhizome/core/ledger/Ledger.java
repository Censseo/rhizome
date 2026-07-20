package rhizome.core.ledger;

import rhizome.core.transaction.TransactionAmount;

/**
 * Wallet balance store. Implementations live in lib-persistence so that
 * lib-core stays free of any storage backend dependency.
 */
public interface Ledger {

    boolean hasWallet(PublicAddress wallet);

    void createWallet(PublicAddress wallet);

    TransactionAmount getWalletValue(PublicAddress wallet);

    void withdraw(PublicAddress wallet, TransactionAmount amt);

    void revertSend(PublicAddress wallet, TransactionAmount amt);

    void deposit(PublicAddress wallet, TransactionAmount amt);

    void revertDeposit(PublicAddress wallet, TransactionAmount amt);

    /**
     * Visits every stored {@code (wallet, balance)} pair — the state-snapshot export path.
     * Optional: stores that never serve snapshots may leave the unsupported default.
     */
    default void forEachBalance(java.util.function.ObjLongConsumer<PublicAddress> consumer) {
        throw new UnsupportedOperationException("this ledger does not support enumeration");
    }
}
