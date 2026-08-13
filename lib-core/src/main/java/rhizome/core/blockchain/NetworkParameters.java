package rhizome.core.blockchain;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import rhizome.core.common.Constants;
import rhizome.crypto.PowAlgorithm;
import rhizome.crypto.PowCosts;

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

    // --- Proof-of-work costs (memory-hardness upgrade path) ---
    /**
     * Pufferfish2 time cost in force from genesis. Defaults to {@link PowCosts#DEFAULT}'s
     * {@code cost_t} (0), so the existing chain re-verifies unchanged.
     */
    @lombok.Builder.Default
    private final int powCostT = 0;
    /**
     * Pufferfish2 memory cost in force from genesis. Defaults to {@link PowCosts#DEFAULT}'s
     * {@code cost_m} (8), so the existing chain re-verifies unchanged.
     */
    @lombok.Builder.Default
    private final int powCostM = 8;
    /**
     * Height at which the PoW costs switch to {@link #powCostTAfter}/{@link #powCostMAfter}
     * (0 = never). This is THE memory-hardness upgrade path: raising the costs is a
     * consensus change, so it is scheduled at a coordinated height rather than changed in
     * place — blocks below the height keep verifying under the genesis costs, blocks at or
     * above it under the new ones, and no existing block is re-interpreted.
     *
     * <p><b>Polarity:</b> 0 means <em>never</em> here — the inverse of
     * {@link #consensusV2Height}, {@link #boxActivationHeight} and
     * {@link #tokenActivationHeight}, where 0 means <em>from genesis</em>. The inversion is
     * the historical configuration semantics each field shipped with; unifying the polarities
     * would be a network-configuration change (every network's upgrade schedule would need a
     * re-specified height), not a refactor, so the polarities stay and this note pins the
     * difference. See {@link #powCostsAt} for the one consumer.
     */
    @lombok.Builder.Default
    private final long powUpgradeHeight = 0;
    /** PoW time cost from {@link #powUpgradeHeight} on; -1 = unset (only valid when no upgrade). */
    @lombok.Builder.Default
    private final int powCostTAfter = -1;
    /** PoW memory cost from {@link #powUpgradeHeight} on; -1 = unset (only valid when no upgrade). */
    @lombok.Builder.Default
    private final int powCostMAfter = -1;

    // --- Consensus-V2 activation ---
    /**
     * Height at which the consensus-V2 rules take effect (0 = from genesis). V2 bundles three
     * audit fixes that would otherwise reinterpret already-accepted history — an unplanned hard
     * fork on any chain with a past:
     * <ul>
     *   <li>difficulty retarget bounds measured by median-of-3 timestamps (anti-timewarp)
     *       instead of the two raw boundary timestamps;</li>
     *   <li>the {@link #minFee} floor enforced at consensus ({@code TRANSACTION_FEE_TOO_LOW}),
     *       not only at mempool admission;</li>
     *   <li>a zero-amount deposit no longer creates the recipient wallet.</li>
     * </ul>
     * On a live chain this MUST be scheduled at a future height coordinated across nodes, exactly
     * like {@link #powUpgradeHeight}: blocks below the height keep verifying under the legacy
     * rules, blocks at or above it under V2. On a fresh chain (clean mainnet/testnet) 0 is
     * correct — V2 applies from genesis and there is no history to reinterpret.
     *
     * <p><b>Polarity:</b> 0 means <em>from genesis</em> (the {@link #powUpgradeHeight} field is
     * the inverse: 0 = never there).
     */
    @lombok.Builder.Default
    private final long consensusV2Height = 0;

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
     * Minimum fee a poolable transaction must pay (0 = no floor, the default). A configured floor
     * lets an operator reject free transactions at mempool admission (the previously-unused
     * {@code TRANSACTION_FEE_TOO_LOW} status). From {@link #consensusV2Height} on the same floor
     * is also a consensus rule (blocks carrying an under-fee transaction are rejected); below
     * that height it remains a local admission policy only.
     */
    @lombok.Builder.Default
    private final long minFee = 0L;

    /**
     * Maximum serialized block size in bytes. Bounds a block's cost to download,
     * store and validate — critical now that contract transactions carry
     * variable-length payloads (without it a single block could be gigabytes).
     */
    private final int maxBlockSizeBytes;

    /**
     * Maximum gas a single contract transaction may declare. A contract's {@code gasLimit} is
     * otherwise bounded only by affordability, and with {@code gasPrice = 0} (valid at consensus —
     * the min-fee floor is a mempool-only policy) a zero-cost call can name an arbitrary limit, so a
     * miner could put a {@code loop … br 0} call with a 10^11 limit in a block they mine: it clears
     * PoW (verified first), is relayed, then every validating node runs those billions of
     * instructions synchronously under the consensus lock — a free liveness DoS disproportionate to
     * the attacker's hash power. This is the consensus-side twin of the read-only VM cap
     * ({@code ContractApi.MAX_READONLY_GAS}); a real call needs a few million gas, so the ceiling is
     * generous. 0 disables the cap. See WHITEPAPER.md §7.3/§7.5.
     */
    @lombok.Builder.Default
    private final long maxTxGas = 50_000_000L;

    /**
     * Maximum total {@code gasLimit} the contract transactions in one block may declare (the
     * block gas limit). {@link #maxTxGas} bounds one transaction; this bounds the block, so a block
     * packed with many capped calls still has a finite worst-case validation cost. Checked against
     * declared limits (not gas actually used), so the bound holds before any instruction runs.
     * 0 disables the ceiling. See WHITEPAPER.md §7.3.
     */
    @lombok.Builder.Default
    private final long maxBlockGas = 250_000_000L;

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
    private final java.util.Map<Long, rhizome.crypto.SHA256Hash> checkpoints = java.util.Map.of();

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
     * GHOST uncle economics. These are the <b>base</b> amounts: an included uncle's miner
     * is paid {@code miningReward * uncleRewardNum / uncleRewardDen} and the including
     * (nephew) block's miner earns {@code miningReward / nephewRewardDivisor} per uncle on
     * top of the base reward. {@link Executor#scaleRewardToWork} then scales each by the
     * uncle's proven work relative to the nephew's difficulty (halving per missing bit), so
     * a cheap sub-difficulty orphan cannot mint a disproportionate reward (audit C1). Both
     * are fresh issuance, but every uncle is a real proof-of-work block and its reward is
     * proportional to that work, so no reward is ever minted without matching work. The
     * scaling reads only the committed uncle and nephew difficulties, so reorg reversal
     * stays exact.
     */
    @lombok.Builder.Default
    private final long uncleRewardNum = 1;
    @lombok.Builder.Default
    private final long uncleRewardDen = 2;
    @lombok.Builder.Default
    private final long nephewRewardDivisor = 32;

    // --- Data boxes ---
    /**
     * Height at which box transactions become valid (0 = from genesis). Judged by the executor
     * through {@link #boxActiveAt} and by the mempool through {@link #boxActiveForNextBlock} —
     * never by a raw height comparison. Polarity: 0 = from genesis, like {@link #consensusV2Height}
     * and unlike {@link #powUpgradeHeight} (0 = never).
     */
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
    /**
     * Height at which token transactions become valid (0 = from genesis). Judged by the executor
     * through {@link #tokenActiveAt} and by the mempool through {@link #tokenActiveForNextBlock}.
     * Polarity: 0 = from genesis, like {@link #boxActivationHeight}.
     */
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
    /**
     * Lower bound the miner vote can push {@code storageFeeFactor} to. Must stay {@code >= 1}
     * (audit M8): at 0 the box storage rent is nil, so a majority of hashrate voting -1 for one
     * epoch (~513 blocks) makes permanent on-chain storage free — unbounded state bloat paid by
     * every full node at zero cost to the voters, who do not internalize storage costs.
     */
    @lombok.Builder.Default
    private final long storageFeeFactorMin = 1;
    @lombok.Builder.Default
    private final long storageFeeFactorMax = 1_000;
    /** Adjustment step and bounds for the votable {@code minValuePerByte}. */
    @lombok.Builder.Default
    private final long minValuePerByteStep = 1;
    /**
     * Lower bound the miner vote can push {@code minValuePerByte} to. Must stay {@code >= 1}
     * (audit M8): at 0 the anti-dust floor {@code value >= size × minValuePerByte} collapses, so
     * boxes of any size can be created with zero locked value — free, permanent state growth.
     */
    @lombok.Builder.Default
    private final long minValuePerByteMin = 1;
    @lombok.Builder.Default
    private final long minValuePerByteMax = 1_000;

    /**
     * All-args constructor used by the Lombok builder. Validates the PoW-cost schedule at
     * build time: the "after" costs may only be set together with a positive
     * {@link #powUpgradeHeight}, and every cost pair must be a valid {@link PowCosts} —
     * a misconfigured upgrade must fail fast at node startup, not mid-chain.
     */
    NetworkParameters(int chainId, String networkName, PowAlgorithm powAlgorithm,
                      int powCostT, int powCostM, long powUpgradeHeight,
                      int powCostTAfter, int powCostMAfter, long consensusV2Height,
                      long genesisTimestamp, int genesisDifficulty,
                      int desiredBlockTimeSec, int difficultyLookback, int minDifficulty, int maxDifficulty,
                      int maxFutureBlockTimeSec, int minBlockTimeSec, int medianTimeWindow,
                      int maxTransactionsPerBlock, long minFee, int maxBlockSizeBytes,
                      long maxTxGas, long maxBlockGas, int maxUnclesPerBlock, int uncleMaxDepth,
                      int maxReorgDepth, java.util.Map<Long, rhizome.crypto.SHA256Hash> checkpoints,
                      long decimalScaleFactor, long initialReward, long rewardEpochBlocks,
                      long rewardDecayNum, long rewardDecayDen,
                      long uncleRewardNum, long uncleRewardDen, long nephewRewardDivisor,
                      long boxActivationHeight, int maxBoxSizeBytes, int maxBoxRegisters,
                      long minValuePerByte, long storagePeriodBlocks, long storageFeeFactor,
                      int maxBoxCollectsPerBlock, long tokenActivationHeight,
                      int maxTokenSymbolBytes, int maxTokenNameBytes, int maxTokenDecimals,
                      long votingEpochLength, long storageFeeFactorStep, long storageFeeFactorMin,
                      long storageFeeFactorMax, long minValuePerByteStep, long minValuePerByteMin,
                      long minValuePerByteMax) {
        new PowCosts(powCostT, powCostM); // genesis costs must be valid
        boolean afterSet = powCostTAfter != -1 || powCostMAfter != -1;
        if (powUpgradeHeight <= 0 && afterSet) {
            throw new IllegalArgumentException(
                "powCostTAfter/powCostMAfter require a positive powUpgradeHeight");
        }
        if (powUpgradeHeight > 0) {
            if (powCostTAfter == -1 || powCostMAfter == -1) {
                throw new IllegalArgumentException(
                    "powUpgradeHeight requires both powCostTAfter and powCostMAfter");
            }
            new PowCosts(powCostTAfter, powCostMAfter); // upgraded costs must be valid
        }
        if (consensusV2Height < 0) {
            throw new IllegalArgumentException("consensusV2Height must be >= 0");
        }
        // A negative activation height would invert the box/token predicates (every height
        // reads "active", including the Long.MAX_VALUE sentinel edge in the mempool gate) —
        // a misconfiguration must fail fast at node startup, not silently activate domains
        // on every chain this node validates.
        if (boxActivationHeight < 0) {
            throw new IllegalArgumentException("boxActivationHeight must be >= 0");
        }
        if (tokenActivationHeight < 0) {
            throw new IllegalArgumentException("tokenActivationHeight must be >= 0");
        }
        // Degenerate consensus constants must fail fast at build time, not mid-chain under the
        // engine lock (audit: unvalidated params): a zero lookback divides by zero on every
        // retarget boundary, a negative one loops the difficulty fold, a non-positive window or
        // block time degenerates the retarget/MTP math, and an inverted difficulty range pins
        // the chain at the floor.
        if (difficultyLookback <= 0) {
            throw new IllegalArgumentException("difficultyLookback must be > 0");
        }
        if (medianTimeWindow <= 0) {
            throw new IllegalArgumentException("medianTimeWindow must be > 0");
        }
        if (desiredBlockTimeSec <= 0) {
            throw new IllegalArgumentException("desiredBlockTimeSec must be > 0");
        }
        if (minDifficulty < 0 || maxDifficulty < minDifficulty) {
            throw new IllegalArgumentException("require 0 <= minDifficulty <= maxDifficulty");
        }
        // Divisors used by the integer-only reward math below.
        if (rewardEpochBlocks <= 0) {
            throw new IllegalArgumentException("rewardEpochBlocks must be > 0");
        }
        if (rewardDecayDen <= 0 || uncleRewardDen <= 0 || nephewRewardDivisor <= 0) {
            throw new IllegalArgumentException("reward denominators must be > 0");
        }
        this.chainId = chainId;
        this.networkName = networkName;
        this.powAlgorithm = powAlgorithm;
        this.powCostT = powCostT;
        this.powCostM = powCostM;
        this.powUpgradeHeight = powUpgradeHeight;
        this.powCostTAfter = powCostTAfter;
        this.powCostMAfter = powCostMAfter;
        this.consensusV2Height = consensusV2Height;
        this.genesisTimestamp = genesisTimestamp;
        this.genesisDifficulty = genesisDifficulty;
        this.desiredBlockTimeSec = desiredBlockTimeSec;
        this.difficultyLookback = difficultyLookback;
        this.minDifficulty = minDifficulty;
        this.maxDifficulty = maxDifficulty;
        this.maxFutureBlockTimeSec = maxFutureBlockTimeSec;
        this.minBlockTimeSec = minBlockTimeSec;
        this.medianTimeWindow = medianTimeWindow;
        this.maxTransactionsPerBlock = maxTransactionsPerBlock;
        this.minFee = minFee;
        this.maxBlockSizeBytes = maxBlockSizeBytes;
        this.maxTxGas = maxTxGas;
        this.maxBlockGas = maxBlockGas;
        this.maxUnclesPerBlock = maxUnclesPerBlock;
        this.uncleMaxDepth = uncleMaxDepth;
        this.maxReorgDepth = maxReorgDepth;
        this.checkpoints = checkpoints;
        this.decimalScaleFactor = decimalScaleFactor;
        this.initialReward = initialReward;
        this.rewardEpochBlocks = rewardEpochBlocks;
        this.rewardDecayNum = rewardDecayNum;
        this.rewardDecayDen = rewardDecayDen;
        this.uncleRewardNum = uncleRewardNum;
        this.uncleRewardDen = uncleRewardDen;
        this.nephewRewardDivisor = nephewRewardDivisor;
        this.boxActivationHeight = boxActivationHeight;
        this.maxBoxSizeBytes = maxBoxSizeBytes;
        this.maxBoxRegisters = maxBoxRegisters;
        this.minValuePerByte = minValuePerByte;
        this.storagePeriodBlocks = storagePeriodBlocks;
        this.storageFeeFactor = storageFeeFactor;
        this.maxBoxCollectsPerBlock = maxBoxCollectsPerBlock;
        this.tokenActivationHeight = tokenActivationHeight;
        this.maxTokenSymbolBytes = maxTokenSymbolBytes;
        this.maxTokenNameBytes = maxTokenNameBytes;
        this.maxTokenDecimals = maxTokenDecimals;
        this.votingEpochLength = votingEpochLength;
        this.storageFeeFactorStep = storageFeeFactorStep;
        this.storageFeeFactorMin = storageFeeFactorMin;
        this.storageFeeFactorMax = storageFeeFactorMax;
        this.minValuePerByteStep = minValuePerByteStep;
        this.minValuePerByteMin = minValuePerByteMin;
        this.minValuePerByteMax = minValuePerByteMax;
    }

    /**
     * Whether the consensus-V2 rules (see {@link #consensusV2Height}) govern a block at
     * {@code height}: true from the activation height on, false below it. Every V2-gated
     * rule — the retarget bound measurement, the consensus fee floor, the zero-deposit
     * wallet-creation skip — keys off this single predicate so both sides of any mirror
     * (HeaderChain/ChainEngine, Executor apply/rollback) always agree.
     */
    public boolean consensusV2(long height) {
        return height >= consensusV2Height;
    }

    /**
     * Whether box transactions are valid in a block at {@code height} (see
     * {@link #boxActivationHeight}). The executor judges the block's own height through this;
     * the mempool judges the NEXT block through {@link #boxActiveForNextBlock}. One predicate
     * per domain, like {@link #consensusV2(long)}, so the admission side and the validation
     * side can never disagree on where the boundary falls.
     */
    public boolean boxActiveAt(long height) {
        return height >= boxActivationHeight;
    }

    /**
     * Whether token transactions are valid in a block at {@code height} (see
     * {@link #tokenActivationHeight}); the token twin of {@link #boxActiveAt}.
     */
    public boolean tokenActiveAt(long height) {
        return height >= tokenActivationHeight;
    }

    /**
     * Whether box transactions are valid in the block AFTER the one at {@code confirmedHeight} —
     * the height the mempool judges a pooled transaction against. Expressed subtractively on
     * purpose: {@code confirmedHeight} may be the {@code Long.MAX_VALUE} "past every activation"
     * sentinel of {@code AccountView.confirmedHeight()}, and an
     * {@code activeAt(confirmedHeight + 1)} form would overflow the sentinel to
     * {@code Long.MIN_VALUE} and refuse a domain that activated long ago.
     */
    public boolean boxActiveForNextBlock(long confirmedHeight) {
        return boxActivationHeight <= 0 || confirmedHeight >= boxActivationHeight - 1;
    }

    /** The token twin of {@link #boxActiveForNextBlock}, with the same sentinel-safe subtraction. */
    public boolean tokenActiveForNextBlock(long confirmedHeight) {
        return tokenActivationHeight <= 0 || confirmedHeight >= tokenActivationHeight - 1;
    }

    /**
     * The Pufferfish2 cost parameters in force for a block at {@code height}: the genesis
     * costs below {@link #powUpgradeHeight} (or always, when no upgrade is scheduled), the
     * "after" costs from the upgrade height on.
     */
    public PowCosts powCostsAt(long height) {
        if (powUpgradeHeight <= 0 || height < powUpgradeHeight) {
            return new PowCosts(powCostT, powCostM);
        }
        return new PowCosts(powCostTAfter, powCostMAfter);
    }

    /** Reward paid to an included uncle's miner at {@code height}. */
    public long uncleReward(long height) {
        // multiplyExact, not a silent wrap: with the shipped values the product cannot overflow,
        // but an extreme custom configuration must fail loud instead of emitting erratic rewards
        // (audit: multiply-then-divide overflow).
        return Math.multiplyExact(miningReward(height), uncleRewardNum) / uncleRewardDen;
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
            // multiplyExact: overflow means a misconfigured emission curve — fail loud, never wrap
            // (audit: multiply-then-divide overflow).
            reward = Math.multiplyExact(reward, rewardDecayNum) / rewardDecayDen;
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
            // Consensus gas ceiling: one contract call is capped at 50M gas (ample — real calls use a
            // few million; matches the read-only VM cap), and a block's contract calls total at most
            // 250M declared gas, so the worst-case VM work a valid block can force on every node is
            // finite regardless of gasPrice. Without these a mined block could carry a free 10^11-gas
            // loop and stall the network under the consensus lock (audit: unbounded consensus gas).
            .maxTxGas(50_000_000L)
            .maxBlockGas(250_000_000L)
            .maxUnclesPerBlock(2)
            .uncleMaxDepth(7)
            // Mempool admission floor (policy, not consensus): every relayed transaction must
            // promise the miner at least this much revenue — 0.001 PDN for a transfer/box/token
            // op, or an equivalent declared gas budget for a contract call. At the default 0 the
            // pool accepts unlimited zero-fee transactions, and with fee-blind block selection an
            // attacker could fill every block with free spam, censoring honest traffic at zero
            // marginal cost (audit M9). Testnet keeps 0 so local devnets need no funded fees.
            .minFee(10L)
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
            // No mempool fee floor on testnet (see cleanMainnet's minFee): local devnets and
            // tests transact with unfunded fees; the anti-spam floor is a mainnet property.
            .minFee(0)
            .build();
    }

    /**
     * A local devnet: testnet's cheap PoW on mainnet's real 5-second cadence.
     *
     * <p>Testnet deliberately targets 90 s so tests can drive controlled clocks, which makes it
     * the wrong profile for a node an operator actually runs and watches. Pacing a testnet
     * producer at the mainnet-like 5 s (RHIZOME_BLOCK_INTERVAL_MS) makes every retarget window
     * measure ~18x too fast, and since each halving of observed-vs-desired earns +1 bit
     * ({@link DifficultyAdjustment#nextDifficulty}), difficulty climbs ~4 bits per window until
     * the chain grinds to a halt — 6 -> 29 bits, i.e. 64 -> ~537M hashes per block, in seven
     * windows. Devnet fixes the mismatch at the source by retargeting on the same 5 s the
     * producer is paced at, so difficulty settles near the floor instead of running away.
     *
     * <p>Everything else stays borrowed: SHA256 and a difficulty floor of 6 from testnet (coins
     * are worthless, memory-hard PoW would only slow CI), no mempool fee floor so unfunded local
     * transactions relay, and a short retarget window so the cadence corrects quickly rather than
     * after 100 blocks of drift.
     */
    public static NetworkParameters devnet() {
        return cleanMainnet().toBuilder()
            .chainId(3)
            .networkName("rhizome-devnet")
            .powAlgorithm(PowAlgorithm.SHA256)
            .genesisDifficulty(6)
            .minDifficulty(6)
            // Cap the climb well below the point where a single laptop core stalls: even if the
            // retarget overshoots, 2^24 hashes is seconds of work, not hours. Devnet cares about
            // staying alive under an operator's eyes, not about PoW being expensive.
            .maxDifficulty(24)
            // Keep cleanMainnet's desiredBlockTimeSec(5) — the whole point of the profile.
            .minBlockTimeSec(0)
            // Short window: a devnet is restarted constantly and rarely runs 60 blocks, so
            // retarget on 20 to keep the feedback loop inside a typical session.
            .difficultyLookback(20)
            // Wide future bound like testnet: local clocks drift and nodes get suspended.
            .maxFutureBlockTimeSec(120)
            .minFee(0)
            .build();
    }
}
