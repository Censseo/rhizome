package rhizome.vm;

import com.dylibso.chicory.wasm.ChicoryException;

/**
 * Thrown when a contract's WebAssembly call stack reaches
 * {@link DepthLimitedInterpreterMachine#MAX_WASM_CALL_DEPTH}. This is a <em>deterministic</em>
 * limit — every node traps at the identical depth after the identical instruction sequence —
 * so it replaces the JVM {@code StackOverflowError}, whose trip point depends on {@code -Xss},
 * the JVM build and JIT state and would otherwise fork consensus. Extends
 * {@link ChicoryException} so it unwinds the interpreter cleanly; {@link WasmVm} recognises it
 * and turns it into a deterministic revert.
 */
final class WasmCallDepthExceeded extends ChicoryException {
    WasmCallDepthExceeded() {
        super("call depth limit exceeded");
    }
}
