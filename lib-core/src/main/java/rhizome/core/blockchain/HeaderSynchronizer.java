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

        // --- Bodies: fetch, verify each against its validated header, apply ---
        if (forkHeight == engine.height()) {
            return applyBodies(peer, forkHeight, branch)
                ? ChainSynchronizer.Result.EXTENDED : ChainSynchronizer.Result.PEER_INVALID;
        }
        return reorg(peer, forkHeight, branch);
    }

    /** Highest height at which our header and the peer's agree, walking down from the shared tip. */
    private long findCommonAncestor(PeerSource peer) {
        long h = Math.min(engine.height(), peer.height());
        while (h >= GenesisBlock.GENESIS_ID) {
            if (engine.headerAt(h).hash().equals(peerHeaderHash(peer, h))) {
                return h;
            }
            h--;
        }
        return GenesisBlock.GENESIS_ID - 1; // no common block, not even genesis
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
            engine.addBlock(block);
        }
    }

    private BigInteger localWorkAboveFork(long forkHeight) {
        BigInteger work = BigInteger.ZERO;
        for (long h = forkHeight + 1; h <= engine.height(); h++) {
            work = work.add(BigInteger.TWO.pow(engine.headerAt(h).difficulty()));
            for (var uncle : engine.headerAt(h).uncles()) {
                work = work.add(BigInteger.TWO.pow(uncle.difficulty()));
            }
        }
        return work;
    }
}
