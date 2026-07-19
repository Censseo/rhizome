package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Drives a real Rust-compiled WASM contract (contracts/counter.rs -> counter.wasm)
 * through the VM: it reads a counter from storage, increments it, writes it back,
 * and returns it. Proves the whole M1 slice — module load, host ABI (storage +
 * output), deterministic gas, and state that persists across calls.
 */
class WasmVmTest {

    private static final byte[] COUNTER = load("/counter.wasm");
    private final WasmVm vm = new WasmVm();

    private static byte[] load(String resource) {
        try (var in = WasmVmTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (v >>> (8 * i));
        }
        return b;
    }

    @Test
    void runsContractAndIncrementsPersistentStorage() {
        Map<String, byte[]> storage = new HashMap<>();
        byte[] caller = "agent".getBytes(StandardCharsets.UTF_8);

        ExecResult first = vm.execute(COUNTER, new MapHostState(storage, caller, new byte[0], 0), new GasMeter(10_000_000));
        assertEquals(ExecResult.Status.OK, first.status());
        assertArrayEquals(le64(1), first.output());
        assertTrue(first.gasUsed() > 0, "execution should cost gas");

        // Same storage map -> the counter persists and advances.
        ExecResult second = vm.execute(COUNTER, new MapHostState(storage, caller, new byte[0], 0), new GasMeter(10_000_000));
        assertEquals(ExecResult.Status.OK, second.status());
        assertArrayEquals(le64(2), second.output());
    }

    @Test
    void outOfGasIsReportedNotHung() {
        // A budget far too small to finish: the per-instruction meter must abort
        // deterministically instead of running (or hanging) to completion.
        ExecResult r = vm.execute(COUNTER, new MapHostState(new byte[0], new byte[0], 0), new GasMeter(50));
        assertEquals(ExecResult.Status.OUT_OF_GAS, r.status());
        assertEquals(50, r.gasUsed());
    }
}
