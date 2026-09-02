package rhizome.core.blockchain;

import java.util.List;

import rhizome.core.block.BlockImpl;
import rhizome.core.block.UncleRef;

/**
 * The one place every hand-built test fixture computes a candidate's committed supply (§ supply
 * header commitment) from an already-booted {@link ChainEngine}, so the many private
 * {@code nextBlock}/{@code mine}/{@code mineAt}-style helpers scattered across the test suite
 * don't each re-derive the formula — and so a fixture chained onto a genesis this feature made
 * supply-committing (every fresh {@link GenesisBlock#build}) keeps satisfying
 * {@code ChainEngine.addBlock}'s check instead of failing it with {@code INVALID_SUPPLY} for a
 * reason unrelated to what the test actually means to exercise.
 *
 * <p>Mirrors {@code BlockAssembler}'s producer-side stamping exactly: reads only the current tip
 * header's committed supply and adds {@link Issuance#minted}, or stays absent when the tip is
 * absent (FR-004 prefix closure — a legacy all-absent chain fixture needs no change at all).
 *
 * <p>009 T052: a fixture that KNOWS the flow its candidate carries can stamp the exact,
 * burn-aware supply through {@link #next(ChainEngine, long, int, List, long)} — the same
 * {@code ceiling - burned} shape the producer's dry run computes (FR-037). The no-burn forms
 * stamp the ceiling ({@code burned = 0}), which is bound-legal at every height; they exist for
 * coinbase-only fixtures, where nothing burns and no execution is needed.
 */
public final class SupplyStamp {

    private SupplyStamp() {
    }

    /** The supply an honest next block at {@code height}/{@code difficulty} with no uncles would commit. */
    public static long next(ChainEngine engine, long height, int difficulty) {
        return next(engine, height, difficulty, List.of());
    }

    /** As {@link #next(ChainEngine, long, int)}, crediting {@code uncles}' work-scaled issuance too. */
    public static long next(ChainEngine engine, long height, int difficulty, List<UncleRef> uncles) {
        return next(engine, height, difficulty, uncles, 0);
    }

    /**
     * The supply an honest next block commits when its execution burns {@code burned} base units:
     * {@code ceiling - burned}, where {@code ceiling} is the parent supply plus the block's full
     * minted term. {@code burned} is what the fixture's own flows will burn under the rule —
     * usually {@code Burn.burned(params, height, pool, debt)} computed at the call site — and
     * must satisfy {@code 0 <= burned <= debt}, exactly the bound the gates enforce. A fixture
     * that stamps this and then carries a DIFFERENT flow fails the exact identity check, which is
     * the falsifiability the burn tests rely on.
     */
    public static long next(ChainEngine engine, long height, int difficulty, List<UncleRef> uncles,
            long burned) {
        long parentSupply = engine.headerAt(engine.height()).supply();
        if (parentSupply == BlockImpl.SUPPLY_ABSENT) {
            return BlockImpl.SUPPLY_ABSENT;
        }
        long ceiling = Math.addExact(parentSupply,
            Issuance.minted(engine.params(), height, parentSupply, difficulty, uncles));
        return Math.subtractExact(ceiling, burned);
    }
}
