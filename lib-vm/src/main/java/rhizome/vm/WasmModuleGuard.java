package rhizome.vm;

import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.Instruction;

/**
 * The post-parse module validation extracted from {@link WasmVm} (archi-review lot L20): the
 * WASM GC family, the non-deterministic opcode scan, the count-sized allocation caps and the
 * host-ABI whitelist. Static and stateless — the network constants it enforces stay declared on
 * {@code WasmVm} (tests reference them through the VM, and the runtime shares the memory caps
 * and the ABI list), and this class only walks an already-parsed {@link WasmModule}.
 *
 * <p>Every rejection here is a deploy-time network rule: it must fire identically on every
 * node, before the module can enter on-chain state, so the verdict never depends on a host's
 * heap or Chicory's version (see the individual methods).
 */
final class WasmModuleGuard {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WasmModuleGuard.class);

    private WasmModuleGuard() {
    }

    /**
     * Rejects any module that uses non-deterministic opcodes: scalar floating point (f32/f64) and
     * the entire SIMD/vector family (v128, including the vector-float lanes f32x4/f64x2). WASM
     * leaves NaN payloads and some float results implementation-defined, so a contract doing float
     * or vector-float maths could make two nodes on different runtime builds diverge (audit L4).
     * The previous check only matched the {@code F32_}/{@code F64_} prefixes, so vector-float
     * opcodes such as {@code F32x4_ADD} slipped through — this uses an uppercased match against the
     * float and lane-shape families so the whole class is refused rather than relying on the
     * runtime happening to leave SIMD unimplemented. Contracts are integer-only by construction.
     * The scan covers every place an opcode can appear: function bodies, global init expressions
     * and element initializers/offsets (the latter two evaluate at instantiation, unmetered).
     */
    static void rejectNonDeterministic(WasmModule module) {
        var code = module.codeSection();
        if (code != null) {
            for (int i = 0; i < code.functionBodyCount(); i++) {
                for (var instruction : code.getFunctionBody(i).instructions()) {
                    rejectIfNonDeterministic(instruction);
                }
            }
        }
        // Init expressions are evaluated at INSTANTIATION — before any gas is charged — so a
        // float const-expression in a global or element initializer would reach the runtime
        // without ever passing through the code-section scan (audit: floats in init-expressions).
        var globals = module.globalSection();
        if (globals != null) {
            for (int i = 0; i < globals.globalCount(); i++) {
                for (var instruction : globals.getGlobal(i).initInstructions()) {
                    rejectIfNonDeterministic(instruction);
                }
            }
        }
        var elements = module.elementSection();
        if (elements != null) {
            for (int i = 0; i < elements.elementCount(); i++) {
                var element = elements.getElement(i);
                for (var init : element.initializers()) {
                    for (var instruction : init) {
                        rejectIfNonDeterministic(instruction);
                    }
                }
                if (element instanceof com.dylibso.chicory.wasm.types.ActiveElement active) {
                    for (var instruction : active.offset()) {
                        rejectIfNonDeterministic(instruction);
                    }
                }
            }
        }
    }

    private static void rejectIfNonDeterministic(Instruction instruction) {
        String op = instruction.opcode().name().toUpperCase(java.util.Locale.ROOT);
        // Match the float families anywhere in the name, not just as a prefix: the
        // integer<->float conversions (I32_TRUNC_F32_S, I64_REINTERPRET_F64, …) carry the
        // float type in the MIDDLE of the mnemonic and slipped a startsWith("F..") filter,
        // leaving a float value reachable on the stack (audit V6j). All WASM opcodes whose
        // name contains F32/F64 are float ops; contracts are integer-only by construction.
        if (op.contains("F32") || op.contains("F64") || op.startsWith("V128")
                || op.contains("X16_") || op.contains("X8_")
                || op.contains("X4_") || op.contains("X2_")) {
            throw new IllegalArgumentException(
                "non-deterministic opcode " + instruction.opcode().name()
                + " is not allowed (float/SIMD)");
        }
    }

    /**
     * Rejects the WASM GC feature family entirely (audit C1). Chicory 1.7.5 implements the GC
     * opcodes — STRUCT_*, ARRAY_*, REF_TEST/REF_CAST, BR_ON_CAST*, REF_I31/I31_GET, ANY/EXTERN
     * conversions — and they allocate on the JVM <em>heap</em>, outside linear memory:
     * {@code array.new_default} materialises a {@code new long[len]} with {@code len} a runtime
     * operand up to 2^31-1 (~16 GiB in ONE instruction for 1 gas), and Chicory's GcRefStore never
     * sweeps during execution, so every allocation in a loop is retained until the call returns.
     * None of the deterministic budgets ({@link WasmVm#TREE_MAX_PAGES},
     * {@link WasmVm#MAX_TREE_LIVE_LOCALS}, the table caps) see this memory: whether it OOMs
     * depends on each node's {@code -Xmx}, so a large-heap node succeeds with a tiny
     * {@code gasUsed} while a small-heap validator OOMs (normalized to full-gas out-of-gas) —
     * divergent {@code gasUsed} → divergent state root → consensus fork — and an uncaught OOM can
     * kill any node thread. The bulk GC ops ({@code array.copy/fill/new_data/new_elem}) also do
     * O(n) memcpy for a flat 1 gas (audit H1), and const expressions in global/element
     * initializers evaluate {@code array.new} at INSTANTIATION, before any metering. Contracts are
     * integer + linear-memory only by construction, so the whole family is refused: GC comp types
     * in the type section, and GC opcodes in function bodies, global init expressions and element
     * initializers/offsets.
     */
    static void rejectWasmGc(WasmModule module) {
        var types = module.typeSection();
        if (types != null) {
            for (int i = 0; i < types.subTypeCount(); i++) {
                var comp = types.getSubType(i).compType();
                if (comp != null && (comp.structType() != null || comp.arrayType() != null)) {
                    throw new IllegalArgumentException(
                        "WASM GC types (struct/array) are not allowed in contracts");
                }
            }
        }
        var code = module.codeSection();
        if (code != null) {
            for (int i = 0; i < code.functionBodyCount(); i++) {
                for (var instruction : code.getFunctionBody(i).instructions()) {
                    rejectIfGc(instruction);
                }
            }
        }
        var globals = module.globalSection();
        if (globals != null) {
            for (int i = 0; i < globals.globalCount(); i++) {
                for (var instruction : globals.getGlobal(i).initInstructions()) {
                    rejectIfGc(instruction);
                }
            }
        }
        var elements = module.elementSection();
        if (elements != null) {
            for (int i = 0; i < elements.elementCount(); i++) {
                var element = elements.getElement(i);
                for (var init : element.initializers()) {
                    for (var instruction : init) {
                        rejectIfGc(instruction);
                    }
                }
                if (element instanceof com.dylibso.chicory.wasm.types.ActiveElement active) {
                    for (var instruction : active.offset()) {
                        rejectIfGc(instruction);
                    }
                }
            }
        }
    }

    private static void rejectIfGc(Instruction instruction) {
        String op = instruction.opcode().name();
        // REF_TEST*/REF_CAST* are named explicitly: the GC spec has no "cast.test" instruction,
        // so a prefix like CAST_TEST would silently match nothing (audit follow-up).
        if (op.startsWith("STRUCT_") || op.startsWith("ARRAY_")
                || op.startsWith("REF_TEST") || op.startsWith("REF_CAST")
                || op.startsWith("BR_ON_CAST") || op.startsWith("I31_GET")
                || op.equals("REF_I31") || op.equals("ANY_CONVERT_EXTERN")
                || op.equals("EXTERN_CONVERT_ANY")) {
            throw new IllegalArgumentException(
                "WASM GC opcode " + op + " is not allowed in contracts");
        }
    }

    /**
     * Rejects modules whose declared table or locals sizes would force an unmetered, heap-dependent
     * allocation at instantiation/execution (audit H4). Table {@code initial} entries are allocated
     * eagerly by Chicory when the Instance is built — before any gas is charged — so an oversized
     * declaration is refused here at parse/deploy, making the ceiling a deterministic network
     * constant instead of a per-node OOM. Function locals are bounded as defence in depth (Chicory
     * already caps them at 50 000 during parse).
     */
    static void rejectOversizedAllocations(WasmModule module) {
        var tables = module.tableSection();
        if (tables != null) {
            int tableCount = tables.tableCount();
            // Cap the number of tables: each is eagerly allocated at instantiation, so an unbounded
            // count is an unmetered, heap-dependent allocation vector on its own (audit H4 residual).
            if (tableCount > WasmVm.MAX_TABLES) {
                throw new IllegalArgumentException("contract declares too many tables: "
                    + tableCount + " (max " + WasmVm.MAX_TABLES + ")");
            }
            long totalEntries = 0;
            for (int i = 0; i < tableCount; i++) {
                var limits = tables.getTable(i).limits();
                long initial = limits.min();
                // Bound the growth CEILING (max), not only the eagerly-allocated min. table.grow
                // extends the backing int[]/Instance[] arrays at runtime and was metered by gas ONLY —
                // no tree-wide reservation, unlike linear memory (TREE_MAX_PAGES) and locals
                // (MAX_TREE_LIVE_LOCALS). A module declaring `(table 1 10000000 funcref)` — or an
                // unbounded max, which Chicory treats as LIMIT_MAX = 10M — could table.grow into
                // ~gigabytes bounded only by gasLimit (itself bounded only by the sender's balance).
                // A small-heap validator then hits OutOfMemoryError (normalized to full-gas
                // out-of-gas) where a large-heap one completes with partial gas → gasUsed diverges →
                // state-root fork: exactly the heap-dependent-OOM class the audit closed for memory
                // and locals, left open for tables (audit S2). Capping the ceiling — and summing
                // ceilings for the aggregate — makes the whole table footprint a deterministic network
                // constant reserved before Chicory can allocate it.
                long ceiling = limits.max();
                if (initial > WasmVm.MAX_TABLE_ENTRIES || ceiling > WasmVm.MAX_TABLE_ENTRIES) {
                    throw new IllegalArgumentException("contract declares too large a table: min "
                        + initial + " max " + ceiling + " entries (max " + WasmVm.MAX_TABLE_ENTRIES + ")");
                }
                totalEntries += ceiling;
                // Bound the AGGREGATE growth ceiling across all tables, not just each one: many
                // mid-size tables sum to the same multi-GB allocation a single oversized table would.
                if (totalEntries > WasmVm.MAX_TOTAL_TABLE_ENTRIES) {
                    throw new IllegalArgumentException("contract declares too many total table entries: "
                        + totalEntries + " (max " + WasmVm.MAX_TOTAL_TABLE_ENTRIES + ")");
                }
            }
        }
        var code = module.codeSection();
        if (code != null) {
            for (int i = 0; i < code.functionBodyCount(); i++) {
                int locals = code.getFunctionBody(i).localTypes().size();
                if (locals > WasmVm.MAX_FUNCTION_LOCALS) {
                    throw new IllegalArgumentException("contract function declares too many locals: "
                        + locals + " (max " + WasmVm.MAX_FUNCTION_LOCALS + ")");
                }
            }
        }
        // Cap declared params per function type: Chicory sizes each StackFrame's locals array as
        // (params+locals) but enforces no param cap of its own, so an oversized type was an
        // unmetered per-frame allocation the locals budget never counted (audit F2).
        var types = module.typeSection();
        if (types != null) {
            for (int i = 0; i < types.typeCount(); i++) {
                int params = types.getType(i).params().size();
                if (params > WasmVm.MAX_FUNCTION_PARAMS) {
                    throw new IllegalArgumentException("contract function type declares too many params: "
                        + params + " (max " + WasmVm.MAX_FUNCTION_PARAMS + ")");
                }
            }
        }
        // Cap the remaining section counts Chicory leaves unenforced: globals, functions, imports
        // and exports are all materialised per-entry at instantiation — unmetered, heap-dependent
        // allocation unless bounded here as a deterministic network constant (audit F7).
        var globals = module.globalSection();
        if (globals != null && globals.globalCount() > WasmVm.MAX_GLOBALS) {
            throw new IllegalArgumentException("contract declares too many globals: "
                + globals.globalCount() + " (max " + WasmVm.MAX_GLOBALS + ")");
        }
        var functions = module.functionSection();
        if (functions != null && functions.functionCount() > WasmVm.MAX_MODULE_FUNCTIONS) {
            throw new IllegalArgumentException("contract declares too many functions: "
                + functions.functionCount() + " (max " + WasmVm.MAX_MODULE_FUNCTIONS + ")");
        }
        var imports = module.importSection();
        if (imports != null && imports.importCount() > WasmVm.MAX_MODULE_IMPORTS) {
            throw new IllegalArgumentException("contract declares too many imports: "
                + imports.importCount() + " (max " + WasmVm.MAX_MODULE_IMPORTS + ")");
        }
        var exports = module.exportSection();
        if (exports != null && exports.exportCount() > WasmVm.MAX_MODULE_EXPORTS) {
            throw new IllegalArgumentException("contract declares too many exports: "
                + exports.exportCount() + " (max " + WasmVm.MAX_MODULE_EXPORTS + ")");
        }
        // Deploy-time mirror of the runtime boundedMemory cap: a declared initial memory above
        // MAX_CONTRACT_PAGES must be refused here, not only discovered on every later call (the
        // same check, shared via WasmVm.checkDeclaredInitialPages, so the two can never drift).
        var memories = module.memorySection();
        if (memories.isPresent()) {
            for (int i = 0; i < memories.get().memoryCount(); i++) {
                var limits = memories.get().getMemory(i).limits();
                WasmVm.checkDeclaredInitialPages(limits.initialPages());
                if (limits.maximumPages() > WasmVm.MAX_CONTRACT_PAGES) {
                    // An explicit deploy-time REJECTION would break the bundled fixtures — and
                    // every no-max module, where Chicory reports the 65536-page wasm32 ceiling —
                    // so the runtime clamp in boundedMemory stays. Log once per module so the
                    // silent narrowing is at least visible; this is the cached parse path, so it
                    // fires once per module per node, never per call (audit: silent memory clamp).
                    log.warn("contract declares memory max {} pages; clamped to {} at instantiation",
                        limits.maximumPages(), WasmVm.MAX_CONTRACT_PAGES);
                }
            }
        }
    }

    /**
     * Enforces the sandbox ABI at validation time: every import must be a FUNCTION import of a
     * whitelisted {@code env.*} host name (the single declaration lives on {@code WasmVm} —
     * {@link WasmVm#HOST_IMPORTS}, derived from {@link WasmVm.AbiFn}, which the runtime's
     * {@code hostFunctions} also derives from), and the module must export the {@code call} entry
     * point the executor invokes. Runs AFTER the other rejections so their more specific
     * diagnostics (float/SIMD, tables, locals, …) keep firing first on modules that violate both.
     */
    static void rejectNonWhitelistedAbi(WasmModule module) {
        var imports = module.importSection();
        if (imports != null) {
            for (int i = 0; i < imports.importCount(); i++) {
                var imp = imports.getImport(i);
                if (imp.importType() != com.dylibso.chicory.wasm.types.ExternalType.FUNCTION
                        || !WasmVm.ENV.equals(imp.module()) || !WasmVm.HOST_IMPORTS.contains(imp.name())) {
                    throw new IllegalArgumentException("contract imports a non-whitelisted host "
                        + "function: " + imp.module() + "." + imp.name());
                }
            }
        }
        var exports = module.exportSection();
        boolean hasCall = false;
        if (exports != null) {
            for (int i = 0; i < exports.exportCount(); i++) {
                var export = exports.getExport(i);
                if (WasmVm.ENTRY.equals(export.name())
                        && export.exportType() == com.dylibso.chicory.wasm.types.ExternalType.FUNCTION) {
                    hasCall = true;
                    break;
                }
            }
        }
        if (!hasCall) {
            throw new IllegalArgumentException("contract does not export the \"" + WasmVm.ENTRY
                + "\" entry point");
        }
    }
}
