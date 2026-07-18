package rhizome.core.blockchain;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import rhizome.core.common.PowAlgorithm;

/**
 * Consensus configuration for a Rhizome chain.
 *
 * <p>Rhizome starts a <em>clean</em> chain (fresh genesis, corrected rules) whose
 * genesis ledger is seeded from a snapshot of the existing Pandanite chain. The
 * parameters here deliberately fix the design flaws found in the Pandanite C++
 * implementation:
 * <ul>
 *   <li><b>chainId</b> — a network identifier that belongs in the signed
 *       transaction preimage, so signatures cannot be replayed across networks
 *       (Pandanite had none).</li>
 *   <li><b>proof-of-work</b> — {@link PowAlgorithm#PUFFERFISH2} from genesis; no
 *       mid-chain algorithm switch to reason about.</li>
 *   <li><b>mining reward</b> — computed with integer-only arithmetic
 *       ({@link #miningReward(long)}), never floating point, so independent
 *       implementations cannot disagree and fork the chain.</li>
 * </ul>
 *
 * <p>Difficulty is expressed as a number of required leading zero bits.
 */
@Getter
@Builder(toBuilder = true)
@Accessors(fluent = true)
public final class NetworkParameters {

    /** Distinguishes this network; part of the signed transaction preimage. */
    private final int chainId;

    private final String networkName;

    private final PowAlgorithm powAlgorithm;

    // --- Genesis block header ---
    private final long genesisTimestamp;
    private final int genesisDifficulty;

    // --- Difficulty retargeting ---
    private final int desiredBlockTimeSec;
    private final int difficultyLookback;
    private final int minDifficulty;
    private final int maxDifficulty;

    // --- Block validity bounds ---
    /** Max accepted drift of a block timestamp past the local clock (seconds). */
    private final int maxFutureBlockTimeSec;
    /**
     * Consensus-enforced minimum time between a block and its parent (seconds).
     * Every node rejects a block whose timestamp is closer than this to its
     * parent's, so it caps sustained block production at {@code 1 / minBlockTimeSec}
     * per second for everyone — a majority miner included — independently of any
     * local producer pacing (which is only politeness). 0 disables the floor.
     * See WHITEPAPER.md §3.4.
     */
    private final int minBlockTimeSec;
    /** Window (in blocks) for the median-time-past lower bound. */
    private final int medianTimeWindow;
    private final int maxTransactionsPerBlock;

    // --- Finality / hardening ---
    /**
     * Maximum depth of a chain reorganisation a node will perform. Blocks buried
     * deeper are final: even a heavier competing chain cannot rewrite them
     * (weak-subjectivity finality window). At 1 block/s, 600 ≈ 10 minutes.
     */
    private final int maxReorgDepth;
    /**
     * Static checkpoints: height → required block hash. A block at a
     * checkpointed height whose hash differs is rejected outright. Published
     * with releases to pin history against long-range rewrites.
     */
    @lombok.Builder.Default
    private final java.util.Map<Long, rhizome.core.crypto.SHA256Hash> checkpoints = java.util.Map.of();

    // --- Economics (all amounts are integers scaled by decimalScaleFactor) ---
    private final long decimalScaleFactor;
    /** Reward at height 0, already scaled (e.g. 50 * scale). */
    private final long initialReward;
    /** Number of blocks between reward reductions. */
    private final long rewardEpochBlocks;
    /** Reward is multiplied by {@code rewardDecayNum/rewardDecayDen} each epoch. */
    private final long rewardDecayNum;
    private final long rewardDecayDen;

    /**
     * Deterministic, integer-only mining reward for a block at {@code height}.
     *
     * <p>Every epoch of {@link #rewardEpochBlocks} blocks the reward is scaled by
     * {@code rewardDecayNum/rewardDecayDen} using integer multiply-then-divide, so
     * the result is identical on every platform. This is the fix for Pandanite's
     * {@code double}-based reward, which risked consensus forks on rounding.
     */
    public long miningReward(long height) {
        if (height < 0) {
            throw new IllegalArgumentException("height must be non-negative");
        }
        long reward = initialReward;
        long epochs = height / rewardEpochBlocks;
        for (long i = 0; i < epochs && reward > 0; i++) {
            reward = reward * rewardDecayNum / rewardDecayDen;
        }
        return reward;
    }

    /**
     * The clean Rhizome mainnet: Pufferfish2 PoW from genesis, seeded from a
     * Pandanite balance snapshot. Economics mirror Pandanite (50 PDN base,
     * 2/3 decay) but are computed in integer arithmetic.
     */
    public static NetworkParameters cleanMainnet() {
        long scale = 10_000L;
        return NetworkParameters.builder()
            .chainId(1)
            .networkName("rhizome-mainnet")
            .powAlgorithm(PowAlgorithm.PUFFERFISH2)
            .genesisTimestamp(0L)
            .genesisDifficulty(16)
            // Target one block per second. minBlockTimeSec == the target makes the
            // consensus floor also the metronome: nobody can go faster, and the
            // difficulty retarget keeps the average from going slower.
            .desiredBlockTimeSec(1)
            .minBlockTimeSec(1)
            .difficultyLookback(60)
            .minDifficulty(16)
            .maxDifficulty(255)
            // Tight future bound: at most maxFuture/minBlockTime blocks can ever be
            // mined "in advance" before real time must catch up (15 here).
            .maxFutureBlockTimeSec(15)
            .medianTimeWindow(11)
            .maxTransactionsPerBlock(25_000)
            .maxReorgDepth(600)
            .decimalScaleFactor(scale)
            .initialReward(50L * scale)
            .rewardEpochBlocks(666_666L)
            .rewardDecayNum(2L)
            .rewardDecayDen(3L)
            .build();
    }

    /**
     * A low-difficulty network for local development and tests. Keeps a relaxed
     * timing profile (no min-block-time floor, wide future bound, longer target)
     * so tests can drive controlled clocks freely; the 1-block/s consensus floor
     * is a mainnet property, exercised by dedicated tests via explicit params.
     */
    public static NetworkParameters testnet() {
        return cleanMainnet().toBuilder()
            .chainId(2)
            .networkName("rhizome-testnet")
            .genesisDifficulty(6)
            .minDifficulty(6)
            .desiredBlockTimeSec(90)
            .minBlockTimeSec(0)
            .difficultyLookback(100)
            .maxFutureBlockTimeSec(120)
            .build();
    }
}
