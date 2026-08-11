package rhizome.core.box;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.ledger.PublicAddress;
import rhizome.core.token.DefaultTokenProcessor;
import rhizome.core.token.InMemoryTokenStore;
import rhizome.core.token.TokenPayload;
import rhizome.core.transaction.TransactionKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The box and token processors must retain EXACTLY {@code retainDepth} heights of derived state —
 * the reorg window and not one height more.
 *
 * <p>The contract processor was corrected to the {@code height <= cutoff} boundary (audit F10);
 * these two carried a copy of the same retention code that kept {@code height < cutoff}, one height
 * too many, because the fix was applied to one of the three copies. The direction was harmless, but
 * "three parallel copies, two of them stale" is what makes the next correction dangerous.
 *
 * <p>Pruning is driven by the engine against the APPENDED chain tip
 * ({@link rhizome.core.blockchain.BlockStateProcessor#pruneToChainTip}), never by {@code commit}:
 * a commit can still be reverted (the stampStateRoot dry run, a state-root rejection), and a
 * commit-time prune then deletes the oldest in-window height a max-depth reorg still needs.
 */
class RetentionBoundaryTest {

    private static final NetworkParameters PARAMS = NetworkParameters.testnet().toBuilder()
        .storagePeriodBlocks(10).storageFeeFactor(1).minValuePerByte(1).build();

    private static final int RETAIN = 3;

    @Test
    void theBoxProcessorRetainsExactlyRetainDepthHeights() {
        var processor = new DefaultBoxProcessor(new InMemoryBoxStore(), PARAMS, RETAIN);
        PublicAddress owner = PublicAddress.random();

        long last = 8;
        for (long height = 1; height <= last; height++) {
            createBox(processor, owner, height);
            processor.commit(height);
            processor.pruneToChainTip(height); // the engine prunes post-append, not at commit
        }

        // cutoff = last - retainDepth, and everything AT OR BELOW it is dropped, so the surviving
        // window is (cutoff, last] — exactly retainDepth heights, which is what a reorg of
        // maxReorgDepth needs and no more.
        long cutoff = last - RETAIN;
        assertTrue(processor.changes(cutoff).isEmpty(),
            "height " + cutoff + " is the cutoff itself and must be pruned (audit F10 boundary)");
        assertFalse(processor.changes(cutoff + 1).isEmpty(),
            "height " + (cutoff + 1) + " is the oldest height inside the reorg window");
        assertEquals(RETAIN, countRetainedFrom(processor, last),
            "exactly retainDepth heights survive — the pre-F10 form kept one more");
    }

    @Test
    void theTokenProcessorRetainsExactlyRetainDepthHeights() {
        var processor = new DefaultTokenProcessor(new InMemoryTokenStore(), PARAMS, RETAIN);
        PublicAddress minter = PublicAddress.random();

        long last = 8;
        for (long height = 1; height <= last; height++) {
            processor.begin();
            processor.run(TransactionKind.TOKEN_MINT, minter, minter, height,
                TokenPayload.encodeMint(1_000L, 2, "SYM", "name"), height);
            processor.commit(height);
            processor.pruneToChainTip(height); // the engine prunes post-append, not at commit
        }

        long cutoff = last - RETAIN;
        assertTrue(processor.changes(cutoff).isEmpty(),
            "height " + cutoff + " is the cutoff itself and must be pruned (audit F10 boundary)");
        assertFalse(processor.changes(cutoff + 1).isEmpty(),
            "height " + (cutoff + 1) + " is the oldest height inside the reorg window");
    }

    /**
     * Regression: the stampStateRoot dry run commits a candidate at tip+1 and reverts it moments
     * later, so a commit-time prune keyed on that height deletes exactly the oldest in-window
     * height — and its durable receipts with it. The max-depth reorg that then needs those
     * receipts dies on {@code Executor.rollbackBlock}'s missing-receipts guard, leaving a
     * block-producing node unable to adopt a heavier branch ever again. A reverted commit must
     * shrink the retention window by nothing.
     */
    @Test
    void aRevertedCommitPrunesNothing() {
        var store = new InMemoryBoxStore();
        var processor = new DefaultBoxProcessor(store, PARAMS, RETAIN);
        PublicAddress owner = PublicAddress.random();

        long tip = 8;
        for (long height = 1; height <= tip; height++) {
            createBox(processor, owner, height);
            processor.commit(height);
            processor.pruneToChainTip(height);
        }
        long oldest = tip - RETAIN + 1; // the deepest height a max-depth reorg from tip can pop
        assertFalse(processor.receipts(oldest).isEmpty(), "in-window before the dry run");

        // The dry run: commit the candidate at tip+1 (its state must stage — the rollback walk
        // reads it), then revert it. No pruneToChainTip runs: the candidate never appended.
        createBox(processor, owner, tip + 1);
        processor.commit(tip + 1);
        processor.revertBlock(tip + 1);

        assertFalse(processor.receipts(oldest).isEmpty(),
            "the reverted phantom commit must not prune the oldest in-window receipts");
        assertFalse(processor.changes(oldest).isEmpty(),
            "the reverted phantom commit must not prune the oldest in-window changes");
        assertTrue(processor.receipts(tip + 1).isEmpty(), "the phantom height leaves no receipts");
        assertTrue(processor.changes(tip + 1).isEmpty(), "the phantom height leaves no changes");

        // Once a block at tip+1 really appends, the boundary advances exactly one height.
        createBox(processor, owner, tip + 1);
        processor.commit(tip + 1);
        processor.pruneToChainTip(tip + 1);
        assertTrue(processor.changes(oldest).isEmpty(),
            "an appended tip+1 prunes the height that falls out of the window");
    }

    @Test
    void revertingEveryHeightLeavesNothingRetained() {
        // Exercises the byte-counter bookkeeping from the other side: retain on commit, credit
        // back on revert. A counter that drifted upward here would silently shrink the effective
        // budget until live heights started being evicted.
        var processor = new DefaultBoxProcessor(new InMemoryBoxStore(), PARAMS, RETAIN);
        PublicAddress owner = PublicAddress.random();

        for (long height = 1; height <= 3; height++) {
            createBox(processor, owner, height);
            processor.commit(height);
        }
        for (long height = 3; height >= 1; height--) {
            processor.revertBlock(height);
        }

        for (long height = 1; height <= 3; height++) {
            assertTrue(processor.changes(height).isEmpty(), "height " + height + " was reverted");
            assertTrue(processor.events(height).isEmpty(), "height " + height + " was reverted");
        }
    }

    private static void createBox(DefaultBoxProcessor processor, PublicAddress owner, long height) {
        processor.begin();
        processor.run(TransactionKind.BOX_CREATE, owner, owner, 100_000L, height,
            BoxPayload.encodeCreate(List.of(BoxRegister.i64(height))), height);
    }

    /** Heights in {@code [1, last]} that still have retained changes. */
    private static int countRetainedFrom(DefaultBoxProcessor processor, long last) {
        int retained = 0;
        for (long height = 1; height <= last; height++) {
            if (!processor.changes(height).isEmpty()) {
                retained++;
            }
        }
        return retained;
    }
}
