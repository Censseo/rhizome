package rhizome.core.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

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
        if (peer.totalWork().compareTo(engine.totalWork()) <= 0) {
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
        long forkHeight = findCommonAncestor(peer); // first call touches peer.headers → may fall back
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
            return ChainSynchronizer.Result.PEER_INVALID; // claimed heavy, proved light
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

    private static rhizome.core.crypto.SHA256Hash peerHeaderHash(PeerSource peer, long h) {
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
        for (long start = forkHeight + 1; start <= to; start += Constants.BLOCKS_PER_FETCH) {
            long end = Math.min(to, start + Constants.BLOCKS_PER_FETCH - 1);
            List<Block> blocks;
            try {
                blocks = peer.blocks(start, end);
            } catch (RuntimeException e) {
                return false;
            }
            for (Block block : blocks) {
                long idx = ((BlockImpl) block).id() - forkHeight - 1;
                if (idx < 0 || idx >= branch.size()
                    || !block.hash().equals(branch.get((int) idx).hash())) {
                    return false; // body does not match its validated header
                }
                if (engine.addBlock(block) != ExecutionStatus.SUCCESS) {
                    return false;
                }
            }
        }
        return true;
    }

    private ChainSynchronizer.Result reorg(PeerSource peer, long forkHeight, List<BlockHeader> branch) {
        List<Block> localBranch = new ArrayList<>();
        for (long h = forkHeight + 1; h <= engine.height(); h++) {
            localBranch.add(engine.blockAt(h));
        }
        while (engine.height() > forkHeight) {
            engine.popBlock();
        }
        if (!applyBodies(peer, forkHeight, branch)) {
            restore(forkHeight, localBranch);
            return ChainSynchronizer.Result.PEER_INVALID;
        }
        // The branch we replaced is valid work that lost the fork race; keep its blocks as
        // orphans so a later block can reference them as uncles (GHOST).
        for (Block block : localBranch) {
            engine.registerOrphan(block);
        }
        return ChainSynchronizer.Result.REORGED;
    }

    private void restore(long forkHeight, List<Block> localBranch) {
        while (engine.height() > forkHeight) {
            engine.popBlock();
        }
        for (Block block : localBranch) {
            ExecutionStatus status = engine.addBlock(block);
            if (status != ExecutionStatus.SUCCESS) {
                // Re-adding a block that was canonical moments ago must succeed. A failure means an
                // uncle it references was evicted from the (bounded) orphan pool — e.g. an attacker
                // flooded the pool with fresh siblings to knock it out, then forced this failed
                // reorg — so validateUncles now rejects our own block. Swallowing the failure would
                // leave the node permanently shorter than it started, silently (audit: restore
                // self-truncation). Fail loud instead so it is caught and a full resync recovers the
                // suffix, rather than continuing in a silently-truncated state.
                throw new IllegalStateException("failed to restore local branch at "
                    + ((BlockImpl) block).id() + " after a rejected reorg: " + status
                    + " — orphan pool likely evicted a referenced uncle; a full resync is required");
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
            work = work.add(BigInteger.TWO.pow(engine.headerAt(h).difficulty()));
        }
        return work;
    }
}
