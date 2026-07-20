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
}
