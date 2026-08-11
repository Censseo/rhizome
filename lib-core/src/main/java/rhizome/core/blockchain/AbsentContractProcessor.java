package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

/**
 * The contract domain on a node that has no VM wired: {@link ContractProcessor#NONE}.
 *
 * <p>Stands in for a null processor so {@code ChainEngine} and {@code Executor} carry no null
 * checks. Absent is not permissive — {@code Executor}'s first pass still rejects a contract
 * transaction with {@code CONTRACT_EXECUTION_UNAVAILABLE} by testing {@link #available()}, so
 * {@link #run} is unreachable in a correct build and says so loudly if it ever is not. Every other
 * method returns exactly what the null guard it replaces returned.
 *
 * <p>Stateless by construction: no fields, so the singleton is safe to initialize at build time
 * under GraalVM without any reachability metadata.
 */
final class AbsentContractProcessor implements ContractProcessor {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void begin() {
        // no session to open
    }

    @Override
    public void commit(long blockHeight) {
        // no state to persist
    }

    @Override
    public void discard() {
        // no session to drop
    }

    @Override
    public void revertBlock(long blockHeight) {
        // nothing was ever committed for this height
    }

    @Override
    public ContractResult run(PublicAddress from, TransactionKind kind, PublicAddress to,
                              byte[] data, long value, long gasLimit, long nonce) {
        throw new IllegalStateException(
            "no contract processor is wired: a contract transaction must have been rejected with "
            + "CONTRACT_EXECUTION_UNAVAILABLE in Executor's first pass and cannot reach the second");
    }

    @Override
    public List<ContractReceipt> receipts(long blockHeight) {
        return List.of();
    }
}
