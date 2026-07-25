package rhizome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.blockchain.ChainEngine;
import rhizome.core.blockchain.InMemoryChainStore;
import rhizome.core.blockchain.Miner;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.InMemoryLedger;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PowCosts;

/**
 * Locks the height-scheduled PoW-cost upgrade path ({@code NetworkParameters#powCostsAt}):
 * blocks below the upgrade height verify under the genesis costs, blocks at or above it
 * under the upgraded costs, and a block mined with the wrong side's costs is rejected.
 * Default parameters (no upgrade scheduled) keep the genesis costs at every height, so the
 * existing chain is unaffected.
 */
class PowUpgradeTest {

    private static final long UPGRADE_HEIGHT = 5;
    private static final PowCosts GENESIS_COSTS = PowCosts.DEFAULT;
    private static final PowCosts UPGRADED_COSTS = new PowCosts(0, 10);

    private NetworkParameters params;
    private AtomicLong clock;
    private ChainEngine engine;
    private PublicAddress miner;

    private static final long START = 1_000_000_000L; // ms

    @BeforeEach
    void setUp() {
        // A PUFFERFISH2 testnet with a scheduled memory-hardness upgrade at height 5
        // (cost_m 8 -> 10), difficulty kept low so mining stays fast in CI.
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.PUFFERFISH2)
            .powUpgradeHeight(UPGRADE_HEIGHT)
            .powCostTAfter(UPGRADED_COSTS.costT())
            .powCostMAfter(UPGRADED_COSTS.costM())
            .build();
        clock = new AtomicLong(START);
        miner = PublicAddress.random();

        LedgerSnapshot snapshot = new LedgerSnapshot("test", 0, params.chainId());
        engine = ChainEngine.init(params, new InMemoryLedger(), new InMemoryChainStore(), snapshot,
            null, clock::get);
    }

    /** Builds the next block on the engine's tip, mined under the given cost parameters. */
    private Block nextBlock(PowCosts costs) {
        long height = engine.height() + 1;
        var b = BlockImpl.builder()
            .id((int) height)
            .timestamp(clock.addAndGet(params.desiredBlockTimeSec() * 1000L))
            .difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));

        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        ((BlockImpl) b).merkleRoot(tree.getRootHash());
        ((BlockImpl) b).nonce(
            Miner.mineNonce(b.hash(), ((BlockImpl) b).difficulty(), params.powAlgorithm(), costs));
        return b;
    }

    /**
     * Builds the next block on the engine's tip, mined under {@code mineCosts}, such that
     * the nonce does NOT also satisfy the PoW under {@code failCosts} (at difficulty 6 a
     * wrong-costs nonce still passes by chance with p=1/64, which would make the
     * rejection assertions below flaky).
     */
    private Block nextBlock(PowCosts mineCosts, PowCosts failCosts) {
        Block b;
        do {
            b = nextBlock(mineCosts);
        } while (b.verifyNonce(params.powAlgorithm(), failCosts));
        return b;
    }

    @Test
    void powCostsAtDefaultsToGenesisCostsForever() {
        NetworkParameters plain = NetworkParameters.testnet();
        assertEquals(PowCosts.DEFAULT, plain.powCostsAt(0));
        assertEquals(PowCosts.DEFAULT, plain.powCostsAt(1));
        assertEquals(PowCosts.DEFAULT, plain.powCostsAt(Long.MAX_VALUE));
    }

    @Test
    void powCostsAtSwitchesAtTheUpgradeHeight() {
        for (long h = 0; h < UPGRADE_HEIGHT; h++) {
            assertEquals(GENESIS_COSTS, params.powCostsAt(h), "height " + h);
        }
        assertEquals(UPGRADED_COSTS, params.powCostsAt(UPGRADE_HEIGHT));
        assertEquals(UPGRADED_COSTS, params.powCostsAt(UPGRADE_HEIGHT + 1));
        assertEquals(UPGRADED_COSTS, params.powCostsAt(Long.MAX_VALUE));
    }

    @Test
    void builderRejectsMisconfiguredUpgrade() {
        // "After" costs without a scheduled upgrade height.
        assertThrows(IllegalArgumentException.class, () -> NetworkParameters.testnet().toBuilder()
            .powCostTAfter(0).powCostMAfter(10).build());
        // An upgrade height without the "after" costs.
        assertThrows(IllegalArgumentException.class, () -> NetworkParameters.testnet().toBuilder()
            .powUpgradeHeight(100).build());
        assertThrows(IllegalArgumentException.class, () -> NetworkParameters.testnet().toBuilder()
            .powUpgradeHeight(100).powCostTAfter(0).build());
        // Out-of-range costs (genesis or upgraded).
        assertThrows(IllegalArgumentException.class, () -> NetworkParameters.testnet().toBuilder()
            .powCostM(0).build());
        assertThrows(IllegalArgumentException.class, () -> NetworkParameters.testnet().toBuilder()
            .powUpgradeHeight(100).powCostTAfter(0).powCostMAfter(PowCosts.MAX_COST_M + 1).build());
    }

    @Test
    void blocksVerifyUnderTheCostsOfTheirHeight() {
        // Before the boundary: blocks mined under the genesis costs pass.
        while (engine.height() + 1 < UPGRADE_HEIGHT) {
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nextBlock(GENESIS_COSTS)));
        }
        assertEquals(UPGRADE_HEIGHT - 1, engine.height());

        // At the boundary: a block mined under the UPGRADED costs passes.
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nextBlock(UPGRADED_COSTS)));
        assertEquals(UPGRADE_HEIGHT, engine.height());

        // After the boundary: the upgraded costs keep passing.
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nextBlock(UPGRADED_COSTS)));
    }

    @Test
    void blockAfterBoundaryMinedWithOldCostsIsRejected() {
        while (engine.height() + 1 < UPGRADE_HEIGHT) {
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(nextBlock(GENESIS_COSTS)));
        }

        // Same block template, but mined under the GENESIS costs: its nonce satisfies the
        // old PoW and not the new one, so every node must reject it at the boundary.
        Block stale = nextBlock(GENESIS_COSTS, UPGRADED_COSTS);
        assertEquals(ExecutionStatus.INVALID_NONCE, engine.addBlock(stale));
        assertEquals(UPGRADE_HEIGHT - 1, engine.height());
    }

    @Test
    void blockBeforeBoundaryMinedWithNewCostsIsRejected() {
        // Symmetric guard: the upgraded costs are not valid early either.
        Block early = nextBlock(UPGRADED_COSTS, GENESIS_COSTS);
        assertEquals(ExecutionStatus.INVALID_NONCE, engine.addBlock(early));
    }
}
