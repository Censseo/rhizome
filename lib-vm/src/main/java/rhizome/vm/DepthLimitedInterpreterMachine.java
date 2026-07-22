package rhizome.vm;

import java.util.Deque;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.InterpreterMachine;
import com.dylibso.chicory.runtime.MStack;
import com.dylibso.chicory.runtime.StackFrame;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.types.FunctionType;

/**
 * A Chicory interpreter that caps WebAssembly call-stack depth deterministically.
 *
 * <p>Chicory dispatches every WASM {@code call}/{@code call_indirect} — including a function
 * recursing into itself — through the protected {@link InterpreterMachine#call} method via an
 * {@code invokevirtual}, so overriding it intercepts each frame push. Unbounded intra-contract
 * recursion would otherwise overflow the <em>JVM</em> thread stack; Chicory rewraps that
 * {@code StackOverflowError} as a {@code ChicoryException}, and the depth at which it trips
 * depends on {@code -Xss}, the JVM build and JIT state — so two nodes could disagree on whether
 * a contract reverted (and on the gas it burned), forking consensus.
 *
 * <p>Capping WASM activations at a fixed {@link #MAX_WASM_CALL_DEPTH} traps at the exact
 * same depth on every node after the exact same (deterministic) instruction sequence. Paired
 * with running execution on a fixed-size stack (see {@link WasmVm}), the JVM stack can never
 * overflow before this limit is hit, so the outcome is fully deterministic.
 *
 * <p><b>The cap is tree-wide, not per instance.</b> Chicory's {@code callStack} lives on the
 * {@code Instance}, and a fresh {@code Instance}/machine is built for every {@code vm.execute} —
 * including every nested {@code call_contract}. Counting only this instance's {@code callStack}
 * would let a chain of {@code MAX_CALL_DEPTH} contracts stack {@code MAX_CALL_DEPTH × MAX_WASM_CALL_DEPTH}
 * real JVM frames on the one execution thread, reopening the stack-overflow fork the class exists
 * to close (audit). Instead every frame across the whole call tree is counted through a
 * thread-local depth (the entire tree runs on a single {@code rhizome-wasm} thread), so the true
 * worst case is bounded by {@link #MAX_WASM_CALL_DEPTH} — exactly what {@link WasmVm#EXEC_STACK_BYTES}
 * is sized to hold.
 */
final class DepthLimitedInterpreterMachine extends InterpreterMachine {

    /**
     * Maximum nesting of WASM function activations across the entire call tree. Chosen well below
     * what the fixed {@link WasmVm#EXEC_STACK_BYTES} execution stack can hold, so the deterministic
     * trap always fires before any JVM {@code StackOverflowError}, yet far above any legitimate
     * contract's recursion (real contracts iterate, they do not recurse thousands deep).
     */
    static final int MAX_WASM_CALL_DEPTH = 1024;

    /**
     * Frames currently live across all nested {@code Instance}s on this execution thread. The whole
     * call tree (including {@code call_contract} descents) runs on one {@code rhizome-wasm} thread,
     * so a thread-local is a correct tree-wide counter; it always returns to its entry value because
     * every increment is paired with a {@code finally} decrement.
     */
    private static final ThreadLocal<int[]> TREE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * Locals currently live across every activation on this thread's call tree. Each interpreter
     * frame allocates {@code (params+locals)}-sized arrays, and depth alone ({@link
     * #MAX_WASM_CALL_DEPTH}) times {@link WasmVm#MAX_FUNCTION_LOCALS} would hold ~160 MiB for ~1 K
     * gas — an unmetered, heap-dependent spike that forks consensus (small-heap node OOMs → full-gas
     * out-of-gas; large-heap node reverts → partial gas). Reserving locals against a fixed tree-wide
     * cap makes the ceiling a deterministic network constant, mirroring {@link WasmVm}'s page budget
     * (audit V3). The whole tree runs on one {@code rhizome-wasm} thread, so a thread-local is a
     * correct tree-wide counter; every increment is paired with a {@code finally} decrement.
     */
    private static final ThreadLocal<long[]> TREE_LOCALS = ThreadLocal.withInitial(() -> new long[1]);

    DepthLimitedInterpreterMachine(Instance instance) {
        super(instance);
    }

    @Override
    protected long[] call(MStack stack, Instance instance, Deque<StackFrame> callStack,
                          int funcId, long[] args, FunctionType type, boolean popArgs)
            throws ChicoryException {
        int[] depth = TREE_DEPTH.get();
        if (depth[0] >= MAX_WASM_CALL_DEPTH) {
            throw new WasmCallDepthExceeded();
        }
        // Reserve this activation's locals against the tree-wide budget before the frame allocates
        // them, so a locals-heavy recursion is bounded to a deterministic ceiling on every node
        // rather than OOM-ing memory-constrained validators at a host-specific depth (audit V3).
        long frameLocals = localCount(instance, funcId);
        long[] locals = TREE_LOCALS.get();
        if (locals[0] + frameLocals > WasmVm.MAX_TREE_LIVE_LOCALS) {
            throw new WasmLocalsBudgetExceeded();
        }
        depth[0]++;
        locals[0] += frameLocals;
        try {
            return super.call(stack, instance, callStack, funcId, args, type, popArgs);
        } finally {
            depth[0]--;
            locals[0] -= frameLocals;
        }
    }

    /**
     * Declared local count of the function {@code funcId} in {@code instance}, or 0 when it is an
     * imported (host) function — those run as plain Java and allocate no WASM locals array, and
     * {@code instance.function} has no body for them. Any lookup failure yields 0 (the depth cap
     * still bounds the tree), so this can only ever add safety, never reject a legitimate call.
     */
    private static long localCount(Instance instance, int funcId) {
        try {
            var body = instance.function(funcId);
            return body == null ? 0L : body.localTypes().size();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
