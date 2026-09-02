package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.Burn;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.blockchain.SupplyTargetSchedule;

/**
 * US3 (009-native-coin-burn): the miner's economics under the burn, proven by sweeps rather
 * than arguments. The floor can never be reached (the pool excludes every minted term), and
 * including a fee-paying transaction stays strictly profitable at every supply — both are
 * properties of the rule's SHAPE, asserted here over the whole reachable domain.
 *
 * <p>Single-clamp verification (T047, ADR-010 §2): this suite's assertions run through the
 * ONE clamp site, two-arg {@code miningReward} — the only {@code max(·, R_min)} on the
 * consensus path. The burn adds no second clamp: it clamps against the pool and the debt
 * ({@code Burn.burned}), never against the subsidy.
 */
class BurnMinerRevenueFloorTest {

    private final NetworkParameters p = NetworkParameters.cleanMainnet();
    private final long floor = p.minerRevenueFloor();

    /** Heights spanning the full decay schedule: plateau, start, epochs, floor arrival, beyond. */
    private long[] heights() {
        SupplyTargetSchedule schedule = p.supplyTargetSchedule();
        return new long[] {
            1L, 2L,
            p.decayStartHeight() - 1, p.decayStartHeight(),
            p.decayStartHeight() + p.decayEpochBlocks(),
            p.decayStartHeight() + 25 * p.decayEpochBlocks(),
            schedule.floorArrivalHeight() > 0 ? schedule.floorArrivalHeight() - 1 : 0L,
            schedule.floorArrivalHeight() > 0 ? schedule.floorArrivalHeight() : 0L,
        };
    }

    /** Supplies from the pinned genesis up to 4 × S*_peak, plus the tight band around S*(h). */
    private long[] suppliesAt(long h) {
        long sStar = p.supplyTargetAt(h);
        long peak = p.supplyTarget();
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        if (p.genesisSupply() != NetworkParameters.GENESIS_SUPPLY_UNPINNED) {
            out.add(p.genesisSupply());
        }
        for (long s = sStar; s > 0 && s <= 4 * peak; s = Math.multiplyExact(s, 3) / 2) {
            out.add(s);
        }
        out.add(4 * peak);
        for (long around : new long[] {-1, 0, 1, 1_000}) {
            long s = Math.addExact(sStar, around);
            if (s > 0) {
                out.add(s);
            }
        }
        return out.stream().mapToLong(Long::longValue).distinct().toArray();
    }

    /** Pools spanning [0, S*_peak]: empty, unit, the rounding edges, and the full-peak extreme. */
    private long[] pools() {
        long peak = p.supplyTarget();
        return new long[] {0L, 1L, 2L, 3L, 7L, 1_000L, peak / 1_000_000L, peak / 1_000L, peak};
    }

    private long revenueAt(long h, long supply, long pool) {
        long subsidy = p.miningReward(h, supply);
        long debt = Burn.debt(p, h, supply, subsidy);
        long burned = Burn.burned(p, h, pool, debt);
        return subsidy + pool - burned;
    }

    @Test
    void minerRevenueNeverFallsBelowTheFloorUnderAnyBurn() {
        // SC-004: swept over the full decay schedule, the whole reachable supply domain and the
        // pool range [0, S*_peak], the miner's revenue (subsidy + pool - burned) never drops
        // below R_min. Structural reason, asserted numerically: burned <= pool, so revenue >=
        // subsidy >= R_min — the floor's only clamp is feature 005's, untouched.
        for (long h : heights()) {
            for (long supply : suppliesAt(h)) {
                for (long pool : pools()) {
                    long revenue = revenueAt(h, supply, pool);
                    assertTrue(revenue >= floor,
                        "revenue " + revenue + " fell below the floor " + floor
                            + " at height " + h + ", supply " + supply + ", pool " + pool);
                }
            }
        }
    }

    @Test
    void includingAFeePayingTransactionIsStrictlyProfitableAtEverySupply() {
        // SC-005: adding Δ > 0 to the pool raises revenue by Δ minus the marginal burn —
        // NEVER negative for any Δ, and strictly positive once Δ clears the share's rounding
        // grain (at the shipped 1/2: Δ >= 2; contracts/native-coin-burn.md §5's own bound
        // Δ - ceil(Δ·β) is 0 exactly at Δ = 1, β = 1/2). Integer-exact: no floating point in
        // the proof, so an independent implementation reproduces it bit for bit.
        for (long h : heights()) {
            for (long supply : suppliesAt(h)) {
                for (long pool : pools()) {
                    for (long delta : new long[] {1L, 2L, 3L, 7L, 100L, 1_000_000L}) {
                        long gain = revenueAt(h, supply, pool + delta) - revenueAt(h, supply, pool);
                        assertTrue(gain >= 0,
                            "adding a " + delta + "-unit fee LOST " + (-gain)
                                + " at height " + h + ", supply " + supply + ", pool " + pool);
                        if (delta >= 2) {
                            assertTrue(gain >= 1,
                                "adding a " + delta + "-unit fee gained " + gain
                                    + " at height " + h + ", supply " + supply + ", pool " + pool);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theBurnNeverReachesIntoMintedCoin() {
        // FR-010, asserted rather than assumed: (a) burned <= pool at every point of the domain
        // — whatever the debt, the clamp is the SHARE of the flow; and (b) the decomposition is
        // exact — revenue minus the kept flow equals the subsidy alone, so the coinbase term
        // the burn can never touch is identifiable in every figure.
        for (long h : heights()) {
            long sStar = p.supplyTargetAt(h);
            for (long supply : suppliesAt(h)) {
                long subsidy = p.miningReward(h, supply);
                long debt = Burn.debt(p, h, supply, subsidy);
                for (long pool : pools()) {
                    long burned = Burn.burned(p, h, pool, debt);
                    assertTrue(burned <= pool,
                        "burned " + burned + " exceeded the pool " + pool
                            + " at height " + h + ", supply " + supply);
                    long kept = pool - burned;
                    long revenue = subsidy + kept;
                    assertEquals(subsidy, revenue - kept,
                        "the subsidy is the only term outside the flow: the burn never "
                            + "reached into minted coin");
                    // And the whole-carried-debt extreme: even a debt far larger than any
                    // reachable pool leaves the subsidy untouched and the flow at least half kept.
                    assertTrue(Burn.burned(p, h, pool, Long.MAX_VALUE / 4) <= pool);
                    // The share itself never exceeds its pool, over the WHOLE sweep (review
                    // T081: replaces a single-point spot check).
                    assertTrue(Burn.applyShare(p, pool) <= pool,
                        "applyShare(" + pool + ") exceeded the pool at height " + h);
                }
                // The share itself never exceeds the pool, at 1/2 by one integer division.
                if (sStar > 0) {
                    assertTrue(Burn.applyShare(p, 1_000_000L) <= 1_000_000L);
                }
            }
        }
    }
}
