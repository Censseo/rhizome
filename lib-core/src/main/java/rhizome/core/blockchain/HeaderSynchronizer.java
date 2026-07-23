package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import rhizome.core.block.Block;
import rhizome.core.block.BlockHeader;
import rhizome.core.block.BlockImpl;
import rhizome.core.common.Constants;
import rhizome.core.mempool.ExecutionStatus;

/**
 * Headers-first synchroniser. It subsumes {@link ChainSynchronizer}: before any
 * local state is touched, it downloads and validates the peer's <em>headers</em>
 * over the contested range ({@link HeaderChain}) and requires their proven
 * cumulative work to strictly exceed our own branch. A peer that merely
 * <em>claims</em> huge work therefore costs us a bounded header download
 * (~150 B/block) instead of full blocks (up to 4 MiB each) — the anti-DoS gate,
 * far cheaper than before. Only once the headers prove the work do we download
 * bodies, each verified against its validated header before execution.
 *
 * <p>A peer that predates the {@code /headers} endpoint makes {@link PeerSource#headers}
 * throw {@link UnsupportedOperationException}; the synchroniser transparently
 * falls back to the full-block {@link ChainSynchronizer} for that peer (D7).
 */
public final class HeaderSynchronizer {

    /** Max heights advanced in one round, so a hostile "I'm at height 10^9" peer costs a bounded download. */
    static final long MAX_HEADER_WINDOW = 20_000;

    /** Hard cap on exponential-probe steps in the ancestor search, so it stays O(log height). */
    private static final int MAX_ANCESTOR_PROBES = 64;

    private final ChainEngine engine;
    private final ChainSynchronizer fallback;

    public HeaderSynchronizer(ChainEngine engine) {
        this.engine = engine;
        this.fallback = new ChainSynchronizer(engine);
    }

    public ChainSynchronizer.Result syncFrom(PeerSource peer) {
        // Prefilter against our BASE work, not our uncle-inclusive total. The adoption gate below
        // ranks branches by base-only work (localWorkAboveFork, the M4 rule); ranking this early-out
        // by the uncle-inflated total instead let a node with heavy local uncle work refuse to even
        // look at a peer whose base work would win adoption — the two gates disagreeing produced a
        // stable partition after a healed split (audit 5th-pass, reorg-gate metric). peer.totalWork()
        // is self-reported but is an upper bound on the peer's base work, so skipping only when it
        // cannot beat our base work never skips a peer the adoption gate would accept — and a peer that
        // over-reports still can't force a pop/restore, which stays gated on validated base work.
        if (peer.totalWork().compareTo(engine.baseWork()) <= 0) {
            return ChainSynchronizer.Result.NO_CHANGE;
        }
        try {
            return headersFirstSync(peer);
        } catch (UnsupportedOperationException headersUnsupported) {
            // Older peer without /headers: fall back to the full-block path.
            return fallback.syncFrom(peer);
        }
    }

    private ChainSynchronizer.Result headersFirstSync(PeerSource peer) {
        long forkHeight;
        try {
            forkHeight = findCommonAncestor(peer); // first call touches peer.headers → may fall back
        } catch (UnsupportedOperationException headersUnsupported) {
            throw headersUnsupported; // peer lacks /headers: let syncFrom fall back to full blocks
        } catch (RuntimeException e) {
            // A peer that throws or returns an empty/garbage response while we probe for the common
            // ancestor is invalid, exactly like the fetch phases below — it must not propagate out of
            // the sync pass (audit V6c). (UnsupportedOperationException is re-thrown above.)
            return ChainSynchronizer.Result.PEER_INVALID;
        }
        if (forkHeight < GenesisBlock.GENESIS_ID) {
            return ChainSynchronizer.Result.INCOMPATIBLE; // genesis mismatch: different network
        }
        long depth = engine.height() - forkHeight;
        if (depth > engine.params().maxReorgDepth()) {
            return ChainSynchronizer.Result.REORG_TOO_DEEP;
        }

        long windowEnd = Math.min(peer.height(), forkHeight + MAX_HEADER_WINDOW);
        if (windowEnd <= forkHeight) {
            return ChainSynchronizer.Result.NO_CHANGE;
        }

        // --- Header gate: no local mutation until the peer has PROVEN more work in valid headers ---
        List<BlockHeader> branch = fetchHeaders(peer, forkHeight + 1, windowEnd);
        if (branch == null) {
            return ChainSynchronizer.Result.PEER_INVALID;
        }
        HeaderChain.Result validated =
            HeaderChain.validate(engine.params(), engine::headerAt, forkHeight, branch, engine.nowMillis());
        if (!validated.valid()) {
            return ChainSynchronizer.Result.PEER_INVALID;
        }
        if (validated.work().compareTo(localWorkAboveFork(forkHeight)) <= 0) {
            // Claimed heavy, proved light: the branch is structurally valid (PoW/continuity/difficulty
            // all held) but not base-heavier, so it simply loses the fork race — NOT a protocol
            // violation. Returning PEER_INVALID here banned honest total-heavier/base-lighter peers on
            // the first strike, entrenching a split; NO_CHANGE leaves the peer connected (audit 5th-pass).
            return ChainSynchronizer.Result.NO_CHANGE;
        }

        // The headers prove the peer is heavier, but if it has pruned the bodies we need there
        // is nothing to download here — leave it for an archive peer rather than banning it.
        if (forkHeight + 1 < peer.prunedBelow()) {
            return ChainSynchronizer.Result.PEER_PRUNED;
        }

        // --- Bodies: fetch, verify each against its validated header, apply ---
        if (forkHeight == engine.height()) {
            return applyBodies(peer, forkHeight, branch)
                ? ChainSynchronizer.Result.EXTENDED : ChainSynchronizer.Result.PEER_INVALID;
        }
        return reorg(peer, forkHeight, branch);
    }

    /**
     * Highest height at which our header and the peer's agree. Uses a block-locator style
     * search — exponential probes down from the shared tip to bracket the fork, then a binary
     * search inside the bracket — so it costs O(log height) peer round-trips instead of one per
     * block. A slow/hostile peer can therefore no longer tie up the sync thread for
     * height×timeout by never matching (audit M2). Agreement is monotonic on a coherent chain
     * (headers match up to the fork and diverge after), which is what makes the search exact.
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
            return GenesisBlock.GENESIS_ID - 1;
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
        return engine.headerAt(h).hash().equals(peerHeaderHash(peer, h));
    }

    private static rhizome.crypto.SHA256Hash peerHeaderHash(PeerSource peer, long h) {
        List<BlockHeader> one = peer.headers(h, h);
        if (one.isEmpty()) {
            throw new IllegalStateException("peer returned no header at " + h);
        }
        return one.get(0).hash();
    }

    /** Fetches headers [from..to] in bounded batches; null on any transport/decode failure. */
    private List<BlockHeader> fetchHeaders(PeerSource peer, long from, long to) {
        List<BlockHeader> out = new ArrayList<>();
        try {
            for (long start = from; start <= to; start += Constants.BLOCK_HEADERS_PER_FETCH) {
                long end = Math.min(to, start + Constants.BLOCK_HEADERS_PER_FETCH - 1);
                out.addAll(peer.headers(start, end));
            }
        } catch (UnsupportedOperationException e) {
            throw e; // let syncFrom fall back to full-block sync
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    private boolean applyBodies(PeerSource peer, long forkHeight, List<BlockHeader> branch) {
        long to = forkHeight + branch.size();
        List<long[]> windows = new ArrayList<>();
        for (long start = forkHeight + 1; start <= to; start += Constants.BLOCKS_PER_FETCH) {
            windows.add(new long[] {start, Math.min(to, start + Constants.BLOCKS_PER_FETCH - 1)});
        }
        if (windows.isEmpty()) {
            return true;
        }
        // Pipeline the body download: while the current window's blocks are applied to the engine, the
        // NEXT window is fetched over the network on a helper thread. Application stays strictly serial
        // and in order (the engine is single-writer), so the applied sequence — and thus every consensus
        // outcome and the state root — is byte-for-byte identical; only the network I/O of window K+1
        // overlaps the disk/CPU apply of window K. Exactly one fetch is ever outstanding, so the peer
        // source is still used from one thread at a time.
        ExecutorService fetcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rhizome-body-fetch");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<List<Block>> pending = submitFetch(fetcher, peer, windows.get(0));
            for (int i = 0; i < windows.size(); i++) {
                List<Block> blocks;
                try {
                    blocks = pending.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (ExecutionException e) {
                    return false; // transport/decode failure fetching this window (was a RuntimeException)
                }
                // Start the next window's fetch before applying the current one, so the two overlap.
                if (i + 1 < windows.size()) {
                    pending = submitFetch(fetcher, peer, windows.get(i + 1));
                }
                for (Block block : blocks) {
                    long idx = ((BlockImpl) block).id() - forkHeight - 1;
                    if (idx < 0 || idx >= branch.size()
                        || !block.hash().equals(branch.get((int) idx).hash())) {
                        return false; // body does not match its validated header
                    }
                    // The header at this index was PoW-verified by HeaderChain.validate and the body's
                    // hash equals it (checked just above), so the body's work is already proven — apply
                    // it without re-running the memory-hard PoW hash (audit P4). Every other check runs.
                    if (engine.addValidatedBody(block) != ExecutionStatus.SUCCESS) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            // Cancel any still-running prefetch (e.g. after a mismatch/failure returned early) and free
            // the helper thread. The fetch is read-only network I/O, so a discarded result changes nothing.
            fetcher.shutdownNow();
        }
    }

    private static Future<List<Block>> submitFetch(ExecutorService fetcher, PeerSource peer, long[] window) {
        return fetcher.submit(() -> peer.blocks(window[0], window[1]));
    }

    private ChainSynchronizer.Result reorg(PeerSource peer, long forkHeight, List<BlockHeader> branch) {
        // Unlike ChainSynchronizer's small bounded reorg, the header path applies up to
        // MAX_HEADER_WINDOW bodies with interleaved network I/O (applyBodies pipelines fetch+apply), so
        // the whole sequence cannot run under the engine lock — that would stall every lock-guarded API
        // read and the producer for the entire multi-thousand-block download. Instead the two MUTATION
        // phases each run atomically under withConsistentView, with the I/O apply between them holding no
        // lock. The block producer (own thread) and /submit (event loop) both add at engine.height()+1 —
        // where we pop to — so an interleave during the forward apply just fails an addValidatedBody and
        // drops us to the restore path; making capture+pop and restore/adopt each atomic means restore
        // re-adds the local branch with no interleave and so can never throw "full resync required" and
        // silently truncate the chain (audit: reorg atomicity). A forward-apply race is thus self-healing:
        // the reorg aborts, the original tip is restored intact, and the next round retries.

        // Phase 1 — capture the pre-reorg GHOST weight and local branch, then pop, as one atomic step.
        List<Block> localBranch = new ArrayList<>();
        BigInteger localTotal = engine.withConsistentView(() -> {
            BigInteger total = engine.totalWork();
            for (long h = forkHeight + 1; h <= engine.height(); h++) {
                localBranch.add(engine.blockAt(h));
            }
            while (engine.height() > forkHeight) {
                engine.popBlock();
            }
            return total;
        });

        // Phase 2 — fetch and apply the peer bodies. Network I/O, so deliberately OUTSIDE the lock.
        boolean applied = applyBodies(peer, forkHeight, branch);

        // Phase 3 — adopt or restore, atomically so restore cannot race a producer/submit add.
        return engine.withConsistentView(() -> {
            if (!applied) {
                restore(forkHeight, localBranch);
                return ChainSynchronizer.Result.PEER_INVALID;
            }
            // GHOST fork choice (§3.7, audit S4). The base-only header gate is the anti-DoS PREFILTER —
            // base-only because a header's claimed uncle work is unverifiable and could otherwise be
            // inflated with fake in-range refs to force a pop/restore (M4). The AUTHORITATIVE choice, made
            // here after the bodies applied, weights genuine uncle work: engine.totalWork() now folds in
            // each uncle addBlock proved eligible. Adopt only if the peer's uncle-inclusive total strictly
            // exceeds ours — a branch with more BASE work but a lighter subtree must not displace the
            // heavier subtree. The uncle work counted here is validated, not the gate's claim, so no M4 lever.
            if (engine.totalWork().compareTo(localTotal) <= 0) {
                restore(forkHeight, localBranch);
                return ChainSynchronizer.Result.NO_CHANGE;
            }
            // The branch we replaced is valid work that lost the fork race; keep its blocks as
            // orphans so a later block can reference them as uncles (GHOST).
            for (Block block : localBranch) {
                engine.registerOrphan(block);
            }
            return ChainSynchronizer.Result.REORGED;
        });
    }

    private void restore(long forkHeight, List<Block> localBranch) {
        while (engine.height() > forkHeight) {
            engine.popBlock();
        }
        for (Block block : localBranch) {
            // restoreBlock trusts our own already-validated uncle refs instead of re-checking them
            // against the orphan pool: an attacker who flooded the pool with fresh siblings to evict a
            // referenced uncle, then forced this failed reorg, can no longer turn it into a forced full
            // resync (audit V5). Uncle work/rewards derive from the committed refs, so restoration is
            // exact. Any other failure remains a genuine invariant breach and still fails loud below.
            ExecutionStatus status = engine.restoreBlock(block);
            if (status != ExecutionStatus.SUCCESS) {
                // Re-adding a block that was canonical moments ago must otherwise succeed. Swallowing a
                // failure would leave the node permanently shorter than it started, silently (audit:
                // restore self-truncation). Fail loud instead so it is caught and a full resync
                // recovers the suffix, rather than continuing in a silently-truncated state.
                throw new IllegalStateException("failed to restore local branch at "
                    + ((BlockImpl) block).id() + " after a rejected reorg: " + status
                    + " — a full resync is required");
            }
        }
    }

    private BigInteger localWorkAboveFork(long forkHeight) {
        // Base work only, matching HeaderChain's base-only branch total: the reorg gate compares
        // like with like and never lets unverifiable committed uncle work (on either side) drive a
        // pop/restore decision (audit M4). Uncle work still decides true fork choice via
        // engine.totalWork() once the bodies validate.
        BigInteger work = BigInteger.ZERO;
        for (long h = forkHeight + 1; h <= engine.height(); h++) {
            work = work.add(BlockWork.of(engine.headerAt(h).difficulty()));
        }
        return work;
    }
}
