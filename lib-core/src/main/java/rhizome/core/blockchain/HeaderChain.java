package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
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
        INVALID_SUPPLY,
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
            // Supply accounting (§ supply header commitment, FR-003/FR-004/FR-006/US3): cheap,
            // header-only integer arithmetic reading only the previous header through the
            // combined `at` view (never a body, never ledger state) -- so it sits with the other
            // structural checks, after vote/difficulty and BEFORE the memory-hard PoW check below
            // (DoS armor ordering, WHITEPAPER §3.5). This is the SAME Issuance.minted formula and
            // the SAME prefix-closure rule ChainEngine.addBlock's checkSupply enforces (FR-007) --
            // a forged emission chain is rejected at ~158 B/header, before this header's own PoW
            // is even verified, let alone any later header's.
            Rejection supplyRejection = checkSupply(params, header, at.apply(h - 1));
            if (supplyRejection != null) {
                return Result.reject(supplyRejection, h);
            }
            // Cheapest-first, mirroring ChainEngine.addBlock (audit: validation order): the
            // timestamp bounds are pure comparisons, so they run BEFORE the memory-hard PoW —
            // a forged window then costs the verifier zero hashes instead of one.
            if (header.timestamp() <= Retarget.medianTimePast(params, at, h - 1)) {
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
                expectedDifficulty = Retarget.stepWindow(params, at, expectedDifficulty, h);
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
            // Shared with ChainEngine's fold (see Retarget); only the checkpoint memo below is
            // this validator's own, because a reorg can rewrite a window under it.
            difficulty = Retarget.stepWindow(params, at, difficulty, boundary);
            if (memo != null) {
                memo.put(boundary, new DifficultyCheckpoint(difficulty, at.apply(boundary).hash()));
            }
        }
        return difficulty;
    }

    /**
     * Structural uncle check + summed committed work, or {@code null} if the references are
     * malformed. Eligibility against the orphan pool is deferred to full validation — it needs
     * the bodies. Shared with the engine so header sync and block validation cannot disagree.
     */
    private static BigInteger uncleWork(BlockHeader header, NetworkParameters params) {
        return UncleWeight.structuralWork(header.uncles(), header.difficulty(), params);
    }

    /**
     * Supply accounting gate (§ supply header commitment, FR-003/FR-004), header-only: the SAME
     * rule as {@code ChainEngine.checkSupply}, enforced through the one shared home
     * {@link SupplyGate} (009-native-coin-burn: registry OI-4's collapse) --
     *
     * <ul>
     *   <li>Parent supply-less (FR-004 prefix closure): {@code header} must ALSO be supply-less --
     *       a mid-chain start is rejected exactly like a dropped commitment.</li>
     *   <li>Parent committed: {@code header} MUST commit too, and must satisfy the shared rule --
     *       exact equality against {@code parent.supply + Issuance.minted(header)} at this stage
     *       (FR-003) -- computed here from {@code header} and {@code parent} alone, no body, no
     *       ledger state.</li>
     * </ul>
     *
     * <p>Returns {@code null} on success, or the {@link Rejection} to report at this header's
     * height — this caller's own mapping of the gate's neutral verdict (S-5).
     */
    private static Rejection checkSupply(NetworkParameters params, BlockHeader header, BlockHeader parent) {
        SupplyGate.Verdict verdict = SupplyGate.check(params, header.id(), parent.supply(),
            header.supply(), header.difficulty(), header.uncles());
        return verdict == SupplyGate.Verdict.OK ? null : Rejection.INVALID_SUPPLY;
    }
}
