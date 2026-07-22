package rhizome.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the WASM function-locals guards (audit V1 &amp; V3).
 *
 * <p><b>V1</b> — Chicory's parser caps each local-declaration <em>group</em> at 50 000 but not the
 * number of groups, and it eagerly expands every group into a per-local list <em>during the parse
 * itself</em>. So a tiny module could declare billions of locals and OOM the node inside
 * {@code Parser.parse}, before the post-parse {@code rejectOversizedAllocations} guard could run.
 * {@link WasmVm#validateCode} must therefore bound the aggregate from the raw bytes ahead of the
 * parse. These tests feed hostile code sections and assert a clean {@code IllegalArgumentException}.
 *
 * <p><b>V3</b> — even a within-cap locals count, multiplied by the call-depth cap, is an unmetered
 * heap-dependent spike (~160 MiB) that forks consensus (small-heap node OOMs vs large-heap node
 * reverts). A locals-heavy recursion must instead trap on the deterministic tree-wide locals budget.
 */
class WasmLocalsGuardTest {

    private final WasmVm vm = new WasmVm();

    private static final byte[] WASM_HEADER = {
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00
    };

    /** Unsigned LEB128, matching how the WASM binary format (and Chicory's parser) encode u32. */
    private static byte[] leb(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long value = v;
        do {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value != 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (value != 0);
        return out.toByteArray();
    }

    /**
     * A module carrying only a header and a Code section of {@code funcCount} functions, each
     * declaring {@code groupsPerFunc} local groups of {@code localsPerGroup} i32 each and a bare
     * {@code end}. This is exactly the shape of the V1 attack (many groups / many functions), minus
     * the type/func/export sections the pre-scan does not need — it rejects before {@code Parser.parse}.
     */
    private static byte[] hostileLocalsModule(int funcCount, int groupsPerFunc, int localsPerGroup) {
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        contents.writeBytes(leb(funcCount));
        for (int f = 0; f < funcCount; f++) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(leb(groupsPerFunc));
            for (int g = 0; g < groupsPerFunc; g++) {
                body.writeBytes(leb(localsPerGroup));
                body.write(0x7F); // i32
            }
            body.write(0x0B); // end
            byte[] bodyBytes = body.toByteArray();
            contents.writeBytes(leb(bodyBytes.length));
            contents.writeBytes(bodyBytes);
        }
        byte[] c = contents.toByteArray();
        ByteArrayOutputStream mod = new ByteArrayOutputStream();
        mod.writeBytes(WASM_HEADER);
        mod.write(0x0A); // code section id
        mod.writeBytes(leb(c.length));
        mod.writeBytes(c);
        return mod.toByteArray();
    }

    @Test
    void rejectsFunctionDeclaringTooManyLocalsAcrossManyGroups() {
        // 8193 groups of 1 local: each group is under Chicory's 50 000 per-group cap, but the sum
        // exceeds MAX_FUNCTION_LOCALS. The module is < 20 KiB yet, unguarded, forces the parser to
        // build an 8193-entry list; scaled up (65 000 groups × 50 000) that is billions of entries.
        byte[] mod = hostileLocalsModule(1, WasmVm.MAX_FUNCTION_LOCALS + 1, 1);
        assertTrue(mod.length < WasmVm.MAX_CODE_SIZE, "attack module is small");
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("locals"), ex.getMessage());
    }

    @Test
    void rejectsModuleWhoseFunctionsAggregateTooManyLocals() {
        // Each function is at exactly MAX_FUNCTION_LOCALS (so the per-function cap passes), but nine
        // of them sum past MAX_MODULE_TOTAL_LOCALS — the parser retains every function body's list at
        // once, so the aggregate across functions is the real ceiling.
        int perFunc = WasmVm.MAX_FUNCTION_LOCALS;
        int funcs = (int) (WasmVm.MAX_MODULE_TOTAL_LOCALS / perFunc) + 2;
        byte[] mod = hostileLocalsModule(funcs, 1, perFunc);
        var ex = assertThrows(IllegalArgumentException.class, () -> WasmVm.validateCode(mod));
        assertTrue(ex.getMessage().contains("total locals"), ex.getMessage());
    }

    @Test
    void acceptsAModestLocalsCountThatRealContractsUse() {
        // A single function with a handful of locals must still pass the pre-scan and reach the
        // parser (which then rejects THIS stub for other reasons — no type/func section — but never
        // for locals). We only assert the pre-scan did not raise the locals error.
        byte[] mod = hostileLocalsModule(1, 1, 8);
        try {
            WasmVm.validateCode(mod);
        } catch (Throwable e) {
            // This bare stub has no type/func section, so Parser.parse rejects it for structure —
            // that is fine. The only thing under test is that the locals pre-scan did NOT fire.
            String msg = String.valueOf(e.getMessage());
            assertTrue(!msg.contains("locals"),
                "modest locals must not trip the locals guard: " + msg);
        }
    }

    /**
     * A recursive function declaring 400 i32 locals. Live locals across the tree grow by 400 per
     * activation, crossing MAX_TREE_LIVE_LOCALS (262 144) at depth ~656 — before the depth cap
     * (1024) — so the deterministic locals-budget trap fires first. 400 = LEB128 {@code 90 03}.
     */
    private static final byte[] RECURSIVE_WITH_LOCALS = {
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,            // magic + version
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,                       // type: () -> ()
        0x03, 0x02, 0x01, 0x00,                                   // func: one function of type 0
        0x07, 0x08, 0x01, 0x04, 0x63, 0x61, 0x6C, 0x6C, 0x00, 0x00, // export "call" -> func 0
        0x0A, 0x09, 0x01, 0x07, 0x01, (byte) 0x90, 0x03, 0x7F,    // code: 1 group of 400 i32 locals,
        0x10, 0x00, 0x0B                                          //   (call 0) end
    };

    @Test
    void localsHeavyRecursionTrapsOnTheDeterministicLocalsBudget() {
        // Generous gas so the LOCALS budget — not gas or depth — is what stops it. Runs on the
        // fixed-stack thread so no JVM StackOverflowError intervenes.
        ExecResult r = WasmVm.onBoundedStack(() ->
            vm.execute(RECURSIVE_WITH_LOCALS, new MapHostState(new byte[0], new byte[0], 0),
                new GasMeter(500_000_000)));
        assertEquals(ExecResult.Status.REVERTED, r.status());
        assertTrue(r.message() != null && r.message().contains("locals"),
            "expected a locals-budget revert, got: " + r.message());
    }

    @Test
    void localsBudgetRevertIsDeterministicWarmAndCold() {
        // gasUsed must be identical whether the module cache is warm or cold, or the state root
        // would depend on node-local cache state (the fork class the VM guards against).
        WasmVm.clearModuleCacheForTest();
        ExecResult cold = WasmVm.onBoundedStack(() ->
            vm.execute(RECURSIVE_WITH_LOCALS, new MapHostState(new byte[0], new byte[0], 0),
                new GasMeter(500_000_000)));
        ExecResult warm = WasmVm.onBoundedStack(() ->
            vm.execute(RECURSIVE_WITH_LOCALS, new MapHostState(new byte[0], new byte[0], 0),
                new GasMeter(500_000_000)));
        assertEquals(cold.status(), warm.status());
        assertEquals(cold.gasUsed(), warm.gasUsed());
    }
}
