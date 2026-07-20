package rhizome.vm;

import java.util.List;

/**
 * Outcome of a contract execution: whether it succeeded, its return data, the
 * event logs it emitted, and the gas consumed (always charged, even on failure —
 * the caller pays for the work done before the fault). Logs are kept only on
 * success; a reverted or out-of-gas call emits none.
 */
public record ExecResult(Status status, byte[] output, List<LogEntry> logs, long gasUsed, String message) {

    public enum Status { OK, REVERTED, OUT_OF_GAS }

    public static ExecResult ok(byte[] output, List<LogEntry> logs, long gasUsed) {
        return new ExecResult(Status.OK, output, List.copyOf(logs), gasUsed, null);
    }

    public static ExecResult reverted(long gasUsed, String message) {
        return new ExecResult(Status.REVERTED, new byte[0], List.of(), gasUsed, message);
    }

    public static ExecResult outOfGas(long gasUsed) {
        return new ExecResult(Status.OUT_OF_GAS, new byte[0], List.of(), gasUsed, "out of gas");
    }

    public boolean succeeded() {
        return status == Status.OK;
    }
}
