package rhizome.core.blockchain;

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

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The engine's incremental median-time-past ring must agree with {@link Retarget}, the scan the
 * header validator uses.
 *
 * <p>This divergence is deliberate: re-reading the window from the store on every added block was
 * measurable, so the engine maintains the same median over a primitive ring (audit P6). But a ring
 * and a scan are two implementations of one consensus rule, and a node whose ring drifts accepts
 * timestamps the rest of the network rejects. {@code MedianTimePastRingTest} already pins the ring
 * against a hand-written ground truth; this pins it against the shared function the other side of
 * the protocol actually runs, which is the comparison that can regress when {@code Retarget}
 * changes.
 */
class RetargetMirrorTest {

    private NetworkParameters params;
    private ChainEngine engine;
    private AtomicLong clock;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3)
            .medianTimeWindow(5).minBlockTimeSec(0).maxFutureBlockTimeSec(1_000_000).build();
        clock = new AtomicLong(1_000_000L);
        miner = PublicAddress.random();
        engine = ChainEngine.boot(
                params,
                TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, params.chainId()))
            .clock(clock::get)
            .build();
    }

    private void mine(long jitter) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000 + jitter)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b));
    }

    private void assertRingMatchesScan(String when) {
        assertEquals(Retarget.medianTimePast(params, engine::headerAt, engine.height()),
            engine.medianTimePastForTest(),
            "the engine ring and the header validator's scan must agree " + when);
    }

    @Test
    void theRingAgreesWithTheHeaderValidatorScanAcrossAddsAndPops() {
        assertRingMatchesScan("at genesis");

        // Jittered, strictly increasing timestamps: a median only differs from a mean when the
        // window is not monotonic in arrival order.
        long[] jitter = {700, -400, 900, -200, 500, 100, -300, 800, 0, 600, -100, 300};
        for (long j : jitter) {
            mine(j);
            assertRingMatchesScan("after adding height " + engine.height());
        }

        // Pop back below the window size, which exercises the ring's front re-entry.
        for (int i = 0; i < 8; i++) {
            ChainEngineTestAccess.popBlock(engine);
            assertRingMatchesScan("after popping to height " + engine.height());
        }

        // And re-extend: the ring must rebuild forward exactly as the scan reads it.
        for (int i = 0; i < 7; i++) {
            mine(200L * i - 400);
            assertRingMatchesScan("after re-extending to height " + engine.height());
        }
    }
}
