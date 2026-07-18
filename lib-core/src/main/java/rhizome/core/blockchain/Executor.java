package rhizome.core.blockchain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.crypto.SHA256Hash;
import rhizome.core.ledger.Ledger;
import rhizome.core.ledger.LedgerException;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionImpl;

import static rhizome.core.mempool.ExecutionStatus.*;

/**
 * Validates and applies a block's transactions to the ledger.
 *
 * <p>Clean-chain rules (Pandanite parity where sound, fixed where it was not):
 * <ol>
 *   <li>Exactly one coinbase transaction; its amount must equal the expected
 *       mining reward for the block height ({@code NetworkParameters.miningReward},
 *       integer-only — no float comparison forks).</li>
 *   <li>Every other transaction must: target this network
 *       ({@code chainId}), have a valid signature whose key matches the sender
 *       address, and not be a duplicate (in-block or already executed —
 *       identified by the signature-free content hash, immune to Ed25519
 *       malleability).</li>
 *   <li>Balance checks and mutations go through the {@link Ledger}, whose
 *       checked arithmetic rejects underflow — the C++ ledger's unchecked
 *       {@code uint64} subtraction is what inflated balances in the
 *       invalid.json incident.</li>
 *   <li>Application is transactional: on any failure every applied operation is
 *       rolled back in reverse order and the failing status returned.</li>
 * </ol>
 *
 * <p>Account-nonce ordering (strictly increasing per sender) is enforced at the
 * engine level where the nonce store lives; the executor validates everything
 * that is ledger-local.
 */
public final class Executor {

    private Executor() {}

    /** One applied ledger mutation, recorded for rollback. */
    private record AppliedOp(Op op, PublicAddress wallet, TransactionAmount amount) {
        enum Op { WITHDRAW, DEPOSIT }
    }

    /**
     * Validates and applies {@code block} to {@code ledger}.
     *
     * @param alreadyExecuted membership test over content hashes of transactions
     *                        the chain has already executed (backed by the txdb)
     * @return {@link ExecutionStatus#SUCCESS}, or the failure status with the
     *         ledger left exactly as it was
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params) {
        var blockImpl = (BlockImpl) block;
        long height = blockImpl.id();
        long expectedReward = params.miningReward(height);

        // --- Pass 1: structural validation, no state touched ---
        Transaction coinbase = null;
        Set<SHA256Hash> seenInBlock = new HashSet<>();
        for (Transaction t : block.transactions()) {
            var tx = (TransactionImpl) t;
            if (tx.isTransactionFee()) {
                if (coinbase != null) {
                    return EXTRA_MINING_FEE;
                }
                coinbase = t;
                continue;
            }
            if (tx.chainId() != params.chainId()) {
                return INVALID_CHAIN_ID;
            }
            SHA256Hash id = t.hashContents();
            if (!seenInBlock.add(id) || alreadyExecuted.test(id)) {
                return EXPIRED_TRANSACTION;
            }
            if (!PublicAddress.of(tx.signingKey()).equals(tx.from())) {
                return WALLET_SIGNATURE_MISMATCH;
            }
            if (!t.signatureValid()) {
                return INVALID_SIGNATURE;
            }
        }
        if (coinbase == null) {
            return NO_MINING_FEE;
        }
        if (((TransactionImpl) coinbase).amount().amount() != expectedReward) {
            return INCORRECT_MINING_FEE;
        }

        // --- Pass 2: transactional application ---
        PublicAddress miner = ((TransactionImpl) coinbase).to();
        List<AppliedOp> applied = new ArrayList<>();
        try {
            for (Transaction t : block.transactions()) {
                var tx = (TransactionImpl) t;
                if (tx.isTransactionFee()) {
                    continue;
                }
                long amount = tx.amount().amount();
                long fee = tx.fee().amount();
                long charged;
                try {
                    charged = Math.addExact(amount, fee);
                } catch (ArithmeticException e) {
                    rollback(ledger, applied);
                    return BALANCE_TOO_LOW;
                }

                if (!ledger.hasWallet(tx.from())) {
                    rollback(ledger, applied);
                    return SENDER_DOES_NOT_EXIST;
                }
                if (ledger.getWalletValue(tx.from()).amount() < charged) {
                    rollback(ledger, applied);
                    return BALANCE_TOO_LOW;
                }

                withdraw(ledger, applied, tx.from(), new TransactionAmount(charged));
                deposit(ledger, applied, tx.to(), tx.amount());
                if (fee > 0) {
                    deposit(ledger, applied, miner, new TransactionAmount(fee));
                }
            }
            deposit(ledger, applied, miner, ((TransactionImpl) coinbase).amount());
            return SUCCESS;
        } catch (LedgerException e) {
            rollback(ledger, applied);
            return BALANCE_TOO_LOW;
        }
    }

    private static void withdraw(Ledger ledger, List<AppliedOp> applied,
                                 PublicAddress wallet, TransactionAmount amount) {
        ledger.withdraw(wallet, amount);
        applied.add(new AppliedOp(AppliedOp.Op.WITHDRAW, wallet, amount));
    }

    private static void deposit(Ledger ledger, List<AppliedOp> applied,
                                PublicAddress wallet, TransactionAmount amount) {
        if (!ledger.hasWallet(wallet)) {
            ledger.createWallet(wallet);
        }
        ledger.deposit(wallet, amount);
        applied.add(new AppliedOp(AppliedOp.Op.DEPOSIT, wallet, amount));
    }

    private static void rollback(Ledger ledger, List<AppliedOp> applied) {
        for (int i = applied.size() - 1; i >= 0; i--) {
            AppliedOp op = applied.get(i);
            switch (op.op()) {
                case WITHDRAW -> ledger.revertSend(op.wallet(), op.amount());
                case DEPOSIT -> ledger.revertDeposit(op.wallet(), op.amount());
            }
        }
    }

    /**
     * Undoes a previously applied block: the exact inverse of
     * {@link #executeBlock}'s mutations, in reverse order. The block must be the
     * most recently applied one (used by {@code popBlock} during reorgs).
     */
    public static void rollbackBlock(Block block, Ledger ledger) {
        List<Transaction> transactions = block.transactions();
        Transaction coinbase = transactions.stream()
            .filter(t -> ((TransactionImpl) t).isTransactionFee())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Block has no coinbase"));
        PublicAddress miner = ((TransactionImpl) coinbase).to();

        ledger.revertDeposit(miner, ((TransactionImpl) coinbase).amount());
        for (int i = transactions.size() - 1; i >= 0; i--) {
            var tx = (TransactionImpl) transactions.get(i);
            if (tx.isTransactionFee()) {
                continue;
            }
            long fee = tx.fee().amount();
            if (fee > 0) {
                ledger.revertDeposit(miner, new TransactionAmount(fee));
            }
            ledger.revertDeposit(tx.to(), tx.amount());
            ledger.revertSend(tx.from(), new TransactionAmount(tx.amount().amount() + fee));
        }
    }
}
