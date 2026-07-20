package rhizome.core.blockchain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.box.BoxProcessor;
import rhizome.core.token.TokenProcessor;
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
        return executeBlock(block, ledger, alreadyExecuted, params, null);
    }

    /**
     * As {@link #executeBlock(Block, Ledger, Predicate, NetworkParameters)}, but
     * offloads Ed25519 checks to {@code verifier} (parallel, with a verify-once
     * cache). Signatures are checked in one batch up front; the structural pass
     * then trusts that result. Pass {@code null} to verify inline.
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params,
                                               SignatureVerifier verifier) {
        return executeBlock(block, ledger, alreadyExecuted, params, verifier, null);
    }

    /**
     * As above, with a {@link ContractProcessor} for DEPLOY/CALL transactions. When
     * {@code processor} is null, contract transactions are rejected (consensus does
     * not run them). Contract state writes are buffered in a per-block session that
     * is committed only when the whole block succeeds, so block execution stays
     * atomic; a contract that reverts or runs out of gas still pays its gas (like
     * Ethereum) without invalidating the block.
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params,
                                               SignatureVerifier verifier,
                                               ContractProcessor processor) {
        return executeBlock(block, ledger, alreadyExecuted, params, verifier, processor, null);
    }

    /**
     * As above, with a {@link BoxProcessor} for the box transaction kinds
     * (BOX_CREATE/UPDATE/SPEND/COLLECT). When {@code boxProcessor} is null, box
     * transactions are rejected. Box state is staged in a per-block session that
     * commits atomically with the block, exactly like contract state.
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params,
                                               SignatureVerifier verifier,
                                               ContractProcessor processor,
                                               BoxProcessor boxProcessor) {
        return executeBlock(block, ledger, alreadyExecuted, params, verifier, processor, boxProcessor, null);
    }

    /**
     * As above, with a {@link TokenProcessor} for the native-token kinds
     * (TOKEN_MINT/TRANSFER/BURN). When {@code tokenProcessor} is null, token
     * transactions are rejected. Token state is staged in a per-block session that
     * commits atomically with the block.
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params,
                                               SignatureVerifier verifier,
                                               ContractProcessor processor,
                                               BoxProcessor boxProcessor,
                                               TokenProcessor tokenProcessor) {
        return executeBlock(block, ledger, alreadyExecuted, params, verifier,
            processor, boxProcessor, tokenProcessor, null);
    }

    /**
     * As above, additionally collecting into {@code touchedLedger} (when non-null) every
     * ledger address this block credited or debited — so the caller can read their final
     * balances to feed the authenticated state accumulator.
     */
    public static ExecutionStatus executeBlock(Block block, Ledger ledger,
                                               Predicate<SHA256Hash> alreadyExecuted,
                                               NetworkParameters params,
                                               SignatureVerifier verifier,
                                               ContractProcessor processor,
                                               BoxProcessor boxProcessor,
                                               TokenProcessor tokenProcessor,
                                               Set<PublicAddress> touchedLedger) {
        var blockImpl = (BlockImpl) block;
        long height = blockImpl.id();
        long expectedReward = params.miningReward(height);

        // Batch-verify all signatures in parallel before the structural pass.
        if (verifier != null && !verifier.verifyAll(block.transactions())) {
            return INVALID_SIGNATURE;
        }

        // --- Pass 1: structural validation, no state touched ---
        Transaction coinbase = null;
        Set<SHA256Hash> seenInBlock = new HashSet<>();
        int boxCollects = 0;
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
            // Contract transactions execute only when a processor is wired; without
            // one, consensus does not run them, so a block carrying one is rejected —
            // no contract tx can be mistaken for a transfer.
            if (tx.kind().isContract() && processor == null) {
                return CONTRACT_EXECUTION_UNAVAILABLE;
            }
            if (tx.kind().isBox()) {
                if (boxProcessor == null || height < params.boxActivationHeight()) {
                    return BOX_UNAVAILABLE;
                }
                // Box ops run no VM and cost no gas; the gas fields are reserved and must
                // be zero (else the signed preimage could carry hidden, unpriced data).
                if (tx.gasLimit() != 0 || tx.gasPrice() != 0) {
                    return BOX_PAYLOAD_INVALID;
                }
                if (tx.kind() == rhizome.core.transaction.TransactionKind.BOX_COLLECT
                    && ++boxCollects > params.maxBoxCollectsPerBlock()) {
                    return BOX_LIMIT_EXCEEDED;
                }
            }
            if (tx.kind().isToken()) {
                if (tokenProcessor == null || height < params.tokenActivationHeight()) {
                    return TOKEN_UNAVAILABLE;
                }
                // Token ops run no VM, cost no gas, and move no PDN — the token amount lives
                // in the payload, so the gas fields and the PDN amount field must be zero.
                if (tx.gasLimit() != 0 || tx.gasPrice() != 0 || tx.amount().amount() != 0) {
                    return TOKEN_PAYLOAD_INVALID;
                }
            }
            // A negative amount or fee would invert the ledger arithmetic: withdrawing
            // a negative value MINTS money for the sender and deposits drive the
            // recipient's balance negative. Amounts are conceptually unsigned, so any
            // negative long (including values with the high bit set) is illegal.
            if (tx.amount().amount() < 0 || tx.fee().amount() < 0) {
                return INVALID_TRANSACTION_AMOUNT;
            }
            SHA256Hash id = t.hashContents();
            if (!seenInBlock.add(id) || alreadyExecuted.test(id)) {
                return EXPIRED_TRANSACTION;
            }
            if (!PublicAddress.of(tx.signingKey()).equals(tx.from())) {
                return WALLET_SIGNATURE_MISMATCH;
            }
            if (verifier == null && !t.signatureValid()) {
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
        if (processor != null) {
            processor.begin();
        }
        if (boxProcessor != null) {
            boxProcessor.begin();
        }
        if (tokenProcessor != null) {
            tokenProcessor.begin();
        }
        try {
            for (Transaction t : block.transactions()) {
                var tx = (TransactionImpl) t;
                if (tx.isTransactionFee()) {
                    continue;
                }
                if (tx.kind().isContract()) {
                    ExecutionStatus contractStatus = applyContract(tx, ledger, applied, miner, processor);
                    if (contractStatus != SUCCESS) {
                        return abort(processor, boxProcessor, tokenProcessor, ledger, applied, contractStatus);
                    }
                    continue;
                }
                if (tx.kind().isBox()) {
                    ExecutionStatus boxStatus = applyBox(tx, ledger, applied, miner, boxProcessor, height);
                    if (boxStatus != SUCCESS) {
                        return abort(processor, boxProcessor, tokenProcessor, ledger, applied, boxStatus);
                    }
                    continue;
                }
                if (tx.kind().isToken()) {
                    ExecutionStatus tokenStatus = applyToken(tx, ledger, applied, miner, tokenProcessor, height);
                    if (tokenStatus != SUCCESS) {
                        return abort(processor, boxProcessor, tokenProcessor, ledger, applied, tokenStatus);
                    }
                    continue;
                }
                long amount = tx.amount().amount();
                long fee = tx.fee().amount();
                long charged;
                try {
                    charged = Math.addExact(amount, fee);
                } catch (ArithmeticException e) {
                    return abort(processor, boxProcessor, tokenProcessor, ledger, applied, BALANCE_TOO_LOW);
                }

                if (!ledger.hasWallet(tx.from())) {
                    return abort(processor, boxProcessor, tokenProcessor, ledger, applied, SENDER_DOES_NOT_EXIST);
                }
                if (ledger.getWalletValue(tx.from()).amount() < charged) {
                    return abort(processor, boxProcessor, tokenProcessor, ledger, applied, BALANCE_TOO_LOW);
                }

                withdraw(ledger, applied, tx.from(), new TransactionAmount(charged));
                deposit(ledger, applied, tx.to(), tx.amount());
                if (fee > 0) {
                    deposit(ledger, applied, miner, new TransactionAmount(fee));
                }
            }
            deposit(ledger, applied, miner, ((TransactionImpl) coinbase).amount());
            // GHOST uncle rewards: fresh issuance to each referenced uncle's miner, plus a
            // nephew bonus to this block's miner. Every uncle is a real PoW block, so no
            // reward is minted without matching work. Uncle validity (miner address, depth,
            // no double-crediting) was already enforced by the engine.
            payUncleRewards(blockImpl, ledger, applied, miner, params);
            if (processor != null) {
                processor.commit(blockImpl.id());
            }
            if (boxProcessor != null) {
                boxProcessor.commit(blockImpl.id());
            }
            if (tokenProcessor != null) {
                tokenProcessor.commit(blockImpl.id());
            }
            // Report every touched ledger address (each applied op names its wallet) so the
            // caller can read final balances for the state accumulator.
            if (touchedLedger != null) {
                for (AppliedOp op : applied) {
                    touchedLedger.add(op.wallet());
                }
            }
            return SUCCESS;
        } catch (LedgerException e) {
            return abort(processor, boxProcessor, tokenProcessor, ledger, applied, BALANCE_TOO_LOW);
        } catch (ArithmeticException e) {
            // A deposit that would overflow a wallet's 64-bit balance (Math.addExact
            // in the ledger) must be rejected cleanly, not left as a partial mutation.
            // Underflow is already a LedgerException above; this is the overflow twin.
            return abort(processor, boxProcessor, tokenProcessor, ledger, applied, BALANCE_OVERFLOW);
        }
    }

    /** Mints the GHOST uncle and nephew rewards for a block's referenced uncles. */
    private static void payUncleRewards(BlockImpl block, Ledger ledger, List<AppliedOp> applied,
                                        PublicAddress miner, NetworkParameters params) {
        List<rhizome.core.block.UncleRef> uncles = block.uncles();
        if (uncles.isEmpty()) {
            return;
        }
        long height = block.id();
        long uncleReward = params.uncleReward(height);
        long nephewReward = params.nephewReward(height);
        for (rhizome.core.block.UncleRef ref : uncles) {
            if (uncleReward > 0) {
                deposit(ledger, applied, ref.miner(), new TransactionAmount(uncleReward));
            }
            if (nephewReward > 0) {
                deposit(ledger, applied, miner, new TransactionAmount(nephewReward));
            }
        }
    }

    /** Rolls back applied ledger ops and discards the contract/box/token sessions, then returns the status. */
    private static ExecutionStatus abort(ContractProcessor processor, BoxProcessor boxProcessor,
                                         TokenProcessor tokenProcessor, Ledger ledger,
                                         List<AppliedOp> applied, ExecutionStatus status) {
        rollback(ledger, applied);
        if (processor != null) {
            processor.discard();
        }
        if (boxProcessor != null) {
            boxProcessor.discard();
        }
        if (tokenProcessor != null) {
            tokenProcessor.discard();
        }
        return status;
    }

    /**
     * Runs one native-token transaction. The token processor validates and stages the
     * token-state change (no ledger effect); this method moves only the fee to the miner.
     * A non-SUCCESS token status invalidates the block.
     */
    private static ExecutionStatus applyToken(TransactionImpl tx, Ledger ledger, List<AppliedOp> applied,
                                              PublicAddress miner, TokenProcessor tokenProcessor, long height) {
        TokenProcessor.TokenResult result =
            tokenProcessor.run(tx.kind(), tx.from(), tx.to(), tx.nonce(), tx.data(), height);
        if (!result.success()) {
            return result.status();
        }
        long fee = tx.fee().amount();
        if (fee > 0) {
            if (!ledger.hasWallet(tx.from())) {
                return SENDER_DOES_NOT_EXIST;
            }
            if (ledger.getWalletValue(tx.from()).amount() < fee) {
                return BALANCE_TOO_LOW;
            }
            withdraw(ledger, applied, tx.from(), new TransactionAmount(fee));
            deposit(ledger, applied, miner, new TransactionAmount(fee));
        }
        return SUCCESS;
    }

    /**
     * Runs one box transaction. The box processor validates and stages the box-state
     * change (no ledger access); this method then moves value: the fee to the miner,
     * the locked value out of the sender (CREATE/UPDATE) or the released value back to
     * the sender (SPEND/COLLECT). Unlike a contract revert, a non-SUCCESS box status
     * invalidates the block.
     */
    private static ExecutionStatus applyBox(TransactionImpl tx, Ledger ledger, List<AppliedOp> applied,
                                            PublicAddress miner, BoxProcessor boxProcessor, long height) {
        long amount = tx.amount().amount();
        long fee = tx.fee().amount();
        BoxProcessor.BoxResult result =
            boxProcessor.run(tx.kind(), tx.from(), tx.to(), amount, tx.nonce(), tx.data(), height);
        if (!result.success()) {
            return result.status();
        }
        long debit;
        try {
            debit = Math.addExact(fee, result.debitFrom());
        } catch (ArithmeticException e) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        // Only a positive debit needs a funded sender; a rent collector taking value out
        // of a box (debit 0) may have no wallet yet — the deposit below creates it.
        if (debit > 0) {
            if (!ledger.hasWallet(tx.from())) {
                return SENDER_DOES_NOT_EXIST;
            }
            if (ledger.getWalletValue(tx.from()).amount() < debit) {
                return BALANCE_TOO_LOW;
            }
            withdraw(ledger, applied, tx.from(), new TransactionAmount(debit));
        }
        if (fee > 0) {
            deposit(ledger, applied, miner, new TransactionAmount(fee));
        }
        // Released value goes to the box owner on a SPEND (the signer, tx.from), or to the
        // collector named in a permissionless BOX_COLLECT (tx.to, whose from is empty).
        if (result.creditFrom() > 0) {
            PublicAddress creditTarget = boxCreditTarget(tx);
            deposit(ledger, applied, creditTarget, new TransactionAmount(result.creditFrom()));
        }
        return SUCCESS;
    }

    /** Who receives value released by a box op: the collector for BOX_COLLECT, else the sender. */
    private static PublicAddress boxCreditTarget(TransactionImpl tx) {
        return tx.kind() == rhizome.core.transaction.TransactionKind.BOX_COLLECT ? tx.to() : tx.from();
    }

    /**
     * Runs one contract transaction. Gas is always charged to the miner (even on a
     * revert — the work was done); the attached value moves to the contract only on
     * success; the contract's state writes live in the processor's block session.
     *
     * <p>Returns SUCCESS for both a successful and a reverted call (a revert does not
     * invalidate the block, Ethereum-style). A non-SUCCESS status means the
     * transaction could not be afforded or applied and the block is invalid.
     */
    private static ExecutionStatus applyContract(TransactionImpl tx, Ledger ledger,
                                                 List<AppliedOp> applied, PublicAddress miner,
                                                 ContractProcessor processor) {
        long value = tx.amount().amount();
        long gasLimit = tx.gasLimit();
        long gasPrice = tx.gasPrice();
        if (value < 0 || gasLimit < 0 || gasPrice < 0) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        long required;
        try {
            required = Math.addExact(value, Math.multiplyExact(gasLimit, gasPrice));
        } catch (ArithmeticException e) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        if (!ledger.hasWallet(tx.from())) {
            return SENDER_DOES_NOT_EXIST;
        }
        if (ledger.getWalletValue(tx.from()).amount() < required) {
            return BALANCE_TOO_LOW;
        }

        ContractProcessor.ContractResult result =
            processor.run(tx.from(), tx.kind(), tx.to(), tx.data(), value, gasLimit, tx.nonce());

        long gasFee = Math.multiplyExact(result.gasUsed(), gasPrice);
        if (gasFee > 0) {
            withdraw(ledger, applied, tx.from(), new TransactionAmount(gasFee));
            deposit(ledger, applied, miner, new TransactionAmount(gasFee));
        }
        if (result.success() && value > 0) {
            PublicAddress target = result.contractAddress() != null ? result.contractAddress() : tx.to();
            withdraw(ledger, applied, tx.from(), new TransactionAmount(value));
            deposit(ledger, applied, target, new TransactionAmount(value));
        }
        return SUCCESS;
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
    public static void rollbackBlock(Block block, Ledger ledger, ContractProcessor processor,
                                     long height, NetworkParameters params) {
        rollbackBlock(block, ledger, processor, null, height, params);
    }

    /** As above, also reversing the block's box transactions via the {@link BoxProcessor}. */
    public static void rollbackBlock(Block block, Ledger ledger, ContractProcessor processor,
                                     BoxProcessor boxProcessor, long height, NetworkParameters params) {
        List<Transaction> transactions = block.transactions();
        Transaction coinbase = transactions.stream()
            .filter(t -> ((TransactionImpl) t).isTransactionFee())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Block has no coinbase"));
        PublicAddress miner = ((TransactionImpl) coinbase).to();

        // Runtime receipts (gas used, success) for this block's contract txs, in block
        // order; consumed in reverse as we walk transactions backwards.
        List<ContractProcessor.ContractReceipt> receipts =
            processor != null ? processor.receipts(height) : List.of();
        int ri = receipts.size() - 1;
        // Box receipts (ledger deltas) for this block's box txs, consumed in reverse.
        List<BoxProcessor.BoxReceipt> boxReceipts =
            boxProcessor != null ? boxProcessor.receipts(height) : List.of();
        int bi = boxReceipts.size() - 1;

        // Inverse of payUncleRewards: same flat amounts, derived from the committed refs.
        List<rhizome.core.block.UncleRef> uncles = ((BlockImpl) block).uncles();
        if (!uncles.isEmpty()) {
            long uncleReward = params.uncleReward(height);
            long nephewReward = params.nephewReward(height);
            for (rhizome.core.block.UncleRef ref : uncles) {
                if (nephewReward > 0) {
                    ledger.revertDeposit(miner, new TransactionAmount(nephewReward));
                }
                if (uncleReward > 0) {
                    ledger.revertDeposit(ref.miner(), new TransactionAmount(uncleReward));
                }
            }
        }
        ledger.revertDeposit(miner, ((TransactionImpl) coinbase).amount());
        for (int i = transactions.size() - 1; i >= 0; i--) {
            var tx = (TransactionImpl) transactions.get(i);
            if (tx.isTransactionFee()) {
                continue;
            }
            if (tx.kind().isContract()) {
                revertContract(ledger, tx, receipts.get(ri--), miner);
                continue;
            }
            if (tx.kind().isBox()) {
                revertBox(ledger, tx, boxReceipts.get(bi--), miner);
                continue;
            }
            if (tx.kind().isToken()) {
                revertToken(ledger, tx, miner);
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

    /** Inverse of {@link #applyContract}'s ledger effects, using the block's receipt. */
    private static void revertContract(Ledger ledger, TransactionImpl tx,
                                       ContractProcessor.ContractReceipt receipt, PublicAddress miner) {
        long gasFee = Math.multiplyExact(receipt.gasUsed(), tx.gasPrice());
        if (gasFee > 0) {
            ledger.revertDeposit(miner, new TransactionAmount(gasFee));
            ledger.revertSend(tx.from(), new TransactionAmount(gasFee));
        }
        long value = tx.amount().amount();
        if (receipt.success() && value > 0) {
            PublicAddress target = tx.kind() == rhizome.core.transaction.TransactionKind.DEPLOY
                ? Contracts.deriveAddress(tx.from(), tx.nonce()) : tx.to();
            ledger.revertDeposit(target, new TransactionAmount(value));
            ledger.revertSend(tx.from(), new TransactionAmount(value));
        }
    }

    /** Inverse of {@link #applyBox}'s ledger effects, using the block's box receipt. */
    private static void revertBox(Ledger ledger, TransactionImpl tx,
                                  BoxProcessor.BoxReceipt receipt, PublicAddress miner) {
        long fee = tx.fee().amount();
        long debit = fee + receipt.debitFrom();
        if (receipt.creditFrom() > 0) {
            ledger.revertDeposit(boxCreditTarget(tx), new TransactionAmount(receipt.creditFrom()));
        }
        if (fee > 0) {
            ledger.revertDeposit(miner, new TransactionAmount(fee));
        }
        if (debit > 0) {
            ledger.revertSend(tx.from(), new TransactionAmount(debit));
        }
    }

    /** Inverse of {@link #applyToken}'s ledger effects: a token op moves only the fee. */
    private static void revertToken(Ledger ledger, TransactionImpl tx, PublicAddress miner) {
        long fee = tx.fee().amount();
        if (fee > 0) {
            ledger.revertDeposit(miner, new TransactionAmount(fee));
            ledger.revertSend(tx.from(), new TransactionAmount(fee));
        }
    }
}
