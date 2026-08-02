package rhizome.core.blockchain;

/**
 * Test/simulation access to {@link ChainEngine}'s package-private chain mutation primitives.
 *
 * <p>{@link ChainEngine#popBlock()} is package-private on purpose (audit: unguarded public
 * popBlock): truncating the canonical chain is a synchronizer-only operation, and reducing the
 * visibility makes any future accidental caller a compile error rather than a live chain
 * truncation. Tests and crash-recovery simulations — including those in other modules, which
 * cannot see package-private members — go through this explicit bridge instead, so every
 * non-synchronizer pop is greppable as deliberate.
 */
public final class ChainEngineTestAccess {

    private ChainEngineTestAccess() {
    }

    /** Pops the tip block exactly as {@link ChainEngine#popBlock()} does — tests only. */
    public static void popBlock(ChainEngine engine) {
        engine.popBlock();
    }
}
