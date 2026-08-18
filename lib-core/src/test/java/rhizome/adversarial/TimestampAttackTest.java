package rhizome.adversarial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rhizome.core.blockchain.DifficultyAdjustment;
import rhizome.core.blockchain.NetworkParameters;
import rhizome.core.mempool.ExecutionStatus;

/**
 * Attacks on the chain's notion of time, which is really an attack on difficulty (TIME and POW
 * families — see docs/adversarial/spec.md).
 *
 * <p>Block timestamps are miner-supplied and unverifiable, so every proof-of-work chain has to
 * decide how much they are allowed to move difficulty. Pandanite bounded "future" by a
 * Sybil-manipulable network median and took its median-time-past over ten blocks, and miners used
 * both to push timestamps forward and reject honest blocks (WHITEPAPER §4.7, upstream #19/#22).
 *
 * <p>Rhizome bounds the future by the <em>local</em> clock and the retarget step by whole bits. The
 * scenarios below attack the two directions a liar can push: forward (pre-mining a branch into the
 * future) and backward-compressed (making a window look instantaneous to drive difficulty up and
 * price honest miners out).
 */
class TimestampAttackTest {

    /**
     * TIME-01 — pre-mining is bounded by the future window, and the bound is a block count. The
     * whitepaper justifies mainnet's 15 s bound as "≈3 blocks at 5 s"; this pins that arithmetic
     * rather than leaving it as prose.
     */
    @Test
    void anAttackerCanPreStampOnlyAsManyBlocksAsTheFutureWindowHoldsBlocks() {
        int futureWindowSec = 15;
        int blockTimeSec = 5;
        AdversarialChain chain = AdversarialChain.on(NetworkParameters.testnet().toBuilder()
            .maxFutureBlockTimeSec(futureWindowSec)
            .desiredBlockTimeSec(blockTimeSec)
            .build()).build();

        long wallClock = chain.now();
        // The attacker mines ahead; the victim's clock does not move. Each forge advances the
        // fixture clock by one interval, so it is reset before every submission.
        for (int block = 1; block <= futureWindowSec / blockTimeSec; block++) {
            var forge = chain.forge();
            chain.setClock(wallClock);
            long stamp = wallClock + (long) block * blockTimeSec * 1000L;
            assertEquals(ExecutionStatus.SUCCESS, chain.engine().addBlock(forge.timestamp(stamp).seal()),
                "block " + block + " is still inside the future window");
        }

        var beyond = chain.forge();
        chain.setClock(wallClock);
        long tooFar = wallClock + (futureWindowSec + 1) * 1000L;
        assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_IN_FUTURE,
            chain.engine().addBlock(beyond.timestamp(tooFar).seal()),
            "one second past the window the pre-mined branch stops, whatever work it carries");
        assertEquals(1 + futureWindowSec / blockTimeSec, chain.height());
    }

    /**
     * POW-05 — the compression campaign. An attacker stamps every block at the earliest instant
     * the rules allow, across several retarget windows, to make the chain believe it is running far
     * too fast and drive difficulty up (pricing out competitors, then dropping it again).
     *
     * <p>Two bounds must hold for the whole campaign, not just for one retarget: no window may move
     * difficulty by more than {@link DifficultyAdjustment#MAX_STEP_BITS} bits, and the value must
     * stay inside the network's own bounds. Together they cap what a sustained liar can do to
     * difficulty at a known, small rate.
     */
    @Test
    void aSustainedMinimalTimestampCampaignMovesDifficultyOnlyAtTheBoundedRate() {
        NetworkParameters params = NetworkParameters.testnet().toBuilder()
            .difficultyLookback(4)
            .genesisDifficulty(6)
            .minDifficulty(6)
            .maxDifficulty(10)
            .build();
        AdversarialChain chain = AdversarialChain.on(params).build();

        List<Integer> difficulties = new ArrayList<>();
        difficulties.add(chain.engine().difficulty());
        for (int i = 0; i < 3 * params.difficultyLookback(); i++) {
            var forge = chain.forge();
            // The earliest legal instant: median-time-past + 1 ms. The window then measures as
            // close to zero as consensus permits — the strongest form of this lie.
            assertEquals(ExecutionStatus.SUCCESS,
                chain.engine().addBlock(forge.timestamp(chain.minimalTimestamp()).seal()));
            difficulties.add(chain.engine().difficulty());
        }

        for (int i = 1; i < difficulties.size(); i++) {
            int step = Math.abs(difficulties.get(i) - difficulties.get(i - 1));
            assertTrue(step <= DifficultyAdjustment.MAX_STEP_BITS,
                "retarget at height " + (i + 1) + " moved difficulty by " + step
                    + " bits, above the bounded step of " + DifficultyAdjustment.MAX_STEP_BITS);
            assertTrue(difficulties.get(i) >= params.minDifficulty()
                    && difficulties.get(i) <= params.maxDifficulty(),
                "difficulty left its network bounds: " + difficulties.get(i));
        }
        assertTrue(difficulties.get(difficulties.size() - 1) > params.genesisDifficulty(),
            "the campaign is self-defeating: compressing time raises the attacker's own cost");
    }

    /**
     * TIME-02 — backdating. A block is floored twice, and both floors are asserted here because
     * they catch different antedating attacks: median-time-past stops a miner passing off a stale
     * branch as contemporaneous, and the parent's own timestamp (plus {@code minBlockTime}) stops a
     * miner mining a block "before" the one it builds on. On this profile the parent floor is the
     * later of the two, which is why the accepted case below is the engine's own computed minimum
     * rather than one millisecond above the median.
     */
    @Test
    void aBlockBelowEitherTimestampFloorIsRefused() {
        AdversarialChain chain = AdversarialChain.testnet().build();
        chain.extendBy(8);

        long medianTimePast = chain.engine().medianTimePastForTest();
        long parentTimestamp = chain.engine().headerAt(chain.height()).timestamp();

        var atMedian = chain.forge();
        assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_TOO_OLD,
            chain.engine().addBlock(atMedian.timestamp(medianTimePast).seal()),
            "the median-time-past floor is exclusive");

        var belowMedian = chain.forge();
        assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_TOO_OLD,
            chain.engine().addBlock(belowMedian.timestamp(medianTimePast - 60_000L).seal()));

        var beforeParent = chain.forge();
        assertEquals(ExecutionStatus.BLOCK_TIMESTAMP_TOO_CLOSE,
            chain.engine().addBlock(beforeParent.timestamp(parentTimestamp - 1).seal()),
            "and a block cannot predate the block it extends");

        var earliestLegal = chain.forge();
        assertEquals(ExecutionStatus.SUCCESS,
            chain.engine().addBlock(earliestLegal.timestamp(chain.minimalTimestamp()).seal()),
            "at the earliest instant both floors allow, the same block is valid");
    }
}
