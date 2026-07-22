package rhizome.vm;

import com.dylibso.chicory.wasm.ChicoryException;

/**
 * Thrown when the locals live across a contract's whole call tree would exceed
 * {@link WasmVm#MAX_TREE_LIVE_LOCALS}. Like {@link WasmCallDepthExceeded} this is a
 * <em>deterministic</em> resource cap — a fixed network constant, reserved before the interpreter
 * allocates the frame's locals arrays — so it replaces the host-heap-dependent
 * {@link OutOfMemoryError} that a locals-heavy recursion would otherwise raise at a per-node depth,
 * forking consensus (audit V3). Extends {@link ChicoryException} so it unwinds the interpreter
 * cleanly; {@link WasmVm} recognises it and turns it into a deterministic revert.
 */
final class WasmLocalsBudgetExceeded extends ChicoryException {
    WasmLocalsBudgetExceeded() {
        super("locals budget exceeded");
    }
}
