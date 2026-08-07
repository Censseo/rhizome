package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import rhizome.core.block.BlockHeader;
import rhizome.core.block.UncleRef;
import rhizome.crypto.SHA256Hash;

/**
 * Stateless validator for a contiguous run of block headers extending a trusted
 * fork point — the core of headers-first sync. Everything a node checks about a
 * block <em>except</em> transaction execution is verifiable from headers alone:
 * id continuity, hash chaining, proof-of-work, the difficulty recomputed from
 * header timestamps ({@link DifficultyAdjustment}), the median-time-past and
 * min-block-time rules, the future bound, and structural uncle limits. The
 * returned cumulative work is each header's {@code 2^difficulty} — BASE work
 * only, deliberately excluding the committed uncle difficulties: uncle refs
 * cannot be confirmed as real, pooled, eligible orphans until the bodies arrive,
 * so folding their claimed work into the gate would let a cheap branch inflate
 * its proof ~maxUnclesPerBlock× and force a pop/restore on every node (audit M4).
 * Genuine uncle work still decides fork choice authoritatively in the
 * synchronizer's phase-3 GHOST vote, after the bodies validated every ref.
 *
 * <p>What is <b>not</b> checked here — and must wait for the bodies in BODY_SYNC —
 * is uncle <em>eligibility</em> (that a referenced uncle is a real, recent,
 * not-yet-credited orphan) and, of course, transaction validity and the state
 * root. Those need data a header does not carry. The committed uncle difficulty,
 * however, is inside the PoW preimage, so the work a branch claims cannot exceed
 * the work its headers actually paid for.
 */
public final class HeaderChain {

    /** Why a header run was rejected (with the offending height), or {@link #NONE}. */
    public enum Rejection {
        NONE,
        DISCONTINUOUS_ID,
        BROKEN_CHAIN,
        INVALID_POW,
        WRONG_DIFFICULTY,
        TIMESTAMP_TOO_OLD,
        TIMESTAMP_TOO_CLOSE,
        TIMESTAMP_IN_FUTURE,
        INVALID_UNCLES,
        INVALID_VOTE,
        CHECKPOINT_MISMATCH
    }

    public record Result(boolean valid, Rejection rejection, long rejectedHeight, BigInteger work) {
        static Result ok(BigInteger work) {
            return new Result(true, Rejection.NONE, 0, work);
        }
        static Result reject(Rejection reason, long height) {
            return new Result(false, reason, height, BigInteger.ZERO);
        }
    }

    /**
     * One memoised retarget boundary: the difficulty in force AFTER that boundary sealed, plus
     * the hash of the boundary header it was folded from. The hash makes the memo self-invalidating:
     * a reorg that rewrote the boundary's window changes the header at that height, so a stale
     * entry is detected and dropped on next use — no explicit invalidation hook is needed, and a
     * memo shared across sync rounds (even across peers) can never feed back a wrong-chain value
     * (audit: O(height) difficulty-fold DoS). Header-only validation is monotone within one
     * {@code validate} call (the combined view trusted-chain-then-candidates is fixed for the
     * call), so folding forward from the highest still-matching boundary is exact.
     */
    public record DifficultyCheckpoint(int difficulty, SHA256Hash boundaryHash) {}

    private HeaderChain() {}

    /**
     * Validates {@code candidates} as heights {@code forkHeight+1, forkHeight+2, …}
     * extending the trusted chain, whose headers at or below {@code forkHeight} are
     * read through {@code trustedHeaderAt}. Returns the branch's cumulative work on
     * success, or the first rejection.
     *
     * @param trustedHeaderAt headers for heights in {@code [1..forkHeight]} (the local validated chain)
     * @param forkHeight      the common-ancestor height (≥ genesis)
     * @param candidates      the peer's headers above the fork, in ascending height order
     * @param nowMillis       current time, for the future-block bound
     */
    public static Result validate(NetworkParameters params, LongFunction<BlockHeader> trustedHeaderAt,
                                  long forkHeight, List<BlockHeader> candidates, long nowMillis) {
        return validate(params, trustedHeaderAt, forkHeight, candidates, nowMillis, null);
    }

    /**
     * As {@link #validate(NetworkParameters, LongFunction, long, List, long)}, with a retarget
     * memo keyed by boundary height (see {@link DifficultyCheckpoint}) so the difficulty fold
     * resumes from the highest still-valid boundary instead of refolding from genesis on every
     * call — amortised O(new boundaries) instead of O(forkHeight) before the first PoW check
     * (audit: O(height) fold DoS). The map is not thread-safe; callers must serialise its use.
     */
    public static Result validate(NetworkParameters params, LongFunction<BlockHeader> trustedHeaderAt,
                                  long forkHeight, List<BlockHeader> candidates, long nowMillis,
                                  java.util.NavigableMap<Long, DifficultyCheckpoint> difficultyMemo) {
        if (candidates.isEmpty()) {
            return Result.reject(Rejection.DISCONTINUOUS_ID, forkHeight + 1);
        }
        // Combined view over the virtual chain: trusted headers at/below the fork, candidates above.
        LongFunction<BlockHeader> at = h -> h <= forkHeight ? trustedHeaderAt.apply(h)
            : candidates.get((int) (h - forkHeight - 1));

        int lookback = params.difficultyLookback();
        // Difficulty the first candidate (height forkHeight+1) must carry, from the boundaries
        // already sealed at or below the fork — then stepped forward as we cross new boundaries.
        int expectedDifficulty = difficultyForNext(params, at, forkHeight, difficultyMemo);

        SHA256Hash prevHash = trustedHeaderAt.apply(forkHeight).hash();
        long expectedId = forkHeight + 1;
        BigInteger work = BigInteger.ZERO;

        for (BlockHeader header : candidates) {
            long h = header.id();
            if (h != expectedId) {
                return Result.reject(Rejection.DISCONTINUOUS_ID, expectedId);
            }
            if (!header.lastBlockHash().equals(prevHash)) {
                return Result.reject(Rejection.BROKEN_CHAIN, h);
            }
            // Enforce static checkpoints in the header gate too, not only in ChainEngine.addBlock: a
            // base-heavier branch diverging at/below a checkpointed height would otherwise pass the
            // gate, drive a pop of local blocks, and only be rejected block-by-block on the way back —
            // an attacker-extractable pop/restore cycle. Rejecting here short-circuits it before any
            // local mutation (audit V6d). (Mainnet ships an empty checkpoint map, so this is latent.)
            SHA256Hash checkpoint = params.checkpoints().get(h);
            if (checkpoint != null && !header.hash().equals(checkpoint)) {
                return Result.reject(Rejection.CHECKPOINT_MISMATCH, h);
            }
            if (header.difficulty() != expectedDifficulty) {
                return Result.reject(Rejection.WRONG_DIFFICULTY, h);
            }
            // The same canonical vote rule ChainEngine.addBlock enforces (audit F1): 0 (abstain) or
            // ±paramId (VoteableParams 1/2). Headers arriving over the wire are already bounded by
            // HeaderCodec, but the tally in ChainEngine.applyVotingAt trusts this gate for every
            // ingress path, so the bound is checked here too. Long abs guards Integer.MIN_VALUE.
            if (Math.abs((long) header.vote()) > 2) {
                return Result.reject(Rejection.INVALID_VOTE, h);
            }
            // Cheapest-first, mirroring ChainEngine.addBlock (audit: validation order): the
            // timestamp bounds are pure comparisons, so they run BEFORE the memory-hard PoW —
            // a forged window then costs the verifier zero hashes instead of one.
            if (header.timestamp() <= medianTimePast(params, at, h - 1)) {
                return Result.reject(Rejection.TIMESTAMP_TOO_OLD, h);
            }
            if (header.timestamp() < at.apply(h - 1).timestamp() + params.minBlockTimeSec() * 1000L) {
                return Result.reject(Rejection.TIMESTAMP_TOO_CLOSE, h);
            }
            if (header.timestamp() > nowMillis + params.maxFutureBlockTimeSec() * 1000L) {
                return Result.reject(Rejection.TIMESTAMP_IN_FUTURE, h);
            }
            if (!header.verifyNonce(params.powAlgorithm(), params.powCostsAt(header.id()))) {
                return Result.reject(Rejection.INVALID_POW, h);
            }
            // Validate the uncle references structurally (count, no dups, difficulty in range) but
            // do NOT fold their claimed work into the total used by the reorg gate. The uncles are
            // committed in the header preimage yet cannot be confirmed as real, pooled, eligible
            // orphans until the bodies arrive, so counting them here lets an attacker pad each
            // header with maxUnclesPerBlock same-difficulty fake uncles and inflate a cheap branch's
            // claimed work ~3× — passing the gate with ~1/3 honest work and forcing a deep
            // pop/restore on every node (audit M4, header-sync path). Base work only makes the gate
            // count only PoW we verified per header; genuine uncle work is still counted
            // authoritatively later in ChainEngine.addBlock/totalWork, with eligibility proven.
            if (uncleWork(header, params) == null) {
                return Result.reject(Rejection.INVALID_UNCLES, h);
            }
            work = work.add(BlockWork.of(header.difficulty()));

            prevHash = header.hash();
            expectedId++;
            // A completed retarget window seals the difficulty for the next block. This MUST
            // match ChainEngine.computeDifficultyFromChain exactly, including excluding the
            // genesis interval from the first window (audit L2) and choosing each bound's
            // measurement rule by the SAME activation predicate (boundary height vs
            // consensusV2Height, audit: timewarp) — otherwise header-sync validation and the
            // engine's own mining disagree and every synced chain is rejected as PEER_INVALID at
            // the first retarget.
            if (h % lookback == 0) {
                long windowStart = h - lookback + 1;
                long measureStart = Math.max(windowStart, GenesisBlock.GENESIS_ID + 1);
                long intervals = h - measureStart;
                if (intervals > 0) {
                    long observedMs = boundaryTimestamp(params, at, h) - boundaryTimestamp(params, at, measureStart);
                    expectedDifficulty = DifficultyAdjustment.nextDifficulty(
                        params, expectedDifficulty, intervals, observedMs / 1000);
                }
                if (difficultyMemo != null) {
                    // Cache the boundary sealed by this candidate too: if the branch is adopted the
                    // recorded hash matches the new canonical header and the entry stays valid; if
                    // not, the hash check drops it on next use (see DifficultyCheckpoint).
                    difficultyMemo.put(h, new DifficultyCheckpoint(expectedDifficulty, header.hash()));
                }
            }
        }
        return Result.ok(work);
    }

    /** Difficulty a block at {@code tip+1} must carry: genesis difficulty stepped through every sealed window ≤ tip. */
    private static int difficultyForNext(NetworkParameters params, LongFunction<BlockHeader> at, long tip,
                                         java.util.NavigableMap<Long, DifficultyCheckpoint> memo) {
        int lookback = params.difficultyLookback();
        long boundary = lookback;
        int difficulty = params.genesisDifficulty();
        if (memo != null) {
            // Resume the fold from the highest cached boundary whose recorded header hash still
            // matches the chain under validation; stale entries (a reorg rewrote their window)
            // are dropped so a lower, still-valid boundary takes over (see DifficultyCheckpoint).
            var floor = memo.floorEntry(tip);
            while (floor != null
                    && !floor.getValue().boundaryHash().equals(at.apply(floor.getKey()).hash())) {
                memo.remove(floor.getKey());
                floor = memo.floorEntry(tip);
            }
            if (floor != null) {
                boundary = floor.getKey() + lookback;
                difficulty = floor.getValue().difficulty();
            }
        }
        for (; boundary <= tip; boundary += lookback) {
            long windowStart = boundary - lookback + 1;
            // Mirror ChainEngine.computeDifficultyFromChain: exclude the genesis interval (audit L2).
            long measureStart = Math.max(windowStart, GenesisBlock.GENESIS_ID + 1);
            long intervals = boundary - measureStart;
            if (intervals > 0) {
                long observedMs = boundaryTimestamp(params, at, boundary) - boundaryTimestamp(params, at, measureStart);
                difficulty = DifficultyAdjustment.nextDifficulty(params, difficulty, intervals, observedMs / 1000);
            }
            if (memo != null) {
                memo.put(boundary, new DifficultyCheckpoint(difficulty, at.apply(boundary).hash()));
            }
        }
        return difficulty;
    }

    /**
     * The timestamp a retarget closing at boundary height {@code h} reads at bound {@code h}:
     * the median-of-3 ({@link #medianTimestamp}) when {@code params.consensusV2(h)} — i.e. the
     * retarget itself is at or past the activation height — and the raw boundary timestamp
     * (the legacy, timewarp-vulnerable rule) below it. The decision height is the BOUNDARY the
     * retarget closes at, and both bounds of one window use the same rule; ChainEngine uses the
     * identical predicate, so header sync and the engine never disagree across the activation.
     */
    private static long boundaryTimestamp(NetworkParameters params, LongFunction<BlockHeader> at, long h) {
        if (params.consensusV2(h)) {
            return medianTimestamp(at, h);
        }
        return at.apply(h).timestamp();
    }

    /**
     * The retarget-bound timestamp at height {@code h}: the median of the (up to) 3 header
     * timestamps ending at {@code h} inclusive, clamped at genesis (audit: timewarp; applies
     * only from {@code consensusV2Height} on, see {@link #boundaryTimestamp}). Measuring
     * a window from two raw timestamps let a miner inflate ONE boundary timestamp and stretch the
     * observed duration — dragging difficulty down at ~no hash cost. With a median-of-3 bound,
     * one manipulated timestamp moves the bound by at most the gap to its neighbour, so steering
     * the retarget needs a sustained multi-block manipulation instead of a single point. The
     * upper-median convention (index {@code size/2}, as {@code medianTimePast}) also keeps the
     * artificial genesis timestamp out of the first window's start bound. MUST match
     * {@code ChainEngine.medianBoundaryTimestamp} exactly — both sides compute the same retarget.
     */
    private static long medianTimestamp(LongFunction<BlockHeader> at, long h) {
        long lo = Math.max(GenesisBlock.GENESIS_ID, h - 2);
        int size = (int) (h - lo + 1);
        long[] timestamps = new long[size];
        for (int i = 0; i < size; i++) {
            timestamps[i] = at.apply(h - i).timestamp();
        }
        java.util.Arrays.sort(timestamps);
        return timestamps[size / 2];
    }

    /** Median timestamp of the last {@code medianTimeWindow} headers up to {@code tip} (inclusive). */
    private static long medianTimePast(NetworkParameters params, LongFunction<BlockHeader> at, long tip) {
        int window = (int) Math.min(params.medianTimeWindow(), tip);
        // Primitive long[] instead of a boxed List<Long>: this runs once per candidate header over a
        // sync window of up to MAX_HEADER_WINDOW, so the per-header boxing + comparator churn added up
        // (the engine's own add path already uses a primitive ring, audit P6). Same median: sort, take
        // index size/2.
        long[] timestamps = new long[window];
        int i = 0;
        for (long h = tip - window + 1; h <= tip; h++) {
            timestamps[i++] = at.apply(h).timestamp();
        }
        java.util.Arrays.sort(timestamps);
        return timestamps[window / 2];
    }

    /**
     * Structural uncle check + summed committed work, or {@code null} if the references are
     * malformed (too many, or duplicated within the block). Eligibility against the orphan
     * pool is deferred to full validation — it needs the bodies.
     */
    private static BigInteger uncleWork(BlockHeader header, NetworkParameters params) {
        List<UncleRef> uncles = header.uncles();
        if (uncles.size() > params.maxUnclesPerBlock()) {
            return null;
        }
        BigInteger work = BigInteger.ZERO;
        java.util.Set<SHA256Hash> seen = new java.util.HashSet<>();
        for (UncleRef ref : uncles) {
            if (!seen.add(ref.hash())) {
                return null; // duplicate uncle within one block
            }
            // An uncle's claimed work must be real and cannot exceed the contemporaneous
            // chain difficulty: bound it to [minDifficulty, header.difficulty()]. Without
            // this, a peer could commit uncles at difficulty maxDifficulty (255) on a
            // cheaply-mined minDifficulty branch and inflate its headers-only claimed work
            // toward 2^255 per header, defeating the anti-DoS work gate the headers-first
            // sync relies on. This is the SAME bound ChainEngine.uncleEligible enforces
            // (nephewDifficulty = the including block's own difficulty), so header-sync
            // validation, block validation and mining all agree — a value outside the range
            // is rejected by every path and can never split the chain.
            int d = ref.difficulty();
            if (d < params.minDifficulty() || d > header.difficulty()) {
                return null;
            }
            work = work.add(BlockWork.of(d));
        }
        return work;
    }
}
