package rhizome.core.blockchain;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import rhizome.core.common.Constants;
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

    /**
     * Maximum serialized block size in bytes. Bounds a block's cost to download,
     * store and validate — critical now that contract transactions carry
     * variable-length payloads (without it a single block could be gigabytes).
     */
    private final int maxBlockSizeBytes;

    /**
     * Maximum uncle references a block may carry (GHOST). Bounds header growth and
     * the extra work a single block can credit. 0 disables uncles.
     */
    private final int maxUnclesPerBlock;

    /**
     * How many generations back an uncle may fork from the main chain (GHOST). An
     * uncle whose parent is older than this is too stale to reference. Ethereum uses 7.
     */
    private final int uncleMaxDepth;

    // --- Finality / hardening ---
    /**
     * Maximum depth of a chain reorganisation a node will perform. Blocks buried
     * deeper are final: even a heavier competing chain cannot rewrite them
     * (weak-subjectivity finality window). At 5 s/block, 120 ≈ 10 minutes.
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
     * GHOST uncle economics. An included uncle pays its miner
     * {@code miningReward * uncleRewardNum / uncleRewardDen}; the including
     * (nephew) block's miner earns {@code miningReward / nephewRewardDivisor} per
     * uncle on top of the base reward. Both are fresh issuance, but every uncle is
     * a real proof-of-work block, so no reward is ever minted without matching work.
     * A flat fraction (not distance-scaled) keeps the reward derivable from the
     * committed uncle refs alone, so reorg reversal is exact.
     */
    @lombok.Builder.Default
    private final long uncleRewardNum = 1;
    @lombok.Builder.Default
    private final long uncleRewardDen = 2;
    @lombok.Builder.Default
    private final long nephewRewardDivisor = 32;

    // --- Data boxes ---
    /** Height at which box transactions become valid (0 = from genesis). */
    @lombok.Builder.Default
    private final long boxActivationHeight = 0;
    /** Maximum serialized size of a box, in bytes. */
    @lombok.Builder.Default
    private final int maxBoxSizeBytes = 65_536;
    /** Maximum number of registers a box may carry. */
    @lombok.Builder.Default
    private final int maxBoxRegisters = 6;
    /** Base units a box must lock per serialized byte (anti-dust; refunded on spend). */
    @lombok.Builder.Default
    private final long minValuePerByte = 1;
    /** Age (in blocks) after which a box may be charged storage rent. */
    @lombok.Builder.Default
    private final long storagePeriodBlocks = 6_307_200L; // ~1 year at 5 s
    /** Storage rent in base units per serialized byte, per storage period. */
    @lombok.Builder.Default
    private final long storageFeeFactor = 1;
    /** Maximum BOX_COLLECT transactions a block may carry (bounds rent-collection work). */
    @lombok.Builder.Default
    private final int maxBoxCollectsPerBlock = 32;

    // --- Native tokens ---
    /** Height at which token transactions become valid (0 = from genesis). */
    @lombok.Builder.Default
    private final long tokenActivationHeight = 0;
    /** Maximum bytes of a token symbol (UTF-8). */
    @lombok.Builder.Default
    private final int maxTokenSymbolBytes = 16;
    /** Maximum bytes of a token name (UTF-8). */
    @lombok.Builder.Default
    private final int maxTokenNameBytes = 64;
    /** Maximum decimals a token may declare. */
    @lombok.Builder.Default
    private final int maxTokenDecimals = 18;

    // --- Miner-voted parameters ---
    /** Blocks per voting epoch; at each boundary the epoch's votes are tallied. */
    @lombok.Builder.Default
    private final long votingEpochLength = 1024;
    /** Adjustment step and bounds for the votable {@code storageFeeFactor}. */
    @lombok.Builder.Default
    private final long storageFeeFactorStep = 1;
    @lombok.Builder.Default
    private final long storageFeeFactorMin = 0;
    @lombok.Builder.Default
    private final long storageFeeFactorMax = 1_000;
    /** Adjustment step and bounds for the votable {@code minValuePerByte}. */
    @lombok.Builder.Default
    private final long minValuePerByteStep = 1;
    @lombok.Builder.Default
    private final long minValuePerByteMin = 0;
    @lombok.Builder.Default
    private final long minValuePerByteMax = 1_000;

    /** Reward paid to an included uncle's miner at {@code height}. */
    public long uncleReward(long height) {
        return miningReward(height) * uncleRewardNum / uncleRewardDen;
    }

    /** Bonus paid to the nephew (including block) miner per included uncle at {@code height}. */
    public long nephewReward(long height) {
        return miningReward(height) / nephewRewardDivisor;
    }

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
            // Target one block every FIVE seconds, PACED BY DIFFICULTY (a Poisson
            // average), not by a per-block time floor. minBlockTimeSec MUST stay 0
            // here: setting it equal to the target starves the retarget — the producer
            // floors every timestamp to parent+minBlockTime, so a 60-block window
            // always measures ~= the desired duration and difficulty never rises to
            // track hashrate. Difficulty would then stay pinned near minDifficulty
            // regardless of the real hashrate, collapsing PoW cost to a fixed
            // 2^minDifficulty and letting an attacker rewrite history or win the
            // future-bound reward race for near-free. Letting difficulty do the pacing
            // is what actually makes each block cost work, so outpacing the chain
            // needs real (majority) hashrate.
            //
            // Why 5 s and not 1 s: propagation is the binding constraint. At ~200 ms
            // network propagation, a 1 s target orphans ~18% of honest blocks
            // (1 - e^(-0.2)); at 5 s it is ~4%. GHOST absorbs orphaned work either
            // way, but a high steady orphan rate still favours a selfish miner (whose
            // private chain never races itself) and multiplies bandwidth and storage.
            // 5 s keeps near-instant UX with a comfortable margin; 1 s becomes viable
            // once compact-block propagation exists.
            .desiredBlockTimeSec(5)
            .minBlockTimeSec(0)
            .difficultyLookback(60)
            .minDifficulty(16)
            .maxDifficulty(255)
            // Future bound kept tight: divided by the block time it is the count of
            // blocks an attacker could pre-mine "into the future" and release to force
            // a reorg, so 15 s (≈3 blocks at 5 s) while still tolerating NTP-level skew.
            .maxFutureBlockTimeSec(15)
            // Median-time-past over ~5 minutes of blocks, so a miner holding a few
            // consecutive blocks cannot meaningfully drag the chain's notion of past
            // time at this cadence.
            .medianTimeWindow(60)
            .maxTransactionsPerBlock(25_000)
            .maxBlockSizeBytes(Constants.MAX_BLOCK_SIZE_BYTES)
            .maxUnclesPerBlock(2)
            .uncleMaxDepth(7)
            // ~10 minutes of wall-clock finality at 5 s/block.
            .maxReorgDepth(120)
            .decimalScaleFactor(scale)
            // Emission schedule, recalibrated for the 5-second cadence (see
            // WHITEPAPER.md §5.3). The decay epoch is measured in BLOCKS, so a value
            // tuned for slow blocks collapses in real time when blocks are fast: the
            // Pandanite-style 666,666-block epoch spans ~1.9 years at 90 s/block but
            // only ~38 days at 5 s/block, draining the whole subsidy in a few years.
            // Both knobs are therefore rescaled by the cadence ratio (×18 = 90/5) so
            // the REAL-TIME schedule is preserved: ~1.9-year epochs, ~48k PDN/day at
            // launch, ~100M PDN total — independent of the block rate.
            .initialReward(50L * scale * 5L / 90L) // 2.7777 PDN/block (was 50 PDN @ 90 s)
            .rewardEpochBlocks(666_666L * 18L)     // ~12,000,000 blocks ≈ 1.9 years @ 5 s
            .rewardDecayNum(2L)
            .rewardDecayDen(3L)
            .build();
    }

    /**
     * A low-difficulty network for local development and tests. Keeps a relaxed
     * timing profile (no min-block-time floor, wide future bound, longer target)
     * so tests can drive controlled clocks freely; the fast-cadence consensus floor
     * is a mainnet property, exercised by dedicated tests via explicit params.
     */
    public static NetworkParameters testnet() {
        // Testnet is the low-difficulty development network: coins carry no value, so
        // ASIC-resistance is irrelevant here and the memory-hard Pufferfish2 would only
        // make local devnets/CI slow. Mainnet (cleanMainnet) keeps PUFFERFISH2. This is a
        // deliberate per-network choice, not the old bug where Pufferfish2 was silently
        // never invoked (Crypto.concatHashes now honors the flag).
        return cleanMainnet().toBuilder()
            .chainId(2)
            .networkName("rhizome-testnet")
            .powAlgorithm(PowAlgorithm.SHA256)
            .genesisDifficulty(6)
            .minDifficulty(6)
            .desiredBlockTimeSec(90)
            .minBlockTimeSec(0)
            .difficultyLookback(100)
            .maxFutureBlockTimeSec(120)
            .build();
    }
}
