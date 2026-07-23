package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

/**
 * Adversarial hostile-module tests for the VM's load-bearing determinism / resource controls, each of
 * which previously had no negative test (audit coverage gaps + findings S2/S5):
 *
 * <ul>
 *   <li>{@code rejectNonDeterministic} — the float/SIMD scan that prevents a cross-runtime NaN/rounding
 *       fork (whitepaper §7.2, audit V6j). A fragile uppercased-substring match with zero prior tests.</li>
 *   <li>Table growth ceiling — an unbounded/oversized {@code max} could {@code table.grow} into a
 *       heap-dependent OOM → divergent {@code gasUsed} → state-root fork (audit S2).</li>
 *   <li>{@code transfer_value} gas — must be charged per byte of the contract-controlled length, like
 *       every other buffer-reading host call, not a flat {@code CALL_BASE} (audit S5).</li>
 * </ul>
 *
 * Modules are hand-assembled from raw bytes (the same approach as the table/locals tests in
 * {@link WasmVmTest}) so no toolchain is needed.
 */
class WasmAdversarialTest {

    private final WasmVm vm = new WasmVm();

    // ---- minimal WASM byte assembler ----

    private static final byte[] MAGIC = {0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00};

    private static void uleb(ByteArrayOutputStream out, long v) {
        do {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (v != 0);
    }

    private static void sleb(ByteArrayOutputStream out, long v) {
        boolean more = true;
        while (more) {
            int b = (int) (v & 0x7F);
            v >>= 7;
            if ((v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)) {
                more = false;
            } else {
                b |= 0x80;
            }
            out.write(b);
        }
    }

    private static byte[] section(int id, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id);
        uleb(out, body.length);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] module(byte[]... sections) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        for (byte[] s : sections) {
            out.writeBytes(s);
        }
        return out.toByteArray();
    }

    private static byte[] bytes(int... vals) {
        byte[] b = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) {
            b[i] = (byte) vals[i];
        }
        return b;
    }

    // ---- float / SIMD rejection ----

    @Test
    void rejectsAModuleUsingAFloatOpcode() {
        // One function ()->() whose body pushes an f64 and drops it. f64.const is opcode 0x44, whose
        // Chicory OpCode name (F64_CONST) contains "F64" — the scan must refuse it. Without this control
        // a contract doing float maths could leave two nodes on different runtime builds with divergent
        // NaN payloads / rounding → consensus fork. A regression in the substring match would silently
        // re-admit the whole class; this is the test that catches it.
        byte[] type = section(1, bytes(0x01, 0x60, 0x00, 0x00));    // 1 type: () -> ()
        byte[] func = section(3, bytes(0x01, 0x00));                // 1 function, type 0
        // body: 0 locals, f64.const 0.0 (8 zero bytes), drop, end
        byte[] body = bytes(0x00, 0x44, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1A, 0x0B);
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x01);                                          // 1 function body
        uleb(code, body.length);
        code.writeBytes(body);
        byte[] mod = module(type, func, section(10, code.toByteArray()));

        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().toLowerCase().contains("non-deterministic")
            || ex.getMessage().toLowerCase().contains("float"), ex.getMessage());
    }

    // ---- table growth ceiling (S2) ----

    @Test
    void rejectsATableWhoseUnboundedMaxCouldGrowUnmetered() {
        // A funcref table with limits flag 0x00 (min only, min=1): Chicory treats the absent max as
        // LIMIT_MAX = 10,000,000. table.grow is metered by gas only, so this could grow into a
        // heap-dependent OOM → gasUsed fork. The deploy-time ceiling cap must refuse it.
        byte[] table = section(4, bytes(0x01, 0x70, 0x00, 0x01)); // 1 table, funcref, min-only, min=1
        byte[] mod = module(table);
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("table"), ex.getMessage());
    }

    @Test
    void rejectsATableDeclaringAnOversizedExplicitMax() {
        // Explicit limits flag 0x01, min=1, max=100000 (LEB128 A0 8D 06) — above MAX_TABLE_ENTRIES.
        byte[] table = section(4, bytes(0x01, 0x70, 0x01, 0x01, 0xA0, 0x8D, 0x06));
        byte[] mod = module(table);
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("table"), ex.getMessage());
    }

    // ---- transfer_value per-byte gas (S5) ----

    /** Builds a module whose {@code call} export invokes {@code transfer_value(0, toLen, 0)} once. */
    private byte[] transferValueModule(int toLen) {
        // type 0: (i32,i32,i64) -> i32  (transfer_value);  type 1: () -> ()  (call)
        byte[] type = section(1, bytes(0x02,
            0x60, 0x03, 0x7F, 0x7F, 0x7E, 0x01, 0x7F,
            0x60, 0x00, 0x00));
        // import env.transfer_value : type 0
        ByteArrayOutputStream imp = new ByteArrayOutputStream();
        imp.write(0x01);                                          // 1 import
        imp.write(0x03); imp.writeBytes("env".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        imp.write(0x0E); imp.writeBytes("transfer_value".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        imp.write(0x00); imp.write(0x00);                         // func import, type index 0
        byte[] importSec = section(2, imp.toByteArray());
        byte[] func = section(3, bytes(0x01, 0x01));              // 1 function, type 1
        byte[] mem = section(5, bytes(0x01, 0x00, 0x01));         // 1 memory, min 1 page
        byte[] export = section(7, bytes(0x01, 0x04, 0x63, 0x61, 0x6C, 0x6C, 0x00, 0x01)); // "call" -> func 1
        // body: 0 locals; i32.const 0; i32.const toLen; i64.const 0; call 0; drop; end
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x00);
        body.write(0x41); sleb(body, 0);
        body.write(0x41); sleb(body, toLen);
        body.write(0x42); sleb(body, 0);
        body.write(0x10); uleb(body, 0);                         // call func index 0 (transfer_value)
        body.write(0x1A);                                        // drop the i32 result
        body.write(0x0B);                                        // end
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x01);
        uleb(code, body.size());
        code.writeBytes(body.toByteArray());
        return module(type, importSec, func, mem, export, section(10, code.toByteArray()));
    }

    @Test
    void transferValueGasScalesWithTheReadLengthNotFlat() {
        // The default host transferValue returns -1 (no ledger), so both runs complete OK; the only
        // gas difference is the per-byte charge for the contract-controlled `to` length. Before the
        // S5 fix transfer_value charged a flat CALL_BASE, so this difference was 0 and a contract could
        // force ~64 MiB reads for 500 gas. It must now scale by (bigLen - smallLen) * PER_BYTE.
        // Both lengths encode to a 2-byte LEB128 i32.const operand, so the two modules are byte-for-byte
        // identical in length — the O(code) module-parse charge (MODULE_PARSE_PER_BYTE) is therefore
        // identical and cancels out of the delta, isolating the transfer_value per-byte charge.
        int smallLen = 1000;
        int bigLen = 8000;
        byte[] smallMod = transferValueModule(smallLen);
        byte[] bigMod = transferValueModule(bigLen);
        assertEquals(smallMod.length, bigMod.length,
            "modules must be equal length so the parse charge cancels out of the gas delta");
        WasmVm.clearModuleCacheForTest();
        ExecResult small = vm.execute(smallMod,
            new MapHostState(new byte[0], new byte[0], 0), new GasMeter(10_000_000));
        ExecResult big = vm.execute(bigMod,
            new MapHostState(new byte[0], new byte[0], 0), new GasMeter(10_000_000));

        assertEquals(ExecResult.Status.OK, small.status(), "small-length call should complete");
        assertEquals(ExecResult.Status.OK, big.status(), "big-length call should complete");
        // Both modules are byte-identical except the i32.const operand (same 1-gas instruction), so the
        // whole gasUsed delta is the transfer_value per-byte charge.
        assertEquals((long) (bigLen - smallLen) * GasSchedule.PER_BYTE, big.gasUsed() - small.gasUsed(),
            "transfer_value must charge PER_BYTE of the read length (audit S5)");
    }
}
