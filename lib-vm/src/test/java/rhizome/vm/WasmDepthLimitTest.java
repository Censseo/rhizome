package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the deterministic contract-VM hardening (audit C2 / H4):
 * unbounded WASM recursion must trap at a fixed depth on every node instead of overflowing the
 * JVM stack (which forks consensus), and oversized code must be refused at deploy.
 */
class WasmDepthLimitTest {

    private final WasmVm vm = new WasmVm();

    /**
     * A hand-assembled module whose exported {@code call} invokes itself unconditionally:
     * {@code (func $call (call $call)) (export "call" (func $call))} — infinite WASM recursion.
     */
    private static final byte[] RECURSIVE = {
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00, // magic + version
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,             // type: () -> ()
        0x03, 0x02, 0x01, 0x00,                         // func: one function of type 0
        0x07, 0x08, 0x01, 0x04, 0x63, 0x61, 0x6C, 0x6C, 0x00, 0x00, // export "call" -> func 0
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x10, 0x00, 0x0B  // code: (call 0) end
    };

    @Test
    void deepRecursionRevertsDeterministicallyInsteadOfCrashing() {
        // Runs on the fixed-stack execution thread, so the deterministic depth cap fires before any
        // JVM StackOverflowError. Generous gas so the cap — not gas — is what stops it.
        ExecResult r = WasmVm.onBoundedStack(() ->
            vm.execute(RECURSIVE, new MapHostState(new byte[0], new byte[0], 0), new GasMeter(50_000_000)));
        assertEquals(ExecResult.Status.REVERTED, r.status());
        assertTrue(r.message() != null && r.message().contains("depth"),
            "expected a call-depth revert, got: " + r.message());
    }

    @Test
    void oversizedContractCodeRejectedAtDeploy() {
        byte[] tooBig = new byte[WasmVm.MAX_CODE_SIZE + 1];
        assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(tooBig));
    }
}
