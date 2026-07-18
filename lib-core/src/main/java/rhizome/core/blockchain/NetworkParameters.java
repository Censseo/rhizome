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
    /** Window (in blocks) for the median-time-past lower bound. */
    private final int medianTimeWindow;
    private final int maxTransactionsPerBlock;

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
            .desiredBlockTimeSec(90)
            .difficultyLookback(100)
            .minDifficulty(6)
            .maxDifficulty(255)
            .maxFutureBlockTimeSec(120)
            .medianTimeWindow(11)
            .maxTransactionsPerBlock(25_000)
            .decimalScaleFactor(scale)
            .initialReward(50L * scale)
            .rewardEpochBlocks(666_666L)
            .rewardDecayNum(2L)
            .rewardDecayDen(3L)
            .build();
    }

    /** A low-difficulty network for local development and tests. */
    public static NetworkParameters testnet() {
        return cleanMainnet().toBuilder()
            .chainId(2)
            .networkName("rhizome-testnet")
            .genesisDifficulty(6)
            .build();
    }
}
