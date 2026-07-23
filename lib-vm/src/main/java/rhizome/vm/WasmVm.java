package rhizome.vm;

import java.util.List;

import com.dylibso.chicory.runtime.ByteBufferMemory;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.MStack;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.ValType;

/**
 * The smart-contract virtual machine: runs a WebAssembly contract deterministically
 * on Chicory (pure Java, no JNI), metering gas per instruction and per host call so
 * untrusted code can neither hang the node nor escape its sandbox.
 *
 * <p>Contracts import a minimal host ABI from module {@code "env"} — storage
 * read/write, call input, caller, attached value, and a return-data slot — and
 * export a single {@code call} entry point. Everything the contract can touch goes
 * through {@link HostState}; the WASM sandbox denies it any other I/O.
 *
 * <p>This is the M1 core: an in-memory host state and a placeholder gas schedule.
 * Persistence (a contract-code + storage store), the deploy/call transaction types,
 * and ledger-backed value transfer are layered on top without changing this class.
 */
public final class WasmVm {

    private static final String ENV = "env";
    private static final String ENTRY = "call";

    /**
     * Hard cap on a contract's linear memory, in 64 KiB pages (1024 pages = 64 MiB).
     * Enforced at instantiation and on {@code memory.grow}, so a contract cannot
     * allocate gigabytes and OOM the node. Comfortably above what the bundled
     * templates declare (16–17 pages ≈ 1 MiB).
     */
    private static final int MAX_CONTRACT_PAGES = 1024;

    /**
     * Hard cap on linear memory summed across a whole contract call TREE (pages, 64 KiB each).
     * {@link #MAX_CONTRACT_PAGES} bounds one Instance, but a chain of {@link
     * WasmContractProcessor#MAX_CALL_DEPTH} distinct contracts holds that many Instances alive at
     * once (each parent is suspended inside its {@code call_contract} while the child runs), so the
     * per-instance cap alone permitted depth × 64 MiB of concurrently-allocated memory. Whether that
     * allocation succeeds or throws {@link OutOfMemoryError} depends on each node's {@code -Xmx} —
     * a large-heap node returns OK, a small-heap node reverts with out-of-gas — which forks
     * consensus and crashes memory-constrained validators. This tree-wide budget (tracked in {@link
     * #TREE_PAGES}, charged in {@link #reserveTreePages}) makes the ceiling a deterministic network
     * constant enforced before any host OOM, exactly as the call-depth cap is tree-wide.
     */
    private static final long TREE_MAX_PAGES = MAX_CONTRACT_PAGES;

    /**
     * Linear-memory pages currently reserved across the active call tree on this thread. A tree runs
     * entirely on one {@link #onBoundedStack} thread (nested {@code call_contract} frames reuse it),
     * so a thread-local running total sums every live Instance's memory. Each {@link #execute} frame
     * reserves its pages on allocation/grow and releases exactly those in a {@code finally}, so the
     * total tracks concurrent (nested) allocation and drops back on unwind — balanced even if the
     * thread is reused.
     */
    private static final ThreadLocal<long[]> TREE_PAGES = ThreadLocal.withInitial(() -> new long[1]);

    /**
     * Hard cap on deployed contract code size (bytes). Bounds the one-time deploy validation and
     * every per-call parse (a cache miss) so a multi-megabyte module cannot be used to amplify
     * node CPU, and bounds on-chain state growth. Comfortably above the bundled templates.
     */
    static final int MAX_CODE_SIZE = 256 * 1024;

    /**
     * Hard cap on a module's declared table size (entries). A table's {@code initial} count forces
     * Chicory to eagerly allocate a reference array of that many entries at INSTANTIATION — before
     * the gas listener runs, so it is completely unmetered. Chicory's own limit is 10,000,000
     * entries (~80 MiB per table), which a few-byte module can declare and which lands, unmetered and
     * heap-dependent, at instantiation (audit H4). This tighter cap keeps that eager allocation a
     * small deterministic network constant (&lt; 1 MiB), independent of Chicory's version; the bundled
     * templates declare a single tiny table. (Chicory already bounds function locals to 50,000.)
     */
    static final long MAX_TABLE_ENTRIES = 65_536;

    /**
     * Hard cap on the number of table sections in a module, and on the SUM of every table's initial
     * entry count. The per-table {@link #MAX_TABLE_ENTRIES} cap alone is insufficient: a WASM module
     * may declare arbitrarily many tables (Chicory validates no count), each eagerly allocated at
     * instantiation, so ~50 000 tables of 65 536 entries each (a few RLE bytes apiece, well under
     * {@link #MAX_CODE_SIZE}) force tens of GB of unmetered, heap-dependent allocation on every CALL
     * — a repeatable OOM crash and a consensus fork between large- and small-heap nodes (audit H4,
     * residual). Bounding the count and the aggregate makes the eager table allocation a small fixed
     * network constant, exactly like {@link #TREE_MAX_PAGES} does for linear memory. The bundled
     * templates declare a single tiny table.
     */
    static final int MAX_TABLES = 16;
    static final long MAX_TOTAL_TABLE_ENTRIES = 65_536;

    /**
     * Hard cap on a function's local-variable count (defence in depth). The interpreter allocates a
     * locals array per activation, so a deep recursion of a high-locals function spikes heap; this
     * bound keeps that product small. Far above what a compiled Rust contract uses.
     *
     * <p>Enforced by {@link #preScanLocals} <em>before</em> {@code Parser.parse}, and re-checked in
     * {@link #rejectOversizedAllocations} as defence in depth — see the pre-scan for why the
     * post-parse check alone is insufficient (audit V1).
     */
    static final int MAX_FUNCTION_LOCALS = 8_192;

    /**
     * Hard cap on the SUM of declared locals across every function body in one module. Chicory's
     * {@code Parser} caps each local-declaration <em>group</em> at 50 000 but does NOT bound the
     * number of groups, and it eagerly expands every group into a per-local list <em>during the
     * parse itself</em>. So a &lt;{@link #MAX_CODE_SIZE} module can declare billions of locals
     * (many groups, or many functions) and OOM the node inside {@code Parser.parse} — before the
     * post-parse {@link #rejectOversizedAllocations} guard can ever run. {@link #preScanLocals}
     * therefore bounds the aggregate directly from the raw bytes, ahead of the parse (audit V1).
     * Generous: real templates declare a few dozen locals total.
     */
    static final long MAX_MODULE_TOTAL_LOCALS = 65_536;

    /**
     * Live per-activation locals summed across the whole call tree. The interpreter allocates
     * {@code (params+locals)}-sized arrays per frame, so a function with {@link #MAX_FUNCTION_LOCALS}
     * locals recursing to {@link DepthLimitedInterpreterMachine#MAX_WASM_CALL_DEPTH} would hold
     * ~8 M live locals (~160 MiB) for ~1 K gas — unmetered and heap-dependent, so a small-heap node
     * OOMs (full-gas out-of-gas) while a large-heap node reverts (partial gas): different gasUsed →
     * consensus fork, exactly the class {@link #TREE_MAX_PAGES} closes for linear memory. This
     * tree-wide reservation (in {@link DepthLimitedInterpreterMachine}) makes the ceiling a
     * deterministic network constant enforced before any host OOM (audit V3). ~5 MiB worst case;
     * far above any legitimate contract, which iterates rather than recursing thousands deep.
     */
    static final long MAX_TREE_LIVE_LOCALS = 262_144;

    /**
     * Fixed stack size (bytes) for the dedicated contract-execution thread. Large enough to hold
     * {@link DepthLimitedInterpreterMachine#MAX_WASM_CALL_DEPTH} interpreter frames on every node,
     * so the deterministic depth trap always fires before a JVM {@code StackOverflowError}, and
     * independent of the host's {@code -Xss}.
     */
    static final long EXEC_STACK_BYTES = 64L * 1024 * 1024;

    /**
     * Parsed, validated modules keyed by SHA-256 of their code. Parsing and the float/SIMD scan are
     * O(code size); caching amortises them across repeated calls to the same contract. Node-local
     * and purely a performance cache — it never changes execution results — with a bounded size so
     * it cannot itself be a memory-growth vector.
     */
    private static final java.util.LinkedHashMap<CodeKey, WasmModule> MODULE_CACHE =
        new java.util.LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<CodeKey, WasmModule> eldest) {
                return size() > 256;
            }
        };

    /**
     * Value-equality wrapper over contract code bytes, used as the {@link #MODULE_CACHE} key. Replaces
     * a per-call SHA-256 + hex-String of the whole module (up to MAX_CODE_SIZE) — O(code) crypto on
     * every call, even a cache hit — with an {@code Arrays.hashCode} loop; equals only runs on a hash
     * collision. The key never affects gas (charged unconditionally before the lookup), so this stays a
     * pure CPU optimization with no consensus effect.
     */
    private record CodeKey(byte[] code) {
        @Override public boolean equals(Object o) {
            return o instanceof CodeKey k && java.util.Arrays.equals(code, k.code);
        }
        @Override public int hashCode() {
            return java.util.Arrays.hashCode(code);
        }
    }

    /**
     * Handles a contract-to-contract call requested via the {@code call_contract}
     * host function. Returns the callee's output on success, or {@code null} when
     * the call failed (unknown contract, revert, depth or reentrancy limit) — the
     * caller keeps running either way, its own state untouched by the failure.
     */
    @FunctionalInterface
    public interface ContractCallHandler {
        byte[] call(byte[] calleeAddress, byte[] input);
    }

    /** Runs {@code wasmCode}'s {@code call} export against {@code host} under {@code gas}. */
    public ExecResult execute(byte[] wasmCode, HostState host, GasMeter gas) {
        return execute(wasmCode, host, gas, null);
    }

    /** As above, with {@code calls} dispatching {@code call_contract} (null = calls always fail). */
    public ExecResult execute(byte[] wasmCode, HostState host, GasMeter gas, ContractCallHandler calls) {
        WasmModule module;
        try {
            // Parse + non-determinism validation are cached by code identity: without this,
            // every CALL re-parsed the whole module and re-scanned every instruction (O(code)
            // work) unpriced, so a large module could be spammed to amplify node CPU. Deploy
            // also caps code size, so a cache miss is bounded work.
            module = moduleFor(wasmCode, gas);
        } catch (Throwable e) {
            // The deterministic module-parse charge is levied here (cache hit and miss alike), so a
            // budget too small to cover it must surface as OUT_OF_GAS — a full-limit, node-independent
            // outcome — not as a "malformed module" revert.
            if (isOutOfGas(e)) {
                return ExecResult.outOfGas(gas.used());
            }
            // Malformed bytecode (or a module using non-deterministic float/SIMD opcodes) never
            // reaches instantiation — deterministic revert.
            return ExecResult.reverted(gas.used(), "invalid module: " + e.getMessage());
        }
        ImportValues imports = ImportValues.builder()
            .addFunction(hostFunctions(host, gas, calls))
            .build();

        // Pages this frame reserves from the tree-wide budget (initial memory + every memory.grow);
        // released in the finally so the budget tracks concurrent nesting and unwinds on return.
        long[] frameAdded = new long[1];
        try {
            Instance instance = Instance.builder(module)
                .withImportValues(imports)
                .withStart(false)
                // Cap and meter linear memory so a contract cannot allocate gigabytes — per-instance
                // AND tree-wide (see TREE_MAX_PAGES) so nested calls cannot sum past the budget.
                .withMemoryFactory(limits -> boundedMemory(limits, gas, frameAdded))
                // Deterministic WASM call-depth cap: traps unbounded recursion at a fixed depth
                // on every node, replacing the JVM-stack-dependent StackOverflowError that would
                // otherwise fork consensus (see DepthLimitedInterpreterMachine).
                .withMachineFactory(DepthLimitedInterpreterMachine::new)
                // Meter every instruction; bulk-memory / memory.grow are charged by their
                // runtime operand, not a flat 1, so O(N) work cannot cost O(1) gas.
                .withUnsafeExecutionListener((instruction, stack) -> meter(instruction, stack, gas, frameAdded))
                .build();

            ExportFunction call = instance.export(ENTRY);
            call.apply();
            return ExecResult.ok(host.output(), host.logs(), gas.used());
        } catch (StackOverflowError | OutOfMemoryError e) {
            // Runaway allocation, or (defence in depth) recursion that somehow outran the
            // deterministic depth cap. The exact heap/stack at which a given JVM trips is
            // host-specific, so letting this surface as a node-local outcome would FORK
            // consensus. Normalize it to a deterministic full-gas out-of-gas.
            return ExecResult.outOfGas(gas.limit());
        } catch (Throwable e) {
            if (isDepthExceeded(e)) {
                // Deterministic: every node traps at the same depth after the same instruction
                // stream, so gas.used() here is identical network-wide.
                return ExecResult.reverted(gas.used(), "call depth limit exceeded");
            }
            if (isLocalsBudgetExceeded(e)) {
                // Deterministic locals-budget cap (audit V3): reserved before the frame allocates,
                // as a fixed network constant, so gas.used() is identical on every node — unlike the
                // host-heap-dependent OOM it replaces.
                return ExecResult.reverted(gas.used(), "locals budget exceeded");
            }
            if (isOutOfGas(e)) {
                return ExecResult.outOfGas(gas.used());
            }
            if (isStackExhausted(e)) {
                // Defence in depth: the tree-wide depth cap should trap first, but if Chicory ever
                // rewraps a real JVM StackOverflowError as ChicoryException("call stack exhausted")
                // it must not surface with node-local gas.used() — that would fork consensus. Pin it
                // to the same deterministic full-gas out-of-gas as the StackOverflowError catch.
                return ExecResult.outOfGas(gas.limit());
            }
            return ExecResult.reverted(gas.used(), e.getMessage());
        } finally {
            // Release this frame's share of the tree-wide page budget, whether it returned, reverted
            // or trapped — so sequential (non-nested) calls on the same thread don't leak pages.
            TREE_PAGES.get()[0] -= frameAdded[0];
        }
    }

    /**
     * Reserves {@code pages} from the tree-wide linear-memory budget, throwing (deterministic revert)
     * if the whole call tree would exceed {@link #TREE_MAX_PAGES}. Charged before the allocation so a
     * small-heap node never has to reach {@link OutOfMemoryError}; the numeric cap is identical on
     * every node, so the outcome cannot depend on the host's heap size.
     */
    private static void reserveTreePages(long pages, long[] frameAdded) {
        long[] tree = TREE_PAGES.get();
        if (tree[0] + pages > TREE_MAX_PAGES) {
            throw new IllegalStateException("contract call tree exceeds linear-memory budget: "
                + (tree[0] + pages) + " pages (max " + TREE_MAX_PAGES + ")");
        }
        tree[0] += pages;
        frameAdded[0] += pages;
    }

    /**
     * Runs {@code task} on a dedicated thread with a fixed, generous stack ({@link #EXEC_STACK_BYTES}).
     * A contract's whole call tree (including nested {@code call_contract} frames) executes on this
     * one thread, so the JVM stack size is a fixed network constant rather than the host's {@code -Xss}
     * — the missing half of the deterministic-recursion guarantee: the fixed stack is always large
     * enough to hold {@link DepthLimitedInterpreterMachine#MAX_WASM_CALL_DEPTH} frames, so the
     * deterministic depth trap always fires before any real {@code StackOverflowError}.
     */
    public static <T> T onBoundedStack(java.util.function.Supplier<T> task) {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try {
                result.set(task.get());
            } catch (Throwable e) {
                error.set(e);
            }
        }, "rhizome-wasm", EXEC_STACK_BYTES);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("contract execution interrupted", e);
        }
        Throwable e = error.get();
        if (e != null) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            if (e instanceof Error er) {
                throw er;
            }
            throw new IllegalStateException("contract execution failed", e);
        }
        return result.get();
    }

    private static boolean isDepthExceeded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof WasmCallDepthExceeded) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalsBudgetExceeded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof WasmLocalsBudgetExceeded) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code e} is Chicory's rewrapped JVM stack overflow ("call stack exhausted"). */
    private static boolean isStackExhausted(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("call stack exhausted")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates contract code at deploy time: size cap, parse, and non-determinism (float/SIMD)
     * scan. Throws on rejection; on success the module is parsed and cached for later calls.
     */
    public static void validateCode(byte[] wasmCode) {
        if (wasmCode.length > MAX_CODE_SIZE) {
            throw new IllegalArgumentException(
                "contract code too large: " + wasmCode.length + " > " + MAX_CODE_SIZE);
        }
        moduleFor(wasmCode, null);
    }

    /**
     * Returns the parsed, validated module for {@code wasmCode}, parsing on a cache miss. When a
     * {@code gas} meter is supplied (a runtime CALL, not deploy-time validation), the O(code) parse +
     * non-determinism/allocation scan is charged so cycling more distinct max-size contracts than the
     * LRU holds cannot force that work unpriced (audit vm F3).
     *
     * <p>The charge is levied on <em>every</em> runtime call — cache hit and miss alike — deliberately.
     * {@code gasUsed} feeds {@code gasFee}, hence the sender/miner balances and the authenticated state
     * root ({@code Executor.applyContract}), so it must be a pure function of the block's contents. The
     * {@code MODULE_CACHE} is a process-wide, non-persistent LRU whose occupancy differs across nodes
     * (cleared on restart, empty after a snapshot-sync pivot, evicted per local access history). Charging
     * only on a miss would make {@code gasUsed} — and thus the state root — depend on that node-local
     * state, so a validator with a cold cache would reject an honest block a warm producer built
     * ({@code INVALID_STATE_ROOT}) and fork off. The cache therefore stays a pure CPU optimization; the
     * fixed, length-derived parse cost is deterministic on every node (audit 5th-pass, VM Finding 1).
     */
    private static WasmModule moduleFor(byte[] wasmCode, GasMeter gas) {
        if (gas != null) {
            gas.charge(GasSchedule.MODULE_PARSE_BASE
                + (long) wasmCode.length * GasSchedule.MODULE_PARSE_PER_BYTE);
        }
        CodeKey key = new CodeKey(wasmCode);
        synchronized (MODULE_CACHE) {
            WasmModule cached = MODULE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        // Bound declared locals from the raw bytes BEFORE Parser.parse: the parser eagerly expands
        // every local group into a per-local list as it reads, so an unbounded aggregate would OOM
        // inside parse, before rejectOversizedAllocations could reject it (audit V1).
        preScanLocals(wasmCode);
        WasmModule module = Parser.parse(wasmCode);
        rejectNonDeterministic(module);
        rejectOversizedAllocations(module);
        synchronized (MODULE_CACHE) {
            MODULE_CACHE.put(key, module);
        }
        return module;
    }

    /**
     * Empties the process-wide module cache. Test-only hook: lets a test reproduce a cold-cache node
     * (fresh restart / post-snapshot pivot) and assert that {@code gasUsed} is identical warm vs cold.
     */
    static void clearModuleCacheForTest() {
        synchronized (MODULE_CACHE) {
            MODULE_CACHE.clear();
        }
    }

    /**
     * Builds a linear memory bounded by {@link #MAX_CONTRACT_PAGES} and charges gas for the
     * eagerly-allocated initial pages, so a module that declares a huge memory (or grows into
     * one) pays for it and can never exceed the cap. Rejecting an oversized initial declaration
     * here reverts the call rather than allocating gigabytes before the gas meter runs.
     */
    private static Memory boundedMemory(MemoryLimits requested, GasMeter gas, long[] frameAdded) {
        int initial = requested.initialPages();
        if (initial > MAX_CONTRACT_PAGES) {
            throw new IllegalArgumentException("contract declares too much initial memory: "
                + initial + " pages (max " + MAX_CONTRACT_PAGES + ")");
        }
        // Tree-wide reservation first: a fixed numeric cap, so a nested chain of contracts cannot
        // sum past TREE_MAX_PAGES no matter the host heap (the fork/OOM vector this closes).
        reserveTreePages(initial, frameAdded);
        gas.charge((long) initial * GasSchedule.MEMORY_PER_PAGE);
        int max = Math.min(Math.max(requested.maximumPages(), initial), MAX_CONTRACT_PAGES);
        return new ByteBufferMemory(new MemoryLimits(initial, max));
    }

    /**
     * Per-instruction gas metering. Most opcodes cost {@link GasSchedule#PER_INSTRUCTION};
     * bulk-memory ops ({@code memory.fill/copy/init}, {@code table.fill}) and the
     * {@code memory.grow}/{@code table.grow} ops do work proportional to a runtime operand
     * (the count on top of the value stack when this fires, before execution), so they are
     * charged by that operand — otherwise a single instruction could memset megabytes for 1 gas.
     */
    private static void meter(Instruction instruction, MStack stack, GasMeter gas, long[] frameAdded) {
        gas.charge(GasSchedule.PER_INSTRUCTION);
        switch (instruction.opcode()) {
            case MEMORY_FILL, MEMORY_COPY, MEMORY_INIT, TABLE_FILL, TABLE_COPY, TABLE_INIT -> {
                // O(N) bulk element/byte moves: charge by the runtime count operand so copying a
                // large table (or memory region) cannot cost a flat 1 gas (audit: table.copy/init
                // were previously unmetered).
                if (stack.size() > 0) {
                    gas.charge((stack.peek() & 0xFFFF_FFFFL) * GasSchedule.PER_BYTE);
                }
            }
            case MEMORY_GROW -> {
                if (stack.size() > 0) {
                    long requested = stack.peek() & 0xFFFF_FFFFL;
                    gas.charge(requested * GasSchedule.MEMORY_PER_PAGE);
                    // Count growth against the tree-wide budget too, before Chicory allocates it, so
                    // a grow chain across nested contracts cannot exceed TREE_MAX_PAGES on any heap.
                    reserveTreePages(requested, frameAdded);
                }
            }
            case TABLE_GROW -> {
                if (stack.size() > 0) {
                    gas.charge((stack.peek() & 0xFFFF_FFFFL) * GasSchedule.MEMORY_PER_PAGE);
                }
            }
            default -> { }
        }
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
     */
    private static void rejectNonDeterministic(WasmModule module) {
        var code = module.codeSection();
        if (code == null) {
            return;
        }
        for (int i = 0; i < code.functionBodyCount(); i++) {
            for (var instruction : code.getFunctionBody(i).instructions()) {
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
    private static void rejectOversizedAllocations(WasmModule module) {
        var tables = module.tableSection();
        if (tables != null) {
            int tableCount = tables.tableCount();
            // Cap the number of tables: each is eagerly allocated at instantiation, so an unbounded
            // count is an unmetered, heap-dependent allocation vector on its own (audit H4 residual).
            if (tableCount > MAX_TABLES) {
                throw new IllegalArgumentException("contract declares too many tables: "
                    + tableCount + " (max " + MAX_TABLES + ")");
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
                if (initial > MAX_TABLE_ENTRIES || ceiling > MAX_TABLE_ENTRIES) {
                    throw new IllegalArgumentException("contract declares too large a table: min "
                        + initial + " max " + ceiling + " entries (max " + MAX_TABLE_ENTRIES + ")");
                }
                totalEntries += ceiling;
                // Bound the AGGREGATE growth ceiling across all tables, not just each one: many
                // mid-size tables sum to the same multi-GB allocation a single oversized table would.
                if (totalEntries > MAX_TOTAL_TABLE_ENTRIES) {
                    throw new IllegalArgumentException("contract declares too many total table entries: "
                        + totalEntries + " (max " + MAX_TOTAL_TABLE_ENTRIES + ")");
                }
            }
        }
        var code = module.codeSection();
        if (code != null) {
            for (int i = 0; i < code.functionBodyCount(); i++) {
                int locals = code.getFunctionBody(i).localTypes().size();
                if (locals > MAX_FUNCTION_LOCALS) {
                    throw new IllegalArgumentException("contract function declares too many locals: "
                        + locals + " (max " + MAX_FUNCTION_LOCALS + ")");
                }
            }
        }
    }

    /**
     * Bounds the locals a module declares by reading the raw WASM byte stream, BEFORE handing it to
     * {@code Parser.parse}. This is the load-bearing half of the locals guard: Chicory's parser caps
     * each local-declaration group at 50 000 but not the number of groups, and it materialises every
     * group into a per-local list <em>as it parses</em> — so a &lt;{@link #MAX_CODE_SIZE} module can
     * declare billions of locals and OOM the node inside the parse, before {@link
     * #rejectOversizedAllocations} (which reads {@code localTypes().size()} on the already-built list)
     * can reject it (audit V1). We mirror the WASM code-section framing exactly and reject as soon as
     * a function's locals exceed {@link #MAX_FUNCTION_LOCALS} or the module total exceeds {@link
     * #MAX_MODULE_TOTAL_LOCALS}. Any framing we cannot interpret is left to {@code Parser.parse},
     * which fails closed with its own {@code MalformedException} before expanding a bad body — so this
     * method only ever <em>adds</em> a rejection, never masks one.
     */
    private static void preScanLocals(byte[] code) {
        // magic(4) + version(4); if absent, let the parser produce the canonical error.
        if (code.length < 8) {
            return;
        }
        int[] p = {8};
        long moduleLocals = 0;
        while (p[0] < code.length) {
            int sectionId = code[p[0]] & 0xFF;
            p[0]++;
            long sectionSize = readVarU32(code, p);
            if (sectionSize < 0) {
                return; // truncated LEB — defer to Parser
            }
            int sectionStart = p[0];
            long sectionEnd = (long) sectionStart + sectionSize;
            if (sectionEnd > code.length) {
                return; // declared size overruns the buffer — Parser will reject
            }
            if (sectionId == 10) { // Code section
                long funcCount = readVarU32(code, p);
                if (funcCount < 0) {
                    return;
                }
                for (long f = 0; f < funcCount; f++) {
                    long bodySize = readVarU32(code, p);
                    if (bodySize < 0) {
                        return;
                    }
                    long bodyEnd = (long) p[0] + bodySize;
                    if (bodyEnd > sectionEnd) {
                        return;
                    }
                    long groupCount = readVarU32(code, p);
                    if (groupCount < 0) {
                        return;
                    }
                    long funcLocals = 0;
                    for (long g = 0; g < groupCount; g++) {
                        long n = readVarU32(code, p);
                        if (n < 0 || p[0] >= code.length) {
                            return; // truncated locals vec — Parser rejects before expanding it
                        }
                        p[0]++; // valtype byte
                        funcLocals += n;
                        moduleLocals += n;
                        if (funcLocals > MAX_FUNCTION_LOCALS) {
                            throw new IllegalArgumentException("contract function declares too many locals: "
                                + funcLocals + " (max " + MAX_FUNCTION_LOCALS + ")");
                        }
                        if (moduleLocals > MAX_MODULE_TOTAL_LOCALS) {
                            throw new IllegalArgumentException("contract declares too many total locals: "
                                + moduleLocals + " (max " + MAX_MODULE_TOTAL_LOCALS + ")");
                        }
                    }
                    p[0] = (int) bodyEnd; // skip the function's expression to the next body
                }
                return; // one code section per module; done
            }
            p[0] = (int) sectionEnd; // skip non-code section
        }
    }

    /**
     * Reads an unsigned LEB128 uint32 at {@code p[0]}, advancing it. Returns {@code -1} (rather than
     * throwing) on a truncated or over-long encoding so {@link #preScanLocals} can defer that module
     * to {@code Parser.parse} for the canonical malformed-module error.
     */
    private static long readVarU32(byte[] data, int[] p) {
        long result = 0;
        int shift = 0;
        while (shift < 35) {
            if (p[0] >= data.length) {
                return -1;
            }
            int b = data[p[0]] & 0xFF;
            p[0]++;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result & 0xFFFF_FFFFL;
            }
            shift += 7;
        }
        return -1; // more than 5 bytes — malformed
    }

    private static boolean isOutOfGas(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OutOfGasException) {
                return true;
            }
        }
        return false;
    }

    private HostFunction[] hostFunctions(HostState host, GasMeter gas, ContractCallHandler calls) {
        HostFunction storageRead = new HostFunction(ENV, "storage_read",
            List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                int keyLen = asLen(args[1]);
                // Charge before touching memory so the work is metered even on a failing path.
                gas.charge(GasSchedule.STORAGE_READ_BASE + (long) keyLen * GasSchedule.PER_BYTE);
                byte[] key = mem.readBytes(asOffset(args[0]), keyLen);
                byte[] value = host.storageRead(key);
                if (value == null) {
                    return new long[] {-1L};
                }
                // Charge for the FULL value length, not just the copied bytes: host.storageRead
                // already materialised and cloned the whole value (O(valueLen) work), so metering
                // only `copied` let a caller pass out_cap = 0 and force repeated full loads of a
                // large value for the flat base cost — the same undercharge box_read was fixed for.
                gas.charge((long) value.length * GasSchedule.PER_BYTE);
                int outPtr = asOffset(args[2]);
                int outCap = asLen(args[3]);
                int copied = Math.min(value.length, outCap);
                if (copied > 0) {
                    mem.write(outPtr, value, 0, copied);
                }
                return new long[] {value.length};
            });

        HostFunction storageWrite = new HostFunction(ENV, "storage_write",
            List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                int keyLen = asLen(args[1]);
                int valLen = asLen(args[3]);
                gas.charge(GasSchedule.STORAGE_WRITE_BASE
                    + (long) (keyLen + valLen) * GasSchedule.PER_BYTE);
                if (keyLen == 0) {
                    // The empty storage key is reserved for the host-written deployer record that
                    // get_deployer reads (set once at deploy). Forbidding contracts from writing it
                    // makes the deployer identity unspoofable — a contract cannot overwrite its own
                    // recorded deployer to defeat an init access check (audit T1).
                    throw new IllegalArgumentException("empty storage key is reserved");
                }
                byte[] key = mem.readBytes(asOffset(args[0]), keyLen);
                byte[] value = mem.readBytes(asOffset(args[2]), valLen);
                host.storageWrite(key, value);
                return null;
            });

        HostFunction setOutput = new HostFunction(ENV, "set_output",
            List.of(ValType.I32, ValType.I32), List.of(),
            (Instance inst, long... args) -> {
                int len = asLen(args[1]);
                gas.charge(GasSchedule.OUTPUT_BASE + (long) len * GasSchedule.PER_BYTE);
                byte[] out = inst.memory().readBytes(asOffset(args[0]), len);
                host.setOutput(out);
                return null;
            });

        HostFunction emitLog = new HostFunction(ENV, "emit_log",
            List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                int topicLen = asLen(args[1]);
                int dataLen = asLen(args[3]);
                gas.charge(GasSchedule.LOG_BASE + (long) (topicLen + dataLen) * GasSchedule.PER_BYTE);
                byte[] topic = mem.readBytes(asOffset(args[0]), topicLen);
                byte[] data = mem.readBytes(asOffset(args[2]), dataLen);
                host.emitLog(topic, data);
                return null;
            });

        // Read the call context into contract memory. Each returns the source's true
        // length (so a contract can size its buffer), copying at most out_cap bytes.
        HostFunction getCaller = new HostFunction(ENV, "get_caller",
            List.of(ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> new long[] {copyOut(inst, host.caller(), args[0], args[1], gas)});

        HostFunction getInput = new HostFunction(ENV, "get_input",
            List.of(ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> new long[] {copyOut(inst, host.input(), args[0], args[1], gas)});

        HostFunction getValue = new HostFunction(ENV, "get_value",
            List.of(), List.of(ValType.I64),
            (Instance inst, long... args) -> new long[] {host.value()});

        HostFunction getSelf = new HostFunction(ENV, "get_self",
            List.of(ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> new long[] {copyOut(inst, host.selfAddress(), args[0], args[1], gas)});

        // get_deployer(out_ptr, out_cap) -> i32: the address that deployed this contract (recorded
        // at deploy, immutable). Copies up to out_cap bytes, returns the true length (0 if unknown).
        // Lets a template gate its init/one-time setup to the deployer so a mempool observer cannot
        // front-run init and seize the contract (audit T1).
        HostFunction getDeployer = new HostFunction(ENV, "get_deployer",
            List.of(ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> {
                // Charge the storage-read base like storage_read: deployer() performs a real backing-store
                // lookup, so without this a contract could loop get_deployer to read the store cheaper than
                // storage_read (copyOut alone charges only PER_BYTE). host.deployer() memoizes the value.
                gas.charge(GasSchedule.STORAGE_READ_BASE);
                return new long[] {copyOut(inst, host.deployer(), args[0], args[1], gas)};
            });

        // transfer_value(to_ptr, to_len, amount) -> i32: pays `amount` native coin from THIS
        // contract's own balance to the 25-byte address at to_ptr. Returns 0 on success, -1 if
        // rejected (unaffordable, bad recipient, or no ledger wired). The move is recorded and
        // applied by the executor on success; a revert discards it (audit T4).
        HostFunction transferValue = new HostFunction(ENV, "transfer_value",
            List.of(ValType.I32, ValType.I32, ValType.I64), List.of(ValType.I32),
            (Instance inst, long... args) -> {
                int toLen = asLen(args[1]);
                // Charge per byte of the contract-controlled `to` read BEFORE the readBytes allocation,
                // exactly like call_contract/storage_read/box_read/copyOut (audit M3). A flat CALL_BASE
                // let a hostile contract loop transfer_value(ptr, ~64 MiB, 0): each call allocated and
                // copied up to the memory cap for 500 gas, then returned -1 — ~10^5x underpriced, a
                // deterministic block-filling CPU/GC DoS that defeated gas-as-a-DoS-bound (audit S5).
                gas.charge(GasSchedule.CALL_BASE + (long) toLen * GasSchedule.PER_BYTE);
                byte[] to = inst.memory().readBytes(asOffset(args[0]), toLen);
                return new long[] {host.transferValue(to, args[2])};
            });

        // box_read(id_ptr, out_ptr, out_cap) -> i32: reads the 32-byte box id at id_ptr,
        // copies the serialized box (up to out_cap bytes) to out_ptr and returns its true
        // length, or -1 if no box exists. A read-only data input — the box is not consumed.
        HostFunction boxRead = new HostFunction(ENV, "box_read",
            List.of(ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                byte[] id = mem.readBytes(asOffset(args[0]), 32);
                gas.charge(GasSchedule.BOX_READ_BASE);
                rhizome.core.box.Box box = host.boxRead(id);
                if (box == null) {
                    return new long[] {-1L};
                }
                // serialize() is O(box size) work; copyOut now charges the full serialized length
                // (a caller passing out_cap = 0 is still billed for the whole box, not just copied
                // bytes), so no separate length charge is needed here beyond the base.
                byte[] serialized = box.serialize();
                return new long[] {copyOut(inst, serialized, args[1], args[2], gas)};
            });

        // call_contract(addr_ptr, addr_len, in_ptr, in_len, out_ptr, out_cap) -> i32:
        // the callee's output length (copied up to out_cap bytes), or -1 if the call
        // failed. The dispatcher runs the callee in its own state frame, so a failed
        // call leaves no trace; gas is shared with this meter, so nested work draws
        // from the same budget (forwarded gas, no resurrection).
        HostFunction callContract = new HostFunction(ENV, "call_contract",
            List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32),
            List.of(ValType.I32),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                int calleeLen = asLen(args[1]);
                int inputLen = asLen(args[3]);
                gas.charge(GasSchedule.CALL_BASE + (long) inputLen * GasSchedule.PER_BYTE);
                byte[] callee = mem.readBytes(asOffset(args[0]), calleeLen);
                byte[] input = mem.readBytes(asOffset(args[2]), inputLen);
                byte[] output = calls == null ? null : calls.call(callee, input);
                if (output == null) {
                    return new long[] {-1L};
                }
                return new long[] {copyOut(inst, output, args[4], args[5], gas)};
            });

        return new HostFunction[] {
            storageRead, storageWrite, setOutput, emitLog, getCaller, getInput, getValue, getSelf,
            getDeployer, transferValue, callContract, boxRead};
    }

    /**
     * Copies {@code src} into contract memory (at most {@code cap} bytes) and returns its true length.
     * Charges for the FULL source length, not just the copied bytes: the host already produced the
     * whole {@code src} (e.g. cloning the call input), so billing only {@code copied} let a caller
     * pass {@code cap = 0} and force that O(src) work for near-zero gas in a loop (audit M3, the same
     * undercharge fixed for storage_read/box_read).
     */
    private static long copyOut(Instance inst, byte[] src, long ptr, long cap, GasMeter gas) {
        gas.charge((long) src.length * GasSchedule.PER_BYTE); // meter the full source, before the write
        int copied = Math.min(src.length, asLen(cap));
        if (copied > 0) {
            inst.memory().write(asOffset(ptr), src, 0, copied);
        }
        return src.length;
    }

    /** A WASM i32 pointer arrives as a long; take the unsigned low 32 bits as a memory offset. */
    private static int asOffset(long i32) {
        return (int) (i32 & 0xFFFF_FFFFL);
    }

    private static int asLen(long i32) {
        int len = (int) (i32 & 0xFFFF_FFFFL);
        if (len < 0) {
            throw new IllegalArgumentException("negative length from contract");
        }
        return len;
    }
}
