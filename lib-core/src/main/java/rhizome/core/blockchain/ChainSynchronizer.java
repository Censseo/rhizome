package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import rhizome.core.block.Block;
import rhizome.core.block.BlockImpl;
import rhizome.core.common.Constants;
import rhizome.crypto.SHA256Hash;
import rhizome.core.mempool.ExecutionStatus;

/**
 * Synchronises the local chain toward a peer, adopting the peer's chain only if
 * it has strictly greater cumulative work — the objective fork-choice rule
 * (Pandanite forked repeatedly for lack of one).
 *
 * <p>Hardened against hostile peers:
 * <ul>
 *   <li><b>Finality window</b> — a reorg deeper than
 *       {@code NetworkParameters.maxReorgDepth} is refused outright; buried
 *       blocks cannot be rewritten no matter the claimed work.</li>
 *   <li><b>No free rollbacks</b> — before any local state is touched, a bounded
 *       prefix of the peer branch is fetched and validated statelessly:
 *       id continuity, hash chaining from the fork point, per-block
 *       proof-of-work, and total verified work strictly above our own branch.
 *       A peer that merely <em>claims</em> huge work can therefore cost us a
 *       bounded download and some hash checks — never a pop/restore cycle.
 *       Passing this gate requires actually spending more PoW than our chain
 *       carries.</li>
 *   <li><b>Restore on failure</b> — if the stateful apply still fails (e.g.
 *       wrong derived difficulty), the local chain is restored exactly.</li>
 * </ul>
 */
public final class ChainSynchronizer {

    public enum Result { NO_CHANGE, EXTENDED, REORGED, REORG_TOO_DEEP, INCOMPATIBLE, PEER_INVALID, PEER_PRUNED }

    /** Extra blocks fetched beyond the fork depth during pre-validation. */
    static final int PREFETCH_EXTRA = 2 * Constants.BLOCKS_PER_FETCH;

    /** Hard cap on exponential-probe steps in the ancestor search, so it stays O(log height). */
    private static final int MAX_ANCESTOR_PROBES = 64;

    private final ChainEngine engine;

    public ChainSynchronizer(ChainEngine engine) {
        this.engine = engine;
    }

    public Result syncFrom(PeerSource peer) {
        // Prefilter against BASE work (not the uncle-inclusive total), matching the base-only adoption
        // gate below — see HeaderSynchronizer.syncFrom for the full rationale (audit 5th-pass,
        // reorg-gate metric). peer.totalWork() bounds the peer's base work from above, so this never
        // skips a peer the adoption gate would accept, and over-reporting still can't force a reorg.
        if (peer.totalWork().compareTo(engine.baseWork()) <= 0) {
            return Result.NO_CHANGE;
        }

        long forkHeight;
        try {
            forkHeight = findCommonAncestor(peer);
        } catch (RuntimeException e) {
            // A peer that throws or returns an empty/garbage response while we probe for the common
            // ancestor must be treated as invalid, exactly like the bulk fetch phases below — never
            // allowed to propagate out of syncFrom and disrupt the whole sync pass (audit V6c).
            return Result.PEER_INVALID;
        }
        if (forkHeight < GenesisBlock.GENESIS_ID) {
            return Result.INCOMPATIBLE; // genesis mismatch: different network
        }

        if (forkHeight == engine.height()) {
            return applyRange(peer, forkHeight + 1, peer.height()) ? Result.EXTENDED : Result.PEER_INVALID;
        }
        return reorg(peer, forkHeight);
    }

    /**
     * Highest height at which our block and the peer's agree. Block-locator search — exponential
     * probes down from the shared tip to bracket the fork, then a binary search inside — so it costs
     * O(log height) peer round-trips instead of one per block (audit M6). This fallback path fetches
     * a FULL block per probe (peer.blockHash → GET /block, up to ~1 MiB), so the linear walk let a
     * peer that 404s /headers (forcing this fallback) tie up a sync thread for height×latency by
     * never matching; the logarithmic locator caps that at ~O(log height) fetches. Agreement is
     * monotonic on a coherent chain (blocks match up to the fork and diverge after), which makes the
     * search exact — the same locator HeaderSynchronizer uses.
     */
    private long findCommonAncestor(PeerSource peer) {
        long top = Math.min(engine.height(), peer.height());
        if (top < GenesisBlock.GENESIS_ID) {
            return GenesisBlock.GENESIS_ID - 1;
        }
        if (agrees(peer, top)) {
            return top; // peer simply extends our chain
        }
        // Phase 1: exponential backoff to bracket the fork between a known match (low) and a
        // known mismatch (high).
        long high = top;   // known mismatch
        long low = -1;     // known match (none yet)
        long step = 1;
        long h = top - 1;
        int probes = 0;
        while (h >= GenesisBlock.GENESIS_ID && probes < MAX_ANCESTOR_PROBES) {
            probes++;
            if (agrees(peer, h)) {
                low = h;
                break;
            }
            high = h;
            if (h == GenesisBlock.GENESIS_ID) {
                break; // genesis itself differs: no common block, not even genesis
            }
            long next = h - step;
            step <<= 1;
            h = Math.max(next, GenesisBlock.GENESIS_ID);
        }
        if (low < 0) {
            return GenesisBlock.GENESIS_ID - 1; // no common block, not even genesis
        }
        // Phase 2: binary search for the highest match in (low, high).
        while (high - low > 1) {
            long mid = low + (high - low) / 2;
            if (agrees(peer, mid)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private boolean agrees(PeerSource peer, long h) {
        return engine.blockAt(h).hash().equals(peer.blockHash(h));
    }

    private boolean applyRange(PeerSource peer, long from, long to) {
        for (long start = from; start <= to; start += Constants.BLOCKS_PER_FETCH) {
            long end = Math.min(to, start + Constants.BLOCKS_PER_FETCH - 1);
            for (Block block : peer.blocks(start, end)) {
                if (engine.addBlock(block) != ExecutionStatus.SUCCESS) {
                    return false;
                }
            }
        }
        return true;
    }

    private Result reorg(PeerSource peer, long forkHeight) {
        long depth = engine.height() - forkHeight;
        if (depth > engine.params().maxReorgDepth()) {
            return Result.REORG_TOO_DEEP;
        }

        // --- Stateless gate: no local mutation until the peer has PROVEN more work ---
        long prefetchEnd = Math.min(peer.height(), forkHeight + depth + PREFETCH_EXTRA);
        List<Block> branch = fetchRange(peer, forkHeight + 1, prefetchEnd);
        if (branch == null || !branchChainsFromFork(branch, forkHeight)) {
            return Result.PEER_INVALID;
        }
        if (verifiedWork(branch).compareTo(localWorkAboveFork(forkHeight)) <= 0) {
            // Claimed heavy, proved light: a structurally valid branch that merely loses the fork race
            // is not a protocol violation — return NO_CHANGE so an honest total-heavier/base-lighter
            // peer is not banned on the first strike (audit 5th-pass, reorg-gate metric).
            return Result.NO_CHANGE;
        }

        // --- Stateful apply, with exact restore on failure ---
        // The whole pop→apply→(restore|adopt) sequence runs under ONE engine-lock hold via
        // withConsistentView. Each ChainEngine call is individually locked, but a reorg is a
        // multi-call sequence, and the block producer (its own thread) and /submit (the event loop)
        // both call engine.addBlock at engine.height()+1 — exactly where we pop to and re-apply. An
        // interleaved add between our pop and re-apply collided on block-id continuity and, worst case,
        // made restore() throw "full resync required", silently truncating the chain to forkHeight
        // (the exception is swallowed by the per-peer syncRound guard). Holding the lock across the
        // sequence restores the single-writer guarantee (WHITEPAPER §3.5/§4.9); the branch is already
        // prefetched, so no network I/O happens under the lock (audit: reorg atomicity). The reentrant
        // lock lets the inner pop/add/restore calls re-acquire it freely.
        Result outcome = engine.withConsistentView(() -> {
            // Uncle-inclusive chain weight before we touch anything — the authoritative GHOST metric (§3.7).
            BigInteger localTotal = engine.totalWork();
            List<Block> localBranch = new ArrayList<>();
            for (long h = forkHeight + 1; h <= engine.height(); h++) {
                localBranch.add(engine.blockAt(h));
            }
            while (engine.height() > forkHeight) {
                engine.popBlock();
            }

            for (Block block : branch) {
                if (engine.addBlock(block) != ExecutionStatus.SUCCESS) {
                    restore(forkHeight, localBranch);
                    return Result.PEER_INVALID;
                }
            }
            // GHOST fork choice (§3.7, audit S4): the base-only gate above is the anti-DoS prefilter;
            // the authoritative choice, now that the bodies applied and addBlock validated every uncle
            // ref, weights genuine uncle work via engine.totalWork(). Adopt the peer branch only if its
            // uncle-inclusive total strictly exceeds ours — a base-heavier but subtree-lighter branch must
            // not displace our heavier GHOST subtree. Validated uncle work only, so no M4 inflation lever.
            if (engine.totalWork().compareTo(localTotal) <= 0) {
                restore(forkHeight, localBranch);
                return Result.NO_CHANGE;
            }
            // The branch we just replaced is valid work that lost the fork race; keep its
            // blocks as orphans so a later block can reference them as uncles (GHOST).
            for (Block block : localBranch) {
                engine.registerOrphan(block);
            }
            return Result.REORGED;
        });
        if (outcome == Result.REORGED) {
            // Branch prefix applied and (by the gate) already heavier than what it replaced; keep
            // extending toward the peer tip, best effort. Network I/O, so deliberately OUTSIDE the lock —
            // each applyRange addBlock is individually locked and an interleaved peer/producer block just
            // ends the best-effort extension early.
            applyRange(peer, prefetchEnd + 1, peer.height());
        }
        return outcome;
    }

    /** Fetches [from..to] in bounded batches; null on any transport/decode failure. */
    private List<Block> fetchRange(PeerSource peer, long from, long to) {
        List<Block> out = new ArrayList<>();
        try {
            for (long start = from; start <= to; start += Constants.BLOCKS_PER_FETCH) {
                long end = Math.min(to, start + Constants.BLOCKS_PER_FETCH - 1);
                out.addAll(peer.blocks(start, end));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    /** Stateless: ids contiguous from the fork, hashes chain, every PoW nonce holds. */
    private boolean branchChainsFromFork(List<Block> branch, long forkHeight) {
        if (branch.isEmpty()) {
            return false;
        }
        SHA256Hash prevHash = engine.blockAt(forkHeight).hash();
        long expectedId = forkHeight + 1;
        for (Block block : branch) {
            var b = (BlockImpl) block;
            if (b.id() != expectedId || !b.lastBlockHash().equals(prevHash)
                || !block.verifyNonce(engine.params().powAlgorithm())) {
                return false;
            }
            prevHash = block.hash();
            expectedId++;
        }
        return true;
    }

    /**
     * Verified proof-of-work above the fork for the peer's branch. Counts each block's own
     * {@code 2^difficulty} ONLY — deliberately NOT the uncle work (audit M4). A branch block's uncle
     * refs are unverified at this stateless stage (validateUncles runs only during addBlock, after
     * the pop), so an attacker who mined only ~1/3 of the honest work could pad each branch block
     * with in-range fake uncle refs, inflate claimed work ~3×, pass this gate, and force an expensive
     * pop/restore cycle before the fakes are finally rejected. Comparing only PoW-verified base work
     * on both sides removes the inflation lever; a branch that is genuinely heavier has more base
     * work in every practical case, and the authoritative GHOST accounting (with validated uncle
     * work) still runs in the engine once the branch is applied.
     */
    private BigInteger verifiedWork(List<Block> branch) {
        BigInteger work = BigInteger.ZERO;
        for (Block block : branch) {
            work = work.add(BlockWork.of(((BlockImpl) block).difficulty()));
        }
        return work;
    }

    /** Local PoW above the fork, base work only — the symmetric counterpart of {@link #verifiedWork}. */
    private BigInteger localWorkAboveFork(long forkHeight) {
        BigInteger work = BigInteger.ZERO;
        for (long h = forkHeight + 1; h <= engine.height(); h++) {
            work = work.add(BlockWork.of(((BlockImpl) engine.blockAt(h)).difficulty()));
        }
        return work;
    }

    private void restore(long forkHeight, List<Block> localBranch) {
        while (engine.height() > forkHeight) {
            engine.popBlock();
        }
        for (Block block : localBranch) {
            // restoreBlock (not addBlock): these blocks were canonical here, so their uncle refs were
            // already fully validated; re-checking them against the orphan pool would let a hostile
            // peer that churned the pool (evicting a referenced uncle) turn a rejected reorg into a
            // forced full resync (audit V5). Any OTHER failure is still a genuine invariant breach.
            ExecutionStatus status = engine.restoreBlock(block);
            if (status != ExecutionStatus.SUCCESS) {
                // Re-adding a just-canonical block must otherwise succeed; a failure would silently
                // leave the node permanently shorter. Fail loud so a full resync recovers the suffix
                // instead of continuing truncated (audit: restore self-truncation).
                throw new IllegalStateException("failed to restore local branch at "
                    + ((BlockImpl) block).id() + " after a rejected reorg: " + status
                    + " — a full resync is required");
            }
        }
    }
}
