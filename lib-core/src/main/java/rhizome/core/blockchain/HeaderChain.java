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
 * cumulative work (each header's {@code 2^difficulty} plus its committed uncle
 * difficulties) is returned so the synchronizer can compare branches by proven
 * PoW before downloading a single body.
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
        INVALID_UNCLES
    }

    public record Result(boolean valid, Rejection rejection, long rejectedHeight, BigInteger work) {
        static Result ok(BigInteger work) {
            return new Result(true, Rejection.NONE, 0, work);
        }
        static Result reject(Rejection reason, long height) {
            return new Result(false, reason, height, BigInteger.ZERO);
        }
    }

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
        if (candidates.isEmpty()) {
            return Result.reject(Rejection.DISCONTINUOUS_ID, forkHeight + 1);
        }
        // Combined view over the virtual chain: trusted headers at/below the fork, candidates above.
        LongFunction<BlockHeader> at = h -> h <= forkHeight ? trustedHeaderAt.apply(h)
            : candidates.get((int) (h - forkHeight - 1));

        int lookback = params.difficultyLookback();
        // Difficulty the first candidate (height forkHeight+1) must carry, from the boundaries
        // already sealed at or below the fork — then stepped forward as we cross new boundaries.
        int expectedDifficulty = difficultyForNext(params, at, forkHeight);

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
            if (header.difficulty() != expectedDifficulty) {
                return Result.reject(Rejection.WRONG_DIFFICULTY, h);
            }
            if (!header.verifyNonce(params.powAlgorithm())) {
                return Result.reject(Rejection.INVALID_POW, h);
            }
            if (header.timestamp() <= medianTimePast(params, at, h - 1)) {
                return Result.reject(Rejection.TIMESTAMP_TOO_OLD, h);
            }
            if (header.timestamp() < at.apply(h - 1).timestamp() + params.minBlockTimeSec() * 1000L) {
                return Result.reject(Rejection.TIMESTAMP_TOO_CLOSE, h);
            }
            if (header.timestamp() > nowMillis + params.maxFutureBlockTimeSec() * 1000L) {
                return Result.reject(Rejection.TIMESTAMP_IN_FUTURE, h);
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
            work = work.add(BigInteger.TWO.pow(header.difficulty()));

            prevHash = header.hash();
            expectedId++;
            // A completed retarget window seals the difficulty for the next block. This MUST
            // match ChainEngine.computeDifficultyFromChain exactly, including excluding the
            // genesis interval from the first window (audit L2) — otherwise header-sync
            // validation and the engine's own mining disagree and every synced chain is
            // rejected as PEER_INVALID at the first retarget.
            if (h % lookback == 0) {
                long windowStart = h - lookback + 1;
                long measureStart = Math.max(windowStart, GenesisBlock.GENESIS_ID + 1);
                long intervals = h - measureStart;
                if (intervals > 0) {
                    long observedMs = at.apply(h).timestamp() - at.apply(measureStart).timestamp();
                    expectedDifficulty = DifficultyAdjustment.nextDifficulty(
                        params, expectedDifficulty, intervals, observedMs / 1000);
                }
            }
        }
        return Result.ok(work);
    }

    /** Difficulty a block at {@code tip+1} must carry: genesis difficulty stepped through every sealed window ≤ tip. */
    private static int difficultyForNext(NetworkParameters params, LongFunction<BlockHeader> at, long tip) {
        int lookback = params.difficultyLookback();
        int difficulty = params.genesisDifficulty();
        for (long boundary = lookback; boundary <= tip; boundary += lookback) {
            long windowStart = boundary - lookback + 1;
            // Mirror ChainEngine.computeDifficultyFromChain: exclude the genesis interval (audit L2).
            long measureStart = Math.max(windowStart, GenesisBlock.GENESIS_ID + 1);
            long intervals = boundary - measureStart;
            if (intervals <= 0) {
                continue;
            }
            long observedMs = at.apply(boundary).timestamp() - at.apply(measureStart).timestamp();
            difficulty = DifficultyAdjustment.nextDifficulty(params, difficulty, intervals, observedMs / 1000);
        }
        return difficulty;
    }

    /** Median timestamp of the last {@code medianTimeWindow} headers up to {@code tip} (inclusive). */
    private static long medianTimePast(NetworkParameters params, LongFunction<BlockHeader> at, long tip) {
        int window = (int) Math.min(params.medianTimeWindow(), tip);
        List<Long> timestamps = new ArrayList<>(window);
        for (long h = tip - window + 1; h <= tip; h++) {
            timestamps.add(at.apply(h).timestamp());
        }
        timestamps.sort(Long::compare);
        return timestamps.get(timestamps.size() / 2);
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
            work = work.add(BigInteger.TWO.pow(d));
        }
        return work;
    }
}
