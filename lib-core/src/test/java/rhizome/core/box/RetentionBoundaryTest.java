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
            processor.begin();
            processor.run(TransactionKind.BOX_CREATE, owner, owner, 100_000L, height,
                BoxPayload.encodeCreate(List.of(BoxRegister.i64(height))), height);
            processor.commit(height);
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
        }

        long cutoff = last - RETAIN;
        assertTrue(processor.changes(cutoff).isEmpty(),
            "height " + cutoff + " is the cutoff itself and must be pruned (audit F10 boundary)");
        assertFalse(processor.changes(cutoff + 1).isEmpty(),
            "height " + (cutoff + 1) + " is the oldest height inside the reorg window");
    }

    @Test
    void revertingEveryHeightLeavesNothingRetained() {
        // Exercises the byte-counter bookkeeping from the other side: retain on commit, credit
        // back on revert. A counter that drifted upward here would silently shrink the effective
        // budget until live heights started being evicted.
        var processor = new DefaultBoxProcessor(new InMemoryBoxStore(), PARAMS, RETAIN);
        PublicAddress owner = PublicAddress.random();

        for (long height = 1; height <= 3; height++) {
            processor.begin();
            processor.run(TransactionKind.BOX_CREATE, owner, owner, 100_000L, height,
                BoxPayload.encodeCreate(List.of(BoxRegister.i64(height))), height);
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
