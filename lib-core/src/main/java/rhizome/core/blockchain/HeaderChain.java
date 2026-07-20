package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import rhizome.core.block.BlockHeader;
import rhizome.core.block.UncleRef;
import rhizome.core.crypto.SHA256Hash;

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
            BigInteger uncleWork = uncleWork(header, params);
            if (uncleWork == null) {
                return Result.reject(Rejection.INVALID_UNCLES, h);
            }
            work = work.add(BigInteger.TWO.pow(header.difficulty())).add(uncleWork);

            prevHash = header.hash();
            expectedId++;
            // A completed retarget window seals the difficulty for the next block.
            if (h % lookback == 0) {
                long windowStart = h - lookback + 1;
                long observedMs = at.apply(h).timestamp() - at.apply(windowStart).timestamp();
                expectedDifficulty = DifficultyAdjustment.nextDifficulty(
                    params, expectedDifficulty, lookback - 1L, observedMs / 1000);
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
            long observedMs = at.apply(boundary).timestamp() - at.apply(windowStart).timestamp();
            difficulty = DifficultyAdjustment.nextDifficulty(params, difficulty, lookback - 1L, observedMs / 1000);
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
            // Defense-in-depth against the BigInteger.TWO.pow(difficulty) OOM/blow-up.
            // Difficulty is a leading-zero-bit count, so it is bounded by maxDifficulty;
            // reject an out-of-range value rather than fold pow(2^31) into the total. This
            // mirrors the header decode bound (uncleWork also runs on locally-held headers)
            // and is a pure safety bound — not a new consensus rule — so it can never reject
            // an uncle the engine's validateUncles would accept.
            int d = ref.difficulty();
            if (d < 0 || d > params.maxDifficulty()) {
                return null;
            }
            work = work.add(BigInteger.TWO.pow(d));
        }
        return work;
    }
}
