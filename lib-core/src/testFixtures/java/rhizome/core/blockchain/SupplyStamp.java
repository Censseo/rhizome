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
        long parentSupply = engine.headerAt(engine.height()).supply();
        if (parentSupply == BlockImpl.SUPPLY_ABSENT) {
            return BlockImpl.SUPPLY_ABSENT;
        }
        return Math.addExact(parentSupply, Issuance.minted(engine.params(), height, parentSupply, difficulty, uncles));
    }
}
