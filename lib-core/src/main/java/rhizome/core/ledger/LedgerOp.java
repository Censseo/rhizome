package rhizome.core.ledger;

/**
 * One applied ledger mutation, as recorded in a block's undo journal: the operation and the
 * wallet and amount it moved. Reverting a journal replays the exact inverse of every entry
 * (WITHDRAW → {@link Ledger#revertSend}, DEPOSIT → {@link Ledger#revertDeposit}) in reverse
 * application order.
 *
 * @param op     the operation that was applied
 * @param wallet the wallet it moved
 * @param amount the amount (always positive: the forward path never applies a zero or negative
 *               mutation — a zero deposit under consensus V2 is a strict no-op and records
 *               nothing, and a zero withdrawal is never charged)
 */
public record LedgerOp(Op op, PublicAddress wallet, long amount) {

    public enum Op { WITHDRAW, DEPOSIT }

    public LedgerOp {
        if (amount < 0) {
            throw new IllegalArgumentException("negative ledger op amount: " + amount);
        }
    }

    /** The exact inverse of this mutation. */
    public void revert(Ledger ledger) {
        switch (op) {
            case WITHDRAW -> ledger.revertSend(wallet, new rhizome.core.transaction.TransactionAmount(amount));
            case DEPOSIT -> ledger.revertDeposit(wallet, new rhizome.core.transaction.TransactionAmount(amount));
        }
    }
}
