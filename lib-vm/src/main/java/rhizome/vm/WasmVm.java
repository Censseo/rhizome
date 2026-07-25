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
     * Hard cap on any single host-side buffer a contract can make the node allocate (bytes).
     * Host functions read contract-controlled lengths (storage keys/values, log data, the
     * transfer_value address, call_contract callee+input, copied-out sources) and materialise
     * them as {@code new byte[len]}. Before this cap, {@code len} was bounded only by the gas
     * remaining — tens of MB with a large gas budget — so whether the allocation succeeded or
     * threw {@link OutOfMemoryError} depended on each node's {@code -Xmx}: a large-heap node
     * returned OK, a small-heap node hit the OOM path, and normalizing that OOM to out-of-gas
     * made {@code gasUsed} heap-dependent — a state-root fork between validators (audit: host
     * allocations proportional to gas). The per-byte gas charge on the length is levied FIRST
     * (so a huge length is always expensive), then {@link #capHostBuffer} turns any length above
     * this fixed network constant into a deterministic full-gas out-of-gas BEFORE the allocation
     * — identical on every node regardless of local heap pressure. 1 MiB is comfortably above
     * anything a legitimate host call needs (storage values, log payloads, call I/O) and small
     * enough that 8 nested frames' worth of concurrent buffers is a few MB worst case, in the
     * style of {@link #TREE_MAX_PAGES} for linear memory.
     */
    static final int HOST_BUFFER_CAP = 1024 * 1024;

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
     * Hard cap on a function type's declared parameter count. Chicory's {@code StackFrame}
     * allocates a {@code (params+locals)}-sized array per activation, and Chicory does NOT enforce
     * its own {@code WasmLimits.MAX_FUNCTION_PARAMS} (1000), so an uncapped param count was an
     * unmetered per-frame allocation the tree-wide locals budget never saw — ~87k i64 params pins
     * ~1.4 MB per frame, a heap-dependent OOM → divergent gasUsed → consensus fork (audit F2). The
     * tree-wide reservation now counts params (see {@link DepthLimitedInterpreterMachine}); this
     * deploy-time cap keeps that reservation small. Matches Chicory's own (unenforced) limit.
     */
    static final int MAX_FUNCTION_PARAMS = 1_000;

    /**
     * Hard cap on a module's declared globals. Each global is materialised as a
     * {@code GlobalInstance} at instantiation — before any gas is charged — so an unbounded count
     * is an unmetered, heap-dependent allocation vector (audit F7). Generous: real contracts
     * declare a handful of globals.
     */
    static final int MAX_GLOBALS = 4_096;

    /**
     * Hard caps on a module's declared function, import and export counts (audit F7). Instantiation
     * eagerly builds per-entry structures for all three (the function-type index array, the resolved
     * {@code ImportValues}, the export map) before the gas meter runs, and Chicory enforces no count
     * limit of its own at parse, so a &lt;{@link #MAX_CODE_SIZE} module could declare ~10^5 entries
     * and force that allocation unmetered on every CALL. These bounds keep the instantiation
     * footprint a small deterministic network constant, in the style of {@link #MAX_TABLES}.
     */
    static final int MAX_MODULE_FUNCTIONS = 16_384;
    static final int MAX_MODULE_IMPORTS = 1_024;
    static final int MAX_MODULE_EXPORTS = 1_024;

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
        // The linear memory this instance builds, captured so the memory.grow meter can read its
        // current/maximum pages and reserve only what a grow will actually commit (see meter).
        Memory[] memHolder = new Memory[1];
        try {
            Instance instance = Instance.builder(module)
                .withImportValues(imports)
                .withStart(false)
                // Cap and meter linear memory so a contract cannot allocate gigabytes — per-instance
                // AND tree-wide (see TREE_MAX_PAGES) so nested calls cannot sum past the budget.
                .withMemoryFactory(limits -> {
                    Memory m = boundedMemory(limits, gas, frameAdded);
                    memHolder[0] = m;
                    return m;
                })
                // Deterministic WASM call-depth cap: traps unbounded recursion at a fixed depth
                // on every node, replacing the JVM-stack-dependent StackOverflowError that would
                // otherwise fork consensus (see DepthLimitedInterpreterMachine).
                .withMachineFactory(DepthLimitedInterpreterMachine::new)
                // Meter every instruction; bulk-memory / memory.grow are charged by their
                // runtime operand, not a flat 1, so O(N) work cannot cost O(1) gas.
                .withUnsafeExecutionListener((instruction, stack) -> meter(instruction, stack, gas, frameAdded, memHolder))
                .build();

            ExportFunction call = instance.export(ENTRY);
            call.apply();
            return ExecResult.ok(host.output(), host.logs(), gas.used());
        } catch (OutOfMemoryError e) {
            // Fatal, never normalized to out-of-gas. After the host-buffer cap (HOST_BUFFER_CAP)
            // and the tree-wide memory/locals/table budgets, every contract-driven allocation is
            // bounded by a fixed network constant reserved BEFORE allocation, so an OOM here means
            // the NODE itself is out of heap — not that the contract exceeded a budgeted constant.
            // Converting it to out-of-gas would make gasUsed depend on the local -Xmx (a large-heap
            // node completes with partial gas, a small-heap one reports full gas) and FORK consensus;
            // a crash is preferable to a fork (audit: heap-dependent gasUsed).
            throw new IllegalStateException("host out of memory during contract execution", e);
        } catch (StackOverflowError e) {
            // Defence in depth: recursion that somehow outran the deterministic depth cap. The exact
            // stack at which a given JVM trips is host-specific, so normalize to a deterministic
            // full-gas out-of-gas rather than a node-local outcome.
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
     * Small pool of reusable workers, each with a fixed, generous stack ({@link
     * #EXEC_STACK_BYTES}), that every contract execution runs on. A contract's whole call tree
     * (including nested {@code call_contract} frames) executes on one of these threads, so the
     * JVM stack size is a fixed network constant rather than the host's {@code -Xss} — the
     * missing half of the deterministic-recursion guarantee: the fixed stack is always large
     * enough to hold {@link DepthLimitedInterpreterMachine#MAX_WASM_CALL_DEPTH} frames, so the
     * deterministic depth trap always fires before any real {@code StackOverflowError}.
     *
     * <p>Reused across calls: the previous per-call {@code new Thread(…, 64MB)} paid thread
     * creation plus a 64 Mio stack mapping per CALL, per dry-run and twice per produced block
     * (stamp + apply) on the consensus path (audit perf). More than one worker (not a single
     * thread): a long dry-run ({@code /call_readonly}, up to the 25M-gas budget) must not queue
     * block validation behind it now that the API handlers are offloaded to a worker pool —
     * the two fixes would otherwise work against each other (audit review). The executor
     * recreates a thread if it dies (e.g. a fatal {@link OutOfMemoryError}), and the tree-page
     * budget above is finally-balanced per frame, so no ThreadLocal state leaks between
     * executions. Concurrent executions are independent (the page budget is a ThreadLocal).
     */
    private static final int VM_WORKER_THREADS = 2;

    private static final java.util.concurrent.ExecutorService BOUNDED_STACK_WORKER =
        java.util.concurrent.Executors.newFixedThreadPool(VM_WORKER_THREADS, new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(null, r, "rhizome-wasm-" + seq.incrementAndGet(), EXEC_STACK_BYTES);
                t.setDaemon(true);
                return t;
            }
        });

    public static <T> T onBoundedStack(java.util.function.Supplier<T> task) {
        java.util.concurrent.Future<T> future = BOUNDED_STACK_WORKER.submit(task::get);
        try {
            return future.get();
        } catch (InterruptedException e) {
            // Interrupt the worker too: otherwise it keeps running a 50M-gas execution detached
            // from the interrupted caller. If the interpreter never observes the interrupt the
            // gas budget still bounds the run; subsequent calls queue behind it (as they would
            // behind any in-flight execution).
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("contract execution interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error er) {
                throw er;
            }
            throw new IllegalStateException("contract execution failed", cause);
        }
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
        rejectWasmGc(module);
        rejectNonDeterministic(module);
        rejectOversizedAllocations(module);
        rejectNonWhitelistedAbi(module);
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
        checkDeclaredInitialPages(initial);
        // Tree-wide reservation first: a fixed numeric cap, so a nested chain of contracts cannot
        // sum past TREE_MAX_PAGES no matter the host heap (the fork/OOM vector this closes).
        reserveTreePages(initial, frameAdded);
        gas.charge((long) initial * GasSchedule.MEMORY_PER_PAGE);
        int max = Math.min(Math.max(requested.maximumPages(), initial), MAX_CONTRACT_PAGES);
        return new ByteBufferMemory(new MemoryLimits(initial, max));
    }

    /**
     * Rejects an initial-memory declaration above {@link #MAX_CONTRACT_PAGES}. Shared by {@link
     * #boundedMemory} (runtime instantiation, where it reverts the call rather than allocating
     * gigabytes before the gas meter runs) and {@link #rejectOversizedAllocations} (deploy-time
     * validation, where the same module is refused before it ever reaches the store) so both
     * enforce exactly the same cap.
     */
    private static void checkDeclaredInitialPages(int initial) {
        if (initial > MAX_CONTRACT_PAGES) {
            throw new IllegalArgumentException("contract declares too much initial memory: "
                + initial + " pages (max " + MAX_CONTRACT_PAGES + ")");
        }
    }

    /**
     * Per-instruction gas metering. Most opcodes cost {@link GasSchedule#PER_INSTRUCTION};
     * bulk-memory ops ({@code memory.fill/copy/init}, {@code table.fill}) and the
     * {@code memory.grow}/{@code table.grow} ops do work proportional to a runtime operand
     * (the count on top of the value stack when this fires, before execution), so they are
     * charged by that operand — otherwise a single instruction could memset megabytes for 1 gas.
     */
    private static void meter(Instruction instruction, MStack stack, GasMeter gas, long[] frameAdded,
                              Memory[] memHolder) {
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
                    // Charge by the operand even if the grow will fail: the anti-DoS invariant is that
                    // a large operand can never be cheap, and the charge must be deterministic.
                    gas.charge(requested * GasSchedule.MEMORY_PER_PAGE);
                    // Reserve tree-wide pages BEFORE Chicory allocates, but only the amount the grow will
                    // actually commit. WASM memory.grow is all-or-nothing: it commits `requested` iff
                    // pages()+requested <= maximumPages(), else it fails (returns -1) and commits nothing.
                    // Reserving `requested` unconditionally (the old behavior) left phantom pages held
                    // against TREE_MAX_PAGES for a grow that never allocated — deterministic, but it could
                    // make a later legitimate grow in the same call tree spuriously trip the budget
                    // (audit VM #4). Reserve 0 when the grow cannot fit the instance cap.
                    Memory mem = memHolder[0];
                    long committable = requested;
                    if (mem != null && requested > (long) mem.maximumPages() - mem.pages()) {
                        committable = 0;
                    }
                    if (committable > 0) {
                        reserveTreePages(committable, frameAdded);
                    }
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
     * The scan covers every place an opcode can appear: function bodies, global init expressions
     * and element initializers/offsets (the latter two evaluate at instantiation, unmetered).
     */
    private static void rejectNonDeterministic(WasmModule module) {
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
     * None of the deterministic budgets ({@link #TREE_MAX_PAGES}, {@link #MAX_TREE_LIVE_LOCALS},
     * the table caps) see this memory: whether it OOMs depends on each node's {@code -Xmx}, so a
     * large-heap node succeeds with a tiny {@code gasUsed} while a small-heap validator OOMs
     * (normalized to full-gas out-of-gas) — divergent {@code gasUsed} → divergent state root →
     * consensus fork — and an uncaught OOM can kill any node thread. The bulk GC ops
     * ({@code array.copy/fill/new_data/new_elem}) also do O(n) memcpy for a flat 1 gas (audit H1),
     * and const expressions in global/element initializers evaluate {@code array.new} at
     * INSTANTIATION, before any metering. Contracts are integer + linear-memory only by
     * construction, so the whole family is refused: GC comp types in the type section, and GC
     * opcodes in function bodies, global init expressions and element initializers/offsets.
     */
    private static void rejectWasmGc(WasmModule module) {
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
        // Cap declared params per function type: Chicory sizes each StackFrame's locals array as
        // (params+locals) but enforces no param cap of its own, so an oversized type was an
        // unmetered per-frame allocation the locals budget never counted (audit F2).
        var types = module.typeSection();
        if (types != null) {
            for (int i = 0; i < types.typeCount(); i++) {
                int params = types.getType(i).params().size();
                if (params > MAX_FUNCTION_PARAMS) {
                    throw new IllegalArgumentException("contract function type declares too many params: "
                        + params + " (max " + MAX_FUNCTION_PARAMS + ")");
                }
            }
        }
        // Cap the remaining section counts Chicory leaves unenforced: globals, functions, imports
        // and exports are all materialised per-entry at instantiation — unmetered, heap-dependent
        // allocation unless bounded here as a deterministic network constant (audit F7).
        var globals = module.globalSection();
        if (globals != null && globals.globalCount() > MAX_GLOBALS) {
            throw new IllegalArgumentException("contract declares too many globals: "
                + globals.globalCount() + " (max " + MAX_GLOBALS + ")");
        }
        var functions = module.functionSection();
        if (functions != null && functions.functionCount() > MAX_MODULE_FUNCTIONS) {
            throw new IllegalArgumentException("contract declares too many functions: "
                + functions.functionCount() + " (max " + MAX_MODULE_FUNCTIONS + ")");
        }
        var imports = module.importSection();
        if (imports != null && imports.importCount() > MAX_MODULE_IMPORTS) {
            throw new IllegalArgumentException("contract declares too many imports: "
                + imports.importCount() + " (max " + MAX_MODULE_IMPORTS + ")");
        }
        var exports = module.exportSection();
        if (exports != null && exports.exportCount() > MAX_MODULE_EXPORTS) {
            throw new IllegalArgumentException("contract declares too many exports: "
                + exports.exportCount() + " (max " + MAX_MODULE_EXPORTS + ")");
        }
        // Deploy-time mirror of the runtime boundedMemory cap: a declared initial memory above
        // MAX_CONTRACT_PAGES must be refused here, not only discovered on every later call (the
        // same check, shared via checkDeclaredInitialPages, so the two can never drift).
        var memories = module.memorySection();
        if (memories.isPresent()) {
            for (int i = 0; i < memories.get().memoryCount(); i++) {
                checkDeclaredInitialPages(memories.get().getMemory(i).limits().initialPages());
            }
        }
    }

    /**
     * The host ABI names a contract may import from module {@code "env"} — exactly the functions
     * {@link #hostFunctions} provides. Deploy-time validation rejects anything else (audit:
     * validateCode controlled neither imports nor exports), so a module demanding an unknown host
     * capability — or importing a memory/table/global instead of a function — never enters on-chain
     * state; instantiation would fail it later anyway, but as a per-call revert rather than a
     * one-time deploy rejection.
     */
    private static final java.util.Set<String> HOST_IMPORTS = java.util.Set.of(
        "storage_read", "storage_write", "set_output", "emit_log", "get_caller", "get_input",
        "get_value", "get_self", "get_deployer", "transfer_value", "call_contract", "box_read");

    /**
     * Enforces the sandbox ABI at validation time: every import must be a FUNCTION import of a
     * whitelisted {@code env.*} host name, and the module must export the {@code call} entry point
     * the executor invokes. Runs AFTER the other rejections so their more specific diagnostics
     * (float/SIMD, tables, locals, …) keep firing first on modules that violate both.
     */
    private static void rejectNonWhitelistedAbi(WasmModule module) {
        var imports = module.importSection();
        if (imports != null) {
            for (int i = 0; i < imports.importCount(); i++) {
                var imp = imports.getImport(i);
                if (imp.importType() != com.dylibso.chicory.wasm.types.ExternalType.FUNCTION
                        || !ENV.equals(imp.module()) || !HOST_IMPORTS.contains(imp.name())) {
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
                if (ENTRY.equals(export.name())
                        && export.exportType() == com.dylibso.chicory.wasm.types.ExternalType.FUNCTION) {
                    hasCall = true;
                    break;
                }
            }
        }
        if (!hasCall) {
            throw new IllegalArgumentException("contract does not export the \"" + ENTRY
                + "\" entry point");
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

    /**
     * Turns a contract-controlled buffer length above {@link #HOST_BUFFER_CAP} into a deterministic
     * full-gas out-of-gas. Must be called AFTER the per-byte charge on the length (a huge length is
     * always expensive) and BEFORE the {@code byte[len]} allocation it sizes: the cap is a fixed
     * network constant, so every node rejects at exactly the same length with exactly the same
     * gasUsed (the full limit), never a heap-dependent {@link OutOfMemoryError} (audit: host
     * allocations proportional to gas).
     */
    static void capHostBuffer(long len, GasMeter gas) {
        if (len > HOST_BUFFER_CAP) {
            // Saturating charge: used becomes the limit and OutOfGasException is thrown, so the
            // outcome is a full-gas out-of-gas identical on every node.
            gas.charge(gas.remaining() + 1);
        }
    }

    private HostFunction[] hostFunctions(HostState host, GasMeter gas, ContractCallHandler calls) {
        HostFunction storageRead = new HostFunction(ENV, "storage_read",
            List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32),
            (Instance inst, long... args) -> {
                Memory mem = inst.memory();
                int keyLen = asLen(args[1]);
                // Charge before touching memory so the work is metered even on a failing path.
                gas.charge(GasSchedule.STORAGE_READ_BASE + (long) keyLen * GasSchedule.PER_BYTE);
                capHostBuffer(keyLen, gas);
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
                // Values written after the cap can never exceed it (storage_write enforces it), so
                // this is defence in depth for pre-existing state; deterministic either way.
                capHostBuffer(value.length, gas);
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
                // Long-cast BEFORE the add: both lengths are contract-controlled ints, so the sum
                // can overflow int before the cast and undercharge a huge write (audit F4). The
                // written bytes become permanent on-chain state, so they pay STORAGE_WRITE_PER_BYTE,
                // not the transient PER_BYTE rate (audit F5).
                gas.charge(GasSchedule.STORAGE_WRITE_BASE
                    + ((long) keyLen + valLen) * GasSchedule.STORAGE_WRITE_PER_BYTE);
                capHostBuffer(keyLen, gas);
                capHostBuffer(valLen, gas);
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
                capHostBuffer(len, gas);
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
                // Long-cast BEFORE the add so the contract-controlled length sum cannot overflow
                // int before the cast and undercharge a huge log (audit F4).
                gas.charge(GasSchedule.LOG_BASE + ((long) topicLen + dataLen) * GasSchedule.PER_BYTE);
                capHostBuffer(topicLen, gas);
                capHostBuffer(dataLen, gas);
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
                capHostBuffer(toLen, gas);
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
                // Charge before touching guest memory, matching every other host fn (the id is a fixed
                // 32 bytes so this is not an undercharge, only an ordering consistency fix).
                gas.charge(GasSchedule.BOX_READ_BASE);
                byte[] id = mem.readBytes(asOffset(args[0]), 32);
                rhizome.core.box.Box box = host.boxRead(id);
                if (box == null) {
                    return new long[] {-1L};
                }
                // Charge the KNOWN serialized size BEFORE serialize() materialises the copy —
                // serializedSize() is O(registers) metadata with no allocation, so the
                // "charge then work" invariant every other host fn keeps now holds here too
                // (previously serialize() ran O(box size) work before the per-byte charge, and a
                // gas-insufficient caller paid for the serialization anyway). The cap check also
                // precedes the allocation, as everywhere else.
                long size = box.serializedSize();
                gas.charge(size * GasSchedule.PER_BYTE);
                capHostBuffer(size, gas);
                byte[] serialized = box.serialize();
                return new long[] {copyOutCharged(inst, serialized, args[1], args[2])};
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
                // Charge per byte of BOTH contract-controlled reads — calleeLen included — before
                // either readBytes allocation, exactly like transfer_value/storage_read (audit F1):
                // metering only inputLen let a contract drive a `new byte[calleeLen]` alloc+copy
                // for free. Long-cast BEFORE the add so the length sum cannot overflow int (F4).
                // A wrong-length callee address simply resolves to nothing: the dispatcher returns
                // null and the call reports -1, so metering (not rejection) is the fix here.
                gas.charge(GasSchedule.CALL_BASE + ((long) calleeLen + inputLen) * GasSchedule.PER_BYTE);
                capHostBuffer(calleeLen, gas);
                capHostBuffer(inputLen, gas);
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
        capHostBuffer(src.length, gas);
        return copyOutCharged(inst, src, ptr, cap);
    }

    /** The copy half of {@link #copyOut}, for callers that already charged the source length. */
    private static long copyOutCharged(Instance inst, byte[] src, long ptr, long cap) {
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
