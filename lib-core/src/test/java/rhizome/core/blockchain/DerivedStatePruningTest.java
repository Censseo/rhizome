package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.crypto.PowAlgorithm;

/**
 * Bounded pruning of the per-boundary derived-state memos (audit: unbounded growth —
 * {@code voteParamsByBoundary} and {@code difficultyByBoundary} each grew one entry per
 * voting/retarget boundary for the life of the process). Pruning must keep the maps small
 * WITHOUT breaking pop correctness: popping a boundary still drops its tally and falls back to
 * the previous boundary's values.
 */
class DerivedStatePruningTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        // Tiny windows so a handful of blocks crosses several boundaries. 90-second spacing
        // matches the target block time, so the difficulty never retargets (genesis == min).
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3)
            .difficultyLookback(4).votingEpochLength(4).maxReorgDepth(2).build();
        clock = new AtomicLong(1_000_000L);
        engine = ChainEngine.boot(
                params,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, params.chainId()))
            .clock(clock::get)
            .build();
    }

    /** A mined next block voting +1 on the storage-fee-factor parameter. */
    private BlockImpl mineNext(int vote) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(90_000L)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
        b.addTransaction(Transaction.of(PublicAddress.random(),
            new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.vote(vote);
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    /**
     * Expected memo sizes after pruning at {@code tip}, derived from the network parameters
     * (mirrors {@code ChainEngine.pruneDerivedStateCaches}): every boundary in the reorg window
     * ({@code b > cutoff}) plus the single floor entry at/below the cutoff when one exists —
     * the sealed resume point. Kept param-driven so the test stays exact when
     * difficultyLookback/votingEpochLength/maxReorgDepth change.
     */
    private int[] expectedCacheSizes(long tip) {
        long mrd = params.maxReorgDepth();
        return new int[] {
            expectedMemoSize(tip, params.difficultyLookback(), tip - mrd),
            expectedMemoSize(tip, params.votingEpochLength(), tip - mrd - params.votingEpochLength())
        };
    }

    private static int expectedMemoSize(long tip, long spacing, long cutoff) {
        int inWindow = 0;
        boolean floorExists = false;
        for (long b = spacing; b <= (tip / spacing) * spacing; b += spacing) {
            if (b > cutoff) {
                inWindow++;
            } else {
                floorExists = true;
            }
        }
        return inWindow + (floorExists ? 1 : 0);
    }

    @Test
    void boundaryMemosStayBounded() {
        for (int i = 0; i < 12; i++) {
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(0)));
        }
        assertEquals(13, engine.height()); // 12 blocks on the genesis tip
        assertTrue(engine.height() > 2 * params.difficultyLookback(),
            "setup sanity: several retarget/voting boundaries were crossed");
        int[] sizes = engine.derivedCacheSizesForTest();
        int[] expected = expectedCacheSizes(engine.height());
        assertEquals(expected[0], sizes[0],
            "difficulty memo must hold only the window + resume point");
        assertEquals(expected[1], sizes[1],
            "vote memo must keep only pop-reachable boundaries + base");
        // The memo stays bounded as the chain advances further — the same param-derived
        // expectation must hold at the new tip (and remain far below the unpruned count).
        for (int i = 0; i < 6; i++) {
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(0)));
        }
        sizes = engine.derivedCacheSizesForTest();
        expected = expectedCacheSizes(engine.height());
        assertEquals(expected[0], sizes[0]);
        assertEquals(expected[1], sizes[1]);
        long crossed = engine.height() / params.difficultyLookback();
        assertTrue(sizes[0] < crossed, "bounded: fewer difficulty entries than crossed boundaries");
    }

    @Test
    void poppingABoundaryStillRestoresThePreviousTally() {
        long defaultSff = params.storageFeeFactor();
        long defaultMvb = params.minValuePerByte();
        // Two voting epochs of unanimous +1 votes (heights 2..8): two tally boundaries (4, 8),
        // two steps up.
        for (int i = 0; i < 7; i++) {
            assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(1)));
        }
        assertEquals(8, engine.height());
        assertArrayEquals(new long[] {defaultSff + 2, defaultMvb}, engine.voteableParams(),
            "each epoch's tally moves the parameter one bounded step");

        // Pop the second boundary: its tally must drop and the FIRST boundary's values come back —
        // the fallback entry must have survived the pruning.
        engine.popBlock();
        assertArrayEquals(new long[] {defaultSff + 1, defaultMvb}, engine.voteableParams());

        // Re-apply a fresh block 8 voting +1: the tally re-runs and steps from the restored base.
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(1)));
        assertArrayEquals(new long[] {defaultSff + 2, defaultMvb}, engine.voteableParams());
    }
}
