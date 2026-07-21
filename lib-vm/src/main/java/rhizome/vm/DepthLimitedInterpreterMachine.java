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
 * <p>Capping {@code callStack.size()} at a fixed {@link #MAX_WASM_CALL_DEPTH} traps at the exact
 * same depth on every node after the exact same (deterministic) instruction sequence. Paired
 * with running execution on a fixed-size stack (see {@link WasmVm}), the JVM stack can never
 * overflow before this limit is hit, so the outcome is fully deterministic.
 */
final class DepthLimitedInterpreterMachine extends InterpreterMachine {

    /**
     * Maximum nesting of WASM function activations. Chosen well below what the fixed
     * {@link WasmVm#EXEC_STACK_BYTES} execution stack can hold, so the deterministic trap always
     * fires before any JVM {@code StackOverflowError}, yet far above any legitimate contract's
     * recursion (real contracts iterate, they do not recurse thousands deep).
     */
    static final int MAX_WASM_CALL_DEPTH = 1024;

    DepthLimitedInterpreterMachine(Instance instance) {
        super(instance);
    }

    @Override
    protected long[] call(MStack stack, Instance instance, Deque<StackFrame> callStack,
                          int funcId, long[] args, FunctionType type, boolean popArgs)
            throws ChicoryException {
        if (callStack.size() >= MAX_WASM_CALL_DEPTH) {
            throw new WasmCallDepthExceeded();
        }
        return super.call(stack, instance, callStack, funcId, args, type, popArgs);
    }
}
