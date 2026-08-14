package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.ledger.PublicAddress;
import rhizome.core.transaction.TransactionKind;

/**
 * Runs contract transactions for the {@link Executor}, without touching the ledger
 * (the executor applies the gas fee and value transfer via its own rolled-back
 * ledger ops). Implemented in the VM module, so consensus depends only on this
 * interface — never on the WASM runtime.
 *
 * <p>Contract state writes are staged in a per-block <em>session</em>: {@link
 * BlockStateProcessor#begin()} opens it, {@link #run} accumulates the successful calls'
 * writes, and the executor ends the block with exactly one of {@link
 * BlockStateProcessor#commit(long)} (block accepted) or {@link BlockStateProcessor#discard()}
 * (block rejected), so contract state moves atomically with the block. The session lifecycle
 * is declared once, on {@link BlockStateProcessor}.
 *
 * <p>The contract domain has two other audiences, declared apart so consumers depend on
 * exactly what they use: the state-root translation reads committed writes through
 * {@link ContractStateSource}, and the node's dashboard surface (code lookup, event logs,
 * dry runs) reads through {@link ContractApi}. This interface composes both — a concrete
 * processor serves all three audiences, but nothing that only queries has to see execution.
 */
public interface ContractProcessor extends BlockStateProcessor, ContractStateSource, ContractApi {

    /**
     * The contract domain on a node with no VM wired. Replaces a null field, so the engine and the
     * executor need no null checks; a contract transaction is still rejected, by
     * {@link BlockStateProcessor#available()} rather than by a null test.
     */
    ContractProcessor NONE = new AbsentContractProcessor();

    /**
     * Executes one contract transaction against the open session. Must not mutate the
     * ledger. A revert or out-of-gas is reported via {@link ContractResult#success()}
     * (false) with the gas actually consumed — it does not throw.
     *
     * @param nonce the sender's account nonce, used to derive a DEPLOY address
     */
    ContractResult run(PublicAddress from, TransactionKind kind, PublicAddress to,
                       byte[] data, long value, long gasLimit, long nonce);

    /**
     * Per-contract-transaction receipts for {@code blockHeight}, in block order — the
     * runtime facts (gas used, whether it succeeded) the ledger cannot re-derive from
     * the transaction alone, needed to reverse a contract tx's gas fee and value
     * transfer on a reorg. Empty for a height with no contract transactions.
     */
    List<ContractReceipt> receipts(long blockHeight);

    /**
     * A native-coin (PDN) transfer a contract made out of its own balance via the {@code
     * transfer_value} host function — e.g. a launchpad paying its creator the sale proceeds. The
     * VM records the intent (affordability checked live against the contract's committed balance);
     * the executor moves the value on success and reverses it on a reorg.
     */
    record NativeTransfer(PublicAddress from, PublicAddress to, long amount) {}

    /** Reads a contract's committed native balance, so the VM can bound {@code transfer_value}. */
    @FunctionalInterface
    interface NativeBalance {
        long balanceOf(PublicAddress address);
    }

    /**
     * Wires the committed-balance source the VM uses to bound {@code transfer_value} (a contract
     * cannot pay out more native coin than it holds). Called once at engine assembly. Processors
     * with no native-transfer support ignore it.
     */
    default void useNativeBalance(NativeBalance source) { }

    /** Runtime outcome of one contract transaction, recorded for reorg reversal. */
    record ContractReceipt(long gasUsed, boolean success, List<NativeTransfer> transfers) {
        public ContractReceipt(long gasUsed, boolean success) {
            this(gasUsed, success, List.of());
        }
    }

    /**
     * Outcome of one contract execution. {@code gasUsed} is charged regardless of
     * success; {@code contractAddress} is the new address for a DEPLOY (null for CALL);
     * {@code logs} are the events it emitted (empty unless it succeeded).
     *
     * <p>The outcome keeps the VM failure's three states instead of flattening them to a
     * boolean plus an error string: a call that exhausted its budget
     * ({@link Status#OUT_OF_GAS}) is not the same event as a contract choosing to revert
     * ({@link Status#REVERTED}), and the distinction must survive to the boundary without
     * parsing the message. {@code error} remains the human-readable message; the JSON
     * projection of the failure (the dashboard's {@code success}/{@code error} pair) is
     * derived from it and is frozen by {@code DryRunJsonFormTest}.
     */
    record ContractResult(Status status, long gasUsed, byte[] output,
                          PublicAddress contractAddress, String error, List<ContractLog> logs,
                          List<NativeTransfer> transfers) {

        /** The three states of a contract execution outcome, in VM order. */
        public enum Status { OK, REVERTED, OUT_OF_GAS }

        public static ContractResult ok(long gasUsed, byte[] output, PublicAddress contractAddress) {
            return new ContractResult(Status.OK, gasUsed, output, contractAddress, null, List.of(), List.of());
        }

        public static ContractResult ok(long gasUsed, byte[] output, PublicAddress contractAddress,
                                        List<ContractLog> logs) {
            return new ContractResult(Status.OK, gasUsed, output, contractAddress, null,
                List.copyOf(logs), List.of());
        }

        public static ContractResult ok(long gasUsed, byte[] output, PublicAddress contractAddress,
                                        List<ContractLog> logs, List<NativeTransfer> transfers) {
            return new ContractResult(Status.OK, gasUsed, output, contractAddress, null,
                List.copyOf(logs), List.copyOf(transfers));
        }

        public static ContractResult reverted(long gasUsed, String error) {
            return new ContractResult(Status.REVERTED, gasUsed, new byte[0], null, error, List.of(), List.of());
        }

        public static ContractResult outOfGas(long gasUsed, String error) {
            return new ContractResult(Status.OUT_OF_GAS, gasUsed, new byte[0], null, error, List.of(), List.of());
        }

        /** Whether the call completed: the JSON surface's {@code success} projects this. */
        public boolean success() {
            return status == Status.OK;
        }
    }
}
