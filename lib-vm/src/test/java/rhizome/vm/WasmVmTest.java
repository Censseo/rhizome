package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private static final byte[] EMITTER = load("/emitter.wasm");
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
    void contractEmitsEventLog() {
        Map<String, byte[]> storage = new HashMap<>();
        byte[] caller = "agent".getBytes(StandardCharsets.UTF_8);

        ExecResult r = vm.execute(EMITTER, new MapHostState(storage, caller, new byte[0], 0), new GasMeter(10_000_000));
        assertEquals(ExecResult.Status.OK, r.status());
        assertEquals(1, r.logs().size());
        LogEntry log = r.logs().get(0);
        assertArrayEquals("count".getBytes(StandardCharsets.UTF_8), log.topic());
        assertArrayEquals(le64(1), log.data()); // the new counter value rode along in the log
    }

    @Test
    void revertedCallEmitsNoLogs() {
        // Too little gas to finish: the emitted log must not survive a non-OK execution.
        ExecResult r = vm.execute(EMITTER, new MapHostState(new byte[0], new byte[0], 0), new GasMeter(50));
        assertTrue(r.logs().isEmpty());
    }

    @Test
    void rejectsModuleDeclaringAnOversizedTable() {
        // A 100,000-entry table is accepted by Chicory (its own cap is 10M) but forces an eager,
        // unmetered ~0.8 MiB reference array at instantiation; our tighter deploy-time cap refuses it
        // so the allocation stays a small deterministic network constant (audit H4). 100000 = LEB128
        // A0 8D 06.
        byte[] mod = new byte[] {
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,          // wasm header
            0x04, 0x06, 0x01, 0x70, 0x00,                            // table section: 1 table, funcref, min-only
            (byte) 0xA0, (byte) 0x8D, 0x06                           // min = 100,000 (LEB128)
        };
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("table"), ex.getMessage());
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
