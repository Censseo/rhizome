package rhizome.core.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rhizome.core.block.BlockImpl;
import rhizome.core.ledger.LedgerSnapshot;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.mempool.ExecutionStatus;
import rhizome.core.mempool.MemPool;
import rhizome.core.merkletree.MerkleTree;
import rhizome.core.transaction.Transaction;
import rhizome.core.transaction.TransactionAmount;
import rhizome.core.transaction.TransactionKind;
import rhizome.crypto.PowAlgorithm;

/**
 * The degraded-state hard barrier (audit 17th pass, blocking finding 1). A peripheral revert
 * failing AFTER {@code store.pop()} committed must leave the store AND the in-memory view
 * agreeing at the lower height — the pop's bookkeeping runs before the revert phase — and the
 * degraded engine must refuse every new-tip write. Two marks, two healing paths (counter-review):
 * a restore failure is cleared by a later successful restore; a torn pop is restart-required —
 * the restore re-applies the suffix but never rewinds the torn peripheral, so clearDegraded
 * must refuse and only a restart into boot recovery lifts the barrier. Previously the exception
 * skipped the bookkeeping (totalWork, the MTP window and currentDifficulty described a chain
 * that no longer existed) and the degraded flag was purely observational: nothing gated
 * addBlock or production.
 */
class DegradedBarrierTest {

    /**
     * A ContractProcessor whose revertBlock throws ONCE (an injected peripheral failure), and
     * only once armed — the engine's boot reconciliation sweeps revertBlock over the whole
     * reorg window, so the saboteur must stay inert until the test has a block to pop.
     */
    private static final class FailingOnceContractProcessor implements ContractProcessor {
        private boolean armed;

        void arm() {
            armed = true;
        }

        @Override public void begin() { }

        @Override public ContractResult run(PublicAddress from, TransactionKind kind, PublicAddress to,
                                            byte[] data, long value, long gasLimit, long nonce) {
            throw new UnsupportedOperationException("no contract transactions in this test");
        }

        @Override public void commit(long blockHeight) { }

        @Override public void discard() { }

        @Override public void revertBlock(long blockHeight) {
            if (armed) {
                armed = false;
                throw new IllegalStateException("injected peripheral revert failure");
            }
        }

        @Override public List<ContractReceipt> receipts(long blockHeight) {
            return List.of();
        }
    }

    private NetworkParameters params;
    private AtomicLong clock;
    private PublicAddress miner;

    @BeforeEach
    void setUp() {
        params = NetworkParameters.testnet().toBuilder()
            .powAlgorithm(PowAlgorithm.SHA256).genesisDifficulty(3).minDifficulty(3).build();
        clock = new AtomicLong(1_000_000L);
        miner = PublicAddress.random();
    }

    /** A chain with no contract domain at all — the control for the saboteur below. */
    private ChainEngine newEngine() {
        return newBoot().build();
    }

    private ChainEngine newEngine(ContractProcessor contracts) {
        return newBoot().contracts(contracts).build();
    }

    private ChainEngine.Boot newBoot() {
        return ChainEngine.boot(params, TestNodeStores.inMemory(),
                new LedgerSnapshot("t", 0, params.chainId()))
            .clock(clock::get);
    }

    /** A mined next block on the current tip (coinbase only). */
    private BlockImpl mineNext(ChainEngine engine) {
        long height = engine.height() + 1;
        var b = (BlockImpl) BlockImpl.builder().id((int) height)
            .timestamp(clock.addAndGet(1000L)).difficulty(engine.difficulty())
            .lastBlockHash(engine.tipHash())
            .supply(SupplyStamp.next(engine, height, engine.difficulty())).build();
        b.addTransaction(Transaction.of(miner, new TransactionAmount(params.miningReward(height))));
        var tree = new MerkleTree();
        tree.setItems(b.transactions());
        b.merkleRoot(tree.getRootHash());
        b.nonce(Miner.mineNonce(b.hash(), b.difficulty(), params.powAlgorithm()));
        return b;
    }

    @Test
    void failedPeripheralRevertLeavesStoreAndMemoryConsistentWithACleanPop() {
        var saboteur = new FailingOnceContractProcessor();
        ChainEngine torn = newEngine(saboteur);
        ChainEngine control = newEngine();
        // Both engines share the genesis, so one block built on the common tip fits both.
        BlockImpl b2 = mineNext(control);
        assertEquals(ExecutionStatus.SUCCESS, torn.addBlock(b2));
        assertEquals(ExecutionStatus.SUCCESS, control.addBlock(b2));

        saboteur.arm();
        control.popBlock(); // the clean pop: where the torn one must land, state-wise
        assertThrows(IllegalStateException.class, torn::popBlock);

        // The pop's in-memory bookkeeping ran BEFORE the failing revert: every consensus input
        // matches the cleanly-popped control — nothing describes the phantom block any more.
        assertEquals(control.height(), torn.height());
        assertEquals(control.tipHash(), torn.tipHash());
        assertEquals(control.totalWork(), torn.totalWork());
        assertEquals(control.baseWork(), torn.baseWork());
        assertEquals(control.difficulty(), torn.difficulty());
        assertEquals(control.medianTimePastForTest(), torn.medianTimePastForTest());
        assertTrue(torn.isDegraded());
        assertFalse(control.isDegraded());
    }

    @Test
    void degradedBarsEveryNewTipWriteAndATornPopIsNotRestoreClearable() {
        var saboteur = new FailingOnceContractProcessor();
        ChainEngine engine = newEngine(saboteur);
        BlockImpl b2 = mineNext(engine);
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(b2));
        saboteur.arm();
        assertThrows(IllegalStateException.class, engine::popBlock);
        assertTrue(engine.isDegraded());

        // Every new-tip path is refused without any validation work, and the chain is untouched:
        // gossip//submit (addBlock), peer-branch adoption (addValidatedBody) and the producer.
        assertEquals(ExecutionStatus.NODE_DEGRADED, engine.addBlock(mineNext(engine)));
        assertEquals(ExecutionStatus.NODE_DEGRADED, engine.addValidatedBody(mineNext(engine)));
        assertEquals(1, engine.height());
        var mempool = new MemPool(params, new SignatureVerifier(), engine, 100);
        assertTrue(new BlockProducer(engine, mempool, miner, clock::get).produce().isEmpty());
        assertEquals(1, engine.height());

        // The trusted restore BYPASSES the barrier — a synchronizer or boot-time rewind must
        // never wedge — but the torn pop marked the engine restart-required: the restore
        // re-applied the suffix without rewinding the torn peripheral, so clearing is refused
        // and the barrier stays up until the operator restarts into boot recovery.
        assertEquals(ExecutionStatus.SUCCESS, engine.restoreBlock(b2));
        assertEquals(2, engine.height());
        engine.clearDegraded();
        assertTrue(engine.isDegraded());
        assertEquals(ExecutionStatus.NODE_DEGRADED, engine.addBlock(mineNext(engine)));
    }

    @Test
    void aRestoreFailureMarkIsClearableOnceARestoreSucceeds() {
        ChainEngine engine = newEngine();
        // The synchronizer's mark: the local branch is truncated, and a full restore that
        // re-applies the whole range via trusted restoreBlock genuinely heals it.
        engine.markDegraded("failed to restore local branch after a rejected reorg", false);
        assertTrue(engine.isDegraded());
        assertEquals(ExecutionStatus.NODE_DEGRADED, engine.addBlock(mineNext(engine)));

        engine.clearDegraded(); // invoked by ChainSynchronizer.restore after a full success
        assertFalse(engine.isDegraded());
        assertEquals(ExecutionStatus.SUCCESS, engine.addBlock(mineNext(engine)));
        assertEquals(2, engine.height());
    }
}
