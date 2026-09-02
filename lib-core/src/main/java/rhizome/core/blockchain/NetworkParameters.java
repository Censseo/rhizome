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
 * <p>Rhizome starts a <em>clean</em> chain (fresh genesis, corrected rules) whose genesis
 * ledger is seeded from a {@code LedgerSnapshot} — for mainnet, an explicit, pinned allocation
 * (see {@link #genesisSupply}), not an import of the Pandanite chain. The parameters here
 * deliberately fix the design flaws found in the Pandanite C++ implementation:
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

    // --- Supply-driven logarithmic emission curve (004-integer-log-curve) ---
    /**
     * Monetary target {@code S*} the curve converges circulating supply toward, in base units.
     * A fixed per-network consensus constant like {@link #chainId} — never voted, never
     * environment-configurable. Must be strictly positive and, when this profile pins a genesis
     * supply ({@link #genesisSupply}), strictly above it: the curve's target must lie ahead of
     * where the chain starts. See {@link EmissionCurve}.
     */
    private final long supplyTarget;
    /**
     * Calibration constant {@code c} (base units) fixing the curve's convergence timescale: near
     * equilibrium the relaxation time is {@code supplyTarget / emissionCoefficient} blocks. Must
     * be strictly positive. See {@link EmissionCurve}.
     */
    private final long emissionCoefficient;
    /**
     * Number of uniform table steps the curve is generated at over {@code (0, supplyTarget]}
     * ({@code >= 2}). See {@link EmissionCurve}.
     */
    private final int emissionTableSteps;
    /**
     * Height at which the supply-driven curve reward applies (0 = <em>never</em>, the
     * {@link #powUpgradeHeight} polarity — NOT the {@link #boxActivationHeight}/
     * {@link #tokenActivationHeight} "0 = from genesis" polarity). Below this height, or on a
     * profile that never schedules it, {@link #miningReward(long, long)} returns exactly the
     * geometric reward. See {@link #emissionCurveActiveAt} / {@link #emissionCurveActiveForNextBlock}.
     */
    @lombok.Builder.Default
    private final long emissionCurveHeight = 0;

    /**
     * The miner revenue floor {@code R_min}, in base units — the consensus-guaranteed minimum
     * scheduled base reward for a block mined under an active curve
     * ({@code miningReward(height, parentSupply) >= R_min > 0} at every curve-active height and
     * every supply; contracts/miner-revenue-floor.md). A plain strictly-positive long — there is
     * deliberately no "unset" sentinel: the floor applies at every curve-active height, and a
     * profile that never schedules the curve (emissionCurveHeight == 0) never reaches this code
     * path, so the value is inert there, exactly like the feature-03 calibration constants.
     *
     * <p>Per-profile consensus constant set like {@link #chainId}. {@code testnet()} and
     * {@code devnet()} derive from {@code cleanMainnet().toBuilder()} and deliberately inherit
     * this floor value unchanged (WI-9 audit decision) even though, since
     * 006-emission-fork-activation, they no longer agree on {@link #emissionCurveHeight}:
     * {@code devnet()} states its own positive height and is curve-active, so the floor is live
     * there exactly as on mainnet; {@code testnet()} states {@code 0} (never) and the inherited
     * floor value stays inert, exactly like the feature-004 calibration constants.
     *
     * <p><b>Not inert for a profile that turns the curve on.</b> Any {@code toBuilder()} profile
     * that sets a positive {@code emissionCurveHeight} (every curve test does, and so does the
     * shipped {@code devnet()}) inherits this mainnet-scale constant against a possibly
     * test-scale {@code supplyTarget}. When
     * {@code R_min} exceeds the curve's own first table entry the floor swallows the entire
     * schedule and every block pays a constant {@code R_min} — set an explicit, profile-scaled
     * floor there rather than assuming the mainnet value stays out of the way.
     *
     * <p>Calibration: {@code 800} base units (0.08 PDN) on mainnet ≈ {@code R₀/32.6} at the
     * provisional genesis allocation — research.md Decision 3 (the low-subsidy regime against
     * {@code minDifficulty}/{@code maxReorgDepth}, crossover at {@code S ≈ 0.9669 × S*}, tail
     * emission ≈ 0.168 % of {@code S*} per year).
     */
    @lombok.Builder.Default
    private final long minerRevenueFloor = 800L;

    // --- Decaying supply target (008-decaying-supply-target) ---
    /**
     * Height at which the supply target {@code S*} begins its scheduled decay toward
     * {@link #supplyTargetFloor} (0 = <em>never</em> — the {@link #powUpgradeHeight}/
     * {@link #emissionCurveHeight} polarity, NOT the {@link #boxActivationHeight} "0 = from
     * genesis" polarity; both conventions live on this class, and copying the wrong sibling
     * silently activates the decay on every network). On a profile at the sentinel,
     * {@link #supplyTargetSchedule} holds the peak everywhere and the whole feature is inert.
     *
     * <p>Per-profile consensus constant set like {@link #chainId} — never voted, never
     * environment-configurable (FR-009). See {@link SupplyTargetSchedule}.
     */
    @lombok.Builder.Default
    private final long decayStartHeight = 0;
    /** Epoch length {@code E} in blocks between target reductions (inert at the sentinel). */
    @lombok.Builder.Default
    private final long decayEpochBlocks = 0;
    /** Per-epoch ratio numerator (inert at the sentinel). */
    @lombok.Builder.Default
    private final long decayNum = 0;
    /** Per-epoch ratio denominator (inert at the sentinel). */
    @lombok.Builder.Default
    private final long decayDen = 0;
    /**
     * The target floor {@code S*_floor} the decay stops at (inert at the sentinel). Must be
     * strictly between 0 and {@link #supplyTarget} whenever the decay is scheduled.
     */
    @lombok.Builder.Default
    private final long supplyTargetFloor = 0;

    // --- Native coin burn (009-native-coin-burn) ---
    /**
     * Burn share numerator {@code βₙ} of the pinned per-network fraction
     * {@code βₙ/β_d} of a block's eligible fee pool consensus destroys at a curve-active height
     * (see {@code Burn}): {@code burned = min(⌊pool × βₙ / β_d⌋, debt)}. A pinned consensus
     * constant like {@link #chainId} — never voted, never environment-configurable. Constraints
     * are enforced at construction: {@code βₙ >= 0} and, strictly, {@code βₙ < β_d} — a 100 %
     * share is the empty-block failure mode and is refused as a calibration, not merely advised
     * against. See {@link #burnShareDen}.
     */
    @lombok.Builder.Default
    private final long burnShareNum = 1L;
    /**
     * Burn share denominator {@code β_d}; must be strictly positive with
     * {@link #burnShareNum} strictly below it. Mainnet ships {@code 1/2}: the miner always keeps
     * at least half of every fee plus the whole subsidy, which is what makes
     * "including a transaction is strictly profitable" provable once, for all supplies
     * (contracts/native-coin-burn.md §5).
     */
    @lombok.Builder.Default
    private final long burnShareDen = 2L;

    /**
     * The generated stepped table for {@code (supplyTarget, emissionCoefficient,
     * emissionTableSteps)}, built once at construction time so a degenerate curve configuration
     * fails fast at node startup rather than mid-chain on the first curve-active evaluation. Not
     * builder-settable — purely derived from the three constants above.
     */
    @lombok.Getter(lombok.AccessLevel.NONE)
    private final EmissionCurve emissionCurve;

    /**
     * The derived decay schedule {@code S*(h)}, built eagerly at construction time from
     * {@link #supplyTarget} and the five decay constants above (plus {@link #emissionCoefficient},
     * from which the per-epoch reduction bound is derived) — exactly as {@link #emissionCurve} is.
     * Not builder-settable — a builder-supplied value is discarded, so a constructed instance's
     * schedule can never drift from its own constants. Validation lives in
     * {@link SupplyTargetSchedule#build}: a degenerate decay configuration refuses node start
     * here, never mid-chain (FR-022).
     */
    @lombok.Getter(lombok.AccessLevel.NONE)
    private final SupplyTargetSchedule supplyTargetSchedule;

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

    // --- Genesis allocation (pinned supply) ---
    /**
     * Sentinel meaning "no pin is in force" for {@link #genesisSupply} — the boot-time
     * equality check ({@code GenesisBlock.build}) does not run. Sits outside the
     * non-negative consensus range so it can never collide with a real pinned total,
     * exactly like {@code BlockImpl.SUPPLY_ABSENT} (feature 01's sentinel discipline). A
     * pinned {@code 0} remains a distinct, meaningful value ("this network mandates an
     * empty genesis"), which is why {@code 0} cannot double as the sentinel.
     */
    public static final long GENESIS_SUPPLY_UNPINNED = -1;

    /**
     * Pinned genesis ledger total S₀, in base units — a fixed per-network consensus
     * constant, set per profile exactly like {@link #chainId}: it MUST NOT be
     * miner-voted (supply is expressly non-votable — {@code VoteableParams} is a
     * separate, disjoint set of knobs), MUST NOT be configurable by environment
     * variable, and MUST NOT be derived from the snapshot at runtime (the direction of
     * the check is always snapshot → constant, never the reverse). {@link
     * #GENESIS_SUPPLY_UNPINNED} means this profile pins nothing and the genesis-supply
     * equality check is skipped for it.
     */
    @lombok.Builder.Default
    private final long genesisSupply = GENESIS_SUPPLY_UNPINNED;

    /**
     * Classpath resource path of this network's default genesis allocation artifact
     * (network metadata, like {@link #networkName} — not itself a consensus value: it
     * only selects which snapshot a default boot loads, and is never committed to the
     * chain). {@code null} means no shipped default for this profile. Exposed as {@link
     * #genesisSnapshotResource()} returning an {@link java.util.Optional}.
     */
    @lombok.Getter(lombok.AccessLevel.NONE)
    private final String genesisSnapshotResource;

    /** See {@link #genesisSnapshotResource}. */
    public java.util.Optional<String> genesisSnapshotResource() {
        return java.util.Optional.ofNullable(genesisSnapshotResource);
    }

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
                      long supplyTarget, long emissionCoefficient, int emissionTableSteps,
                      long emissionCurveHeight, long minerRevenueFloor,
                       long decayStartHeight, long decayEpochBlocks, long decayNum,
                       long decayDen, long supplyTargetFloor,
                       long burnShareNum, long burnShareDen,
                       EmissionCurve ignoredBuilderEmissionCurve,
                      SupplyTargetSchedule ignoredBuilderSupplyTargetSchedule,
                      long boxActivationHeight, int maxBoxSizeBytes, int maxBoxRegisters,
                      long minValuePerByte, long storagePeriodBlocks, long storageFeeFactor,
                      int maxBoxCollectsPerBlock, long tokenActivationHeight,
                      int maxTokenSymbolBytes, int maxTokenNameBytes, int maxTokenDecimals,
                      long votingEpochLength, long storageFeeFactorStep, long storageFeeFactorMin,
                      long storageFeeFactorMax, long minValuePerByteStep, long minValuePerByteMin,
                      long minValuePerByteMax, long genesisSupply, String genesisSnapshotResource) {
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
        // A negative activation height would invert emissionCurveActiveAt for every height
        // (including the Long.MAX_VALUE sentinel edge) — same fail-fast discipline as
        // boxActivationHeight/tokenActivationHeight above.
        if (emissionCurveHeight < 0) {
            throw new IllegalArgumentException("emissionCurveHeight must be >= 0");
        }
        // The floor's whole job is a strictly positive subsidy; a non-positive floor silently
        // restores the zero-clamp cliff the feature exists to remove (research.md Decision 2) -- a
        // misconfiguration must fail fast at node startup, not mid-chain under the engine lock.
        if (minerRevenueFloor <= 0) {
            throw new IllegalArgumentException("minerRevenueFloor must be > 0");
        }
        // supplyTarget must lie strictly ahead of a pinned genesis supply — a curve that starts
        // at or above its own target is degenerate. Unpinned profiles (GENESIS_SUPPLY_UNPINNED)
        // skip this check, mirroring how genesisSupply's equality check is skipped elsewhere.
        if (genesisSupply != GENESIS_SUPPLY_UNPINNED && supplyTarget <= genesisSupply) {
            throw new IllegalArgumentException(
                "supplyTarget must be > genesisSupply when a genesis supply is pinned");
        }
        // Native coin burn share (009-native-coin-burn, guards G-1..G-3): pinned consensus
        // constants — never voted, never environment-configurable (data-model.md §5) — so a
        // profile that cannot state a proper fraction must not start. G-3 is strict on purpose:
        // at βₙ == β_d a block burns its entire fee pool, so a mined transaction raises the burn
        // by exactly its own fee and the miner nets nothing for including it — every rational
        // miner prefers empty blocks and the fee market collapses (the empty-block failure mode).
        // A share above 100 % would reach into the minted subsidy, which the pool's construction
        // forbids; both are refused as calibrations, here at startup, never mid-chain.
        if (burnShareDen <= 0) { // G-1
            throw new IllegalArgumentException("network '" + networkName
                + "': burnShareDen must be > 0, was " + burnShareDen
                + " — the burn share num/den must be a proper fraction");
        }
        if (burnShareNum < 0) { // G-2
            throw new IllegalArgumentException("network '" + networkName
                + "': burnShareNum must be >= 0, was " + burnShareNum
                + " — a negative share would mint coin on the burn path");
        }
        if (burnShareNum >= burnShareDen) { // G-3
            throw new IllegalArgumentException("network '" + networkName
                + "': burnShareNum (" + burnShareNum + ") must be < burnShareDen (" + burnShareDen
                + ") — a 100 % burn is refused as a calibration: a block would destroy its whole "
                + "fee pool, an empty block would beat every block that includes transactions, "
                + "and the burn would reach past the fee pool into minted coin");
        }
        // Guard G-4 (009-native-coin-burn, contracts/native-coin-burn.md §3): the coefficient must
        // not exceed the LOWEST target the schedule can reach — the decay floor when a decay is
        // scheduled (supplyTargetFloor > 0 exactly when it is; an unscheduled profile holds the
        // 0 sentinel and its peak IS its floor), else the peak itself. This turns the
        // `ln(x) <= x − 1` argument into a checked invariant: obligation(h) <= c·ln(x) + 1 and
        // debt(h) >= S*·(x − 1) + minted, so `c <= S*_floor` together with `minted >= R_min >= 1`
        // secures `obligation <= debt` at every height — the block's clamp can never be looser
        // than what the debt permits, so the burn can always be applied in full.
        long sFloor = supplyTargetFloor > 0 ? supplyTargetFloor : supplyTarget;
        if (emissionCoefficient > sFloor) {
            throw new IllegalArgumentException("network '" + networkName
                + "': emissionCoefficient (" + emissionCoefficient + ") must be <= the lowest "
                + "supply target (" + sFloor + (supplyTargetFloor > 0 ? ", supplyTargetFloor"
                    : ", the peak — no decay is scheduled")
                + ") — this secures obligation <= debt at every height, so a block's burn "
                + "obligation is always covered by the carried debt it clamps against");
        }
        // EmissionCurve.build validates supplyTarget/coefficient/steps itself (IllegalArgumentException)
        // and does the O(N) table generation eagerly, right here at construction time — a
        // degenerate curve configuration must fail fast at node startup, never mid-chain on the
        // first curve-active evaluation. `ignoredBuilderEmissionCurve` exists only so this
        // constructor's parameter list has one entry per class field (Lombok's class-level
        // @Builder generates its build() call from the full field list, positionally) — the
        // curve is ALWAYS derived fresh from supplyTarget/emissionCoefficient/emissionTableSteps
        // below, never from a builder-supplied value, so a constructed instance's curve can
        // never drift from its own constants.
        this.emissionCurve = EmissionCurve.build(supplyTarget, emissionCoefficient, emissionTableSteps);
        // Same discipline as the curve above: the schedule is ALWAYS derived fresh from the five
        // decay constants (+ the coefficient), never taken from the builder — and its own
        // construction-time validation refuses a degenerate decay configuration here, at node
        // startup, never mid-chain.
        this.supplyTargetSchedule = SupplyTargetSchedule.build(supplyTarget, decayStartHeight,
            decayEpochBlocks, decayNum, decayDen, supplyTargetFloor, emissionCoefficient);
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
        this.supplyTarget = supplyTarget;
        this.emissionCoefficient = emissionCoefficient;
        this.emissionTableSteps = emissionTableSteps;
        this.emissionCurveHeight = emissionCurveHeight;
        this.minerRevenueFloor = minerRevenueFloor;
        this.decayStartHeight = decayStartHeight;
        this.decayEpochBlocks = decayEpochBlocks;
        this.decayNum = decayNum;
        this.decayDen = decayDen;
        this.supplyTargetFloor = supplyTargetFloor;
        this.burnShareNum = burnShareNum;
        this.burnShareDen = burnShareDen;
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
        this.genesisSupply = genesisSupply;
        this.genesisSnapshotResource = genesisSnapshotResource;
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
     * Whether the supply-driven curve reward (see {@link #emissionCurveHeight}) governs a block
     * at {@code height}: active only when a height has actually been scheduled ({@code > 0}) and
     * {@code height} has reached it. Unlike {@link #boxActiveAt}/{@link #tokenActiveAt}, 0 means
     * <em>never</em> here (the {@link #powUpgradeHeight} polarity), not "from genesis".
     */
    public boolean emissionCurveActiveAt(long height) {
        return emissionCurveHeight > 0 && height >= emissionCurveHeight;
    }

    /**
     * The next-block mirror of {@link #emissionCurveActiveAt}, structurally parallel to
     * {@link #boxActiveForNextBlock}/{@link #tokenActiveForNextBlock} (same sentinel-safe
     * subtraction, so the {@code Long.MAX_VALUE} "past every activation" sentinel resolves true
     * without overflow) — but unlike those two, it currently has no production caller. Box/token
     * transactions sit in the mempool awaiting a not-yet-known future block, so admission
     * ({@code TransactionAdmission}) must predict activation for a height it does not control;
     * a coinbase is never mempool-pooled — {@code BlockAssembler} always knows the exact next
     * height it is building for and reaches the identical answer via {@link #emissionCurveActiveAt}
     * inside {@link #miningReward(long, long)}'s own dispatch. Kept for pattern symmetry and in
     * case a future mempool-side consumer needs it (e.g. previewing economics for a pooled
     * transaction); exercised today only by its own unit test.
     */
    public boolean emissionCurveActiveForNextBlock(long confirmedHeight) {
        return emissionCurveHeight > 0 && confirmedHeight >= emissionCurveHeight - 1;
    }

    /**
     * The derived decay schedule {@code S*(h)} for this network — always present (an unscheduled
     * profile holds a schedule whose {@link SupplyTargetSchedule#targetAt} is the peak everywhere).
     * Manual accessor because the field's builder-supplied twin is discarded
     * ({@code @Getter(NONE)}); derived exactly like {@code emissionCurve}, never builder-settable.
     */
    public SupplyTargetSchedule supplyTargetSchedule() {
        return supplyTargetSchedule;
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
     * Deterministic, integer-only mining reward for a block at {@code height}, given the
     * parent's committed circulating supply — the supply-driven twin of {@link #miningReward(long)}.
     *
     * <p>Dispatches on {@link #emissionCurveActiveAt}: below activation (or on a profile that
     * never schedules the curve) this returns exactly {@link #miningReward(long)}'s geometric
     * result, ignoring {@code parentSupply} entirely. At or above activation it returns the
     * curve's raw value at {@code parentSupply}, measured against the <b>live</b> supply target
     * {@code S*(h)} ({@link #supplyTargetSchedule}'s {@code targetAt(height)} — the peak below
     * the scheduled decay-start height, decayed above it), floored to
     * {@link #minerRevenueFloor} — the single clamp site (contracts/miner-revenue-floor.md §2;
     * 008 contracts/supply-target-schedule.md §5): the curve's negative branch (supply at/above
     * the live target) and every supply whose raw value drops below the floor must never mint a
     * reward below {@code R_min}. {@code EmissionCurve.raw} itself stays signed — this
     * {@code max} is the only clamp, so the floored base is what every derivation (uncle,
     * nephew, {@code Issuance.minted}) sees. The inputs are still exactly
     * {@code (height, parent header's committed supply)} — no ledger read is introduced, so
     * coinbase validation stays in the pre-PoW structural pass.
     */
    public long miningReward(long height, long parentSupply) {
        if (emissionCurveActiveAt(height)) {
            return Math.max(minerRevenueFloor,
                emissionCurve.raw(parentSupply, supplyTargetSchedule.targetAt(height)));
        }
        return miningReward(height);
    }

    /**
     * The live supply target {@code S*(h)} the curve measures distance from at {@code height} —
     * the peak below/at the decay-start height, decayed geometrically per epoch to the floor
     * after it. Derived publication accessor (FR-017): the same value
     * {@link #miningReward(long, long)} dispatches through, exposed so the node API and tests
     * publish the number that actually governs rather than re-deriving it (one formula, one
     * home — {@link SupplyTargetSchedule#targetAt}).
     */
    public long supplyTargetAt(long height) {
        return supplyTargetSchedule.targetAt(height);
    }

    /**
     * The block at {@code height}'s <b>burn obligation</b>: {@code max(0, -raw(parentSupply,
     * targetAt(h)))} while the curve governs, {@code 0} below activation or on a profile that
     * never schedules the curve (008 contracts/supply-target-schedule.md §6). Derived, never
     * stored, never on the consensus wire, and <b>never enforced by this feature</b> — no coin
     * is destroyed here; publication only (the destruction mechanism is feature 08's). A
     * non-zero obligation states how much the block <em>may</em> destroy so supply tracks the
     * falling target; a cumulative form is deliberately absent (research.md Decision 4).
     */
    public long burnObligation(long height, long parentSupply) {
        if (!emissionCurveActiveAt(height)) {
            return 0;
        }
        long raw = emissionCurve.raw(parentSupply, supplyTargetSchedule.targetAt(height));
        return Math.max(0, Math.negateExact(raw));
    }

    /** The supply-driven twin of {@link #uncleReward(long)}, deriving from the dispatched base. */
    public long uncleReward(long height, long parentSupply) {
        return Math.multiplyExact(miningReward(height, parentSupply), uncleRewardNum) / uncleRewardDen;
    }

    /** The supply-driven twin of {@link #nephewReward(long)}, deriving from the dispatched base. */
    public long nephewReward(long height, long parentSupply) {
        return miningReward(height, parentSupply) / nephewRewardDivisor;
    }

    /**
     * The clean Rhizome mainnet: Pufferfish2 PoW from genesis, seeded from an explicit, pinned
     * allocation (see {@link #genesisSupply}) — not a Pandanite balance snapshot. Reward
     * economics mirror Pandanite's shape (50 PDN base, 2/3 decay) but are computed in integer
     * arithmetic.
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
            // Pinned genesis supply S0 (see genesisSupply javadoc): PROVISIONAL 100M PDN
            // (research.md Decision 4), the least-surprise calibration anchor for the
            // feature-03 emission curve pending governance ratification of the final
            // allocation. Must equal the shipped artifact's recomputed total exactly —
            // LedgerSnapshotTest#theShippedAllocationMatchesThePinnedGenesisSupplyExactly
            // fails the build on any drift between this constant and the artifact.
            .genesisSupply(1_000_000_000_000L)
            .genesisSnapshotResource("genesis/rhizome-mainnet.json")
            // Supply-driven logarithmic emission curve constants (004-integer-log-curve;
            // WHITEPAPER.md §5.3; research.md Decision 6): calibrated for tau ~= 20 years at
            // this 5-second cadence, targeting the speed-of-light supply S* (299,792,458 PDN).
            .supplyTarget(2_997_924_580_000L)
            .emissionCoefficient(23_750L)
            .emissionTableSteps(256)
            // Activation height (006-emission-fork-activation): 1, not 0. Heights are 1-based and
            // genesis (height 0) pays no coinbase at all, so height 1 is the first block that pays
            // any coinbase -- scheduling the curve there means the very first minted block pays the
            // calibrated curve value rather than a geometric leftover. Mainnet is pre-launch, so
            // nothing already-mined is being reinterpreted, and c/S* are calibrated against this
            // profile's pinned S0 (genesisSupply above), which only exists once genesis is reached.
            .emissionCurveHeight(1L)
            // Miner revenue floor R_min (research.md Decision 3): 800 base units = 0.08 PDN, the
            // consensus-guaranteed minimum subsidy under an active curve (≈ R₀/32.6 at the
            // provisional allocation; crossover at S ≈ 0.9669 × S*; tail emission ≈ 0.168 % of
            // S* per year until feature 08's burn counterbalances it). Live from height 1 on
            // mainnet and devnet; inert only on testnet, which never schedules the curve.
            .minerRevenueFloor(800L)
            // The mainnet decay schedule (008-decaying-supply-target, T045 — set strictly LAST,
            // after every proof was green; the sequencing principle from 006). Calibrated per
            // research.md §Decision 3 (the single source the tests cite; contracts/
            // supply-target-schedule.md §8 cross-links it): decay starts at one relaxation time
            // (20 years at this 5 s cadence), falls 799/800 per quarter (0.4991 %/year) to the
            // floor S*/2 — reached E_f = 555 epochs later (H_f = 1 001 268 000). Until then every
            // height pays the peak target: bit-for-bit the pre-decay arithmetic.
            .decayStartHeight(126_144_000L)
            .decayEpochBlocks(1_576_800L)
            .decayNum(799L)
            .decayDen(800L)
            .supplyTargetFloor(1_498_962_290_000L)
            // Burn share (009-native-coin-burn, T004): 1/2 — the miner keeps the other half of
            // every fee plus the whole subsidy. Stated explicitly even though it equals the
            // builder default, so a future retuning of the default cannot silently move mainnet.
            .burnShareNum(1L)
            .burnShareDen(2L)
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
            // Explicitly un-pin (research.md Decision 7): this profile derives from
            // cleanMainnet().toBuilder(), which would otherwise silently inherit the
            // mainnet genesis-supply pin and its shipped-artifact resource. Testnet has no
            // launch to protect (Clarifications Q2) — the equality check must not run here,
            // and funded snapshots under this borrowed profile keep working via a builder
            // override, exactly as before this feature (~20 existing suites rely on it).
            .genesisSupply(GENESIS_SUPPLY_UNPINNED)
            .genesisSnapshotResource(null)
            // Explicitly never activate the curve (006-emission-fork-activation), rather than
            // inherit cleanMainnet()'s now-scheduled height: testnet is the test-shaped profile,
            // not a deployed network with operators to notify (Clarifications Q3). Keeping it at
            // 0 keeps the retained geometric rule (FR-007) exercised and the ~20 existing suites'
            // reward baseline deterministic — they assume the height-only miningReward(height)
            // form stays the mainnet-baseline geometric value.
            .emissionCurveHeight(0L)
            // Explicitly never schedule the decay (008 T045, WI-9): cleanMainnet() now carries a
            // 20-year decay start, and silent inheritance would make a testnet node carry a
            // schedule it can never reach. Stated, never inherited — and the whole group is
            // stated, not just the sentinel: SupplyTargetSchedule.build refuses an unscheduled
            // profile that still carries mainnet's inert epoch/ratio/floor, precisely so a
            // forgotten start height cannot masquerade as "no decay".
            .decayStartHeight(0L)
            .decayEpochBlocks(0L)
            .decayNum(0L)
            .decayDen(0L)
            .supplyTargetFloor(0L)
            // Explicitly restate the burn share (009 T004, WI-9): testnet derives from
            // cleanMainnet().toBuilder() and would silently inherit any future mainnet retuning.
            // The curve never activates on this profile, so the share is inert here — stated
            // anyway so the profile names its own monetary policy.
            .burnShareNum(1L)
            .burnShareDen(2L)
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
            // Explicitly un-pin (research.md Decision 7) — same reasoning as testnet(): this
            // profile also derives from cleanMainnet().toBuilder() and must not silently
            // inherit the mainnet genesis-supply pin or its shipped-artifact resource.
            .genesisSupply(GENESIS_SUPPLY_UNPINNED)
            .genesisSnapshotResource(null)
            // Explicitly restate (006-emission-fork-activation) rather than rely on inheriting
            // cleanMainnet()'s value: scripts/local-testnet/start.sh starts RHIZOME_NETWORK=devnet,
            // so a local network must mint under the same rule mainnet mints under to be
            // representative. Devnet's default empty genesis means these figures are uncalibrated
            // unless RHIZOME_SNAPSHOT supplies the shipped mainnet allocation — a known, accepted
            // limitation of running devnet without a funded snapshot.
            .emissionCurveHeight(1L)
            // Explicitly restate (008 T045, WI-9) rather than inherit cleanMainnet()'s decay:
            // unlike the curve — which devnet activates deliberately — a 20-year decay start is
            // unreachable on a devnet that lives for minutes, so scheduling it here would be
            // theatre. The restatement exists to defeat silent inheritance, not to enable the
            // feature (research.md Decision 5) — and covers the inert constants too, which
            // SupplyTargetSchedule.build now requires to agree with the sentinel.
            .decayStartHeight(0L)
            .decayEpochBlocks(0L)
            .decayNum(0L)
            .decayDen(0L)
            .supplyTargetFloor(0L)
            // Explicitly restate the burn share (009 T004, WI-9): same reasoning as testnet(),
            // but devnet IS curve-active — the 1/2 share is live here exactly as on mainnet.
            .burnShareNum(1L)
            .burnShareDen(2L)
            .build();
    }
}
