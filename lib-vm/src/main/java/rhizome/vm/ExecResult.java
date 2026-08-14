package rhizome.vm;

import java.util.List;

import rhizome.core.blockchain.ContractProcessor.ContractResult;

/**
 * Outcome of a contract execution: whether it succeeded, its return data, the
 * event logs it emitted, and the gas consumed (always charged, even on failure —
 * the caller pays for the work done before the fault). Logs are kept only on
 * success; a reverted or out-of-gas call emits none.
 *
 * <p>The three-state status is the consensus boundary's own
 * ({@link ContractResult.Status}): the VM reports its failure through the same
 * vocabulary the ledger sees, so the processor never has to translate between
 * two enums that mean the same thing.
 */
public record ExecResult(ContractResult.Status status, byte[] output, List<LogEntry> logs, long gasUsed, String message) {

    public static ExecResult ok(byte[] output, List<LogEntry> logs, long gasUsed) {
        return new ExecResult(ContractResult.Status.OK, output, List.copyOf(logs), gasUsed, null);
    }

    public static ExecResult reverted(long gasUsed, String message) {
        return new ExecResult(ContractResult.Status.REVERTED, new byte[0], List.of(), gasUsed, message);
    }

    public static ExecResult outOfGas(long gasUsed) {
        return new ExecResult(ContractResult.Status.OUT_OF_GAS, new byte[0], List.of(), gasUsed, "out of gas");
    }

    public boolean succeeded() {
        return status == ContractResult.Status.OK;
    }
}
