package rhizome.core.blockchain;

/**
 * Test-only bridge to {@link ChainEngine}'s package-private reorg-window controls, for tests that
 * live outside {@code rhizome.core.blockchain} (the node's HTTP tests, which need a node whose
 * chain is genuinely mid-reorg to assert the 503 gate).
 *
 * <p>It exists so those tests do not reach in with {@code setAccessible(true)}: reflection there
 * would be brittle against a rename the compiler would otherwise catch, and it is exactly what
 * this codebase avoids everywhere else to stay GraalVM-native-friendly. Being a test source, it
 * widens nothing in the shipped jar — the production methods stay package-private.
 */
public final class ReorgWindowTestAccess {

    private ReorgWindowTestAccess() {}

    /** Opens a reorg window on {@code engine}; false if one is already open. */
    public static boolean begin(ChainEngine engine) {
        return engine.beginReorgWindow();
    }

    /** Closes the window opened by {@link #begin}. */
    public static void end(ChainEngine engine) {
        engine.endReorgWindow();
    }
}
