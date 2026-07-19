package rhizome.vm;

/**
 * Outcome of a contract execution: whether it succeeded, its return data, and
 * the gas consumed (always charged, even on failure — the caller pays for the
 * work done before the fault).
 */
public record ExecResult(Status status, byte[] output, long gasUsed, String message) {

    public enum Status { OK, REVERTED, OUT_OF_GAS }

    public static ExecResult ok(byte[] output, long gasUsed) {
        return new ExecResult(Status.OK, output, gasUsed, null);
    }

    public static ExecResult reverted(long gasUsed, String message) {
        return new ExecResult(Status.REVERTED, new byte[0], gasUsed, message);
    }

    public static ExecResult outOfGas(long gasUsed) {
        return new ExecResult(Status.OUT_OF_GAS, new byte[0], gasUsed, "out of gas");
    }

    public boolean succeeded() {
        return status == Status.OK;
    }
}
