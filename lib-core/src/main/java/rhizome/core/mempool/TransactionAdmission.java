package rhizome.core.mempool;

import java.util.function.LongSupplier;

import rhizome.core.blockchain.FeePolicy;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.transaction.Transaction;

import static rhizome.core.mempool.ExecutionStatus.*;

/**
 * The transaction pool's structural admission gates, as a pure function of the transaction and
 * the confirmed state — every check that runs BEFORE the pool lock (extracted from MemPool,
 * archi-review lot L22, constat 19). No pool state is read here, so the verdict is stable and
 * the caller can run it without holding the pool lock.
 *
 * <p>The verdicts mirror the consensus gates the block executor applies — chainId, negative
 * amounts, the minimum-fee floor, the gas-field rules and the box/token activation heights are
 * the same rules {@code Executor.executeBlock} enforces (pinned by {@code AdmissionParityTest}).
 * That parity is the whole point of this class: a transaction admitted here but rejected by the
 * executor would be selected into every candidate block and halt production network-wide, so the
 * pool must never be more permissive than the chain.
 */
final class TransactionAdmission {

    /** How far past the local clock a pooled transaction's timestamp may lie (audit B-4). */
    static final long MAX_TX_FUTURE_DRIFT_MS = 2 * 60 * 60 * 1000L;

    private TransactionAdmission() {
    }

    /**
     * Runs the structural gates in the pool's own cheapest-first order: coinbase exclusion,
     * chainId, negative amounts, the optional minimum-fee floor, the gas-field rules for the
     * contract/box/token kinds, the activation-height gates and the timestamp sanity window.
     * Returns {@code SUCCESS} when the transaction is a candidate for the pool's stateful
     * checks (deduplication, replace-by-fee, cumulative balance, signature, capacity), which
     * run under the pool lock.
     */
    static ExecutionStatus check(NetworkParameters params, AccountView accounts,
                                 LongSupplier clock, Transaction transaction) {
        Transaction tx = transaction;
        if (tx.isTransactionFee()) {
            return INVALID_TRANSACTION_NONCE; // coinbase is minted in blocks, never pooled
        }
        if (tx.kind() == rhizome.core.transaction.TransactionKind.BOX_COLLECT) {
            return INVALID_TRANSACTION_NONCE; // rent collection is minted in blocks, never pooled
        }
        if (tx.chainId() != params.chainId()) {
            return INVALID_CHAIN_ID;
        }
        if (tx.amount().amount() < 0 || tx.fee().amount() < 0) {
            return INVALID_TRANSACTION_AMOUNT; // negative would mint money / force negative balances
        }
        // Optional minimum-fee floor (0 = disabled, the default on testnet), so an operator can
        // refuse free transactions at admission rather than have the pool fill with zero-fee spam
        // (audit L5). The rule itself is one expression, shared with the executor —
        // FeePolicy.underMinFee, the consensus copy gated on consensusV2Height; the pool applies
        // it unconditionally, which is the stricter, safe direction below the activation (a
        // pooled under-floor transaction would poison every candidate block once it lands).
        // Contract calls pay through gas, not the fee field, so their declared gas
        // budget counts toward the floor — its full value is already locked by the cumulative
        // balance check below, and their realized revenue is bounded below by the intrinsic CALL
        // gas charge, so a zero-fee, zero-gasPrice call can never sneak past a positive floor.
        if (FeePolicy.underMinFee(params, tx)) {
            return TRANSACTION_FEE_TOO_LOW;
        }
        if (tx.kind().isContract() && (tx.gasLimit() < 0 || tx.gasPrice() < 0)) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        // Mirror the consensus per-transaction gas ceiling at admission so an over-cap call is never
        // pooled or relayed (it would be rejected by executeBlock anyway). Defense in depth against the
        // unbounded-consensus-gas vector; the block-wide ceiling stays a consensus-only rule.
        if (tx.kind().isContract() && params.maxTxGas() > 0 && tx.gasLimit() > params.maxTxGas()) {
            return GAS_LIMIT_EXCEEDED;
        }
        // Box ops run no VM and cost no gas; the gas fields are reserved and must be zero.
        if (tx.kind().isBox() && (tx.gasLimit() != 0 || tx.gasPrice() != 0)) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        // Token ops carry no gas and move no PDN (the token amount is in the payload).
        if (tx.kind().isToken() && (tx.gasLimit() != 0 || tx.gasPrice() != 0 || tx.amount().amount() != 0)) {
            return INVALID_TRANSACTION_AMOUNT;
        }
        // Activation-height gate, mirrored from the executor. A box/token tx is only valid in a block at
        // or above its activation height; the executor otherwise hard-fails such a tx (BOX_UNAVAILABLE /
        // TOKEN_UNAVAILABLE), which aborts the WHOLE block. Without this gate a pre-activation box/token
        // tx would be admitted, selected into every candidate block, and halt production until activation.
        // Reject it here so it never enters the pool. The predicate judges the NEXT block and compares
        // subtractively on purpose — see NetworkParameters.boxActiveForNextBlock for the Long.MAX_VALUE
        // sentinel that forbids a +1 form.
        long confirmedHeight = accounts.confirmedHeight();
        if (tx.kind().isBox() && !params.boxActiveForNextBlock(confirmedHeight)) {
            return BOX_UNAVAILABLE;
        }
        if (tx.kind().isToken() && !params.tokenActiveForNextBlock(confirmedHeight)) {
            return TOKEN_UNAVAILABLE;
        }
        if (!tx.senderBindingValid()) {
            return WALLET_SIGNATURE_MISMATCH;
        }
        // Timestamp sanity window: the field is signed but otherwise unconstrained, so a tx
        // stamped far in the future could sit in the pool as permanently "fresh" junk with no
        // consensus rule ever catching it (the signed field was inert — audit B-4). A generous
        // 2-hour future drift absorbs honest clock skew; negative timestamps are malformed.
        long txTimestamp = tx.timestamp();
        if (txTimestamp < 0 || txTimestamp > clock.getAsLong() + MAX_TX_FUTURE_DRIFT_MS) {
            return INVALID_TRANSACTION_TIMESTAMP;
        }
        return SUCCESS;
    }
}
