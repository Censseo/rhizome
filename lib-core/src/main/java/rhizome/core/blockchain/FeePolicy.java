package rhizome.core.blockchain;

import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionKind;

/**
 * The fee and admission arithmetic shared by the executor and the mempool.
 *
 * <p>The two sites kept their own copies of the miner-revenue formula and of the fee-floor
 * condition, held in agreement only by a comment ("the SAME rule MemPool.addTransaction
 * applies"). The executor applies the floor as a consensus rule from the consensus-V2
 * activation height on, the mempool as a relay rule at admission — but the rule itself must
 * be one expression, or a boundary transaction (fee exactly at the floor, a contract call
 * whose gas budget crosses it) drifts: the pool would relay work the chain rejects, or drop
 * work the chain accepts. {@code AdmissionParityTest} pins the agreement on the boundaries.
 *
 * <p>What the two call sites deliberately keep to themselves is WHEN the floor is active:
 * the executor judges the block's own height ({@code consensusV2}), the mempool applies it
 * unconditionally — stricter below the activation, which is the safe direction (a pooled
 * under-floor transaction would poison every candidate block once the activation lands).
 */
public final class FeePolicy {

    private FeePolicy() {
    }

    /**
     * The revenue a miner can earn from {@code tx}: the plain fee for value/box/token ops; for
     * a contract call the fee plus its declared gas budget ({@code gasLimit × gasPrice},
     * saturating) — an upper bound on the realized {@code gasUsed × gasPrice}, deterministic at
     * block-assembly time when gasUsed is still unknown. Also the mempool's metric for the
     * admission floor and the RBF bump (NOT for selection ordering, see MemPool.priorityRate).
     */
    public static long minerRevenue(Transaction tx) {
        if (!tx.kind().isContract()) {
            return tx.fee().amount();
        }
        try {
            return Math.addExact(tx.fee().amount(), Math.multiplyExact(tx.gasLimit(), tx.gasPrice()));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Whether the minimum-fee floor rejects {@code tx}. A network with the floor at 0 (the
     * testnet default) disables it. {@link TransactionKind#BOX_COLLECT} is exempt: it is
     * self-authorized, minted by the block producer (never pooled), and must carry fee 0 by
     * the executor's payload rule. Contract calls pay through gas, not the fee field, so their
     * declared gas budget counts toward the floor.
     */
    public static boolean underMinFee(NetworkParameters params, Transaction tx) {
        return params.minFee() > 0
            && tx.kind() != TransactionKind.BOX_COLLECT
            && minerRevenue(tx) < params.minFee();
    }
}
