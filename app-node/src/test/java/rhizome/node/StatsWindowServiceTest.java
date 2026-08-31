package rhizome.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;

/**
 * The {@code GET /stats} window cache (extracted from NodeService): the aggregate is recomputed
 * only when the tip height moves, and — since the emission fragment landed — it carries the
 * tip's own committed supply alongside the parent supply it already held. The window loop that
 * already visits the tip captures it, so a stationary poll costs no extra consensus lock and the
 * cache stays one entry keyed by tip height.
 */
class StatsWindowServiceTest {

    private static BlockImpl block(long id, long supply) {
        var b = (BlockImpl) BlockImpl.builder()
            .id((int) id)
            .timestamp(1_000L + id)
            .difficulty(1)
            .lastBlockHash(rhizome.crypto.SHA256Hash.empty())
            .supply(supply)
            .build();
        b.addTransaction(rhizome.core.transaction.Transaction.of(
            rhizome.core.ledger.PublicAddress.random(),
            new rhizome.core.transaction.TransactionAmount(
                rhizome.core.blockchain.NetworkParameters.testnet().miningReward(id))));
        return b;
    }

    @Test
    void theWindowCarriesTheTipSupplyWhenTheTipCommitsOne() {
        List<BlockImpl> chain = new ArrayList<>();
        chain.add(block(1, 0L));
        chain.add(block(2, 50L));
        chain.add(block(3, 110L));
        var service = new StatsWindowService(() -> 3, i -> chain.get((int) i - 1));

        var w = service.statsWindow(32);
        assertEquals(3, w.height());
        assertEquals(50L, w.parentSupply());
        assertEquals(110L, w.tipSupply());
    }

    @Test
    void theWindowCarriesTheAbsentSentinelWhenTheTipCommitsNoSupply() {
        // A legacy all-absent chain: absence must pass through as SUPPLY_ABSENT, never 0.
        List<BlockImpl> chain = new ArrayList<>();
        chain.add(block(1, BlockImpl.SUPPLY_ABSENT));
        chain.add(block(2, BlockImpl.SUPPLY_ABSENT));
        var service = new StatsWindowService(() -> 2, i -> chain.get((int) i - 1));

        var w = service.statsWindow(32);
        assertEquals(2, w.height());
        assertEquals(BlockImpl.SUPPLY_ABSENT, w.parentSupply());
        assertEquals(BlockImpl.SUPPLY_ABSENT, w.tipSupply());
    }

    @Test
    void aWindowNarrowerThanTwoBlocksStillCarriesBothSupplies() {
        // window=1 covers only the tip; the parent supply is fetched directly rather than
        // assumed covered, and the tip supply still comes from the visited tip block.
        List<BlockImpl> chain = new ArrayList<>();
        chain.add(block(1, 0L));
        chain.add(block(2, 50L));
        chain.add(block(3, 110L));
        var service = new StatsWindowService(() -> 3, i -> chain.get((int) i - 1));

        var w = service.statsWindow(1);
        assertEquals(3, w.windowStart());
        assertEquals(50L, w.parentSupply());
        assertEquals(110L, w.tipSupply());
    }

    @Test
    void aStationaryPollReusesTheCachedWindowWithoutRevisitingBlocks() {
        List<BlockImpl> chain = new ArrayList<>();
        chain.add(block(1, 0L));
        chain.add(block(2, 50L));
        int[] visits = {0};
        var service = new StatsWindowService(() -> 2, i -> {
            visits[0]++;
            return chain.get((int) i - 1);
        });

        service.statsWindow(32);
        int afterFirst = visits[0];
        var again = service.statsWindow(32);
        assertEquals(afterFirst, visits[0], "a same-tip poll must not re-decode the window");
        assertEquals(50L, again.tipSupply(), "the cached entry still carries the tip supply");
    }
}
