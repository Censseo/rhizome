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

    // ---- memory.grow per-page gas (audit S-4) ----

    /** Builds a module whose {@code call} export runs {@code memory.grow(pages); drop} once. */
    private byte[] memoryGrowModule(int pages) {
        byte[] type = section(1, bytes(0x01, 0x60, 0x00, 0x00));      // 1 type: () -> ()
        byte[] func = section(3, bytes(0x01, 0x00));                  // 1 function, type 0
        byte[] mem = section(5, bytes(0x01, 0x00, 0x01));             // 1 memory, min 1 page, no max
        byte[] export = section(7, bytes(0x01, 0x04, 0x63, 0x61, 0x6C, 0x6C, 0x00, 0x00)); // "call" -> func 0
        // body: 0 locals; i32.const pages; memory.grow 0; drop; end
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x00);
        body.write(0x41); sleb(body, pages);
        body.write(0x40); body.write(0x00);                          // memory.grow, memory index 0
        body.write(0x1A);                                            // drop the prev-size i32
        body.write(0x0B);                                            // end
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x01);
        uleb(code, body.size());
        code.writeBytes(body.toByteArray());
        return module(type, func, mem, export, section(10, code.toByteArray()));
    }

    @Test
    void memoryGrowGasScalesWithThePageCountNotFlat() {
        // memory.grow does O(pages) heap work, so it must be charged by its runtime page operand — a flat
        // PER_INSTRUCTION would let one instruction claim megabytes for 1 gas (a deterministic memory /
        // GC DoS). Two grows differing only in the page count must differ in gasUsed by exactly
        // (bigPages - smallPages) * MEMORY_PER_PAGE. This is the only test pinning that invariant, which
        // otherwise rests on an untested assumption about Chicory's unsafe-listener operand timing.
        int smallPages = 2;
        int bigPages = 20;                                            // both encode to a 1-byte LEB operand
        byte[] smallMod = memoryGrowModule(smallPages);
        byte[] bigMod = memoryGrowModule(bigPages);
        assertEquals(smallMod.length, bigMod.length,
            "modules must be equal length so the parse charge cancels out of the gas delta");
        WasmVm.clearModuleCacheForTest();
        ExecResult small = vm.execute(smallMod,
            new MapHostState(new byte[0], new byte[0], 0), new GasMeter(10_000_000));
        ExecResult big = vm.execute(bigMod,
            new MapHostState(new byte[0], new byte[0], 0), new GasMeter(10_000_000));

        assertEquals(ExecResult.Status.OK, small.status(), "small grow should complete");
        assertEquals(ExecResult.Status.OK, big.status(), "big grow should complete");
        assertEquals((long) (bigPages - smallPages) * GasSchedule.MEMORY_PER_PAGE,
            big.gasUsed() - small.gasUsed(),
            "memory.grow must charge MEMORY_PER_PAGE per requested page (audit S-4)");
    }

    // ---- call_contract callee-address per-byte gas (F1) ----

    /** Builds a module whose {@code call} export invokes {@code call_contract(0, calleeLen, 0, 0, 0, 0)} once. */
    private byte[] callContractModule(int calleeLen) {
        // type 0: (i32 x 6) -> i32  (call_contract);  type 1: () -> ()  (call)
        byte[] type = section(1, bytes(0x02,
            0x60, 0x06, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x01, 0x7F,
            0x60, 0x00, 0x00));
        // import env.call_contract : type 0
        ByteArrayOutputStream imp = new ByteArrayOutputStream();
        imp.write(0x01);                                          // 1 import
        imp.write(0x03); imp.writeBytes("env".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        imp.write(0x0D); imp.writeBytes("call_contract".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        imp.write(0x00); imp.write(0x00);                         // func import, type index 0
        byte[] importSec = section(2, imp.toByteArray());
        byte[] func = section(3, bytes(0x01, 0x01));              // 1 function, type 1
        byte[] mem = section(5, bytes(0x01, 0x00, 0x01));         // 1 memory, min 1 page
        byte[] export = section(7, bytes(0x01, 0x04, 0x63, 0x61, 0x6C, 0x6C, 0x00, 0x01)); // "call" -> func 1
        // body: 0 locals; i32.const 0 (addr ptr); i32.const calleeLen; i32.const 0 (in ptr);
        //       i32.const 0 (in len); i32.const 0 (out ptr); i32.const 0 (out cap); call 0; drop; end
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x00);
        body.write(0x41); sleb(body, 0);
        body.write(0x41); sleb(body, calleeLen);
        body.write(0x41); sleb(body, 0);
        body.write(0x41); sleb(body, 0);
        body.write(0x41); sleb(body, 0);
        body.write(0x41); sleb(body, 0);
        body.write(0x10); uleb(body, 0);                         // call func index 0 (call_contract)
        body.write(0x1A);                                        // drop the i32 result
        body.write(0x0B);                                        // end
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x01);
        uleb(code, body.size());
        code.writeBytes(body.toByteArray());
        return module(type, importSec, func, mem, export, section(10, code.toByteArray()));
    }

    @Test
    void callContractGasScalesWithTheCalleeAddressLengthNotJustInput() {
        // No call dispatcher is wired (calls == null), so both runs return -1 and complete OK;
        // the only gas difference is the per-byte charge for the contract-controlled callee-address
        // read. Before the F1 fix only inputLen was metered, so this delta was 0 and a contract
        // could force ~64 MiB alloc+copy per call for a flat CALL_BASE. It must now scale by
        // (bigLen - smallLen) * PER_BYTE, exactly like the transfer_value fix (audit S5).
        // Both lengths encode to a 2-byte LEB128 i32.const operand, so the modules are byte-for-byte
        // identical in length and the O(code) parse charge cancels out of the delta.
        int smallLen = 1000;
        int bigLen = 8000;
        byte[] smallMod = callContractModule(smallLen);
        byte[] bigMod = callContractModule(bigLen);
        assertEquals(smallMod.length, bigMod.length,
            "modules must be equal length so the parse charge cancels out of the gas delta");
        WasmVm.clearModuleCacheForTest();
        ExecResult small = vm.execute(smallMod,
            new MapHostState(new byte[0], new byte[0], 0), new GasMeter(10_000_000));
        ExecResult big = vm.execute(bigMod,
            new MapHostState(new byte[0], new byte[0], 0), new GasMeter(10_000_000));

        assertEquals(ExecResult.Status.OK, small.status(), "small-length call should complete");
        assertEquals(ExecResult.Status.OK, big.status(), "big-length call should complete");
        assertEquals((long) (bigLen - smallLen) * GasSchedule.PER_BYTE, big.gasUsed() - small.gasUsed(),
            "call_contract must charge PER_BYTE of the callee-address length too (audit F1)");
    }

    // ---- deploy-time section caps (F2b / F7) ----

    /** A type-only module declaring one function type with {@code params} i32 parameters. */
    private byte[] paramsModule(int params) {
        ByteArrayOutputStream type = new ByteArrayOutputStream();
        uleb(type, 1);                                            // 1 type
        type.write(0x60);
        uleb(type, params);
        for (int i = 0; i < params; i++) {
            type.write(0x7F);                                     // i32
        }
        uleb(type, 0);                                            // no returns
        return module(section(1, type.toByteArray()));
    }

    @Test
    void rejectsAFunctionTypeDeclaringTooManyParams() {
        // Chicory sizes each StackFrame's locals array as (params+locals) but enforces no param cap
        // of its own: an oversized type is an unmetered per-frame allocation the tree-wide locals
        // budget would otherwise never see (audit F2). One param over the cap must be refused.
        byte[] mod = paramsModule(WasmVm.MAX_FUNCTION_PARAMS + 1);
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("params"), ex.getMessage());
    }

    @Test
    void acceptsAFunctionTypeAtTheParamsCap() {
        // Exactly MAX_FUNCTION_PARAMS is allowed: the runtime test in WasmLocalsGuardTest relies on
        // an at-cap type deploying, so the cap must be a strict greater-than rejection.
        WasmVm.validateCode(paramsModule(WasmVm.MAX_FUNCTION_PARAMS));
    }

    /** A global-section-only module declaring {@code globals} immutable i32 globals. */
    private byte[] globalsModule(int globals) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        uleb(body, globals);
        for (int i = 0; i < globals; i++) {
            body.write(0x7F);                                     // i32
            body.write(0x00);                                     // immutable
            body.write(0x41); body.write(0x00);                   // i32.const 0
            body.write(0x0B);                                     // end
        }
        return module(section(6, body.toByteArray()));
    }

    @Test
    void rejectsAModuleDeclaringTooManyGlobals() {
        // Each global is materialised at instantiation, before any gas is charged — an unbounded
        // count is an unmetered, heap-dependent allocation vector (audit F7).
        byte[] mod = globalsModule(WasmVm.MAX_GLOBALS + 1);
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("globals"), ex.getMessage());
    }

    @Test
    void rejectsAModuleDeclaringTooManyFunctions() {
        // Every declared function forces per-entry instantiation structures; Chicory enforces no
        // count limit at parse (audit F7). Each function here is a bare (end) body of type () -> ().
        int functions = WasmVm.MAX_MODULE_FUNCTIONS + 1;
        byte[] type = section(1, bytes(0x01, 0x60, 0x00, 0x00));  // 1 type: () -> ()
        ByteArrayOutputStream func = new ByteArrayOutputStream();
        uleb(func, functions);
        for (int i = 0; i < functions; i++) {
            func.write(0x00);                                     // type index 0
        }
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        uleb(code, functions);
        for (int i = 0; i < functions; i++) {
            uleb(code, 2);                                        // body size
            code.write(0x00);                                     // 0 local groups
            code.write(0x0B);                                     // end
        }
        byte[] mod = module(type, section(3, func.toByteArray()), section(10, code.toByteArray()));
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("functions"), ex.getMessage());
    }

    @Test
    void rejectsAModuleDeclaringTooManyImports() {
        int imports = WasmVm.MAX_MODULE_IMPORTS + 1;
        byte[] type = section(1, bytes(0x01, 0x60, 0x00, 0x00));  // 1 type: () -> ()
        ByteArrayOutputStream imp = new ByteArrayOutputStream();
        uleb(imp, imports);
        for (int i = 0; i < imports; i++) {
            imp.write(0x03); imp.writeBytes("env".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            imp.write(0x01); imp.writeBytes("f".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            imp.write(0x00); imp.write(0x00);                     // func import, type index 0
        }
        byte[] mod = module(type, section(2, imp.toByteArray()));
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("imports"), ex.getMessage());
    }

    @Test
    void rejectsAModuleDeclaringTooManyExports() {
        int exports = WasmVm.MAX_MODULE_EXPORTS + 1;
        byte[] type = section(1, bytes(0x01, 0x60, 0x00, 0x00));  // 1 type: () -> ()
        byte[] func = section(3, bytes(0x01, 0x00));              // 1 function, type 0
        ByteArrayOutputStream exp = new ByteArrayOutputStream();
        uleb(exp, exports);
        for (int i = 0; i < exports; i++) {
            byte[] name = ("e" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8); // unique
            uleb(exp, name.length);
            exp.writeBytes(name);
            exp.write(0x00);                                      // func export
            uleb(exp, 0);                                         // func index 0
        }
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x01);                                         // 1 body
        code.write(0x02);                                         // body size
        code.write(0x00);                                         // 0 local groups
        code.write(0x0B);                                         // end
        byte[] mod = module(type, func, section(7, exp.toByteArray()), section(10, code.toByteArray()));
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("exports"), ex.getMessage());
    }

    /** {@code memory.grow(hi); drop; memory.grow(lo); drop} — the first grow overshoots the instance cap. */
    private byte[] failedThenSucceedGrowModule(int hi, int lo) {
        byte[] type = section(1, bytes(0x01, 0x60, 0x00, 0x00));      // () -> ()
        byte[] func = section(3, bytes(0x01, 0x00));                  // 1 function, type 0
        byte[] mem = section(5, bytes(0x01, 0x00, 0x01));            // 1 memory, min 1 page, no max
        byte[] export = section(7, bytes(0x01, 0x04, 0x63, 0x61, 0x6C, 0x6C, 0x00, 0x00)); // "call" -> func 0
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x00);
        body.write(0x41); sleb(body, hi);
        body.write(0x40); body.write(0x00);                          // memory.grow (fails at the cap)
        body.write(0x1A);                                            // drop -1
        body.write(0x41); sleb(body, lo);
        body.write(0x40); body.write(0x00);                          // memory.grow (fits)
        body.write(0x1A);                                            // drop prev size
        body.write(0x0B);                                            // end
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x01);
        uleb(code, body.size());
        code.writeBytes(body.toByteArray());
        return module(type, func, mem, export, section(10, code.toByteArray()));
    }

    @Test
    void aFailedGrowDoesNotConsumeTheTreePageBudget() {
        // The instance is capped at MAX_CONTRACT_PAGES = 1024 (= TREE_MAX_PAGES). memory.grow(1024) from
        // 1 page overshoots the cap, so it returns -1 and allocates nothing; memory.grow(1) then fits and
        // must succeed. The old accounting reserved the full 1024 pages of the FAILED grow against the
        // tree budget, so the second grow tripped TREE_MAX_PAGES and reverted the call for memory that was
        // never allocated (audit VM #4). WASM grow is all-or-nothing, so a failed grow must reserve
        // nothing — the call now completes.
        byte[] mod = failedThenSucceedGrowModule(1024, 1);
        WasmVm.clearModuleCacheForTest();
        ExecResult r = vm.execute(mod,
            new MapHostState(new byte[0], new byte[0], 0), new GasMeter(10_000_000));
        assertEquals(ExecResult.Status.OK, r.status(),
            "a grow that fails at the instance cap must not consume the tree-page budget");
    }
}
